package dev.t2u.lounge

// Kotlin original, protocol reference: youtubei.js Session.generate_session_locally (MIT).

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

/**
 * A locally generated InnerTube visitorData value: a protobuf message with field 1 an
 * 11-character id and field 5 the Unix seconds, base64url encoded and then URL-encoded (the padding becomes %3D).
 * It is random, not linked to an account and not persisted; the server's own value replaces it after the first answer.
 */
object VisitorData {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val ID_LENGTH = 11
    private val secureRandom = SecureRandom()

    fun generate(random: Random = secureRandom, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val id = buildString { repeat(ID_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        return encode(id, nowSeconds)
    }

    /** Protobuf bytes (tag 0x0a, length, id, tag 0x28, varint seconds), base64url, then URL-encoded. */
    fun encode(id: String, seconds: Long): String {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x0a)
        out.write(idBytes.size)
        out.write(idBytes)
        out.write(0x28)
        var v = seconds
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        val b64 = Base64.getUrlEncoder().encodeToString(out.toByteArray())
        return URLEncoder.encode(b64, "UTF-8")
    }
}
