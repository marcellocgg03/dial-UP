package dev.t2u.renderer

// Port of tube2upnp src/dataStore.js, GPL-3.0-only.
//
// Per-renderer persistent store. yt-cast-receiver's DefaultDataStore uses one shared directory, so
// every receiver in the process would read the same 'app.pid' / screenId and casts would land on the
// wrong speaker. Each speaker therefore gets its own directory. node-persist (one file per key) is
// replaced by one JSON object per speaker, as CLAUDE.md recommends.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

private val UNSAFE_UDN_CHARS = Regex("[^A-Za-z0-9_.-]")

/**
 * CAST-03. Values live in `<baseDir>/<sanitised rendererId>/store.json` as one JSON object (key to
 * JsonElement). The directory name is [rendererId] with every character outside `[A-Za-z0-9_.-]`
 * replaced by `_`; a name that would be `.`/`..`/empty, or that resolves outside [baseDir], is refused
 * with [IllegalArgumentException] (T-02-26: a UDN comes off the LAN).
 *
 * Keys used in Phase 2:
 *  - `dial.uuid`: Kotlin addition, keeps the SSDP/DIAL UDN of the receiver stable across restarts.
 *  - `app.pid`: the DIAL app process id (yt-cast-receiver YouTubeApp).
 *  - `mdxContext.cl` and `mdxContext.m`: screenId and deviceId of the two YouTube clients (yt-cast-receiver
 *    Session). They are separate keys, so writing one never changes the other.
 *
 * Reads are lazy and cached after the first read. [get] never throws: a missing file is empty, a
 * corrupt or wrongly shaped one is reported once through [onError] and then treated as empty, so the
 * next [set] writes a fresh valid file. Writes are serialised by a Mutex and atomic (store.json.tmp,
 * then move).
 */
class RendererDataStore(
    baseDir: File,
    rendererId: String,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : dev.t2u.lounge.DataStore {
    private val dir: File
    private val file: File
    private val mutex = Mutex()
    private var cache: MutableMap<String, JsonElement>? = null

    init {
        val name = rendererId.replace(UNSAFE_UDN_CHARS, "_")
        require(name.isNotEmpty() && name != "." && name != "..") { "refusing renderer id '$rendererId': not usable as a directory name" }
        val base = baseDir.canonicalFile
        val candidate = File(base, name).canonicalFile
        require(candidate.parentFile == base) { "refusing renderer id '$rendererId': resolves outside the data directory" }
        dir = candidate
        file = File(dir, "store.json")
    }

    override suspend fun get(key: String): JsonElement? = mutex.withLock {
        try {
            loaded()[key]
        } catch (e: Exception) {
            onError("DataStore get '$key' failed", e)
            null
        }
    }

    override suspend fun set(key: String, value: JsonElement?) {
        mutex.withLock {
            try {
                val values = loaded()
                if (value == null) values.remove(key) else values[key] = value
                val text = JsonObject(LinkedHashMap(values)).toString()
                withContext(Dispatchers.IO) { writeAtomically(file, text) }
            } catch (e: Exception) {
                onError("DataStore set '$key' failed", e)
            }
        }
    }

    /** The cached map, read from disk on first use. Called under [mutex]. */
    private fun loaded(): MutableMap<String, JsonElement> {
        cache?.let { return it }
        val values = LinkedHashMap<String, JsonElement>()
        if (file.exists()) {
            try {
                val parsed = Json.parseToJsonElement(file.readText())
                if (parsed !is JsonObject) throw IOException("$file is not a JSON object")
                values.putAll(parsed)
            } catch (e: Exception) {
                values.clear()
                onError("DataStore: ignoring unreadable $file", e)
            }
        }
        cache = values
        return values
    }
}
