// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dialup.app.model.cardRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Id of the daily DialUP update-check job. Persisted OS state, like [YTDLP_JOB_ID]; it must differ from it. */
const val UPDATE_JOB_ID = 7102

private const val TAG = "UpdateJob"
private const val JOB_RUN_LIMIT_MS = 2L * 60_000L

/** Schedules and cancels the daily check for a newer DialUP release. It follows the "App updates" switch. */
object UpdateCheckJob {
    /** Schedules a periodic job (24 h, flex 6 h, any network, persisted) unless one is pending; scheduling again would restart the period. */
    fun schedule(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(UPDATE_JOB_ID) != null) return
            val info = JobInfo.Builder(UPDATE_JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
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

    fun cancel(context: Context) {
        try {
            context.getSystemService(JobScheduler::class.java)?.cancel(UPDATE_JOB_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }
}

/**
 * The daily check for a newer DialUP release, run by the system with no activity open. It uses the same throttled
 * [AppUpdates] model as the app, and tells the author once per release (see [UpdateNotifier]). Never exported.
 */
class UpdateCheckJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                withTimeoutOrNull(JOB_RUN_LIMIT_MS) { check() }
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

    private suspend fun check() {
        val enabled = AppSettings.of(this).data.value.updateCheckEnabled
        if (!enabled) return
        val model = AppUpdates.of(this)
        model.autoCheck(true)
        val channel = AppSettings.of(this).data.value.updateChannel
        val card = cardRelease(true, model.state.value.record, BuildConfigFacts.versionName(this) ?: "0", channel) ?: return
        UpdateNotifier(applicationContext).notifyOnce(card.tag, card.url)
    }
}

/** Posts "DialUP x is available" on its own channel, once per release tag (the last tag told is kept in a small file). */
class UpdateNotifier(private val context: Context) {
    private val memo = File(context.filesDir, "update-notified.txt")

    fun notifyOnce(tag: String, url: String) {
        try {
            if (lastTold() == tag) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            // the page was vouched for by safeReleaseUrl when the record was written
            val open = PendingIntent.getActivity(
                context,
                3,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dialup)
                .setContentTitle("DialUP update available")
                .setContentText("DialUP $tag is available. Tap to open the release page.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.notify(NOTIFICATION_ID, notification)
            remember(tag)
        } catch (_: Exception) {
            // a missing permission or a refused channel only means no notice
        }
    }

    private fun lastTold(): String? = try {
        memo.readText().trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    }

    private fun remember(tag: String) {
        try {
            memo.writeText(tag)
        } catch (_: IOException) {
        }
    }

    companion object {
        const val CHANNEL_ID = "dialup-updates"
        const val NOTIFICATION_ID = 300
    }
}
