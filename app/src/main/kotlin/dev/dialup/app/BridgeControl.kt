// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.dialup.app.model.SingleFlight
import dev.dialup.app.model.StoppedWork
import dev.dialup.app.model.awaitNull
import dev.dialup.app.model.StartDecision
import dev.dialup.app.model.restartSequence
import dev.dialup.app.model.startDecision
import dev.dialup.app.model.startWanted
import dev.dialup.app.model.startWhenStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only code that starts or stops the bridge service from the UI. The switch is one persisted
 * flag ([AppSettingsData.bridgeEnabled]); every start path reads it (the service bootstrap, [BootReceiver] and
 * the app's own start), so off stays off until [setEnabled] turns it on again.
 */
object BridgeControl {
    /**
     * Persists the switch and applies it, as one task on [SettingsWriter]: the persist and the start or stop run on the settings
     * writer thread in that order, never on the caller's thread, and this returns at once (the [Job] is for callers that want to
     * wait). The start decision comes after the persist in the same task, so the service bootstrap, which reads the switch
     * again, never sees the old value. On: starts the service when the battery exemption is held and it is not already
     * running. Off: stops the running service, which stops discovery, DIAL, the media server, polling and the locks with it.
     * [source] (ui, restart) goes into the `settings` event. [alsoStop] is a service that must stop after the persist even
     * when it is not the published one (the notification Stop passes the instance that received the intent).
     */
    fun setEnabled(context: Context, on: Boolean, source: String, alsoStop: Service? = null): Job {
        val app = context.applicationContext
        return SettingsWriter.post {
            AppSettings.of(app).update { it.copy(bridgeEnabled = on) }
            // On while the old service is still being destroyed (a quick off-then-on flip): the start waits for it
            val deferred = on && ServiceHub.service != null
            val details = mutableMapOf<String, Any?>("bridgeEnabled" to on, "source" to source)
            if (deferred) details["deferred"] = true
            // the instance that got the notification intent logs it, which may not be the published one
            ((alsoStop as? BridgeService)?.evidence ?: ServiceHub.service?.evidence)?.event("settings", details)
            if (on) {
                applyStart(app, enabled = true, deferred = deferred)
            } else {
                NetworkResume.disarm(app)
                ServiceHub.service?.stopSelf()
                alsoStop?.stopSelf()
            }
        }
    }

    /**
     * Wi-Fi is back after the bridge stopped itself for lack of a network ([NetworkResume]): starts the service again when the
     * switch is still on and the battery exemption is held. A start the system refuses is only logged; the callback stays armed
     * so the next network event tries again, and opening the app or flipping the switch starts the bridge by hand.
     */
    fun resumeAfterNetwork(context: Context) {
        val app = context.applicationContext
        if (ServiceHub.service != null) return
        if (!AppSettings.of(app).data.value.bridgeEnabled || !BatteryGate.isOpen(app)) {
            NetworkResume.disarm(app)
            return
        }
        try {
            ContextCompat.startForegroundService(app, Intent(app, BridgeService::class.java))
            Log.i("t2u", "network resume: started the bridge service")
        } catch (t: Throwable) {
            Log.w("t2u", "network resume: the system refused the start: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * The start decision behind [setEnabled] (on) and the end of maintenance: nothing when the switch is off or maintenance runs,
     * the deferred start while a service is still going away, otherwise the start or the wait for Wi-Fi. Runs on [SettingsWriter].
     */
    private fun applyStart(app: Context, enabled: Boolean, deferred: Boolean) {
        if (!enabled) return
        if (deferred) {
            startAfterStop(app)
            return
        }
        val wanted = startWanted(enabled, MaintenanceMarker.isActive(app))
        when (startDecision(wanted, BatteryGate.isOpen(app), false, hasBindableNetwork(app))) {
            StartDecision.START -> startService(app)
            StartDecision.WAIT_FOR_WIFI -> NetworkResume.arm(app)
            StartDecision.NONE -> Unit
        }
    }

    /**
     * The stop half of a restart or an update: the maintenance marker holds every start path off, the network resume is
     * disarmed and the running service is asked to stop. The saved switch is not written.
     */
    private fun beginMaintenance(app: Context, source: String) {
        MaintenanceMarker.begin(app)
        ServiceHub.service?.evidence?.event("maintenance", mapOf("state" to "begin", "source" to source))
        NetworkResume.disarm(app)
        ServiceHub.service?.stopSelf()
    }

    /**
     * The resume half: on the settings writer, so it runs after every queued settings write. Only the last of overlapping work
     * ends the marker and decides; it reads the saved switch, so an author who switched the bridge off meanwhile keeps it off.
     * A service still going away (the wait timed out) gets the deferred start, never a start sent to the dying service.
     */
    private suspend fun endMaintenance(app: Context, source: String) {
        SettingsWriter.run {
            if (!MaintenanceMarker.end(app)) return@run
            try {
                (ServiceHub.service?.evidence ?: offlineEvidence(app.filesDir)).event("maintenance", mapOf("state" to "end", "source" to source))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("t2u", "maintenance end event failed: ${t.javaClass.name}")
            }
            applyStart(app, AppSettings.of(app).data.value.bridgeEnabled, ServiceHub.service != null)
        }
    }

    private val startFlight = SingleFlight<Boolean>()

    /**
     * Starts the service once the old one is gone, when the switch is still on and the battery exemption is held. A
     * service that is running and not stopping simply times out the wait and nothing happens.
     */
    private fun startAfterStop(app: Context) {
        startFlight.launch(BridgeLifecycle.scope) {
            startWhenStopped(
                awaitGone = { awaitServiceGone() },
                stillWanted = {
                    AppSettings.of(app).data.value.bridgeEnabled && BatteryGate.isOpen(app) && ServiceHub.service == null
                },
                start = { if (hasBindableNetwork(app)) startService(app) else NetworkResume.arm(app) },
            )
        }
    }

    private fun startService(app: Context) {
        try {
            // reason extra unset: the service reads this as an open (the author turned it on)
            ContextCompat.startForegroundService(app, Intent(app, BridgeService::class.java))
        } catch (t: Throwable) {
            // a foreground-service start from the background can be denied on Android 12+
            Log.w("t2u", "bridge start refused: ${t.javaClass.name}: ${t.message}")
        }
    }

    private val restartFlight = SingleFlight<Boolean>()

    /**
     * Restarts the bridge: stop it (the maintenance marker holds every start path off, the saved switch stays on), wait up to
     * 15 s for the old service to be gone and for the process-wide stop, then start it again. Only call it after [dev.dialup.app.model.restartStep] said RESTART_NOW or the author confirmed (the
     * restart prompt does that): a restart ends playback like a switch-off does. The stop-then-start order avoids starting
     * the service while the old instance is still being destroyed. The sequence runs on [BridgeLifecycle.scope], so closing
     * or recreating the Activity mid-restart cannot cancel it, and its resume is non-cancellable (it reads the saved switch, so a switch-off made meanwhile is kept). A second restart
     * while one runs joins it. Returns false when the old service did not stop in time. [source] lands in the `settings`
     * evidence events: "restart" for the author's button, "auto-restart" for the restart an app open makes.
     */
    suspend fun restart(context: Context, source: String = "restart"): Boolean {
        val app = context.applicationContext
        return restartFlight.launch(BridgeLifecycle.scope) {
            restartSequence(
                stop = { beginMaintenance(app, source) },
                awaitStopped = { awaitServiceGone() },
                resume = { endMaintenance(app, source) },
            )
        }.await()
    }

    private val stoppedWork = StoppedWork(BridgeLifecycle.scope)

    /**
     * Runs [block] with the bridge stopped, then starts it again: for work that cannot run while a renderer is being cast to (a
     * yt-dlp update). Only a bridge that is running is stopped and started again; with the bridge off, or never started, [block]
     * simply runs. The saved switch is never written: the maintenance marker holds every start path off meanwhile, so a process
     * killed in the middle leaves the switch on. A second caller while one runs joins the first flight and gets its result (its
     * own [block] does not run; the bridge is stopped once); when the bridge did not stop in time every caller gets
     * IllegalStateException. The bridge comes back in all cases once the stop was requested, also when [block] throws or is
     * cancelled and when the stop timed out, unless the author switched it off meanwhile (the resume reads the saved switch).
     * It ends playback like a switch-off does, so call it only after the author agreed. The sequence runs on
     * [BridgeLifecycle.scope]; [source] lands in the `maintenance` evidence events.
     */
    suspend fun <T> whileStopped(context: Context, source: String, block: suspend () -> T): T {
        val app = context.applicationContext
        if (!stoppedWork.active && ServiceHub.service == null && !MaintenanceMarker.isActive(app)) return block()
        return stoppedWork.run(
            around = { body ->
                restartSequence(
                    stop = { beginMaintenance(app, source) },
                    awaitStopped = {
                        val stopped = awaitServiceGone()
                        // a bridge that is still running keeps the work away: nothing changes under an active cast
                        if (stopped) body()
                        stopped
                    },
                    resume = { endMaintenance(app, source) },
                )
            },
            block = block,
        )
    }

    /** True when the service is gone and no stop is in flight, each within [STOP_TIMEOUT_MS]. */
    private suspend fun awaitServiceGone(): Boolean =
        awaitNull(ServiceHub.serviceFlow, STOP_TIMEOUT_MS) &&
            BridgeLifecycle.awaitIdle(STOP_TIMEOUT_MS)

    private const val STOP_TIMEOUT_MS = 15_000L
}
