package dev.t2u.app

import android.content.Context
import dev.t2u.dial.DialAccessMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

const val SETTINGS_FILE = "settings.json"

/**
 * Default DIAL access mode (decision D-01: phone only). The single place plan 02-06 flips if the
 * D-03 measurement shows YouTube does not list phone-only receivers.
 */
val DEFAULT_DIAL_ACCESS = DialAccessMode.PHONE

/**
 * App settings kept in `files/settings.json` (decision D-02). Read once at construction: a missing
 * file gives the defaults, malformed JSON or an unknown value gives the defaults and calls [onError]
 * (the safe default is phone only). Writes replace the file through a temp file and a rename, so a
 * reader never sees a half-written file. Keys this class does not know are kept.
 */
class Settings(private val file: File, private val onError: (String, Throwable) -> Unit) {
    private val lock = Any()
    private var extras: Map<String, JsonElement> = emptyMap()
    private val mode = MutableStateFlow(DEFAULT_DIAL_ACCESS)

    /** Current mode; read per request by the DIAL gate and the SSDP reply policy. */
    val dialAccess: StateFlow<DialAccessMode> get() = mode

    init {
        mode.value = load()
    }

    fun setDialAccess(mode: DialAccessMode) {
        synchronized(lock) {
            this.mode.value = mode
            write(mode)
        }
    }

    private fun load(): DialAccessMode {
        if (!file.exists()) return DEFAULT_DIAL_ACCESS
        return try {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            extras = o.filterKeys { it != KEY_DIAL_ACCESS }
            val raw = o[KEY_DIAL_ACCESS]?.jsonPrimitive?.contentOrNull
            if (raw == null) {
                DEFAULT_DIAL_ACCESS
            } else {
                DialAccessMode.fromWire(raw)
                    ?: DEFAULT_DIAL_ACCESS.also { onError("settings:read", IllegalArgumentException("unknown dialAccess value")) }
            }
        } catch (t: Throwable) {
            onError("settings:read", t)
            DEFAULT_DIAL_ACCESS
        }
    }

    private fun write(mode: DialAccessMode) {
        try {
            val json = JsonObject(extras + (KEY_DIAL_ACCESS to JsonPrimitive(mode.wire))).toString()
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, "settings.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        } catch (t: Throwable) {
            onError("settings:write", t)
        }
    }

    private companion object {
        const val KEY_DIAL_ACCESS = "dialAccess"
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
