package dev.t2u.media

// GPL-3.0-only. Port of the cache half of reference/tube2upnp/src/mediaServer.js (#pruneCache, #reuse, cacheInfo):
// one size cap over the built files and the downloaded sources, least-recently-used eviction by file modification
// time. Pure JVM; the directory belongs to the file builder and nothing outside it is ever touched.

import java.io.File

/** Default cache cap in MB (D-02). Node's default is 512; a phone has less room. */
const val DEFAULT_CACHE_MB = 256

/** [DEFAULT_CACHE_MB] in bytes. */
const val DEFAULT_CACHE_LIMIT_BYTES = DEFAULT_CACHE_MB * 1_048_576L

/** True for the names of finished cache files: built `.m4a` / `.flac` and downloaded `.source` (Node `CACHE_FILE`). */
fun isCacheFileName(name: String): Boolean = name.endsWith(".m4a") || name.endsWith(".flac") || name.endsWith(".source")

/** What the cache holds now: the number and total size of finished files, and the cap in force at the time. */
data class CacheInfo(val files: Int, val bytes: Long, val limitBytes: Long)

/**
 * The cache over the builder's directory [dir]. [limitBytes] is read inside every prune and never remembered, so a
 * cap changed while the app runs (the deferred cache slider) applies to the next prune. Events go to [onEvent] as maps.
 *
 * Only finished files (names passing [isCacheFileName], non-empty) count; `.part`, `.download` and `.info.json`
 * files are in-progress or leftover and are never counted, evicted or served.
 */
class FileCache(
    dir: File,
    private val limitBytes: () -> Long,
    private val onEvent: (Map<String, Any?>) -> Unit = {},
) {
    private val dir: File = dir.absoluteFile
    private val lock = Any()

    /** Sets the modification time of [file] to now: LRU order that survives a restart (Node `#reuse` uses utimes). */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /** Counts only non-empty regular files whose name passes [isCacheFileName]; the limit is read now. */
    fun info(): CacheInfo {
        var files = 0
        var bytes = 0L
        for (f in listCacheFiles()) {
            files++
            bytes += f.size
        }
        return CacheInfo(files, bytes, limitBytes())
    }

    /**
     * Deletes the least-recently-used cache files, oldest modification time first (ties by name), until the total
     * fits the limit read now. Names in [keep] are never chosen (the file just built and its source, D-04: they
     * survive even when they alone exceed the cap). [busy] is consulted by the source pairing of Task 3. Returns the
     * number of files deleted.
     */
    @Suppress("UNUSED_PARAMETER")
    fun prune(keep: Set<String> = emptySet(), busy: (String) -> Boolean = { false }): Int = synchronized(lock) {
        val limit = limitBytes()
        val entries = listCacheFiles().sortedWith(compareBy<Entry> { it.modified }.thenBy { it.name })
        var total = entries.sumOf { it.size }
        var evicted = 0
        var freed = 0L
        for (e in entries) {
            if (total <= limit) break
            if (e.name in keep) continue
            if (File(dir, e.name).delete()) {
                total -= e.size
                freed += e.size
                evicted++
            }
        }
        if (evicted > 0) {
            emit(
                mapOf(
                    "event" to "cache-prune",
                    "evicted" to evicted,
                    "freedBytes" to freed,
                    "totalBytes" to total,
                    "limitBytes" to limit,
                    // RESEARCH Pitfall 12: the pipe range cache and the read-ahead windows are separate budgets.
                    "covers" to "built and source files",
                ),
            )
        }
        evicted
    }

    /**
     * The start scan (D-12, Node `start`): the whole directory belongs to the builder, so every regular file that is
     * not a finished non-empty cache file goes (`.part`, `.source.download`, `.info.json`, yt-dlp leftovers such as
     * `.ytdl` and `.part-Frag`, empty files, anything foreign); the rest is kept, then pruned to the current cap.
     * Creates the directory when missing. Only names listed from [dir] are ever deleted (T-04-01).
     */
    fun scan(): CacheInfo {
        dir.mkdirs()
        var removed = 0
        var kept = 0
        var keptBytes = 0L
        synchronized(lock) {
            for (name in dir.list().orEmpty()) {
                val f = File(dir, name)
                if (!f.isFile) continue
                if (isCacheFileName(name) && f.length() > 0) {
                    kept++
                    keptBytes += f.length()
                } else if (f.delete()) {
                    removed++
                }
            }
        }
        emit(mapOf("event" to "cache-scan", "kept" to kept, "keptBytes" to keptBytes, "removed" to removed))
        prune()
        return info()
    }

    /** Deletes every cache file (Node `clearCache`) and returns how many went. */
    fun clear(): Int = synchronized(lock) {
        var removed = 0
        for (name in dir.list().orEmpty()) {
            if (!isCacheFileName(name)) continue
            val f = File(dir, name)
            if (f.isFile && f.delete()) removed++
        }
        emit(mapOf("event" to "cache-clear", "removed" to removed, "keptInUse" to 0))
        removed
    }

    /** Deletes every regular file that is not a cache file: the temp files of builds a stop cancelled. */
    fun deleteTempFiles() {
        synchronized(lock) {
            for (name in dir.list().orEmpty()) {
                if (isCacheFileName(name)) continue
                val f = File(dir, name)
                if (f.isFile) runCatching { f.delete() }
            }
        }
    }

    // ------------------------------------------------------------------------------------ listing

    private class Entry(val name: String, val size: Long, val modified: Long)

    /** Non-empty regular cache files by name; a file that vanishes between listing and stat is skipped. */
    private fun listCacheFiles(): List<Entry> {
        val names = dir.list() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (name in names) {
            if (!isCacheFileName(name)) continue
            val f = File(dir, name)
            val size = f.length()
            val modified = f.lastModified()
            if (size > 0 && modified > 0 && f.isFile) out.add(Entry(name, size, modified))
        }
        return out
    }

    private fun emit(event: Map<String, Any?>) {
        try {
            onEvent(event)
        } catch (_: Throwable) {
            // logging must never break the cache
        }
    }
}
