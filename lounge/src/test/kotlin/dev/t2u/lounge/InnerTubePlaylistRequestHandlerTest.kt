package dev.t2u.lounge

import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    // ---- Task 2: response shapes

    private val target = Video(
        id = "bbbbbbbbbb2",
        client = Client.YTMUSIC,
        context = VideoContext(playlistId = "PLtest", index = 1),
    )

    private fun parse(json: String): PreviousNextVideos =
        InnerTubePlaylistRequestHandler.parseNext(Json.parseToJsonElement(json).jsonObject, target)

    private fun fixture(name: String): String =
        InnerTubePlaylistRequestHandlerTest::class.java.getResourceAsStream("/innertube/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun setsOf(vararg entries: String): String =
        """{"contents":{"singleColumnWatchNextResults":{"autoplay":{"autoplay":{"sets":[{"mode":"NORMAL",${entries.joinToString(",")}}]}}}}}"""

    private fun endpointRenderer(kind: String, wrapperKey: String, videoId: String, extra: String = ""): String =
        """"$wrapperKey":{"$kind":{"endpoint":{"watchEndpoint":{"videoId":"$videoId"$extra}}}}"""

    @Test
    fun firstItemHasNoPrevious() {
        val r = parse(fixture("next-first"))
        assertNull(r.previous)
        assertEquals("bbbbbbbbbb2", r.next?.id)
    }

    @Test
    fun lastItemTakesNextFromMaybeHistoryAndPlaylistAutoplayIsIt() = runBlocking {
        val r = parse(fixture("next-last-item"))
        assertEquals("bbbbbbbbbb2", r.previous?.id)
        assertEquals("dddddddddd4", r.next?.id)
        assertEquals("EAEYAcABAdoBBAgBKgDyBQIKAA%3D%3D", r.next?.context?.params)
        assertNull(r.next?.context?.playlistId)
        assertNull(r.next?.context?.index)

        fake.defaultFixture = "next-last-item"
        val playlist = Playlist()
        playlist.requestHandler = handler()
        playlist.setAutoplayMode(AutoplayMode.ENABLED)
        playlist.updateByMessage(
            "setPlaylist",
            setPlaylistPayload("PLtest", listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), "cccccccccc3", 2),
            Client.YT,
        )
        assertEquals("dddddddddd4", playlist.autoplay?.id)
        assertFalse(playlist.hasNext)
        assertTrue(playlist.hasPrevious)
    }

    @Test
    fun mixStartHasNextAndAutoplayCarrierWithRdPlaylist() {
        val r = parse(fixture("next-rd-start"))
        assertNull(r.previous)
        assertEquals("eeeeeeeeee5", r.next?.id)
        assertEquals("RDeeeeeeeeee5", r.next?.context?.playlistId)
        assertEquals(1, r.next?.context?.index)
    }

    @Test
    fun autoplayPathsAreTriedInOrderWhenNextRendererIsAbsent() {
        val mdx = """"autoplayVideoRenderer":{"mdxAutoplayVideoRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"mmmmmmmmmm1"}}}}"""
        val autonav = endpointRenderer("autonavEndpointRenderer", "autoplayVideoRenderer", "nnnnnnnnnn2")
        val autoplayEp = endpointRenderer("autoplayEndpointRenderer", "autoplayVideoRenderer", "ppppppppppp")
        assertEquals("mmmmmmmmmm1", parse(setsOf(mdx)).next?.id)
        assertEquals("nnnnnnnnnn2", parse(setsOf(autonav)).next?.id)
        assertEquals("ppppppppppp", parse(setsOf(autoplayEp)).next?.id)
        // all three present: the mdx path wins over autonav and autoplayEndpoint
        val all = """"autoplayVideoRenderer":{"mdxAutoplayVideoRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"mmmmmmmmmm1"}}},""" +
            """"autonavEndpointRenderer":{"endpoint":{"watchEndpoint":{"videoId":"nnnnnnnnnn2"}}},""" +
            """"autoplayEndpointRenderer":{"endpoint":{"watchEndpoint":{"videoId":"ppppppppppp"}}}}"""
        assertEquals("mmmmmmmmmm1", parse(setsOf(all)).next?.id)
    }

    @Test
    fun paramsStayPercentEncodedAndCttIsRead() {
        val r = parse(fixture("next-ctt"))
        assertEquals("EAEYAcABAdoBBAgBKgDyBQIKAA%3D%3D", r.next?.context?.params)
        assertEquals("CTTSECRETTOKEN-from-server", r.next?.context?.ctt)
    }

    @Test
    fun candidatesWithABadVideoIdAreDropped() {
        val short = endpointRenderer("autoplayEndpointRenderer", "nextVideoRenderer", "short")
        val long = endpointRenderer("autoplayEndpointRenderer", "nextVideoRenderer", "abcdefghijkl")
        val bad = endpointRenderer("autoplayEndpointRenderer", "nextVideoRenderer", "abc def gh!")
        assertNull(parse(setsOf(short)).next)
        assertNull(parse(setsOf(long)).next)
        assertNull(parse(setsOf(bad)).next)
    }

    @Test
    fun theVideoClientEqualsTheTargetClient() {
        val r = parse(fixture("next-middle"))
        assertEquals(Client.YTMUSIC, r.previous?.client)
        assertEquals(Client.YTMUSIC, r.next?.client)
    }

    @Test
    fun firstNormalSetIsPreferredOverTheFirstSet() {
        val json = """{"contents":{"singleColumnWatchNextResults":{"autoplay":{"autoplay":{"sets":[""" +
            """{"mode":"SHUFFLE","nextVideoRenderer":{"autoplayEndpointRenderer":{"endpoint":{"watchEndpoint":{"videoId":"sssssssssss"}}}}},""" +
            """{"mode":"NORMAL","nextVideoRenderer":{"autoplayEndpointRenderer":{"endpoint":{"watchEndpoint":{"videoId":"nnnnnnnnnn2"}}}}}]}}}}}"""
        assertEquals("nnnnnnnnnn2", parse(json).next?.id)
    }
}
