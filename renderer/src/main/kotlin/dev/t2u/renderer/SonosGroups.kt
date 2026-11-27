// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// Sonos groups, shown as Metrolist shows them: one cast target per group, on its coordinator, named after the members
// ("Deck + Lamp"); the other members leave the cast menu until the group is split.
//
// Each speaker is believed only about itself: whether it is a member comes from its own answer, and a coordinator's group
// name from the coordinator's own answer. A device on the LAN that makes up a group can then only rename or hide itself.

import dev.t2u.upnp.SonosGroup
import dev.t2u.upnp.isSonosRendererUdn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** How often the Sonos speakers are asked about their groups (Metrolist's discovery interval). */
const val SONOS_GROUP_POLL_MS = 15_000L

/** A speaker that did not answer this many reads in a row is forgotten, and with it what it said about its group. */
private const val MAX_MISSES = 2

/** How many searches follow a speaker's release from a group while it is still not back. */
private const val RELEASE_SEARCHES = 4

/**
 * [read] asks the speaker at a description URL for the household's groups and throws when it gets no answer. [present] is
 * true for a speaker that is a cast target, or that the author hid: only then do the members of its group stay out of the
 * cast menu, so a group whose coordinator cannot be listed never disappears. [refresh] is called from one coroutine at a
 * time; every other function may be called from any thread.
 */
internal class SonosGroups(
    private val read: suspend (location: String) -> List<SonosGroup>,
    private val present: (udn: String) -> Boolean,
) {
    /** [group] is the one the speaker puts itself in; null until it answered. */
    private data class Seat(val location: String, val group: SonosGroup?, val misses: Int = 0)

    private val seats = ConcurrentHashMap<String, Seat>()

    private val _members = MutableStateFlow<Set<String>>(emptySet())

    /** The speakers that are out of the cast menu because another speaker coordinates their group. */
    val members: StateFlow<Set<String>> = _members.asStateFlow()

    /** Speakers released from a group and not back yet, with the number of searches still owed to each. */
    private val released = HashMap<String, Int>()

    /** True while [udn] belongs to a group whose coordinator is [present]. */
    fun isMember(udn: String): Boolean {
        val coordinator = seats[udn]?.group?.coordinator ?: return false
        return coordinator != udn && present(coordinator)
    }

    /** The coordinator of the group [udn] says it is in, or null. */
    fun coordinatorOf(udn: String): String? = seats[udn]?.group?.coordinator

    /**
     * The name of the group [udn] coordinates: its visible members' names joined by " + ", each the author's [userName]
     * for that speaker when there is one, else its Sonos room name. Null unless it coordinates two or more rooms.
     */
    fun groupName(udn: String, userName: (String) -> String?): String? {
        val group = seats[udn]?.group?.takeIf { it.coordinator == udn } ?: return null
        val rooms = group.members.filter { it.visible }
        if (rooms.map { it.zoneName }.distinct().size < 2) return null
        return rooms.map { userName(it.udn) ?: it.zoneName }.filter { it.isNotBlank() }.distinct().joinToString(" + ")
            .ifBlank { null }
    }

    /**
     * Asks every known Sonos speaker, [running] ones (UDN to description URL) and members that already left the menu,
     * about its group. Returns true when a speaker released from a group is still not running, so a search should be sent.
     */
    suspend fun refresh(running: Map<String, String>): Boolean {
        for ((udn, location) in running) {
            if (!isSonosRendererUdn(udn)) continue
            val seat = seats[udn]
            if (seat == null || seat.location != location) seats[udn] = Seat(location, seat?.group)
        }
        val asked = seats.entries.map { it.key to it.value }
        val answers = coroutineScope {
            asked.map { (udn, seat) ->
                async {
                    val groups = try {
                        read(seat.location)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    Triple(udn, seat, groups)
                }
            }.awaitAll()
        }
        for ((udn, seat, groups) in answers) {
            val own = groups?.firstOrNull { group -> group.members.any { it.udn == udn } }
            when {
                own != null -> seats[udn] = Seat(seat.location, own)
                seat.misses + 1 >= MAX_MISSES -> seats.remove(udn)
                else -> seats[udn] = seat.copy(misses = seat.misses + 1)
            }
        }
        val before = _members.value
        val now = seats.keys.filterTo(HashSet()) { isMember(it) }
        _members.value = now
        for (udn in before - now) released[udn] = RELEASE_SEARCHES
        released.keys.removeAll { it in now || it in running }
        if (released.isEmpty()) return false
        for (udn in released.keys.toList()) {
            val left = released.getValue(udn) - 1
            if (left <= 0) released.remove(udn) else released[udn] = left
        }
        return true
    }
}
