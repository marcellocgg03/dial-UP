// Owner: Plan 01-06 (ffmpeg pipes and the Ktor media server).
package dev.t2u.app

import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.StreamEntry
import dev.t2u.media.StreamSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/**
 * SPK-05 instrument: the Ktor media server and the youtubedl-android ffmpeg on the phone.
 *
 * Commands are adb-triggered (D-02); each long job replies `{"started":true}` at once and writes its result
 * (`files/evidence/media-tokens-<input>.json`) when done, which `tools/spike.sh pull` copies and
 * `tools/media-check.sh` / `tools/sonos-play.sh` consume.
 *
 * Every request the server sees becomes an `http-req` event with `server":"media"`, the HTTP version, the Range
 * header and the User-Agent: that is how the Sonos' HTTP version, Range use and User-Agent get measured
 * (RESEARCH Open Question 3).
 */
class MediaFeature : SpikeFeature {
    override val name = "media"
    override val commands: Set<String> = setOf("media-start", "media-stop", "pipe-test")

    private val jobLock = Mutex()
    private var host: SpikeHost? = null
    private var runner: FfmpegRunner? = null
    private var server: MediaServer? = null

    @Volatile private var boundIp: String? = null

    @Volatile private var busyWith: String? = null

    @Volatile private var lastTokenFile: String? = null

    override fun start(host: SpikeHost) {
        this.host = host
    }

    override fun stop() {
        server?.stop()
        server = null
        host = null
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "media-start" -> startJob("media-start") { runMediaStart() }
        "pipe-test" -> handlePipeTest(args)
        "media-stop" -> {
            stopServer()
            CommandReply(true, """{"stopped":true}""")
        }
        else -> CommandReply(false, ERR_UNKNOWN)
    }

    override fun state(): JsonObject = buildJsonObject {
        val s = server
        put("ready", host != null)
        put("running", s != null && s.isRunning())
        put("base", if (s != null && s.isRunning()) base(s) else null)
        put("tokens", s?.tokenCount() ?: 0)
        put("ffmpegLive", runner?.liveCount() ?: 0)
        put("busy", busyWith)
        put("lastTokenFile", lastTokenFile)
    }

    // ----- commands ------------------------------------------------------------------------------------

    private fun runMediaStart() {
        val h = host ?: return
        val srv = ensureServer(h)
        val sine = ensureSine(h)
        val path = srv.register(StreamEntry(StreamSource.FileSource(sine), "mp4", "audio/mp4", "sine-file"))
        writeTokens(h, "sine", srv, listOf(Token("sine-file", path, "mp4", "audio/mp4", "file")))
        h.evidence.event("cmd-done", mapOf("cmd" to "media-start", "base" to base(srv), "tokens" to 1))
    }

    /** Generates the 30 s 440 Hz tone on the phone with its own ffmpeg (SPK-05 input 1). Reuses an earlier file. */
    private fun ensureSine(h: SpikeHost): File {
        val dir = File(h.spikeDir, "media").also { it.mkdirs() }
        val sine = File(dir, "sine.m4a")
        if (sine.isFile && sine.length() > 0) return sine
        val r = runner ?: error("ffmpeg runner not ready")
        val code = r.runToCompletion(
            listOf("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=30", "-c:a", "aac", "-b:a", "128k", "-f", "ipod", sine.absolutePath),
            60,
        )
        check(code == 0 && sine.length() > 0) { "ffmpeg could not generate the tone (exit $code)" }
        return sine
    }

    /** Starts the server on the Wi-Fi IPv4 address if it is not running (or the address changed). */
    private fun ensureServer(h: SpikeHost): MediaServer {
        val paths = h.payloads()
        if (runner == null) runner = FfmpegRunner(paths, h.context.cacheDir, h.evidence)
        val ip = h.wifiIpv4()?.hostAddress ?: error("no Wi-Fi IPv4 address")
        server?.let { running ->
            if (running.isRunning() && ip == boundIp) return running
            running.stop()
        }
        val srv = MediaServer(
            host = ip,
            port = PORT,
            launcher = runner,
            onRequest = { onRequest(h, it) },
            bufferDir = File(h.spikeDir, "media/buffer").also { it.mkdirs() },
        )
        srv.start()
        server = srv
        boundIp = ip
        h.evidence.event("media-server", mapOf("state" to "started", "host" to ip, "port" to srv.resolvedPort()))
        return srv
    }

