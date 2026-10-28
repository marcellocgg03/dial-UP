package dev.t2u.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    @Volatile
    var service: BridgeService? = null
}

/**
 * The bridge's foreground service (type connectedDevice). Holds a MulticastLock, a WifiLock and a
 * partial WakeLock for its whole lifetime, writes a heartbeat every 30 s and a state.json snapshot
 * that `tools/spike.sh dump-state` reads. The event log goes to the app external files dir
 * (`files/logs/`, `tools/pull-logs.sh`); state.json stays in the internal evidence dir.
 *
 * Sticky on purpose since Phase 5 (D-01): a system kill shows as an exit-info entry followed by
 * `service-start reason=sticky`. The notification Stop action and a denied start return
 * START_NOT_STICKY, so they are not restarted. A denied start (no battery exemption, D-04 and D-12)
 * shows as `service-gate denied` and nothing else: no lock, no feature, no socket. The work starts
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
    private val startedAtMs = SystemClock.elapsedRealtime()
    private var bootstrapped = false
    private var startForegroundFailed = false
    private var logDirFact: Pair<File, String>? = null

    /** The cast count last seen by [castCountLoop]; the heartbeat carries it so a sleep window shows whether casting was on. */
    @Volatile
    private var casting = 0
    private var lastNotifiedText: String = notificationText(CastCount(0, 0))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Pitfall 12: channel first, startForeground before anything slow. A refusal (A9) is logged
        // and stops the service instead of crashing it.
        var foregroundError: Throwable? = null
        try {
            createChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(CastCount(0, 0)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (t: Throwable) {
            foregroundError = t
        }

        evidenceDir = File(filesDir, "evidence").also { it.mkdirs() }
        spikeDir = File(filesDir, "spike").also { it.mkdirs() }
        // D-08: the rotating event log goes to the app external files dir so `adb pull` reads it
        // without run-as; state.json and payloads.json stay in the internal evidence dir.
        val (logDir, where) = logDirFor(getExternalFilesDir(null), filesDir)
        logDir.mkdirs()
        evidence = EvidenceLog(logDir)
        // D-11: the level from config.json applies from the first event (loadAppConfig never throws, reads at most 64 KiB).
        evidence.setLevel(logLevelOf(loadAppConfig(filesDir).config.logLevel))
        logDirFact = logDir to where
        if (foregroundError != null) {
            startForegroundFailed = true
            evidence.event(
                "feature-error",
                mapOf("where" to "startForeground", "error" to foregroundError.javaClass.name, "message" to foregroundError.message),
            )
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startForegroundFailed) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            evidence.event("cmd", mapOf("name" to "stop", "source" to "notification"))
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
        evidence.event(
            "service-start",
            mapOf(
                "pid" to android.os.Process.myPid(),
                "sdk" to Build.VERSION.SDK_INT,
                "version" to BuildConfigFacts.versionName(this),
                "reason" to reason.wire,
                "exempt" to exempt,
            ),
        )
        logDirFact?.let { (logDir, where) -> evidence.event("logdir", mapOf("where" to where, "path" to logDir.absolutePath)) }
        if (!gateDecision(exempt, reason)) {
            evidence.event("service-gate", mapOf("denied" to true, "reason" to reason.wire))
            stopSelf()
            return false
        }
        ServiceHub.service = this
        acquireLocks()
        logExitInfo()
        startFeatures()
        scope.launch { castCountLoop() }
        scope.launch { heartbeatLoop() }
        return true
    }

    override fun onDestroy() {
        evidence.event("service-stop")
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

    @SuppressLint("WakelockTimeout")
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
        wifiLock.acquire()
        wakeLock.acquire()

        evidence.event("lock", mapOf("name" to "t2u:mcast", "mode" to "multicast", "isHeld" to mcastLock.isHeld))
        evidence.event("lock", mapOf("name" to "t2u:wifi", "mode" to modeName, "isHeld" to wifiLock.isHeld))
        evidence.event("lock", mapOf("name" to "t2u:cpu", "mode" to "PARTIAL_WAKE_LOCK", "isHeld" to wakeLock.isHeld))
    }

    private fun releaseLocks() {
        // a denied or failed start never created the locks
        if (::mcastLock.isInitialized) releaseOne("t2u:mcast", { mcastLock.isHeld }, { mcastLock.release() })
        if (::wifiLock.isInitialized) releaseOne("t2u:wifi", { wifiLock.isHeld }, { wifiLock.release() })
        if (::wakeLock.isInitialized) releaseOne("t2u:cpu", { wakeLock.isHeld }, { wakeLock.release() })
    }

    private fun releaseOne(name: String, isHeld: () -> Boolean, release: () -> Unit) {
        try {
            if (isHeld()) release()
        } catch (t: Throwable) {
            evidence.event("feature-error", mapOf("where" to "release:$name", "error" to t.javaClass.name, "message" to t.message))
        }
        evidence.event("lock", mapOf("name" to name, "isHeld" to isHeld()))
    }

    // ----- wifi helpers --------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wifiLinkProperties(): Pair<Inet4Address?, String?> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null to null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val props = cm.getLinkProperties(network) ?: continue
            val v4 = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
            return v4 to props.interfaceName
        }
        return null to null
    }

    override fun wifiIpv4(): Inet4Address? = wifiLinkProperties().first

    override fun wifiInterfaceName(): String? = wifiLinkProperties().second

    // ----- heartbeat and state -------------------------------------------------------------------

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                val state = currentState()
                evidence.event("heartbeat", heartbeatFields())
                writeStateFile(state)
            } catch (t: Throwable) {
                evidence.event("feature-error", mapOf("where" to "heartbeat", "error" to t.javaClass.name, "message" to t.message))
            }
            delay(HEARTBEAT_MS)
        }
    }

    private fun heartbeatFields(): Map<String, Any?> {
        val power = getSystemService(PowerManager::class.java)
        return mapOf(
            "mcast" to mcastLock.isHeld,
            "wifi" to wifiLock.isHeld,
            "wake" to wakeLock.isHeld,
            "wifiIp" to wifiIpv4()?.hostAddress,
            "iface" to wifiInterfaceName(),
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
    fun currentState(): JsonObject {
        val power = getSystemService(PowerManager::class.java)
        val ip = wifiIpv4()?.hostAddress
        val iface = wifiInterfaceName()
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
        // temp file then rename, so a reader never sees a half-written state.json
        val tmp = File(evidenceDir, "state.json.tmp")
        tmp.writeText(state.toString())
        tmp.renameTo(File(evidenceDir, "state.json"))
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tube2U", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(count: CastCount): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tube2U (app)")
            .setContentText(notificationText(count))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    /**
     * The count is derived from feature state (every [CastCountSource] feature), never from events.
     * The notification is re-posted only when its text differs from the last one posted (Pitfall 11).
     */
    private suspend fun castCountLoop() {
        sumCastCounts(features.filterIsInstance<CastCountSource>()).collect { count ->
            casting = count.casting
            evidence.event("cast-count", mapOf("casting" to count.casting, "total" to count.total))
            val text = notificationText(count)
            if (text != lastNotifiedText) {
                lastNotifiedText = text
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(count))
            }
        }
    }

    companion object {
        const val ACTION_STOP = "dev.t2u.spike.STOP"
        const val CHANNEL_ID = "t2u-spike"
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
