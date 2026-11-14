package io.github.ynkcc.moredisplay.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    private val rate = 48_000
    private val bpf = 4
    private val chunk = rate / 50 * bpf // 20 ms

    /** Runs a simulated capture; returns the timestamps of every queued piece and the timeline. */
    private fun simulate(
        seconds: Int,
        startBacklogMs: Int = 0,
        rateFactor: Double = 1.0,
        silentFromMs: Int = Int.MAX_VALUE,
        silentToMs: Int = Int.MAX_VALUE
    ): Pair<List<Pair<Long, Long>>, AudioTimeline> {
        val t0 = 1_000_000_000L
        val tl = AudioTimeline(t0, rate, bpf)
        val pieces = ArrayList<Pair<Long, Long>>() // start, end
        fun queue(bytes: Int, silence: Boolean) {
            if (bytes <= 0) return
            val start = tl.nextUs
            tl.advance(bytes, silence)
            pieces += start to tl.nextUs
        }
        var arrivedBytes = 0L
        var pending = startBacklogMs * rate / 1_000 * bpf // backlog in the pipe at the start
        for (ms in 0..seconds * 1_000 step 5) {
            val now = t0 + ms * 1_000L
            val silent = ms in silentFromMs until silentToMs
            // The source delivers rate*factor samples per second, in 20 ms bursts.
            val due = (ms * rate * rateFactor / 1_000).toLong() * bpf
            if (!silent && due - arrivedBytes >= chunk) { pending += chunk; arrivedBytes += chunk }
            if (silent) arrivedBytes = due
            if (pending > 0) {
                val n = minOf(pending, chunk)
                pending -= n
                queue(tl.admit(n, now), silence = false)
            } else {
                queue(tl.silenceNeeded(now, chunk), silence = true)
            }
        }
        return pieces to tl
    }

    private fun assertContinuous(pieces: List<Pair<Long, Long>>) {
        for (i in 1 until pieces.size) {
            val gap = pieces[i].first - pieces[i - 1].second
            assertTrue("gap/overlap of $gap us at piece $i", gap == 0L)
        }
    }

    @Test
    fun steadySound_noCorrections() {
        val (pieces, tl) = simulate(10)
        assertContinuous(pieces)
        assertEquals(0, tl.silenceMs)
        assertEquals(0, tl.droppedMs)
    }

    @Test
    fun startupBacklog_isDropped_notStampedAhead() {
        val (pieces, tl) = simulate(5, startBacklogMs = 340)
        assertContinuous(pieces)
        // 340 ms of backlog: the track may start 100 ms behind the clock and run up to 100 ms ahead of it.
        assertTrue("dropped ${tl.droppedMs} ms", tl.droppedMs in 120..160)
        assertTrue(pieces.last().second <= 1_000_000_000L + 5_000_000L + 100_000L)
    }

    @Test
    fun silentApps_areFilledWithSilence() {
        val (pieces, tl) = simulate(10, silentFromMs = 3_000, silentToMs = 6_000)
        assertContinuous(pieces)
        assertTrue("silence ${tl.silenceMs} ms", tl.silenceMs in 2_800..3_100)
    }

    @Test
    fun slowAndFastSampleClocks_stayNearTheClock_withoutJumps() {
        for (factor in listOf(0.99, 1.01)) {
            val (pieces, tl) = simulate(60, rateFactor = factor)
            assertContinuous(pieces)
            val end = 1_000_000_000L + 60_000_000L
            assertTrue("factor $factor drifted to ${end - tl.nextUs} us", kotlin.math.abs(end - tl.nextUs) < 300_000)
        }
    }
}
