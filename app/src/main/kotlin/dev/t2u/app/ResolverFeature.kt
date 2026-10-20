// Owner: Plan 01-05 (yt-dlp resolver measurements).
package dev.t2u.app

import dev.t2u.media.ResolveRecord
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.Resolver
import dev.t2u.media.isValidVideoId
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * SPK-04 instrument: runs the bundled yt-dlp on the phone through [AndroidYtDlpGateway] and records what
 * happened. Commands are adb-triggered (D-02) and long-running ones reply `{"started":true}` at once and
 * write a result file into files/evidence, which `tools/spike.sh pull` copies.
 *
 * File contract with Plan 01-06 (it reads these, this plan writes them; no shared classes):
 *  - files/spike/last-resolve.json {"id","url","ext","httpHeaders","title"}: the FULL googlevideo URL, so it
 *    lives only in app-private files/spike and never in files/evidence (threat T-01-12).
 *  - files/spike/last-download.json {"id","path","bytes"}.
 *
 * Only one yt-dlp job runs at a time ([jobLock], threat T-01-13); a second request replies `{"error":"busy"}`.
 */
class ResolverFeature : SpikeFeature {
    override val name = "resolver"
    override val commands: Set<String> = setOf("ytdlp-version", "resolve")

    private val jobLock = Mutex()
    private var host: SpikeHost? = null
    private var gateway: AndroidYtDlpGateway? = null
    private var resolver: Resolver? = null

    @Volatile private var busyWith: String? = null

    @Volatile private var lastRecord: JsonObject? = null

    @Volatile private var ytdlpVersion: String? = null

    override fun start(host: SpikeHost) {
        this.host = host
        val gw = AndroidYtDlpGateway(host.context, host.evidence)
        gateway = gw
        resolver = Resolver(gw)
    }

    override fun stop() {
        host = null
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "ytdlp-version" -> startJob("ytdlp-version") { runVersion() }
        "resolve" -> handleResolve(args)
        else -> CommandReply(false, ERR_UNKNOWN)
    }

    override fun state(): JsonObject = buildJsonObject {
        put("ready", host != null)
        put("busy", busyWith)
        put("ytdlpVersion", ytdlpVersion)
        lastRecord?.let { put("lastRecord", it) }
    }

    // ----- commands ------------------------------------------------------------------------------

    private fun handleResolve(args: Map<String, String>): CommandReply {
        val id = args["id"].orEmpty()
        if (id.isEmpty()) return CommandReply(false, """{"error":"id required"}""")
        // Refused synchronously, before any job or process exists (threat T-01-10).
        if (!isValidVideoId(id)) return CommandReply(false, ERR_INVALID_ID)
        val cache = args["cache"] ?: "off"
        val verbose = args["verbose"] ?: "0"
        if (cache != "on" && cache != "off") return CommandReply(false, """{"error":"cache must be on or off"}""")
        if (verbose != "0" && verbose != "1") return CommandReply(false, """{"error":"verbose must be 0 or 1"}""")

        return startJob("resolve") { runResolve(id, cache == "on", verbose == "1") }
    }

    private fun runVersion() {
        val h = host ?: return
        val gw = gateway ?: return
        val version = gw.version()
        ytdlpVersion = version
        h.evidence.event("ytdlp-version", mapOf("version" to version))
        writeEvidenceFile("ytdlp-version.json", buildJsonObject { put("version", version) })
    }

    private fun runResolve(id: String, cacheOn: Boolean, verbose: Boolean) {
        val h = host ?: return
        val res = resolver ?: return
        val cacheDir = if (cacheOn) ytdlpCacheDir(h) else null
        val (record, info) = res.resolveOnce(id, if (cacheOn) "single-cache" else "single", 0, cacheDir, verbose)
        val json = record.toJson()
        lastRecord = json
        // The record holds only host and expire, never the URL (T-01-12).
        h.evidence.event("resolve", record.toEvent())
        writeEvidenceFile("resolve-$id.json", json)
        if (info != null) writeLastResolve(h, id, info)
    }

    // ----- helpers -------------------------------------------------------------------------------

    /** Claims the single job slot or replies busy; the job runs on the service scope (Dispatchers.IO). */
    private fun startJob(label: String, block: () -> Unit): CommandReply {
        val h = host ?: return CommandReply(false, """{"error":"not started"}""")
        if (!jobLock.tryLock()) return CommandReply(false, ERR_BUSY)
        busyWith = label
        h.scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                h.evidence.event("feature-error", mapOf("feature" to name, "job" to label, "error" to t.javaClass.name, "message" to t.message))
            } finally {
                busyWith = null
                jobLock.unlock()
            }
        }
        return CommandReply(true, """{"started":true}""")
    }

    private fun ytdlpCacheDir(h: SpikeHost): String {
        val dir = File(h.context.cacheDir, "yt-dlp")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun writeEvidenceFile(fileName: String, json: JsonElement) {
        val h = host ?: return
        writeAtomic(File(h.evidenceDir, fileName), json.toString())
    }

    private fun writeLastResolve(h: SpikeHost, id: String, info: ResolvedInfo) {
        val json = buildJsonObject {
            put("id", id)
            put("url", info.url)
            put("ext", info.ext)
            put("httpHeaders", buildJsonObject { for ((k, v) in info.headers) put(k, v) })
            put("title", info.title)
        }
        // app-private files/spike, not files/evidence: it holds the signed URL
        writeAtomic(File(h.spikeDir, "last-resolve.json"), json.toString())
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) target.writeText(text)
    }

    private fun ResolveRecord.toEvent(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "pass" to pass,
        "index" to index,
        "ok" to ok,
        "wallMs" to wallMs,
        "ext" to ext,
        "formatId" to formatId,
        "urlHost" to urlHost,
        "expire" to expire,
        "error" to error,
    )

    private companion object {
        const val ERR_UNKNOWN = """{"error":"unknown command"}"""
        const val ERR_BUSY = """{"error":"busy"}"""
        const val ERR_INVALID_ID = """{"error":"invalid id"}"""
    }
}
