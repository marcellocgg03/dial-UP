package dev.t2u.lounge

// Tests for YouTubeApp (port of yt-cast-receiver 2.1.0 lib/app/YouTubeApp.js, MIT, Copyright (c) Patrick Kan):
// sender messages in, player calls and sender messages out, against FakeLoungeServer.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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

    private suspend fun startedApp(
        options: AppOptions = AppOptions("T2U test", "T2U", "Test", store),
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
    ): YouTubeApp {
        val a = YouTubeApp(player, options, lanHttpClient(), fake.endpoints, sink, scope, clock)
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
    fun mutePolicyReportsActualVolumeEvenWhenItDoesNotChange() = run {
        startedApp()
        connect(app = "android-phone-21.16.256")
        player.volume = Volume(0, true)
        val before = count("cl", "onVolumeChanged")
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("muted", "true") })
        awaitUntil(what = "unchanged muted volume reported") { count("cl", "onVolumeChanged") > before }
        val reported = messages("cl").last { it.first == "onVolumeChanged" }.second
        assertEquals("0", reported["volume"])
        assertEquals("true", reported["muted"])
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
    fun networkInterruptedSenderKeepsPlayingAfterReturn() = run {
        var now = 1_000L
        val a = startedApp(clock = { now })
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        a.onNetworkLost()
        now += 5_000
        a.onNetworkReturned()
        now += 7_000
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "implicit disconnect") { disconnects.isNotEmpty() }
        barrier("cl")
        assertEquals(PlayerStatus.PLAYING, player.status)
        assertTrue(a.connectedSenders.isEmpty())
        assertTrue(events.any { it.first == "lounge-app" && it.second["type"] == "noSendersLeft" && it.second["networkRecovery"] == true })
        connect()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = "true"))
        awaitUntil(what = "explicit disconnect resets player") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun networkInterruptedSenderKeepsPlayingDuringOutage() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "implicit disconnect") { disconnects.isNotEmpty() }
        barrier("cl")
        assertEquals(PlayerStatus.PLAYING, player.status)
    }

    @Test
    fun explicitDisconnectDuringNetworkRecoveryStillResetsPlayer() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        a.onNetworkReturned()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = "true"))
        awaitUntil(what = "explicit disconnect resets player") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun implicitDisconnectAfterRecoveryWindowStillResetsPlayer() = run {
        var now = 1_000L
        val a = startedApp(clock = { now })
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        a.onNetworkReturned()
        now += NETWORK_SENDER_RECOVERY_MS
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "expired recovery resets player") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun senderConnectedAfterNetworkLossIsNotProtected() = run {
        val a = startedApp()
        a.onNetworkLost()
        a.onNetworkReturned()
        connect()
        castAndPlay("VID00000001")
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "new sender disconnect resets player") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun senderReconnectedDuringRecoveryIsNotProtectedFromLaterDisconnects() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        a.onNetworkReturned()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "first implicit disconnect") { disconnects.size == 1 }
        connect()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "dev-1", ui = null))
        awaitUntil(what = "subsequent disconnect resets player") { player.status == PlayerStatus.IDLE }
    }

    @Test
    fun senderMissingFromSnapshotAfterNetworkReturnKeepsPlaying() = run {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")
        a.onNetworkLost()
        a.onNetworkReturned()
        rpc("cl", "loungeStatus", loungeStatus(emptyList(), "unknown", ui = null))
        awaitUntil(what = "unlisted sender disconnected") { disconnects.isNotEmpty() }
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
    fun successiveVolumeRequestsReportTheLatestVolume() = run {
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
        awaitUntil(what = "latest volume message") {
            messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).any { it.second["volume"] == "50" }
        }
        assertEquals("50", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"])
    }

    private suspend fun blockSendQueue(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        fake.sendGate = gate
        val before = count("cl", "nowPlaying")
        rpc("cl", "getNowPlaying")
        awaitUntil(what = "send queue blocked") { count("cl", "nowPlaying") > before }
        return gate
    }

    private fun releaseSendQueue(gate: CompletableDeferred<Unit>) {
        fake.sendGate = null
        gate.complete(Unit)
    }

    private suspend fun incomingBarrier() {
        val before = events.count { (kind, fields) -> kind == "lounge-app" && fields["name"] == "volumeTestBarrier" }
        rpc("cl", "volumeTestBarrier")
        awaitUntil(what = "incoming messages processed") {
            events.count { (kind, fields) -> kind == "lounge-app" && fields["name"] == "volumeTestBarrier" } > before
        }
    }

    @Test
    fun newerVolumeUpRequestCancelsOlderReportBeforeSlowSpeakerRead() = run {
        assertNewVolumeCancelsOlderReport(30, 39, 42, 9, 12)
    }

    @Test
    fun newerVolumeDownRequestCancelsOlderReportBeforeSlowSpeakerRead() = run {
        assertNewVolumeCancelsOlderReport(60, 51, 48, -9, -12)
    }

    private suspend fun assertNewVolumeCancelsOlderReport(start: Int, first: Int, last: Int, firstDelta: Int, lastDelta: Int) {
        startedApp()
        connect()
        player.volume = Volume(start, false)
        val before = count("cl", "onVolumeChanged")
        val sendGate = blockSendQueue()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        try {
            rpc("cl", "setVolume", buildJsonObject { put("volume", "$first"); put("delta", "$firstDelta") })
            awaitUntil(what = "first volume applied") { player.volume.level == first }
            incomingBarrier()
            player.beforeGetVolume = {
                readStarted.complete(Unit)
                releaseRead.await()
            }
            rpc("cl", "setVolume", buildJsonObject { put("volume", "$last"); put("delta", "$lastDelta") })
            readStarted.await()
            releaseSendQueue(sendGate)
            delay(100)
            assertEquals(before, count("cl", "onVolumeChanged"), "obsolete $first must not be reported")
        } finally {
            player.beforeGetVolume = {}
            releaseRead.complete(Unit)
            releaseSendQueue(sendGate)
        }
        awaitUntil(what = "volume $last reported") {
            messages("cl").any { it.first == "onVolumeChanged" && it.second["volume"] == "$last" }
        }
        assertEquals(listOf("$last"), messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).map { it.second["volume"] })
        assertEquals(listOf("volume:$first:false", "volume:$last:false"), player.calls.filter { it.startsWith("volume:") })
    }

    @Test
    fun unchangedVolumeRequestStillReplacesOlderReport() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val before = count("cl", "onVolumeChanged")
        rpcBatch(
            "cl",
            "setVolume" to buildJsonObject { put("volume", "40") },
            "setVolume" to buildJsonObject { put("volume", "40") },
        )
        awaitUntil(what = "volume confirmed") { count("cl", "onVolumeChanged") > before }
        assertTrue(messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).all { it.second["volume"] == "40" })
        assertEquals(1, player.calls.count { it.startsWith("volume:") })
    }

    @Test
    fun repeatedIdenticalButtonRequestsReceiveFeedbackDuringTheHold() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val before = count("cl", "onVolumeChanged")
        coroutineScope {
            val hold = async {
                repeat(12) {
                    rpc("cl", "setVolume", buildJsonObject { put("volume", "33"); put("delta", "3") })
                    delay(100)
                }
            }
            awaitUntil(timeoutMs = 700, what = "volume feedback during button hold") {
                count("cl", "onVolumeChanged") > before
            }
            assertFalse(hold.isCompleted)
            hold.await()
        }
        assertEquals(1, player.calls.count { it == "volume:33:false" })
    }

    @Test
    fun volumeDownNeverRaisesSpeakerWhenSenderTargetsMoveBackwards() = run {
        startedApp()
        connect()
        player.volume = Volume(50, false)
        val before = count("cl", "onVolumeChanged")
        rpcBatch(
            "cl",
            "setVolume" to buildJsonObject { put("volume", "40"); put("delta", "-6") },
            "setVolume" to buildJsonObject { put("volume", "46"); put("delta", "-9") },
            "setVolume" to buildJsonObject { put("volume", "34"); put("delta", "-6") },
            "setVolume" to buildJsonObject { put("volume", "43"); put("delta", "-3") },
        )
        incomingBarrier()
        awaitUntil(what = "final volume confirmed") {
            messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).lastOrNull()?.second?.get("volume") == "34"
        }
        assertEquals(listOf("volume:40:false", "volume:34:false"), player.calls.filter { it.startsWith("volume:") })
        assertEquals(Volume(34, false), player.volume)
    }

    @Test
    fun volumeUpNeverLowersSpeakerWhenSenderTargetsMoveBackwards() = run {
        startedApp()
        connect()
        player.volume = Volume(20, false)
        rpcBatch(
            "cl",
            "setVolume" to buildJsonObject { put("volume", "26"); put("delta", "9") },
            "setVolume" to buildJsonObject { put("volume", "23"); put("delta", "3") },
            "setVolume" to buildJsonObject { put("volume", "32"); put("delta", "6") },
            "setVolume" to buildJsonObject { put("volume", "29"); put("delta", "6") },
        )
        incomingBarrier()
        assertEquals(listOf("volume:26:false", "volume:32:false"), player.calls.filter { it.startsWith("volume:") })
        assertEquals(Volume(32, false), player.volume)
    }

    @Test
    fun changingButtonDirectionAndUsingSliderRemainResponsive() = run {
        startedApp()
        connect()
        player.volume = Volume(50, false)
        rpcBatch(
            "cl",
            "setVolume" to buildJsonObject { put("volume", "40"); put("delta", "-3") },
            "setVolume" to buildJsonObject { put("volume", "43"); put("delta", "3") },
            "setVolume" to buildJsonObject { put("volume", "25") },
            "setVolume" to buildJsonObject { put("volume", "60"); put("delta", "0") },
            "setVolume" to buildJsonObject { put("volume", "57"); put("delta", -3) },
        )
        incomingBarrier()
        assertEquals(
            listOf("volume:40:false", "volume:43:false", "volume:25:false", "volume:60:false", "volume:57:false"),
            player.calls.filter { it.startsWith("volume:") },
        )
    }

    @Test
    fun directionalRequestUsesCurrentExternalSpeakerVolumeAndReportsItWhenTargetIsStale() = run {
        startedApp()
        connect()
        player.volume = Volume(20, false)
        val before = count("cl", "onVolumeChanged")
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("delta", "-3") })
        awaitUntil(what = "actual external volume reported") { count("cl", "onVolumeChanged") > before }
        assertEquals("20", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"])
        assertTrue(player.calls.none { it.startsWith("volume:") })
    }

    @Test
    fun directionalRequestPreservesMutePolicyWhenLevelIsStale() = run {
        startedApp()
        connect(app = "youtube-desktop")
        player.volume = Volume(20, false)
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("delta", "-3"); put("muted", "true") })
        awaitUntil(what = "speaker muted at current level") { "volume:20:true" in player.calls }
        assertEquals(Volume(20, true), player.volume)
    }

    @Test
    fun invalidVolumeDeltaIsLoggedAndNotApplied() = run {
        startedApp()
        connect()
        player.volume = Volume(20, false)
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40"); put("delta", "bad") })
        awaitUntil(what = "malformed delta logged") {
            events.any { (kind, fields) -> kind == "lounge-app" && fields["reason"] == "delta is not a number" }
        }
        assertTrue(player.calls.none { it.startsWith("volume:") })
        assertEquals(Volume(20, false), player.volume)
    }

    @Test
    fun volumeCommandsDoNotWaitForPlaybackPosition() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val blockedPosition = CompletableDeferred<Unit>()
        player.beforeGetPosition = { blockedPosition.await() }
        val beforeVolume = count("cl", "onVolumeChanged")
        try {
            rpc("cl", "setVolume", buildJsonObject { put("volume", "40") })
            awaitUntil(what = "volume reported without playback position") { count("cl", "onVolumeChanged") > beforeVolume }
            assertEquals("40", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"])
        } finally {
            player.beforeGetPosition = {}
            blockedPosition.complete(Unit)
        }
    }

    @Test
    fun getVolumeCancelsAnOlderQueuedReport() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val before = count("cl", "onVolumeChanged")
        val gate = blockSendQueue()
        try {
            rpcBatch(
                "cl",
                "setVolume" to buildJsonObject { put("volume", "40") },
                "getVolume" to null,
            )
            awaitUntil(what = "volume read") { player.volume.level == 40 }
            incomingBarrier()
        } finally {
            releaseSendQueue(gate)
        }
        awaitUntil(what = "current volume returned") {
            messages("cl").any { it.first == "onVolumeChanged" && it.second["volume"] == "40" }
        }
        delay(300)
        assertEquals(listOf("40"), messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).map { it.second["volume"] })
    }

    @Test
    fun externalVolumeStateCancelsAnOlderQueuedReport() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        val before = count("cl", "onVolumeChanged")
        val gate = blockSendQueue()
        try {
            rpc("cl", "setVolume", buildJsonObject { put("volume", "40") })
            awaitUntil(what = "first volume applied") { player.volume.level == 40 }
            incomingBarrier()
            player.volume = Volume(45, false)
            player.notifyExternalStateChange(null)
        } finally {
            releaseSendQueue(gate)
        }
        awaitUntil(what = "external volume reported") {
            messages("cl").any { it.first == "onVolumeChanged" && it.second["volume"] == "45" }
        }
        delay(300)
        assertEquals(listOf("45"), messages("cl").filter { it.first == "onVolumeChanged" }.drop(before).map { it.second["volume"] })
    }

    @Test
    fun failedVolumeRequestReportsActualVolume() = run {
        startedApp()
        connect()
        player.volume = Volume(30, false)
        player.volumeResult = false
        val before = count("cl", "onVolumeChanged")
        rpc("cl", "setVolume", buildJsonObject { put("volume", "40") })
        awaitUntil(what = "actual volume after failure") { count("cl", "onVolumeChanged") > before }
        assertEquals("30", messages("cl").last { it.first == "onVolumeChanged" }.second["volume"])
    }

    @Test
    fun stopTellsBothSessionsAndReleasesTheListener() = run<Unit> {
        val a = startedApp()
        connect()
        a.stop()
        assertEquals(AppState.STOPPED, a.state)
        assertTrue("loungeScreenDisconnected" in messages("cl").map { it.first })
        assertTrue("loungeScreenDisconnected" in messages("m").map { it.first })
        assertNotNull(store.text(StoreKeys.APP_PID))
    }

    // ---- playback failure ends the cast visibly (quick 261028-ago)

    @Test
    fun aPlaybackFailureTellsStateFourThenDisconnectsAndResets() = run<Unit> {
        val a = startedApp()
        connect()
        castAndPlay("VID00000001")

        player.reportPlaybackFailure("the speaker never started playing")

        awaitUntil(what = "screen disconnected") { count("cl", "loungeScreenDisconnected") == 1 && disconnects.isNotEmpty() }
        val names = messages("cl")
        val four = names.indexOfLast { it.first == "onStateChange" && it.second["state"] == "4" }
        val gone = names.indexOfFirst { it.first == "loungeScreenDisconnected" }
        assertTrue(four >= 0 && four < gone, "state 4 comes before the disconnect: $names")
        assertTrue(a.connectedSenders.isEmpty())
        assertEquals(listOf("dev-1" to false), disconnects.toList())
        assertEquals(PlayerStatus.IDLE, player.status)
        assertEquals(1, clearCount())
        assertTrue(
            names.drop(gone + 1).none { it.first == "onStateChange" },
            "the reset's IDLE is not sent after the disconnect: ${names.map { it.first + ":" + it.second["state"] }}",
        )
    }

    @Test
    fun aPlaybackFailureWithoutASenderPostsNothing() = run<Unit> {
        startedApp()
        player.reportPlaybackFailure("the speaker never started playing")
        barrier("cl")
        assertEquals(0, count("cl", "loungeScreenDisconnected"))
        assertEquals(0, clearCount())
    }

    @Test
    fun theSameSenderCanCastAgainAfterAFailure() = run<Unit> {
        startedApp()
        connect()
        castAndPlay("VID00000001")
        player.reportPlaybackFailure("the speaker never started playing")
        awaitUntil(what = "first disconnect") { disconnects.isNotEmpty() && player.status == PlayerStatus.IDLE }

        connect()
        val before = messages("cl").count { it.first == "onStateChange" && it.second["state"] == "1" }
        rpc("cl", "setPlaylist", playlist("VID00000002", "LIST2"))
        awaitUntil(what = "second video playing") { "play:VID00000002" in player.calls && player.status == PlayerStatus.PLAYING }
        awaitUntil(what = "second playing state") {
            messages("cl").count { it.first == "onStateChange" && it.second["state"] == "1" } > before
        }
        assertEquals(1, count("cl", "loungeScreenDisconnected"))
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

    // ---- a sender that left without an event (plan 03-19, device session C 2026-10-22)

    /** A YouTube Music like sender: no `atp` capability, so autoplay is unsupported while it is connected. */
    private fun musicDevice(id: String, theme: String = "cl") = buildJsonObject {
        put("app", "android.m-phone-9.20.53")
        put("capabilities", "que,pas,dsdtr,rpe,mlm,scn")
        put("clientName", "android")
        put("type", "REMOTE_CONTROL")
        put("name", "TEST PHONE")
        put("theme", theme)
        put("id", id)
    }

    private suspend fun connectMusic(id: String = "music-1") {
        val before = count("cl", "onStateChange")
        rpc("cl", "loungeStatus", loungeStatus(listOf(musicDevice(id)), id))
        awaitUntil(what = "music sender $id connected") { count("cl", "onStateChange") > before && id in connects }
    }

    /** A second sender joins while Music stays: the server lists both. */
    private suspend fun connectAppBesideMusic(id: String) {
        rpc("cl", "loungeStatus", loungeStatus(listOf(musicDevice("music-1"), device(id)), id))
        awaitUntil(what = "app sender $id connected") { id in connects }
    }

    /** The log of 2026-10-22: Music connected, then the YouTube app connected and the status lists only the app. */
    private suspend fun musicThenYouTubeAppWhoseStatusListsOnlyTheApp() {
        connectMusic()
        assertEquals(AutoplayMode.UNSUPPORTED, player.autoplayMode, "Music has no atp")
        connect(id = "app-1")
        barrier("cl")
    }

    @Test
    fun aSenderThatLeftWithoutAnEventStopsBlockingAutoplay() = run {
        val a = startedApp()
        musicThenYouTubeAppWhoseStatusListsOnlyTheApp()

        assertEquals(listOf("music-1" to true), disconnects.toList(), "the ghost is reported as an implicit disconnect")
        assertEquals(listOf("app-1"), a.connectedSenders.map { it.id })
        assertEquals(AutoplayMode.ENABLED, player.autoplayMode)
        assertEquals("ENABLED", messages("cl").last { it.first == "onAutoplayModeChanged" }.second["autoplayMode"])
    }

    @Test
    fun theEndOfTheQueueAsksForTheAutoplayVideoAfterTheGhostIsGone() = run {
        player.queue.requestHandler = ScriptedHandler(mapOf("VID00000001" to PreviousNextVideos(null, vid("VID00000002"))))
        startedApp()
        musicThenYouTubeAppWhoseStatusListsOnlyTheApp()
        castAndPlay("VID00000001")

        rpc("cl", "next")
        barrier("cl")

        assertTrue("play:VID00000002" in player.calls, "the autoplay video is played, not 'autoplay is not enabled': ${player.calls}")
    }

    @Test
    fun aStatusThatListsEverySenderChangesNothing() = run {
        val a = startedApp()
        connectMusic()
        rpc("cl", "loungeStatus", loungeStatus(listOf(musicDevice("music-1"), device("app-1")), "app-1"))
        awaitUntil(what = "app sender connected") { "app-1" in connects }
        rpc("cl", "loungeStatus", loungeStatus(listOf(musicDevice("music-1"), device("app-1")), "app-1", ui = null))
        barrier("cl")

        assertEquals(listOf("music-1", "app-1"), a.connectedSenders.map { it.id })
        assertTrue(disconnects.isEmpty(), "${disconnects.toList()}")
        assertEquals(AutoplayMode.UNSUPPORTED, player.autoplayMode, "Music is still connected")
    }

    @Test
    fun aStatusWithoutADevicesListDropsNobody() = run {
        val a = startedApp()
        connectMusic()
        connectAppBesideMusic("app-2")

        rpc("cl", "loungeStatus", buildJsonObject { })
        rpc("cl", "loungeStatus", buildJsonObject { put("devices", "this is not json") })
        rpc("cl", "loungeStatus", buildJsonObject { put("devices", "{\"not\":\"an array\"}") })
        barrier("cl")

        assertEquals(listOf("music-1", "app-2"), a.connectedSenders.map { it.id })
        assertTrue(disconnects.isEmpty(), "${disconnects.toList()}")
    }

    @Test
    fun anInactiveSessionsStatusDoesNotDropTheActiveSessionsSenders() = run {
        val a = startedApp()
        connectMusic()
        connectAppBesideMusic("app-3")

        // the other theme's session is not the active one; its status lists nobody
        rpc("m", "loungeStatus", loungeStatus(emptyList(), "nobody"))
        barrier("m")
        barrier("cl")

        assertEquals(listOf("music-1", "app-3"), a.connectedSenders.map { it.id })
        assertTrue(disconnects.isEmpty(), "${disconnects.toList()}")
        assertEquals(Client.YT, a.activeClient)
    }
}
