package dev.t2u.lounge

// Kotlin original: message-level logging for the lounge port.
//
// The lounge protocol is undocumented and can change without notice, so every message in both directions and every
// raw chunk is reported to the EventSink, always redacted: lounge tokens, SID, gsessionid, screen and device
// ids, pairing codes and a sender's user name or avatar never reach a log. The key list follows tools/lounge-fixtures.py,
// which scrubbed the recorded fixtures with the same rules.

import dev.t2u.upnp.EventSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Redact {
    /** Keys whose string values are secrets (JSON keys, form fields and query parameters). */
    private val SECRET_KEYS = setOf(
        "loungeToken", "loungeIdToken", "SID", "gsessionid", "screenId", "screen_id", "screen_ids",
        "deviceId", "device_id", "id", "pairingCode", "pairing_code", "zx",
        "user", "userAvatarUri", "obfuscatedGaiaId", "ownerObfuscatedGaiaId", "ctt",
        // opaque per-session values found in recorded traffic
        "cachedSessionState", "localChannelEncryptionKey", "ms", "castUserId", "advertising_id",
        "clickTrackingParams", "disconnectBehavior",
    )

    /** Keys whose long base64url-looking values are legitimate YouTube ids and opaque parameters. */
    private val EXEMPT_KEYS = setOf("listId", "videoIds", "videoId", "params")

    /** A base64url run this long is treated as a credential wherever it appears. */
    private val LONG_RUN = Regex("[A-Za-z0-9_-]{40,}")

    private fun marker(length: Int) = "<redacted len $length>"

    /** Redacts [e]; JSON held inside strings (loungeStatus devices, connectionEventDetails, device) is parsed and redacted too. */
    fun json(e: JsonElement): JsonElement = walk(e, null, false)

    /** Redacts free text: JSON is walked as [json] does, anything else gets a key/value and long-run scrub. */
    fun text(s: String): String {
        val trimmed = s.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return json(Json.parseToJsonElement(trimmed)).toString()
            } catch (_: Exception) {
                // fall through to the text scrub
            }
        }
        var out = s
        for (key in SECRET_KEYS) {
            val k = Regex.escape(key)
            out = Regex("(\"$k\"\\s*:\\s*\")([^\"]*)(\")").replace(out) { m -> m.groupValues[1] + marker(m.groupValues[2].length) + m.groupValues[3] }
            out = Regex("((?:^|[?&])$k=)([^&\\s]*)").replace(out) { m -> m.groupValues[1] + marker(m.groupValues[2].length) }
        }
        return LONG_RUN.replace(out) { marker(it.value.length) }
    }

    private fun walk(e: JsonElement, key: String?, force: Boolean): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.associate { (k, v) -> k to walk(v, k, force || k in SECRET_KEYS) })
        is JsonArray -> {
            val secretSlot = e.size >= 2 && (e[0] as? JsonPrimitive)?.takeIf { it.isString }?.content in setOf("c", "S")
            JsonArray(
                e.mapIndexed { i, v ->
                    if (secretSlot && i == 1) walk(v, key, true) else walk(v, key, force)
                },
            )
        }
        is JsonPrimitive -> redactPrimitive(e, key, force)
        JsonNull -> e
    }

    private fun redactPrimitive(p: JsonPrimitive, key: String?, force: Boolean): JsonPrimitive {
        if (!p.isString || p.content.isEmpty()) return p
        if (force) return JsonPrimitive(marker(p.content.length))
        if (key in EXEMPT_KEYS) return p
        val t = p.content.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return JsonPrimitive(walk(Json.parseToJsonElement(t), key, false).toString())
            } catch (_: Exception) {
                // not JSON after all: scrub it as text
            }
        }
        return JsonPrimitive(LONG_RUN.replace(p.content) { marker(it.value.length) })
    }
}

/**
 * Reports lounge traffic to an [EventSink] for one client [theme]. Kinds: `lounge` (one per message, fields theme, dir
 * rx|tx, aid, name, payload), `lounge-raw` (redacted chunk payloads in pieces of at most 1800 characters: theme, seq,
 * part, parts, data), `lounge-http`, `lounge-session` and `lounge-error`. The session registers its own secrets with
 * [addSecret] as a last line of defence: any literal occurrence is replaced before an event leaves.
 */
class LoungeLog(private val sink: EventSink, private val theme: String) {
    private val secrets = ConcurrentHashMap.newKeySet<String>()
    private val rawSeq = AtomicInteger(0)

    fun addSecret(value: String?) {
        if (value != null && value.length >= MIN_SECRET_LENGTH) secrets.add(value)
    }

    private fun scrub(s: String): String {
        var out = s
        for (secret in secrets) if (secret in out) out = out.replace(secret, "<redacted len ${secret.length}>")
        return out
    }

    private fun emit(kind: String, fields: Map<String, Any?>) {
        try {
            sink.event(kind, fields)
        } catch (_: Exception) {
            // logging must never break the session
        }
    }

    private fun clip(s: String): String = if (s.length <= MAX_PAYLOAD_CHARS) s else s.take(MAX_PAYLOAD_CHARS) + "...[+${s.length - MAX_PAYLOAD_CHARS} chars]"

    fun rx(messages: List<IncomingMessage>) {
        for (m in messages) {
            val payload = when {
                m.name == "c" || m.name == "S" -> "<redacted>"
                m.payload == null -> ""
                else -> clip(scrub(Redact.json(m.payload).toString()))
            }
            emit("lounge", mapOf("theme" to theme, "dir" to "rx", "aid" to m.aid, "name" to m.name, "payload" to payload))
        }
    }

    fun tx(messages: List<OutgoingMessage>) {
        for (m in messages) {
            val body = JsonObject(
                m.payload.entries.associate { (k, v) ->
                    k to when (v) {
                        null -> JsonNull
                        is Boolean -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                },
            )
            emit("lounge", mapOf("theme" to theme, "dir" to "tx", "aid" to m.aid, "name" to m.name, "payload" to clip(scrub(Redact.json(body).toString()))))
        }
    }

    /** One decoded RPC or handshake chunk, redacted and split into pieces. */
    fun raw(payload: String) {
        val redacted = scrub(Redact.text(payload))
        val seq = rawSeq.incrementAndGet()
        val pieces = if (redacted.isEmpty()) listOf("") else redacted.chunked(RAW_PIECE_CHARS)
        pieces.forEachIndexed { i, piece ->
            emit("lounge-raw", mapOf("theme" to theme, "seq" to seq, "part" to i + 1, "parts" to pieces.size, "data" to piece))
        }
    }

    /** [path] has no query string: the query holds the lounge token, SID and gsessionid. */
    fun http(method: String, path: String, status: Int, ms: Long) {
        emit("lounge-http", mapOf("theme" to theme, "method" to method, "path" to path, "status" to status, "ms" to ms))
    }

    fun session(what: String, vararg extra: Pair<String, Any?>) {
        emit("lounge-session", mapOf("theme" to theme, "what" to what) + extra.toMap())
    }

    fun problem(where: String, message: String) {
        emit("lounge-error", mapOf("theme" to theme, "where" to where, "message" to clip(scrub(Redact.text(message)))))
    }

    fun error(where: String, t: Throwable) {
        emit(
            "lounge-error",
            mapOf("theme" to theme, "where" to where, "type" to (t::class.simpleName ?: "Throwable"), "message" to clip(scrub(Redact.text(t.message ?: "")))),
        )
    }

    private companion object {
        const val RAW_PIECE_CHARS = 1800
        const val MAX_PAYLOAD_CHARS = 8000
        const val MIN_SECRET_LENGTH = 6
    }
}
