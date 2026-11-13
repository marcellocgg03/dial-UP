package dev.t2u.media

/**
 * The input side of mediaServer.js `#urlInputArgs`: ffmpeg reads [info]'s URL itself with the headers yt-dlp
 * chose, reconnects on a dropped read, and (input-side `-ss`) range-requests from [startAt] instead of reading from 0.
 * [logLevel] is the `ffmpegLogLevel` config switch (a name from the validated whitelist).
 */
fun urlInputArgs(
    info: ResolvedInfo,
    startAt: Int,
    logLevel: String = "error",
    policy: FfmpegUrlPolicy = FfmpegUrlPolicy.GOOGLEVIDEO,
): List<String> {
    // the resolver's answer is trusted no further than the direct proxy trusts it: same host rule, and only the protocols that rule needs
    require(policy.allows(info.url)) { "ffmpeg input URL is not allowed" }
    // a CR or LF in a header would end the header and start another one
    val hdrs = info.headers.entries.joinToString("") { (k, v) -> "${stripLineBreaks(k)}: ${stripLineBreaks(v)}\r\n" }
    val args = mutableListOf("-hide_banner", "-loglevel", logLevel, "-protocol_whitelist", policy.protocols)
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

private fun stripLineBreaks(s: String): String = s.filterNot { it == '\r' || it == '\n' }

/**
 * Which URLs ffmpeg may read as its network input and which protocols it may use to do so. [GOOGLEVIDEO] is the rule of the
 * direct proxy (`UpstreamPolicy.GOOGLEVIDEO`): https, no user info, port 443, host `googlevideo.com` or a subdomain.
 */
class FfmpegUrlPolicy(val protocols: String, val allows: (String) -> Boolean) {
    fun allows(url: String): Boolean = allows.invoke(url)

    companion object {
        val GOOGLEVIDEO = FfmpegUrlPolicy("https,tls,tcp,crypto") { isGoogleVideoUrl(it) }

        /** Any http(s) URL; for tests that serve the "upstream" from a loopback server. Never used in production wiring. */
        val ANY_HTTP = FfmpegUrlPolicy("http,https,tls,tcp,crypto") { u ->
            runCatching { java.net.URI(u).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
        }
    }
}

/** True for an https URL on port 443, without user info, whose host is `googlevideo.com` or one of its subdomains. */
fun isGoogleVideoUrl(url: String): Boolean {
    val uri = try {
        java.net.URI(url)
    } catch (_: java.net.URISyntaxException) {
        return false
    }
    if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return false
    if (uri.port != -1 && uri.port != 443) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}
