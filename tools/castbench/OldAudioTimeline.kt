package castbench

/**
 * Timestamps for the cast sound, so that the sound track has no holes and never goes backwards.
 *
 * The TV's player plays only as far as it has both picture and sound, so a hole in the sound track makes it
 * pause. The sound reaches MoreDisplay in bursts, with a small backlog at the start, nothing while the apps are
 * silent, and on a sample clock that drifts slightly from the system clock used by the picture. So the time of
 * each sample is its position in one continuous track (`start + samples / rate`), kept close to the system clock
 * without ever jumping:
 * - sound arriving more than [maxAheadUs] ahead of the clock (backlog, a fast sample clock) is dropped;
 * - when the track falls more than [maxBehindUs] behind the clock (silent apps, a slow sample clock), silence is
 *   added until it is [targetBehindUs] behind again.
 *
 * Pure Kotlin (unit-tested); used from one thread.
 */
class OldAudioTimeline(
    startUs: Long,
    private val sampleRate: Int,
    private val bytesPerFrame: Int,
    private val targetBehindUs: Long = 100_000,
    private val maxBehindUs: Long = 200_000,
    private val maxAheadUs: Long = 100_000
) {
    private val startUs = startUs - targetBehindUs
    private var frames = 0L

    /** Silence added and sound dropped so far, in milliseconds (diagnostics). */
    var silenceMs = 0L
        private set
    var droppedMs = 0L
        private set

    /** The timestamp of the next sample. */
    val nextUs: Long get() = startUs + frames * 1_000_000L / sampleRate

    private fun durationUs(bytes: Int): Long = (bytes / bytesPerFrame) * 1_000_000L / sampleRate

    private fun bytesFor(us: Long): Int = ((us * sampleRate / 1_000_000L).toInt()) * bytesPerFrame

    /**
     * [bytes] of sound arrived at [nowUs]. Returns how many of them to keep, from the end of the burst (the rest,
     * the oldest, is dropped). Call [advance] with the kept count once they are queued.
     */
    fun admit(bytes: Int, nowUs: Long): Int {
        val whole = bytes - bytes % bytesPerFrame
        val ahead = nextUs + durationUs(whole) - nowUs
        if (ahead <= maxAheadUs) return whole
        val drop = minOf(whole, bytesFor(ahead - maxAheadUs) + bytesPerFrame - 1).let { it - it % bytesPerFrame }
        droppedMs += durationUs(drop) / 1_000
        return whole - drop
    }

    /** Bytes of silence to add now (0 if none), at most [maxBytes]. Call [advance] with the count once queued. */
    fun silenceNeeded(nowUs: Long, maxBytes: Int): Int {
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
