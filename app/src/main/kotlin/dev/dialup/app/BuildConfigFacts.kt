package dev.dialup.app

import android.content.Context

/**
 * Which build this is. An F-Droid build (-Pfdroid=true) never downloads a newer yt-dlp or looks for a newer DialUP by itself:
 * [AppSettingsData.updateCheckEnabled] and [AppSettingsData.ytdlpAutoUpdate] are held false and their rows are not shown.
 * F-Droid delivers app updates; the yt-dlp Update now button stays, because the user presses it.
 */
internal object BuildConfigFacts {
    val fdroid: Boolean get() = BuildConfig.FDROID

    fun versionName(context: Context): String? =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            null
        }
}
