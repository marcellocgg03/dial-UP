// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI rename (quick 261101-dcg, PD-4): the names the author gave their renderers. The map lives in <dataDir>/names.json next to
// hidden.json and manual.json, as a JSON object keyed by UDN: {"uuid": {"name": "Kitchen"}}. A user name replaces the whole shown
// name (card and DIAL friendly name) exactly as typed, trimmed. Matching (hide, manual, ports, the name filter) never uses it.

import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

private val prettyNamesJson = Json { prettyPrint = true }

/** A UPnP friendlyName "should be < 64 characters" (PD-4); counted in code points, so an emoji counts once. */
const val MAX_RENDERER_NAME = 63

/** T-dcg-02: the names map never holds more than this many renderers (a damaged or runaway file cannot grow it). */
const val MAX_NAMES = 128

/**
 * Why [raw] cannot be a renderer name, or null when it can: blank, a control character (line break, tab; so none can reach an
 * XML body or a log line), or more than [MAX_RENDERER_NAME] characters. The text is judged trimmed.
 */
fun rendererNameProblem(raw: String): String? {
    val text = raw.trim()
    return when {
        text.isEmpty() -> "Enter a name"
        text.any { Character.isISOControl(it) } -> "No line breaks or tabs"
        text.codePointCount(0, text.length) > MAX_RENDERER_NAME -> "At most $MAX_RENDERER_NAME characters"
        else -> null
    }
}

/** The trimmed [raw] when it is a usable name, else null. */
fun cleanRendererName(raw: String): String? = if (rendererNameProblem(raw) == null) raw.trim() else null

/** The name a renderer shows: the cleaned [userName] when there is a usable one, else the [describedName]. */
fun shownRendererName(userName: String?, describedName: String): String =
    userName?.let { cleanRendererName(it) } ?: describedName

/**
 * The renderer names. [warn] receives one line per problem (unreadable file, dropped entry, refused set, failed write); it is
 * never used for control flow and nothing here throws on a bad file. [get] reads a volatile snapshot, so a renderer can ask from
 * any thread without a lock.
 */
class RendererNames(dataDir: File, private val warn: (String) -> Unit = {}) {
    private val file = File(dataDir, "names.json")
    private val lock = Any()
    private val items = LinkedHashMap<String, String>()
    private val writeMutex = Mutex()

    @Volatile private var snapshot: Map<String, String> = emptyMap()

    private val _entries = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The names by UDN in the order they were set; follows every set and remove. */
    val entries: StateFlow<Map<String, String>> = _entries.asStateFlow()

    /** Reads names.json. A missing file is empty; anything unreadable is ignored with a warning. */
    fun load(): RendererNames {
        val loaded = LinkedHashMap<String, String>()
        try {
            val data = Json.parseToJsonElement(file.readText())
            if (data !is JsonObject) throw IllegalArgumentException("not a JSON object")
            for ((udn, value) in data) {
                val name = stringField(value, "name")?.let { cleanRendererName(it) }
                when {
                    name == null -> warn("Ignoring entry '$udn' in $file: it needs a usable string name")
                    loaded.size >= MAX_NAMES -> warn("Ignoring entry '$udn' in $file: more than $MAX_NAMES named renderers")
                    else -> loaded[udn] = name
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // nothing named yet
        } catch (_: java.nio.file.NoSuchFileException) {
        } catch (e: Exception) {
            warn("Ignoring unreadable $file: ${e.message}")
            loaded.clear()
        }
        synchronized(lock) {
            items.clear()
            items.putAll(loaded)
            publish()
        }
        return this
    }

    /** The name of [udn], or null. Safe from any thread. */
    fun get(udn: String): String? = snapshot[udn]

    /** Names [udn] [raw] (trimmed) and writes the file. False, with a warning, when the name is unusable or [MAX_NAMES] other renderers are named already. */
    suspend fun set(udn: String, raw: String): Boolean {
        val name = cleanRendererName(raw)
        if (name == null) {
            warn("Not naming '$udn': ${rendererNameProblem(raw)}")
            return false
        }
        val accepted = synchronized(lock) {
            if (udn !in items && items.size >= MAX_NAMES) {
                false
            } else {
                items[udn] = name
                publish()
                true
            }
        }
        if (!accepted) {
            warn("Not naming '$udn': $MAX_NAMES renderers are named already")
            return false
        }
        persist()
        return true
    }

    /** Forgets the name of [udn] and writes the file. False when it had none. */
    suspend fun remove(udn: String): Boolean {
        val removed = synchronized(lock) {
            val had = items.remove(udn) != null
            if (had) publish()
            had
        }
        if (removed) persist()
        return removed
    }

    private fun stringField(entry: JsonElement, key: String): String? {
        val field = (entry as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return if (field.isString) field.content else null
    }

    /** Call with [lock] held. */
    private fun publish() {
        snapshot = LinkedHashMap(items)
        _entries.value = snapshot
    }

    private suspend fun persist() {
        // Several writes share one temp file, so run them one at a time; the snapshot is taken inside the mutex, so the last
        // writer always writes every entry.
        writeMutex.withLock {
            val current = synchronized(lock) { LinkedHashMap(items) }
            try {
                withContext(Dispatchers.IO) {
                    val json = JsonObject(current.mapValues { (_, name) -> JsonObject(mapOf("name" to JsonPrimitive(name))) })
                    writeTextAtomic(file, prettyNamesJson.encodeToString(JsonObject.serializer(), json), AtomicWriteOptions.RENDERER)
                }
            } catch (e: IOException) {
                warn("Could not save $file: ${e.message}")
            }
        }
    }
}
