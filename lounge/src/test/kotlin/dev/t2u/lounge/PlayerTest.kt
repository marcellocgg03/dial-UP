package dev.t2u.lounge

// Tests for Player (port of yt-cast-receiver 2.1.0 lib/Player.js, MIT, Copyright (c) Patrick Kan), GPL-3.0-only.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Player whose do* hooks record their calls and return configurable results. */
class FakePlayer : Player() {
    val calls = CopyOnWriteArrayList<String>()
    var playResult = true
    var pauseResult = true
    var resumeResult = true
    var stopResult = true
    var seekResult = true
    var volumeResult = true
    var volume = Volume(50, false)
    var position = 0.0
    var duration = 200.0
    var lastPlayedPosition: Double? = null
    var lastVolumeSet: Volume? = null

    /** The player's clock (epoch ms); a test moves it by hand. */
    var nowMs: Long = 1_700_000_000_000

    override fun clockMs(): Long = nowMs

    /** True while the fake speaker has not started after a seek (plan 03-20): the player reports buffering. */
    @Volatile var seekHold: Boolean = false

    override fun seekHoldActive(): Boolean = seekHold

    override suspend fun doPlay(video: Video, position: Double): Boolean {
        calls += "play:${video.id}"
        lastPlayedPosition = position
        return playResult
    }

    override suspend fun doPause(): Boolean {
        calls += "pause"
        return pauseResult
    }

    override suspend fun doResume(): Boolean {
        calls += "resume"
        return resumeResult
    }

    override suspend fun doStop(): Boolean {
        calls += "stop"
        return stopResult
    }

    override suspend fun doSeek(position: Double): Boolean {
        calls += "seek:$position"
        return seekResult
    }

    override suspend fun doSetVolume(volume: Volume): Boolean {
        calls += "volume:${volume.level}:${volume.muted}"
        lastVolumeSet = volume
        if (volumeResult) this.volume = volume
        return volumeResult
    }

    override suspend fun doGetVolume(): Volume = volume

    override suspend fun doGetPosition(): Double = position

    override suspend fun doGetDuration(): Double = duration

    /** When set, doClear throws this after recording the call. */
    var clearFailure: Throwable? = null

    override suspend fun doClear() {
        calls += "clear"
        clearFailure?.let { throw it }
    }
}

/**
 * A request handler that answers from a map of target video id to previous/next videos and records
 * which ids it was asked about. [failOn] makes it throw for one id.
 */
class ScriptedHandler(private val answers: Map<String, PreviousNextVideos?>) : PlaylistRequestHandler {
    val asked = CopyOnWriteArrayList<String>()

    @Volatile
    var failOn: String? = null

    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
        asked += target.id
        if (target.id == failOn) throw IllegalStateException("handler boom")
        return answers[target.id]
    }
}

/** PLAY-05 and CAST-08: sender commands become play, pause, stop, seek and volume calls with the sender's semantics. */
class PlayerTest {
    private fun payload(
        listId: String? = "RDtest",
        videoIds: String? = "dQw4w9WgXcQ",
        videoId: String? = "dQw4w9WgXcQ",
        currentIndex: String? = "0",
    ): JsonObject = buildJsonObject {
        listId?.let { put("listId", it) }
        videoIds?.let { put("videoIds", it) }
        videoId?.let { put("videoId", it) }
        currentIndex?.let { put("currentIndex", it) }
        put("currentTime", "0")
    }

    /** Player with one cast song queued and a listener collecting every state event. */
    private suspend fun queued(): Pair<FakePlayer, MutableList<PlayerStateEvent>> {
        val player = FakePlayer()
        val events = CopyOnWriteArrayList<PlayerStateEvent>()
        player.addStateListener { events += it }
        player.queue.updateByMessage("setPlaylist", payload(), Client.YT)
        return player to events
    }

    private suspend fun playing(): Pair<FakePlayer, MutableList<PlayerStateEvent>> {
        val (player, events) = queued()
        assertTrue(player.play(player.queue.current!!, 0.0))
        events.clear()
        player.calls.clear()
        return player to events
    }

