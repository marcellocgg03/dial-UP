package dev.t2u.renderer

// Port of tube2upnp src/config.js (Node defaults; config.json is SVC-07), GPL-3.0-only.

import dev.t2u.media.YTDLP_CHANNELS
import dev.t2u.media.YTDLP_DEFAULT_CHANNEL
import java.io.File

/** Name shown in the YouTube cast menu (CAST-07). Placeholders: {name} {manufacturer} {model}. */
const val DEFAULT_NAME_TEMPLATE = "🔊 {name}"

/**
 * D-04: while the Docker server runs next to this app, entries carry a hard-coded suffix
 * ("🔊 Living Room Speaker (app)") so the two sets of receivers can be told apart in the cast menu.
 * There is deliberately no naming setting. D-04: remove at parity (delete this constant, the
 * `nameSuffix` default and its use in [receiverName] callers).
 */
const val DOCKER_COEXISTENCE_SUFFIX = " (app)"

/** Every value `outputFormat` may take (config.js): "auto" walks the learned chain, the rest force one format. */
val OUTPUT_FORMATS = setOf("auto", "direct", "mp4", "adts", "flac", "mp3")

/** The ffmpeg `-loglevel` names a config may select (T-03-09: a whitelist, never free text in an argument list). */
val FFMPEG_LOG_LEVELS = setOf("quiet", "panic", "fatal", "error", "warning", "info", "verbose", "debug")

/** The values `logLevel` may take (D-11): what the evidence log and logcat get. Exact lowercase strings. */
val LOG_LEVELS: Set<String> = setOf("off", "info", "debug")

/** `mp3Bitrate` shape: a number then `k`, 64..320 (checked by [isValidMp3Bitrate]). */
private val MP3_BITRATE = Regex("""^(\d{2,3})k$""")

/** True for "64k" through "320k" (T-03-09: only this shape can reach an ffmpeg argument). */
fun isValidMp3Bitrate(value: String): Boolean =
    MP3_BITRATE.matchEntire(value)?.groupValues?.get(1)?.toInt()?.let { it in 64..320 } == true

/**
 * DISC-04: which speakers appear. The patterns are case-insensitive regular expressions tested with a
 * find-anywhere match against the speaker's friendly name (renderer.js tests `desc.friendlyName`, not
 * the templated cast-menu name). A name is accepted when [include] is empty or any include pattern
 * matches, and no [exclude] pattern matches. Held as the pattern strings so [Config] equality works;
 * the compiled form is built once per filter. A pattern that does not compile never matches (and is
 * reported by [problems]).
 */
data class NameFilter(val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) {
    private val includeRegexes: List<Regex> by lazy(LazyThreadSafetyMode.PUBLICATION) { compile(include) }
    private val excludeRegexes: List<Regex> by lazy(LazyThreadSafetyMode.PUBLICATION) { compile(exclude) }

    fun accepts(friendlyName: String): Boolean =
        (include.isEmpty() || includeRegexes.any { it.containsMatchIn(friendlyName) }) &&
            excludeRegexes.none { it.containsMatchIn(friendlyName) }

    /** Patterns that do not compile, by key and index only (never the pattern text). */
    fun problems(): List<String> =
        problemsOf("include", include) + problemsOf("exclude", exclude)

    private fun compile(patterns: List<String>): List<Regex> = patterns.mapNotNull { compileOrNull(it) }

    private fun problemsOf(key: String, patterns: List<String>): List<String> =
        patterns.mapIndexedNotNull { i, p -> if (compileOrNull(p) == null) "$key[$i] is not a valid regular expression" else null }

    companion object {
        val ALLOW_ALL = NameFilter()

        /** Compiles a name pattern the way the filter does, or null when it is invalid. */
        fun compileOrNull(pattern: String): Regex? =
            try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (_: java.util.regex.PatternSyntaxException) {
                null
            }
    }
}

