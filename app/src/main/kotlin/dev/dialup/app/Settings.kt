package dev.dialup.app

import android.content.Context
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.YTDLP_CHANNELS
import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException

const val SETTINGS_FILE = "settings.json"

/**
 * Default DIAL access mode: phone only. The single place to flip if a measurement shows YouTube does not
 * list phone-only receivers.
 */
val DEFAULT_DIAL_ACCESS = DialAccessMode.PHONE

/** The speaker emoji every cast-menu name starts with unless the author changes it. */
const val DEFAULT_NAME_PREFIX = "🔊"

/** The longest prefix the Settings field takes, in characters. */
const val MAX_NAME_PREFIX_LENGTH = 16

/** The app's light or dark look. UI-only: the bridge never reads it, and it is not a config.json override. */
enum class ThemeMode(val wire: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** The mode for a wire name, or null for anything else (the match is case-sensitive). */
        fun fromWire(s: String?): ThemeMode? = entries.firstOrNull { it.wire == s }
    }
}

/** The theme of a fresh install: follow the phone's light or dark setting. */
val DEFAULT_THEME = ThemeMode.SYSTEM

/** Whether the app draws dark for [mode], given the phone's own dark setting [systemDark]. */
fun useDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Everything the app's own screens can change, as kept in `files/settings.json`. The first three fields
 * always have a value; the nullable ones are overrides of config.json keys, written only once the author sets them in
 * the app. A null field means "config.json or the built-in default decides".
 */
data class AppSettingsData(
    val bridgeEnabled: Boolean = true,
    val dialAccess: DialAccessMode = DEFAULT_DIAL_ACCESS,
    val clearOnCastEnd: Boolean = false,
    val showAudioFormat: Boolean = false,

    /** Tell the author when another device on the LAN starts a cast on a speaker (LAN mode only). */
    val notifyGuests: Boolean = true,
    /** The text before a speaker's own name in the cast menu. Empty is allowed. Read live; a change renames the running speakers. */
    val namePrefix: String = DEFAULT_NAME_PREFIX,
    val theme: ThemeMode = DEFAULT_THEME,
    /** App-only: whether opening the app checks GitHub for a newer DialUP release. Not a config.json override. */
    val updateCheckEnabled: Boolean = true,
    val logEnabled: Boolean? = null,
    val gapless: Boolean? = null,
    val cacheMb: Int? = null,
    val outputFormat: String? = null,
    val ytdlpChannel: String? = null,
)

/**
 * What [parseSettings] found: the usable [data], the keys this version does not know ([extras], kept on write) and the
 * [problems], key names only, never values (a value may be a secret).
 */
data class ParsedSettings(
    val data: AppSettingsData,
    val extras: Map<String, JsonElement>,
    val problems: List<String>,
)

/** The cache size range Config.validate accepts, in MiB. */
private val CACHE_MB_RANGE = 0..32768

/** The app's selectable formats, plus the old automatic value for migration. */
val SETTINGS_OUTPUT_FORMATS = setOf("auto", "streaming", "direct", "mp4")

private const val KEY_BRIDGE_ENABLED = "bridgeEnabled"
private const val KEY_DIAL_ACCESS = "dialAccess"
private const val KEY_CLEAR_ON_CAST_END = "clearOnCastEnd"
private const val KEY_SHOW_AUDIO_FORMAT = "showAudioFormat"
private const val KEY_NOTIFY_GUESTS = "notifyGuests"
private const val KEY_NAME_PREFIX = "namePrefix"
private const val KEY_THEME = "theme"
private const val KEY_UPDATE_CHECK = "updateCheckEnabled"
private const val KEY_LOG_ENABLED = "logEnabled"
private const val KEY_GAPLESS = "gapless"
private const val KEY_CACHE_MB = "cacheMb"
private const val KEY_OUTPUT_FORMAT = "outputFormat"
private const val KEY_YTDLP_CHANNEL = "ytdlpChannel"

