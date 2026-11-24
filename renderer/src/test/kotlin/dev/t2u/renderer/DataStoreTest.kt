package dev.t2u.renderer

// Tests for RendererDataStore (port of tube2upnp src/dataStore.js), GPL-3.0-only.

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CAST-03: each speaker keeps its screen and device IDs across restarts, in its own store. */
class DataStoreTest {
    @TempDir lateinit var tempRoot: File

    // under the per-test @TempDir, so the JUnit extension removes it (deleteOnExit does not remove a directory that has files in it)
    private fun tempDir(): File = Files.createTempDirectory(tempRoot.toPath(), "store-").toFile()

    private fun ids(device: String, screen: String): JsonObject = buildJsonObject {
        put("deviceId", device)
        put("screenId", screen)
    }

    @Test
    fun valuesSurviveARestart() = runBlocking {
        val base = tempDir()
        val first = RendererDataStore(base, "uuid:RINCON_ABC:1")
        first.set("app.pid", JsonPrimitive("p1"))
        first.set("mdxContext.cl", ids("d1", "s1"))
        first.set("dial.uuid", JsonPrimitive("u1"))

        val second = RendererDataStore(base, "uuid:RINCON_ABC:1")
        assertEquals(JsonPrimitive("p1"), second.get("app.pid"))
        assertEquals(ids("d1", "s1"), second.get("mdxContext.cl"))
        assertEquals(JsonPrimitive("u1"), second.get("dial.uuid"))
    }

    @Test
    fun writingMdxContextMLeavesClUnchanged() = runBlocking {
        val base = tempDir()
        val store = RendererDataStore(base, "lamp")
        store.set("mdxContext.cl", ids("cl-device", "cl-screen"))
        store.set("mdxContext.m", ids("m-device", "m-screen"))
        assertEquals(ids("cl-device", "cl-screen"), RendererDataStore(base, "lamp").get("mdxContext.cl"))
        assertEquals(ids("m-device", "m-screen"), RendererDataStore(base, "lamp").get("mdxContext.m"))
    }

    @Test
    fun differentSpeakersNeverShareAStore() = runBlocking {
        val base = tempDir()
        val a = RendererDataStore(base, "uuid:RINCON_ABC:1")
        val b = RendererDataStore(base, "fake-renderer")
        a.set("app.pid", JsonPrimitive("a-pid"))
        assertNull(b.get("app.pid"))
        b.set("app.pid", JsonPrimitive("b-pid"))
        assertEquals(JsonPrimitive("a-pid"), RendererDataStore(base, "uuid:RINCON_ABC:1").get("app.pid"))
        assertEquals(JsonPrimitive("b-pid"), RendererDataStore(base, "fake-renderer").get("app.pid"))

        val dirs = base.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        assertEquals(listOf("fake-renderer", storeDirName("uuid:RINCON_ABC:1")).sorted(), dirs)
        assertTrue(File(base, storeDirName("uuid:RINCON_ABC:1") + "/store.json").isFile)
    }

    @Test
    fun idsThatDifferOnlyInUnsafeCharactersGetDifferentStores() = runBlocking<Unit> {
        val base = tempDir()
        RendererDataStore(base, "speaker/1").set("dial.uuid", JsonPrimitive("u-a"))
        assertNull(RendererDataStore(base, "speaker?1").get("dial.uuid"), "the second id must not read the first one's identity")
        assertEquals(JsonPrimitive("u-a"), RendererDataStore(base, "speaker/1").get("dial.uuid"))
        val second = RendererDataStore(base, "speaker?1")
        second.set("dial.uuid", JsonPrimitive("u-b"))
        assertEquals(JsonPrimitive("u-a"), RendererDataStore(base, "speaker/1").get("dial.uuid"), "the second id must not overwrite the first one's identity")
        assertEquals(2, base.listFiles()!!.count { it.isDirectory }, "two ids, two directories")
        assertNotEquals(storeDirName("speaker/1"), storeDirName("speaker?1"))
    }

    @Test
    fun aSafeIdKeepsExactlyItsDirectoryName() {
        for (id in listOf("rincon_000e58a1b2c301400", "fake-renderer", "lamp", "uuid_RINCON_ABC_1", "a".repeat(200))) {
            assertEquals(id, storeDirName(id), "a safe id keeps its exact name")
            assertEquals(id, legacyStoreDirName(id))
        }
    }

    @Test
    fun aUdnNamedLikeADataFileGetsItsOwnStoreDir() {
        for (id in listOf("ports.json", "hidden.json", "manual.json", "names.json", "pinned.json", "speaker.json", "PORTS.JSON", "ports.json.tmp")) {
            val name = storeDirName(id)
            assertTrue(name.contains('~'), "a data file name gets the altered form: $name")
            assertNotEquals(id, name)
        }
        val sonos = legacyStoreDirName("uuid:RINCON_000E58A0B1C2_MR")
        assertEquals(sonos, storeDirName(sonos), "a sanitised Sonos id keeps its exact name")
    }

