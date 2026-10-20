package dev.t2u.renderer

// Port of tube2upnp test/ports.test.js, GPL-3.0-only. The seven titles are kept verbatim.

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CAST-02: a speaker keeps its DIAL port whatever the discovery order. */
class PortsTest {
    private fun tempDir(): File = Files.createTempDirectory("ports-").toFile().also { it.deleteOnExit() }

    private fun make(dataDir: File = tempDir(), basePort: Int = 3000, warn: (String) -> Unit = {}) =
        PortRegistry(dataDir, basePort, warn).load()

    private fun portsFile(dataDir: File): JsonObject =
        Json.parseToJsonElement(File(dataDir, "ports.json").readText()) as JsonObject

    private fun expectedFile(vararg pairs: Pair<String, Int>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    @DisplayName("hands out consecutive ports and reuses a released one")
    fun consecutiveAndReleased() {
        val r = make()
        assertEquals(3000, r.claim("a"))
        assertEquals(3001, r.claim("b"))
        r.release(3000)
        assertEquals(3000, r.claim("c"))
    }

    @Test
    @DisplayName("a speaker keeps its port across restarts whatever the discovery order")
    fun keepsPortAcrossRestarts() = runBlocking {
        val dataDir = tempDir()
        val first = make(dataDir)
        for (udn in listOf("kitchen", "lamp", "deck")) first.remember(udn, first.claim(udn))
        assertEquals(expectedFile("kitchen" to 3000, "lamp" to 3001, "deck" to 3002), portsFile(dataDir))

        val second = make(dataDir)
        assertEquals(3002, second.claim("deck"))
        assertEquals(3001, second.claim("lamp"))
        assertEquals(3000, second.claim("kitchen"))
    }

    @Test
    @DisplayName("a new device never takes the port remembered for an offline one")
    fun newDeviceNeverTakesRememberedPort() = runBlocking {
        val dataDir = tempDir()
        val first = make(dataDir)
        first.remember("kitchen", first.claim("kitchen"))
        first.remember("lamp", first.claim("lamp"))

        val second = make(dataDir)
        assertEquals(3002, second.claim("newcomer"))
        assertEquals(3001, second.claim("lamp"))
    }

    @Test
    @DisplayName("devices that never came up (filtered, failed) are not remembered")
    fun neverCameUpNotRemembered() {
        val dataDir = tempDir()
        val r = make(dataDir)
        r.claim("tv")
        r.release(3000)
        val next = make(dataDir)
        assertEquals(3000, next.claim("lamp"))
    }

    @Test
    @DisplayName("tolerates a corrupt ports.json")
    fun toleratesCorrupt() {
        val dataDir = tempDir()
        File(dataDir, "ports.json").writeText("{nope")
        val warnings = CopyOnWriteArrayList<String>()
        val r = make(dataDir, warn = { warnings += it })
        assertEquals(3000, r.claim("a"))
        assertEquals(1, warnings.size, "one warning for the unreadable file")
    }

    @Test
    @DisplayName("ignores a ports.json of the wrong shape, and entries that are not ports")
    fun ignoresWrongShape() {
        for (content in listOf("null", "[]", "\"3000\"", "{\"a\":\"3005\",\"b\":3001.5,\"c\":70000,\"d\":3002}")) {
            val dataDir = tempDir()
            File(dataDir, "ports.json").writeText(content)
            val r = make(dataDir)
            assertEquals(3000, r.claim("a"), content)
            assertEquals(if (content.contains("\"d\"")) 3002 else 3001, r.claim("d"), content)
        }
    }

    @Test
    @DisplayName("speakers coming up at the same time are all saved without write errors")
    fun concurrentSaves() = runBlocking {
        val dataDir = tempDir()
        val warnings = CopyOnWriteArrayList<String>()
        val r = make(dataDir, warn = { warnings += it })
        val udns = listOf("kitchen", "lamp", "deck", "family")
        coroutineScope { udns.map { u -> async { r.remember(u, r.claim(u)) } }.awaitAll() }
        assertEquals(emptyList(), warnings.toList())
        assertEquals(expectedFile("kitchen" to 3000, "lamp" to 3001, "deck" to 3002, "family" to 3003), portsFile(dataDir))
        assertEquals(mapOf("kitchen" to 3000, "lamp" to 3001, "deck" to 3002, "family" to 3003), r.saved())
    }

    // ----- additions beyond the Node suite ------------------------------------------------------

    @Test
    fun missingFileStartsEmptyWithoutWarning() {
        val warnings = CopyOnWriteArrayList<String>()
        val r = make(warn = { warnings += it })
        assertEquals(emptyMap(), r.saved())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun noTempFileLeftBehind() = runBlocking {
        val dataDir = tempDir()
        val r = make(dataDir)
        r.remember("a", r.claim("a"))
        assertTrue(File(dataDir, "ports.json").exists())
        assertTrue(!File(dataDir, "ports.json.tmp").exists())
    }
}
