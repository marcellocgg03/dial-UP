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
                val entry = value as? JsonObject
                val name = (entry?.get("name") as? JsonPrimitive)?.content.orEmpty()
                val address = (entry?.get("address") as? JsonPrimitive)?.content.orEmpty()
                loaded[udn] = HiddenSpeaker(udn, name, address)
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

    /** Hides [entry] (a second add of the same UDN refreshes its name and address) and writes the file. True when it is hidden afterwards. */
    suspend fun add(entry: HiddenSpeaker): Boolean {
        synchronized(lock) {
            items[entry.udn] = entry
            publish()
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
