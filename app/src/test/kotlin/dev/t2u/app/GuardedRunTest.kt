package dev.t2u.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardedRunTest {
    @Test
    fun runGuardedReturnsTrueWhenTheBlockCompletes() = runBlocking {
        var ran = 0
        var reported = 0
        val ok = runGuarded("start-bridge", { _, _ -> reported++ }) { ran++ }
        assertTrue(ok)
        assertEquals(1, ran)
        assertEquals(0, reported)
    }

    @Test
    fun runGuardedReportsAFailureAndReturnsFalse() = runBlocking {
        val seen = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("boom")
        val ok = runGuarded("start-bridge", { w, t -> seen.add(w to t) }) { throw boom }
        assertEquals(false, ok)
        assertEquals(1, seen.size)
        assertEquals("start-bridge", seen[0].first)
        assertTrue(seen[0].second === boom)
    }

    @Test
    fun runGuardedRethrowsCancellation() {
        var reported = 0
        assertFailsWith<CancellationException> {
            runBlocking { runGuarded("start-bridge", { _, _ -> reported++ }) { throw CancellationException("stop") } }
        }
        assertEquals(0, reported)
    }

    @Test
    fun runGuardedSurvivesAReportThatThrows() = runBlocking {
        val ok = runGuarded("start-bridge", { _, _ -> throw IllegalStateException("log is broken") }) {
            throw IllegalStateException("boom")
        }
        assertEquals(false, ok)
    }

    @Test
    fun featureErrorFieldsCutsTheMessageAt200Characters() {
        val fields = featureErrorFields("start-bridge", IllegalStateException("x".repeat(500)))
        assertEquals("start-bridge", fields["where"])
        assertEquals("java.lang.IllegalStateException", fields["error"])
        assertEquals(200, (fields["message"] as String).length)
        assertNull(featureErrorFields("w", IllegalStateException())["message"])
    }
}
