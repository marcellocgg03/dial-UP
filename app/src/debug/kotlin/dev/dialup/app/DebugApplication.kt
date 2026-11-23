// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executors

private const val STRICT_TAG = "t2u-strict"
private const val MAX_APP_FRAMES = 3

/**
 * One compact line for a StrictMode violation: the [kind] (the violation's simple class name), then the first
 * three frames of [stack] whose class lives under `dev.dialup.` or `dev.t2u.`, each as `Class.method:line` with the
 * simple class name, or `no app frame` when the stack holds none. The violation's message is deliberately not a
 * parameter, because a disk violation's message can hold a path; a frame's file name is not printed either.
 */
internal fun strictModeLine(kind: String, stack: List<StackTraceElement>): String {
    val frames = stack
        .filter { it.className.startsWith("dev.dialup.") || it.className.startsWith("dev.t2u.") }
        .take(MAX_APP_FRAMES)
        .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    return if (frames.isEmpty()) "$kind no app frame" else "$kind ${frames.joinToString(" ")}"
}

/**
 * Debug builds only (the release build has no Application class). Installs a StrictMode thread policy on the main
 * thread before any Activity, Service or Receiver runs, so the phone check can show whether file or network work
 * still happens there. It changes no behaviour: log penalties only, never a crash or a dialog. No VmPolicy and no
 * detectAll, because unbuffered I/O and resource checks are noise for this check.
 */
class DebugApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "t2u-strict").apply { isDaemon = true } }
            policy.penaltyListener(executor) { violation ->
                Log.w(STRICT_TAG, strictModeLine(violation.javaClass.simpleName, violation.stackTrace.toList()))
            }
        }
        StrictMode.setThreadPolicy(policy.build())
    }
}
