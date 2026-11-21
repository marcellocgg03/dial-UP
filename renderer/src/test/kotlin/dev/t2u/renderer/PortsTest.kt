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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CAST-02: a speaker keeps its DIAL port whatever the discovery order. */
class PortsTest {
    @TempDir lateinit var tempRoot: File

    // under the per-test @TempDir, so the JUnit extension removes it (deleteOnExit does not remove a directory that has files in it)
    private fun tempDir(): File = Files.createTempDirectory(tempRoot.toPath(), "ports-").toFile()

    private fun make(
        dataDir: File = tempDir(),
        basePort: Int = 3000,
        maxSaved: Int = PortRegistry.MAX_SAVED,
        clock: () -> Long = System::currentTimeMillis,
        warn: (String) -> Unit = {},
    ) = PortRegistry(dataDir, basePort, maxSaved, clock, warn).load()

    /** The entry order of ports.json as written (a JsonObject's equality ignores order). */
    private fun fileOrder(dataDir: File): List<String> =
        (Json.parseToJsonElement(File(dataDir, "ports.json").readText()) as JsonObject).keys.toList()

    private fun writeFileOf(dataDir: File, vararg pairs: Pair<String, Int>) {
        File(dataDir, "ports.json").writeText(
            pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { "\"${it.first}\":${it.second}" },
        )
    }

    private suspend fun PortRegistry.up(udn: String): Int = claim(udn).also { remember(udn, it) }

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

    // ----- Phase 11 plan 04: ports.json stays bounded (NET-02, D-04, D-06) --------------------------

    @Test
    @DisplayName("past the bound the oldest entry whose port is not in use is dropped")
    fun prunesOldestUnseenNotInUse() = runBlocking {
        val dataDir = tempDir()
        val r = make(dataDir, maxSaved = 2)
        val a = r.up("a")
        val b = r.up("b")
        r.release(a)
        r.release(b)
        r.up("c")
        assertEquals(listOf("b", "c"), r.saved().keys.toList())
        assertEquals(listOf("b", "c"), fileOrder(dataDir))
    }

    @Test
    @DisplayName("an entry whose port is in use is never pruned")
    fun inUseEntriesAreNeverPruned() = runBlocking {
        val dataDir = tempDir()
        val r = make(dataDir, maxSaved = 2)
        r.up("a")
        r.up("b")
        r.up("c")
        assertEquals(listOf("a", "b", "c"), fileOrder(dataDir), "all three run: all three stay")
        r.release(3000)
        r.up("d")
        assertEquals(listOf("b", "c", "d"), fileOrder(dataDir), "a was released: it goes first, and only as many as needed")
    }

    @Test
    @DisplayName("remembering an entry again moves it to the end, and the last entry again writes nothing")
    fun rememberMovesTheEntryToTheEnd() = runBlocking {
        val dataDir = tempDir()
        val r = make(dataDir)
        val a = r.up("a")
        r.up("b")
        r.remember("a", a)
        assertEquals(listOf("b", "a"), fileOrder(dataDir))
        val before = File(dataDir, "ports.json").readBytes()
        File(dataDir, "ports.json").delete()
        r.remember("a", a)
        assertTrue(!File(dataDir, "ports.json").exists(), "the same entry again writes nothing")
        File(dataDir, "ports.json").writeBytes(before)
    }

