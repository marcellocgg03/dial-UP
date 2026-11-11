package dev.t2u.lounge

// Characterization tests for YouTubeApp's incoming-message handling and lounge status (plan 09-28, D-05): they pin what
// the code does today, before the file is split. Where today's behaviour looks odd it is pinned as it is and named in
// the test; nothing here encodes a fix. Every wait is on an observable condition (awaitUntil, or a getNowPlaying
// barrier that is answered only after everything sent before it was handled), never on a fixed delay.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeAppCharacterizationTest {
    private val fake = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = FakePlayer()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = EventSink { kind, fields -> events.add(kind to fields) }
    private val apps = CopyOnWriteArrayList<YouTubeApp>()
    private val aids = AtomicInteger(500)
    private val connects = CopyOnWriteArrayList<String>()
    private val disconnects = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val holdMusicInit = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        holdMusicInit.countDown()
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

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; calls=${player.calls}; cl=${messages("cl").map { it.first }}")
        }
    }

    private fun newApp(http: OkHttpClient = lanHttpClient(), appScope: CoroutineScope = scope): YouTubeApp {
        val a = YouTubeApp(player, AppOptions("T2U test", "T2U", "Test", MemoryDataStore()), http, fake.endpoints, sink, appScope)
        a.onSenderConnect = { connects.add(it.id) }
        a.onSenderDisconnect = { s, implicit -> disconnects.add(s.id to implicit) }
        apps.add(a)
        return a
    }

    private suspend fun startedApp(appScope: CoroutineScope = scope): YouTubeApp = newApp(appScope = appScope).also { it.start() }

    // ---- driving the lounge

    private fun message(name: String, payload: JsonObject?): JsonArray {
        val args = ArrayList<JsonElement>()
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

    private fun device(id: String?, theme: String = "cl") = buildJsonObject {
        put("app", "android-phone-21.16.256")
        put("capabilities", "que,vsp,pas,dsdtr,mvp,atp,mus")
        put("clientName", "android")
        put("type", "REMOTE_CONTROL")
        put("name", "TEST PHONE")
        put("theme", theme)
        if (id != null) put("id", id)
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

    private suspend fun connect(id: String = "dev-1") {
        val before = count("cl", "onStateChange")
        rpc("cl", "loungeStatus", loungeStatus(listOf(device(id)), id))
        awaitUntil(what = "sender $id connected") { count("cl", "onStateChange") > before && id in connects }
    }

    private suspend fun castAndPlay(videoId: String) {
        rpc("cl", "setPlaylist", playlist(videoId))
        awaitUntil(what = "$videoId playing") { "play:$videoId" in player.calls && player.status == PlayerStatus.PLAYING }
        awaitUntil(what = "playing state reported") { messages("cl").any { it.first == "onStateChange" && it.second["state"] == "1" } }
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

    /** A getNowPlaying sent now is answered only after every message sent before it was handled. */
    private suspend fun barrier(theme: String) {
        val before = count(theme, "nowPlaying")
        rpc(theme, "getNowPlaying")
        awaitUntil(what = "answer to getNowPlaying on $theme") { count(theme, "nowPlaying") > before }
    }

    private fun appEvents(type: String) = events.filter { it.first == "lounge-app" && it.second["type"] == type }

    private fun errorAt(where: String) = events.any { it.first == "lounge-app" && it.second["type"] == "error" && it.second["where"] == where }

    private fun malformed(reason: String) = events.any { it.first == "lounge-app" && it.second["type"] == "malformed" && it.second["reason"] == reason }

    // ---- inactive session

    @Test
    fun everyControlMessageOnAnInactiveSessionIsIgnoredAndNothingIsPosted() = run {
        startedApp()
        rpcBatch(
            "m",
            "setPlaylist" to playlist("VID00000001"),
            "updatePlaylist" to playlist("VID00000001"),
            "next" to null,
            "previous" to null,
            "pause" to null,
            "stopVideo" to null,
            "seekTo" to buildJsonObject { put("newTime", "5") },
            "setVolume" to buildJsonObject { put("volume", "40") },
            "play" to null,
            "setAutoplayMode" to buildJsonObject { put("autoplayMode", "DISABLED") },
        )
        barrier("m")
        assertEquals(emptyList(), player.calls.toList())
        assertEquals(Volume(50, false), player.volume)
        assertEquals(listOf("nowPlaying"), messages("m").map { it.first }, "only the barrier's own answer was posted")
        assertTrue(messages("cl").isEmpty())
        assertTrue(appEvents("malformed").isEmpty(), "an inactive session is not even validated")
    }

    @Test
    fun getVolumeIsAnsweredEvenOnAnInactiveSession() = run {
        // Pinned as found: unlike every other control message, getVolume has no isActive guard.
        startedApp()
        player.volume = Volume(37, false)
        rpc("m", "getVolume")
        awaitUntil(what = "volume answer on the inactive session") { count("m", "onVolumeChanged") == 1 }
        assertEquals("37", messages("m").single { it.first == "onVolumeChanged" }.second["volume"])
    }

    // ---- active session: one arm each

    @Test
    fun nextAndPreviousAtTheEdgesOfAOneVideoQueueStopPlayback() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "previous")
        awaitUntil(what = "previous stops") { player.status == PlayerStatus.STOPPED }
        assertEquals(1, player.calls.count { it == "stop" })

        castAndPlay("VID00000002")
        rpc("cl", "next")
        awaitUntil(what = "next stops") { player.calls.count { it == "stop" } == 2 }
        assertEquals(PlayerStatus.STOPPED, player.status)
    }

    @Test
    fun aMalformedSeekIsNotedAndNeverReachesThePlayer() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "seekTo", buildJsonObject { put("newTime", "soon") })
        rpc("cl", "seekTo")
        barrier("cl")
        assertTrue(malformed("newTime is not a number"))
        assertEquals(2, appEvents("malformed").size, "a seekTo without any payload is malformed too")
        assertTrue(player.calls.none { it.startsWith("seek:") })
        // JavaScript parseInt: the leading integer counts
        rpc("cl", "seekTo", buildJsonObject { put("newTime", "17.9s") })
        awaitUntil(what = "seek to 17") { "seek:17.0" in player.calls }
    }

    @Test
    fun aMalformedVolumeIsNotedAndNeverReachesThePlayer() = run {
        startedApp()
        connect()
        rpc("cl", "setVolume", buildJsonObject { put("volume", "loud") })
        rpc("cl", "setVolume", buildJsonObject { put("muted", "true") })
        rpc("cl", "setVolume")
        barrier("cl")
        assertEquals(3, appEvents("malformed").count { it.second["reason"] == "volume is not a number" })
        assertTrue(player.calls.none { it.startsWith("volume:") })
        assertEquals(0, count("cl", "onVolumeChanged"), "a malformed volume is not answered")
    }

    @Test
    fun aVolumeDownRequestNeverRaisesAndAnUpRequestNeverLowersAndTheConflictIsNoted() = run {
        startedApp()
        connect()
        player.volume = Volume(50, false)
        rpc("cl", "setVolume", buildJsonObject { put("volume", "70"); put("delta", "-5") })
        awaitUntil(what = "down conflict noted") { appEvents("volumeDirectionConflict").size == 1 }
        val down = appEvents("volumeDirectionConflict").single().second
        assertEquals(70, down["requested"])
        assertEquals(50, down["current"])
        assertEquals(-5, down["delta"])
        awaitUntil(what = "down request answered") { count("cl", "onVolumeChanged") == 1 }
        assertEquals("50", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"], "the unchanged volume is answered")
        assertTrue(player.calls.none { it.startsWith("volume:") })

        rpc("cl", "setVolume", buildJsonObject { put("volume", "30"); put("delta", "+5") })
        awaitUntil(what = "up conflict noted") { appEvents("volumeDirectionConflict").size == 2 }
        awaitUntil(what = "up request answered") { count("cl", "onVolumeChanged") == 2 }
        assertTrue(player.calls.none { it.startsWith("volume:") })

        // a delta that agrees with the request is applied as asked, and a zero delta is no direction at all
        rpc("cl", "setVolume", buildJsonObject { put("volume", "60"); put("delta", "5") })
        awaitUntil(what = "agreeing up request applied") { "volume:60:false" in player.calls }
        rpc("cl", "setVolume", buildJsonObject { put("volume", "20"); put("delta", "0") })
        awaitUntil(what = "zero delta applied as asked") { "volume:20:false" in player.calls }
        assertEquals(2, appEvents("volumeDirectionConflict").size)
    }

    @Test
    fun theMutedFlagIsReadFromABooleanOrFromTextIgnoringCaseAndSpaces() = run {
        startedApp()
        connect()
        // the sender is a phone, so the AUTO mute policy sets the level to zero when it mutes
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", true) })
        awaitUntil(what = "muted by a boolean") { "volume:0:true" in player.calls }
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", false) })
        awaitUntil(what = "unmuted by a boolean") { "volume:40:false" in player.calls }
        rpc("cl", "setVolume", buildJsonObject { put("volume", "41"); put("muted", " TRUE ") })
        awaitUntil(what = "muted by text") { player.calls.count { it == "volume:0:true" } == 2 }
        rpc("cl", "setVolume", buildJsonObject { put("volume", "42"); put("muted", "yes") })
        awaitUntil(what = "anything but true is not muted") { "volume:42:false" in player.calls }
    }

    @Test
    fun setAutoplayModeAppliesAKnownModeAndNotesAnUnknownOne() = run {
        startedApp()
        connect()
        assertEquals(AutoplayMode.ENABLED, player.autoplayMode)
        val before = count("cl", "onAutoplayModeChanged")
        rpc("cl", "setAutoplayMode", buildJsonObject { put("autoplayMode", "DISABLED") })
        awaitUntil(what = "mode answered") { count("cl", "onAutoplayModeChanged") > before }
        assertEquals(AutoplayMode.DISABLED, player.autoplayMode)
        assertEquals("DISABLED", messages("cl").last { it.first == "onAutoplayModeChanged" }.second["autoplayMode"])

        val answered = count("cl", "onAutoplayModeChanged")
        rpc("cl", "setAutoplayMode", buildJsonObject { put("autoplayMode", "SOMETIMES") })
        rpc("cl", "setAutoplayMode")
        barrier("cl")
        assertEquals(2, appEvents("malformed").count { it.second["reason"] == "unknown autoplayMode" })
        assertEquals(answered, count("cl", "onAutoplayModeChanged"))
        assertEquals(AutoplayMode.DISABLED, player.autoplayMode)
    }

    // ---- playlist messages

    @Test
    fun aSetPlaylistWithoutAnObjectPayloadIsNotedAndIgnored() = run {
        startedApp()
        connect()
        rpc("cl", "setPlaylist")
        rpc("cl", "updatePlaylist")
        barrier("cl")
        assertEquals(2, appEvents("malformed").count { it.second["reason"] == "payload is not an object" })
        assertEquals(emptyList(), player.calls.toList())
    }

    @Test
    fun anUpdatePlaylistThatDropsTheCurrentVideoStopsPlayback() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "updatePlaylist", buildJsonObject { put("listId", "LIST1"); put("videoIds", "VID00000002") })
        awaitUntil(what = "stopped") { player.status == PlayerStatus.STOPPED }
        assertEquals(1, player.calls.count { it == "stop" })
    }

    @Test
    fun anUpdatePlaylistThatKeepsTheCurrentVideoAnswersNowPlayingAndPlaysOn() = run {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        val before = count("cl", "nowPlaying")
        rpc("cl", "updatePlaylist", buildJsonObject { put("listId", "LIST1"); put("videoIds", "VID00000001,VID00000002") })
        awaitUntil(what = "nowPlaying after the update") { count("cl", "nowPlaying") > before }
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertFalse("stop" in player.calls)
        assertEquals(1, player.calls.count { it == "play:VID00000001" })
    }

    @Test
    fun anUpdatePlaylistWhileNothingIsQueuedAsksForNoStopAndPostsNoAnswer() = run {
        // Pinned as found: with no current video the update takes the stop branch, and stopping an idle player does nothing.
        startedApp()
        connect()
        val before = count("cl", "nowPlaying")
        rpc("cl", "updatePlaylist", buildJsonObject { put("listId", "LIST1"); put("videoIds", "VID00000001") })
        barrier("cl")
        assertEquals(before + 1, count("cl", "nowPlaying"), "only the barrier's own answer")
        assertEquals(emptyList(), player.calls.toList())
    }

    // ---- batch handling

    @Test
    fun aMessageThatThrowsIsReportedAndTheRestOfItsBatchIsStillHandled() = run {
        startedApp()
        connect()
        val failing = AtomicInteger(0)
        player.beforeGetVolume = {
            // only the first read fails: the state answer of the next message reads the volume too
            if (failing.incrementAndGet() == 1) throw IllegalStateException("speaker did not answer")
        }
        val before = count("cl", "nowPlaying")
        rpcBatch("cl", "getVolume" to null, "getNowPlaying" to null)
        awaitUntil(what = "the second message of the batch") { count("cl", "nowPlaying") > before }
        assertTrue(failing.get() >= 2, "the state answer read the volume again")
        assertTrue(errorAt("message:getVolume"))
        assertEquals("speaker did not answer", events.first { it.second["where"] == "message:getVolume" }.second["message"])
        assertEquals(0, count("cl", "onVolumeChanged"))
    }

    @Test
    fun aCancelledMessageIsNeitherReportedNorSwallowed() = run {
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startedApp(ownScope)
        connect()
        val entered = CompletableDeferred<Unit>()
        val unwound = CompletableDeferred<Unit>()
        player.beforeGetVolume = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                unwound.complete(Unit)
            }
        }
        rpc("cl", "getVolume")
        entered.await()
        ownScope.cancel()
        unwound.await()
        // absence of a report: the check can only pass wrongly, never fail wrongly, if it runs early
        delay(100)
        assertFalse(errorAt("message:getVolume"), "a cancellation is not a problem to report")
    }

    // ---- lounge status

    @Test
    fun aStatusWithoutAConnectionEventIsAnsweredWithAvailabilityAndAutoplayModeInOnePost() = run {
        startedApp()
        connect()
        val posts = fake.sent.getValue("cl").size
        rpc(
            "cl",
            "loungeStatus",
            buildJsonObject {
                put("devices", JsonArray(listOf(device("dev-1"))).toString())
                put("connectionEventDetails", "this is not json")
            },
        )
        awaitUntil(what = "answer posted") { fake.sent.getValue("cl").size > posts }
        val post = fake.sent.getValue("cl").last()
        assertEquals("2", post["count"], "both replies leave in a single post")
        assertEquals("onHasPreviousNextChanged", post["req0__sc"])
        assertEquals("onAutoplayModeChanged", post["req1__sc"])
        assertEquals("ENABLED", post["req1_autoplayMode"])
        assertTrue(errorAt("connectionEventDetails"), "an unreadable event is reported, not fatal")
        assertEquals(posts + 1, fake.sent.getValue("cl").size)
    }

    @Test
    fun aDeviceThatCannotBeParsedIsReportedAndTheOthersAreKept() = run {
        val a = startedApp()
        connect()
        // a REMOTE_CONTROL entry without an id is dropped; the listed sender stays connected
        rpc("cl", "loungeStatus", loungeStatus(listOf(device("dev-1"), device(null)), "nobody"))
        awaitUntil(what = "unparseable sender reported") { errorAt("sender") }
        barrier("cl")
        assertEquals(listOf("dev-1"), a.connectedSenders.map { it.id })
        assertTrue(disconnects.isEmpty())
    }

    @Test
    fun sendersAlreadyOnTheScreenWhenTheAppStartsAreTakenOverWithoutAConnectEvent() = run {
        // The cl session's first RPC stream delivers a status that lists a sender while the music session's bind init is
        // held, so the app is still STARTING when the status is handled.
        val gated = lanHttpClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            if (req.method == "POST" && req.url.queryParameter("theme") == "m" && req.url.encodedPath.endsWith("/bind")) holdMusicInit.await()
            chain.proceed(req)
        }.build()
        rpc("cl", "loungeStatus", loungeStatus(listOf(device("dev-9")), "dev-9"))
        val a = newApp(http = gated)
        val starting = scope.async { a.start() }
        awaitUntil(what = "senders restored") { appEvents("sendersRestored").isNotEmpty() }
        assertEquals(AppState.STARTING, a.state)
        assertEquals(1, appEvents("sendersRestored").single().second["count"])
        assertEquals(listOf("dev-9"), a.connectedSenders.map { it.id })
        assertEquals(Client.YT, a.activeClient)
        assertTrue(connects.isEmpty(), "taking over is not a connect: onSenderConnect is not called")
        holdMusicInit.countDown()
        starting.await()
        assertEquals(AppState.RUNNING, a.state)
        assertEquals(listOf("dev-9"), a.connectedSenders.map { it.id })
    }
}
