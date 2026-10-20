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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CAST-03: each speaker keeps its screen and device IDs across restarts, in its own store. */
class DataStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("store-").toFile().also { it.deleteOnExit() }

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
        assertEquals(listOf("fake-renderer", "uuid_RINCON_ABC_1"), dirs)
        assertTrue(File(base, "uuid_RINCON_ABC_1/store.json").isFile)
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
}
