package dev.t2u.renderer

// Port of tube2upnp src/ports.js, GPL-3.0-only.
//
// Stable DIAL port per renderer, persisted in <dataDir>/ports.json ({"udn": port}, same shape as the
// Node file). YouTube apps cache a receiver's address, so if a speaker's port changes across restarts
// (discovery order is a race), a cast to "Lamp" can land on whichever speaker now holds its old port.
// A speaker gets its remembered port back; ports remembered for other speakers are never handed out.
//
// Entries are kept in last-seen order, oldest first (the order of the JSON object in the file): a setup, a refresh and a
// removal move a speaker to the end; the order reaches the file with the next write and otherwise at most every
// RECENCY_WRITE_MS. At most maxSaved entries are kept: past it the oldest whose port is not in use are dropped, an entry read
// from the file and not yet seen in this run only after PRUNE_GRACE_MS. A dropped speaker that returns gets a free port that
// no running speaker holds and no remembered speaker owns (accepted: identity lives in the per-speaker store, not in the
// port). The Node file shape is unchanged, and a file written by an older version loads whole, whatever its size.

import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException

private const val MAX_PORT = 65535

private val prettyJson = Json { prettyPrint = true }

/**
 * [warn] receives one line per problem (unreadable file, wrong shape, dropped entries,
 * failed write). It is never used for control flow and nothing here throws on bad files.
 *
 * [maxSaved] bounds the remembered entries; [Int.MAX_VALUE] never drops one. [clock] is epoch ms (a test seam).
 */
