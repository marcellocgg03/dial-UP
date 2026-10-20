package dev.t2u.lounge

// Tests for Playlist (port of yt-cast-receiver 2.1.0 lib/app/Playlist.js, MIT, Copyright (c) Patrick Kan), GPL-3.0-only.

import dev.t2u.upnp.EventSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A handler that answers from a lambda, so a test decides what comes before and after. */
private class LambdaHandler(val block: suspend (Video) -> PreviousNextVideos?) : PlaylistRequestHandler {
    var resets = 0

    override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? = block(target)

    override fun reset() {
        resets++
    }
}

/** CAST-08 / PLAY-05: the queue follows the sender's setPlaylist and updatePlaylist with the library's exact rules. */
class PlaylistTest {
    private fun setPayload(
        listId: String? = "RDtest",
        videoIds: String? = "A,B",
        videoId: String? = "A",
        currentIndex: String? = "0",
    ): JsonObject = buildJsonObject {
        listId?.let { put("listId", it) }
        videoIds?.let { put("videoIds", it) }
        videoId?.let { put("videoId", it) }
        currentIndex?.let { put("currentIndex", it) }
    }

    private fun collectEvents(playlist: Playlist): MutableList<Map<String, Any?>> {
        val seen = CopyOnWriteArrayList<Map<String, Any?>>()
        playlist.events = EventSink { kind, fields -> if (kind == "playlist") seen += fields }
        return seen
    }

    @Test
    fun setPlaylistWithoutListIdClearsTheQueueAndEmitsPlaylistCleared() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("setPlaylist", setPayload(), Client.YT)
        assertNotNull(playlist.current)
        val seen = collectEvents(playlist)

        playlist.updateByMessage("setPlaylist", setPayload(listId = null), Client.YT)

