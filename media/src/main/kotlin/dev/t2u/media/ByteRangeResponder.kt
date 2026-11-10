package dev.t2u.media

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Read size of a body copy from a file or from upstream. */
internal const val FILE_CHUNK = 64 * 1024

/** The DLNA streaming headers every answer for a registered token carries. */
internal fun dlnaHeaders(call: ApplicationCall, entry: StreamEntry) {
    call.response.header("transferMode.dlna.org", "Streaming")
    call.response.header("contentFeatures.dlna.org", dlnaFeatures(entry.format))
    call.response.header(HttpHeaders.CacheControl, "no-store")
}

/**
 * Answers a call from an existing file: status, `Content-Range`, `Accept-Ranges`, the HEAD length, 416 for a range
 * past the end and the body bytes. One owner of the file framing, for file tokens, the cached range of a pipe run and
 * the buffered HTTP/1.0 path alike.
 */
internal class ByteRangeResponder {
    suspend fun respond(call: ApplicationCall, entry: StreamEntry, file: File, head: Boolean, out: Outcome) {
        val size = file.length()
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = if (rangeHeader != null) parseRange(rangeHeader, size) else null
        if (range is ByteRange.Unsatisfiable) {
            out.status = 416
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }
        val span = range as? ByteRange.Span ?: ByteRange.Span(0, size - 1)
        val length = if (size == 0L) 0 else span.end - span.start + 1
        val partial = range is ByteRange.Span
        val status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK

        dlnaHeaders(call, entry)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (partial) call.response.header(HttpHeaders.ContentRange, "bytes ${span.start}-${span.end}/$size")
        out.status = status.value
        val type = ContentType.parse(entry.mime)

        if (head) {
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val status: HttpStatusCode = status
                    override val contentType: ContentType = type
                    override val contentLength: Long = length
                },
            )
            return
        }
        call.respond(
            object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = status
                override val contentType: ContentType = type
                override val contentLength: Long = length

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(span.start)
                            var left = length
                            val buf = ByteArray(FILE_CHUNK)
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                channel.writeFully(buf, 0, n)
                                out.bytes += n
                                left -= n
                            }
                        }
                    }
                }
            },
        )
    }
}
