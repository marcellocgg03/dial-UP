package dev.t2u.app

import android.content.Context
import dev.t2u.dial.DialAccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.IOException

const val SETTINGS_FILE = "settings.json"

/**
 * Default DIAL access mode (decision D-01: phone only). The single place plan 02-06 flips if the
 * D-03 measurement shows YouTube does not list phone-only receivers.
 */
val DEFAULT_DIAL_ACCESS = DialAccessMode.PHONE

/**
 * Everything the app's own screens can change, as kept in `files/settings.json` (GUI-08, D-04). The first three fields
 * always have a value; the nullable ones are overrides of config.json keys, written only once the author sets them in
 * the app. A null field means "config.json or the built-in default decides".
 */
data class AppSettingsData(
    val bridgeEnabled: Boolean = true,
    val dialAccess: DialAccessMode = DEFAULT_DIAL_ACCESS,
    val clearOnCastEnd: Boolean = false,
    val logEnabled: Boolean? = null,
    val gapless: Boolean? = null,
    val cacheMb: Int? = null,
    val outputFormat: String? = null,
    val ytdlpChannel: String? = null,
)

/**
 * What [parseSettings] found: the usable [data], the keys this version does not know ([extras], kept on write) and the
 * [problems], key names only, never values (T-07-04).
 */
data class ParsedSettings(
    val data: AppSettingsData,
    val extras: Map<String, JsonElement>,
    val problems: List<String>,
)

private const val KEY_DIAL_ACCESS = "dialAccess"
private const val KEY_GAPLESS = "gapless"

private val KNOWN_KEYS = setOf(KEY_DIAL_ACCESS, KEY_GAPLESS)

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
    o[KEY_DIAL_ACCESS]?.let { e ->
        val mode = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { DialAccessMode.fromWire(it) }
        if (mode != null) data = data.copy(dialAccess = mode) else problems += KEY_DIAL_ACCESS
    }
    o[KEY_GAPLESS]?.let { e ->
        val b = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (b != null) data = data.copy(gapless = b) else problems += KEY_GAPLESS
    }
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

/** The JSON written for [data]: known keys in a stable order, null overrides left out. */
private fun settingsJson(data: AppSettingsData, extras: Map<String, JsonElement>): JsonObject {
    val known = LinkedHashMap<String, JsonElement>()
    known[KEY_DIAL_ACCESS] = JsonPrimitive(data.dialAccess.wire)
    data.gapless?.let { known[KEY_GAPLESS] = JsonPrimitive(it) }
    return JsonObject(extras + known)
}

/**
 * App settings kept in `files/settings.json` (decision D-02, GUI-08). Read once at construction: a missing file gives
 * the defaults, malformed JSON or a bad value gives the default for that key and calls [onError] once (the safe default
 * for who may cast is phone only). [update] replaces the file through a temp file and a rename, so a reader never sees a
 * half-written file. Keys this class does not know are kept.
 */
class Settings(
    private val file: File,
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
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, "settings.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
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
            val created = Settings(file) { where, t ->
                ServiceHub.service?.evidence?.event(
                    "feature-error",
                    mapOf("where" to where, "error" to t.javaClass.name, "message" to t.message),
                )
            }
            instance = created
            return created
        }
    }
}