        assertNull(playlist.id)
        assertNull(playlist.current)
        assertTrue(playlist.videoIds.isEmpty())
        assertEquals(listOf("playlistCleared"), seen.map { it["type"] })
    }

    @Test
    fun setPlaylistSetsIdVideoIdsAndEmitsPlaylistSet() = runBlocking {
        val playlist = Playlist()
        val seen = collectEvents(playlist)
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B,C"), Client.YTMUSIC)

        assertEquals("RDtest", playlist.id)
        assertEquals(listOf("A", "B", "C"), playlist.videoIds)
        assertEquals(3, playlist.length)
        assertEquals(Client.YTMUSIC, playlist.current?.client)
        assertEquals(listOf("playlistSet"), seen.map { it["type"] })
    }

    @Test
    fun otherMessageNamesAreIgnored() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("play", setPayload(), Client.YT)
        assertNull(playlist.id)
        assertNull(playlist.current)
    }

    @Test
    fun currentIndexWithoutVideoIdLeavesCurrentNull() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("setPlaylist", setPayload(videoId = null), Client.YT)
        assertNull(playlist.current)
        assertEquals("RDtest", playlist.id)
    }

    @Test
    fun currentIndexAsTheJsonNumberZeroIsFalsyButTheStringZeroIsTruthy() = runBlocking {
        val withNumber = Playlist()
        withNumber.updateByMessage(
            "setPlaylist",
            buildJsonObject {
                put("listId", "RDtest")
                put("videoIds", "A,B")
                put("videoId", "A")
                put("currentIndex", 0)
            },
            Client.YT,
        )
        assertNull(withNumber.current)

        val withString = Playlist()
        withString.updateByMessage("setPlaylist", setPayload(currentIndex = "0"), Client.YT)
        assertEquals("A", withString.current?.id)
        assertEquals(0, withString.current?.context?.index)

        val withEmpty = Playlist()
        withEmpty.updateByMessage("setPlaylist", setPayload(currentIndex = ""), Client.YT)
        assertNull(withEmpty.current)
    }

    @Test
    fun setPlaylistKeepsCttAndParamsInTheContext() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage(
            "setPlaylist",
            buildJsonObject {
                put("listId", "RDtest")
                put("videoIds", "A,B")
                put("videoId", "B")
                put("currentIndex", "1")
                put("ctt", "cttvalue")
                put("params", "paramsvalue")
            },
            Client.YT,
        )
        assertEquals(VideoContext("RDtest", 1, "cttvalue", "paramsvalue"), playlist.current?.context)
    }

    @Test
    fun updatePlaylistClearsCurrentWhenItIsNoLongerInTheList() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "A"), Client.YT)
        val seen = collectEvents(playlist)

        playlist.updateByMessage("updatePlaylist", setPayload(videoIds = "B,C"), Client.YT)

        assertNull(playlist.current)
        assertEquals(listOf("B", "C"), playlist.videoIds)
        assertEquals(listOf("playlistUpdated"), seen.map { it["type"] })
    }

    @Test
    fun updatePlaylistReIndexesTheCurrentVideo() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "A"), Client.YT)

        playlist.updateByMessage("updatePlaylist", setPayload(listId = "RDnew", videoIds = "Z,A,B"), Client.YT)

        assertEquals("A", playlist.current?.id)
        assertEquals(1, playlist.current?.context?.index)
        assertEquals("RDnew", playlist.current?.context?.playlistId)
        assertNull(playlist.current?.context?.params)
        assertEquals("RDnew", playlist.id)
    }

    @Test
    fun updatePlaylistWithNoCurrentVideoStaysEmpty() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("updatePlaylist", setPayload(), Client.YT)
        assertNull(playlist.current)
        assertEquals(listOf("A", "B"), playlist.videoIds)
    }

    @Test
    fun eventDetailsPicksTheEventTypeAndCarriesTheVideoId() = runBlocking {
        val playlist = Playlist()
        val seen = collectEvents(playlist)
        playlist.updateByMessage(
            "updatePlaylist",
            buildJsonObject {
                put("listId", "RDtest")
                put("videoIds", "A,B")
                put("eventDetails", """{"eventType":"VIDEO_ADDED","videoId":"B"}""")
            },
            Client.YT,
        )
        assertEquals("videoAdded", seen.single()["type"])
        assertEquals("B", seen.single()["videoId"])
    }

    @Test
    fun malformedEventDetailsFallsBackToTheMessageDefault() = runBlocking {
        val playlist = Playlist()
        val seen = collectEvents(playlist)
        playlist.updateByMessage(
            "setPlaylist",
            buildJsonObject {
                put("listId", "RDtest")
                put("videoIds", "A")
                put("eventDetails", "{not json")
            },
            Client.YT,
        )
        assertEquals(listOf("eventDetailsParseError", "playlistSet"), seen.map { it["type"] })
    }

    @Test
    fun withTheNoOpHandlerNothingIsPreviousOrNext() = runBlocking {
        val playlist = Playlist()
        playlist.updateByMessage("setPlaylist", setPayload(), Client.YT)
        assertFalse(playlist.hasPrevious)
        assertFalse(playlist.hasNext)
        assertNull(playlist.next())
        assertNull(playlist.previous())
        assertNull(playlist.autoplay)
    }

    @Test
    fun aHandlerAnswerGivesHasNextForAMiddleVideo() = runBlocking {
        val playlist = Playlist()
        val b = Video("B", Client.YT, VideoContext("RDtest", 1))
        playlist.requestHandler = LambdaHandler { target -> if (target.id == "A") PreviousNextVideos(null, b) else null }

        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "A"), Client.YT)

        assertTrue(playlist.hasNext)
        assertFalse(playlist.hasPrevious)
        assertEquals(b, playlist.getState().next)
        assertNull(playlist.autoplay)

        assertEquals(b, playlist.next())
        assertEquals("B", playlist.current?.id)
    }

    @Test
    fun atTheLastIndexHasNextIsFalseUnlessAutoplayIsEnabledWhereAutoplayIsTheAnswer() = runBlocking {
        val playlist = Playlist()
        val c = Video("C", Client.YT)
        playlist.requestHandler = LambdaHandler { PreviousNextVideos(null, c) }
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "B", currentIndex = "1"), Client.YT)

        assertFalse(playlist.hasNext)
        assertNull(playlist.autoplay)

        playlist.setAutoplayMode(AutoplayMode.ENABLED)

        assertFalse(playlist.hasNext)
        assertEquals(c, playlist.autoplay)
        assertEquals(c, playlist.getState().autoplay)
        assertNull(playlist.getState().next)
    }

    @Test
    fun previousMovesBackAndReturnsTheVideo() = runBlocking {
        val playlist = Playlist()
        val a = Video("A", Client.YT, VideoContext("RDtest", 0))
        playlist.requestHandler = LambdaHandler { target -> if (target.id == "B") PreviousNextVideos(a, null) else null }
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "B", currentIndex = "1"), Client.YT)

        assertTrue(playlist.hasPrevious)
        assertEquals(a, playlist.previous())
        assertEquals("A", playlist.current?.id)
    }

    @Test
    fun setAsCurrentSkipsTheSameVideoAndDropsAnAutoplayNext() = runBlocking {
        val playlist = Playlist()
        val c = Video("C", Client.YT)
        playlist.requestHandler = LambdaHandler { PreviousNextVideos(null, c) }
        playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B", videoId = "B", currentIndex = "1"), Client.YT)
        playlist.setAutoplayMode(AutoplayMode.ENABLED)
        val before = playlist.current

        playlist.setAsCurrent(before!!)
        assertEquals(c, playlist.autoplay)

        playlist.setAsCurrent(c)
        assertEquals(c, playlist.current)
        assertNull(playlist.autoplay)
    }

    @Test
    fun aSecondRefreshWhileTheFirstIsSuspendedCancelsTheFirst() = runBlocking {
        val playlist = Playlist()
        val firstEntered = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<PreviousNextVideos?>()
        var firstWasCancelled = false
        val staleNext = Video("STALE", Client.YT)
        val freshNext = Video("B", Client.YT)
        var calls = 0
        playlist.requestHandler = LambdaHandler { _ ->
            calls++
            if (calls == 1) {
                firstEntered.complete(Unit)
                try {
                    firstGate.await()
                } catch (e: CancellationException) {
                    firstWasCancelled = true
                    throw e
                }
            } else {
                PreviousNextVideos(null, freshNext)
            }
        }

        val first = launch { playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B,C"), Client.YT) }
        firstEntered.await()
        assertTrue(playlist.isUpdating)

        val second = launch { playlist.updateByMessage("setPlaylist", setPayload(videoIds = "A,B,C"), Client.YT) }
        joinAll(first, second)
        firstGate.complete(PreviousNextVideos(null, staleNext))

        assertTrue(firstWasCancelled)
        assertFalse(playlist.isUpdating)
        assertEquals(freshNext, playlist.getState().next)
        assertTrue(playlist.hasNext)
    }

    @Test
    fun resetClearsTheQueueAndResetsTheHandler() = runBlocking {
        val playlist = Playlist()
        val handler = LambdaHandler { null }
        playlist.requestHandler = handler
        playlist.updateByMessage("setPlaylist", setPayload(), Client.YT)
        playlist.setAutoplayMode(AutoplayMode.ENABLED)

        playlist.reset()

        assertNull(playlist.id)
        assertNull(playlist.current)
        assertEquals(AutoplayMode.UNSUPPORTED, playlist.autoplayMode)
        assertEquals(1, handler.resets)
    }
}