/**
 * Runtime configuration with the Node defaults. Built in code, optionally overlaid by config.json
 * (see [parseConfigFile], SVC-07). RESEARCH Open Question 3 / A1: the Docker server runs on another
 * host, so the Node ports (DIAL 3000+, media 8000) do not collide.
 *
 * @property excludeServers case-insensitive SERVER-header substrings of devices that are never speakers
 *   (RESEARCH Open Question 5: the QuickShare NOTIFY flood). This is not the DISC-04 include/exclude
 *   filter, which is [nameFilter].
 * @property nameFilter DISC-04 include/exclude patterns on the friendly name; applied in Renderer.init (plan 03-06).
 * @property mp3Bitrate ffmpeg bitrate for the mp3 format.
 * @property flacBits bit depth for the flac format.
 * @property ytdlpVerbose debug switch, consumed by the bridge (plan 03-10).
 * @property ytdlpChannel the yt-dlp release channel the background updater follows: "stable" (the default) or "nightly"
 *   (PLAY-02). Read by the app before every update attempt, so an edited config.json applies at the next one.
 * @property ffmpegLogLevel debug switch, consumed by the bridge (plan 03-10).
 * @property logLevel "off", "info" (the default) or "debug" (SVC-06, D-11): what the app's event log file and logcat get;
 *   debug adds SSDP packets, raw lounge chunks, lounge noops and media GETs. Applied at service start and at every bridge start.
 * @property outputFormat "auto" (the Node default) walks the format chain direct, mp4, adts, flac, mp3 with the
 *   per-speaker learned format; any other value forces that one format and never falls back (plan 03-06).
 * @property queueSettleTimeoutMs how long a track end waits for a playlist refresh in flight before it asks for the
 *   next track; tuning for tests, not readable from config.json (plan 03-09).
 * @property playRetryDelaysMs the waits before the retries of a track whose lookup or preparation failed for an unclear
 *   reason (D-11): the track is tried again after each wait, with a fresh lookup, and skipped when the last retry fails;
 *   tuning for tests, not readable from config.json, like [queueSettleTimeoutMs].
 * @property cacheMb size cap in MiB of the converted and downloaded track cache (built mp4/flac files and .source
 *   files; the pipe range cache and read-ahead are separate). Read by the cache each time it prunes, so a changed
 *   value applies at the next prune (D-02; Node CACHE_MB, default 512 there). 0 is legal: only the file just built stays.
 * @property prefetch prepares the next track while the current one plays (Node PREFETCH).
 * @property gapless hands the prepared next track to the speaker with SetNextAVTransportURI; ignored when [prefetch]
 *   is false, because there is no prepared track to hand over (Node GAPLESS, D-07).
 * @property clearOnCastEnd GUI-08: when false (the default) a cast that ends, by the last sender leaving or by Stop,
 *   leaves the speaker as it is; when true the speaker's queue is cleared. Set only from the app's own settings
 *   (settings.json), never read from config.json; plan 07-07 reads it.
 * @property startTimeoutMs keeps the Node 12 s: it is measured from Play, after the resolve, so the
 *   cold yt-dlp start does not count (RESEARCH A8; plan 02-14 records the real first-cast timing).
 */
data class Config(
    val dataDir: File,
    val receiverBasePort: Int = 3000,
    val mediaPort: Int = 8000,
    val advertiseIp: String? = null,
    val discoveryIntervalS: Int = 300,
    val pruneIntervalS: Int = 60,
    val defaultMaxAge: Int = 1800,
    val nameTemplate: String = DEFAULT_NAME_TEMPLATE,
    val nameSuffix: String = DOCKER_COEXISTENCE_SUFFIX,
    val outputFormat: String = "auto",
    val pollIntervalMs: Long = 2000,
    val startTimeoutMs: Long = 12000,
    val autoplayOnConnect: Boolean = true,
    val excludeServers: List<String> = listOf("QuickShareUPnP"),
    val nameFilter: NameFilter = NameFilter.ALLOW_ALL,
    val mp3Bitrate: String = "192k",
    val flacBits: Int = 16,
    val ytdlpVerbose: Boolean = false,
    val ytdlpChannel: String = YTDLP_DEFAULT_CHANNEL,
    val ffmpegLogLevel: String = "error",
    val logLevel: String = "info",
    val queueSettleTimeoutMs: Long = 10_000,
    val cacheMb: Int = 256,
    val prefetch: Boolean = true,
    val gapless: Boolean = true,
    val playRetryDelaysMs: List<Long> = listOf(2000L, 5000L),
    val clearOnCastEnd: Boolean = false,
) {
    /** Throws [IllegalArgumentException] naming the offending setting. */
    fun validate() {
        require(outputFormat in OUTPUT_FORMATS) {
            "outputFormat \"$outputFormat\" is not one of ${OUTPUT_FORMATS.joinToString(", ")}"
        }
        require(isValidMp3Bitrate(mp3Bitrate)) { "mp3Bitrate must be a number from 64 to 320 followed by k" }
        require(flacBits == 16 || flacBits == 24) { "flacBits must be 16 or 24" }
        require(ffmpegLogLevel in FFMPEG_LOG_LEVELS) { "ffmpegLogLevel must be one of ${FFMPEG_LOG_LEVELS.joinToString(", ")}" }
        require(logLevel in LOG_LEVELS) { "logLevel must be one of ${LOG_LEVELS.joinToString(", ")}" }
        require(nameFilter.problems().isEmpty()) { nameFilter.problems().joinToString("; ") }
        require(receiverBasePort in 1..65535) { "receiverBasePort $receiverBasePort is not a port" }
        require(mediaPort in 1..65535) { "mediaPort $mediaPort is not a port" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
        require(startTimeoutMs > 0) { "startTimeoutMs must be positive" }
        require(queueSettleTimeoutMs >= 0) { "queueSettleTimeoutMs must not be negative" }
        require(playRetryDelaysMs.all { it >= 0 }) { "playRetryDelaysMs must not contain a negative delay" }
        require(ytdlpChannel in YTDLP_CHANNELS) { "ytdlpChannel must be one of ${YTDLP_CHANNELS.joinToString(", ")}" }
        require(cacheMb in 0..32768) { "cacheMb must be a whole number from 0 to 32768" }
    }
}

/**
 * The cast-menu name of a receiver: [template] with the first `{name}`, `{manufacturer}` and `{model}`
 * replaced literally (as renderer.js does with String.replace, in that order), trimmed, then [suffix]
 * appended. The friendly name arrives from parseDescription, which substitutes "Unknown renderer" for
 * an empty one, so the result is never just the emoji and the suffix.
 */
fun receiverName(template: String, suffix: String, friendlyName: String, manufacturer: String, model: String): String =
    template
        .replaceFirst("{name}", friendlyName)
        .replaceFirst("{manufacturer}", manufacturer)
        .replaceFirst("{model}", model)
        .trim() + suffix
