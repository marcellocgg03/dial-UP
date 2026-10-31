// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.NowPlaying
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakersPresenterTest {
    private fun speaker(
        status: PlayerStatus,
        track: NowPlaying? = null,
        queueHasCurrent: Boolean = false,
        hasPrevious: Boolean = false,
        hasNext: Boolean = false,
        online: Boolean = true,
    ) = SpeakerUi(
        udn = "u", name = "n", address = "10.0.0.2", status = status, track = track, error = null,
        hasPrevious = hasPrevious, hasNext = hasNext, queueHasCurrent = queueHasCurrent, position = null, online = online,
    )

    private val aTrack = NowPlaying("AAAAAAAAAAA", "t", "a", 210.0, "adts", "")

    @Test
    fun `playing offers pause and stop`() {
        val b = buttonsFor(speaker(PlayerStatus.PLAYING, aTrack), busy = false)
        assertEquals(PlayPause.PAUSE, b.playPause)
        assertTrue(b.stop)
        assertTrue(b.clear)
    }

    @Test
    fun `paused offers play and stop`() {
        val b = buttonsFor(speaker(PlayerStatus.PAUSED, aTrack), busy = false)
        assertEquals(PlayPause.PLAY, b.playPause)
        assertTrue(b.stop)
    }

    @Test
    fun `stopped with a current queue item or a track offers play but no stop`() {
        val withQueue = buttonsFor(speaker(PlayerStatus.STOPPED, queueHasCurrent = true), busy = false)
        assertEquals(PlayPause.PLAY, withQueue.playPause)
        assertEquals(false, withQueue.stop)
        val withTrack = buttonsFor(speaker(PlayerStatus.STOPPED, aTrack), busy = false)
        assertEquals(PlayPause.PLAY, withTrack.playPause)
        assertEquals(false, withTrack.stop)
    }

    @Test
    fun `stopped with neither and idle offer no play`() {
        assertEquals(PlayPause.DISABLED, buttonsFor(speaker(PlayerStatus.STOPPED), busy = false).playPause)
        val idle = buttonsFor(speaker(PlayerStatus.IDLE), busy = false)
        assertEquals(PlayPause.DISABLED, idle.playPause)
        assertEquals(false, idle.stop)
        assertTrue(idle.clear, "clear is on whenever the speaker is online")
    }

    @Test
    fun `loading offers stop only`() {
        val b = buttonsFor(speaker(PlayerStatus.LOADING, aTrack), busy = false)
        assertEquals(PlayPause.DISABLED, b.playPause)
        assertTrue(b.stop)
    }

    @Test
    fun `previous and next follow the queue`() {
        val both = buttonsFor(speaker(PlayerStatus.PLAYING, aTrack, hasPrevious = true, hasNext = true), busy = false)
        assertTrue(both.previous && both.next)
        val none = buttonsFor(speaker(PlayerStatus.PLAYING, aTrack), busy = false)
        assertEquals(false, none.previous)
        assertEquals(false, none.next)
        val onlyNext = buttonsFor(speaker(PlayerStatus.PLAYING, aTrack, hasNext = true), busy = false)
        assertEquals(false, onlyNext.previous)
        assertTrue(onlyNext.next)
    }

    @Test
    fun `offline or busy disables every button`() {
        val off = CardButtons(PlayPause.DISABLED, stop = false, previous = false, next = false, clear = false)
        val playing = speaker(PlayerStatus.PLAYING, aTrack, hasPrevious = true, hasNext = true)
        assertEquals(off, buttonsFor(playing.copy(online = false), busy = false))
        assertEquals(off, buttonsFor(playing, busy = true))
    }

    @Test
    fun `playPauseAction maps the button to its action`() {
        assertEquals(SpeakerAction.PLAY, playPauseAction(PlayPause.PLAY))
        assertEquals(SpeakerAction.PAUSE, playPauseAction(PlayPause.PAUSE))
        assertEquals(null, playPauseAction(PlayPause.DISABLED))
    }

    @Test
    fun `a second press for the same speaker is ignored while the first runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, SpeakerAction>>()
        val presenter = SpeakersPresenter(this) { udn, action -> calls.add(udn to action); gate.await(); true }

        presenter.press("a", SpeakerAction.NEXT)
        advanceUntilIdle()
        assertEquals(setOf("a"), presenter.busy.value)
        presenter.press("a", SpeakerAction.NEXT)
        advanceUntilIdle()
        assertEquals(1, calls.size, "the double tap sent one command")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), presenter.busy.value)
        presenter.press("a", SpeakerAction.NEXT)
        advanceUntilIdle()
        assertEquals(2, calls.size, "a press after the command returned runs")
    }

    @Test
    fun `another speaker's press runs in parallel`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val presenter = SpeakersPresenter(this) { udn, _ -> calls.add(udn); gate.await(); true }

        presenter.press("a", SpeakerAction.STOP)
        presenter.press("b", SpeakerAction.STOP)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), calls)
        assertEquals(setOf("a", "b"), presenter.busy.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), presenter.busy.value)
    }

    @Test
    fun `a command that throws still frees the card`() = runTest {
        val presenter = SpeakersPresenter(this) { _, _ -> error("boom") }
        presenter.press("a", SpeakerAction.CLEAR)
        advanceUntilIdle()
        assertEquals(emptySet(), presenter.busy.value)
    }
}
