// Owner: the yt-dlp resolver measurements.
package dev.dialup.app

import android.os.SystemClock
import dev.t2u.media.GatewayResult
import dev.t2u.media.ResolveRecord
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.Resolver
import dev.t2u.media.YtDlpCall
import dev.t2u.media.YtDlpGateway
import dev.t2u.media.downloadCall
import dev.t2u.media.isValidVideoId
import dev.t2u.media.summarize
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * Instrument that runs the bundled yt-dlp on the phone through [AndroidYtDlpGateway] and records what
 * happened. Commands are adb-triggered; long-running ones reply `{"started":true}` at once and write a
 * result file into files/evidence, which `tools/spike.sh pull` copies.
 *
 * File contract with MediaFeature (it reads these, this feature writes them; no shared classes):
 *  - files/spike/last-resolve.json {"id","url","ext","httpHeaders","title"}: the FULL googlevideo URL, so it
 *    lives only in app-private files/spike and never in files/evidence.
 *  - files/spike/last-download.json {"id","path","bytes"}.
 *
 * Only one yt-dlp job runs at a time ([jobLock]); a second request replies `{"error":"busy"}`.
 */
class ResolverFeature : SpikeFeature {
    override val name = "resolver"
    override val commands: Set<String> = setOf("ytdlp-version", "ytdlp-update", "resolve", "resolve-set", "download")

    /** Keeps the last raw gateway result so the verbose run can save its stderr. */
    private class CapturingGateway(private val inner: YtDlpGateway) : YtDlpGateway {
        @Volatile var last: GatewayResult? = null

        override fun run(call: YtDlpCall): GatewayResult = inner.run(call).also { last = it }

        override fun version(): String? = inner.version()

        override fun update(channel: String): String = inner.update(channel)
    }

    private data class UpdateOutcome(val before: String?, val status: String, val after: String?, val channel: String, val ms: Long)

    private val jobLock = Mutex()
    private var host: SpikeHost? = null
    private var gateway: AndroidYtDlpGateway? = null
    private var capture: CapturingGateway? = null
    private var resolver: Resolver? = null

    @Volatile private var busyWith: String? = null

    @Volatile private var lastRecord: JsonObject? = null

    @Volatile private var ytdlpVersion: String? = null

    @Volatile private var progress: JsonObject? = null

    override fun start(host: SpikeHost) {
        this.host = host
        val gw = AndroidYtDlpGateway(host.context, host.evidence)
        val cap = CapturingGateway(gw)
        gateway = gw
        capture = cap
        resolver = Resolver(cap)
    }

    override fun stop() {
        host = null
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "ytdlp-version" -> startJob("ytdlp-version") { runVersion() }
        "ytdlp-update" -> handleUpdate(args)
        "resolve" -> handleResolve(args)
        "resolve-set" -> handleResolveSet(args)
        "download" -> handleDownload(args)
        else -> CommandReply(false, ERR_UNKNOWN)
    }

    override fun state(): JsonObject = buildJsonObject {
        put("ready", host != null)
        put("busy", busyWith)
        put("ytdlpVersion", ytdlpVersion)
        lastRecord?.let { put("lastRecord", it) }
        progress?.let { put("progress", it) }
    }

    // ----- argument handling (synchronous: bad input never starts a job) -------------------------------

    private fun handleUpdate(args: Map<String, String>): CommandReply {
        val channel = args["channel"] ?: "stable"
        if (channel != "stable" && channel != "nightly") return error("channel must be stable or nightly")
        return startJob("ytdlp-update") { runUpdate(channel) }
    }

    private fun handleResolve(args: Map<String, String>): CommandReply {
        val id = args["id"].orEmpty()
        if (id.isEmpty()) return error("id required")
        // Refused synchronously, before any job or process exists, so a malformed id never reaches yt-dlp.
        if (!isValidVideoId(id)) return CommandReply(false, ERR_INVALID_ID)
        val cache = args["cache"] ?: "off"
        val verbose = args["verbose"] ?: "0"
        if (cache != "on" && cache != "off") return error("cache must be on or off")
        if (verbose != "0" && verbose != "1") return error("verbose must be 0 or 1")

        return startJob("resolve") { runResolve(id, cache == "on", verbose == "1") }
    }