    private fun stopServer() {
        val h = host
        server?.stop()
        server = null
        boundIp = null
        h?.evidence?.event("media-server", mapOf("state" to "stopped"))
    }


    // ----- pipe-test -----------------------------------------------------------------------------------

    /** What an input contributes: the ffmpeg input side and, when it is already an AAC-in-MP4 file, a direct file token. */
    private class PipeInput(val name: String, val args: List<String>, val m4aFile: File?, val source: String?)

    /** Replies at once with the reason when the input cannot be built; ffmpeg is never started in that case. */
    private fun handlePipeTest(args: Map<String, String>): CommandReply {
        val h = host ?: return reply("not started")
        val input = args["input"] ?: "sine"
        if (input != "sine" && input != "download" && input != "url") return reply("input must be sine, download or url")
        val built = when (input) {
            "download" -> buildDownloadInput(h)
            "url" -> buildUrlInput(h)
            else -> PipeInputResult(null, null)
        }
        built.error?.let { return CommandReply(false, it) }
        return startJob("pipe-test:$input") { runPipeTest(h, input, built.input) }
    }

    private class PipeInputResult(val input: PipeInput?, val error: String?)

    private fun fail(message: String) = PipeInputResult(null, buildJsonObject { put("error", message) }.toString())

    private fun readJson(file: File): JsonObject? = try {
        if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else null
    } catch (_: Exception) {
        null
    }

    private fun fileInputArgs(file: File) = listOf("-hide_banner", "-loglevel", "error", "-i", file.absolutePath, "-vn")

    private fun buildDownloadInput(h: SpikeHost): PipeInputResult {
        val json = readJson(File(h.spikeDir, "last-download.json"))
        val path = json?.get("path")?.jsonPrimitive?.contentOrNull
        if (path.isNullOrEmpty()) return fail("no download yet")
        val file = File(path)
        if (!file.isFile) return fail("no download yet")
        // The path must canonicalise under app storage (threat T-01-15).
        val canonical = file.canonicalFile.toPath()
        val roots = listOf(h.context.filesDir, h.context.cacheDir).map { it.canonicalFile.toPath() }
        if (roots.none { canonical.startsWith(it) }) return fail("path outside app storage")
        val m4a = file.takeIf { it.extension.lowercase() in setOf("m4a", "mp4") }
        return PipeInputResult(PipeInput("download", fileInputArgs(file), m4a, null), null)
    }

