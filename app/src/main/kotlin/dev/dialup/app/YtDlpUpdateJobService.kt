// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import dev.dialup.app.model.YtDlpJobAction
import dev.dialup.app.model.ytdlpJobAction
import dev.t2u.media.YtDlpUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Id of the daily yt-dlp update job. It is persisted OS state: renaming it later needs a cancel of the old id. */
const val YTDLP_JOB_ID = 7101

/** Longest one run may work; the system would call onStopJob at about ten minutes anyway. */
private const val JOB_RUN_LIMIT_MS = 9L * 60_000L

private const val TAG = "YtDlpJob"

/** Schedules the daily yt-dlp update job. */
object YtDlpJob {
    /**
     * Schedules a periodic job (24 h, flex 6 h, any network, persisted across reboots) unless one with [YTDLP_JOB_ID] is
     * pending: scheduling again would restart the period. Any failure only reaches logcat.
     */
    fun schedule(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(YTDLP_JOB_ID) != null) return
            val info = JobInfo.Builder(YTDLP_JOB_ID, ComponentName(context, YtDlpUpdateJobService::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(info)
            if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "schedule refused: $result")
        } catch (e: Exception) {
            Log.w(TAG, "schedule failed", e)
        }
    }
}

/**
 * The daily yt-dlp update. The system binds it, also with no bridge service running. [onStartJob] only
 * launches the work off the main thread and returns true; a finally always calls jobFinished, and on a periodic job the
 * next period is the retry. Never exported, bound only with BIND_JOB_SERVICE.
 */
class YtDlpUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                withTimeoutOrNull(JOB_RUN_LIMIT_MS) { runUpdate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "run failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUpdate() {
        val settings = AppSettings.of(this)
        val cast = ServiceHub.cast.value
        val serviceUp = ServiceHub.service != null && cast != null
        val casting = cast?.castCount?.value?.casting ?: 0
        val action = ytdlpJobAction(settings.data.value.bridgeEnabled, serviceUp, casting)
        val log = ServiceHub.service?.evidence ?: EvidenceLog.shared(eventLogDir(filesDir))
        log.event("ytdlp-job", mapOf("action" to action.wire))
        when (action) {
            YtDlpJobAction.SKIP_OFF, YtDlpJobAction.POSTPONE_CASTING -> Unit
            YtDlpJobAction.IN_BRIDGE -> cast?.ytdlpUpdateIfDue("job")
            YtDlpJobAction.OFFLINE -> {
                val updater = YtDlpUpdater(
                    gateway = AndroidYtDlpGateway(applicationContext, log),
                    channel = { settings.data.value.ytdlpChannel ?: loadAppConfig(filesDir).config.ytdlpChannel },
                    onEvent = { log.event("ytdlp-update", it) },
                    record = YtDlpRecords.of(applicationContext),
                )
                updater.updateIfDue("job")
            }
        }
    }
}