    @Test
    fun aStoreForADataFileUdnLeavesThatFileAlone() = runBlocking {
        val base = tempDir()
        val ports = File(base, "ports.json")
        ports.writeText("{\"uuid:RINCON_A\":8100}")
        RendererDataStore(base, "ports.json").set("dial.uuid", JsonPrimitive("u1"))
        assertTrue(ports.isFile, "ports.json is still a file")
        assertEquals("{\"uuid:RINCON_A\":8100}", ports.readText())
        assertEquals(JsonPrimitive("u1"), RendererDataStore(base, "ports.json").get("dial.uuid"))
    }

    @Test
    fun anAlteredIdNameIsStableAndCarriesAHash() {
        val name = storeDirName("speaker/1")
        assertTrue(Regex("^speaker_1~[0-9a-f]{16}$").matches(name), name)
        assertEquals(name, storeDirName("speaker/1"))
        assertEquals("speaker_1", legacyStoreDirName("speaker/1"))
        val long = storeDirName("a".repeat(150) + "/" + "b".repeat(150))
        assertTrue(long.length <= 117, "at most 100 + 1 + 16 characters: ${long.length}")
        assertTrue(Regex("~[0-9a-f]{16}$").containsMatchIn(long), long)
    }

    @Test
    fun hostileUdnStaysInsideTheBaseDirectory() = runBlocking {
        val base = tempDir()
        val store = RendererDataStore(base, "../../etc")
        store.set("app.pid", JsonPrimitive("x"))
        val dirs = base.listFiles()!!.filter { it.isDirectory }
        assertEquals(1, dirs.size)
        assertEquals(base.canonicalFile, dirs.single().canonicalFile.parentFile)
        assertNotEquals("..", dirs.single().name)
        assertEquals(JsonPrimitive("x"), RendererDataStore(base, "../../etc").get("app.pid"))
    }

    @Test
    fun udnThatSanitisesToADotIsRefused() {
        val base = tempDir()
        assertFailsWith<IllegalArgumentException> { RendererDataStore(base, "..") }
        assertFailsWith<IllegalArgumentException> { RendererDataStore(base, ".") }
        assertFailsWith<IllegalArgumentException> { RendererDataStore(base, "") }
    }

    @Test
    fun corruptFileReadsAsNullAndTheNextSetWritesAValidFile() = runBlocking {
        val base = tempDir()
        val dir = File(base, "lamp").also { it.mkdirs() }
        File(dir, "store.json").writeText("{nope")
        val errors = CopyOnWriteArrayList<String>()
        val store = RendererDataStore(base, "lamp") { msg, _ -> errors += msg }
        assertNull(store.get("app.pid"))
        assertNull(store.get("mdxContext.cl"))
        assertNull(store.get("dial.uuid"))
        assertEquals(1, errors.size, "reported once, not once per key")

        store.set("app.pid", JsonPrimitive("fresh"))
        val parsed: JsonElement = Json.parseToJsonElement(File(dir, "store.json").readText())
        assertTrue(parsed is JsonObject)
        assertEquals(JsonPrimitive("fresh"), RendererDataStore(base, "lamp").get("app.pid"))
    }

    @Test
    fun nonObjectFileIsTreatedAsCorrupt() = runBlocking {
        val base = tempDir()
        File(base, "lamp").mkdirs()
        File(base, "lamp/store.json").writeText("[1,2]")
        val errors = CopyOnWriteArrayList<String>()
        val store = RendererDataStore(base, "lamp") { msg, _ -> errors += msg }
        assertNull(store.get("app.pid"))
        assertEquals(1, errors.size)
    }

    @Test
    fun getOnANeverWrittenKeyIsNullAndSetNullRemovesTheKey() = runBlocking {
        val base = tempDir()
        val errors = CopyOnWriteArrayList<String>()
        val store = RendererDataStore(base, "lamp") { msg, _ -> errors += msg }
        assertNull(store.get("never"))
        assertTrue(errors.isEmpty(), "a missing file is not an error")

        store.set("app.pid", JsonPrimitive("p"))
        store.set("dial.uuid", JsonPrimitive("u"))
        store.set("app.pid", null)
        assertNull(store.get("app.pid"))
        val reread = RendererDataStore(base, "lamp")
        assertNull(reread.get("app.pid"))
        assertEquals(JsonPrimitive("u"), reread.get("dial.uuid"))
    }

    @Test
    fun noTempFileLeftBehind() = runBlocking {
        val base = tempDir()
        RendererDataStore(base, "lamp").set("app.pid", JsonPrimitive("p"))
        assertTrue(File(base, "lamp/store.json").isFile)
        assertFalse(File(base, "lamp/store.json.tmp").exists())
    }

