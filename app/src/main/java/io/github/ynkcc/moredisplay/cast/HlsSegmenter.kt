package io.github.ynkcc.moredisplay.cast

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Cuts the encoded stream into short MPEG-TS segments and serves a live HLS playlist over them (sliding window).
 * A new segment starts at the first key frame after [targetUs]; the encoder makes a key frame every second.
 * The playlist lists the last [window] segments (6 s, so a Wi-Fi hiccup on the TV side doesn't stall it) and
 * [keep] segments stay downloadable for a player that is a little behind.
 * Pure Kotlin (unit-tested); thread-safe.
 *
 * Timestamps come in microseconds on any clock; they are rebased to start at 1 s in 90 kHz units.
 */
class HlsSegmenter(
    private val hasAudio: Boolean,
    private val targetUs: Long = 1_000_000,
    private val keep: Int = 12,
    private val window: Int = 6
) {
    class Segment(val sequence: Long, val durationUs: Long, val bytes: ByteArray)

    private val lock = Any()
    private val segments = ArrayDeque<Segment>()
    private var current: ByteArrayOutputStream? = null
    private var currentStartUs = 0L
    private var nextSequence = 0L
    private var baseUs: Long? = null
    private val muxer = TsMuxer(hasAudio) { packet -> current?.write(packet) }

    /** One encoded video frame (Annex-B). Frames before the first key frame are dropped. */
    fun video(annexB: ByteArray, ptsUs: Long, key: Boolean) = synchronized(lock) {
        if (key) {
            val cur = current
            // 10% tolerance: key frames come every ~1 s, a hair early or late
            if (cur == null || ptsUs - currentStartUs >= targetUs - targetUs / 10) {
                if (cur != null) close(ptsUs)
                if (baseUs == null) baseUs = ptsUs
                current = ByteArrayOutputStream(256 * 1024)
                currentStartUs = ptsUs
                muxer.writeTables()
            }
        }
        if (current == null) return@synchronized
        muxer.writeVideo(annexB, to90k(ptsUs) ?: return@synchronized, key)
    }

    /** One AAC frame with its ADTS header. Dropped until video has started. */
    fun audio(adtsFrame: ByteArray, ptsUs: Long) = synchronized(lock) {
        if (current == null || !hasAudio) return@synchronized
        muxer.writeAudio(adtsFrame, to90k(ptsUs) ?: return@synchronized)
    }

    /** Number of finished segments available. */
    fun ready(): Int = synchronized(lock) { segments.size }

    /** The live playlist, or null before the first segment is finished. */
    fun playlist(): String? = synchronized(lock) {
        if (segments.isEmpty()) return@synchronized null
        val shown = segments.toList().takeLast(window)
        val target = shown.maxOf { (it.durationUs + 999_999) / 1_000_000 }.coerceAtLeast(1)
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(shown.first().sequence).append('\n')
            for (s in shown) {
                append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationUs / 1_000_000.0)).append(",\n")
                append("seg").append(s.sequence).append(".ts\n")
            }
        }
    }

    fun segment(sequence: Long): ByteArray? = synchronized(lock) {
        segments.firstOrNull { it.sequence == sequence }?.bytes
    }

    private fun close(endUs: Long) {
        val bytes = current?.toByteArray() ?: return
        segments.addLast(Segment(nextSequence++, (endUs - currentStartUs).coerceAtLeast(1), bytes))
        while (segments.size > keep) segments.removeFirst()
        current = null
    }

    private fun to90k(ptsUs: Long): Long? {
        val base = baseUs ?: return null
        val rel = ptsUs - base
        if (rel < -900_000) return null // much older than the stream start: drop
        return 90_000 + rel * 9 / 100
    }
}