    @Test
    @DisplayName("an oversized file loads whole, is kept through the start grace and shrinks after it")
    fun anOversizedFileIsKeptThroughTheStartGraceAndShrinksAfterIt() = runBlocking {
        val dataDir = tempDir()
        writeFileOf(dataDir, "e1" to 3000, "e2" to 3001, "e3" to 3002, "e4" to 3003, "e5" to 3004)
        val now = AtomicLong(5_000_000)
        val warnings = CopyOnWriteArrayList<String>()
        val r = make(dataDir, maxSaved = 3, clock = { now.get() }, warn = { warnings += it })
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), r.saved().keys.toList(), "nothing is dropped at load")

        now.addAndGet(PortRegistry.PRUNE_GRACE_MS - 1)
        r.release(r.up("n"))
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "n"), fileOrder(dataDir), "inside the grace nothing is dropped")
        assertTrue(warnings.isEmpty())

        now.addAndGet(1)
        r.up("m")
        assertEquals(listOf("e5", "n", "m"), fileOrder(dataDir))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Dropped 4 oldest entries"), warnings.single())
    }

    @Test
    @DisplayName("a speaker whose entry is the oldest and that comes up in the start grace keeps its entry and its port")
    fun theOldestEntryOfASpeakerThatComesUpInTheGraceIsKept() = runBlocking {
        val dataDir = tempDir()
        writeFileOf(dataDir, "lamp" to 3000, "e2" to 3001, "e3" to 3002, "e4" to 3003, "e5" to 3004)
        val now = AtomicLong(5_000_000)
        val r = make(dataDir, maxSaved = 3, clock = { now.get() })
        now.addAndGet(1000)
        assertEquals(3000, r.claim("lamp"))
        r.remember("lamp", 3000)
        assertEquals(listOf("e2", "e3", "e4", "e5", "lamp"), fileOrder(dataDir))

        now.addAndGet(PortRegistry.PRUNE_GRACE_MS)
        r.up("m")
        assertEquals(listOf("e5", "lamp", "m"), fileOrder(dataDir))
        assertEquals(mapOf("e5" to 3004, "lamp" to 3000, "m" to 3005), r.saved())
    }

    @Test
    @DisplayName("a flood of new entries during the start grace keeps the loaded entries and at most one more")
    fun aFloodDuringTheStartGraceStaysBounded() = runBlocking {
        val dataDir = tempDir()
        writeFileOf(dataDir, "e1" to 3000, "e2" to 3001, "e3" to 3002)
        val now = AtomicLong(5_000_000)
        val r = make(dataDir, maxSaved = 3, clock = { now.get() })
        for (i in 1..5) {
            val port = r.up("x$i")
            r.release(port)
            val keys = r.saved().keys.toList()
            assertEquals(listOf("e1", "e2", "e3", "x$i"), keys, "after x$i")
            assertTrue(keys.size <= 4)
        }
    }

    @Test
    @DisplayName("a pruned speaker that returns gets a port no running speaker holds")
    fun aReturningPrunedSpeakerGetsAPortNoRunningSpeakerHolds() = runBlocking {
        val r = make(maxSaved = 2)
        val lamp = r.up("lamp")
        assertEquals(3000, lamp)
        val x1 = r.up("x1")
        r.release(x1)
        val x2 = r.up("x2")
        r.release(x2)
        assertEquals(listOf("lamp", "x2"), r.saved().keys.toList(), "x1 was pruned")
        val back = r.claim("x1")
        assertEquals(3001, back, "x1 gets a free port")
        assertTrue(back != lamp)
        assertTrue(back != x2, "never the port of a remembered speaker")
    }

    @Test
    @DisplayName("a new speaker may take the port of a pruned one")
    fun aNewSpeakerMayTakeAPrunedPort() = runBlocking {
        val r = make(maxSaved = 2)
        val lamp = r.up("lamp")
        r.release(r.up("x1"))
        r.release(r.up("x2"))
        val x1 = r.claim("x1")
        r.remember("x1", x1)
        assertEquals(listOf("lamp", "x1"), r.saved().keys.toList(), "x2 was pruned for x1's return")
        val y = r.claim("y")
        assertEquals(3002, y, "the port x2 held before it was pruned")
        assertTrue(y != lamp && y != x1)
    }

    @Test
    @DisplayName("a touch writes a changed order at most once per interval and an unchanged order never")
    fun touchWritesTheOrderAtMostOncePerInterval() = runBlocking {
        val dataDir = tempDir()
        val now = AtomicLong(5_000_000)
        val r = make(dataDir, clock = { now.get() })
        r.up("a")
        r.up("b")
        val file = File(dataDir, "ports.json")
        val initial = file.readBytes()

        r.touch("a")
        assertEquals(listOf("b", "a"), r.saved().keys.toList(), "the order moved in memory")
        assertContentEquals(initial, file.readBytes(), "and not on disk yet")

        now.addAndGet(PortRegistry.RECENCY_WRITE_MS)
        r.touch("a")
        assertEquals(listOf("b", "a"), fileOrder(dataDir), "the interval passed: the order is written")
        val written = file.readBytes()

        r.touch("b")
        assertEquals(listOf("a", "b"), r.saved().keys.toList())
        assertContentEquals(written, file.readBytes(), "right after a write nothing is written")

        // an order that is on disk is never written again
        val other = tempDir()
        val r2 = make(other, clock = { now.get() })
        r2.up("a")
        r2.up("b")
        val bytes = File(other, "ports.json").readBytes()
        File(other, "ports.json").delete()
        now.addAndGet(10 * PortRegistry.RECENCY_WRITE_MS)
        r2.touch("b")
        r2.touch("unknown")
        assertTrue(!File(other, "ports.json").exists(), "touching the last entry, or an unknown one, writes nothing")
        File(other, "ports.json").writeBytes(bytes)
    }

    @Test
    @DisplayName("a registry built with Int.MAX_VALUE never drops an entry")
    fun anUnboundedRegistryNeverDrops() = runBlocking {
        val dataDir = tempDir()
        val entries = (1..300).map { "e$it" to 3000 + it }.toTypedArray()
        writeFileOf(dataDir, *entries)
        val now = AtomicLong(5_000_000)
        val r = make(dataDir, maxSaved = Int.MAX_VALUE, clock = { now.get() })
        now.addAndGet(PortRegistry.PRUNE_GRACE_MS * 2)
        for (i in 1..5) r.release(r.up("n$i"))
        assertEquals(305, r.saved().size)
        assertEquals(305, fileOrder(dataDir).size)
    }
}