    @Test
    fun tracerSetPlaylistForOneSongPlaysAndReportsLoadingThenPlaying() = runBlocking {
        val (player, events) = queued()
        val video = assertNotNull(player.queue.current)
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals(Client.YT, video.client)
        assertEquals("RDtest", video.context?.playlistId)
        assertEquals(0, video.context?.index)

        assertTrue(player.play(video, 0.0))

        assertEquals(listOf(PlayerStatus.LOADING, PlayerStatus.PLAYING), events.map { it.current.status })
        assertNull(events[0].previous)
        assertEquals(PlayerStatus.LOADING, events[1].previous?.status)
        assertEquals(listOf("play:dQw4w9WgXcQ"), player.calls.toList())
        assertEquals(0.0, player.lastPlayedPosition)
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertEquals(PlayerStatus.PLAYING, player.statusFlow.value)
        assertEquals("dQw4w9WgXcQ", events[1].current.queue.current?.id)
    }

    @Test
    fun playPassesAidAndStartPositionThrough() = runBlocking {
        val (player, events) = queued()
        player.play(player.queue.current!!, 12.5, aid = 7)
        assertEquals(12.5, player.lastPlayedPosition)
        assertEquals(listOf(7, 7), events.map { it.aid })
    }

    @Test
    fun doPlayFalseReportsStopped() = runBlocking {
        val (player, events) = queued()
        player.playResult = false
        assertFalse(player.play(player.queue.current!!, 0.0))
        assertEquals(listOf(PlayerStatus.LOADING, PlayerStatus.STOPPED), events.map { it.current.status })
        assertEquals(PlayerStatus.STOPPED, player.statusFlow.value)
    }

    @Test
    fun cpnIsSixteenLowercaseHexChars() {
        val cpn = FakePlayer().cpn
        assertTrue(Regex("[0-9a-f]{16}").matches(cpn), cpn)
    }

    @Test
    fun startsIdle() {
        assertEquals(PlayerStatus.IDLE, FakePlayer().status)
    }

