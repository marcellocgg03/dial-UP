// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.ManualSpeaker
import dev.t2u.renderer.PinnedSpeaker
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
    }

    @Test
    fun `paused offers play and stop`() {
        val b = buttonsFor(speaker(PlayerStatus.PAUSED, aTrack), busy = false)
        assertEquals(PlayPause.PLAY, b.playPause)
        assertTrue(b.stop)
    }

    @Test
    fun `stopped with a current queue item or a track offers play and stop`() {
        val withQueue = buttonsFor(speaker(PlayerStatus.STOPPED, queueHasCurrent = true), busy = false)
        assertEquals(PlayPause.PLAY, withQueue.playPause)
        assertTrue(withQueue.stop)
        val withTrack = buttonsFor(speaker(PlayerStatus.STOPPED, aTrack), busy = false)
        assertEquals(PlayPause.PLAY, withTrack.playPause)
        assertTrue(withTrack.stop)
    }

    @Test
    fun `stopped with neither and idle offer no play or stop`() {
        val stopped = buttonsFor(speaker(PlayerStatus.STOPPED), busy = false)
        assertEquals(PlayPause.DISABLED, stopped.playPause)
        assertEquals(false, stopped.stop)
        val idle = buttonsFor(speaker(PlayerStatus.IDLE), busy = false)
        assertEquals(PlayPause.DISABLED, idle.playPause)
        assertEquals(false, idle.stop)
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
        val off = CardButtons(PlayPause.DISABLED, stop = false, previous = false, next = false)
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
        presenter.press("a", SpeakerAction.STOP)
        advanceUntilIdle()
        assertEquals(emptySet(), presenter.busy.value)
    }

    @Test
    fun `saved manual speakers are listed whenever there is no running bridge under an enabled switch`() {
        assertEquals(true, savedManualVisible(switchOn = true, serviceUp = false, bridgeUp = false))
        assertEquals(true, savedManualVisible(switchOn = true, serviceUp = true, bridgeUp = false))
        assertEquals(false, savedManualVisible(switchOn = true, serviceUp = true, bridgeUp = true))
        // switch off: listed whatever the old service and bridge are still doing
        assertEquals(true, savedManualVisible(switchOn = false, serviceUp = true, bridgeUp = true))
        assertEquals(true, savedManualVisible(switchOn = false, serviceUp = true, bridgeUp = false))
        assertEquals(true, savedManualVisible(switchOn = false, serviceUp = false, bridgeUp = false))
    }

    private fun card(udn: String, name: String = udn, online: Boolean = true, manual: Boolean = false) = speaker(PlayerStatus.IDLE)
        .copy(udn = udn, name = name, online = online, manual = manual)

    private fun saved(udn: String, name: String = udn) = ManualSpeaker(ip = "10.0.0.9", udn = udn, name = name, location = "http://10.0.0.9:1400/d.xml")

    private val discovered = card("A")
    private val savedLive = card("M", manual = true)
    private val allSaved = listOf(saved("M"), saved("S2"), saved("H"))

    private fun listing(
        switchOn: Boolean,
        serviceUp: Boolean,
        bridgeUp: Boolean,
        live: List<SpeakerUi> = listOf(discovered, savedLive),
        savedList: List<ManualSpeaker> = allSaved,
        hidden: Set<String> = setOf("H"),
        names: Map<String, String> = emptyMap(),
        waiting: Boolean = false,
        pinned: List<PinnedSpeaker> = emptyList(),
    ) = rendererListing(switchOn, serviceUp, bridgeUp, waiting, live, savedList, hidden, names, pinned)

    @Test
    fun `switch off with the old service and bridge still up lists only the saved greyed cards`() {
        val l = listing(switchOn = false, serviceUp = true, bridgeUp = true)
        assertEquals(emptyList(), l.live)
        assertEquals(listOf("M", "S2"), l.saved.map { it.udn })
        assertTrue(l.saved.all { !it.online && it.manual })
        assertEquals(null, l.note)
    }

    @Test
    fun `switch off after the service went lists the same saved cards`() {
        val l = listing(switchOn = false, serviceUp = false, bridgeUp = false, live = emptyList())
        assertEquals(emptyList(), l.live)
        assertEquals(listOf("M", "S2"), l.saved.map { it.udn })
        assertEquals(null, l.note)
    }

    @Test
    fun `a saved renderer that is also discovered shows once as its saved card while the switch is off`() {
        val l = listing(switchOn = false, serviceUp = true, bridgeUp = true)
        val all = l.live + l.saved
        assertEquals(1, all.count { it.udn == "M" })
        assertEquals(false, all.single { it.udn == "M" }.online)
    }

    @Test
    fun `a hidden saved renderer stays out and a user name shows on the greyed card`() {
        val l = listing(switchOn = false, serviceUp = false, bridgeUp = false, savedList = listOf(saved("M"), saved("H")), names = mapOf("M" to "Kitchen"))
        assertEquals(listOf("M"), l.saved.map { it.udn })
        assertEquals("Kitchen", l.saved.single().name)
    }

    @Test
    fun `switch back on with a bridge shows the bridge list unchanged and no saved cards`() {
        val live = listOf(discovered, savedLive)
        val l = listing(switchOn = true, serviceUp = true, bridgeUp = true, live = live)
        assertEquals(live, l.live)
        assertEquals(emptyList(), l.saved)
        assertEquals(null, l.note)
    }

    @Test
    fun `switch on with a bridge and nothing found says looking`() {
        val l = listing(switchOn = true, serviceUp = true, bridgeUp = true, live = emptyList())
        assertEquals(emptyList(), l.live)
        assertEquals(emptyList(), l.saved)
        assertEquals(ListNote.LOOKING, l.note)
    }

    @Test
    fun `switch on with the service up but no bridge drops a stale live list and lists the saved card once`() {
        val l = listing(switchOn = true, serviceUp = true, bridgeUp = false, live = listOf(savedLive), savedList = listOf(saved("M")))
        assertEquals(emptyList(), l.live)
        assertEquals(listOf("M"), l.saved.map { it.udn })
        assertEquals(ListNote.LOOKING, l.note)
    }

    @Test
    fun `switch on but the service not up yet lists the saved cards and says looking`() {
        val l = listing(switchOn = true, serviceUp = false, bridgeUp = false, live = emptyList())
        assertEquals(emptyList(), l.live)
        assertEquals(listOf("M", "S2"), l.saved.map { it.udn })
        assertEquals(ListNote.LOOKING, l.note)
    }

    @Test
    fun `waiting for Wi-Fi lists the saved cards with no note, whether or not the service is up`() {
        for (serviceUp in listOf(true, false)) {
            val l = listing(switchOn = true, serviceUp = serviceUp, bridgeUp = false, live = emptyList(), waiting = true)
            assertEquals(emptyList(), l.live)
            assertEquals(listOf("M", "S2"), l.saved.map { it.udn })
            assertEquals(null, l.note)
        }
    }

    @Test
    fun `the switch off has no note, a starting bridge shows the spinner and a bridge up has no note`() {
        assertEquals(null, listing(switchOn = false, serviceUp = false, bridgeUp = false, live = emptyList()).note)
        assertEquals(ListNote.LOOKING, listing(switchOn = true, serviceUp = true, bridgeUp = false, live = emptyList()).note)
        assertEquals(null, listing(switchOn = true, serviceUp = true, bridgeUp = true).note)
    }

    @Test
    fun `hide is offered on answering cards and on saved renderers that do not answer`() {
        assertTrue(hideOffered(speaker(PlayerStatus.IDLE)))
        assertTrue(hideOffered(speaker(PlayerStatus.IDLE).copy(manual = true)))
        assertTrue(hideOffered(speaker(PlayerStatus.IDLE, online = false).copy(manual = true)))
        assertEquals(false, hideOffered(speaker(PlayerStatus.IDLE, online = false)))
    }

    @Test
    fun `hide asks first only when it would end a cast`() {
        assertTrue(hideEndsCast(speaker(PlayerStatus.PLAYING)))
        assertTrue(hideEndsCast(speaker(PlayerStatus.PAUSED)))
        assertTrue(hideEndsCast(speaker(PlayerStatus.LOADING)))
        assertEquals(false, hideEndsCast(speaker(PlayerStatus.IDLE)))
        assertEquals(false, hideEndsCast(speaker(PlayerStatus.STOPPED)))
        assertEquals(false, hideEndsCast(speaker(PlayerStatus.PLAYING, online = false).copy(manual = true)))
    }

    @Test
    fun `delete is offered only on renderers saved by address, answering or not`() {
        assertTrue(deleteOffered(speaker(PlayerStatus.IDLE).copy(manual = true)))
        assertTrue(deleteOffered(speaker(PlayerStatus.IDLE, online = false).copy(manual = true)))
        assertEquals(false, deleteOffered(speaker(PlayerStatus.IDLE)))
        assertEquals(false, deleteOffered(speaker(PlayerStatus.IDLE, online = false)))
    }

    private fun pin(udn: String) = PinnedSpeaker(udn, "Pinned $udn", "10.0.0.5")

    @Test
    fun `pinned live cards go first and the rest keep their order`() {
        val live = listOf(card("A"), card("B"), card("C"), card("D"))
        val l = listing(switchOn = true, serviceUp = true, bridgeUp = true, live = live, pinned = listOf(pin("D"), pin("B")))
        assertEquals(listOf("B", "D", "A", "C"), l.live.map { it.udn })
    }

    @Test
    fun `with the bridge off a pinned discovered speaker is a greyed card and a pinned saved one stays one card`() {
        val l = listing(switchOn = false, serviceUp = false, bridgeUp = false, live = emptyList(), pinned = listOf(pin("P"), pin("S2")))
        assertEquals(listOf("S2", "P", "M"), l.saved.map { it.udn }, "pinned first, the rest in their order")
        assertEquals(1, l.saved.count { it.udn == "S2" })
        val p = l.saved.first { it.udn == "P" }
        assertEquals("Pinned P", p.name)
        assertTrue(!p.online && !p.manual)
    }

    @Test
    fun `a hidden speaker is not listed even when it is still pinned`() {
        val l = listing(switchOn = false, serviceUp = false, bridgeUp = false, live = emptyList(), pinned = listOf(pin("H")))
        assertTrue(l.saved.none { it.udn == "H" })
    }

    @Test
    fun `a pinned speaker uses the name the author gave it`() {
        val l = listing(switchOn = false, serviceUp = false, bridgeUp = false, live = emptyList(), pinned = listOf(pin("P")), names = mapOf("P" to "Den"))
        assertEquals("Den", l.saved.first { it.udn == "P" }.name)
    }

    @Test
    fun `an unanswering pinned card offers Hide`() {
        val c = card("P", online = false)
        assertTrue(!hideOffered(c))
        assertTrue(hideOffered(c, pinned = true))
    }
}
