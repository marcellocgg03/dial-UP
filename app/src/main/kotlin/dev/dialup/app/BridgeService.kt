package dev.dialup.app

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.dialup.app.model.NetworkLossWatcher
import dev.dialup.app.model.WIFI_REBIND_WINDOW_MS
import dev.dialup.app.shouldShowCastNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.Inet4Address

/** Process-level handle so the command receiver can reach the running service. */
object ServiceHub {
    private val serviceState = MutableStateFlow<BridgeService?>(null)

    /** The running service as a flow, so the GUI follows it live. */
    val serviceFlow: StateFlow<BridgeService?> = serviceState.asStateFlow()

    var service: BridgeService?
        get() = serviceState.value
        set(value) {
            serviceState.value = value
        }

    /** The cast feature once the service has started its features; null otherwise. */
    val cast = MutableStateFlow<CastFeature?>(null)
}

/**
 * The bridge's foreground service (type connectedDevice). Holds a MulticastLock for its whole
 * lifetime and a WifiLock and a timed partial WakeLock while casting (D-16, [LockPolicy]), writes a heartbeat every 30 s and a state.json snapshot
 * that `tools/spike.sh dump-state` reads. The event log goes to the app external files dir
 * (`files/logs/`, `tools/pull-logs.sh`); state.json stays in the internal evidence dir.
 *
 * Sticky on purpose since Phase 5 (D-01): a system kill shows as an exit-info entry followed by
 * `service-start reason=sticky`. The notification Stop action and a denied start return
 * START_NOT_STICKY, so they are not restarted; Stop also persists the switch off (plan 07-04). A denied start
 * (no battery exemption, D-12, or the switch off, D-04) shows as `service-gate denied` and nothing else: no lock, no feature, no socket. The work starts
 * from the first onStartCommand, which knows the start reason ([startReasonOf]).
 */
class BridgeService : Service(), SpikeHost {
    override lateinit var scope: CoroutineScope
        private set
    override lateinit var evidence: EvidenceLog
        private set
    override lateinit var evidenceDir: File
        private set
    override lateinit var spikeDir: File
        private set

    override val context: Context get() = applicationContext

    /** Features started in onCreate; read by CommandReceiver to route commands. */
    @Volatile
    var features: List<SpikeFeature> = emptyList()
        private set

    private lateinit var mcastLock: WifiManager.MulticastLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wifiLockMode = ""
    private val playLocks = LockPolicy(clock = SystemClock::elapsedRealtime)
    private val startedAtMs = SystemClock.elapsedRealtime()
    private var bootstrapped = false
    private var startForegroundFailed = false
    private var foregroundActive = false
    private var logDirFact: File? = null

