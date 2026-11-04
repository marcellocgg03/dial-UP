package dev.t2u.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

// Port of the mp4 / flac half of reference/tube2upnp/src/mediaServer.js (#remuxToFile, #runRemux, #sourceFile,
// #download, #fileCodecArgs): a track is downloaded once, then ffmpeg builds a complete file from it. Pure JVM.

/**
 * Fetches a track's audio into a file. It must leave a complete audio file at exactly [target] and throw on failure.
 * It makes ONE attempt: the retry loop (fresh extraction after a failure) belongs to [FileBuilder], as in Node
 * `#download`. The yt-dlp implementation is plan 03-07.
 */
fun interface SourceDownloader {
    suspend fun download(videoId: String, info: ResolvedInfo, target: File)
}

/** A download or an ffmpeg build did not produce a file. The message is free of signed URLs (T-03-18). */
class FileBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The ffmpeg argument list that builds one file: a pure function of validated values, so a test can assert it
 * (threat T-03-15). Nothing from a speaker's request reaches it; [source] and [output] are paths the builder made.
 *
 * mp4 is a lossless remux to a non-fragmented MP4 with `moov` first. The output name always ends in `.part`, so
 * `-f` names the container explicitly.
 */
fun fileBuildArgs(
    format: String,
    flacBits: Int,
    startAt: Int,
    source: File,
    output: File,
    logLevel: String = "error",
): List<String> {
    require(startAt >= 0) { "startAt must not be negative" }
    val codec = when (format) {
        "mp4" -> listOf("-c:a", "copy", "-movflags", "+faststart", "-f", "mp4")
        // AAC decodes to float: store it as 16- or 24-bit integer PCM, losslessly compressed.
        "flac" -> {
            require(flacBits == 16 || flacBits == 24) { "flacBits must be 16 or 24" }
            val depth = if (flacBits == 24) listOf("-sample_fmt", "s32", "-bits_per_raw_sample", "24") else listOf("-sample_fmt", "s16")
            listOf("-c:a", "flac") + depth + listOf("-compression_level", "5", "-f", "flac")
        }
        else -> throw IllegalArgumentException("format $format is not a file format")
    }
    // Input-side -ss: ffmpeg seeks in the source instead of decoding up to the offset.
    val seek = if (startAt > 0) listOf("-ss", startAt.toString()) else emptyList()
    return listOf("-hide_banner", "-loglevel", logLevel, "-y") + seek +
        listOf("-i", source.path, "-vn") + codec + output.path
}

/**
 * Builds the complete files of the file formats ([FILE_FORMATS]) in [dir]. [ensure] returns when the file is
 * finished, so the renderer can finish the build before it hands the speaker a URL.
 *
 * Jobs are coalesced per key and live in a private scope: one caller giving up does not cancel the build the
 * others wait for, and a failed job is forgotten so the next call tries again. Output goes to `.part` and is
 * renamed on success, so nothing half-written can be served.
 */
