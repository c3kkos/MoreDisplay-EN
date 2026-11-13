package io.github.ynkcc.moredisplay.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TsMuxerTest {

    private class Parsed(val pid: Int, val start: Boolean, val cc: Int, val adaptation: ByteArray?, val payload: ByteArray)

    private fun parse(packets: List<ByteArray>): List<Parsed> = packets.map { p ->
        assertEquals(188, p.size)
        assertEquals(0x47, p[0].toInt() and 0xFF)
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val afc = (p[3].toInt() shr 4) and 3
        var pos = 4
        var af: ByteArray? = null
        if (afc and 2 != 0) {
            val len = p[4].toInt() and 0xFF
            af = p.copyOfRange(5, 5 + len)
            pos = 5 + len
        }
        Parsed(pid, p[1].toInt() and 0x40 != 0, p[3].toInt() and 0x0F, af, p.copyOfRange(pos, 188))
    }

    private fun pts(header: ByteArray, at: Int): Long =
        ((header[at].toLong() and 0x0E) shl 29) or
            ((header[at + 1].toLong() and 0xFF) shl 22) or
            ((header[at + 2].toLong() and 0xFE) shl 14) or
            ((header[at + 3].toLong() and 0xFF) shl 7) or
            ((header[at + 4].toLong() and 0xFE) shr 1)

    @Test
    fun crcMatchesKnownPat() {
        // The standard PAT for program 1 -> PMT 0x1000 has CRC 0x2AB104B2.
        val pat = byteArrayOf(0x00, 0xB0.toByte(), 13, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, 0xF0.toByte(), 0x00)
        assertEquals(0x2AB104B2, TsMuxer.crc32Mpeg(pat, 0, pat.size))
    }

    @Test
    fun tablesAreValidSections() {
        val packets = ArrayList<ByteArray>()
        TsMuxer(hasAudio = true) { packets += it }.writeTables()
        val parsed = parse(packets)
        assertEquals(listOf(0x0000, 0x1000), parsed.map { it.pid })
        for (p in parsed) {
            assertTrue(p.start)
            val section = p.payload.copyOfRange(1, p.payload.size) // skip pointer field
            val length = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
            // CRC over the whole section including its CRC is 0 for a valid section.
            assertEquals(0, TsMuxer.crc32Mpeg(section, 0, 3 + length))
        }
    }

    @Test
    fun videoRoundTripsWithPtsPcrAndContinuity() {
        val packets = ArrayList<ByteArray>()
        val muxer = TsMuxer(hasAudio = false) { packets += it }
        val frames = listOf(ByteArray(10) { 1 }, ByteArray(5000) { (it % 251).toByte() }, ByteArray(170) { 3 }, ByteArray(176) { 4 })
        frames.forEachIndexed { i, f -> muxer.writeVideo(f, 90_000L + i * 3_000, key = i == 0) }
        val parsed = parse(packets)
        assertTrue(parsed.all { it.pid == TsMuxer.PID_VIDEO })
        parsed.zipWithNext().forEach { (a, b) -> assertEquals((a.cc + 1) and 0xF, b.cc) }

        // Reassemble PES packets.
        val pes = ArrayList<ByteArrayOutputStream>()
        for (p in parsed) {
            if (p.start) {
                pes += ByteArrayOutputStream()
                assertNotNull(p.adaptation)
                val af = p.adaptation!!
                assertTrue("PCR flag on every access unit", af[0].toInt() and 0x10 != 0)
            }
            pes.last().write(p.payload)
        }
        assertEquals(frames.size, pes.size)
        pes.forEachIndexed { i, out ->
            val b = out.toByteArray()
            assertEquals(0xE0, b[3].toInt() and 0xFF)
            assertEquals(90_000L + i * 3_000, pts(b, 9))
            val body = b.copyOfRange(14, b.size)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte()), body.copyOfRange(0, 6))
            assertArrayEquals(frames[i], body.copyOfRange(6, body.size))
        }
        // Random access indicator only on the key frame.
        val firsts = parsed.filter { it.start }
        assertTrue(firsts[0].adaptation!![0].toInt() and 0x40 != 0)
        assertTrue(firsts[1].adaptation!![0].toInt() and 0x40 == 0)
    }

    @Test
    fun audioPesHasLength() {
        val packets = ArrayList<ByteArray>()
        val frame = TsMuxer.adtsHeader(300, 48_000, 2) + ByteArray(300)
        TsMuxer(hasAudio = true) { packets += it }.writeAudio(frame, 123_456)
        val parsed = parse(packets)
        assertTrue(parsed.all { it.pid == TsMuxer.PID_AUDIO })
        val pes = parsed.fold(ByteArray(0)) { acc, p -> acc + p.payload }
        val length = ((pes[4].toInt() and 0xFF) shl 8) or (pes[5].toInt() and 0xFF)
        assertEquals(8 + frame.size, length)
        assertEquals(123_456L, pts(pes, 9))
        assertArrayEquals(frame, pes.copyOfRange(14, 14 + frame.size))
    }

    @Test
    fun adtsHeaderFields() {
        val h = TsMuxer.adtsHeader(371, 48_000, 2)
        assertEquals(0xFFF, ((h[0].toInt() and 0xFF) shl 4) or ((h[1].toInt() and 0xF0) shr 4))
        val frameLength = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
        assertEquals(378, frameLength)
        assertEquals(3, (h[2].toInt() shr 2) and 0xF) // 48 kHz
    }

    @Test
    fun segmenterCutsOnKeyFramesAndSlides() {
        val seg = HlsSegmenter(hasAudio = true, targetUs = 1_000_000, keep = 3, window = 2)
        assertNull(seg.playlist())
        seg.video(ByteArray(100), 0, key = false) // dropped: no key frame yet
        var t = 5_000_000L
        repeat(5) { s ->
            repeat(30) { f ->
                seg.video(ByteArray(500), t, key = f == 0)
                seg.audio(TsMuxer.adtsHeader(10, 48_000, 2) + ByteArray(10), t)
                t += 33_333
            }
        }
        assertEquals(3, seg.ready()) // 4 finished, keep = 3
        val playlist = seg.playlist()!!
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:2"))
        assertTrue(playlist.contains("seg2.ts") && playlist.contains("seg3.ts"))
        assertTrue(!playlist.contains("seg1.ts"))
        assertNull(seg.segment(0))
        val bytes = seg.segment(3)!!
        assertEquals(0, bytes.size % 188)
        assertEquals(0x47, bytes[0].toInt() and 0xFF)
        assertEquals(0, ((bytes[1].toInt() and 0x1F) shl 8) or (bytes[2].toInt() and 0xFF)) // starts with PAT
    }
}