    // ---------------------------------------------------------------------------------- the one-time move (D-09)

    private fun seedStore(base: File, name: String, uuid: String) {
        File(base, name).mkdirs()
        File(base, "$name/store.json").writeText("""{"dial.uuid":"$uuid"}""")
    }

    private fun seedPorts(base: File, vararg ids: String) {
        File(base, "ports.json").writeText(ids.withIndex().joinToString(prefix = "{", postfix = "}", separator = ",") { "\"${it.value}\":${3000 + it.index}" })
    }

    private suspend fun uuidOf(base: File, id: String): JsonElement? = RendererDataStore(base, id).get("dial.uuid")

    @Test
    fun theOldDirectoryOfAnAlteredIdIsMovedOnceAndKeepsItsIdentity() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "speaker/1")
        File(base, "speaker_1").mkdirs()
        File(base, "speaker_1/store.json").writeText("""{"dial.uuid":"u-old","mdxContext.cl":{"screenId":"s1"},"mdxContext.m":{"screenId":"s2"}}""")
        val warns = CopyOnWriteArrayList<String>()
        assertEquals(1, moveLegacyStores(base, warn = { warns += it }))
        val store = RendererDataStore(base, "speaker/1")
        assertEquals(JsonPrimitive("u-old"), store.get("dial.uuid"))
        assertEquals(JsonPrimitive("s1"), (store.get("mdxContext.cl") as JsonObject)["screenId"])
        assertEquals(JsonPrimitive("s2"), (store.get("mdxContext.m") as JsonObject)["screenId"])
        assertFalse(File(base, "speaker_1").exists())
        assertTrue(File(base, LEGACY_STORES_MARKER).isFile)
        assertEquals(0, moveLegacyStores(base, warn = { warns += it }), "once")
        assertTrue(warns.isEmpty(), warns.toString())
    }

    @Test
    fun theOldDirectoryIsNotMovedWhenASafeIdInTheRecordOwnsIt() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "speaker_1", "speaker/1")
        seedStore(base, "speaker_1", "u-safe")
        assertEquals(0, moveLegacyStores(base))
        assertEquals(JsonPrimitive("u-safe"), uuidOf(base, "speaker_1"))
        assertNull(uuidOf(base, "speaker/1"))
    }

    @Test
    fun aSafeSpeakerPortsJsonNoLongerRemembersKeepsItsDirectory() = runBlocking<Unit> {
        // (a) the safe speaker is forgotten and the altered id is first seen after the update
        val a = tempDir()
        seedPorts(a)
        seedStore(a, "speaker_1", "u-safe")
        assertEquals(0, moveLegacyStores(a))
        assertNull(uuidOf(a, "speaker/1"))
        assertEquals(JsonPrimitive("u-safe"), uuidOf(a, "speaker_1"))

        // (b) the first pass saw the safe speaker; later the real PortRegistry prunes it, and a second pass still moves nothing
        val b = tempDir()
        seedPorts(b, "speaker_1")
        seedStore(b, "speaker_1", "u-safe")
        assertEquals(0, moveLegacyStores(b))
        val clock = AtomicLong(1_000)
        val ports = PortRegistry(b, 3000, 2, clock::get).load()
        val p1 = ports.claim("speaker_1")
        ports.remember("speaker_1", p1)
        ports.release(p1)
        clock.addAndGet(PortRegistry.PRUNE_GRACE_MS + 1)
        ports.remember("x1", ports.claim("x1"))
        ports.remember("speaker/1", ports.claim("speaker/1"))
        val kept = (Json.parseToJsonElement(File(b, "ports.json").readText()) as JsonObject).keys
        assertEquals(setOf("x1", "speaker/1"), kept, "speaker_1 was pruned from ports.json")
        assertEquals(0, moveLegacyStores(b), "the marker stops a second pass")
        assertNull(uuidOf(b, "speaker/1"))
        assertEquals(JsonPrimitive("u-safe"), uuidOf(b, "speaker_1"))
    }

    @Test
    fun anOldRecordOfMoreThanMaxSavedKeysStillMovesTheStore() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, *((1..299).map { "flood-%03d".format(it) } + "speaker/1").toTypedArray())
        seedStore(base, "speaker_1", "u-old")
        assertEquals(1, moveLegacyStores(base))
        assertEquals(JsonPrimitive("u-old"), uuidOf(base, "speaker/1"))
    }

    @Test
    fun theSavedPortsBoundIsLiftedUntilTheMarkerExists() {
        val base = tempDir()
        assertEquals(Int.MAX_VALUE, savedPortsBound(base))
        File(base, LEGACY_STORES_MARKER).mkdirs()
        assertEquals(Int.MAX_VALUE, savedPortsBound(base), "a directory is not the marker")
        File(base, LEGACY_STORES_MARKER).delete()
        assertEquals(0, moveLegacyStores(base))
        assertEquals(PortRegistry.MAX_SAVED, savedPortsBound(base))
    }

    @Test
    fun twoCollidingAlteredIdsNeitherTakesTheOldDirectory() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "speaker/1", "speaker?1")
        seedStore(base, "speaker_1", "u-old")
        assertEquals(0, moveLegacyStores(base))
        assertTrue(File(base, "speaker_1").isDirectory)
        assertNull(uuidOf(base, "speaker/1"))
        assertNull(uuidOf(base, "speaker?1"))
        assertNotEquals(storeDirName("speaker/1"), storeDirName("speaker?1"))
    }

    @Test
    fun aFailedMoveDoesNotThrowAndStartsFresh() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "speaker/1")
        seedStore(base, "speaker_1", "u-old")
        val warns = CopyOnWriteArrayList<String>()
        assertEquals(0, moveLegacyStores(base, moveDir = { _, _ -> throw IOException("no rename") }, warn = { warns += it }))
        assertEquals(1, warns.size, warns.toString())
        assertTrue(File(base, "speaker_1").isDirectory)
        assertNull(uuidOf(base, "speaker/1"))
    }

    @Test
    fun aMarkerThatCannotBeWrittenMovesNothing() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "speaker/1")
        seedStore(base, "speaker_1", "u-old")
        File(base, LEGACY_STORES_MARKER).mkdirs()
        File(base, "$LEGACY_STORES_MARKER/keep").writeText("x")
        val warns = CopyOnWriteArrayList<String>()
        assertEquals(0, moveLegacyStores(base, warn = { warns += it }))
        assertEquals(1, warns.size, warns.toString())
        assertTrue(File(base, "speaker_1").isDirectory)
        assertEquals(Int.MAX_VALUE, savedPortsBound(base))
    }

    @Test
    fun aSafeIdNeverMovesAnything() = runBlocking<Unit> {
        val base = tempDir()
        seedPorts(base, "lamp")
        seedStore(base, "lamp", "u-lamp")
        seedStore(base, "other", "u-other")
        assertEquals(0, moveLegacyStores(base))
        assertEquals(JsonPrimitive("u-lamp"), uuidOf(base, "lamp"))
        assertEquals(JsonPrimitive("u-other"), uuidOf(base, "other"))
    }

    @Test
    fun aMissingOrUnreadableRecordMovesNothing() = runBlocking<Unit> {
        val missing = tempDir()
        seedStore(missing, "speaker_1", "u-old")
        val warnsMissing = CopyOnWriteArrayList<String>()
        assertEquals(0, moveLegacyStores(missing, warn = { warnsMissing += it }))
        assertTrue(File(missing, LEGACY_STORES_MARKER).isFile)
        assertTrue(warnsMissing.isEmpty())
        assertTrue(File(missing, "speaker_1").isDirectory)

        val broken = tempDir()
        File(broken, "ports.json").writeText("{nope")
        seedStore(broken, "speaker_1", "u-old")
        val warns = CopyOnWriteArrayList<String>()
        assertEquals(0, moveLegacyStores(broken, warn = { warns += it }))
        assertEquals(1, warns.size, warns.toString())
        assertTrue(File(broken, LEGACY_STORES_MARKER).isFile)
        assertTrue(File(broken, "speaker_1").isDirectory)
    }

    @Test
    fun aRecordThatCannotBeReadMovesNothingAndDoesNotThrow() = runBlocking<Unit> {
        // a directory stands at ports.json: reading it throws
        val dir = tempDir()
        File(dir, "ports.json").mkdirs()
        seedStore(dir, "speaker_1", "u-old")
        val warns = CopyOnWriteArrayList<String>()
        assertEquals(0, moveLegacyStores(dir, warn = { warns += it }))
        assertEquals(1, warns.size, warns.toString())
        assertTrue(File(dir, LEGACY_STORES_MARKER).isFile)
        assertTrue(File(dir, "speaker_1").isDirectory)

        for (failure in listOf<() -> Nothing>({ throw IOException("disk") }, { throw IllegalStateException("odd") })) {
            val base = tempDir()
            seedPorts(base, "speaker/1")
            seedStore(base, "speaker_1", "u-old")
            val w = CopyOnWriteArrayList<String>()
            assertEquals(0, moveLegacyStores(base, readRecord = { failure() }, warn = { w += it }))
            assertEquals(1, w.size, w.toString())
            assertTrue(File(base, LEGACY_STORES_MARKER).isFile)
            assertTrue(File(base, "speaker_1").isDirectory)
        }
    }
}
