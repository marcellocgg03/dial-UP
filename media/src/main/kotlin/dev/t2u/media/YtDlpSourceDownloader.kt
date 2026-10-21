package dev.t2u.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// Kotlin original; a port of downloadWithYtdlp in reference/tube2upnp/src/resolver.js. Pure JVM: the blocking
// yt-dlp call goes through YtDlpGateway.

/**
 * A yt-dlp download failed. The message is free of signed URLs (T-03-18); [forbidden] is true when yt-dlp reported
 * "HTTP Error 403", which the caller treats as a reason to extract afresh.
 */
class DownloadException(message: String, val forbidden: Boolean) : Exception(message)

/**
 * The real [SourceDownloader]: one yt-dlp download of the track into `target`. When the lookup's raw JSON is known,
 * yt-dlp is given that (`--load-info-json`) so it only downloads and does not extract a second time; otherwise it
 * gets the watch URL.
 *
 * The info JSON holds signed googlevideo URLs, so it goes into a file only this app can read (mode 600, created
 * new), next to [target]. Whatever is already at that path, a leftover or a symlink somebody planted, is removed
 * first and never written through (T-03-22, T-03-23). The file is deleted afterwards whether the download worked or not.
 *
 * One attempt only: retries with a fresh extraction belong to [FileBuilder].
 */
class YtDlpSourceDownloader(
    private val gateway: YtDlpGateway,
    private val cacheDir: String? = null,
    private val timeoutMs: Long = 600_000,
) : SourceDownloader {
    override suspend fun download(videoId: String, info: ResolvedInfo, target: File) {
        requireVideoId(videoId)
        val infoFile = File(target.path + INFO_SUFFIX)
        val raw = info.rawJson
        try {
            val call = if (raw != null) {
                withContext(Dispatchers.IO) { writePrivate(infoFile, raw) }
                loadInfoDownloadCall(infoFile.path, info.formatId, target.path, cacheDir)
            } else {
                downloadCall(videoId, target.path, cacheDir)
            }
            val result = withTimeout(timeoutMs) { runInterruptible(Dispatchers.IO) { gateway.run(call) } }
            if (result.exitCode != 0) {
                val tail = lastLines(result.stderrTail)
                throw DownloadException(
                    Resolver.redactUrls("yt-dlp download failed for $videoId: $tail").take(MAX_MESSAGE),
                    forbidden = tail.contains("HTTP Error 403"),
                )
            }
            if (!target.isFile || target.length() == 0L) {
                throw DownloadException("yt-dlp download left no file for $videoId", forbidden = false)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { Files.deleteIfExists(infoFile.toPath()) } }
        }
    }

    /** The last two non-blank stderr lines joined by " | " (Node `tail`). */
    private fun lastLines(stderr: String): String =
        stderr.trim().lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" | ")

    /**
     * Writes [text] to a file that did not exist a moment ago and that only this user can read or write. Whatever is
     * at [file] is deleted first: [Files.deleteIfExists] removes a symlink itself, never its target.
     */
    private fun writePrivate(file: File, text: String) {
        val path = file.toPath()
        Files.deleteIfExists(path)
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            // A file system without POSIX attributes: create-new, then owner-only flags.
            if (!file.createNewFile()) throw IOException("could not create ${file.name} as a new file")
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
        Files.write(path, text.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val INFO_SUFFIX = ".info.json"
        const val MAX_MESSAGE = 400
    }
}