private val KNOWN_KEYS = setOf(
    KEY_BRIDGE_ENABLED, KEY_DIAL_ACCESS, KEY_CLEAR_ON_CAST_END, KEY_SHOW_AUDIO_FORMAT, KEY_NOTIFY_GUESTS, KEY_THEME, KEY_UPDATE_CHECK, KEY_LOG_ENABLED,
    KEY_GAPLESS, KEY_CACHE_MB, KEY_OUTPUT_FORMAT, KEY_YTDLP_CHANNEL,
)

/**
 * The nullable override keys of [data] that are set, in a stable order. Names only. The theme and the update check are app-only
 * and never listed.
 */
fun settingsOverrideKeys(data: AppSettingsData): List<String> = buildList {
    if (data.logEnabled != null) add(KEY_LOG_ENABLED)
    if (data.gapless != null) add(KEY_GAPLESS)
    if (data.cacheMb != null) add(KEY_CACHE_MB)
    if (data.outputFormat != null) add(KEY_OUTPUT_FORMAT)
    if (data.ytdlpChannel != null) add(KEY_YTDLP_CHANNEL)
}

/**
 * Turns the text of settings.json into [AppSettingsData]. Pure: it never throws. Text that is not a JSON object gives
 * the defaults and one problem; a key with a wrong type or value falls back alone and adds its name to the problems.
 */
fun parseSettings(text: String): ParsedSettings {
    val o = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return ParsedSettings(AppSettingsData(), emptyMap(), listOf("settings.json"))
    val problems = mutableListOf<String>()
    var data = AppSettingsData()

    fun bool(key: String): Boolean? {
        val e = o[key] ?: return null
        val b = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (b == null) problems += key
        return b
    }

    fun text(key: String, allowed: Set<String>): String? {
        val e = o[key] ?: return null
        val t = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in allowed }
        if (t == null) problems += key
        return t
    }

    bool(KEY_BRIDGE_ENABLED)?.let { data = data.copy(bridgeEnabled = it) }
    o[KEY_DIAL_ACCESS]?.let { e ->
        val mode = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { DialAccessMode.fromWire(it) }
        if (mode != null) data = data.copy(dialAccess = mode) else problems += KEY_DIAL_ACCESS
    }
    bool(KEY_CLEAR_ON_CAST_END)?.let { data = data.copy(clearOnCastEnd = it) }
    bool(KEY_SHOW_AUDIO_FORMAT)?.let { data = data.copy(showAudioFormat = it) }
    bool(KEY_NOTIFY_GUESTS)?.let { data = data.copy(notifyGuests = it) }
    o[KEY_NAME_PREFIX]?.let { e ->
        val text = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text != null) data = data.copy(namePrefix = text) else problems += KEY_NAME_PREFIX
    }
    o[KEY_THEME]?.let { e ->
        val mode = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { ThemeMode.fromWire(it) }
        if (mode != null) data = data.copy(theme = mode) else problems += KEY_THEME
    }
    bool(KEY_UPDATE_CHECK)?.let { data = data.copy(updateCheckEnabled = it) }
    data = data.copy(logEnabled = bool(KEY_LOG_ENABLED), gapless = bool(KEY_GAPLESS))
    o[KEY_CACHE_MB]?.let { e ->
        val n = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in CACHE_MB_RANGE }
        if (n != null) data = data.copy(cacheMb = n) else problems += KEY_CACHE_MB
    }
    data = data.copy(
        outputFormat = text(KEY_OUTPUT_FORMAT, SETTINGS_OUTPUT_FORMATS),
        ytdlpChannel = text(KEY_YTDLP_CHANNEL, YTDLP_CHANNELS),
    )
    return ParsedSettings(data, o.filterKeys { it !in KNOWN_KEYS }, problems)
}

/** The settings in [file], or the defaults when it is missing or unreadable. Never throws; problems are dropped here. */
fun readSettingsFile(file: File): AppSettingsData =
    try {
        if (file.isFile) parseSettings(file.readText()).data else AppSettingsData()
    } catch (_: IOException) {
        AppSettingsData()
    } catch (_: SecurityException) {
        AppSettingsData()
    }

