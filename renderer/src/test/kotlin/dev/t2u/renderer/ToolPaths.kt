package dev.t2u.renderer

import java.io.File

/** Where the tests find the real ffmpeg and ffprobe: the first one on PATH, else the usual Linux location (so a test that needs it is skipped, not broken, when it is absent). */
internal object ToolPaths {
    val ffmpeg: String by lazy { resolve("ffmpeg") }
    val ffprobe: String by lazy { resolve("ffprobe") }

    private fun resolve(name: String): String {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
        return (onPath ?: File("/usr/bin", name)).path
    }
}