class PortRegistry(
    private val dataDir: File,
    private val basePort: Int = 3000,
    private val maxSaved: Int = MAX_SAVED,
    private val clock: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = {},
) {
    companion object {
        /** ports.json keeps at most this many entries; past it the oldest-unseen entries whose port is not in use are dropped. */
        const val MAX_SAVED = 256

        /**
         * An entry read from ports.json and not seen in this run is kept this long after the load, even past [MAX_SAVED]. Longer
         * than the first retry of a failed setup (RendererRegistry.RETRY_FAILED_MS plus RETRY_CHECK_MS), so a live speaker that
         * comes up after a start keeps its entry.
         */
        const val PRUNE_GRACE_MS = 6 * 60 * 1000L

        /** The most often a [touch] writes a changed order to the file. */
        const val RECENCY_WRITE_MS = 10 * 60 * 1000L
    }

    private val file = File(dataDir, "ports.json")
    private val lock = Any()
    private val saved = LinkedHashMap<String, Int>()
    private val inUse = HashSet<Int>()
    private val writeMutex = Mutex()

    // All guarded by [lock].
    private var loadedAt = clock()
    private var lastWriteAt = clock()

    /** The keys [load] read that no claim, remember or touch has marked seen yet. */
    private val unseen = HashSet<String>()

    /** The order in memory is not on disk yet. */
    private var orderDirty = false
    private var dropLogged = false

    /** Reads ports.json. A missing file is empty; anything unreadable or of the wrong shape is ignored with a warning. Drops nothing, whatever the count. */
    fun load(): PortRegistry {
        val loaded = LinkedHashMap<String, Int>()
        try {
            val data = Json.parseToJsonElement(file.readText())
            if (data !is JsonObject) throw IllegalArgumentException("not a JSON object")
            for ((udn, value) in data) {
                val port = asPort(value)
                if (port == null) warn("Ignoring entry '$udn' in $file: not a port (integer 1..$MAX_PORT)") else loaded[udn] = port
            }
        } catch (_: FileNotFoundException) {
            // first run: nothing remembered yet
        } catch (_: NoSuchFileException) {
        } catch (e: Exception) {
            warn("Ignoring unreadable $file: ${e.message}")
            loaded.clear()
        }
        synchronized(lock) {
            saved.clear()
            saved.putAll(loaded)
            unseen.clear()
            unseen.addAll(loaded.keys)
            orderDirty = false
            loadedAt = clock()
            lastWriteAt = loadedAt
        }
        return this
    }

    /** Reserve a port for [udn]: its remembered one if free, else the lowest port nobody holds or remembers. */
    fun claim(udn: String): Int {
        synchronized(lock) {
            unseen.remove(udn)
            val mine = saved[udn]
            if (mine != null && mine !in inUse) {
                inUse.add(mine)
                return mine
            }
            val remembered = saved.values.toSet()
            var p = basePort
            while (p in inUse || p in remembered) p++
            check(p <= MAX_PORT) { "no free DIAL port from $basePort up" }
            inUse.add(p)
            return p
        }
    }

    /** True while the start grace holds back the entries read from the file; a clock that moved backwards ends it. Call with [lock] held. */
    private fun graceOn(): Boolean {
        val d = clock() - loadedAt
        return d >= 0 && d < PRUNE_GRACE_MS
    }

    /**
     * Persist the port once the renderer is up, so devices that get filtered out do not reserve one. The speaker becomes the
     * newest entry; while more than [maxSaved] are held, the oldest whose port is not in use go (an entry read from the file and
     * not seen yet is kept through the start grace).
     */
    suspend fun remember(udn: String, port: Int) {
        var droppedNow = 0
        var warnFirstDrop = false
        synchronized(lock) {
            unseen.remove(udn)
            val unchanged = saved[udn] == port && saved.keys.lastOrNull() == udn
            if (!unchanged) {
                saved.remove(udn)
                saved[udn] = port
            }
            if (saved.size > maxSaved) {
                val graceOn = graceOn()
                val it = saved.entries.iterator()
                while (saved.size > maxSaved && it.hasNext()) {
                    val e = it.next()
                    // a speaker mid-setup or finishing its destroy holds its port in inUse (and the new entry is never dropped)
                    if (e.key == udn || e.value in inUse) continue
                    if (graceOn && e.key in unseen) continue
                    it.remove()
                    droppedNow++
                }
                if (droppedNow > 0 && !dropLogged) {
                    dropLogged = true
                    warnFirstDrop = true
                }
            }
            if (unchanged && droppedNow == 0) return
        }
        if (warnFirstDrop) warn("Dropped $droppedNow oldest entries of $file beyond $maxSaved; later drops are not logged")
        writeFile()
    }

    /**
     * Marks [udn] as just seen: a saved entry moves to the end of the order. Never drops an entry. The order reaches the file
     * only when it changed and [RECENCY_WRITE_MS] have passed since the last write (a kill loses at most that much recency; a
     * bridge stop of an unchanged order writes nothing).
     */
    suspend fun touch(udn: String) {
        val write = synchronized(lock) {
            unseen.remove(udn)
            if (udn in saved && saved.keys.lastOrNull() != udn) {
                val port = saved.remove(udn)!!
                saved[udn] = port
                orderDirty = true
            }
            val sinceWrite = clock() - lastWriteAt
            orderDirty && (sinceWrite < 0 || sinceWrite >= RECENCY_WRITE_MS)
        }
        if (write) writeFile()
    }

    private suspend fun writeFile() {
        // Several speakers come up at once; writes share one temp file, so run them one at a time.
        // The snapshot is taken inside the mutex, so the last writer always writes every entry.
        writeMutex.withLock {
            val snapshot = synchronized(lock) {
                lastWriteAt = clock()
                orderDirty = false
                LinkedHashMap(saved)
            }
            try {
                withContext(Dispatchers.IO) {
                    val json = JsonObject(snapshot.mapValues { JsonPrimitive(it.value) })
                    writeTextAtomic(file, prettyJson.encodeToString(JsonObject.serializer(), json), AtomicWriteOptions.RENDERER)
                }
            } catch (e: IOException) {
                synchronized(lock) { orderDirty = true }
                warn("Could not save $file: ${e.message}")
            }
        }
    }

    fun release(port: Int) {
        synchronized(lock) { inUse.remove(port) }
    }

    /** A copy of the remembered udn-to-port map, for state dumps. */
    fun saved(): Map<String, Int> = synchronized(lock) { LinkedHashMap(saved) }

    private fun asPort(value: kotlinx.serialization.json.JsonElement): Int? {
        if (value !is JsonPrimitive || value.isString) return null
        val d = value.content.toDoubleOrNull() ?: return null
        // Number.isInteger(3001.0) is true in JS, so 3001.0 counts; 3001.5 does not.
        if (d != Math.floor(d) || d < 1 || d > MAX_PORT) return null
        return d.toInt()
    }
}
