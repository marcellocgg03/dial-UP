package dev.t2u.lounge

// Tests for YouTubeApp (port of yt-cast-receiver 2.1.0 lib/app/YouTubeApp.js, MIT, Copyright (c) Patrick Kan):
// sender messages in, player calls and sender messages out, against FakeLoungeServer.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeAppTest {
    private val fake = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryDataStore()
    private val player = FakePlayer()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = EventSink { kind, fields -> events.add(kind to fields) }
    private val apps = CopyOnWriteArrayList<YouTubeApp>()
    private val aids = AtomicInteger(100)
    private val connects = CopyOnWriteArrayList<String>()
    private val disconnects = CopyOnWriteArrayList<Pair<String, Boolean>>()

    @AfterEach
    fun tearDown() {
        runBlocking {
            for (a in apps) {
                try {
                    withTimeout(5000) { a.stop() }
                } catch (_: Exception) {
                    // best effort
                }
            }
        }
        scope.cancel()
        fake.close()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun awaitUntil(timeoutMs: Long = 5000, what: String = "condition", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; calls=${player.calls}; sent=${fake.sentNames("cl")}")
        }
    }

    private suspend fun startedApp(options: AppOptions = AppOptions("T2U test", "T2U", "Test", store)): YouTubeApp {
        val a = YouTubeApp(player, options, lanHttpClient(), fake.endpoints, sink, scope)
        a.onSenderConnect = { connects.add(it.id) }
        a.onSenderDisconnect = { s, implicit -> disconnects.add(s.id to implicit) }
        apps.add(a)
        a.start()
        return a
    }

    // ---- driving the lounge

    private fun message(name: String, payload: JsonObject?): JsonArray {
        val args = ArrayList<kotlinx.serialization.json.JsonElement>()
        args.add(JsonPrimitive(name))
        if (payload != null) args.add(payload)
        return JsonArray(listOf(JsonPrimitive(aids.incrementAndGet()), JsonArray(args)))
    }

    private fun rpc(theme: String, name: String, payload: JsonObject? = null) {
        fake.enqueueRpc(theme, JsonArray(listOf(message(name, payload))).toString())
    }

    private fun rpcBatch(theme: String, vararg items: Pair<String, JsonObject?>) {
        fake.enqueueRpc(theme, JsonArray(items.map { message(it.first, it.second) }).toString())
    }

    private fun device(id: String, app: String = "android-phone-21.16.256", theme: String = "cl") = buildJsonObject {
        put("app", app)
        put("capabilities", "que,vsp,pas,dsdtr,mvp,atp,mus")
        put("clientName", "android")
        put("type", "REMOTE_CONTROL")
        put("name", "TEST PHONE")
        put("theme", theme)
        put("id", id)
    }

    private fun loungeStatus(devices: List<JsonObject>, deviceId: String, ui: String? = "true") = buildJsonObject {
        put("devices", JsonArray(devices).toString())
        put(
            "connectionEventDetails",
            buildJsonObject {
                if (ui != null) put("ui", ui)
                put("deviceId", deviceId)
            }.toString(),
        )
    }

    private fun playlist(videoId: String, listId: String = "LIST1") = buildJsonObject {
        put("videoId", videoId)
        put("currentIndex", "0")
        put("listId", listId)
        put("currentTime", "0")
        put("videoIds", videoId)
    }

    private suspend fun connect(theme: String = "cl", id: String = "dev-1", app: String = "android-phone-21.16.256") {
        val before = count(theme, "onStateChange")
        rpc(theme, "loungeStatus", loungeStatus(listOf(device(id, app, theme)), id))
        awaitUntil(what = "sender $id connected") { count(theme, "onStateChange") > before && id in connects }
    }

    private suspend fun castAndPlay(videoId: String, theme: String = "cl") {
        rpc(theme, "setPlaylist", playlist(videoId))
        awaitUntil(what = "$videoId playing") { "play:$videoId" in player.calls && player.status == PlayerStatus.PLAYING }
        awaitUntil(what = "playing state reported") { messages(theme).any { it.first == "onStateChange" && it.second["state"] == "1" } }
    }

    /** Every message the app posted for [theme], in order, as (name, payload fields). */
    private fun messages(theme: String): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, Map<String, String>>>()
        for (form in fake.sent[theme] ?: emptyList<Map<String, String>>()) {
            val indexes = form.keys.mapNotNull { Regex("req(\\d+)__sc").matchEntire(it)?.groupValues?.get(1) }.sortedBy { it.toInt() }
            for (i in indexes) {
                val prefix = "req${i}_"
                val payload = form.filterKeys { it.startsWith(prefix) && it != "${prefix}_sc" }.mapKeys { it.key.removePrefix(prefix) }
                out.add(form.getValue("${prefix}_sc") to payload)
            }
        }
        return out
    }

    private fun count(theme: String, name: String) = messages(theme).count { it.first == name }

    /** Waits until a getNowPlaying sent now has been answered: everything sent before it has been handled. */
    private suspend fun barrier(theme: String) {
        val before = count(theme, "nowPlaying")
        rpc(theme, "getNowPlaying")
        awaitUntil(what = "answer to getNowPlaying on $theme") { count(theme, "nowPlaying") > before }
    }

    // ---- behaviour

    @Test
    fun senderConnectReportsTheSenderAndTheState() = run {
        val a = startedApp()
        connect()
        assertEquals(listOf("dev-1"), a.connectedSenders.map { it.id })
        assertEquals(listOf("dev-1"), connects.toList())
        assertEquals(Client.YT, a.activeClient)
        val names = messages("cl").map { it.first }
        val nav = names.indexOf("onHasPreviousNextChanged")
        assertTrue(nav >= 0 && names.indexOf("nowPlaying") > nav && names.indexOf("onStateChange") > names.indexOf("nowPlaying"), "$names")
        val navPayload = messages("cl").first { it.first == "onHasPreviousNextChanged" }.second
        assertEquals("false", navPayload["hasPrevious"])
        assertEquals("false", navPayload["hasNext"])
    }

    @Test
    fun pauseResumeStopAndSeekReachThePlayer() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")

        rpc("cl", "seekTo", buildJsonObject { put("newTime", "42") })
        awaitUntil(what = "seek") { "seek:42.0" in player.calls }

        rpc("cl", "pause")
        awaitUntil(what = "pause") { "pause" in player.calls }

        rpc("cl", "play")
        awaitUntil(what = "resume") { "resume" in player.calls }

        rpc("cl", "stopVideo")
        awaitUntil(what = "stop") { "stop" in player.calls }
        assertEquals(PlayerStatus.STOPPED, player.status)
    }

    @Test
    fun setVolumeReachesThePlayerOnlyWhenItDiffersAndGetVolumeAnswers() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)

        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", "false") })
        awaitUntil(what = "volume 40") { "volume:40:false" in player.calls }
        awaitUntil(what = "volume reported") { messages("cl").any { it.first == "onVolumeChanged" && it.second["volume"] == "40" } }
        val reported = count("cl", "onVolumeChanged")

        // the same volume again: the player is not asked, and getVolume is still answered
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", "false") })
        rpc("cl", "getVolume")
        awaitUntil(what = "answer to getVolume") { count("cl", "onVolumeChanged") > reported }
        assertEquals(1, player.calls.count { it.startsWith("volume:") })
        val answer = messages("cl").last { it.first == "onVolumeChanged" }.second
        assertEquals("40", answer["volume"])
        assertEquals("false", answer["muted"])
    }

    @Test
    fun getNowPlayingAnswersTheStateWhenActiveAndAnEmptyOneWhenNot() = run {
        startedApp()
        // nothing is active yet
        barrier("cl")
        val empty = messages("cl").last { it.first == "nowPlaying" }.second
        assertTrue(empty.isEmpty(), "inactive nowPlaying is empty: $empty")

        connect()
        castAndPlay("VID00000001")
        val before = count("cl", "nowPlaying")
        rpc("cl", "getNowPlaying")
        awaitUntil(what = "active nowPlaying") { count("cl", "nowPlaying") > before }
        val active = messages("cl").last { it.first == "nowPlaying" }.second
        assertEquals("VID00000001", active["videoId"])
        assertEquals("1", active["state"])
    }

    @Test
    fun muteSetsLevelZeroForAMobileSenderAndKeepsItForADesktopSender() = run {
        startedApp()
        connect(app = "android-phone-21.16.256")
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", "true") })
        awaitUntil(what = "muted to zero") { "volume:0:true" in player.calls }
        assertFalse("volume:40:true" in player.calls)
    }

    @Test
    fun muteKeepsTheLevelForADesktopSender() = run {
        startedApp()
        connect(app = "youtube-desktop")
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", "true") })
        awaitUntil(what = "muted at level") { "volume:40:true" in player.calls }
        assertFalse("volume:0:true" in player.calls)
    }

    @Test
    fun explicitDisconnectOfTheLastSenderResetsThePlayer() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = "true"))
        awaitUntil(what = "player reset") { player.status == PlayerStatus.IDLE && disconnects.isNotEmpty() }
        assertEquals(listOf("dev-1" to false), disconnects.toList())
        assertTrue(a.connectedSenders.isEmpty())
    }

    @Test
    fun implicitDisconnectIsReportedAsImplicit() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "implicit disconnect") { disconnects.isNotEmpty() }
        assertEquals(listOf("dev-1" to true), disconnects.toList())
        // the default policy resets the player when every sender is gone
        awaitUntil(what = "player reset") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun anImplicitDisconnectKeepsThePlayerWhenOnlyExplicitDisconnectsReset() = run {
        startedApp(AppOptions("T2U test", "T2U", "Test", store, resetPlayerOnDisconnect = ResetPlayerOnDisconnectPolicy.ALL_EXPLICITLY_DISCONNECTED))
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "implicit disconnect") { disconnects.isNotEmpty() }
        barrier("cl")
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun launchOfTheOtherThemeSwitchesTheSessionAndTheOldSessionIsIgnored() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")

        val pid = a.launch("pairingCode=PAIR12&theme=m")
        assertEquals(store.text(StoreKeys.APP_PID), pid)
        assertEquals(Client.YTMUSIC, a.activeClient)
        assertEquals(PlayerStatus.IDLE, player.status)
        assertTrue(a.connectedSenders.isEmpty())
        awaitUntil(what = "loungeScreenDisconnected on cl") { "loungeScreenDisconnected" in messages("cl").map { it.first } }
        awaitUntil(what = "old sender reported gone") { disconnects == listOf("dev-1" to false) }

        val registration = fake.requests.single { it.path.endsWith("register_pairing_code") }
        assertEquals("PAIR12", registration.form["pairing_code"])
        assertEquals(((store.map["mdxContext.m"] as JsonObject)["screenId"] as JsonPrimitive).content, registration.form["screen_id"])

        // a setPlaylist now arriving on cl is ignored; the barrier proves it was handled before we look
        rpc("cl", "setPlaylist", playlist("VID00000002"))
        barrier("cl")
        assertFalse("play:VID00000002" in player.calls)
        assertEquals(PlayerStatus.IDLE, player.status)
    }

    @Test
    fun launchRefusesMissingPairingCodeAndUnknownTheme() = run {
        val a = startedApp()
        assertFailsWith<IllegalArgumentException> { a.launch("theme=cl") }
        assertFailsWith<IllegalArgumentException> { a.launch("pairingCode=ABC123&theme=x") }
        assertTrue(fake.requests.none { it.path.endsWith("register_pairing_code") })
    }

    @Test
    fun anUnknownMessageIsLoggedAsNotHandledAndNothingIsSent() = run {
        startedApp()
        connect()
        barrier("cl")
        val posts = fake.sent.getValue("cl").size
        rpc("cl", "frobnicate", buildJsonObject { put("x", "1") })
        awaitUntil(what = "not-handled event") {
            events.any { (kind, f) -> kind == "lounge-app" && f["type"] == "notHandled" && f["name"] == "frobnicate" }
        }
        barrier("cl")
        // the only post since is the answer to the barrier
        assertEquals(posts + 1, fake.sent.getValue("cl").size)
    }

    @Test
    fun threeVolumeEventsWithin200msProduceOneVolumeMessage() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val before = count("cl", "onVolumeChanged")
        rpcBatch(
            "cl",
            "setVolume" to buildJsonObject { put("volume", "40") },
            "setVolume" to buildJsonObject { put("volume", "45") },
            "setVolume" to buildJsonObject { put("volume", "50") },
        )
        awaitUntil(what = "three volume calls") { player.calls.count { it.startsWith("volume:") } == 3 }
        awaitUntil(what = "one volume message") { count("cl", "onVolumeChanged") > before }
        delay(600)
        assertEquals(before + 1, count("cl", "onVolumeChanged"))
        assertEquals("50", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"])
    }

    @Test
    fun stopTellsBothSessionsAndReleasesTheListener() = run {
        val a = startedApp()
        connect()
        a.stop()
        assertEquals(AppState.STOPPED, a.state)
        assertTrue("loungeScreenDisconnected" in messages("cl").map { it.first })
        assertTrue("loungeScreenDisconnected" in messages("m").map { it.first })
        assertNotNull(store.text(StoreKeys.APP_PID))
    }

    // ---- previous/next availability follows the queue (plan 03-04)

    private fun vid(id: String, index: Int? = null) =
        Video(id, Client.YT, if (index != null) VideoContext("LIST1", index) else null)

    /** First -> autoplay second -> autoplay third: the scripted chain the hop tests walk. */
    private fun installChain() {
        player.queue.requestHandler = ScriptedHandler(
            mapOf(
                "VID00000001" to PreviousNextVideos(null, vid("VID00000002")),
                "VID00000002" to PreviousNextVideos(vid("VID00000001", 0), vid("VID00000003")),
                "VID00000003" to PreviousNextVideos(vid("VID00000002"), null),
            ),
        )
    }

    private fun navMessages(): List<Map<String, String>> = messages("cl").filter { it.first == "onHasPreviousNextChanged" }.map { it.second }

    @Test
    fun anAutoplayHopTellsTheSenderThatPreviousAndNextAreNowAvailable() = run {
        installChain()
        startedApp()
        connect()
        castAndPlay("VID00000001")
        // one-song list with autoplay on: next is available through autoplay, previous is not
        assertEquals("true", navMessages().last()["hasNext"])
        assertEquals("false", navMessages().last()["hasPrevious"])

        rpc("cl", "next")
        awaitUntil(what = "hop to the autoplay video") { "play:VID00000002" in player.calls && player.status == PlayerStatus.PLAYING }
        awaitUntil(what = "previous reported after the hop refresh") { navMessages().any { it["hasPrevious"] == "true" } }
        assertTrue(messages("cl").any { it.first == "nowPlaying" && it.second["videoId"] == "VID00000002" })
        assertEquals("true", navMessages().last()["hasNext"], "the next autoplay video exists")
        awaitUntil(what = "autoplayUpNext for the new autoplay video") {
            messages("cl").any { it.first == "autoplayUpNext" && it.second["videoId"] == "VID00000003" }
        }
    }

    @Test
    fun aStateEventThatChangesNoAvailabilitySendsNoExtraNavMessage() = run {
        installChain()
        startedApp()
        connect()
        castAndPlay("VID00000001")
        barrier("cl")
        val before = navMessages().size

        // (a seek or a resume re-reports now playing and availability by the existing rules; pause and volume do not)
        rpc("cl", "setVolume", buildJsonObject { put("volume", "33"); put("muted", "false") })
        awaitUntil(what = "volume") { "volume:33:false" in player.calls }
        rpc("cl", "pause")
        awaitUntil(what = "pause") { "pause" in player.calls }
        barrier("cl")

        assertEquals(before, navMessages().size, "same availability as the last one sent: nothing new")
    }

    @Test
    fun theAvailabilitySentAtConnectIsRememberedSoLaterIdenticalStatesAddNoMessage() = run {
        startedApp()
        connect()
        assertEquals("false", navMessages().last()["hasPrevious"])
        // the first state event ever has no previous state and re-reports everything by the existing rules
        player.notifyExternalStateChange(null)
        barrier("cl")
        val afterConnect = navMessages().size
        player.position = 12.0
        player.notifyExternalStateChange(null)
        player.position = 13.0
        player.notifyExternalStateChange(null)
        barrier("cl")
        assertEquals(afterConnect, navMessages().size, "same availability as at connect: no new message")
    }

    @Test
    fun anInactiveSessionStatusMessageNeverOverwritesTheRememberedAvailability() = run {
        installChain()
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "next")
        awaitUntil(what = "previous reported") { navMessages().any { it["hasPrevious"] == "true" } }
        barrier("cl")
        val before = navMessages().size

        // an unrelated loungeStatus on the inactive theme answers with a null availability
        rpc("m", "loungeStatus", loungeStatus(emptyList(), "nobody"))
        awaitUntil(what = "inactive answer") { messages("m").any { it.first == "onHasPreviousNextChanged" } }
        assertEquals("false", messages("m").first { it.first == "onHasPreviousNextChanged" }.second["hasPrevious"])

        player.position = 5.0
        player.notifyExternalStateChange(null)
        barrier("cl")
        assertEquals(before, navMessages().size, "the inactive branch did not touch the remembered availability")
    }

    // ---- clear on disconnect, second phone, session switch (plan 03-04, CAST-06)

    private fun clearCount() = player.calls.count { it == "clear" }

    @Test
    fun withTwoSendersOneLeavingChangesNothingAndTheLastOneClearsTheSpeaker() = run {
        startedApp()
        connect(id = "dev-1")
        castAndPlay("VID00000001")
        connect(id = "dev-2")

        rpc("cl", "loungeStatus", loungeStatus(listOf(device("dev-2")), "dev-1", ui = "true"))
        awaitUntil(what = "first sender gone") { disconnects.any { it.first == "dev-1" } }
        barrier("cl")
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertEquals(0, clearCount())

        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-2", ui = "true"))
        awaitUntil(what = "player reset") { player.status == PlayerStatus.IDLE && disconnects.any { it.first == "dev-2" } }
        assertEquals(1, clearCount())
        assertEquals(listOf("play:VID00000001", "stop", "clear"), player.calls.toList())
    }

    @Test
    fun theLastSenderLeavingClearsTheSpeakerEvenWhenItWasAlreadyStopped() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "stopVideo")
        awaitUntil(what = "stop") { player.status == PlayerStatus.STOPPED }

        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = "true"))
        awaitUntil(what = "player reset") { player.status == PlayerStatus.IDLE }
        assertEquals(1, clearCount(), "stop() was a no-op, the clear hook still ran")
    }

    @Test
    fun switchingBetweenYouTubeAndYouTubeMusicResetsWithoutClearing() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")

        a.launch("pairingCode=PAIR12&theme=m")

        assertEquals(PlayerStatus.IDLE, player.status)
        assertEquals(0, clearCount(), "the new session starts playing at once, so the speaker is not cleared")
    }

    @Test
    fun theAppsOwnStopResetsWithClear() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")

        a.stop()

        assertEquals(1, clearCount())
        assertEquals(PlayerStatus.IDLE, player.status)
    }

    @Test
    fun aSecondPhoneJoiningDoesNotDisturbPlaybackAndGetsTheCurrentState() = run {
        startedApp()
        connect(id = "dev-1")
        castAndPlay("VID00000001")
        val playsBefore = player.calls.toList()

        connect(id = "dev-2")
        barrier("cl")

        assertEquals(playsBefore, player.calls.toList(), "no stop, restart or clear")
        assertEquals(PlayerStatus.PLAYING, player.status)
        val joined = messages("cl").last { it.first == "nowPlaying" }.second
        assertEquals("VID00000001", joined["videoId"])
        assertEquals("1", joined["state"])
        assertTrue(messages("cl").any { it.first == "onStateChange" && it.second["state"] == "1" })
    }
}
