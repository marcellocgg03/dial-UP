package dev.dialup.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.Inet4Address

/** Reply to a spike command; [json] is a single-line JSON document returned through `am broadcast`. */
data class CommandReply(val ok: Boolean, val json: String)

/**
 * What the foreground service offers to a feature. Implemented by [BridgeService].
 * [payloads] blocks while the youtubedl-android payloads are extracted: never call it on the main thread.
 */
interface SpikeHost {
    val context: Context

    /** SupervisorJob + Dispatchers.IO, cancelled in the service's onDestroy. */
    val scope: CoroutineScope
    val evidence: EvidenceLog

    /** filesDir/evidence (app-private). */
    val evidenceDir: File

    /** filesDir/spike (app-private). */
    val spikeDir: File

    fun wifiIpv4(): Inet4Address?

    fun wifiInterfaceName(): String?

    fun payloads(): NativePayloads.Paths
}

/**
 * One feature of the service. [CastFeature] is the product: it runs the cast bridge. [ResolverFeature] and
 * [MediaFeature] are adb diagnostics from Phase 1 (resolve timings, ffmpeg pipes); the media diagnostic listens on
 * its own port 8100, so it never collides with the bridge's media server.
 *
 * Originally one wave-3 plan filled one feature. A feature is started in the service's onCreate and stopped in
 * onDestroy; commands named in [commands] reach [handle]; [state] is merged into state.json under [name].
 * Long-running commands reply `{"started":true}` at once and write their result file into evidenceDir.
 */
interface SpikeFeature {
    val name: String
    val commands: Set<String>

    fun start(host: SpikeHost)

    fun stop()

    fun handle(cmd: String, args: Map<String, String>): CommandReply

    fun state(): JsonObject
}

object SpikeFeatures {
    fun all(): List<SpikeFeature> = listOf(CastFeature(), ResolverFeature(), MediaFeature())
}
