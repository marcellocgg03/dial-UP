package dev.t2u.renderer

// Port of tube2upnp src/ports.js, GPL-3.0-only.
//
// Stable DIAL port per renderer, persisted in <dataDir>/ports.json ({"udn": port}, same shape as the
// Node file). YouTube apps cache a receiver's address, so if a speaker's port changes across restarts
// (discovery order is a race), a cast to "Lamp" can land on whichever speaker now holds its old port.
// A speaker gets its remembered port back; ports remembered for other speakers are never handed out.

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
 * [warn] receives one line per problem (unreadable file, wrong shape, dropped entry,
 * failed write). It is never used for control flow and nothing here throws on bad files.
 */
class PortRegistry(
    private val dataDir: File,
    private val basePort: Int = 3000,
    private val warn: (String) -> Unit = {},
) {
    private val file = File(dataDir, "ports.json")
    private val lock = Any()
    private val saved = LinkedHashMap<String, Int>()
    private val inUse = HashSet<Int>()
    private val writeMutex = Mutex()

    /** Reads ports.json. A missing file is empty; anything unreadable or of the wrong shape is ignored with a warning. */
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
        }
        return this
    }

    /** Reserve a port for [udn]: its remembered one if free, else the lowest port nobody holds or remembers. */
    fun claim(udn: String): Int {
        synchronized(lock) {
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

    /** Persist the port once the renderer is up, so devices that get filtered out do not reserve one. */
    suspend fun remember(udn: String, port: Int) {
        synchronized(lock) {
            if (saved[udn] == port) return
            saved[udn] = port
        }
        // Several speakers come up at once; writes share one temp file, so run them one at a time.
        // The snapshot is taken inside the mutex, so the last writer always writes every entry.
        writeMutex.withLock {
            val snapshot = synchronized(lock) { LinkedHashMap(saved) }
            try {
                withContext(Dispatchers.IO) {
                    val json = JsonObject(snapshot.mapValues { JsonPrimitive(it.value) })
                    writeTextAtomic(file, prettyJson.encodeToString(JsonObject.serializer(), json), AtomicWriteOptions.RENDERER)
                }
            } catch (e: IOException) {
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
