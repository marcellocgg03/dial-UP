package dev.t2u.lounge

import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerTubePlaylistRequestHandlerTest {
    private val fake = FakeInnerTubeServer()

    @AfterEach
    fun tearDown() = fake.close()

    private fun handler(events: dev.t2u.upnp.EventSink = dev.t2u.upnp.NoEvents) =
        InnerTubePlaylistRequestHandler(lanHttpClient(), fake.endpoints, events)

    private fun setPlaylistPayload(listId: String, ids: List<String>, videoId: String, index: Int, ctt: String? = null): JsonObject =
        buildJsonObject {
            put("listId", JsonPrimitive(listId))
            put("videoIds", JsonPrimitive(ids.joinToString(",")))
            put("videoId", JsonPrimitive(videoId))
            put("currentIndex", JsonPrimitive(index.toString()))
            if (ctt != null) put("ctt", JsonPrimitive(ctt))
        }

    @Test
    fun middleItemGivesPreviousAndNextThroughAPlaylist() = runBlocking {
        val playlist = Playlist()
        playlist.requestHandler = handler()
        playlist.updateByMessage(
            "setPlaylist",
            setPlaylistPayload("PLtest", listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), "bbbbbbbbbb2", 1),
            Client.YT,
        )

        assertTrue(playlist.hasPrevious)
        assertTrue(playlist.hasNext)
        val state = playlist.getState()
        assertEquals("aaaaaaaaaa1", state.previous?.id)
        assertEquals("PLtest", state.previous?.context?.playlistId)
        assertEquals(0, state.previous?.context?.index)
        assertEquals("cccccccccc3", state.next?.id)
        assertEquals("PLtest", state.next?.context?.playlistId)
        assertEquals(2, state.next?.context?.index)
        assertEquals(Client.YT, state.next?.client)
    }

    @Test
    fun requestShapeFollowsTheTvClient() = runBlocking {
        val playlist = Playlist()
        playlist.requestHandler = handler()
        playlist.updateByMessage(
            "setPlaylist",
            setPlaylistPayload("PLtest", listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), "bbbbbbbbbb2", 1),
            Client.YT,
        )

        assertEquals(1, fake.requests.size)
        val req = fake.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/youtubei/v1/next", req.path)
        val query = req.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        assertEquals("false", query["prettyPrint"])
        assertEquals("json", query["alt"])
        assertFalse("key" in query, "the API key must never be sent")
        assertEquals("7", req.headers["x-youtube-client-name"])
        assertEquals(InnerTubeConstants.CLIENT_VERSION, req.headers["x-youtube-client-version"])
        val visitor = req.headers["x-goog-visitor-id"]
        assertTrue(!visitor.isNullOrEmpty())
        assertEquals(InnerTubeConstants.USER_AGENT, req.headers["user-agent"])
        assertEquals("https://www.youtube.com", req.headers["origin"])

        val body = req.body
        assertEquals("bbbbbbbbbb2", body["videoId"]!!.jsonPrimitive.content)
        assertEquals("true", body["enableMdxAutoplay"]!!.jsonPrimitive.content)
        assertEquals("true", body["isMdxPlayback"]!!.jsonPrimitive.content)
        assertEquals("PLtest", body["playlistId"]!!.jsonPrimitive.content)
        assertEquals("1", body["playlistIndex"]!!.jsonPrimitive.content)
        val client = body["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("TVHTML5", client["clientName"]!!.jsonPrimitive.content)
        assertEquals(visitor, client["visitorData"]!!.jsonPrimitive.content)
        assertEquals("false", body["context"]!!.jsonObject["user"]!!.jsonObject["enableSafetyMode"]!!.jsonPrimitive.content)
        assertNotNull(body["context"]!!.jsonObject["request"])
    }

    @Test
    fun noTargetWithoutIndexAsksNothing() = runBlocking {
        val result = handler().getPreviousNextVideos(Video("zzzzzzzzzz9"), Playlist())
        assertNotNull(result)
        assertNull(result.previous)
    }
}