class FileBuilder(
    dir: File,
    private val launcher: PipeLauncher,
    private val resolver: VideoResolver,
    private val downloader: SourceDownloader,
    private val flacBits: Int = 16,
    private val ffmpegLogLevel: String = "error",
    cacheLimitBytes: () -> Long = { DEFAULT_CACHE_LIMIT_BYTES },
    private val retryDelaysMs: List<Long> = listOf(2000L, 5000L),
    private val onEvent: (Map<String, Any?>) -> Unit = {},
) {
    init {
        require(flacBits == 16 || flacBits == 24) { "flacBits must be 16 or 24" }
    }

    private val dir: File = dir.absoluteFile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val buildJobs = HashMap<String, Deferred<File>>()
    private val sourceJobs = HashMap<String, Deferred<File>>()

    private val cache = FileCache(this.dir, cacheLimitBytes, onEvent)

    /** The cached file formats ([FILE_FORMATS], in chain order) already built for [videoId] from the start. */
    fun cachedFormats(videoId: String): List<String> =
        FILE_FORMATS.filter { isFinished(finalFile(videoId, it, 0)) }

    /** The finished file for the key, or null when it is not there. */
    fun fileFor(videoId: String, format: String, startAt: Int): File? {
        val file = finalFile(videoId, format, startAt)
        return file.takeIf { isFinished(it) }?.also { cache.touch(it) }
    }

    /**
     * The validated path of the final file for the key, whether or not it exists yet: a token registered before its build
     * (or after Android deleted the file) names this path, and [ensure] builds it there. Same validation as every
     * builder path (T-03-17).
     */
    fun pathFor(videoId: String, format: String, startAt: Int): File = finalFile(videoId, format, startAt)

    /** Returns the finished file, building it (download first, then ffmpeg) when it does not exist yet. */
    suspend fun ensure(videoId: String, format: String, startAt: Int): File {
        val final = finalFile(videoId, format, startAt)
        if (isFinished(final)) return final.also { cache.touch(it) }
        val key = keyOf(videoId, format, startAt)
        val job = synchronized(lock) {
            buildJobs[key] ?: scope.async(start = CoroutineStart.LAZY) { build(videoId, format, startAt, final) }.also { j ->
                buildJobs[key] = j
                j.invokeOnCompletion { synchronized(lock) { buildJobs.remove(key, j) } }
                j.start()
            }
        }
        return job.await()
    }

    /**
     * How many builds and source downloads run now. The yt-dlp updater waits while this is above 0, so it cannot swap yt-dlp
     * under a running download (WR-03).
     */
    fun activeCount(): Int = synchronized(lock) { buildJobs.size + sourceJobs.size }

    /** Scans the directory for the cache of an earlier run (D-12): finished files stay, half-written ones go. */
    fun start(): CacheInfo = cache.scan()

    /**
     * Cancels running builds and downloads (their ffmpeg processes are destroyed) and deletes their temp files.
     * Finished cache files stay: a service stop does not empty the cache (D-12).
     */
    fun close() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        cache.deleteTempFiles()
        cache.dropUncachedFiles()
    }

    /** Deletes every cache file (Node `clearCache`) and returns how many went. */
    fun clear(): Int = cache.clear()

    /** What the cache holds now. */
    fun info(): CacheInfo = cache.info()

    /** Pins [file] for as long as a response reads it; call the returned release when done (one-shot). */
    fun pin(file: File): () -> Unit = cache.pin(file)

    /** True while a build or a source download of [videoId] runs: its source must not be evicted under it. */
    private fun isBusy(videoId: String): Boolean =
        synchronized(lock) { buildJobs.keys.any { it.startsWith("$videoId-") } || sourceJobs.containsKey(videoId) }

    // ------------------------------------------------------------------------------------ names

    private fun keyOf(videoId: String, format: String, startAt: Int): String = "$videoId-$format-$startAt"

    /** `<validated id>-<format>-<int>.<ext>` inside [dir]: no part of a request becomes a file name (T-03-17). */
    private fun finalFile(videoId: String, format: String, startAt: Int): File {
        requireVideoId(videoId)
        require(format in FILE_FORMATS) { "format $format is not a file format" }
        require(startAt >= 0) { "startAt must not be negative" }
        return File(dir, "${keyOf(videoId, format, startAt)}.${extensionOf(format)}")
    }

    /** True when the source of [videoId] is already downloaded (a non-empty file) or its download is running. */
    fun hasSource(videoId: String): Boolean {
        val path = sourcePath(videoId)
        return isFinished(path) || synchronized(lock) { sourceJobs.containsKey(videoId) }
    }

    private fun sourcePath(videoId: String): File = File(dir, "${requireVideoId(videoId)}.source")

    private fun isFinished(file: File): Boolean = file.isFile && file.length() > 0

    // ------------------------------------------------------------------------------------ build

    private suspend fun build(videoId: String, format: String, startAt: Int, final: File): File {
        if (isFinished(final)) return final
        // Pinned while ffmpeg reads it (Node `unpinSrc`); a prune of another build may have taken a source between
        // its download and this pin, in which case it is downloaded again.
        var src = sourceFile(videoId)
        var unpinSrc = cache.pin(src)
        if (!isFinished(src)) {
            unpinSrc()
            src = sourceFile(videoId)
            unpinSrc = cache.pin(src)
        }
        val part = File(final.path + ".part")
        val args = fileBuildArgs(format, flacBits, startAt, src, part, ffmpegLogLevel)
        var proc: Process? = null
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                part.delete()
            }
            val p = withContext(Dispatchers.IO) { launcher.start(args) }
            proc = p
            awaitExit(p)
            val code = p.exitValue()
            if (code != 0 || part.length() == 0L) {
                val tail = withContext(Dispatchers.IO) { launcher.stderrTail(p) }
                throw FileBuildException(
                    Resolver.redactUrls("ffmpeg $format build exited $code: $tail".trim()).take(MAX_MESSAGE),
                )
            }
            withContext(Dispatchers.IO) {
                Files.move(part.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { part.delete() }
            throw e
        } finally {
            proc?.let { p -> withContext(NonCancellable + Dispatchers.IO) { if (p.isAlive) p.destroyForcibly() } }
            unpinSrc()
        }
        // D-04: the file just built and its source are kept even when they alone exceed the cap.
        cache.prune(keep = setOf(final.name, src.name), busy = ::isBusy)
        emit("built", videoId, format, startAt)
        return final
    }

    /** Waits for [p] to exit; cancellation interrupts the wait, and a build that runs too long is killed. */
    private suspend fun awaitExit(p: Process) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(BUILD_TIMEOUT_MIN)
        while (true) {
            if (runInterruptible(Dispatchers.IO) { p.waitFor(POLL_MS, TimeUnit.MILLISECONDS) }) return
            if (System.nanoTime() > deadline) {
                p.destroyForcibly()
                throw FileBuildException("ffmpeg build did not finish within $BUILD_TIMEOUT_MIN min")
            }
        }
    }

    // ------------------------------------------------------------------------------------ source

    /**
     * The downloaded source of [videoId]: reused when present, otherwise one shared download with the retries of
     * [downloadSource]. The pipe formats use it as the last rung when YouTube keeps refusing ffmpeg (Node
     * `#existingSourceFile`). Throws [FileBuildException] when every attempt failed.
     */
    suspend fun sourceFile(videoId: String): File {
        val dest = sourcePath(videoId)
        if (isFinished(dest)) return dest.also { cache.touch(it) }
        val job = synchronized(lock) {
            sourceJobs[videoId] ?: scope.async(start = CoroutineStart.LAZY) { downloadSource(videoId, dest) }.also { j ->
                sourceJobs[videoId] = j
                j.invokeOnCompletion { synchronized(lock) { sourceJobs.remove(videoId, j) } }
                j.start()
            }
        }
        return job.await()
    }

    /**
     * Node `#download`: resolve, download to `<id>.source.download`, rename on success. Every failure drops the
     * cached resolve (a 403 on a seconds-old URL is not expiry, so extract afresh), deletes the partial file and, while
     * delays remain, waits and goes round again; after the last delay the last error is thrown.
     */
    private suspend fun downloadSource(videoId: String, dest: File): File {
        if (isFinished(dest)) return dest
        val part = File(dest.path + ".download")
        var attempt = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    part.delete()
                }
                val info = resolver.resolve(videoId)
                downloader.download(videoId, info, part)
                check(isFinished(part)) { "the download left no file" }
                withContext(Dispatchers.IO) { Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                // Sources downloaded for the pipe rung are bounded by the same cap.
                cache.prune(keep = setOf(dest.name), busy = ::isBusy)
                emit("downloaded", videoId, "source", 0)
                return dest
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { part.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { resolver.invalidate(videoId) }
                withContext(Dispatchers.IO) { part.delete() }
                val text = Resolver.redactUrls(e.message ?: e.javaClass.simpleName).take(MAX_MESSAGE)
                if (attempt >= retryDelaysMs.size) throw FileBuildException("download failed for $videoId: $text", e)
                emit("download-retry", videoId, "source", attempt + 1, text)
                delay(retryDelaysMs[attempt])
                attempt++
            }
        }
    }

    private fun emit(event: String, videoId: String, format: String, n: Int, error: String? = null) {
        try {
            onEvent(mapOf("event" to event, "id" to videoId, "format" to format, "n" to n, "error" to error))
        } catch (_: Throwable) {
            // logging must never break a build
        }
    }

    private companion object {
        const val MAX_MESSAGE = 400
        const val POLL_MS = 250L
        const val BUILD_TIMEOUT_MIN = 15L
    }
}
