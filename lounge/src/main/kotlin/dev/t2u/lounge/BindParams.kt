package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/app/BindParams.js (MIT, Copyright (c) Patrick Kan).
// The query string of the lounge `bind` URL: sorted keys, strictly percent-encoded values (CAST-08, RESEARCH Pattern 2).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Random

enum class BindType { INIT_SESSION, SEND_MESSAGE, RPC }

/** Data needed to build a bind query is missing; [missing] names the fields (never their values). */
class IncompleteLoungeDataException(message: String, val missing: List<String>) :
    Exception("$message: ${missing.joinToString(", ")}")

/**
 * Holds what is needed to build the query string of the bind URL for: establishing the session,
 * sending messages and listening for incoming messages. Methods are synchronized because the RPC reader
 * (AID tracking) and the sender use one instance.
 */
class BindParams(
    deviceId: String,
    private val screenName: String,
    private val screenApp: String,
    val theme: String,
    brand: String,
    model: String,
    private val random: Random,
) {
    private val device = LoungeConstants.BIND_DEVICE
    var id: String = deviceId
        private set
    private val obfuscatedGaiaId = ""
    private val capabilities = LoungeConstants.CAPABILITIES
    private val cst = LoungeConstants.CST
    private val mdxVersion = LoungeConstants.MDX_VERSION
    private val ver = LoungeConstants.VER
    private val v = LoungeConstants.V
    private val deviceInfo: String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            LinkedHashMap(LoungeConstants.DEVICE_INFO).apply {
                put("brand", JsonPrimitive(brand))
                put("model", JsonPrimitive(model))
            },
        ),
    )
    private val cver = LoungeConstants.CVER
    private val t = LoungeConstants.T

    private var rid: Int = generateRid()

    /** The highest message id seen from the lounge (what the RPC GET acknowledges). */
    var aid: Int = LoungeConstants.AID_START
        private set
    private var loungeIdToken: String? = null
    private var sid: String? = null
    private var gsessionid: String? = null

    @Synchronized
    fun reset() {
        sid = null
        loungeIdToken = null
        gsessionid = null
        rid = generateRid()
        aid = LoungeConstants.AID_START
    }

    @Synchronized
    fun updateWithMdxContext(deviceId: String?) {
        if (!deviceId.isNullOrEmpty()) id = deviceId
    }

    @Synchronized
    fun updateWithLoungeToken(token: String) {
        loungeIdToken = token
    }

    @Synchronized
    fun updateWithMessage(message: IncomingMessage) {
        when (message.name) {
            "c" -> ((message.payload as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { sid = it }
            "S" -> (message.payload as? JsonPrimitive)?.contentOrNull?.let { gsessionid = it }
        }
        if (message.aid != 0) aid = maxOf(aid, message.aid)
    }

    @Synchronized
    fun updateWithMessages(messages: List<IncomingMessage>) {
        messages.forEach { updateWithMessage(it) }
    }

    /**
     * Builds the query string for [type]. For [BindType.SEND_MESSAGE], [aidOfMessage] is the id of the sender
     * command the message answers (null for a message the receiver raised itself).
     *
     * @throws IncompleteLoungeDataException when the lounge token, SID or gsessionid is missing.
     */
    @Synchronized
    fun toQueryString(type: BindType, aidOfMessage: Int? = null): String {
        val missing = ArrayList<String>()
        if (loungeIdToken.isNullOrEmpty()) missing.add("loungeIdToken")
        if (type == BindType.SEND_MESSAGE || type == BindType.RPC) {
            if (sid.isNullOrEmpty()) missing.add("SID")
            if (gsessionid.isNullOrEmpty()) missing.add("gsessionid")
        }
        if (missing.isNotEmpty()) {
            throw IncompleteLoungeDataException("Missing data required to construct query string from bind params", missing)
        }
        val params = LinkedHashMap<String, Any?>()
        params["device"] = device
        params["id"] = id
        params["obfuscatedGaiaId"] = obfuscatedGaiaId
        params["name"] = screenName
        params["app"] = screenApp
        params["theme"] = theme
        params["capabilities"] = capabilities
        params["cst"] = cst
        params["mdxVersion"] = mdxVersion
        params["loungeIdToken"] = loungeIdToken
        params["VER"] = ver
        params["v"] = v
        params["zx"] = generateZx()
        params["t"] = t
        when (type) {
            BindType.INIT_SESSION -> {
                params["deviceInfo"] = deviceInfo
                params["RID"] = rid
                params["CVER"] = cver
                rid++
            }
            BindType.SEND_MESSAGE -> {
                val given = aidOfMessage ?: 0
                aid = if (given != 0 && aid != 0) maxOf(given, aid) else if (given != 0) given else aid
                params["deviceInfo"] = deviceInfo
                params["SID"] = sid
                params["RID"] = rid
                params["AID"] = aid
                params["gsessionid"] = gsessionid
                if (given == 0) aid++
                rid++
            }
            BindType.RPC -> {
                params["RID"] = "rpc"
                params["SID"] = sid
                params["CI"] = 0
                params["AID"] = aid
                params["gsessionid"] = gsessionid
                params["TYPE"] = "xmlhttp"
            }
        }
        return encodeStrict(params)
    }

    private fun generateZx(): String =
        java.lang.Long.toHexString(random.nextLong() ushr 16).padStart(12, '0')

    private fun generateRid(): Int =
        random.nextInt(LoungeConstants.RID_MAX - LoungeConstants.RID_MIN + 1) + LoungeConstants.RID_MIN
}

/**
 * query-string's strict mode: keys in ascending order, each key and value percent-encoded as UTF-8 keeping only
 * A-Z a-z 0-9 - _ . ~ ; null values are skipped; numbers print as JavaScript prints them.
 */
fun encodeStrict(params: Map<String, Any?>): String =
    params.keys.sorted().mapNotNull { key ->
        params[key]?.let { value -> encodeComponent(key) + "=" + encodeComponent(jsString(value)) }
    }.joinToString("&")

private const val HEX = "0123456789ABCDEF"

private fun encodeComponent(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val unreserved = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            sb.append(c.toChar())
        } else {
            sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
    return sb.toString()
}
