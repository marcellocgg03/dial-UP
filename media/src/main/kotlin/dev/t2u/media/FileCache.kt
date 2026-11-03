package dev.t2u.media

// GPL-3.0-only. Port of the cache half of reference/tube2upnp/src/mediaServer.js (#pruneCache, #reuse, #pin,
// cacheInfo): one size cap over the built files and the downloaded sources, least-recently-used eviction by file
// modification time, pins for files in use. Pure JVM; the directory belongs to the file builder and nothing outside
// it is ever touched (T-04-01).

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
 *
 * Pins are counted per file name in memory. A pinned file is skipped by [prune] and [clear], so the disk can pass
 * the cap by the files in use: the cap, plus the files being read or built (T-04-02).
 */
class FileCache(
    dir: File,
    private val limitBytes: () -> Long,
    private val onEvent: (Map<String, Any?>) -> Unit = {},
) {
    private val dir: File = dir.absoluteFile
    private val lock = Any()
    private val pins = HashMap<String, Int>()

    /** Names seen by a scan or by a prune under a positive limit: files that were kept, and so are never dropped by a zero limit. */
    private val known = HashSet<String>()

    /** Files created while the limit was 0: the only ones a zero limit may delete, once nothing reads them. */
    private val uncached = HashSet<String>()

    /** The video ids of the last two builds seen under a zero limit: the track that plays and the one queued next stay (gapless). */
    private val recentIds = ArrayList<String>()

    /** Sets the modification time of [file] to now: LRU order that survives a restart (Node `#reuse` uses utimes). */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Pins [file] (a response is reading it, or ffmpeg is) and touches it. The returned release is one-shot: calling
     * it a second time does not unpin another holder (Node `#pin`). A file outside the cache directory is not pinned.
     */
    fun pin(file: File): () -> Unit {
        val name = file.name
        if (file.absoluteFile.parentFile != dir) return {}
        synchronized(lock) { pins.merge(name, 1, Int::plus) }
        touch(file)
        var released = false
        return {
            synchronized(lock) {
                if (!released) {
                    released = true
                    val left = (pins[name] ?: 1) - 1
                    if (left <= 0) pins.remove(name) else pins[name] = left
                }
            }
        }
    }

    fun isPinned(name: String): Boolean = synchronized(lock) { pins.containsKey(name) }

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
     * fits the limit read now. Never chosen: names in [keep] (the file just built and its source, D-04: they
     * survive even when they alone exceed the cap) and pinned names.
     *
     * Pairing (D-01): when a built file goes and no other built file of its video remains, the video's `.source`
     * goes in the same step, unless [busy] says a build or download of that video is running, or the source is
     * kept or pinned. A source whose video still has a built file, or is busy, is skipped at its turn; an orphan
     * source is an ordinary LRU entry. Returns the number of files deleted.
     */
    fun prune(keep: Set<String> = emptySet(), busy: (String) -> Boolean = { false }): Int = synchronized(lock) {
        val limit = limitBytes()
        val entries = listCacheFiles().sortedWith(compareBy<Entry> { it.modified }.thenBy { it.name })
        val present = entries.map { it.name }.toHashSet()
        if (limit <= 0L) return@synchronized pruneUncached(entries, keep, busy)
        known.addAll(present)
        uncached.clear()
        val sizes = entries.associate { it.name to it.size }
        var total = entries.sumOf { it.size }
        var evicted = 0
        var freed = 0L
        var skippedPinned = 0

        fun remove(name: String): Boolean {
            val gone = File(dir, name).let { it.delete() || !it.exists() }
            if (gone && present.remove(name)) {
                val size = sizes.getValue(name)
                total -= size
                freed += size
                evicted++
            }
            return gone
        }

        for (e in entries) {
            if (total <= limit) break
            val name = e.name
            if (name !in present || name in keep) continue
            if (pins.containsKey(name)) {
                skippedPinned++
                continue
            }
            val id = builtId(name)
            if (id == null && name.endsWith(".source")) {
                val sid = name.removeSuffix(".source")
                if (present.any { builtId(it) == sid } || busy(sid)) continue
            }
            if (!remove(name)) continue
            if (id != null && present.none { builtId(it) == id }) {
                val source = "$id.source"
                if (source in present && source !in keep && !pins.containsKey(source) && !busy(id)) remove(source)
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
                    "pinned" to skippedPinned,
                    // RESEARCH Pitfall 12: the pipe range cache and the read-ahead windows are separate budgets.
                    "covers" to "built and source files",
                ),
            )
        }
        evicted
    }

    /**
     * The zero limit: nothing that was kept before is ever evicted ("0 MB" stops new files from being cached, it does not
     * empty the cache). The files in [keep] that no scan or positive-limit prune has seen are the ones this run made, so they
     * count as uncached; the uncached ones that are neither in [keep], pinned nor busy go.
     */
    private fun pruneUncached(entries: List<Entry>, keep: Set<String>, busy: (String) -> Boolean): Int {
        for (e in entries) if (e.name in keep && e.name !in known) uncached.add(e.name)
        for (name in keep) {
            val id = idOf(name) ?: continue
            if (name in known) continue
            recentIds.remove(id)
            recentIds.add(id)
            while (recentIds.size > 2) recentIds.removeAt(0)
        }
        var evicted = 0
        for (e in entries) {
            val name = e.name
            if (name !in uncached || name in keep || pins.containsKey(name) || idOf(name) in recentIds) continue
            if (name.endsWith(".source") && busy(name.removeSuffix(".source"))) continue
            if (File(dir, name).let { it.delete() || !it.exists() }) {
                uncached.remove(name)
                evicted++
            }
        }
        return evicted
    }

    /** Deletes the unpinned files made under a zero limit (a bridge stop): nothing that was cached before is touched. */
    fun dropUncachedFiles(): Int = synchronized(lock) {
        var removed = 0
        for (name in uncached.toList()) {
            if (pins.containsKey(name)) continue
            if (File(dir, name).let { it.delete() || !it.exists() }) {
                uncached.remove(name)
                removed++
            }
        }
        removed
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
                    known.add(name)
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

    /** Deletes every unpinned cache file (Node `clearCache`) and returns how many went. */
    fun clear(): Int = synchronized(lock) {
        var removed = 0
        var inUse = 0
        for (name in dir.list().orEmpty()) {
            if (!isCacheFileName(name)) continue
            if (pins.containsKey(name)) {
                inUse++
                continue
            }
            val f = File(dir, name)
            if (f.isFile && f.delete()) removed++
        }
        emit(mapOf("event" to "cache-clear", "removed" to removed, "keptInUse" to inUse))
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

    /** The video id of a built file name `<11-char id>-<format>-<int>.<ext>`, or null for anything else. */
    private fun idOf(name: String): String? = builtId(name) ?: name.takeIf { it.endsWith(".source") }?.removeSuffix(".source")

    private fun builtId(name: String): String? = BUILT.matchEntire(name)?.groupValues?.get(1)

    private fun emit(event: Map<String, Any?>) {
        try {
            onEvent(event)
        } catch (_: Throwable) {
            // logging must never break the cache
        }
    }

    private companion object {
        val BUILT = Regex("([A-Za-z0-9_-]{11})-[a-z0-9]+-\\d+\\.(?:m4a|flac)")
    }
}

/**
 * Moves the finished cache files of [from] into [to] once (the cache used to live in the app's cache directory, which Android may
 * empty): a name that already exists in [to] is left alone, anything else in [from] (part files, leftovers) is ignored.
 * @return how many files moved
 */
fun migrateCacheDir(from: File, to: File): Int {
    val names = from.list() ?: return 0
    to.mkdirs()
    var moved = 0
    for (name in names) {
        if (!isCacheFileName(name)) continue
        val src = File(from, name)
        val dst = File(to, name)
        if (!src.isFile || dst.exists()) continue
        if (src.renameTo(dst) || runCatching { src.copyTo(dst); src.delete(); true }.getOrDefault(false)) moved++
    }
    return moved
}
