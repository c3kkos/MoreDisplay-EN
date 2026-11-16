package io.github.ynkcc.moredisplay.cast

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Cuts the encoded stream into short MPEG-TS segments and serves a live HLS playlist over them (sliding window).
 * A new segment starts at the first key frame after [targetUs]; the encoder makes a key frame every second.
 * Settings → Cast → Playback picks the length: 2 s (Smooth: players keep about three segments in reserve, so
 * longer segments ride out Wi-Fi hiccups) or 1 s (Low delay). The playlist lists the last [window] segments and
 * [keep] segments stay downloadable for a player that is a little behind.
 *
 * [holdBackUs] is how far behind the newest segment the player should play. A player at the newest edge can only
 * download a segment once it's finished, so it runs dry at every segment (a pause every segment length). The
 * playlist says so with the standard tags `EXT-X-SERVER-CONTROL:HOLD-BACK` and `EXT-X-START:TIME-OFFSET`
 * (players that don't know a tag ignore it); the cast also waits for enough segments before playing and
 * [CastController] moves a player that still pauses further back.
 * Pure Kotlin (unit-tested); thread-safe.
 *
 * Timestamps come in microseconds on any clock; they are rebased to start at 1 s in 90 kHz units.
 */
class HlsSegmenter(
    private val hasAudio: Boolean,
    private val targetUs: Long = 1_000_000,
    private val keep: Int = 12,
    private val window: Int = 6,
    private val holdBackUs: Long = 0,
    /**
     * Start-up burst: the playlist leaves out the newest [burstSegments] finished segments until [release]. Some
     * receivers (the Shield's Default Media Receiver) ignore `HOLD-BACK` and always play at the newest listed
     * segment, so they start with almost no reserve and pause until pauses have built one up. Listing the held
     * segments all at once, once the player has reached the (held-back) edge, hands it that reserve in one go.
     */
    burstSegments: Int = 0
) {
    class Segment(val sequence: Long, val durationUs: Long, val bytes: ByteArray)

    private val lock = Any()

    /** Newest finished segments left out of the playlist (0 once released). */
    private var held = burstSegments
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

    /** Segments finished since the start (diagnostics). */
    fun made(): Long = synchronized(lock) { nextSequence }

    /** Number of finished segments available. */
    fun ready(): Int = synchronized(lock) { segments.size }

    /** The live playlist, or null before the first segment is finished. */
    fun playlist(): String? = synchronized(lock) {
        val listed = segments.toList().dropLast(held)
        if (listed.isEmpty()) return@synchronized null
        val shown = listed.takeLast(window)
        val target = targetDuration(shown)
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            if (holdBackUs > 0) {
                val seconds = String.format(Locale.US, "%.1f", holdBackUs / 1_000_000.0)
                append("#EXT-X-SERVER-CONTROL:HOLD-BACK=").append(seconds).append('\n')
                append("#EXT-X-START:TIME-OFFSET=-").append(seconds).append('\n')
            }
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

    /** Sequence number of the newest finished segment, or -1 before the first (diagnostics). */
    fun newest(): Long = synchronized(lock) { segments.lastOrNull()?.sequence ?: -1 }

    /** Sequence number of the newest segment in the playlist (below [newest] while the burst is held), or -1. */
    fun newestListed(): Long = synchronized(lock) {
        segments.toList().dropLast(held).lastOrNull()?.sequence ?: -1
    }

    /** True while the start-up burst is still held back. */
    fun holding(): Boolean = synchronized(lock) { held > 0 }

    /**
     * Lists the held-back segments (see `burstSegments`). Returns how many segments that added and their length
     * in µs, or null if nothing was held.
     */
    fun release(): Pair<Int, Long>? = synchronized(lock) {
        if (held == 0) return@synchronized null
        val added = segments.toList().takeLast(held)
        held = 0
        added.size to added.sumOf { it.durationUs }
    }

    /**
     * `EXT-X-TARGETDURATION`: the longest segment *rounded to the nearest second* (RFC 8216 4.3.3.1), not rounded
     * up. Segments are cut at key frames, so a 2 s segment is often 2.033 s; rounding up made it 3. That matters:
     * players reload a live playlist about once per target duration (an edge player then waits 3 s for each 2 s
     * segment), and `HOLD-BACK` must be at least 3 target durations, so 8 s was out of spec with a target of 3.
     */
    private fun targetDuration(shown: List<Segment>): Long =
        shown.maxOf { (it.durationUs + 500_000) / 1_000_000 }.coerceAtLeast(1)

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
