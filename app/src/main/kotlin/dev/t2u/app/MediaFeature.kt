// Owner: Plan 01-06 (ffmpeg pipes and the Ktor media server).
package dev.t2u.app

import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.StreamEntry
import dev.t2u.media.StreamSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

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
    override val commands: Set<String> = setOf("media-start", "media-stop")

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
        writeTokens(h, "sine", srv, listOf(Token("sine-file", path, "mp4", "audio/mp4")))
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

    // ----- evidence ------------------------------------------------------------------------------------

    private data class Token(val label: String, val path: String, val format: String, val mime: String)

    private fun base(srv: MediaServer): String = "http://${boundIp}:${srv.resolvedPort()}"

    private fun writeTokens(h: SpikeHost, input: String, srv: MediaServer, tokens: List<Token>) {
        val json = buildJsonObject {
            put("base", base(srv))
            put("input", input)
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
        const val ERR_UNKNOWN = """{"error":"unknown command"}"""
        const val ERR_BUSY = """{"error":"busy"}"""
    }
}
