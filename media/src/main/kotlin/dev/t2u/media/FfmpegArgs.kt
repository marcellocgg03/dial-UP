package dev.t2u.media

/**
 * The input side of mediaServer.js `#urlInputArgs`: ffmpeg reads [info]'s URL itself with the headers yt-dlp
 * chose, reconnects on a dropped read, and (input-side `-ss`) range-requests from [startAt] instead of reading from 0.
 * [logLevel] is the `ffmpegLogLevel` config switch (a name from the validated whitelist).
 */
fun urlInputArgs(info: ResolvedInfo, startAt: Int, logLevel: String = "error"): List<String> {
    val hdrs = info.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
    val args = mutableListOf("-hide_banner", "-loglevel", logLevel)
    if (hdrs.isNotEmpty()) args += listOf("-headers", hdrs)
    args += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
    if (startAt > 0) args += listOf("-ss", startAt.toString())
    args += listOf("-i", info.url, "-vn")
    return args
}

/**
 * The input side of mediaServer.js `#fileInputArgs`: ffmpeg reads the downloaded file at [path], seeking on the
 * input side to [startAt] seconds when that is positive. [logLevel] is the `ffmpegLogLevel` config switch.
 */
fun fileInputArgs(path: String, startAt: Int, logLevel: String = "error"): List<String> {
    val args = mutableListOf("-hide_banner", "-loglevel", logLevel)
    if (startAt > 0) args += listOf("-ss", startAt.toString())
    args += listOf("-i", path, "-vn")
    return args
}
