// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI-03 (plan 07-11, D-08): the speakers the author hid from the cast menus. The set lives in <dataDir>/hidden.json next to
// ports.json, as a JSON object keyed by UDN: {"uuid": {"name": "Kitchen", "address": "192.168.1.20"}}. The name and address are
// kept only so the Hidden section can show something the author recognises while the speaker is not running.

import dev.t2u.lounge.PlayerStatus
import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.parseIpv4Literal
import dev.t2u.upnp.stringOrNull
import dev.t2u.upnp.writeTextAtomic
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
                val name = (value as? JsonObject)?.stringOrNull("name")
                val address = (value as? JsonObject)?.stringOrNull("address")
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
                    writeTextAtomic(file, prettyHiddenJson.encodeToString(JsonObject.serializer(), json), AtomicWriteOptions.RENDERER)
                }
            } catch (e: IOException) {
                warn("Could not save $file: ${e.message}")
            }
        }
    }
}

private val prettyManualJson = Json { prettyPrint = true }

/** T-07-31: the manual list never holds more than this many speakers (a damaged or runaway file cannot grow it). */
const val MAX_MANUAL = 32

/** GUI-04 (plan 07-13, D-06): a speaker the author added by typing its IPv4 address, kept so it is probed at every start. */
data class ManualSpeaker(val ip: String, val udn: String, val name: String, val location: String)

/**
 * The speakers added by address, in `<dataDir>/manual.json` as a JSON object keyed by UDN:
 * {"uuid": {"ip": "192.168.1.40", "name": "Kitchen", "location": "http://192.168.1.40:1400/xml/device_description.xml"}}.
 * The bridge probes every entry at start (through the same guards as the first add) so the card is back without adding it again.
 * [warn] receives one line per problem and is never used for control flow; nothing here throws on a bad file.
 */
class ManualSpeakers(dataDir: File, private val warn: (String) -> Unit = {}) {
    private val file = File(dataDir, "manual.json")
    private val lock = Any()
    private val items = LinkedHashMap<String, ManualSpeaker>()
    private val writeMutex = Mutex()

    private val _entries = MutableStateFlow<List<ManualSpeaker>>(emptyList())

    /** The saved speakers in the order they were added; follows every add and remove. */
    val entries: StateFlow<List<ManualSpeaker>> = _entries.asStateFlow()

    /** Reads manual.json. A missing file is empty; anything unreadable is ignored with a warning. */
    fun load(): ManualSpeakers {
        val loaded = LinkedHashMap<String, ManualSpeaker>()
        try {
            val data = Json.parseToJsonElement(file.readText())
            if (data !is JsonObject) throw IllegalArgumentException("not a JSON object")
            for ((udn, value) in data) {
                val ip = (value as? JsonObject)?.stringOrNull("ip")
                val name = (value as? JsonObject)?.stringOrNull("name")
                val location = (value as? JsonObject)?.stringOrNull("location")
                when {
                    ip == null || name == null || location == null || udn.isEmpty() || location.isEmpty() ->
                        warn("Ignoring entry '$udn' in $file: it needs a UDN and a string ip, name and location")
                    !validAddress(ip) ->
                        warn("Ignoring entry '$udn' in $file: '$ip' is not a private LAN IPv4 address")
                    loaded.size >= MAX_MANUAL && udn !in loaded && loaded.values.none { it.ip == ip } ->
                        warn("Ignoring entry '$udn' in $file: more than $MAX_MANUAL manual speakers")
                    else -> {
                        loaded.entries.removeIf { it.key == udn || it.value.ip == ip }
                        loaded[udn] = ManualSpeaker(ip, udn, name, location)
                    }
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            // nothing saved yet
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

    /**
     * Saves [entry], replacing an entry with the same UDN or the same address, and writes the file. False, with a warning, when
     * the address is not a private LAN IPv4 literal or [MAX_MANUAL] other speakers are saved already.
     */
    suspend fun add(entry: ManualSpeaker): Boolean {
        val refusal = synchronized(lock) {
            val replaced = items.values.count { it.udn == entry.udn || it.ip == entry.ip }
            when {
                !validAddress(entry.ip) -> "'${entry.ip}' is not a private LAN IPv4 address"
                replaced == 0 && items.size >= MAX_MANUAL -> "$MAX_MANUAL renderers are saved already"
                else -> {
                    items.entries.removeIf { it.key == entry.udn || it.value.ip == entry.ip }
                    items[entry.udn] = entry
                    publish()
                    null
                }
            }
        }
        if (refusal != null) {
            warn("Not saving '${entry.udn}': $refusal")
            return false
        }
        persist()
        return true
    }

    /** T-07-31: the address is re-checked here, so a damaged file or a careless caller cannot save an aim at a public address. */
    private fun validAddress(ip: String): Boolean {
        val parsed = parseIpv4Literal(ip) ?: return false
        return isPrivateLanAddress(parsed)
    }

    /** Forgets [udn] and writes the file. False when it was not saved. */
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
                            e.udn to JsonObject(
                                mapOf(
                                    "ip" to JsonPrimitive(e.ip),
                                    "name" to JsonPrimitive(e.name),
                                    "location" to JsonPrimitive(e.location),
                                ),
                            )
                        },
                    )
                    writeTextAtomic(file, prettyManualJson.encodeToString(JsonObject.serializer(), json), AtomicWriteOptions.RENDERER)
                }
            } catch (e: IOException) {
                warn("Could not save $file: ${e.message}")
            }
        }
    }
}

/**
 * The greyed card of every saved renderer whose UDN is not in [hidden], in the saved order (GUI-04, D-07, PD-3): idle, no track,
 * no navigation, not online, marked manual. A hidden saved renderer stays in manual.json but is not listed: a hidden one must
 * not come back as a greyed card, with the bridge on or off. A user name in [names] (by UDN, quick 261101-dcg) replaces the saved name on the card.
 */
fun savedSpeakerCards(saved: List<ManualSpeaker>, hidden: Set<String>, names: Map<String, String> = emptyMap()): List<SpeakerUi> =
    saved.filter { it.udn !in hidden }.map { entry ->
        SpeakerUi(
            udn = entry.udn,
            name = names[entry.udn] ?: entry.name,
            address = entry.ip,
            status = PlayerStatus.IDLE,
            track = null,
            error = null,
            hasPrevious = false,
            hasNext = false,
            queueHasCurrent = false,
            position = null,
            online = false,
            manual = true,
        )
    }