    private fun buildUrlInput(h: SpikeHost): PipeInputResult {
        val json = readJson(File(h.spikeDir, "last-resolve.json"))
        val url = json?.get("url")?.jsonPrimitive?.contentOrNull
        if (url.isNullOrEmpty()) return fail("no resolve result yet")
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return fail("url rejected")
        }
        val hostName = uri.host?.lowercase()
        if (uri.scheme != "https" || hostName == null || !hostName.endsWith(".googlevideo.com")) return fail("url rejected")
        val headers = (json["httpHeaders"] as? JsonObject)
            ?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { "$k: $it\r\n" } }
            ?.joinToString("")
            .orEmpty()
        // urlInputArgs of mediaServer.js
        val args = buildList {
            addAll(listOf("-hide_banner", "-loglevel", "error"))
            if (headers.isNotEmpty()) addAll(listOf("-headers", headers))
            addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"))
            addAll(listOf("-i", url, "-vn"))
        }
        return PipeInputResult(PipeInput("url", args, null, "https://$hostName"), null)
    }

    /**
     * Registers every way of serving [pipe] (RESEARCH Pattern 7, inputs 1-3 reported separately):
     * a direct file token when the source is an m4a, pipe tokens for ADTS (offered as three MIME types, since the
     * Sonos sink list has no AAC type, Pitfall 7) and MP3, and file-backed ADTS and MP3 tokens (complete file
     * first, Content-Length and Range) so a renderer that needs them can be tested whatever its HTTP version.
     */
    private fun runPipeTest(h: SpikeHost, input: String, pipe: PipeInput?) {
        val srv = ensureServer(h)
        val ff = runner ?: error("ffmpeg runner not ready")
        val dir = File(h.spikeDir, "media").also { it.mkdirs() }
        val source = pipe ?: ensureSine(h).let { PipeInput("sine", fileInputArgs(it), it, null) }
        val tokens = ArrayList<Token>()

        fun reg(src: StreamSource, format: String, mime: String, label: String, kind: String) {
            tokens.add(Token(label, srv.register(StreamEntry(src, format, mime, label)), format, mime, kind))
        }

        source.m4aFile?.let { reg(StreamSource.FileSource(it), "mp4", "audio/mp4", "$input-file", "file") }
        reg(StreamSource.PipeSource(source.args), "adts", "audio/aac", "$input-adts-aac", "pipe")
        reg(StreamSource.PipeSource(source.args), "adts", "audio/x-aac", "$input-adts-xaac", "pipe")
        reg(StreamSource.PipeSource(source.args), "adts", "audio/mp4", "$input-adts-mp4", "pipe")
        reg(StreamSource.PipeSource(source.args), "mp3", "audio/mpeg", "$input-mp3", "pipe")

        val aac = File(dir, "$input.aac")
        val mp3 = File(dir, "$input.mp3")
        val builds = listOf(
            Triple(aac, listOf("-c:a", "copy", "-f", "adts"), Triple("adts", "audio/aac", "$input-adts-file")),
            Triple(mp3, listOf("-c:a", "libmp3lame", "-b:a", "192k", "-f", "mp3"), Triple("mp3", "audio/mpeg", "$input-mp3-file")),
        )
        val results = ArrayList<Map<String, Any?>>()
        for ((file, outArgs, meta) in builds) {
            file.delete()
            val code = ff.runToCompletion(listOf("-y") + source.args + outArgs + file.absolutePath, FILE_BUILD_TIMEOUT_SEC)
            val ok = code == 0 && file.length() > 0
            results.add(mapOf("label" to meta.third, "ok" to ok, "exitCode" to code, "bytes" to file.length()))
            if (ok) reg(StreamSource.FileSource(file), meta.first, meta.second, meta.third, "file")
        }
        writeTokens(h, input, srv, tokens, source.source)
        h.evidence.event("cmd-done", mapOf("cmd" to "pipe-test", "input" to input, "base" to base(srv), "tokens" to tokens.size, "fileBuilds" to results))
    }

    // ----- evidence ------------------------------------------------------------------------------------

    private data class Token(val label: String, val path: String, val format: String, val mime: String, val kind: String)

    private fun base(srv: MediaServer): String = "http://${boundIp}:${srv.resolvedPort()}"

    private fun writeTokens(h: SpikeHost, input: String, srv: MediaServer, tokens: List<Token>, source: String? = null) {
        val json = buildJsonObject {
            put("base", base(srv))
            put("input", input)
            // URL inputs are recorded by host only: the signed URL stays in files/spike (threat T-01-12)
            if (source != null) put("source", source)
            put(
                "tokens",
                buildJsonArray {
                    for (t in tokens) {
                        add(
                            buildJsonObject {
                                put("label", t.label)
                                put("path", t.path)
                                put("format", t.format)
                                put("mime", t.mime)
                                put("kind", t.kind)
                            },
                        )
                    }
                },
            )
        }
        val file = File(h.evidenceDir, "media-tokens-$input.json")
        writeAtomic(file, json.toString())
        lastTokenFile = file.name
    }

    private fun onRequest(h: SpikeHost, req: MediaRequest) {
        // the phone's own apps would reach the server through its Wi-Fi address or loopback
        val self = req.remoteHost == boundIp || req.remoteHost.startsWith("127.") || req.remoteHost == "::1"
        h.evidence.event(
            "http-req",
            mapOf(
                "server" to "media",
                "method" to req.method,
                "uri" to req.uri,
                "httpVersion" to req.httpVersion,
                "src" to "${req.remoteHost}:${req.remotePort}",
                "self" to self,
                "range" to req.range,
                "ua" to req.userAgent,
                "status" to req.status,
                "bytes" to req.bytes,
                "pipeMode" to req.pipeMode,
                "label" to req.label,
                "note" to req.note,
            ),
        )
    }

    // ----- helpers -------------------------------------------------------------------------------------

    /** Claims the single job slot or replies busy; the job runs on the service scope (Dispatchers.IO). */
    private fun startJob(label: String, block: () -> Unit): CommandReply {
        val h = host ?: return reply("not started")
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

    private fun reply(message: String) = CommandReply(false, buildJsonObject { put("error", message) }.toString())

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) target.writeText(text)
    }

    private companion object {
        const val PORT = 8100
        const val FILE_BUILD_TIMEOUT_SEC = 180L
        const val ERR_UNKNOWN = """{"error":"unknown command"}"""
        const val ERR_BUSY = """{"error":"busy"}"""
    }
}
