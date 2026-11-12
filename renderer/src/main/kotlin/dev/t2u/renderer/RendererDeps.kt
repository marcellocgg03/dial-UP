package dev.t2u.renderer

// What a Renderer needs from the outside, and what it reports as playing, GPL-3.0-only.

import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Everything a [Renderer] needs from the outside. [http] must derive from `lanHttpClient()`,
 * [scope] is the service scope the renderer's own child scope hangs under, [clock] is epoch milliseconds.
 */
data class RendererDeps(
    val config: Config,
    val resolver: VideoResolver,
    val media: MediaRegistrar,
    val http: OkHttpClient,
    val createReceiver: ReceiverFactory,
    val scope: CoroutineScope,
    val events: EventSink = NoEvents,
    val clock: () -> Long = System::currentTimeMillis,
    val playlistRequestHandlerFactory: (() -> PlaylistRequestHandler)? = null,
    /** The wait between two reads of the slow-clear watch; a test replaces it to step the watch by hand. */
    val pause: suspend (Long) -> Unit = { delay(it) },
    /**
     * whether a cast that ends (the last sender leaving) empties the speaker. Read at clear time, so a switch of
     * the setting applies to a running bridge. The default reads the fixed [config] flag.
     */
    val clearOnCastEnd: () -> Boolean = { config.clearOnCastEnd },
    /**
     * Rename: the author's name for the speaker with this UDN, read once at init; null keeps the described name.
     * Matching (hide, manual, ports, the name filter) never uses it.
     */
    val userName: (String) -> String? = { null },
    /** The template of a name shown in the cast menu ({name} {manufacturer} {model}), read at setup and on [Renderer.refreshName]. */
    val nameTemplate: () -> String = { config.nameTemplate },
)

/** What the speaker is playing, for the status UI and the notification. */
data class NowPlaying(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: Double,
    val format: String,
    val thumbnail: String,
    val sourceMime: String? = null,
)
