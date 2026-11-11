package dev.dialup.app

import android.content.Context

/** Tiny helper kept out of the service body so BuildConfig (disabled) is not needed. */
internal object BuildConfigFacts {
    fun versionName(context: Context): String? =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            null
        }
}
