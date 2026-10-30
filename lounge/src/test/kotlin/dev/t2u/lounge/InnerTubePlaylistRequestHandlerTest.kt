package dev.t2u.lounge

import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun requestShapeFollowsTheTvClient() = runBlocking<Unit> {
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

    // ---- Task 3: failures, cancellation, log hygiene, visitorData, ctt, guard

    private class Captured : dev.t2u.upnp.EventSink {
        val all = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        override fun event(kind: String, fields: Map<String, Any?>) {
            all.add(kind to fields)
        }

        fun text(): String = all.joinToString("\n") { "${it.first} ${it.second}" }
    }

    private fun nextJson(videoId: String): String =
        """{"responseContext":{"visitorData":"SERVERVISITOR1"},"contents":{"singleColumnWatchNextResults":{"autoplay":{"autoplay":{"sets":[""" +
            """{"mode":"NORMAL","nextVideoRenderer":{"autoplayEndpointRenderer":{"endpoint":{"watchEndpoint":{"videoId":"$videoId"}}}}}]}}}}}"""

    private suspend fun startsFineWith(h: PlaylistRequestHandler) {
        val playlist = Playlist()
        playlist.requestHandler = h
        playlist.updateByMessage(
            "setPlaylist",
            setPlaylistPayload("PLtest", listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), "bbbbbbbbbb2", 1),
            Client.YT,
        )
        assertFalse(playlist.hasNext)
        assertFalse(playlist.hasPrevious)
    }

    private val mid = Video("bbbbbbbbbb2", Client.YT, VideoContext("PLtest", 1))

    @Test
    fun http500GivesEmptyResult() = runBlocking {
        fake.status = 500
        assertEquals(PreviousNextVideos(null, null), handler().getPreviousNextVideos(mid, Playlist()))
        startsFineWith(handler())
    }

    @Test
    fun malformedJsonGivesEmptyResult() = runBlocking {
        fake.rawResponse = "not json"
        assertEquals(PreviousNextVideos(null, null), handler().getPreviousNextVideos(mid, Playlist()))
        startsFineWith(handler())
    }

    @Test
    fun connectionRefusedGivesEmptyResult() = runBlocking {
        val h = handler()
        fake.close()
        assertEquals(PreviousNextVideos(null, null), h.getPreviousNextVideos(mid, Playlist()))
        startsFineWith(h)
    }

    @Test
    fun oversizedBodyGivesEmptyResult() = runBlocking {
        fake.rawResponse = """{"pad":"${"x".repeat((InnerTubeConstants.MAX_RESPONSE_BYTES + 1000).toInt())}"}"""
        assertEquals(PreviousNextVideos(null, null), handler().getPreviousNextVideos(mid, Playlist()))
        startsFineWith(handler())
    }

    @Test
    fun responseWithoutAutoplayContainerGivesEmptyResult() = runBlocking {
        fake.defaultFixture = "next-none"
        assertEquals(PreviousNextVideos(null, null), handler().getPreviousNextVideos(mid, Playlist()))
        startsFineWith(handler())
    }

    @Test
    fun cancellingEndsTheCallQuicklyAndIsNotSwallowed() = runBlocking {
        fake.delayMs = 5000
        val job = async(Dispatchers.IO) { handler().getPreviousNextVideos(mid, Playlist()) }
        val deadline = System.currentTimeMillis() + 5000
        while (fake.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(fake.requests.isNotEmpty())
        val started = System.currentTimeMillis()
        job.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        assertTrue(System.currentTimeMillis() - started < 1000)
    }

    @Test
    fun logsCarryShapeOnlyAndNeverASecret() = runBlocking {
        val events = Captured()
        val h = InnerTubePlaylistRequestHandler(lanHttpClient(), fake.endpoints, events, { "CLIENTVISITOR123" })
        val withCtt = mid.copy(context = mid.context!!.copy(ctt = "TARGETCTTSECRET"))
        fake.defaultFixture = "next-ctt"
        h.getPreviousNextVideos(withCtt, Playlist()) // success; the answer holds the server ctt and visitorData
        fake.defaultFixture = "next-none"
        h.getPreviousNextVideos(withCtt, Playlist()) // nothing found
        fake.rawResponse = "{\"distinctiveBodyString\":\"leakme-body-marker\"}"
        h.getPreviousNextVideos(withCtt, Playlist()) // nothing found, hostile-looking body
        fake.rawResponse = "leakme-body-marker not json"
        h.getPreviousNextVideos(withCtt, Playlist()) // failure
        val text = events.text()
        assertTrue(events.all.isNotEmpty())
        assertTrue("bbbbbbbbbb2" in text, "events carry the request video id")
        assertTrue("contents" in text && "responseContext" in text, "events carry the top-level response keys")
        for (secret in listOf("TARGETCTTSECRET", "CTTSECRETTOKEN-from-server", "CLIENTVISITOR123", "SERVERVISITOR1", "leakme-body-marker")) {
            assertFalse(secret in text, "event log leaked $secret")
        }
    }

    @Test
    fun secondRequestAdoptsTheServersVisitorData() = runBlocking {
        val h = InnerTubePlaylistRequestHandler(lanHttpClient(), fake.endpoints, dev.t2u.upnp.NoEvents, { "CLIENTVISITOR123" })
        h.getPreviousNextVideos(mid, Playlist())
        h.getPreviousNextVideos(mid, Playlist())
        val first = fake.requests[0]
        val second = fake.requests[1]
        assertEquals("CLIENTVISITOR123", first.headers["x-goog-visitor-id"])
        assertEquals("SERVERVISITOR1", second.headers["x-goog-visitor-id"])
        assertEquals("SERVERVISITOR1", second.body["context"]!!.jsonObject["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content)
    }

    @Test
    fun cttIsSentBackAndTheNextCarriesTheServersToken() = runBlocking {
        fake.defaultFixture = "next-ctt"
        val withCtt = mid.copy(context = mid.context!!.copy(ctt = "TARGETCTTSECRET"))
        val result = handler().getPreviousNextVideos(withCtt, Playlist())
        val tokens = fake.requests.single().body["context"]!!.jsonObject["user"]!!.jsonObject["credentialTransferTokens"]!!.jsonArray
        assertEquals(1, tokens.size)
        assertEquals("VIDEO", tokens[0].jsonObject["scope"]!!.jsonPrimitive.content)
        assertEquals("TARGETCTTSECRET", tokens[0].jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("CTTSECRETTOKEN-from-server", result.next?.context?.ctt)
    }

    @Test
    fun mixPingPongStopsAtTheLastItem() = runBlocking {
        val a = "aaaaaaaaaa1"
        val b = "bbbbbbbbbb2"
        fake.onJson(a, nextJson(b))
        fake.onJson(b, nextJson(a))
        val h = handler()
        val playlist = Playlist()
        playlist.requestHandler = h
        playlist.setAutoplayMode(AutoplayMode.ENABLED)
        playlist.updateByMessage("setPlaylist", setPlaylistPayload("PLtest", listOf(a), a, 0), Client.YT)
        assertTrue(playlist.isLast)
        assertEquals(b, playlist.autoplay?.id)

        val autoplayB = Video(b, Client.YT, VideoContext(playlistId = "RDx"))
        assertNull(h.getPreviousNextVideos(autoplayB, playlist).next, "A was asked about, B is the last item: dropped")

        h.reset()
        assertEquals(a, h.getPreviousNextVideos(autoplayB, playlist).next?.id, "reset() clears the memory")
    }

    @Test
    fun theGuardDoesNotApplyInTheMiddleOfAList() = runBlocking {
        val h = handler()
        h.getPreviousNextVideos(Video("cccccccccc3", Client.YT, VideoContext("PLtest", 2)), Playlist())
        val playlist = Playlist()
        playlist.requestHandler = h
        playlist.updateByMessage(
            "setPlaylist",
            setPlaylistPayload("PLtest", listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "cccccccccc3"), "bbbbbbbbbb2", 1),
            Client.YT,
        )
        assertEquals("cccccccccc3", playlist.getState().next?.id)
        assertEquals("aaaaaaaaaa1", playlist.getState().previous?.id)
    }

    @Test
    fun `videoArrays reports paths and counts of video lists without any id`() {
        val root = Json.parseToJsonElement(
            """{"a":{"panel":[{"videoId":"x"},{"w":{"videoId":"y"}},{"videoId":"z"},{"title":"t"}]},"b":[{"videoId":"q"}],"ctt":"SECRET"}""",
        ).jsonObject
        val shape = InnerTubePlaylistRequestHandler.videoArrays(root)
        assertEquals("/a/panel:3", shape)
    }
}
