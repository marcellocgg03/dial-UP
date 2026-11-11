package dev.dialup.app

import java.io.File

/** Android can reclaim this directory, and its Clear cache action removes it. Move the old persistent files once. */
@Synchronized
internal fun mediaBufferDir(context: android.content.Context): File {
    val dir = File(context.cacheDir, "media-buffer")
    dev.t2u.media.migrateMediaBufferDir(File(context.filesDir, "media-buffer"), dir)
    return dir
}
