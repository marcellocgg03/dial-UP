// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI-03 (plan 07-11, D-08): the speakers the author hid from the cast menus. The set lives in <dataDir>/hidden.json next to
// ports.json, as a JSON object keyed by UDN: {"uuid": {"name": "Kitchen", "address": "192.168.1.20"}}. The name and address are
// kept only so the Hidden section can show something the author recognises while the speaker is not running.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

private val prettyHiddenJson = Json { prettyPrint = true }

/** T-07-27: the hidden set never holds more than this many speakers (a damaged or runaway file cannot grow it). */
const val MAX_HIDDEN = 128

/** A speaker the author hid: its UDN and what it was called and where it was when it was hidden. */
data class HiddenSpeaker(val udn: String, val name: String, val address: String)

/**
 * The hidden set. [warn] receives one line per problem (unreadable file, dropped entry, refused add, failed write); it is
 * never used for control flow and nothing here throws on a bad file. [contains] reads a volatile snapshot, so the registry
 * can ask from any thread without a lock.
 */
class HiddenSpeakers(dataDir: File, private val warn: (String) -> Unit = {}) {
    private val file = File(dataDir, "hidden.json")
    private val lock = Any()
    private val items = LinkedHashMap<String, HiddenSpeaker>()
    private val writeMutex = Mutex()

    @Volatile private var snapshot: Set<String> = emptySet()

    private val _entries = MutableStateFlow<List<HiddenSpeaker>>(emptyList())

    /** The hidden speakers in the order they were hidden; follows every add and remove. */
    val entries: StateFlow<List<HiddenSpeaker>> = _entries.asStateFlow()

    /** Reads hidden.json. A missing file is empty; anything unreadable is ignored with a warning. */
    fun load(): HiddenSpeakers {
        val loaded = LinkedHashMap<String, HiddenSpeaker>()
        try {
            val data = Json.parseToJsonElement(file.readText())
            if (data !is JsonObject) throw IllegalArgumentException("not a JSON object")
            for ((udn, value) in data) {
                val name = stringField(value, "name")
                val address = stringField(value, "address")
                when {
                    name == null || address == null ->
                        warn("Ignoring entry '$udn' in $file: it needs a string name and a string address")
                    loaded.size >= MAX_HIDDEN ->
                        warn("Ignoring entry '$udn' in $file: more than $MAX_HIDDEN hidden speakers")
                    else -> loaded[udn] = HiddenSpeaker(udn, name, address)
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // nothing hidden yet
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

    /** True when [udn] is hidden. Safe from any thread. */
    fun contains(udn: String): Boolean = udn in snapshot

    /** Hides [entry] (a second add of the same UDN refreshes its name and address) and writes the file. False, with a warning, when [MAX_HIDDEN] other speakers are hidden already. */
    suspend fun add(entry: HiddenSpeaker): Boolean {
        val accepted = synchronized(lock) {
            if (entry.udn !in items && items.size >= MAX_HIDDEN) {
                false
            } else {
                items[entry.udn] = entry
                publish()
                true
            }
        }
        if (!accepted) {
            warn("Not hiding '${entry.udn}': $MAX_HIDDEN speakers are hidden already")
            return false
        }
        persist()
        return true
    }

    /** Brings [udn] back and writes the file. False when it was not hidden. */
    suspend fun remove(udn: String): Boolean {
        val removed = synchronized(lock) {
            val had = items.remove(udn) != null
            if (had) publish()
            had
        }
        if (removed) persist()
        return removed
    }

    private fun stringField(entry: kotlinx.serialization.json.JsonElement, key: String): String? {
        val field = (entry as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return if (field.isString) field.content else null
    }

    /** Call with [lock] held. */
    private fun publish() {
        snapshot = items.keys.toSet()
        _entries.value = items.values.toList()
    }

    private suspend fun persist() {
        // Several writes share one temp file, so run them one at a time; the snapshot is taken inside the mutex, so the last
        // writer always writes every entry.
        writeMutex.withLock {
            val current = synchronized(lock) { items.values.toList() }
            try {
                withContext(Dispatchers.IO) {
                    val json = JsonObject(
                        current.associate { e ->
                            e.udn to JsonObject(mapOf("name" to JsonPrimitive(e.name), "address" to JsonPrimitive(e.address)))
                        },
                    )
                    writeAtomically(file, prettyHiddenJson.encodeToString(JsonObject.serializer(), json))
                }
            } catch (e: IOException) {
                warn("Could not save $file: ${e.message}")
            }
        }
    }
}
