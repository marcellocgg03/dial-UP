package dev.t2u.lounge

// Replaces the incoming-message parsing of yt-cast-receiver 2.1.0 lib/app/Message.js (parseIncoming) and the
// line reader of lib/app/RPCConnection.js (MIT, Copyright (c) Patrick Kan). CAST-08, RESEARCH Pattern 1 and Pitfall 1:
// the lounge sends `<len>\n<payload>` chunks where len counts UTF-16 code units of the UTF-8-decoded text. The Node
// version ran a regex over single lines, which breaks on accented titles, emoji and chunks split across reads. This
// decoder buffers until a chunk is complete and never reports message text (it can hold credentials).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One message of a lounge chunk: `[aid, [name, args...]]`. One arg becomes [payload], several a JsonArray, none null. */
data class IncomingMessage(val aid: Int, val name: String, val payload: JsonElement?)

/**
 * Feed it text as it arrives; it calls [onPayload] with each complete chunk payload, in order.
 * A length line that is not a number is dropped and reported; a length above [maxChunkChars] resets the buffer
 * and is reported (T-02-32). [onGarbage] receives a short description only, never message text.
 */
class ChunkDecoder(
    private val onPayload: (String) -> Unit,
    private val onGarbage: (String) -> Unit = {},
    private val maxChunkChars: Int = 1_048_576,
) {
    private val buffer = StringBuilder()
    private var expected = -1

    fun feed(text: String) {
        buffer.append(text)
        while (true) {
            if (expected < 0) {
                var skip = 0
                while (skip < buffer.length && buffer[skip].isWhitespace()) skip++
                if (skip > 0) buffer.delete(0, skip)
                val nl = buffer.indexOf("\n")
                if (nl < 0) {
                    if (buffer.length > MAX_LENGTH_LINE) {
                        onGarbage("length line longer than $MAX_LENGTH_LINE characters")
                        buffer.setLength(0)
                    }
                    return
                }
                val line = buffer.substring(0, nl).trim()
                val len = line.toLongOrNull()
                if (len == null || len < 0) {
                    buffer.delete(0, nl + 1)
                    onGarbage("non-numeric length line (${line.length} characters)")
                    continue
                }
                if (len > maxChunkChars) {
                    buffer.setLength(0)
                    onGarbage("chunk length $len exceeds $maxChunkChars")
                    return
                }
                buffer.delete(0, nl + 1)
                expected = len.toInt()
            }
            if (buffer.length < expected) return
            val payload = buffer.substring(0, expected)
            buffer.delete(0, expected)
            expected = -1
            onPayload(payload)
        }
    }

    /** Parses one chunk payload (a JSON array of `[aid, [name, args...]]`); malformed entries are skipped and reported. */
    fun parseMessages(payload: String): List<IncomingMessage> {
        val text = payload.trim()
        if (text.isEmpty()) return emptyList()
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            onGarbage("chunk payload is not JSON (${text.length} characters)")
            return emptyList()
        }
        if (root !is JsonArray) {
            onGarbage("chunk payload is not an array")
            return emptyList()
        }
        val out = ArrayList<IncomingMessage>(root.size)
        for (entry in root) {
            val message = parseEntry(entry)
            if (message == null) onGarbage("skipped a malformed message entry") else out.add(message)
        }
        return out
    }

    private fun parseEntry(entry: JsonElement): IncomingMessage? {
        if (entry !is JsonArray || entry.size != 2) return null
        val idPrimitive = entry[0] as? JsonPrimitive ?: return null
        if (idPrimitive.isString) return null
        val aid = idPrimitive.intOrNull ?: return null
        val body = entry[1] as? JsonArray ?: return null
        val namePrimitive = body.firstOrNull() as? JsonPrimitive ?: return null
        if (!namePrimitive.isString) return null
        val args = body.drop(1)
        val payload: JsonElement? = when (args.size) {
            0 -> null
            1 -> args[0]
            else -> JsonArray(args)
        }
        return IncomingMessage(aid, namePrimitive.content, payload)
    }

    private companion object {
        const val MAX_LENGTH_LINE = 24
    }
}

/**
 * Turns byte reads into text and keeps an incomplete trailing multi-byte sequence for the next read, so a
 * character split across two network reads decodes correctly. Malformed bytes become U+FFFD.
 */
class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val input = ByteBuffer.allocate(carry.size + length)
        input.put(carry).put(bytes, offset, length).flip()
        val out = CharBuffer.allocate((input.remaining() * decoder.maxCharsPerByte()).toInt() + 4)
        decoder.decode(input, out, false)
        carry = ByteArray(input.remaining())
        input.get(carry)
        out.flip()
        return out.toString()
    }

    /** End of stream: an unfinished sequence at the end becomes U+FFFD. */
    fun finish(): String {
        val input = ByteBuffer.wrap(carry)
        carry = ByteArray(0)
        val out = CharBuffer.allocate(carry.size + input.remaining() * 2 + 4)
        decoder.decode(input, out, true)
        decoder.flush(out)
        decoder.reset()
        out.flip()
        return out.toString()
    }
}
