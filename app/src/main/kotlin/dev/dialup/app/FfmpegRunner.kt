package dev.dialup.app

import dev.t2u.media.JvmPipeLauncher
import dev.t2u.media.PipeLauncher
import dev.t2u.media.ProcessExit
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Starts the youtubedl-android ffmpeg (`libffmpeg.so` in nativeLibraryDir) with the environment it needs:
 *  - `LD_LIBRARY_PATH` = python lib dir + ffmpeg lib dir, because `libc++_shared.so` exists only in the python
 *    payload (RESEARCH Pitfall 5, youtubedl-android issue 369);
 *  - `SSL_CERT_FILE` = the python payload's cert bundle, so ffmpeg can read https inputs;
 *  - `TMPDIR` = [cacheDir].
 *
 * Arguments are always a list; there is no shell. Process bookkeeping (stderr tail, live set, exit
 * callback) is [JvmPipeLauncher]'s, shared with the pure-JVM tests. Each exit becomes an `ffmpeg` evidence
 * event with URLs reduced to their host and header arguments dropped (they carry signed query strings).
 */
class FfmpegRunner(
    paths: NativePayloads.Paths,
    cacheDir: File,
    private val evidence: EvidenceLog,
) : PipeLauncher {
    /** Process bookkeeping (stderr tail, live set, exit callback) is shared with the JVM tests. */
    private val launcher = JvmPipeLauncher(
        binary = paths.ffmpegBin.absolutePath,
        environment = mapOf(
            "LD_LIBRARY_PATH" to "${paths.pythonLibDir.absolutePath}:${paths.ffmpegLibDir.absolutePath}",
            "SSL_CERT_FILE" to paths.certFile.absolutePath,
            "TMPDIR" to cacheDir.absolutePath,
        ),
        onExit = ::logExit,
    )

    override fun start(args: List<String>): Process = launcher.start(args)

    override fun stderrTail(process: Process): String = launcher.stderrTail(process)

    /** Runs ffmpeg to the end and returns its exit code; kills it and returns -1 after [timeoutSec] seconds. */
    fun runToCompletion(args: List<String>, timeoutSec: Long): Int {
        val p = start(args)
        val finished = try {
            p.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            throw e
        }
        if (!finished) {
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
            return -1
        }
        return p.exitValue()
    }

    fun liveCount(): Int = launcher.liveCount()

    private fun logExit(exit: ProcessExit) {
        evidence.event(
            "ffmpeg",
            mapOf(
                "args" to redact(exit.args),
                "exitCode" to exit.exitCode,
                "ms" to exit.millis,
                "stderrTail" to exit.stderrTail.takeLast(512),
            ),
        )
    }

    companion object {
        /** Any http(s) URL becomes its host; the value after `-headers` is dropped (cookies, signed query). */
        fun redact(args: List<String>): List<String> {
            val out = ArrayList<String>(args.size)
            var afterHeaders = false
            for (a in args) {
                out.add(
                    when {
                        afterHeaders -> "[headers]"
                        a.startsWith("http://") || a.startsWith("https://") -> hostOf(a)
                        else -> a
                    },
                )
                afterHeaders = a == "-headers"
            }
            return out
        }

        private fun hostOf(url: String): String =
            try {
                URI(url).host?.let { "url:$it" } ?: "url:?"
            } catch (_: Exception) {
                "url:?"
            }
    }
}
