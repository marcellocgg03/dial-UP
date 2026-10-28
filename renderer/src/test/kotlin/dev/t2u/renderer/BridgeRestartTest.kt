package dev.t2u.renderer

// SVC-08 (plan 05-06): a bridge started again on the same data directory, as after a process kill, keeps each speaker's
// DIAL port, DIAL uuid, lounge screen id and learned format. The "kill" is a stop of bridge A; the test asserts that stop
// changes neither ports.json nor store.json, so nothing a real kill would skip is what makes the restart work.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.StoreKeys
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class BridgeRestartTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    private suspend fun stored(key: String): String? {
        val v = RendererDataStore(h.dataDir, HARNESS_RENDERER_UDN).get(key)
        // dial.uuid is a string, mdxContext.<theme> an object: compare either by its JSON text
        return (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: (v as? kotlinx.serialization.json.JsonObject)?.toString()
    }

    private val portsFile: File get() = File(h.dataDir, "ports.json")
    private val storeFile: File get() = File(File(h.dataDir, HARNESS_RENDERER_UDN), "store.json")

    @Test
    @DisplayName("a bridge started again on the same data dir after a kill keeps the DIAL port, the DIAL uuid, the screen id and the learned format, and ignores half-written temp files")
    fun restartKeepsPortsIdsAndLearnedFormat() = h.run<Unit> {
        // ---- bridge A: first run
        val a = h.newBridge(h.testConfig(outputFormat = "auto"))
        a.start()
        val first = h.launch(a)
        h.connectSender()
        var waited = 0
        while (stored(StoreKeys.mdxContext("cl")) == null && waited++ < 250) delay(20)
        val screenId = assertNotNull(stored(StoreKeys.mdxContext("cl")))
        val dialUuid = assertNotNull(stored(StoreKeys.DIAL_UUID))
        assertEquals(first.uuid, dialUuid, "the SSDP uuid is the stored dial.uuid")
        RendererDataStore(h.dataDir, HARNESS_RENDERER_UDN).set(LEARNED_FORMAT_KEY, JsonPrimitive("mp3"))

        val portsBefore = portsFile.readBytes()
        val storeBefore = storeFile.readBytes()
        a.stop()
        assertContentEquals(portsBefore, portsFile.readBytes(), "stop must not rewrite ports.json")
        assertContentEquals(storeBefore, storeFile.readBytes(), "stop must not rewrite store.json")

        // ---- what a kill in the middle of a write leaves behind
        File(h.dataDir, "ports.json.tmp").writeText("{\"fake-rend")
        File(storeFile.parentFile, "store.json.tmp").writeText("{\"dial.uu")

        // ---- bridge B: same data dir, a different base port in its config
        val b = h.newBridge(h.testConfig(outputFormat = "auto"))
        b.start()
        val second = h.launch(b)
        assertEquals(first.port, second.port, "the DIAL port is kept")
        assertEquals(first.uuid, second.uuid, "the DIAL uuid is kept")
        assertEquals(dialUuid, stored(StoreKeys.DIAL_UUID))
        assertEquals(screenId, stored(StoreKeys.mdxContext("cl")), "the screen id is kept")

        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        h.connectSender(id = "dev-2")
        h.rpc("cl", "setPlaylist", h.playlist(VID))
        h.awaitUntil("SetAVTransportURI on the speaker") { h.setAvTransportUris().isNotEmpty() }
        delay(1000) // room for any further format to be tried
        val uris = h.setAvTransportUris()
        assertEquals(1, uris.size, "exactly one SetAVTransportURI: $uris")
        assertTrue(URI(uris.single()).path.endsWith(".mp3"), "the learned format came first: $uris")
    }
}
