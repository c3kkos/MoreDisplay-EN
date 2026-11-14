package io.github.ynkcc.moredisplay.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSegmenterTest {

    private val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)

    /** Feeds [seconds] of 30 fps video with a key frame every second (like the encoder). */
    private fun feed(seg: HlsSegmenter, seconds: Int) {
        for (i in 0 until seconds * 30) seg.video(frame, 5_000_000L + i * 33_333L, key = i % 30 == 0)
    }

    @Test
    fun lowDelay_cutsEverySecond() {
        val seg = HlsSegmenter(hasAudio = false, targetUs = 1_000_000, keep = 12, window = 6)
        feed(seg, 5)
        assertEquals(4, seg.ready()) // the 5th is still open
        val playlist = seg.playlist()!!
        assertTrue(playlist, playlist.contains("#EXT-X-TARGETDURATION:1\n"))
    }

    @Test
    fun smooth_cutsEveryTwoSeconds_andKeepsTheWindow() {
        val seg = HlsSegmenter(hasAudio = false, targetUs = 2_000_000, keep = 8, window = 5)
        feed(seg, 25)
        assertEquals(8, seg.ready()) // 12 finished, 8 kept
        val playlist = seg.playlist()!!
        assertTrue(playlist, playlist.contains("#EXT-X-TARGETDURATION:2\n"))
        assertEquals(5, Regex("#EXTINF:").findAll(playlist).count())
        assertTrue(playlist, playlist.contains("#EXT-X-MEDIA-SEQUENCE:7\n"))
        assertNotNull(seg.segment(11))
    }

    @Test
    fun castConfig_segmentLength() {
        assertEquals(2_000_000L, CastConfig().segmentUs) // Smooth is the default
        assertEquals(1_000_000L, CastConfig(smooth = false).segmentUs)
    }
}
