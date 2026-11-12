package dev.dialup.app

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.dialup.app.model.FetchResult
import dev.t2u.media.GatewayResult
import dev.t2u.media.YtDlpCall
import dev.t2u.media.YtDlpGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * youtubedl-android 0.18.1 behind [YtDlpGateway] (the swap-out seam: the library stays
 * replaceable because upstream is stale). Never call from the main thread.
 *
 * Facts from the 0.18.1 classes (javap on library-0.18.1.aar, which differs slightly from master):
 *  - `YoutubeDLRequest.buildCommand()` orders options, then custom commands, then URLs, so a `--`
 *    added with `addCommands` lands between the options and the URL, so an option can never be read as a URL.
 *  - `execute()` adds `--js-runtimes quickjs:<libqjs.so>` and `--ffmpeg-location` itself, and adds
 *    `--no-cache-dir` unless the request carries a `--cache-dir` option.
 *  - `YoutubeDLRequest(List<String>)` accepts an empty list and `execute()` does not insist on a URL, so a
 *    [YtDlpCall] whose url is null (the `--load-info-json` download form) runs without a URL and
 *    without the `--` marker. Its on-device behaviour is checked on a phone, not in the unit tests.
 *  - `execute()` throws [com.yausername.youtubedl_android.YoutubeDLException] on a non-zero exit
 *    (its message is the process stderr), so a failure arrives here as an exception, not an exit code.
 */
class AndroidYtDlpGateway(
    private val context: Context,
    private val evidence: EvidenceLog,
) : YtDlpGateway {
    override fun run(call: YtDlpCall): GatewayResult {
        checkOffMain()
        NativePayloads.ensure(context, evidence)

        // A call with no URL is the `--load-info-json` form: yt-dlp reads the extraction from a file. Never add a watch
        // URL next to that option, yt-dlp would extract again.
        val url = call.url
        val request = if (url == null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)
        for (option in call.options) {
            val value = option.value
            if (value == null) request.addOption(option.name) else request.addOption(option.name, value)
        }
        // "--" after the options and before the URL: nothing in the URL can be read as an option. Only with a URL.
        if (url != null) request.addCommands(listOf("--"))

        val t0 = SystemClock.elapsedRealtime()
        return try {
            val response = YoutubeDL.getInstance().execute(request)
            val err = response.err
            GatewayResult(
                exitCode = response.exitCode,
                stdout = response.out,
                stderrTail = err.takeLast(TAIL_CHARS),
                wallMs = SystemClock.elapsedRealtime() - t0,
                stderrHead = err.take(HEAD_CHARS),
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure(e, t0)
        } catch (e: Exception) {
            failure(e, t0)
        }
    }

    private fun failure(e: Exception, t0: Long): GatewayResult {
        val message = e.message ?: e.javaClass.name
        return GatewayResult(
            exitCode = -1,
            stdout = "",
            stderrTail = message.takeLast(TAIL_CHARS),
            wallMs = SystemClock.elapsedRealtime() - t0,
            stderrHead = message.take(HEAD_CHARS),
        )
    }

    override fun version(): String? {
        checkOffMain()
        NativePayloads.ensure(context, evidence)
        return YoutubeDL.getInstance().version(context.applicationContext)
    }

    /** [channel] is "stable" or "nightly" (anything else is treated as stable). Throws on a failed update. */
    override fun update(channel: String): String {
        checkOffMain()
        NativePayloads.ensure(context, evidence)
        val updateChannel = if (channel == "nightly") YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, updateChannel)
        return status?.name ?: "UNKNOWN"
    }

    /**
     * The newest release tag of yt-dlp on [channel] from GitHub (the same source the library updates from), or null when it cannot
     * be read: offline, rate limited or an unexpected answer. Nothing is installed.
     */
    override fun latestVersion(channel: String): String? {
        checkOffMain()
        val repo = if (channel == "nightly") "yt-dlp/yt-dlp-nightly-builds" else "yt-dlp/yt-dlp"
        val result = fetchReleases(
            githubReleasesClient(),
            "https://api.github.com/repos/$repo/releases/latest",
            userAgentFor(BuildConfigFacts.versionName(context)),
        )
        val body = (result as? FetchResult.Body)?.text ?: return null
        return try {
            (Json.parseToJsonElement(body) as? JsonObject)
                ?.get("tag_name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkOffMain() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "yt-dlp must not run on the main thread" }
    }

    companion object {
        const val TAIL_CHARS = 4000
        const val HEAD_CHARS = 64 * 1024
    }
}