    @Test
    fun pauseActsOnlyWhilePlaying() = runBlocking {
        val (idle, _) = queued()
        assertFalse(idle.pause())
        assertTrue(idle.calls.isEmpty())

        val (player, events) = playing()
        assertTrue(player.pause())
        assertEquals(listOf("pause"), player.calls.toList())
        assertEquals(PlayerStatus.PAUSED, events.single().current.status)
        // paused now: a second pause does nothing
        player.calls.clear()
        assertFalse(player.pause())
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun pauseFailureLeavesStatusAlone() = runBlocking {
        val (player, events) = playing()
        player.pauseResult = false
        assertFalse(player.pause())
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun resumeWhilePausedCallsDoResume() = runBlocking {
        val (player, events) = playing()
        player.pause()
        events.clear()
        player.calls.clear()
        assertTrue(player.resume())
        assertEquals(listOf("resume"), player.calls.toList())
        assertEquals(PlayerStatus.PLAYING, events.single().current.status)
    }

    @Test
    fun resumeWhilePlayingOnlyReEmitsAndReturnsFalse() = runBlocking {
        val (player, events) = playing()
        assertFalse(player.resume())
        assertTrue(player.calls.isEmpty())
        assertEquals(PlayerStatus.PLAYING, events.single().current.status)
    }

    @Test
    fun resumeWhileStoppedReplaysTheCurrentVideo() = runBlocking {
        val (player, _) = playing()
        player.stop()
        player.calls.clear()
        assertTrue(player.resume())
        assertEquals(listOf("play:dQw4w9WgXcQ"), player.calls.toList())
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun resumeWithNothingQueuedReturnsFalse() = runBlocking {
        val player = FakePlayer()
        assertFalse(player.resume())
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun stopIsANoOpReturningTrueWhenStoppedOrIdle() = runBlocking {
        val idle = FakePlayer()
        assertTrue(idle.stop())
        assertTrue(idle.calls.isEmpty())

        val (player, events) = playing()
        assertTrue(player.stop())
        assertEquals(listOf("stop"), player.calls.toList())
        assertEquals(PlayerStatus.STOPPED, events.single().current.status)

        player.calls.clear()
        events.clear()
        assertTrue(player.stop())
        assertTrue(player.calls.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun stopFailureReturnsFalseWithoutEmitting() = runBlocking {
        val (player, events) = playing()
        player.stopResult = false
        assertFalse(player.stop())
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun playOnAPlayingPlayerStopsItFirst() = runBlocking {
        val (player, events) = playing()
        assertTrue(player.play(player.queue.current!!, 0.0))
        assertEquals(listOf("stop", "play:dQw4w9WgXcQ"), player.calls.toList())
        assertEquals(
            listOf(PlayerStatus.STOPPED, PlayerStatus.LOADING, PlayerStatus.PLAYING),
            events.map { it.current.status },
        )
    }

    @Test
    fun seekWhileIdleOrStoppedReturnsFalse() = runBlocking {
        val (player, _) = queued()
        assertFalse(player.seek(30.0))
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun seekWhilePlayingEmitsLoadingAtTargetThenPlaying() = runBlocking {
        val (player, events) = playing()
        assertTrue(player.seek(42.0))
        assertEquals(listOf("seek:42.0"), player.calls.toList())
        assertEquals(listOf(PlayerStatus.LOADING, PlayerStatus.PLAYING), events.map { it.current.status })
        assertEquals(42.0, events[0].current.position)
        assertEquals(PlayerStatus.PLAYING, events[0].previous?.status)
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun seekWhilePausedStaysPausedAtTheNewPosition() = runBlocking {
        val (player, events) = playing()
        player.pause()
        events.clear()
        player.calls.clear()
        player.position = 10.0
        assertTrue(player.seek(10.0))
        assertEquals(listOf("seek:10.0"), player.calls.toList(), "no resume after a paused seek")
        assertEquals(listOf(PlayerStatus.LOADING, PlayerStatus.PAUSED), events.map { it.current.status })
        assertEquals(10.0, events.last().current.position)
        assertEquals(PlayerStatus.PAUSED, player.status)
    }

    @Test
    fun resumeAfterAPausedSeekCallsDoResumeOnce() = runBlocking {
        val (player, _) = playing()
        player.pause()
        player.position = 10.0
        assertTrue(player.seek(10.0))
        player.calls.clear()
        assertTrue(player.resume())
        assertEquals(listOf("resume"), player.calls.toList())
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun seekFailureEmitsNoPlayingState() = runBlocking {
        val (player, events) = playing()
        player.seekResult = false
        assertFalse(player.seek(10.0))
        assertEquals(listOf(PlayerStatus.LOADING), events.map { it.current.status })
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun mutedWithZeroOnMuteSendsLevelZero() = runBlocking {
        val player = FakePlayer()
        player.setZeroVolumeLevelOnMute(true)
        assertTrue(player.setVolume(Volume(40, true)))
        assertEquals(Volume(0, true), player.lastVolumeSet)
    }

    @Test
    fun mutedWithoutZeroOnMuteKeepsTheLevel() = runBlocking {
        val player = FakePlayer()
        assertTrue(player.setVolume(Volume(40, true)))
        assertEquals(Volume(40, true), player.lastVolumeSet)
    }

    @Test
    fun successfulVolumeChangeEmitsAStateEvent() = runBlocking {
        val player = FakePlayer()
        val events = CopyOnWriteArrayList<PlayerStateEvent>()
        player.addStateListener { events += it }
        assertTrue(player.setVolume(Volume(30, false), aid = 4))
        val event = events.single()
        assertEquals(Volume(30, false), event.current.volume)
        assertEquals(Volume(50, false), event.previous?.volume)
        assertEquals(4, event.aid)
    }

    @Test
    fun failedVolumeChangeEmitsNothing() = runBlocking {
        val player = FakePlayer()
        player.volumeResult = false
        val events = CopyOnWriteArrayList<PlayerStateEvent>()
        player.addStateListener { events += it }
        assertFalse(player.setVolume(Volume(30, false)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun enablingZeroOnMuteWhileMutedEnforcesLevelZero() = runBlocking {
        val player = FakePlayer()
        player.volume = Volume(35, true)
        player.setZeroVolumeLevelOnMute(true)
        assertEquals(Volume(0, true), player.volume)
        assertTrue(player.zeroVolumeLevelOnMute)
    }

    @Test
    fun getVolumeClampsTheLevelTo0To100() = runBlocking {
        val player = FakePlayer()
        player.volume = Volume(140, false)
        assertEquals(100, player.getVolume().level)
        player.volume = Volume(-5, true)
        assertEquals(Volume(0, true), player.getVolume())
    }

    @Test
    fun withTheNoOpHandlerThereIsNothingPreviousOrNext() = runBlocking {
        val (player, events) = playing()
        assertEquals(NavInfo(hasPrevious = false, hasNext = false, autoplayMode = AutoplayMode.UNSUPPORTED), player.getNavInfo())
        assertNull(player.queue.autoplay)

        assertFalse(player.next())
        assertEquals(listOf("stop"), player.calls.toList())
        assertEquals(PlayerStatus.STOPPED, events.single().current.status)
    }

    @Test
    fun previousWithNothingBeforeStopsAndReturnsFalse() = runBlocking {
        val (player, _) = playing()
        assertFalse(player.previous())
        assertEquals(listOf("stop"), player.calls.toList())
        assertEquals(PlayerStatus.STOPPED, player.status)
    }

    @Test
    fun resetReturnsToIdleAndClearsTheQueue() = runBlocking {
        val (player, events) = playing()
        player.reset()
        assertEquals(PlayerStatus.IDLE, player.status)
        assertNull(player.queue.current)
        assertNull(player.queue.id)
        assertEquals(listOf(PlayerStatus.STOPPED, PlayerStatus.IDLE), events.map { it.current.status })
    }

    @Test
    fun externalStateChangeUpdatesStatusAndEmits() = runBlocking {
        val (player, events) = playing()
        player.notifyExternalStateChange(PlayerStatus.PAUSED)
        assertEquals(PlayerStatus.PAUSED, player.status)
        assertEquals(PlayerStatus.PAUSED, events.single().current.status)
        assertEquals(PlayerStatus.PLAYING, events.single().previous?.status)
    }

    @Test
    fun externalStateChangeWithNullKeepsTheStatusButStillEmits() = runBlocking {
        val (player, events) = playing()
        player.position = 77.0
        player.notifyExternalStateChange(null)
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertEquals(77.0, events.single().current.position)
    }

    @Test
    fun aThrowingListenerDoesNotBreakPlaybackAndIsReported() = runBlocking {
        val player = FakePlayer()
        val reported = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        player.events = EventSink { kind, fields -> reported += kind to fields }
        val good = CopyOnWriteArrayList<PlayerStateEvent>()
        player.addStateListener { throw IllegalStateException("listener boom") }
        player.addStateListener { good += it }
        player.queue.updateByMessage("setPlaylist", payload(), Client.YT)

        assertTrue(player.play(player.queue.current!!, 0.0))
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertEquals(2, good.size)
        assertTrue(reported.any { it.first == "player" && it.second["error"].toString().contains("listener boom") })
    }

    @Test
    fun removedListenerStopsReceivingEvents() = runBlocking {
        val player = FakePlayer()
        val got = CopyOnWriteArrayList<PlayerStateEvent>()
        val listener = PlayerStateListener { got += it }
        player.addStateListener(listener)
        player.notifyExternalStateChange(PlayerStatus.PLAYING)
        player.removeStateListener(listener)
        player.notifyExternalStateChange(PlayerStatus.PAUSED)
        assertEquals(1, got.size)
    }

    // ---- autoplay chain (plan 03-04)

    private fun vid(id: String, index: Int? = null) =
        Video(id, Client.YT, if (index != null) VideoContext("L", index) else null)

    /** A index 0 of a one-song list L; A -> B (autoplay), B -> A / C, C -> B / nothing. */
    private suspend fun chain(mode: AutoplayMode): Triple<FakePlayer, ScriptedHandler, MutableList<PlayerStateEvent>> {
        val player = FakePlayer()
        val handler = ScriptedHandler(
            mapOf(
                "A" to PreviousNextVideos(null, vid("B")),
                "B" to PreviousNextVideos(vid("A", 0), vid("C")),
                "C" to PreviousNextVideos(vid("B"), null),
            ),
        )
        player.queue.requestHandler = handler
        val events = CopyOnWriteArrayList<PlayerStateEvent>()
        player.addStateListener { events += it }
        player.queue.setAutoplayMode(mode)
        player.queue.updateByMessage("setPlaylist", payload(listId = "L", videoIds = "A", videoId = "A"), Client.YT)
        return Triple(player, handler, events)
    }

    @Test
    fun tracerAnAutoplayChainKeepsGoingPastTheFirstHop() = runBlocking {
        val (player, handler, _) = chain(AutoplayMode.ENABLED)
        assertTrue(player.play(player.queue.current!!, 0.0))
        assertEquals(listOf("A"), handler.asked.toList())

        assertTrue(player.next())
        assertEquals("B", player.queue.current?.id)
        assertTrue(player.queue.hasPrevious, "previous is refreshed after the hop")
        assertEquals("C", player.queue.autoplay?.id)
        assertEquals(listOf("A", "B"), handler.asked.toList())

        assertTrue(player.next())
        assertEquals("C", player.queue.current?.id)
        assertEquals(PlayerStatus.PLAYING, player.status)

        assertFalse(player.next())
        assertEquals(PlayerStatus.STOPPED, player.status)
        assertEquals(listOf("play:A", "stop", "play:B", "stop", "play:C", "stop"), player.calls.toList())
    }

    @Test
    fun withAutoplayDisabledTheChainStopsAfterTheFirstVideo() = runBlocking {
        val (player, handler, _) = chain(AutoplayMode.DISABLED)
        assertTrue(player.play(player.queue.current!!, 0.0))

        assertFalse(player.next())
        assertEquals(PlayerStatus.STOPPED, player.status)
        assertEquals("A", player.queue.current?.id)
        assertEquals(listOf("A"), handler.asked.toList(), "no hop, so no hop refresh")
    }

    @Test
    fun navInfoCountsTheAutoplayVideoOnlyWhileAutoplayIsEnabled() = runBlocking {
        val (enabled, _, _) = chain(AutoplayMode.ENABLED)
        assertEquals(NavInfo(hasPrevious = false, hasNext = true, autoplayMode = AutoplayMode.ENABLED), enabled.getNavInfo())

        val (disabled, _, _) = chain(AutoplayMode.DISABLED)
        assertEquals(NavInfo(hasPrevious = false, hasNext = false, autoplayMode = AutoplayMode.DISABLED), disabled.getNavInfo())
    }

    @Test
    fun navInfoInTheMiddleOfAListIsBothWaysRegardlessOfAutoplay() = runBlocking {
        for (mode in listOf(AutoplayMode.ENABLED, AutoplayMode.DISABLED)) {
            val player = FakePlayer()
            player.queue.requestHandler = ScriptedHandler(mapOf("B" to PreviousNextVideos(vid("A", 0), vid("C", 2))))
            player.queue.setAutoplayMode(mode)
            player.queue.updateByMessage(
                "setPlaylist",
                payload(listId = "L", videoIds = "A,B,C", videoId = "B", currentIndex = "1"),
                Client.YT,
            )
            assertEquals(NavInfo(hasPrevious = true, hasNext = true, autoplayMode = mode), player.getNavInfo(), mode.name)
        }
    }

    @Test
    fun playingANonAutoplayVideoLeavesNextAloneAndTriggersNoRefresh() = runBlocking {
        val (player, handler, _) = chain(AutoplayMode.ENABLED)
        val before = handler.asked.toList()
        assertTrue(player.play(vid("X"), 0.0))
        assertEquals(before, handler.asked.toList())
        assertEquals("X", player.queue.current?.id)
        assertEquals("B", player.queue.autoplay?.id, "the autoplay candidate is untouched by a non-autoplay video")
    }

    @Test
    fun theHopRefreshRunsOncePerHopAfterPlayingAndAFinalStateFollowsIt() = runBlocking {
        val (player, handler, events) = chain(AutoplayMode.ENABLED)
        assertTrue(player.play(player.queue.current!!, 0.0))
        events.clear()

        assertTrue(player.next())

        assertEquals(listOf("A", "B"), handler.asked.toList(), "exactly one extra ask: B")
        val statuses = events.map { it.current.status }
        assertEquals(listOf(PlayerStatus.STOPPED, PlayerStatus.LOADING, PlayerStatus.PLAYING, PlayerStatus.PLAYING), statuses)
        // The first PLAYING predates the refresh, the last one carries the refreshed previous and autoplay.
        assertNull(events[2].current.queue.previous)
        assertEquals("A", events.last().current.queue.previous?.id)
        assertEquals("C", events.last().current.queue.autoplay?.id)
    }

    @Test
    fun aHandlerThatThrowsDuringTheHopRefreshDoesNotBreakPlay() = runBlocking {
        val (player, handler, _) = chain(AutoplayMode.ENABLED)
        val reported = CopyOnWriteArrayList<Map<String, Any?>>()
        player.events = EventSink { kind, fields -> if (kind == "player") reported += fields }
        assertTrue(player.play(player.queue.current!!, 0.0))
        handler.failOn = "B"

        assertTrue(player.next())

        assertEquals(PlayerStatus.PLAYING, player.status)
        assertEquals("B", player.queue.current?.id)
        assertTrue(reported.any { it["type"] == "hopRefreshFailed" })
    }

    // ---- unconditional clear hook (plan 03-04, CAST-06)

    @Test
    fun resetOnAPlayingPlayerStopsThenClears() = runBlocking {
        val (player, _) = playing()
        player.reset()
        assertEquals(listOf("stop", "clear"), player.calls.toList())
        assertEquals(PlayerStatus.IDLE, player.status)
    }

    @Test
    fun resetOnAStoppedPlayerStillClears() = runBlocking {
        val (player, _) = playing()
        player.stop()
        player.calls.clear()
        player.reset()
        assertEquals(listOf("clear"), player.calls.toList(), "stop is a no-op when STOPPED, clear is not")
    }

    @Test
    fun resetOnAnIdlePlayerStillClears() = runBlocking {
        val player = FakePlayer()
        player.reset()
        assertEquals(listOf("clear"), player.calls.toList())
        assertEquals(PlayerStatus.IDLE, player.status)
    }

    @Test
    fun resetWithoutClearNeverCallsDoClear() = runBlocking {
        val (playing, _) = playing()
        playing.reset(clear = false)
        assertEquals(listOf("stop"), playing.calls.toList())

        val idle = FakePlayer()
        idle.reset(clear = false)
        assertTrue(idle.calls.isEmpty())
    }

    @Test
    fun aFailingDoClearStillLeavesThePlayerIdleWithAnEmptyQueue() = runBlocking {
        val (player, _) = playing()
        val reported = CopyOnWriteArrayList<Map<String, Any?>>()
        player.events = EventSink { kind, fields -> if (kind == "player") reported += fields }
        player.clearFailure = IllegalStateException("speaker unreachable")

        player.reset()

        assertEquals(PlayerStatus.IDLE, player.status)
        assertNull(player.queue.current)
        assertTrue(reported.any { it["type"] == "clearFailed" })
    }

    @Test
    fun aCancelledDoClearPropagates() = runBlocking<Unit> {
        val (player, _) = playing()
        player.clearFailure = kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> { player.reset() }
    }

    @Test
    fun noPositionIsToldBeforeTheFirstState() {
        val player = FakePlayer()
        assertNull(player.senderPosition())
    }

    @Test
    fun theLastStateEmittedIsRecordedAndCountsOnWhilePlaying() {
        runBlocking {
            val (player, _) = queued()
            player.position = 12.0
            assertTrue(player.play(player.queue.current!!, 0.0))
            assertEquals(12.0, player.senderPosition(), "the position of the PLAYING state just emitted")

            player.nowMs += 5_000
            assertEquals(17.0, player.senderPosition(), "the phone's bar has counted on for 5 s")

            player.position = 90.0
            assertTrue(player.seek(90.0))
            assertEquals(90.0, player.senderPosition(), "the state after the seek replaces it")
            player.nowMs += 2_500
            assertEquals(92.5, player.senderPosition())
        }
    }

    @Test
    fun aPausedStateDoesNotCountOn() {
        runBlocking {
            val (player, _) = playing()
            player.position = 30.0
            assertTrue(player.pause())
            player.nowMs += 60_000
            assertEquals(30.0, player.senderPosition(), "the phone's bar stands still while paused")
        }
    }

    @Test
    fun aPositionCorrectionIsRecordedAndAnIdlePlayerDoesNotCountOn() {
        runBlocking {
            val (player, _) = playing()
            player.position = 40.0
            player.nowMs += 10_000
            player.notifyPositionCorrection()
            assertEquals(40.0, player.senderPosition(), "the corrected position, from now on")
            player.nowMs += 3_000
            assertEquals(43.0, player.senderPosition())

            player.reset()
            val atReset = assertNotNull(player.senderPosition(), "the IDLE state of the reset is the last one told")
            player.nowMs += 30_000
            assertEquals(atReset, player.senderPosition(), "an IDLE player does not count on")
        }
    }

    @Test
    fun aSeekOnASpeakerThatHasNotStartedIsToldToTheSenderAsLoadingNotPlaying() {
        runBlocking {
            val (player, events) = playing()
            events.clear()
            player.seekHold = true
            assertTrue(player.seek(42.0))
            assertEquals(PlayerStatus.PLAYING, player.status, "the player itself keeps playing so a Pause still acts")
            val codes = events.map { OutgoingMessage.onStateChange(null, it.current).payload["state"] }
            assertTrue(codes.isNotEmpty() && codes.all { it == 3 }, "the sender is told loading (3), never playing (1): $codes")

            player.seekHold = false
            player.position = 42.0
            player.notifyPositionCorrection()
            assertEquals(1, OutgoingMessage.onStateChange(null, events.last().current).payload["state"], "playing once the hold ends")
        }
    }

    @Test
    fun aPauseWhileTheSenderIsShownLoadingStillPauses() {
        runBlocking {
            val (player, events) = playing()
            player.seekHold = true
            assertTrue(player.seek(42.0))
            events.clear()
            assertTrue(player.pause(), "the player is PLAYING underneath the loading state")
            assertEquals(PlayerStatus.PAUSED, player.status)
            assertEquals(2, OutgoingMessage.onStateChange(null, events.last().current).payload["state"])
        }
    }

    @Test
    fun theBarOfASenderThatIsBufferingDoesNotCountOn() {
        runBlocking {
            val (player, _) = playing()
            player.position = 100.0 // what the renderer reports while it holds the seek target
            player.seekHold = true
            assertTrue(player.seek(100.0))
            player.nowMs += 8_000
            assertEquals(100.0, player.senderPosition(), "no counting on while the sender shows loading")
        }
    }

    // ---- playback failure (quick 261028-ago)

    @Test
    fun aReportedFailureTellsTheSendersStateFourBeforeTheFailureListenerRuns() {
        runBlocking {
            val (player, events) = playing()
            val order = CopyOnWriteArrayList<String>()
            player.addStateListener { order += "state:${it.current.senderCode}" }
            val reasons = CopyOnWriteArrayList<String>()
            player.addFailureListener {
                order += "failure"
                reasons += it
            }
            player.reportPlaybackFailure("boom")
            assertEquals(PlayerStatus.STOPPED, player.status)
            assertEquals(4, events.last().current.senderCode)
            assertEquals(listOf("state:4", "failure"), order.toList())
            assertEquals(listOf("boom"), reasons.toList())
        }
    }

    @Test
    fun aThrowingFailureListenerDoesNotStopTheNextOne() {
        runBlocking {
            val (player, _) = playing()
            val logged = CopyOnWriteArrayList<Map<String, Any?>>()
            player.events = EventSink { kind, fields -> if (kind == "player") logged += fields }
            val reached = CopyOnWriteArrayList<String>()
            player.addFailureListener { throw IllegalStateException("listener boom") }
            player.addFailureListener { reached += it }
            player.reportPlaybackFailure("why")
            assertEquals(listOf("why"), reached.toList())
            assertTrue(logged.any { it["type"] == "listenerError" })
            assertTrue(logged.any { it["type"] == "playbackFailed" && it["reason"] == "why" })
        }
    }

    @Test
    fun aRemovedFailureListenerIsNotCalled() {
        runBlocking {
            val (player, _) = playing()
            val reasons = CopyOnWriteArrayList<String>()
            val listener = PlaybackFailureListener { reasons += it }
            player.addFailureListener(listener)
            player.removeFailureListener(listener)
            player.reportPlaybackFailure("x")
            assertTrue(reasons.isEmpty())
        }
    }
}
