package io.github.ynkcc.moredisplay.cast

import kotlin.math.abs

/**
 * Timestamps for the cast sound, so that the sound track has no holes, never goes backwards, and keeps in step
 * with the picture for hours.
 *
 * The TV's player plays only as far as it has both picture and sound, so a hole in the sound track makes it
 * pause. The sound reaches MoreDisplay in bursts, with a small backlog at the start, nothing while the apps are
 * silent, and on a sample clock that drifts slightly from the system clock used by the picture. So the time of
 * each sample is its position in one continuous track (`start + samples / rate`), kept close to the system clock
 * without ever jumping:
 * - sound arriving more than [maxAheadUs] ahead of the clock (backlog) is dropped;
 * - when no sound has come for [quietUs] (silent apps) and the track is more than [maxBehindUs] behind the clock,
 *   silence is added until it is [targetBehindUs] behind again. Not sooner: system_server's pump sometimes stalls
 *   for 100-200 ms and then delivers the sound in a burst, and silence added during such a stall would push all
 *   later sound back for good (the cause of sound drifting behind the picture over a long cast, in 1.x);
 * - **clock following** ([take]): Cast players (Shaka, which Google's receiver uses) ignore the sound's
 *   timestamps and play its samples back to back, so only the *number* of samples keeps sound and picture
 *   together. A sample clock off by 0.02% puts them 240 ms apart after 20 minutes. So once the arrival delay has
 *   settled, its low percentile over [windowUs] is compared with its value at the start; when it strays by more
 *   than [toleranceUs], each 20 ms piece is stretched or shrunk by a sample or two (linear interpolation,
 *   inaudible) until it is back. That follows clock errors up to 0.1% (0.2% when far off).
 *
 * Pure Kotlin (unit-tested); used from one thread. Bytes are 16-bit little-endian PCM frames.
 */
