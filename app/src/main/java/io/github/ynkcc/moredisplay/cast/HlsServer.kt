package io.github.ynkcc.moredisplay.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Tiny HTTP server for the Chromecast: serves the live playlist and the segments of one [HlsSegmenter] under a
 * random, per-session path, on the Wi-Fi address only. Sends the CORS headers the Cast player requires.
 */
class HlsServer(private val segmenter: HlsSegmenter, private val address: InetAddress) {

    companion object {
        private const val TAG = "MoreDisplay_Hls"
    }

    private val token = UUID.randomUUID().toString().replace("-", "")
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, 0))
    }
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "MoreDisplay-hls").apply { isDaemon = true } }
    private val acceptor = Thread({ acceptLoop() }, "MoreDisplay-hls-accept").apply { isDaemon = true }

    /** Requests received (diagnostics: 0 means the Chromecast never reached the tablet). */
    @Volatile
    var requests = 0
        private set

    /** The playlist URL to give the Chromecast. */
    val playlistUrl: String = "http://${address.hostAddress}:${socket.localPort}/$token/live.m3u8"

    fun start() = acceptor.start()

    fun stop() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { pool.execute { serve(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) = client.use { s ->
        runCatching {
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return@runCatching
            while (true) { // skip the request headers
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            requests++
            Log.d(TAG, "request: $requestLine")
            val parts = requestLine.split(' ')
            if (parts.size < 2) return@runCatching respond(s.getOutputStream(), 400, "text/plain", null)
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val out = s.getOutputStream()
            if (method == "OPTIONS") return@runCatching respond(out, 204, null, null)
            if (method != "GET" && method != "HEAD") return@runCatching respond(out, 405, "text/plain", null)
            val prefix = "/$token/"
            if (!path.startsWith(prefix)) return@runCatching respond(out, 404, "text/plain", null)
            val name = path.removePrefix(prefix)
            val body: ByteArray?
            val type: String
            when {
                name == "live.m3u8" -> {
                    body = segmenter.playlist()?.toByteArray()
                    type = "application/vnd.apple.mpegurl"
                }
                name.startsWith("seg") && name.endsWith(".ts") -> {
                    body = name.removePrefix("seg").removeSuffix(".ts").toLongOrNull()?.let { segmenter.segment(it) }
                    type = "video/mp2t"
                }
                else -> {
                    body = null
                    type = "text/plain"
                }
            }
            if (body == null) respond(out, 404, "text/plain", null)
            else respond(out, 200, type, if (method == "HEAD") null else body, length = body.size)
        }.onFailure { Log.d(TAG, "request failed: ${it.message}") }
    }

    private fun respond(out: OutputStream, code: Int, type: String?, body: ByteArray?, length: Int = body?.size ?: 0) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Method Not Allowed"
        }
        val headers = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            if (type != null) append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Cache-Control: no-cache, no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8_192) return null
            sb.append(c.toChar())
        }
    }
}
