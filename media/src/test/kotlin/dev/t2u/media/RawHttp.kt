package dev.t2u.media

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket

/** Minimal raw HTTP client so tests see the exact status line, header lines and framing the server sends. */
class RawResponse(val status: Int, val headerLines: List<Pair<String, String>>, val body: ByteArray) {
    fun header(name: String): String? = headerLines.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headerCount(name: String): Int = headerLines.count { it.first.equals(name, ignoreCase = true) }
}

object RawHttp {
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** Reads the status line and headers; leaves [input] at the first body byte. */
    fun readHead(input: InputStream): Pair<Int, List<Pair<String, String>>> {
        val statusLine = readLine(input) ?: error("no status line")
        val status = statusLine.split(' ')[1].toInt()
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            headers.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return status to headers
    }

    fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)!!.substringBefore(';').trim().toInt(16)
            if (size == 0) {
                readLine(input)
                return out.toByteArray()
            }
            val buf = ByteArray(size)
            var got = 0
            while (got < size) {
                val n = input.read(buf, got, size - got)
                check(n >= 0) { "eof inside chunk" }
                got += n
            }
            out.write(buf)
            readLine(input)
        }
    }

    fun requestBytes(method: String, path: String, version: String, headers: Map<String, String>): ByteArray {
        val sb = StringBuilder("$method $path $version\r\nHost: 127.0.0.1\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** One request on a fresh connection; the body is read to the end according to the framing. */
    fun request(
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        version: String = "HTTP/1.1",
    ): RawResponse = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 30_000
        s.getOutputStream().apply {
            write(requestBytes(method, path, version, headers))
            flush()
        }
        val input = s.getInputStream().buffered()
        val (status, hdrs) = readHead(input)
        val te = hdrs.firstOrNull { it.first.equals("Transfer-Encoding", true) }?.second
        val cl = hdrs.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toLongOrNull()
        val body = when {
            method == "HEAD" -> ByteArray(0)
            te != null && te.contains("chunked", true) -> readChunked(input)
            cl != null -> input.readNBytes(cl.toInt())
            else -> input.readBytes()
        }
        RawResponse(status, hdrs, body)
    }
}
