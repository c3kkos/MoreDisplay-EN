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
        private const val BURST_FALLBACK_MS = 30_000L
        // 4 s in beta3: the Shield then paused once at ~3 s, just before the release.
        private const val BURST_SETTLE_MS = 2_000L
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

    /** Segments sent, and the speed of the last one in Mbit/s (diagnostics: Wi-Fi from the tablet to the TV). */
    @Volatile
    var segmentsSent = 0
        private set

    @Volatile
    var lastSendMbps = 0f
        private set

    /**
     * How many finished segments were newer than the one the TV last fetched (diagnostics: the TV's real distance
     * from the newest edge, measured on our side; 0 = it fetched the newest, so it plays at the edge). -1 = none yet.
     */
    @Volatile
    var lastFetchBehind = -1L
        private set

    /** Time between the TV's last two playlist reloads in ms (diagnostics), -1 = fewer than two so far. */
    @Volatile
    var lastReloadGapMs = -1L
        private set

    @Volatile
    private var lastPlaylistAtMs = -1L

    @Volatile
    private var firstPlaylistAtMs = -1L

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
                    if (method == "GET") notePlaylist()
                }
                name.startsWith("seg") && name.endsWith(".ts") -> {
                    val sequence = name.removePrefix("seg").removeSuffix(".ts").toLongOrNull()
                    body = sequence?.let { segmenter.segment(it) }
                    type = "video/mp2t"
                    if (method == "GET" && sequence != null) noteSegment(sequence, found = body != null)
                }
                else -> {
                    body = null
                    type = "text/plain"
                }
            }
            if (body == null) {
                respond(out, 404, "text/plain", null)
            } else if (type == "video/mp2t" && method == "GET") {
                val t0 = System.nanoTime()
                respond(out, 200, type, body, length = body.size)
                // With linger, close() waits until the TV has received the data (up to 10 s), so the time is
                // the real transfer time, not just the copy into the socket buffer.
                runCatching { s.setSoLinger(true, 10) }
                runCatching { s.close() }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                segmentsSent++
                if (ms > 0) lastSendMbps = (body.size * 8 / 1_000.0 / ms).toFloat()
            } else {
                respond(out, 200, type, if (method == "HEAD") null else body, length = body.size)
            }
        }.onFailure { Log.d(TAG, "request failed: ${it.message}") }
    }

    // ---- Request log (tag MoreDisplay_Hls, info): what the TV fetches and when, measured on the tablet ----

    private fun notePlaylist() {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastPlaylistAtMs
        lastPlaylistAtMs = now
        if (previous >= 0) lastReloadGapMs = now - previous
        if (firstPlaylistAtMs < 0) firstPlaylistAtMs = now
        Log.i(TAG, "playlist reload (newest seg${segmenter.newest()}, listed up to seg${segmenter.newestListed()}, " +
            "${if (previous >= 0) "${now - previous} ms after the last" else "first"})")
        // Fallback: never hold the burst back for long, whatever the TV does.
        if (segmenter.holding() && now - firstPlaylistAtMs > BURST_FALLBACK_MS) releaseBurst("fallback after ${BURST_FALLBACK_MS / 1_000} s")
    }

    private fun noteSegment(sequence: Long, found: Boolean) {
        val behind = segmenter.newest() - sequence
        if (found) lastFetchBehind = behind
        Log.i(TAG, "fetch seg$sequence: ${if (found) "$behind newer segment(s) ready" else "not available (newest seg${segmenter.newest()})"}")
        // The TV fetched the newest listed segment: it's at the edge of the held-back playlist. List the held
        // segments now, so it gets their length as reserve in one go instead of building it up through pauses.
        // Not in the first seconds: the TV must have picked its start position (and started playing) first.
        val sinceFirst = android.os.SystemClock.elapsedRealtime() - firstPlaylistAtMs
        if (found && segmenter.holding() && firstPlaylistAtMs >= 0 && sinceFirst >= BURST_SETTLE_MS &&
            sequence >= segmenter.newestListed()
        ) releaseBurst("TV reached seg$sequence after ${sinceFirst} ms")
    }

    private fun releaseBurst(why: String) {
        val (count, us) = segmenter.release() ?: return
        Log.i(TAG, "start-up burst: $why; listing $count held segment(s), ${"%.1f".format(java.util.Locale.US, us / 1e6)} s of reserve")
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
