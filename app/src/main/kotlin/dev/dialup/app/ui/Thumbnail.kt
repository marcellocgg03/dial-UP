// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Album art for the speaker cards (plan 07-06, backlog 999.5). The URL comes from track metadata, i.e. from the network
 * (T-07-07), so only https addresses are fetched, the client derives from [lanHttpClient] (guard on, redirects off, SVC-05),
 * the body is capped at [MAX_BYTES] and decoded downsampled. Every failure is a null, never an exception into the UI.
 * No image library is added; Coil would replace this object behind [TrackArt].
 */
object ThumbnailLoader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_SIDE_PX = 256

    private val client = lanHttpClient().newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = LruCache<String, ImageBitmap>(16)

    suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith("https://")) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = fetch(url) ?: return@withContext null
                decode(bytes)?.also { cache.put(url, it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val declared = body.contentLength()
            if (declared > MAX_BYTES) return null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

/** The 64 dp art square of a card: the track's image once loaded, otherwise an empty rounded square. */
@Composable
fun TrackArt(url: String?, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrEmpty()) null else ThumbnailLoader.load(url)
    }
    val shape = RoundedCornerShape(8.dp)
    val shown = image
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
