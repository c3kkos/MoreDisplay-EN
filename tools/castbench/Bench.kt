package castbench

import io.github.ynkcc.moredisplay.cast.HlsSegmenter
import io.github.ynkcc.moredisplay.cast.HlsServer
import java.io.File
import java.net.InetAddress

/**
 * Cast bench: plays the test clip (see make-clip.sh) in real time through the app's real HlsSegmenter, TsMuxer
 * and HlsServer (Smooth settings, start-up burst), served on 127.0.0.1 for the receiver page.
 *
 * Usage: Bench <video.h264> <audio.aac> [audio drift ms per minute] [minutes]
 *   The drift option shifts the sound's timestamps on purpose, to check that the receiver measures drift.
 */
fun main(args: Array<String>) {
    val driftMsPerMin = args.getOrNull(2)?.toDouble() ?: 0.0
    val minutes = args.getOrNull(3)?.toDouble() ?: 30.0
    val video = splitAccessUnits(File(args[0]).readBytes())
    val audio = splitAdts(File(args[1]).readBytes())
    println("clip: ${video.size} video frames (${video.count { it.second }} key), ${audio.size} AAC frames")

    val seg = HlsSegmenter(
        hasAudio = true, targetUs = 2_000_000, keep = 12, window = 8, holdBackUs = 8_000_000, burstSegments = 3
    )
    val server = HlsServer(seg, InetAddress.getByName("127.0.0.1"))
    server.start()
    println("PLAYLIST ${server.playlistUrl}")

    val t0 = System.nanoTime() / 1_000 + 200_000
    val endUs = t0 + (minutes * 60_000_000).toLong()
    var vi = 0
    var ai = 0
    var nextStats = t0 + 10_000_000
    while (vi < video.size) {
        val vPts = t0 + vi * 100_000L / 3 // 30 fps
        val aBase = t0 + ai * 1024L * 1_000_000L / 48_000
        val aPts = aBase + ((aBase - t0) / 60_000_000.0 * driftMsPerMin * 1_000).toLong()
        val now = System.nanoTime() / 1_000
        if (vPts > endUs) break
        // Hand frames over once their time has come, in timestamp order, like the encoders do.
        when {
            ai < audio.size && aPts <= vPts && aPts <= now -> { seg.audio(audio[ai], aPts); ai++ }
            vPts <= now -> { seg.video(video[vi].first, vPts, video[vi].second); vi++ }
            else -> Thread.sleep(2)
        }
        if (now >= nextStats) {
            nextStats += 10_000_000
            println(
                "stats ${(now - t0) / 1_000_000}s: segments ${seg.made()}, requests ${server.requests}, " +
                    "sent ${server.segmentsSent}, TV fetching ${server.lastFetchBehind} behind, " +
                    "reloads ${server.lastReloadGapMs} ms apart"
            )
        }
    }
    println("done")
    Thread.sleep(30_000)
    server.stop()
}

/** Splits an Annex-B stream at access unit delimiters; returns (access unit, is key frame). */
fun splitAccessUnits(data: ByteArray): List<Pair<ByteArray, Boolean>> {
    val nals = ArrayList<Pair<Int, Int>>() // (start code offset, nal type)
    var i = 0
    while (i + 3 < data.size) {
        if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
            val sc = when {
                data[i + 2].toInt() == 1 -> 3
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> 4
                else -> 0
            }
            if (sc > 0 && i + sc < data.size) {
                nals += i to (data[i + sc].toInt() and 0x1f)
                i += sc
                continue
            }
        }
        i++
    }
    val units = ArrayList<Pair<ByteArray, Boolean>>()
    var start = -1
    var key = false
    for ((off, type) in nals) {
        if (type == 9) {
            if (start >= 0) units += data.copyOfRange(start, off) to key
            start = off
            key = false
        }
        if (type == 5) key = true
    }
    if (start >= 0) units += data.copyOfRange(start, data.size) to key
    return units
}

/** Splits an ADTS stream into frames (header included). */
fun splitAdts(data: ByteArray): List<ByteArray> {
    val frames = ArrayList<ByteArray>()
    var i = 0
    while (i + 7 <= data.size) {
        val len = ((data[i + 3].toInt() and 3) shl 11) or ((data[i + 4].toInt() and 0xff) shl 3) or
            ((data[i + 5].toInt() and 0xe0) ushr 5)
        if (len < 7 || i + len > data.size) break
        frames += data.copyOfRange(i, i + len)
        i += len
    }
    return frames
}
