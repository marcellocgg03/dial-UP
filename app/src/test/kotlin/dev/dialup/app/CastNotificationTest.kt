package dev.dialup.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CastNotificationTest {
    private class FakeSource(initial: CastCount) : CastCountSource {
        val state = MutableStateFlow(initial)
        override val castCount: StateFlow<CastCount> get() = state
    }

    @Test
    fun `text reads casting of total speakers being cast to`() {
        assertEquals("1 renderer being cast to", notificationText(CastCount(1, 4)))
        assertEquals("2 renderers being cast to", notificationText(CastCount(2, 4)))
    }

    @Test
    fun `while casting without a network the text says it is waiting`() {
        assertEquals(NOTIFICATION_WAITING_TEXT, notificationText(CastCount(1, 3), waiting = true))
        assertEquals("Waiting for Wi-Fi", NOTIFICATION_WAITING_TEXT)
        assertEquals("1 renderer being cast to", notificationText(CastCount(1, 3), waiting = false))
    }

    @Test
    fun `an idle bridge without a network keeps the count text`() {
        assertEquals("0 renderers being cast to", notificationText(CastCount(0, 3), waiting = true))
    }

    @Test
    fun `no speakers reads 0 of 0`() {
        assertEquals("0 renderers being cast to", notificationText(CastCount(0, 0)))
    }

    @Test
    fun `an empty source list emits 0 of 0 first`() = runBlocking {
        assertEquals(CastCount(0, 0), sumCastCounts(emptyList()).first())
    }

    @Test
    fun `two sources sum to 1 of 5`() = runBlocking {
        val a = FakeSource(CastCount(1, 2))
        val b = FakeSource(CastCount(0, 3))
        assertEquals(CastCount(1, 5), sumCastCounts(listOf(a, b)).first())
    }

    @Test
    fun `changing the first source re-emits the new sum 0 of 5`() = runBlocking {
        val a = FakeSource(CastCount(1, 2))
        val b = FakeSource(CastCount(0, 3))
        val seen = async(Dispatchers.Default) { sumCastCounts(listOf(a, b)).take(2).toList() }
        delay(150)
        a.state.value = CastCount(0, 2)
        assertEquals(listOf(CastCount(1, 5), CastCount(0, 5)), withTimeout(5_000) { seen.await() })
    }

    @Test
    fun `setting a source to an equal value emits nothing new`() = runBlocking {
        val a = FakeSource(CastCount(1, 2))
        val b = FakeSource(CastCount(0, 3))
        val seen = java.util.concurrent.CopyOnWriteArrayList<CastCount>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            sumCastCounts(listOf(a, b)).collect { seen.add(it) }
        }
        delay(150)
        a.state.value = CastCount(1, 2) // equal value, StateFlow drops it
        b.state.value = CastCount(0, 3) // equal value
        delay(150)
        job.cancel()
        assertEquals(listOf(CastCount(1, 5)), seen.toList())
    }

    @Test
    fun `parts moving while the sum stays equal emits nothing new (distinctUntilChanged)`() = runBlocking {
        val a = FakeSource(CastCount(1, 2))
        val b = FakeSource(CastCount(0, 3))
        val seen = java.util.concurrent.CopyOnWriteArrayList<CastCount>()
        val job = launch(Dispatchers.Default) { sumCastCounts(listOf(a, b)).collect { seen.add(it) } }
        delay(150)
        a.state.value = CastCount(0, 2)
        b.state.value = CastCount(1, 3) // (0,2)+(1,3) = (1,5) again
        delay(150)
        job.cancel()
        // may pass through (0,5) depending on interleaving, but never repeats an equal neighbour
        assertEquals(seen.toList(), seen.toList().fold(emptyList<CastCount>()) { acc, c -> if (acc.lastOrNull() == c) acc else acc + c })
        assertEquals(CastCount(1, 5), seen.last())
    }
}
