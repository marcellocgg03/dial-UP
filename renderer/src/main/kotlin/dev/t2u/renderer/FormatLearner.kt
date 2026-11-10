package dev.t2u.renderer

// What one Renderer knows about the formats its speaker takes, GPL-3.0-only.

import dev.t2u.media.FALLBACK_CHAIN
import dev.t2u.upnp.SinkProtocol
import kotlinx.serialization.json.JsonPrimitive

/**
 * The speaker's format knowledge: the sink protocols it listed, the format remembered in its store, and the formats
 * that reached PLAYING since this renderer started. [outputFormat] is read at every use so a config change applies at
 * once; [note] is the Renderer's event helper. The fields keep the @Volatile and concurrent-set types they had in Renderer.
 */
internal class FormatLearner(
    private val outputFormat: () -> String,
    private val store: () -> RendererDataStore?,
    private val note: (level: String, message: String) -> Unit,
) {
    @Volatile private var protocols: List<SinkProtocol>? = null

    /** The format that last reached PLAYING on this speaker (loaded from its store, see [confirm]). */
    @Volatile private var learnedFormat: String? = null

    /** Formats that reached PLAYING on this speaker since this renderer started; a start failure never forgets one of these. */
    private val playedFormats: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun setProtocols(sink: List<SinkProtocol>?) {
        protocols = sink
    }

    /** Reads the remembered format from [from]; a stored value counts only when it names a chain format. */
    suspend fun load(from: RendererDataStore) {
        learnedFormat = (from.get(LEARNED_FORMAT_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it in FALLBACK_CHAIN }
    }

    fun choose(mime: String): String = chooseFormat(outputFormat(), learnedFormat, protocols, mime)

    /** The format for a mid-track start of a direct stream, which cannot start at an offset. */
    fun offsetFormat(mime: String): String = nextFormat("direct", protocols, mime) ?: "mp3"

    /** The format to try after [format] failed, or null when the mode or the chain has none left. */
    fun nextAfter(format: String, mime: String): String? =
        when (outputFormat()) {
            "auto" -> nextFormat(format, protocols, mime)
            "streaming" -> if (format == "adts") "mp3" else null
            else -> null
        }

    fun markPlayed(format: String) {
        playedFormats.add(format)
    }

    /**
     * A normal start proves the format works for future tracks. An offset stream may have been chosen
     * only to seek, so it must not replace the speaker's ordinary format preference.
     */
    suspend fun confirm(c: Current) {
        if (c.offsetOnly) return
        playedFormats.add(c.format)
        if (outputFormat() != "auto" || learnedFormat == c.format) return
        learnedFormat = c.format
        note("info", "Format ${c.format} works on this speaker - remembered")
        store()?.set(LEARNED_FORMAT_KEY, JsonPrimitive(c.format))
    }

    /**
     * A remembered format that has played on this speaker in this session is working: a start failure of one track
     * (or of another format) does not make the app forget it. One that never played is cleared.
     */
    suspend fun forgetUnproven(c: Current) {
        val remembered = learnedFormat
        if (outputFormat() == "auto" && !c.offsetOnly && remembered == c.format && remembered !in playedFormats) {
            learnedFormat = null
            store()?.set(LEARNED_FORMAT_KEY, null)
        }
    }
}