    /** The cast count last seen by [castCountLoop]; the heartbeat carries it so a sleep window shows whether casting was on. */
    @Volatile
    private var casting = 0
    private var lastNotifiedText: String? = notificationText(CastCount(0, 0))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // an exception nobody handles on this scope would end the process; log it instead (plan 05-19, WR-01). The scope
        // exists before the evidence log does, so a failure in that window goes to logcat.
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + loggingExceptionHandler { where, t ->
                if (::evidence.isInitialized) {
                    evidence.event("feature-error", featureErrorFields(where, t))
                } else {
                    android.util.Log.e("t2u", "$where: ${t.javaClass.name}: ${t.message}")
                }
            },
        )

        // Pitfall 12: channel first, startForeground before anything slow. A refusal (A9) is logged
        // and stops the service instead of crashing it.
        var foregroundError: Throwable? = null
        try {
            createChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(CastCount(0, 0), ongoing = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundActive = true
        } catch (t: Throwable) {
            foregroundError = t
        }

        evidenceDir = File(filesDir, "evidence").also { it.mkdirs() }
        spikeDir = File(filesDir, "spike").also { it.mkdirs() }
        // RS-04: the rotating event log is app-private (filesDir/evidence), one shared writer for the process (CR-01).
        val logDir = eventLogDir(filesDir)
        logDir.mkdirs()
        evidence = EvidenceLog.shared(logDir)
        // D-11: the level from config.json applies from the first event (loadAppConfig never throws, reads at most 64 KiB).
        evidence.setLevel(logLevelOf(loadAppConfig(filesDir).config.logLevel))
        logDirFact = logDir
        scope.launch { removeLegacyLogs() }
        if (foregroundError != null) {
            startForegroundFailed = true
            evidence.event(
                "feature-error",
                mapOf("where" to "startForeground", "error" to foregroundError.javaClass.name, "message" to foregroundError.message),
            )
            stopSelf()
        }
    }

    /**
     * RS-04: earlier builds wrote the event log to the shared external files directory (logs/, readable by other apps on Android 8
     * to 10). Removes those files and the directory once per start; best effort, every failure is swallowed.
     */
    private fun removeLegacyLogs() {
        try {
            val dir = File(getExternalFilesDir(null) ?: return, "logs")
            if (!dir.isDirectory) return
            var removed = 0
            dir.listFiles()?.forEach { f ->
                if ((f.name.startsWith("spike-events.jsonl") || f.name.startsWith("dialup-events.jsonl")) && f.delete()) removed++
            }
            dir.delete() // only succeeds when empty
            if (removed > 0) evidence.event("legacy-log-removed", mapOf("count" to removed))
        } catch (t: Throwable) {
            Failures.report("legacy-log-cleanup", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startForegroundFailed) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            evidence.event("cmd", mapOf("name" to "stop", "source" to "notification"))
            // the author's own Stop: persist off so no start path (reboot, reopen, sticky) turns the bridge back on (D-04)
            AppSettings.of(this).update { it.copy(bridgeEnabled = false) }
            evidence.event("settings", mapOf("bridgeEnabled" to false, "source" to "notification"))
            NetworkResume.disarm(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!bootstrapped) {
            bootstrapped = true
            val reason = startReasonOf(intent == null, intent?.action, intent?.getStringExtra(EXTRA_START_REASON))
            if (!bootstrap(reason)) return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Everything slow, behind the battery gate (D-04, D-12: the same gate for open, boot and sticky
     * starts). Returns false when the gate is shut; the service has then asked to stop and holds no lock.
     */
    private fun bootstrap(reason: StartReason): Boolean {
        val exempt = BatteryGate.isOpen(this)
        // D-04: the author's switch, persisted; a sticky restart after it went off is denied below
        val enabled = AppSettings.of(this).data.value.bridgeEnabled
        evidence.event(
            "service-start",
            mapOf(
                "pid" to android.os.Process.myPid(),
                "sdk" to Build.VERSION.SDK_INT,
                "version" to BuildConfigFacts.versionName(this),
                "reason" to reason.wire,
                "exempt" to exempt,
                "enabled" to enabled,
            ),
        )
        logDirFact?.let { logDir -> evidence.event("logdir", mapOf("where" to "internal", "path" to logDir.absolutePath)) }
        if (!gateDecision(exempt, enabled, reason)) {
            evidence.event("service-gate", mapOf("denied" to true, "reason" to reason.wire, "exempt" to exempt, "enabled" to enabled))
            stopSelf()
            return false
        }
        NetworkResume.disarm(this)
        acquireLocks()
        // published only now: currentState() and heartbeatFields() read the lateinit locks that acquireLocks() sets (WR-09)
        ServiceHub.service = this
        logExitInfo()
        startFeatures()
        scope.launch { castCountLoop() }
        scope.launch { networkLossLoop() }
        scope.launch { heartbeatLoop() }
        return true
    }

    /**
     * Returns without waiting for the bridge shutdown: [CastFeature.stop] hands it to [BridgeLifecycle.scope] (off the main
     * thread, outliving this service) and every start path waits on [BridgeLifecycle.awaitIdle], so clearing
     * [ServiceHub.service] early is safe (plan 07-20, review WR-02).
     */
    override fun onDestroy() {
        evidence.event("service-stop")
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        ServiceHub.cast.value = null
        stopFeatures()
        releaseLocks()
        scope.cancel()
        ServiceHub.service = null
        evidence.flush()
        super.onDestroy()
    }

    // ----- features ------------------------------------------------------------------------------

    private fun startFeatures() {
        val all = SpikeFeatures.all()
        features = all
        for (feature in all) {
            try {
                feature.start(this)
            } catch (t: Throwable) {
                // a failing feature must not stop the others or the service
                evidence.event(
                    "feature-error",
                    mapOf("where" to "${feature.name}:start", "error" to t.javaClass.name, "message" to t.message),
                )
            }
        }
        ServiceHub.cast.value = all.filterIsInstance<CastFeature>().firstOrNull()
    }

    private fun stopFeatures() {
        for (feature in features.asReversed()) {
            try {
                feature.stop()
            } catch (t: Throwable) {
                evidence.event(
                    "feature-error",
                    mapOf("where" to "${feature.name}:stop", "error" to t.javaClass.name, "message" to t.message),
                )
            }
        }
    }

    /** Blocking; throws IllegalStateException on the main thread (see [NativePayloads.ensure]). */
    override fun payloads(): NativePayloads.Paths = NativePayloads.ensure(applicationContext, evidence)

    // ----- locks ---------------------------------------------------------------------------------

    /**
     * Creates the three locks and acquires only the multicast lock (D-16): the Wi-Fi and wake locks
     * follow the cast count through [applyPlayLocks].
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val power = getSystemService(PowerManager::class.java)

        mcastLock = wifi.createMulticastLock("t2u:mcast").apply { setReferenceCounted(false) }
        val (mode, modeName) =
            if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY to "FULL_LOW_LATENCY"
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF to "FULL_HIGH_PERF"
            }
        wifiLockMode = modeName
        wifiLock = wifi.createWifiLock(mode, "t2u:wifi").apply { setReferenceCounted(false) }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "t2u:cpu").apply { setReferenceCounted(false) }

        mcastLock.acquire()
        evidence.event("lock", mapOf("name" to "t2u:mcast", "mode" to "multicast", "isHeld" to mcastLock.isHeld, "reason" to "service"))
    }

    /**
     * Holds the Wi-Fi lock and the partial wake lock while [hold] (a speaker is casting or the 60 s
     * grace is running) and releases both otherwise (D-16, SVC-03). The wake lock always carries
     * [WAKE_LOCK_TIMEOUT_MS] and is re-armed on every call while held (reference counting is off), so
     * a stuck loop cannot keep the CPU awake for ever (Pitfall 9). Only transitions are logged.
     */
    @Synchronized
    private fun applyPlayLocks(hold: Boolean, reason: String) {
        // a denied or failed start never created the locks
        if (!::wifiLock.isInitialized || !::wakeLock.isInitialized) return
        if (hold) {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
                evidence.event("lock", mapOf("name" to "t2u:wifi", "mode" to wifiLockMode, "isHeld" to wifiLock.isHeld, "reason" to "casting"))
            }
            val wakeWasHeld = wakeLock.isHeld
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            if (!wakeWasHeld) {
                evidence.event("lock", mapOf("name" to "t2u:cpu", "mode" to "PARTIAL_WAKE_LOCK", "isHeld" to wakeLock.isHeld, "reason" to "casting"))
            }
        } else if (wifiLock.isHeld || wakeLock.isHeld) {
            releaseOne("t2u:wifi", { wifiLock.isHeld }, { wifiLock.release() }, "idle-grace")
            releaseOne("t2u:cpu", { wakeLock.isHeld }, { wakeLock.release() }, "idle-grace")
        }
    }

    private fun releaseLocks() {
        // a denied or failed start never created the locks
        if (::mcastLock.isInitialized) releaseOne("t2u:mcast", { mcastLock.isHeld }, { mcastLock.release() })
        if (::wifiLock.isInitialized) releaseOne("t2u:wifi", { wifiLock.isHeld }, { wifiLock.release() })
        if (::wakeLock.isInitialized) releaseOne("t2u:cpu", { wakeLock.isHeld }, { wakeLock.release() })
    }

    private fun releaseOne(name: String, isHeld: () -> Boolean, release: () -> Unit, reason: String = "service-stop") {
        try {
            if (isHeld()) release()
        } catch (t: Throwable) {
            evidence.event("feature-error", mapOf("where" to "release:$name", "error" to t.javaClass.name, "message" to t.message))
        }
        evidence.event("lock", mapOf("name" to name, "isHeld" to isHeld(), "reason" to reason))
    }

    // ----- wifi helpers --------------------------------------------------------------------------

    private fun wifiLinkProperties(): Pair<Inet4Address?, String?> = bindableLink(this)

    override fun wifiIpv4(): Inet4Address? = wifiLinkProperties().first

    override fun wifiInterfaceName(): String? = wifiLinkProperties().second

    // ----- heartbeat and state -------------------------------------------------------------------

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                applyPlayLocks(playLocks.shouldHold(), "tick")
                val link = wifiLinkProperties() // one lookup per tick (WR-09)
                val state = currentState(link)
                evidence.event("heartbeat", heartbeatFields(link))
                writeStateFile(state)
            } catch (t: Throwable) {
                evidence.event("feature-error", mapOf("where" to "heartbeat", "error" to t.javaClass.name, "message" to t.message))
            }
            delay(HEARTBEAT_MS)
        }
    }

    private fun heartbeatFields(link: Pair<Inet4Address?, String?>): Map<String, Any?> {
        val power = getSystemService(PowerManager::class.java)
        return mapOf(
            "mcast" to mcastLock.isHeld,
            "wifi" to wifiLock.isHeld,
            "wake" to wakeLock.isHeld,
            "wifiIp" to link.first?.hostAddress,
            "iface" to link.second,
            "idle" to power.isDeviceIdleMode,
            "interactive" to power.isInteractive,
            "ignoringBatteryOpt" to power.isIgnoringBatteryOptimizations(packageName),
            "uptimeMs" to (SystemClock.elapsedRealtime() - startedAtMs),
            // absolute clocks: elapsedRealtime counts deep sleep, uptimeMillis does not, so the
            // difference between two heartbeats shows how long the CPU slept (Pitfall 3)
            "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
            "uptimeMillis" to SystemClock.uptimeMillis(),
            "standbyBucket" to standbyBucket(),
            "casting" to casting,
        )
    }

    /** The app's own standby bucket (API 28+, no permission needed for one's own package), else null. */
    private fun standbyBucket(): Int? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            getSystemService(android.app.usage.UsageStatsManager::class.java)?.appStandbyBucket
        } catch (_: Throwable) {
            null
        }
    }

    /** Live state snapshot; also the content of evidence/state.json and the dump-state reply. */
    fun currentState(link: Pair<Inet4Address?, String?> = wifiLinkProperties()): JsonObject {
        val power = getSystemService(PowerManager::class.java)
        val ip = link.first?.hostAddress
        val iface = link.second
        return buildJsonObject {
            put("mcast", mcastLock.isHeld)
            put("wifi", wifiLock.isHeld)
            put("wake", wakeLock.isHeld)
            put("wifiLockMode", wifiLockMode)
            put("ip", ip?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            put("iface", iface?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            put("idle", power.isDeviceIdleMode)
            put("interactive", power.isInteractive)
            put("ignoringBatteryOpt", power.isIgnoringBatteryOptimizations(packageName))
            put("uptimeMs", SystemClock.elapsedRealtime() - startedAtMs)
            put("pid", android.os.Process.myPid())
            for (feature in features) {
                val featureState = try {
                    feature.state()
                } catch (t: Throwable) {
                    buildJsonObject { put("error", "${t.javaClass.name}: ${t.message}") }
                }
                put(feature.name, featureState)
            }
        }
    }

    private fun writeStateFile(state: JsonObject) {
        // temp file then atomic replace, so a reader never sees a half-written state.json; a failure is thrown to the
        // heartbeat loop, which logs it as a feature-error (WR-09)
        writeTextAtomic(evidenceDir, "state.json", state.toString())
    }

    // ----- exit info and notification ------------------------------------------------------------

    private fun logExitInfo() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(packageName, 0, 5)) {
                evidence.event(
                    "exit-info",
                    mapOf(
                        "reason" to info.reason,
                        "status" to info.status,
                        "timestamp" to info.timestamp,
                        "description" to info.description,
                    ),
                )
            }
        } catch (t: Throwable) {
            evidence.event("feature-error", mapOf("where" to "exit-info", "error" to t.javaClass.name, "message" to t.message))
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bridge Status", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(count: CastCount, ongoing: Boolean = foregroundActive): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DialUP")
            .setContentText(notificationText(count))
            .setSmallIcon(R.drawable.ic_stat_dialup)
            .setOngoing(ongoing)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateCastNotification(count: CastCount, wifiAvailable: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!shouldShowCastNotification(count, wifiAvailable)) {
            if (foregroundActive) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundActive = false
            } else {
                manager.cancel(NOTIFICATION_ID)
            }
            lastNotifiedText = null
            return
        }

        val text = notificationText(count)
        if (!foregroundActive) {
            val promotionError = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    startCastForeground(count)
                    null
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    e
                } catch (e: SecurityException) {
                    e
                }
            } else {
                try {
                    startCastForeground(count)
                    null
                } catch (e: SecurityException) {
                    e
                }
            }
            if (promotionError == null) {
                foregroundActive = true
                lastNotifiedText = text
                return
            }
            evidence.event(
                "feature-error",
                mapOf("where" to "promote-foreground", "error" to promotionError.javaClass.name, "message" to promotionError.message),
            )
        }

        if (text != lastNotifiedText) {
            lastNotifiedText = text
            manager.notify(NOTIFICATION_ID, buildNotification(count))
        }
    }

    private fun startCastForeground(count: CastCount) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(count, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /**
     * The Wi-Fi-loss decision. A short drop keeps the bridge available for in-place rebind and playback resync.
     * A continuous outage beyond [WIFI_REBIND_WINDOW_MS] stops the bridge/service without sending Stop or Clear to
     * speakers; [NetworkResume] starts it again when Wi-Fi returns.
     */
    private suspend fun networkLossLoop() {
        val sources = features.filterIsInstance<NetworkStateSource>()
        if (sources.isEmpty()) return
        val present: Flow<Boolean> = combine(sources.map { it.wifiPresent }) { all -> all.all { it } }
        val castingNow: Flow<Int> = sumNetworkLossCastCounts(features.filterIsInstance<CastCountSource>()).map { it.casting }
        NetworkLossWatcher(present, castingNow, SystemClock::elapsedRealtime) { action, downMs ->
            if (!AppSettings.of(this).data.value.bridgeEnabled) {
                evidence.event("net-pause", mapOf("skipped" to "switch-off"))
                return@NetworkLossWatcher
            }
            evidence.event(
                "net-pause",
                mapOf("action" to action.wire, "downMs" to downMs, "windowMs" to WIFI_REBIND_WINDOW_MS, "casting" to casting),
            )
            features.filterIsInstance<CastFeature>().firstOrNull()?.onNetworkLossExpired()
            NetworkResume.arm(this)
            stopSelf()
        }.run()
    }

    /**
     * The count is derived from feature state (every [CastCountSource] feature), never from events.
     * The notification is re-posted only when its text differs from the last one posted (Pitfall 11).
     */
    private suspend fun castCountLoop() {
        val sources = features.filterIsInstance<NetworkStateSource>()
        val noNetwork: Flow<Boolean> =
            if (sources.isEmpty()) {
                flowOf(false)
            } else {
                combine(sources.map { it.networkUp }) { ups -> ups.all { it } }
                    .combine(combine(sources.map { it.wifiPresent }) { present -> present.all { it } }) { up, present -> !up || !present }
                    .distinctUntilChanged()
            }
        var lastCount: CastCount? = null
        combine(sumCastCounts(features.filterIsInstance<CastCountSource>()), noNetwork) { count, unavailable -> count to unavailable }.collect { (count, unavailable) ->
            if (count != lastCount) {
                lastCount = count
                casting = count.casting
                applyPlayLocks(playLocks.onCasting(count.casting), "casting")
                evidence.event("cast-count", mapOf("casting" to count.casting, "total" to count.total))
            }
            updateCastNotification(count, wifiAvailable = !unavailable)
        }
    }

    companion object {
        const val ACTION_STOP = "dev.dialup.STOP"
        const val CHANNEL_ID = "dialup"
        const val NOTIFICATION_ID = 1
        const val HEARTBEAT_MS = 30_000L
    }
}

/** Tiny helper kept out of the service body so BuildConfig (disabled) is not needed. */
internal object BuildConfigFacts {
    fun versionName(context: Context): String? =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            null
        }
}
