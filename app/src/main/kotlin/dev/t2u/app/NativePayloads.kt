package dev.t2u.app

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Extracts the youtubedl-android python and ffmpeg payloads once and reports where everything lives.
 * Extraction is heavy (about 50 MB unzipped on first run) so it runs only when a command asks for it,
 * never in the service's onCreate: the DIAL receivers must announce first.
 */
object NativePayloads {
    data class Paths(
        val nativeLibDir: File,
        val ffmpegBin: File,
        val pythonLibDir: File,
        val ffmpegLibDir: File,
        val certFile: File,
    ) {
        /** `LD_LIBRARY_PATH` value for spawning libffmpeg.so (libc++_shared.so is only in the python payload). */
        fun ldLibraryPath(): String = "${pythonLibDir.absolutePath}:${ffmpegLibDir.absolutePath}"

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "nativeLibDir" to nativeLibDir.absolutePath,
            "ffmpegBin" to ffmpegBin.absolutePath,
            "ffmpegBinExists" to ffmpegBin.exists(),
            "pythonLibDir" to pythonLibDir.absolutePath,
            "pythonLibDirExists" to pythonLibDir.isDirectory,
            "ffmpegLibDir" to ffmpegLibDir.absolutePath,
            "ffmpegLibDirExists" to ffmpegLibDir.isDirectory,
            "certFile" to certFile.absolutePath,
            "certFileExists" to certFile.isFile,
        )

        fun toJson(): JsonObject = buildJsonObject {
            for ((k, v) in toMap()) {
                when (v) {
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
    }

    @Volatile
    private var cached: Paths? = null

    @Synchronized
    fun ensure(context: Context, evidence: EvidenceLog): Paths {
        check(Looper.myLooper() != Looper.getMainLooper()) { "NativePayloads.ensure must not run on the main thread" }
        cached?.let { return it }

        val app = context.applicationContext
        timed(evidence, "youtubedl-init") { YoutubeDL.getInstance().init(app) }
        timed(evidence, "ffmpeg-init") { FFmpeg.getInstance().init(app) }

        val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
        val root = app.noBackupFilesDir
        val paths = Paths(
            nativeLibDir = nativeLibDir,
            ffmpegBin = File(nativeLibDir, "libffmpeg.so"),
            pythonLibDir = File(root, "youtubedl-android/packages/python/usr/lib"),
            ffmpegLibDir = File(root, "youtubedl-android/packages/ffmpeg/usr/lib"),
            certFile = File(root, "youtubedl-android/packages/python/usr/etc/tls/cert.pem"),
        )
        evidence.event("payload-init", mapOf("step" to "paths") + paths.toMap())
        cached = paths
        return paths
    }

    private inline fun timed(evidence: EvidenceLog, step: String, block: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            block()
            evidence.event("payload-init", mapOf("step" to step, "ok" to true, "ms" to SystemClock.elapsedRealtime() - t0))
        } catch (t: Throwable) {
            evidence.event(
                "payload-init",
                mapOf("step" to step, "ok" to false, "ms" to SystemClock.elapsedRealtime() - t0, "error" to t.javaClass.name, "message" to t.message),
            )
            throw t
        }
    }
}
