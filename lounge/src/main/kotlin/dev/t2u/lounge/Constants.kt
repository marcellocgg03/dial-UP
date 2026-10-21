package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/Constants.js and the constants of lib/app/BindParams.js,
// lib/app/Session.js, lib/app/RPCConnection.js and lib/app/YouTubeApp.js
// (MIT, Copyright (c) Patrick Kan).
//
// CAST-08 / CLAUDE.md protocol-risk constraint: the YouTube lounge protocol is undocumented and can
// change without notice, so every protocol constant (URLs, bind parameters, device info, client
// themes, player status codes, store keys, timeouts, user agent) lives in this one file. A protocol
// change is a one-file diff.

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The lounge API URLs. [base] is injectable so tests can point the receiver at a fake lounge server. */
class LoungeEndpoints(val base: String = "https://www.youtube.com") {
    val generateScreenId: String = "$base/api/lounge/pairing/generate_screen_id"
    val getLoungeTokenBatch: String = "$base/api/lounge/pairing/get_lounge_token_batch"
    val registerPairingCode: String = "$base/api/lounge/pairing/register_pairing_code"
    val getPairingCode: String = "$base/api/lounge/pairing/get_pairing_code?ctx=pair"
    val bind: String = "$base/api/lounge/bc/bind"
}

/**
 * The InnerTube API URLs. [base] is injectable so tests can point the handler at a fake /next server.
 * Source of every value here and in [InnerTubeConstants]: 03-RESEARCH.md Pattern 1.
 */
class InnerTubeEndpoints(val base: String = "https://www.youtube.com") {
    val next: String = "$base/youtubei/v1/next?prettyPrint=false&alt=json"
}

/** The TVHTML5 client identity and limits of the InnerTube /next call (03-RESEARCH.md Pattern 1). */
object InnerTubeConstants {
    const val CLIENT_NAME = "TVHTML5"
    const val CLIENT_NAME_ID = "7"
    const val CLIENT_VERSION = "7.20230405.08.01"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.142 Safari/537.36; SMART-TV; Tizen 4.0,gzip(gfe)"
    const val OS_NAME = "Windows"
    const val OS_VERSION = "10.0"
    const val PLATFORM = "DESKTOP"
    const val ORIGIN = "https://www.youtube.com"
    const val ORIGINAL_URL = "https://www.youtube.com/tv"

    /** setPlaylist awaits the refresh, so first-play latency is bounded by this (RESEARCH Pitfall 4). */
    const val CALL_TIMEOUT_MS = 10000L

    /** A hostile or broken response larger than this is refused unread (2 MiB). */
    const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

    /** How many recently asked-about video ids the Mix ping-pong guard remembers. */
    const val RECENT_LIMIT = 8
}

object LoungeConstants {
    // lib/Constants.js CONF_DEFAULTS
    const val SCREEN_APP = "ytcr"
    const val DEFAULT_BRAND = "Generic"
    const val DEFAULT_MODEL = "SmartTV"

    // lib/app/BindParams.js (the constructor's fixed fields)
    const val BIND_DEVICE = "LOUNGE_SCREEN"
    const val CAPABILITIES = "dsp,mic,dpa,ntb"
    const val CST = "m"
    const val MDX_VERSION = 2
    const val VER = 8
    const val V = 2
    const val CVER = 1
    const val T = 1
    const val AID_START = 3

    /** RID is a random number in this inclusive range (BindParams #generateRID). */
    const val RID_MIN = 41000
    const val RID_MAX = 49999

    /** lib/app/BindParams.js DEFAULT_DEVICE_INFO, in the original key order. brand and model are added per receiver. */
    val DEVICE_INFO: JsonObject = JsonObject(
        linkedMapOf(
            "year" to JsonPrimitive(0),
            "os" to JsonPrimitive("Windows"),
            "osVersion" to JsonPrimitive("10.0"),
            "chipset" to JsonPrimitive(""),
            "clientName" to JsonPrimitive("TVHTML5"),
            "dialAdditionalDataSupportLevel" to JsonPrimitive("unsupported"),
            "mdxDialServerType" to JsonPrimitive("MDX_DIAL_SERVER_TYPE_UNKNOWN"),
        ),
    )

    /** lib/app/Session.js: the lounge token refresh timer when the token reports no expiry (13 days). */
    const val TOKEN_REFRESH_FALLBACK_MS = 1123200000L

    /** lib/app/RPCConnection.js MAX_RETRIES. */
    const val RPC_MAX_RETRIES = 3

    /** The Node server's undici body timeout (5 minutes) bounds the lounge long poll (RESEARCH A4). */
    const val RPC_READ_TIMEOUT_MS = 300000L

    /** The working Node server's User-Agent; the lounge endpoints are known to accept it (RESEARCH A3, Pitfall 8). */
    const val USER_AGENT = "node"

    /** Volume changes from the sender are deferred this long so a slider drag does not flood the speaker. */
    const val VOLUME_DEFER_MS = 200L
}

/** Keys of the per-speaker [DataStore]. */
object StoreKeys {
    /** The DIAL app pid (lib/app/YouTubeApp.js start()). */
    const val APP_PID = "app.pid"

    /** The per-speaker DIAL UUID (src/dataStore.js). */
    const val DIAL_UUID = "dial.uuid"

    /** The stored screenId/deviceId for one client theme, e.g. `mdxContext.cl` (lib/app/Session.js begin()). */
    fun mdxContext(theme: String): String = "mdxContext.$theme"
}

/** lib/Constants.js PLAYER_STATUSES. The codes are what the sender sees in state messages. */
enum class PlayerStatus(val code: Int) {
    IDLE(-1),
    PLAYING(1),
    PAUSED(2),
    LOADING(3),
    STOPPED(4),
}

/** lib/Constants.js AUTOPLAY_MODES. */
enum class AutoplayMode { ENABLED, DISABLED, UNSUPPORTED }

/** lib/Constants.js CLIENTS: the YouTube and YouTube Music sender apps, told apart by the bind `theme`. */
enum class Client(val key: String, val theme: String, val displayName: String) {
    YT("YT", "cl", "YouTube"),
    YTMUSIC("YTMUSIC", "m", "YouTube Music"),
    ;

    companion object {
        /** The client for a bind theme (`cl` or `m`), or null for anything else. */
        fun fromTheme(theme: String?): Client? = entries.firstOrNull { it.theme == theme }
    }
}

/** lib/Constants.js MUTE_POLICIES (the wire value is kept for logs). */
enum class MutePolicy(val wire: String) {
    ZERO_VOLUME_LEVEL("zeroLevel"),
    PRESERVE_VOLUME_LEVEL("preserveLevel"),
    AUTO("auto"),
}

/** lib/Constants.js RESET_PLAYER_ON_DISCONNECT_POLICIES. */
enum class ResetPlayerOnDisconnectPolicy(val wire: String) {
    ALL_EXPLICITLY_DISCONNECTED("allExplicitlyDisconnected"),
    ALL_DISCONNECTED("allDisconnected"),
}