    private class SetPlan(
        val passes: List<String>,
        val update: Boolean,
        val channel: String,
        val ids: List<String>,
        val refused: List<String>,
        val label: String?,
    )

    private fun handleResolveSet(args: Map<String, String>): CommandReply {
        val passes = (args["passes"] ?: "default,cache").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (passes.isEmpty() || passes.any { it != "default" && it != "cache" } || passes.toSet().size != passes.size) {
            return error("passes must be a comma list of default and cache")
        }
        val update = args["update"] ?: "1"
        if (update != "0" && update != "1") return error("update must be 0 or 1")
        val channel = args["channel"] ?: "stable"
        if (channel != "stable" && channel != "nightly") return error("channel must be stable or nightly")
        val spares = args["spares"] ?: "0"
        if (spares != "0" && spares != "1") return error("spares must be 0 or 1")
        val limit = args["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return error("limit must be a positive integer") }
        val label = args["label"]
        if (label != null && !LABEL.matches(label)) return error("label must match [a-z0-9-]{1,20}")

        val h = host ?: return error("not started")
        val (setIds, spareIds) = try {
            loadVideoSet(h)
        } catch (t: Throwable) {
            return error("video-set.txt unreadable")
        }
        val refused = mutableListOf<String>()
        val extra = (args["extra"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val extraValid = mutableListOf<String>()
        for (id in extra) {
            if (isValidVideoId(id)) extraValid += id else refused += id.take(MAX_REFUSED_ECHO)
        }
        var ids = setIds + (if (spares == "1") spareIds else emptyList()) + extraValid
        if (limit != null) ids = ids.take(limit)
        if (ids.isEmpty()) return error("no video IDs to resolve")

        val plan = SetPlan(passes, update == "1", channel, ids, refused, label)
        return startJob("resolve-set") { runResolveSet(plan) }
    }

    private fun handleDownload(args: Map<String, String>): CommandReply {
        val id = args["id"].orEmpty()
        if (id.isEmpty()) return error("id required")
        if (!isValidVideoId(id)) return CommandReply(false, ERR_INVALID_ID)
        return startJob("download") { runDownload(id) }
    }

    // ----- jobs (run on the service scope, one at a time) ----------------------------------------------

    private fun runVersion() {
        val h = host ?: return
        val gw = gateway ?: return
        val version = gw.version()
        ytdlpVersion = version
        h.evidence.event("ytdlp-version", mapOf("version" to version))
        writeEvidenceFile("ytdlp-version.json", buildJsonObject { put("version", version) })
    }

    /** A failure is recorded as the status, never thrown: an offline phone must not stop the measurement. */
    private fun doUpdate(h: SpikeHost, gw: YtDlpGateway, channel: String): UpdateOutcome {
        val before = runCatching { gw.version() }.getOrNull()
        val t0 = SystemClock.elapsedRealtime()
        val status = try {
            gw.update(channel)
        } catch (t: Throwable) {
            "FAILED: " + Resolver.redactUrls((t.message ?: t.javaClass.name)).take(300)
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val after = runCatching { gw.version() }.getOrNull()
        ytdlpVersion = after ?: before
        val outcome = UpdateOutcome(before, status, after, channel, ms)
        h.evidence.event(
            "ytdlp-update",
            mapOf("before" to before, "status" to status, "after" to after, "channel" to channel, "ms" to ms),
        )
        return outcome
    }

    private fun updateJson(o: UpdateOutcome): JsonObject = buildJsonObject {
        put("before", o.before)
        put("status", o.status)
        put("after", o.after)
        put("channel", o.channel)
        put("ms", o.ms)
    }

    private fun runUpdate(channel: String) {
        val h = host ?: return
        val gw = gateway ?: return
        val outcome = doUpdate(h, gw, channel)
        writeEvidenceFile("ytdlp-update.json", updateJson(outcome))
    }

    private fun runResolve(id: String, cacheOn: Boolean, verbose: Boolean) {
        val h = host ?: return
        val res = resolver ?: return
        val cacheDir = if (cacheOn) ytdlpCacheDir(h) else null
        val (record, info) = res.resolveOnce(id, if (cacheOn) "single-cache" else "single", 0, cacheDir, verbose)
        lastRecord = record.toJson()
        // The record holds only host and expire, never the URL.
        h.evidence.event("resolve", record.toEvent())
        writeEvidenceFile("resolve-$id.json", record.toJson())
        if (info != null) writeLastResolve(h, id, info)
    }

    private fun runResolveSet(plan: SetPlan) {
        val h = host ?: return
        val gw = gateway ?: return
        val cap = capture ?: return
        val res = resolver ?: return
        val suffix = plan.label?.let { "-$it" } ?: ""
        val startedAt = Instant.now().toString()

        // payload extraction time, only when this call is the one that performs it
        var payloadInitMs: Long? = null
        if (!NativePayloads.isReady) {
            val t0 = SystemClock.elapsedRealtime()
            NativePayloads.ensure(h.context, h.evidence)
            payloadInitMs = SystemClock.elapsedRealtime() - t0
        }

        val versionBefore = runCatching { gw.version() }.getOrNull()
        var update: UpdateOutcome? = null
        if (plan.update) {
            update = doUpdate(h, gw, plan.channel)
            writeEvidenceFile("ytdlp-update.json", updateJson(update))
        }
        val versionAfter = if (update != null) update.after else versionBefore
        ytdlpVersion = versionAfter

        var lastResolveWritten = false

        // One verbose resolve of the first ID, cache off: the JS runtimes line and the yt-dlp debug header.
        var jsRuntimeLine: String? = null
        val firstId = plan.ids.first()
        val (verboseRecord, verboseInfo) = res.resolveOnce(firstId, "verbose", 0, null, true)
        jsRuntimeLine = verboseRecord.jsRuntimeLine
        val verboseText = cap.last?.stderrHead.orEmpty()
        // URLs in yt-dlp's debug output are cut to scheme and host before the file is written
        writeEvidenceText("ytdlp-verbose$suffix.txt", Resolver.redactUrls(verboseText).take(VERBOSE_MAX_CHARS))
        h.evidence.event("resolve", verboseRecord.toEvent() + mapOf("jsRuntimeLine" to jsRuntimeLine))
        if (verboseInfo != null && !lastResolveWritten) {
            writeLastResolve(h, firstId, verboseInfo)
            lastResolveWritten = true
        }

        val cacheDirPath = ytdlpCacheDir(h)
        val cacheEntriesAtStart = File(cacheDirPath).walkTopDown().count { it.isFile }
        val passResults = linkedMapOf<String, List<ResolveRecord>>()
        val total = plan.ids.size
        for (pass in plan.passes) {
            val cacheDir = if (pass == "cache") cacheDirPath else null
            val records = mutableListOf<ResolveRecord>()
            for ((index, id) in plan.ids.withIndex()) {
                val (record, info) = res.resolveOnce(id, pass, index, cacheDir, false)
                records += record
                lastRecord = record.toJson()
                h.evidence.event("resolve", record.toEvent())
                if (info != null && !lastResolveWritten) {
                    writeLastResolve(h, id, info)
                    lastResolveWritten = true
                }
                val p = buildJsonObject {
                    put("label", plan.label)
                    put("pass", pass)
                    put("done", records.size)
                    put("total", total)
                    put("ok", records.count { it.ok })
                    put("fail", records.count { !it.ok })
                    put("lastId", id)
                    put("updatedAt", Instant.now().toString())
                }
                progress = p
                writeEvidenceFile("resolve-progress.json", p)
            }
            passResults[pass] = records
        }

        val results = buildJsonObject {
            put("label", plan.label)
            put("startedAt", startedAt)
            put("finishedAt", Instant.now().toString())
            put("ytdlpVersionBefore", versionBefore)
            put("ytdlpVersionAfter", versionAfter)
            put("updateStatus", update?.status ?: "skipped")
            put("updateChannel", plan.channel)
            put("updateMs", update?.ms)
            put("jsRuntimeLine", jsRuntimeLine)
            put("payloadInitMs", payloadInitMs)
            put("verbose", verboseRecord.toJson())
            put("idCount", total)
            put("cacheDirEntriesAtStart", cacheEntriesAtStart)
            put(
                "passes",
                buildJsonObject {
                    for ((pass, records) in passResults) {
                        put(
                            pass,
                            buildJsonObject {
                                put("records", JsonArray(records.map { it.toJson() }))
                                put("summary", summarize(records).toJson())
                            },
                        )
                    }
                },
            )
            put("refused", JsonArray(plan.refused.map { buildJsonObject { put("id", it); put("reason", "invalid id") } }))
        }
        writeEvidenceFile("resolve-results$suffix.json", results)
        h.evidence.event(
            "cmd-done",
            mapOf(
                "cmd" to "resolve-set",
                "label" to plan.label,
                "passes" to plan.passes,
                "ids" to total,
                "ytdlpVersionAfter" to versionAfter,
                "jsRuntimeLine" to jsRuntimeLine,
            ),
        )
    }

    private fun runDownload(id: String) {
        val h = host ?: return
        val gw = gateway ?: return
        val dir = File(h.spikeDir, "dl")
        dir.mkdirs()
        // Out template built from the validated ID; %(ext)s is expanded by yt-dlp
        val template = File(dir, "$id.%(ext)s").absolutePath
        val result = gw.run(downloadCall(id, template, null))
        val produced = dir.listFiles { f -> f.isFile && f.name.startsWith("$id.") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
        if (result.exitCode != 0 || produced == null) {
            val why = if (result.exitCode != 0) Resolver.errorTail(result.stderrTail) else "no output file"
            h.evidence.event("download", mapOf("id" to id, "ok" to false, "wallMs" to result.wallMs, "error" to why))
            writeEvidenceFile(
                "download-$id.json",
                buildJsonObject {
                    put("id", id)
                    put("ok", false)
                    put("wallMs", result.wallMs)
                    put("error", why)
                },
            )
            return
        }
        val bytes = produced.length()
        writeAtomic(
            File(h.spikeDir, "last-download.json"),
            buildJsonObject {
                put("id", id)
                put("path", produced.absolutePath)
                put("bytes", bytes)
            }.toString(),
        )
        h.evidence.event("download", mapOf("id" to id, "ok" to true, "bytes" to bytes, "wallMs" to result.wallMs, "ext" to produced.extension))
        writeEvidenceFile(
            "download-$id.json",
            buildJsonObject {
                put("id", id)
                put("ok", true)
                put("bytes", bytes)
                put("wallMs", result.wallMs)
                put("ext", produced.extension)
            },
        )
    }

    // ----- helpers -------------------------------------------------------------------------------------

    /** Claims the single job slot or replies busy; the job runs on the service scope (Dispatchers.IO). */
    private fun startJob(label: String, block: () -> Unit): CommandReply {
        val h = host ?: return error("not started")
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

    private fun error(message: String) = CommandReply(false, buildJsonObject { put("error", message) }.toString())

    /** The 24 set IDs and the spares from assets/spike/video-set.txt (lines after "# spares" are spares). */
    private fun loadVideoSet(h: SpikeHost): Pair<List<String>, List<String>> {
        val set = mutableListOf<String>()
        val spares = mutableListOf<String>()
        var inSpares = false
        h.context.assets.open("spike/video-set.txt").bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (line.contains("spares", ignoreCase = true)) inSpares = true
                    continue
                }
                if (!isValidVideoId(line)) continue
                if (inSpares) spares += line else set += line
            }
        }
        return set to spares
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

    private fun writeEvidenceText(fileName: String, text: String) {
        val h = host ?: return
        writeAtomic(File(h.evidenceDir, fileName), text)
    }

    private fun writeLastResolve(h: SpikeHost, id: String, info: ResolvedInfo) {
        val json = buildJsonObject {
            put("id", id)
            put("url", info.url)
            put("ext", info.ext)
            put("httpHeaders", buildJsonObject { for ((k, v) in info.headers) put(k, JsonPrimitive(v)) })
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
        const val MAX_REFUSED_ECHO = 32
        const val VERBOSE_MAX_CHARS = 64 * 1024
        val LABEL = Regex("^[a-z0-9-]{1,20}$")
    }
}
