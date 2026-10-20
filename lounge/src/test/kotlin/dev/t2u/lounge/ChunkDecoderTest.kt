package dev.t2u.lounge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ChunkDecoderTest {
    private class Decoded(val messages: List<IncomingMessage>, val garbage: List<String>, val payloads: List<String>)

    private fun decode(text: String, maxChunkChars: Int = 1_048_576): Decoded {
        val messages = ArrayList<IncomingMessage>()
        val garbage = ArrayList<String>()
        val payloads = ArrayList<String>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(
            onPayload = { payloads.add(it); messages.addAll(decoder.parseMessages(it)) },
            onGarbage = { garbage.add(it) },
            maxChunkChars = maxChunkChars,
        )
        decoder.feed(text)
        return Decoded(messages, garbage, payloads)
    }

    private fun fixture(name: String): String =
        ChunkDecoderTest::class.java.getResourceAsStream("/fixtures/$name")?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: fail("fixture $name is not on the classpath")

    private fun frame(payload: String) = "${payload.length}\n$payload"

    private fun manifestFiles(): List<String> =
        Json.parseToJsonElement(fixture("manifest.json")).jsonObject["files"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun recordedHandshakeAndRpcDecodeToTheManifestNamesWithoutGarbage() {
        val expected = Json.parseToJsonElement(fixture("manifest.json")).jsonObject["incoming_names"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        val names = HashSet<String>()
        for (file in manifestFiles().filter { it.startsWith("handshake") || (it.startsWith("rpc-") && it.endsWith(".txt")) }) {
            val d = decode(fixture(file))
            assertTrue(d.garbage.isEmpty(), "$file reported garbage: ${d.garbage}")
            d.messages.forEach { names.add(it.name) }
        }
        assertEquals(expected, names)
    }

    @Test
    fun everyRecordedRpcFixtureDecodesAndCarriesSetPlaylist() {
        val rpcFiles = manifestFiles().filter { it.startsWith("rpc-") && it.endsWith(".txt") }
        assertTrue(rpcFiles.isNotEmpty(), "manifest lists no rpc-*.txt file")
        var withSetPlaylist = 0
        for (file in rpcFiles) {
            val d = decode(fixture(file))
            assertTrue(d.garbage.isEmpty(), "$file reported garbage: ${d.garbage}")
            assertTrue(d.messages.isNotEmpty(), "$file decoded to zero messages")
            val keepAliveOnly = d.messages.all { it.name == "noop" || it.name == "getDiscoveryDeviceId" }
            val has = d.messages.any { it.name == "setPlaylist" }
            // a capture that never cast (rpc-m.txt) holds only keepalives; every capture of a cast must carry setPlaylist
            assertTrue(has || keepAliveOnly, "$file has no setPlaylist message")
            if (has) withSetPlaylist++
        }
        assertTrue(withSetPlaylist >= 1, "no recorded rpc fixture carries setPlaylist")
    }

    @Test
    fun aChunkFedInTwoHalvesDecodesLikeTheWholeChunk() {
        val text = fixture("rpc-cl.txt")
        val whole = decode(text).messages
        val parts = ArrayList<IncomingMessage>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(onPayload = { parts.addAll(decoder.parseMessages(it)) })
        val a = text.length / 3
        val b = 2 * text.length / 3
        decoder.feed(text.substring(0, a))
        decoder.feed(text.substring(a, b))
        decoder.feed(text.substring(b))
        assertEquals(whole, parts)
        assertTrue(whole.isNotEmpty())
    }

    @Test
    fun aMultiByteCharacterSplitAcrossTwoByteReadsDecodesLikeTheWholeText() {
        val payload = "[[1,[\"setPlaylist\",{\"title\":\"Pitypang ő ű 😀\"}]]]\n"
        val bytes = frame(payload).toByteArray(Charsets.UTF_8)
        val whole = decode(frame(payload)).messages
        // cut inside every multi-byte sequence in turn
        for (cut in 1 until bytes.size) {
            val utf8 = Utf8StreamDecoder()
            val messages = ArrayList<IncomingMessage>()
            lateinit var decoder: ChunkDecoder
            decoder = ChunkDecoder(onPayload = { messages.addAll(decoder.parseMessages(it)) })
            decoder.feed(utf8.decode(bytes, 0, cut))
            decoder.feed(utf8.decode(bytes, cut, bytes.size - cut))
            decoder.feed(utf8.finish())
            assertEquals(whole, messages, "cut at byte $cut")
        }
    }

    @Test
    fun accentedTitlesAndEmojiAreCountedInUtf16CodeUnits() {
        val payload = "[[7,[\"setPlaylist\",{\"title\":\"Pitypang ő ű 😀\"}]]]\n"
        val d = decode(frame(payload))
        assertTrue(d.garbage.isEmpty())
        assertEquals(1, d.messages.size)
        val title = d.messages[0].payload!!.jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("Pitypang ő ű 😀", title)
        // the emoji is one code point but two UTF-16 units: the frame is two longer than a code point count
        assertEquals(payload.codePointCount(0, payload.length) + 1, payload.length)
    }

    @Test
    fun emptyArrayYieldsNothingAndNoError() {
        val d = decode(frame("[]\n"))
        assertTrue(d.messages.isEmpty())
        assertTrue(d.garbage.isEmpty())
    }

    @Test
    fun noopIsPassedOnWithNullPayload() {
        val d = decode(frame("[[5,[\"noop\"]]]\n"))
        assertEquals(listOf(IncomingMessage(5, "noop", null)), d.messages)
        assertNull(d.messages[0].payload)
    }

    @Test
    fun oneArgumentBecomesThePayloadSeveralBecomeAnArray() {
        val d = decode(frame("[[0,[\"c\",\"sid\",\"\",8]],[1,[\"S\",\"gs\"]]]\n"))
        assertEquals(JsonArray(listOf(JsonPrimitive("sid"), JsonPrimitive(""), JsonPrimitive(8))), d.messages[0].payload)
        assertEquals(JsonPrimitive("gs"), d.messages[1].payload)
    }

    @Test
    fun aNonNumericLengthLineIsReportedAndSkippedAndDecodingContinues() {
        val good = frame("[[9,[\"noop\"]]]\n")
        val d = decode("abc\n$good")
        assertEquals(1, d.garbage.size)
        assertEquals(listOf("noop"), d.messages.map { it.name })
    }

    @Test
    fun anOversizedLengthIsReportedAndTheBufferReset() {
        val messages = ArrayList<IncomingMessage>()
        val garbage = ArrayList<String>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(onPayload = { messages.addAll(decoder.parseMessages(it)) }, onGarbage = { garbage.add(it) })
        decoder.feed("2000000\n[[1,[\"noop\"]]]")
        assertEquals(1, garbage.size)
        decoder.feed(frame("[[2,[\"noop\"]]]\n"))
        assertEquals(listOf(2), messages.map { it.aid })
    }

    @Test
    fun aMalformedEntryIsSkippedWhileTheValidEntriesOfTheChunkAreReturned() {
        val d = decode(frame("[[5,[\"noop\"]],[6,\"bad\"],[7,[\"pause\"]]]\n"))
        assertEquals(listOf("noop", "pause"), d.messages.map { it.name })
        assertEquals(1, d.garbage.size)
    }

    @Test
    fun aPayloadThatIsNotJsonIsReportedAndDecodingContinues() {
        val bad = "not json at all\n"
        val d = decode(frame(bad) + frame("[[3,[\"noop\"]]]\n"))
        assertEquals(1, d.garbage.size)
        assertEquals(listOf("noop"), d.messages.map { it.name })
    }

    @Test
    fun garbageReportsNeverContainMessageText() {
        val secret = "SECRETsid1X000000000000"
        val d = decode(frame("[[6,\"$secret\"]]\n") + "$secret\n")
        assertTrue(d.garbage.isNotEmpty())
        assertFalse(d.garbage.any { secret in it })
    }
}
