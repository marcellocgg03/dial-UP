// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.dialup.app.model.FetchResult
import dev.dialup.app.model.UpdateRecord
import dev.dialup.app.model.parseUpdateRecord
import dev.dialup.app.model.updateRecordJson
import dev.t2u.upnp.lanHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The most of a releases list the check reads (30 releases are far below this). */
const val MAX_RELEASES_BODY: Long = 1_048_576L

/** The client of the update check: derived from [lanHttpClient] (SVC-05), so no redirect is followed. */
fun githubReleasesClient(): OkHttpClient = lanHttpClient().newBuilder()
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

/** The User-Agent GitHub requires (it answers 403 without one): the app name and version, nothing about the device. */
fun userAgentFor(versionName: String?): String = "DialUP/" + (versionName ?: "0")

/**
 * Asks [url] for the releases list. Blocking; callers run it on Dispatchers.IO. Never throws for a network problem:
 * 403 or 429 with `x-ratelimit-remaining: 0` or a `retry-after` header is [FetchResult.RateLimited], other error codes
 * (a redirect included) are [FetchResult.Http], a body over [MAX_RELEASES_BODY] is [FetchResult.TooLarge], and an
 * [IOException] is [FetchResult.Offline].
 */
fun fetchReleases(client: OkHttpClient, url: String, userAgent: String): FetchResult {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()
    return try {
        client.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> {
                    val source = response.body.source()
                    if (source.request(MAX_RELEASES_BODY + 1)) {
                        FetchResult.TooLarge
                    } else {
                        FetchResult.Body(source.buffer.readUtf8())
                    }
                }
                (code == 403 || code == 429) &&
                    (response.header("x-ratelimit-remaining") == "0" || response.header("retry-after") != null) ->
                    FetchResult.RateLimited
                else -> FetchResult.Http(code)
            }
        }
    } catch (_: IOException) {
        FetchResult.Offline
    }
}

/** The check record in `<dir>/<name>`. A missing or unreadable file is an empty record; a write is atomic. */
class UpdateRecordFile(private val dir: File, private val name: String = "update-check.json") {
    fun load(): UpdateRecord =
        try {
            val f = File(dir, name)
            if (f.isFile) parseUpdateRecord(f.readText()) else UpdateRecord()
        } catch (_: IOException) {
            UpdateRecord()
        }

    fun save(r: UpdateRecord) = writeTextAtomic(dir, name, updateRecordJson(r))
}
