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
        silentToMs: Int = Int.MAX_VALUE,
        stallFromMs: Int = Int.MAX_VALUE,
        stallToMs: Int = Int.MAX_VALUE,
        lateness: MutableList<Pair<Int, Long>>? = null
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
            val due = (ms.toLong() * rate * rateFactor / 1_000).toLong() * bpf
            if (!silent && due - arrivedBytes >= chunk) { pending += chunk; arrivedBytes += chunk }
            if (silent) arrivedBytes = due
            if (pending > 0 && ms !in stallFromMs until stallToMs) {
                val n = minOf(pending, chunk)
                pending -= n
                val start = tl.nextUs
                queue(tl.take(ByteArray(n), 0, n, now).size, silence = false)
                lateness?.add(ms to now - tl.nextUs)
                if (tl.nextUs < start) error("went backwards")
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

    @Test
    fun stalledPump_addsNoSilence() {
        // system_server's pump stalls for 150 ms, then delivers in a burst: that's late sound, not silence.
        val (pieces, tl) = simulate(20, stallFromMs = 10_000, stallToMs = 10_150)
        assertContinuous(pieces)
        assertEquals(0, tl.silenceMs)
        assertEquals(0, tl.droppedMs)
    }

    @Test
    fun smallClockErrors_areFollowed_withinTolerance() {
        // 0.05% off: 300 ms apart after 10 minutes without clock following.
        for (factor in listOf(0.9995, 1.0005)) {
            val late = ArrayList<Pair<Int, Long>>()
            val (pieces, tl) = simulate(600, rateFactor = factor, lateness = late)
            assertContinuous(pieces)
            val reference = tl.referenceUs!!
            val worst = late.filter { it.first > 60_000 }.maxOf { kotlin.math.abs(it.second - reference) }
            assertTrue("factor $factor: $worst us from the reference", worst < 40_000)
            assertTrue("factor $factor: clock ${tl.clockMs} ms", if (factor > 1) tl.clockMs < -200 else tl.clockMs > 200)
            assertEquals(0, tl.silenceMs)
        }
    }

    @Test
    fun soundAfterSilence_carriesOnInStep() {
        val late = ArrayList<Pair<Int, Long>>()
        val (pieces, tl) = simulate(40, silentFromMs = 15_000, silentToMs = 25_000, lateness = late)
        assertContinuous(pieces)
        val reference = tl.referenceUs!!
        val worst = late.filter { it.first in 25_100..40_000 }.maxOf { kotlin.math.abs(it.second - reference) }
        assertTrue("$worst us from the reference after the silence", worst < 30_000)
    }
}
