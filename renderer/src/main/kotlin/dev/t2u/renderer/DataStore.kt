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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

private val UNSAFE_UDN_CHARS = Regex("[^A-Za-z0-9_.-]")

/** An altered id keeps at most this many characters of its sanitised name before the `~` and the hash. */
private const val ALTERED_PREFIX_MAX_CHARS = 100

/** Hex digits of the SHA-256 of the id appended to an altered id's directory name (64 bits). */
private const val NAME_HASH_HEX_CHARS = 16

/** The directory name earlier versions used for [rendererId]: every character outside `[A-Za-z0-9_.-]` replaced by `_`. */
internal fun legacyStoreDirName(rendererId: String): String = rendererId.replace(UNSAFE_UDN_CHARS, "_")

/**
 * True when [name] could be a file the data directory itself holds: every such file ends `.json` (ports.json, hidden.json,
 * manual.json, names.json, pinned.json) and its atomic-write temp file ends `.json.tmp`. Compared ignoring case, so a
 * case-insensitive file system cannot map a store directory onto one either.
 */
private fun namesADataFile(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.endsWith(".json") || lower.endsWith(".json.tmp")
}

/**
 * The directory name of [rendererId]'s store. An id the replacement did not change (every real Sonos id, the fake renderer)
 * keeps its exact name, unless that name could be a data file of the data directory (it ends `.json` or `.json.tmp`, ignoring
 * case): such an id gets the altered form so a LAN-supplied UDN of `ports.json` can never turn that file into a directory.
 * Directories already on disk are not moved. A changed id gets its sanitised prefix (at most 100 characters), `~` and 16 lowercase hex digits of
 * the SHA-256 of the id, so two ids that differ only in unsafe characters never share a directory. The `~` is outside the
 * safe class, so no safe id can ever equal an altered id's name.
 */
internal fun storeDirName(rendererId: String): String {
    val legacy = legacyStoreDirName(rendererId)
    if (legacy == rendererId && !namesADataFile(legacy)) return legacy
    val digest = MessageDigest.getInstance("SHA-256").digest(rendererId.toByteArray(Charsets.UTF_8))
    val hash = digest.take(NAME_HASH_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
    return legacy.take(ALTERED_PREFIX_MAX_CHARS) + "~" + hash
}

/**
 * The marker file the one-time store move writes into the data directory before its first move. Its name holds a `~` and no
 * 16-hex tail, so no store directory can ever carry it.
 */
internal const val LEGACY_STORES_MARKER = "legacy-stores~moved"

/**
 * The bound to build the [PortRegistry] with: [PortRegistry.MAX_SAVED] once the one-time pass of [moveLegacyStores] has
 * written its marker file, else [Int.MAX_VALUE]. ports.json is pruned only after the pass has recorded that it ran; until then
 * the file is the previous version's, whatever its size, and the pass must read it as that version left it. A directory at the
 * marker path is not the marker.
 */
internal fun savedPortsBound(dataDir: File): Int =
    if (File(dataDir, LEGACY_STORES_MARKER).isFile) PortRegistry.MAX_SAVED else Int.MAX_VALUE

/** One rename inside one directory: a crash cannot leave two half copies (never copy then delete). */
private fun moveDirAtomically(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Moves the old, plain-sanitised store directory of an altered id to its new [storeDirName], once. Runs at the first bridge
 * start after the update (until [LEGACY_STORES_MARKER] exists), before the [PortRegistry] is built. The store records no UDN, so
 * the one record of who used an old directory is the ports.json the previous version left: that version never pruned it, and the
 * new code does not prune it before the marker exists ([savedPortsBound]), so its key set is read raw, once, whatever its size.
 * A directory moves only when that record names exactly one speaker whose old name it is and that id was altered; a safe id in
 * the record (then the directory is that speaker's own), two altered ids for one old name, or no id at all for it: nobody takes
 * the directory. So pruning by the new code and an id first seen after the update can never move a store.
 *
 * Never throws. A ports.json that cannot be read or parsed is one [warn] line and an empty record (nothing moves, the marker is
 * still written). The marker is written before the first move; if that fails, one warn line and nothing moves. A failed move is
 * one warn line and that speaker starts with a fresh store and pairs once. Accepted residuals: a safe speaker the record never
 * held can lose its directory to the one altered id the record names for it (which already shared that directory), and a crash
 * after the marker and before the moves costs the altered speaker one re-pair.
 *
 * Returns the number of directories moved. [readRecord] is the test seam for a read that throws.
 */
internal fun moveLegacyStores(dataDir: File, moveDir: (File, File) -> Unit = ::moveDirAtomically, readRecord: (File) -> String = { it.readText() }, warn: (String) -> Unit = {}): Int {
    val marker = File(dataDir, LEGACY_STORES_MARKER)
    if (marker.isFile) return 0

    val record: Set<String> = try {
        val portsFile = File(dataDir, "ports.json")
        if (!portsFile.exists()) {
            emptySet()
        } else {
            val parsed = Json.parseToJsonElement(readRecord(portsFile))
            if (parsed !is JsonObject) throw IOException("$portsFile is not a JSON object")
            parsed.keys.toSet()
        }
    } catch (e: Exception) {
        warn("Could not read the saved ports for the store move: ${e.message}")
        emptySet()
    }

    try {
        writeTextAtomic(marker, "", AtomicWriteOptions.RENDERER)
    } catch (e: Exception) {
        warn("Could not write the store move marker, nothing moves at this start: ${e.message}")
        return 0
    }

    val safeIds = HashSet<String>()
    val alteredByOldName = LinkedHashMap<String, MutableList<String>>()
    for (id in record) {
        val legacy = legacyStoreDirName(id)
        if (legacy == id) safeIds += id else alteredByOldName.getOrPut(legacy) { ArrayList() } += id
    }

    var moved = 0
    for ((old, ids) in alteredByOldName) {
        if (ids.size != 1 || old in safeIds || old.isEmpty() || old == "." || old == "..") continue
        try {
            val from = File(dataDir, old)
            val to = File(dataDir, storeDirName(ids.single()))
            if (!from.isDirectory || to.exists()) continue
            moveDir(from, to)
            moved++
        } catch (e: Exception) {
            warn("Could not move the old store directory of a speaker: ${e.message}")
        }
    }
    return moved
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
