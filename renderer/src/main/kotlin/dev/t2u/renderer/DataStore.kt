package dev.t2u.renderer

// Port of tube2upnp src/dataStore.js, GPL-3.0-only.
//
// Per-renderer persistent store. yt-cast-receiver's DefaultDataStore uses one shared directory, so
// every receiver in the process would read the same 'app.pid' / screenId and casts would land on the
// wrong speaker. Each speaker therefore gets its own directory. node-persist (one file per key) is
// replaced by one JSON object per speaker (plain JSON files, no database).

import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private val UNSAFE_UDN_CHARS = Regex("[^A-Za-z0-9_.-]")

/** An altered id keeps at most this many characters of its sanitised name before the `~` and the hash. */
private const val ALTERED_PREFIX_MAX_CHARS = 100

/** Hex digits of the SHA-256 of the id appended to an altered id's directory name (64 bits). */
private const val NAME_HASH_HEX_CHARS = 16

/** The directory name earlier versions used for [rendererId]: every character outside `[A-Za-z0-9_.-]` replaced by `_`. */
internal fun legacyStoreDirName(rendererId: String): String = rendererId.replace(UNSAFE_UDN_CHARS, "_")

/**
 * The directory name of [rendererId]'s store. An id the replacement did not change (every real Sonos id, the fake renderer)
 * keeps its exact name. A changed id gets its sanitised prefix (at most 100 characters), `~` and 16 lowercase hex digits of
 * the SHA-256 of the id, so two ids that differ only in unsafe characters never share a directory. The `~` is outside the
 * safe class, so no safe id can ever equal an altered id's name.
 */
internal fun storeDirName(rendererId: String): String {
    val legacy = legacyStoreDirName(rendererId)
    if (legacy == rendererId) return legacy
    val digest = MessageDigest.getInstance("SHA-256").digest(rendererId.toByteArray(Charsets.UTF_8))
    val hash = digest.take(NAME_HASH_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
    return legacy.take(ALTERED_PREFIX_MAX_CHARS) + "~" + hash
}

/**
 * Values live in `<baseDir>/<sanitised rendererId>/store.json` as one JSON object (key to
 * JsonElement). The directory name is [storeDirName]: an id made only of `[A-Za-z0-9_.-]` keeps its
 * exact name; any other id becomes its sanitised prefix (every other character replaced by `_`), `~` and a
 * 64-bit hash of the id, so two ids never share a directory. A name that would be `.`/`..`/empty, or that
 * resolves outside [baseDir], is refused with [IllegalArgumentException] (a UDN comes off the LAN).
 *
 * Keys used:
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
        val name = storeDirName(rendererId)
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
                withContext(Dispatchers.IO) { writeTextAtomic(file, text, AtomicWriteOptions.RENDERER) }
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