class AudioTimeline(
    startUs: Long,
    private val sampleRate: Int,
    private val bytesPerFrame: Int,
    private val targetBehindUs: Long = 100_000,
    private val maxBehindUs: Long = 200_000,
    private val maxAheadUs: Long = 100_000,
    private val toleranceUs: Long = 15_000,
    private val windowUs: Long = 5_000_000,
    private val settleUs: Long = 3_000_000,
    private val quietUs: Long = 500_000
) {
    private val startUs = startUs - targetBehindUs
    private var frames = 0L

    /** Silence added and sound dropped so far, in milliseconds (diagnostics). */
    var silenceMs = 0L
        private set
    var droppedMs = 0L
        private set

    /** Net samples added (+) or removed (-) by clock following, as milliseconds (diagnostics). */
    val clockMs: Long get() = clockFrames * 1_000 / sampleRate
    private var clockFrames = 0L

    /** The timestamp of the next sample. */
    val nextUs: Long get() = startUs + frames * 1_000_000L / sampleRate

    private fun durationUs(bytes: Int): Long = (bytes / bytesPerFrame) * 1_000_000L / sampleRate

    private fun bytesFor(us: Long): Int = ((us * sampleRate / 1_000_000L).toInt()) * bytesPerFrame

    // ---- Clock following state ----
    private var firstUs = Long.MIN_VALUE
    private var lastSoundUs = startUs
    private val lateTimes = ArrayDeque<Long>()
    private val lateValues = ArrayDeque<Long>()

    /** The arrival delay the track is kept at (learned once it has settled), or null before. */
    var referenceUs: Long? = null
        private set
    private var steering = 0

    /**
     * [len] bytes of sound from [buf] at [off] arrived at [nowUs]. Returns the bytes to queue at [nextUs]: the
     * backlog dropped ([admit]) and, to follow the clock, stretched or shrunk by a sample or two. Call [advance]
     * with the returned size once queued.
     */
    fun take(buf: ByteArray, off: Int, len: Int, nowUs: Long): ByteArray {
        if (firstUs == Long.MIN_VALUE) firstUs = nowUs
        val resuming = nowUs - lastSoundUs >= quietUs
        val whole = len - len % bytesPerFrame
        var keep = admit(whole, nowUs)
        if (keep == 0) return ByteArray(0)
        val reference = referenceUs
        if (resuming && reference != null) {
            // The apps make sound again after a silence: put it exactly where sound normally arrives, by adding
            // silence before it or dropping its start, so it carries on in step with the picture.
            lateTimes.clear()
            lateValues.clear()
            val offUs = nowUs - (nextUs + durationUs(keep)) - reference
            if (offUs > 0) {
                val pad = bytesFor(offUs)
                silenceMs += durationUs(pad) / 1_000
                return ByteArray(pad) + buf.copyOfRange(off + whole - keep, off + whole)
            }
            val cut = minOf(keep, bytesFor(-offUs))
            droppedMs += durationUs(cut) / 1_000
            keep -= cut
            if (keep == 0) return ByteArray(0)
        }
        val from = off + whole - keep
        val n = keep / bytesPerFrame
        val step = clockStep(nowUs, nowUs - (nextUs + durationUs(keep)))
        if (step == 0 || n < 8) return buf.copyOfRange(from, from + keep)
        clockFrames += step
        return resample(buf, from, n, n + step)
    }

    /** Records how late this piece arrived; returns the samples to add (+) or remove (-) now. */
    private fun clockStep(nowUs: Long, lateUs: Long): Int {
        lateTimes.addLast(nowUs)
        lateValues.addLast(lateUs)
        while (lateTimes.isNotEmpty() && nowUs - lateTimes.first() > windowUs) {
            lateTimes.removeFirst()
            lateValues.removeFirst()
        }
        if (nowUs - firstUs < settleUs + windowUs) {
            // Not settled yet: forget the start-up backlog, then take the reference from a full window.
            if (nowUs - firstUs < settleUs) { lateTimes.clear(); lateValues.clear() }
            return 0
        }
        if (lateValues.size < 20) return 0 // too little sound lately (silent apps) to judge
        val late = lowPercentile()
        val reference = referenceUs ?: late.also { referenceUs = it }
        val error = late - reference // > 0: the track falls behind the clock (slow sample clock)
        if (steering == 0 && abs(error) > toleranceUs) steering = if (error > 0) 1 else -1
        else if (steering != 0 && abs(error) < toleranceUs / 3) steering = 0
        // 1 sample per 20 ms piece follows clock errors up to 0.1%; far off (a broken clock) up to 1%.
        return steering * when {
            abs(error) > 6 * toleranceUs -> 10
            abs(error) > 4 * toleranceUs -> 2
            else -> 1
        }
    }

    /** The 20th percentile: the delay of the promptly delivered pieces, without the scheduling hiccups. */
    private fun lowPercentile(): Long {
        val sorted = lateValues.toLongArray().also { it.sort() }
        return sorted[sorted.size / 5]
    }

    /** Linear-interpolation resampling of [n] frames from [src] at [off] to [m] frames. */
    private fun resample(src: ByteArray, off: Int, n: Int, m: Int): ByteArray {
        val channels = bytesPerFrame / 2
        val out = ByteArray(m * bytesPerFrame)
        fun sample(frame: Int, ch: Int): Int {
            val i = off + frame * bytesPerFrame + ch * 2
            return (src[i].toInt() and 0xff) or (src[i + 1].toInt() shl 8)
        }
        val scale = (n - 1).toDouble() / (m - 1)
        for (j in 0 until m) {
            val pos = j * scale
            val i = pos.toInt().coerceAtMost(n - 1)
            val f = pos - i
            val i2 = (i + 1).coerceAtMost(n - 1)
            for (ch in 0 until channels) {
                val a = sample(i, ch)
                val v = (a + (sample(i2, ch) - a) * f).toInt().coerceIn(-32768, 32767)
                val o = j * bytesPerFrame + ch * 2
                out[o] = v.toByte()
                out[o + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    /**
     * [bytes] of sound arrived at [nowUs]. Returns how many of them to keep, from the end of the burst (the rest,
     * the oldest, is dropped). Call [advance] with the kept count once they are queued.
     */
    fun admit(bytes: Int, nowUs: Long): Int {
        val whole = bytes - bytes % bytesPerFrame
        if (whole > 0) lastSoundUs = nowUs
        val ahead = nextUs + durationUs(whole) - nowUs
        if (ahead <= maxAheadUs) return whole
        val drop = minOf(whole, bytesFor(ahead - maxAheadUs) + bytesPerFrame - 1).let { it - it % bytesPerFrame }
        droppedMs += durationUs(drop) / 1_000
        return whole - drop
    }

    /** Bytes of silence to add now (0 if none), at most [maxBytes]. Call [advance] with the count once queued. */
    fun silenceNeeded(nowUs: Long, maxBytes: Int): Int {
        if (nowUs - lastSoundUs < quietUs) return 0 // late sound (a stalled pump), not silence
        val behind = nowUs - nextUs
        if (behind <= maxBehindUs) return 0
        val bytes = minOf(maxBytes, bytesFor(behind - targetBehindUs))
        return bytes - bytes % bytesPerFrame
    }

    /** [bytes] (sound or silence) were queued at [nextUs]. */
    fun advance(bytes: Int, silence: Boolean = false) {
        frames += bytes / bytesPerFrame
        if (silence) silenceMs += durationUs(bytes) / 1_000
    }
}
