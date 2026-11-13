package dev.t2u.media

import java.io.File
import kotlin.system.exitProcess

/**
 * Local stand-in for the phone's `pipe-test input=sine`: serves the same token labels from WSL with
 * ffmpeg (from PATH), so `tools/media-check.sh --local` can prove the acceptance checks before the phone run.
 * Usage: LocalServeMain <output dir>. Prints READY after tokens.json is written, then blocks until killed.
 * Not a test; lives in the test source set so the main artifact stays free of it.
 */
private val FFMPEG = ToolPaths.ffmpeg

private fun ffmpeg(vararg args: String) {
    val p = ProcessBuilder(listOf(FFMPEG, "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
    val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "ffmpeg ${args.toList()} failed: $log" }
}

private fun json(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: LocalServeMain <output dir>")
        exitProcess(2)
    }
    val dir = File(args[0]).also { it.mkdirs() }
    val tone = File(dir, "tone.m4a")
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=30", "-c:a", "aac", "-b:a", "128k", "-f", "ipod", tone.absolutePath)
    val aac = File(dir, "sine.aac")
    val mp3 = File(dir, "sine.mp3")
    val input = listOf("-i", tone.absolutePath, "-vn")
    ffmpeg(*(input + listOf("-c:a", "copy", "-f", "adts", aac.absolutePath)).toTypedArray())
    ffmpeg(*(input + listOf("-c:a", "libmp3lame", "-b:a", "192k", "-f", "mp3", mp3.absolutePath)).toTypedArray())

    val buffer = File(dir, "buffer").also { it.mkdirs() }
    val server = MediaServer("127.0.0.1", 8100, JvmPipeLauncher(FFMPEG), { }, buffer)
    server.start()

    val pipeInput = listOf("-hide_banner", "-loglevel", "error", "-i", tone.absolutePath, "-vn")
    // never-ending source: lets the no-orphan check see a client hang up while ffmpeg is still running
    val endless = listOf("-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "sine=frequency=440:duration=600", "-vn")
    class Tok(val label: String, val path: String, val format: String, val mime: String, val kind: String)
    val tokens = ArrayList<Tok>()
    fun reg(src: StreamSource, format: String, mime: String, label: String, kind: String) {
        tokens.add(Tok(label, server.register(StreamEntry(src, format, mime, label)), format, mime, kind))
    }
    reg(StreamSource.FileSource(tone), "mp4", "audio/mp4", "sine-file", "file")
    reg(StreamSource.PipeSource(pipeInput), "adts", "audio/aac", "sine-adts-aac", "pipe")
    reg(StreamSource.PipeSource(pipeInput), "adts", "audio/x-aac", "sine-adts-xaac", "pipe")
    reg(StreamSource.PipeSource(pipeInput), "adts", "audio/mp4", "sine-adts-mp4", "pipe")
    reg(StreamSource.PipeSource(pipeInput), "mp3", "audio/mpeg", "sine-mp3", "pipe")
    reg(StreamSource.FileSource(aac), "adts", "audio/aac", "sine-adts-file", "file")
    reg(StreamSource.FileSource(mp3), "mp3", "audio/mpeg", "sine-mp3-file", "file")
    reg(StreamSource.PipeSource(endless), "mp3", "audio/mpeg", "endless-mp3", "pipe")

    val body = tokens.joinToString(",") {
        "{\"label\":${json(it.label)},\"path\":${json(it.path)},\"format\":${json(it.format)},\"mime\":${json(it.mime)},\"kind\":${json(it.kind)}}"
    }
    val out = File(dir, "tokens.json")
    File(dir, "tokens.json.tmp").also {
        it.writeText("{\"base\":\"http://127.0.0.1:${server.resolvedPort()}\",\"input\":\"sine\",\"tokens\":[$body]}")
        it.renameTo(out)
    }
    println("READY")
    System.out.flush()
    Thread.currentThread().join()
}
