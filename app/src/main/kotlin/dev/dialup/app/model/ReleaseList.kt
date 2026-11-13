// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/** The releases list (newest 30). Plain https, no redirect is followed. */
const val RELEASES_API_URL = "https://api.github.com/repos/marcellocgg03/dial-UP/releases?per_page=30"

/** The page opened when no safe release page is known. */
const val RELEASES_PAGE_URL = "https://github.com/marcellocgg03/dial-UP/releases"

private const val ALLOWED_PATH_PREFIX = "/marcellocgg03/dial-up/releases/"

/** One release of the list: its tag, its page, its parsed version and whether GitHub marks it a pre-release. */
data class Release(val tag: String, val url: String, val version: SemVer, val prerelease: Boolean) {
    /** A nightly build: its pre-release part starts with `nightly` (v1.1.1-nightly.12). */
    val nightly: Boolean get() = version.pre.firstOrNull() == "nightly"
}

/**
 * True when [url] is a release page of this repository: https, host github.com, no user info or port, a path under
 * `/marcellocgg03/dial-UP/releases/` (letter case ignored) with at least one segment after it and no dot segments.
 */
fun isAllowedReleaseUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme != "https") return false
    if (uri.userInfo != null || uri.port != -1) return false
    if (uri.host?.lowercase() != "github.com") return false
    if (uri.rawAuthority?.lowercase() != "github.com") return false
    val path = uri.rawPath ?: return false
    if (!path.lowercase().startsWith(ALLOWED_PATH_PREFIX)) return false
    val rest = path.substring(ALLOWED_PATH_PREFIX.length)
    if (rest.isEmpty()) return false
    val segments = path.split('/')
    if (segments.any { it == "." || it == ".." || it.lowercase() == "%2e" || it.lowercase() == "%2e%2e" }) return false
    return true
}

/** [url] when it is an allowed release page, else the constant releases page. */
fun safeReleaseUrl(url: String?): String = if (url != null && isAllowedReleaseUrl(url)) url else RELEASES_PAGE_URL

/**
 * The newest release of the GitHub releases list in [json]: the maximum by semantic version over every non-draft entry
 * with a semantic tag and an allowed page, pre-releases included; nightly builds only on the [channel] NIGHTLY. Null for an empty list or text that is not a list.
 * Never throws.
 */
fun newestRelease(json: String, channel: UpdateChannel = UpdateChannel.STABLE): Release? {
    val arr = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    var best: Release? = null
    for (e in arr) {
        val o = e as? JsonObject ?: continue
        if (flag(o["draft"]) == true) continue
        val tag = str(o["tag_name"]) ?: continue
        val url = str(o["html_url"]) ?: continue
        if (!isAllowedReleaseUrl(url)) continue
        val v = SemVer.parse(tag) ?: continue
        val r = Release(tag, url, v, flag(o["prerelease"]) == true)
        if (r.nightly && channel != UpdateChannel.NIGHTLY) continue
        if (best == null || r.version > best.version) best = r
    }
    return best
}

private fun str(e: kotlinx.serialization.json.JsonElement?): String? =
    (e as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun flag(e: kotlinx.serialization.json.JsonElement?): Boolean? =
    (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
