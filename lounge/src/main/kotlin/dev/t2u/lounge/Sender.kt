package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/Sender.js (MIT, Copyright (c) Patrick Kan).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The signed-in user behind a sender, when the sender reports one. */
data class SenderUser(val name: String, val thumbnail: String?)

/** Thrown by [Sender.parse] for a payload that is not a valid sender; callers log it and carry on. */
class SenderParseException(message: String) : Exception(message)

/** A phone or browser connected to this receiver (the payload of a `remoteConnected` message). */
data class Sender(
    val id: String,
    val name: String,
    val capabilities: List<String>,
    val app: String?,
    val client: Client?,
    val user: SenderUser?,
    val device: JsonObject,
    val obfuscatedGaiaId: String?,
    val ownerObfuscatedGaiaId: String?,
) {
    /** The sender can show the autoplay toggle (capability `atp`). */
    fun supportsAutoplay(): Boolean = "atp" in capabilities

    /** The sender app is `youtube.m-desktop` or `youtube-desktop`, which have a mute button. */
    fun supportsMute(): Boolean = app?.endsWith("-desktop") ?: false

    companion object {
        /**
         * Parses a device entry of a `remoteConnected` or `remoteDisconnected` message.
         * Requires an id, a name or clientName, and a theme that is a known [Client] theme (`cl` or `m`).
         * A nested `device` that is not a JSON object string becomes an empty object.
         *
         * @throws SenderParseException when the entry is not valid.
         */
        fun parse(data: JsonObject): Sender {
            val id = data.text("id")
            val name = data.text("name") ?: data.text("clientName")
            val client = Client.fromTheme(data.text("theme"))
            if (id == null || name == null || client == null) {
                throw SenderParseException("Invalid sender data: id, name or clientName and a known theme are required")
            }
            val userName = data.text("user")
            return Sender(
                id = id,
                name = name,
                capabilities = data.text("capabilities")?.split(',') ?: emptyList(),
                app = data.text("app"),
                client = client,
                user = userName?.let { SenderUser(it, data.text("userAvatarUri")) },
                device = parseDevice(data["device"]),
                obfuscatedGaiaId = data.text("obfuscatedGaiaId"),
                ownerObfuscatedGaiaId = data.text("ownerObfuscatedGaiaId"),
            )
        }

        /** A non-empty string value, or null (JavaScript's `data.x || fallback`). */
        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

        private fun parseDevice(value: JsonElement?): JsonObject {
            val text = (value as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return JsonObject(emptyMap())
            return try {
                Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
        }
    }
}