/** The JSON written for [data]: known keys in a stable order, null overrides and default switches left out. */
private fun settingsJson(data: AppSettingsData, extras: Map<String, JsonElement>): JsonObject {
    val known = LinkedHashMap<String, JsonElement>()
    // The two plain switches and the theme are written only when they differ from the fresh-install default, so the file stays small.
    if (!data.bridgeEnabled) known[KEY_BRIDGE_ENABLED] = JsonPrimitive(false)
    known[KEY_DIAL_ACCESS] = JsonPrimitive(data.dialAccess.wire)
    if (data.clearOnCastEnd) known[KEY_CLEAR_ON_CAST_END] = JsonPrimitive(true)
    if (data.showAudioFormat) known[KEY_SHOW_AUDIO_FORMAT] = JsonPrimitive(true)
    if (!data.notifyGuests) known[KEY_NOTIFY_GUESTS] = JsonPrimitive(false)
    if (data.namePrefix != DEFAULT_NAME_PREFIX) known[KEY_NAME_PREFIX] = JsonPrimitive(data.namePrefix)
    if (data.theme != DEFAULT_THEME) known[KEY_THEME] = JsonPrimitive(data.theme.wire)
    if (!data.updateCheckEnabled) known[KEY_UPDATE_CHECK] = JsonPrimitive(false)
    data.logEnabled?.let { known[KEY_LOG_ENABLED] = JsonPrimitive(it) }
    data.gapless?.let { known[KEY_GAPLESS] = JsonPrimitive(it) }
    data.cacheMb?.let { known[KEY_CACHE_MB] = JsonPrimitive(it) }
    data.outputFormat?.let { known[KEY_OUTPUT_FORMAT] = JsonPrimitive(it) }
    data.ytdlpChannel?.let { known[KEY_YTDLP_CHANNEL] = JsonPrimitive(it) }
    return JsonObject(extras + known)
}

/**
 * App settings kept in `files/settings.json`. Read once at construction: a missing file gives
 * the defaults, malformed JSON or a bad value gives the default for that key and calls [onError] once (the safe default
 * for who may cast is phone only). [update] replaces the file atomically or not at all, so a reader never sees a half-written
 * file; a failed replacement keeps the previous file, calls [onError] and the next update writes the full current settings
 * again. Keys this class does not know are kept.
 */
class Settings(
    private val file: File,
    private val writer: (File, String) -> Unit = { target, text ->
        writeTextAtomic(target, text, AtomicWriteOptions.APP.copy(atomicOnly = true))
    },
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var extras: Map<String, JsonElement> = emptyMap()
    private val state = MutableStateFlow(AppSettingsData())
    private val mode = MutableStateFlow(DEFAULT_DIAL_ACCESS)

    /** The current settings, as observed by the screens and the bridge gates. */
    val data: StateFlow<AppSettingsData> get() = state

    /** Current mode; read per request by the DIAL gate and the SSDP reply policy. */
    val dialAccess: StateFlow<DialAccessMode> get() = mode

    init {
        load()
    }

    /** Applies [change] to the current settings under the lock, publishes the result and persists it atomically. */
    fun update(change: (AppSettingsData) -> AppSettingsData) {
        synchronized(lock) {
            val next = change(state.value)
            state.value = next
            mode.value = next.dialAccess
            write(next)
        }
    }

    fun setDialAccess(mode: DialAccessMode) {
        update { it.copy(dialAccess = mode) }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val parsed = parseSettings(file.readText())
            extras = parsed.extras
            state.value = parsed.data
            mode.value = parsed.data.dialAccess
            if (parsed.problems.isNotEmpty()) {
                onError("settings:read", IllegalArgumentException("settings.json: ${parsed.problems.joinToString(", ")}"))
            }
        } catch (t: Throwable) {
            onError("settings:read", t)
        }
    }

    private fun write(data: AppSettingsData) {
        try {
            val json = settingsJson(data, extras).toString()
            file.absoluteFile.parentFile?.mkdirs()
            writer(file, json)
        } catch (t: Throwable) {
            onError("settings:write", t)
        }
    }
}

/** One process-wide [Settings] on `filesDir/settings.json`. */
object AppSettings {
    @Volatile private var instance: Settings? = null

    fun of(context: Context): Settings {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val file = File(context.applicationContext.filesDir, SETTINGS_FILE)
            val created = Settings(file, onError = { where, t -> Failures.report(where, t) })
            instance = created
            return created
        }
    }
}
