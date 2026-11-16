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
    fun targetDuration_roundsToNearest_soHoldBackStaysInSpec() {
        // Key frames a hair late: every segment is 2.033 s (61 frames), like on the device.
        val seg = HlsSegmenter(hasAudio = false, targetUs = 2_000_000, keep = 12, window = 8, holdBackUs = 8_000_000)
        var pts = 5_000_000L
        for (s in 0 until 9) { // the 9th key frame closes the 8th segment
            for (i in 0 until 61) {
                seg.video(frame, pts, key = i == 0)
                pts += 33_333L
            }
        }
        val playlist = seg.playlist()!!
        assertTrue(playlist, playlist.contains("#EXTINF:2.033,"))
        assertTrue(playlist, playlist.contains("#EXT-X-TARGETDURATION:2\n"))
        // RFC 8216: HOLD-BACK must be at least three target durations.
        val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(playlist)!!.groupValues[1].toInt()
        assertTrue(8.0 >= 3 * target)
        assertEquals(7L, seg.newest())
    }

    @Test
    fun holdBack_isAnnouncedInThePlaylist() {
        val seg = HlsSegmenter(hasAudio = false, targetUs = 2_000_000, keep = 12, window = 8, holdBackUs = 8_000_000)
        feed(seg, 12)
        val playlist = seg.playlist()!!
        assertTrue(playlist, playlist.contains("#EXT-X-SERVER-CONTROL:HOLD-BACK=8.0\n"))
        assertTrue(playlist, playlist.contains("#EXT-X-START:TIME-OFFSET=-8.0\n"))
        assertEquals(5L, seg.made())
        // No hold-back: no tags.
        val plain = HlsSegmenter(hasAudio = false).also { feed(it, 3) }.playlist()!!
        assertTrue(plain, !plain.contains("HOLD-BACK") && !plain.contains("EXT-X-START"))
    }

    @Test
    fun castConfig_stabilitySettings() {
        val smooth = CastConfig()
        assertEquals(8_000_000L, smooth.holdBackUs)
        assertEquals(5, smooth.segmentsBeforePlay)
        // The player must be able to start hold-back behind the newest segment within what the playlist lists.
        assertTrue(smooth.playlistWindow * smooth.segmentUs > smooth.holdBackUs)
        assertTrue(smooth.segmentsKept >= smooth.playlistWindow)
        val low = CastConfig(smooth = false)
        assertTrue(low.playlistWindow * low.segmentUs > low.holdBackUs)
    }

    @Test
    fun castConfig_segmentLength() {
        assertEquals(2_000_000L, CastConfig().segmentUs) // Smooth is the default
        assertEquals(1_000_000L, CastConfig(smooth = false).segmentUs)
    }
}
