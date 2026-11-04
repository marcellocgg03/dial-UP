// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI-04 (plan 07-13, D-06, T-07-31): the manual-speakers store. Tolerant of a missing or damaged file, validated, capped, atomic.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualSpeakersTest {
    private fun tempDir(): File = Files.createTempDirectory("manual-").toFile().also { it.deleteOnExit() }

    private class Warnings : (String) -> Unit {
        val lines = CopyOnWriteArrayList<String>()
        override fun invoke(line: String) {
            lines.add(line)
        }
    }

    private fun store(dir: File, warnings: Warnings = Warnings()) = ManualSpeakers(dir, warnings).load()

    private fun speaker(n: Int, ip: String = "192.168.1.${n % 250 + 1}", udn: String = "uuid:$n") =
        ManualSpeaker(ip, udn, "Name $n", "http://$ip:1400/xml/device_description.xml")

    @Test
    @DisplayName("a missing manual.json loads empty without a warning")
    fun missingFileIsEmptyAndSilent() {
        val w = Warnings()
        assertTrue(store(tempDir(), w).entries.value.isEmpty())
        assertTrue(w.lines.isEmpty(), w.lines.toString())
    }

    @Test
    @DisplayName("malformed JSON loads empty with one warning")
    fun malformedJsonIsEmptyWithOneWarning() {
        val dir = tempDir()
        File(dir, "manual.json").writeText("{ not json")
        val w = Warnings()
        assertTrue(store(dir, w).entries.value.isEmpty())
        assertEquals(1, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("a file that is not a JSON object loads empty with one warning")
    fun wrongShapeIsEmptyWithOneWarning() {
        val dir = tempDir()
        File(dir, "manual.json").writeText("[1, 2]")
        val w = Warnings()
        assertTrue(store(dir, w).entries.value.isEmpty())
        assertEquals(1, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("an entry with a bad address, or no udn or location, is dropped with a warning and the others load")
    fun badEntriesAreDropped() {
        val dir = tempDir()
        File(dir, "manual.json").writeText(
            """
            {
              "good": {"ip": "192.168.1.20", "name": "Kitchen", "location": "http://192.168.1.20:1400/d.xml"},
              "public": {"ip": "8.8.8.8", "name": "Evil", "location": "http://8.8.8.8/d.xml"},
              "multicast": {"ip": "224.0.0.251", "name": "Evil", "location": "http://x/d.xml"},
              "named": {"ip": "speaker.local", "name": "Evil", "location": "http://x/d.xml"},
              "hex": {"ip": "0xc0.168.1.21", "name": "Evil", "location": "http://x/d.xml"},
              "noLocation": {"ip": "192.168.1.21", "name": "Lamp"},
              "emptyLocation": {"ip": "192.168.1.22", "name": "Lamp", "location": ""},
              "noIp": {"name": "Lamp", "location": "http://x/d.xml"},
              "numeric": {"ip": "192.168.1.23", "name": 5, "location": "http://x/d.xml"},
              "notAnObject": "x",
              "alsoGood": {"ip": "10.0.0.7", "name": "Deck", "location": "http://10.0.0.7:1400/d.xml"}
            }
            """.trimIndent(),
        )
        val w = Warnings()
        val s = store(dir, w)
        assertEquals(listOf("good", "alsoGood"), s.entries.value.map { it.udn })
        assertEquals(9, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("an added speaker is there after a fresh load")
    fun addThenFreshLoad() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        assertTrue(s.add(speaker(1)))
        assertEquals(listOf(speaker(1)), store(dir).entries.value)
    }

    @Test
    @DisplayName("adding the same udn or the same address again replaces the entry")
    fun addReplaces() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        s.add(speaker(1))
        s.add(speaker(2))
        // same udn, new address
        s.add(speaker(1, ip = "192.168.1.99"))
        assertEquals(listOf("uuid:2", "uuid:1"), s.entries.value.map { it.udn })
        assertEquals("192.168.1.99", s.entries.value.last().ip)
        // same address, new udn
        s.add(speaker(3, ip = "192.168.1.99", udn = "uuid:new"))
        assertEquals(listOf("uuid:2", "uuid:new"), s.entries.value.map { it.udn })
        assertEquals(s.entries.value, store(dir).entries.value)
    }

    @Test
    @DisplayName("more than MAX_MANUAL speakers are refused with a warning")
    fun capIsEnforced() = runBlocking {
        val w = Warnings()
        val s = store(tempDir(), w)
        for (i in 1..MAX_MANUAL) assertTrue(s.add(speaker(i, ip = "10.0.${i / 250}.${i % 250 + 1}")), "add $i")
        assertFalse(s.add(speaker(1000, ip = "10.9.9.9")))
        assertEquals(MAX_MANUAL, s.entries.value.size)
        assertEquals(1, w.lines.size, w.lines.toString())
        // replacing an entry that is there is not growth
        assertTrue(s.add(speaker(1, ip = "10.0.0.1")))
        assertEquals(MAX_MANUAL, s.entries.value.size)
    }

    @Test
    @DisplayName("a file holding more than MAX_MANUAL entries loads the first MAX_MANUAL and warns")
    fun oversizedFileIsCapped() {
        val dir = tempDir()
        val body = (1..MAX_MANUAL + 5).joinToString(",") { i ->
            "\"u$i\": {\"ip\": \"10.1.${i / 250}.${i % 250 + 1}\", \"name\": \"n$i\", \"location\": \"http://x/$i\"}"
        }
        File(dir, "manual.json").writeText("{$body}")
        val w = Warnings()
        assertEquals(MAX_MANUAL, store(dir, w).entries.value.size)
        assertEquals(5, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("remove persists and no temp file is left behind")
    fun removePersistsAndLeavesNoTemp() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        s.add(speaker(1))
        s.add(speaker(2))
        assertTrue(s.remove("uuid:1"))
        assertFalse(s.remove("uuid:1"))
        assertEquals(listOf("uuid:2"), store(dir).entries.value.map { it.udn })
        assertFalse(File(dir, "manual.json.tmp").exists(), "no manual.json.tmp")
        assertEquals(listOf("manual.json"), dir.list()!!.toList())
    }

    @Test
    @DisplayName("a failed write warns and leaves the in-memory list as it was changed")
    fun failedWriteWarns() = runBlocking {
        val dir = tempDir()
        val w = Warnings()
        val s = store(dir, w)
        // a directory where the file should go makes the atomic rename fail
        File(dir, "manual.json").mkdirs()
        File(dir, "manual.json/keep").writeText("x")
        s.add(speaker(1))
        assertTrue(w.lines.any { it.contains("Could not save") }, w.lines.toString())
        assertEquals(1, s.entries.value.size)
    }
    @Test
    @DisplayName("ManualLiveness: a reply keeps it online, two misses in a row mean offline, a reply brings it back")
    fun livenessCountsMissedProbes() {
        val live = ManualLiveness()
        assertEquals(ManualVerdict.ONLINE, live.round(true))
        assertEquals(ManualVerdict.MISSED, live.round(false))
        assertEquals(ManualVerdict.OFFLINE, live.round(false))
        assertEquals(ManualVerdict.OFFLINE, live.round(false))
        assertEquals(ManualVerdict.ONLINE, live.round(true))
        // a reply between two misses resets the count
        assertEquals(ManualVerdict.MISSED, live.round(false))
        assertEquals(ManualVerdict.ONLINE, live.round(true))
        assertEquals(ManualVerdict.MISSED, live.round(false))
    }

    @Test
    @DisplayName("descriptionUrlAllowed accepts only an http URL on the typed address")
    fun descriptionUrlAllowedChecksTheHost() {
        val ip = java.net.InetAddress.getByName("192.168.1.40")
        assertTrue(descriptionUrlAllowed("http://192.168.1.40:1400/xml/device_description.xml", ip))
        assertTrue(descriptionUrlAllowed("http://192.168.1.40/desc.xml", ip))
        assertFalse(descriptionUrlAllowed("https://192.168.1.40:1400/xml/device_description.xml", ip))
        assertFalse(descriptionUrlAllowed("http://192.168.1.41:1400/xml/device_description.xml", ip))
        assertFalse(descriptionUrlAllowed("http://speaker.local:1400/xml/device_description.xml", ip))
        assertFalse(descriptionUrlAllowed("http://user@192.168.1.40:1400/xml/device_description.xml", ip))
        assertFalse(descriptionUrlAllowed("http://192.168.1.40:0/xml/device_description.xml", ip))
        assertFalse(descriptionUrlAllowed("http://192.168.1.40:99999/x", ip))
        assertFalse(descriptionUrlAllowed("not a url", ip))
        assertEquals("http://192.168.1.40:1400/xml/device_description.xml", sonosDescriptionUrl("192.168.1.40"))
    }

    @Test
    @DisplayName("savedSpeakerCards lists saved renderers greyed in order and skips hidden ones")
    fun savedSpeakerCardsListsGreyedAndSkipsHidden() {
        val saved = listOf(speaker(1), speaker(2), speaker(3))
        val cards = savedSpeakerCards(saved, setOf("uuid:2"))
        assertEquals(listOf("uuid:1", "uuid:3"), cards.map { it.udn })
        val first = cards.first()
        assertEquals("Name 1", first.name)
        assertEquals(saved[0].ip, first.address)
        assertEquals(dev.t2u.lounge.PlayerStatus.IDLE, first.status)
        assertEquals(null, first.track)
        assertEquals(null, first.error)
        assertEquals(null, first.position)
        assertFalse(first.hasPrevious)
        assertFalse(first.hasNext)
        assertFalse(first.queueHasCurrent)
        assertFalse(first.online)
        assertTrue(first.manual)
        assertEquals(listOf("uuid:1", "uuid:2", "uuid:3"), savedSpeakerCards(saved, emptySet()).map { it.udn })
    }

    @Test
    @DisplayName("savedSpeakerCards shows a user name")
    fun savedSpeakerCardsShowsAUserName() {
        val saved = listOf(speaker(1), speaker(2))
        val cards = savedSpeakerCards(saved, emptySet(), mapOf("uuid:2" to "Kitchen"))
        assertEquals(listOf("Name 1", "Kitchen"), cards.map { it.name })
    }
}
