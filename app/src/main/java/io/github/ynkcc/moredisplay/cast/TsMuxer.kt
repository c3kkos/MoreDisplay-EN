package io.github.ynkcc.moredisplay.cast

/**
 * Minimal MPEG transport stream writer for HLS: one program with H.264 video (PID 0x100, also the PCR PID) and
 * optional AAC/ADTS audio (PID 0x101). Pure Kotlin, no Android APIs, so it is unit-tested on the JVM.
 *
 * Timestamps are in 90 kHz units. Every video access unit gets an access unit delimiter and a PCR; key frames get
 * the random access indicator. Each segment must start with [writeTables].
 *
 * @param out receives whole 188-byte packets
 */
class TsMuxer(private val hasAudio: Boolean, private val out: (ByteArray) -> Unit) {

    companion object {
        const val PACKET_SIZE = 188
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor). */
        fun crc32Mpeg(data: ByteArray, from: Int, to: Int): Int {
            var crc = -1
            for (i in from until to) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        /** 7-byte ADTS header for one AAC-LC frame of [aacLength] bytes. */
        fun adtsHeader(aacLength: Int, sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
                .indexOf(sampleRate).also { require(it >= 0) { "unsupported sample rate $sampleRate" } }
            val profile = 1 // AAC LC (object type 2) - 1
            val frameLength = aacLength + 7
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, layer 0, no CRC
                ((profile shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (frameLength shr 11)).toByte(),
                ((frameLength shr 3) and 0xFF).toByte(),
                (((frameLength and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte()
            )
        }
    }

    private val continuity = HashMap<Int, Int>()

    /** PAT + PMT. Write them at the start of every segment so each segment is decodable on its own. */
    fun writeTables() {
        // PAT: program 1 -> PMT PID
        val pat = byteArrayOf(
            0x00, 0xB0.toByte(), 13, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte()
        )
        writeSection(PID_PAT, pat)

        val streams = ArrayList<Byte>()
        fun stream(type: Int, pid: Int) {
            streams += type.toByte()
            streams += (0xE0 or (pid shr 8)).toByte()
            streams += (pid and 0xFF).toByte()
            streams += 0xF0.toByte()
            streams += 0x00
        }
        stream(STREAM_TYPE_H264, PID_VIDEO)
        if (hasAudio) stream(STREAM_TYPE_AAC_ADTS, PID_AUDIO)
        val sectionLength = 9 + streams.size + 4
        val pmt = byteArrayOf(
            0x02, (0xB0 or (sectionLength shr 8)).toByte(), (sectionLength and 0xFF).toByte(),
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(), // PCR PID
            0xF0.toByte(), 0x00
        ) + streams.toByteArray()
        writeSection(PID_PMT, pmt)
    }

    /** One H.264 access unit in Annex-B form (SPS/PPS included before key frames by the caller). */
    fun writeVideo(annexB: ByteArray, pts90k: Long, key: Boolean) {
        val pes = pesHeader(0xE0, pts90k, payloadLength = AUD.size + annexB.size, unbounded = true) + AUD + annexB
        val pcr = (pts90k - 4_500).coerceAtLeast(0) // a little before the frame's PTS
        packetize(PID_VIDEO, pes, pcr = pcr, randomAccess = key)
    }

    /** One AAC frame with its ADTS header. */
    fun writeAudio(adtsFrame: ByteArray, pts90k: Long) {
        if (!hasAudio) return
        val pes = pesHeader(0xC0, pts90k, payloadLength = adtsFrame.size, unbounded = false) + adtsFrame
        packetize(PID_AUDIO, pes, pcr = null, randomAccess = false)
    }

    private fun pesHeader(streamId: Int, pts: Long, payloadLength: Int, unbounded: Boolean): ByteArray {
        val headerRest = 3 + 5 // flags(2) + header length(1) + PTS(5)
        val length = if (unbounded || payloadLength + headerRest > 0xFFFF) 0 else payloadLength + headerRest
        return byteArrayOf(
            0x00, 0x00, 0x01, streamId.toByte(),
            (length shr 8).toByte(), (length and 0xFF).toByte(),
            0x80.toByte(), // '10', no scrambling, not aligned
            0x80.toByte(), // PTS only
            0x05,
            (0x20 or ((pts shr 29).toInt() and 0x0E) or 1).toByte(),
            ((pts shr 22).toInt() and 0xFF).toByte(),
            (((pts shr 14).toInt() and 0xFE) or 1).toByte(),
            ((pts shr 7).toInt() and 0xFF).toByte(),
            (((pts shl 1).toInt() and 0xFE) or 1).toByte()
        )
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val crc = crc32Mpeg(section, 0, section.size)
        val body = byteArrayOf(0x00) + section + byteArrayOf( // pointer field + section + CRC
            (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()
        )
        val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        header(packet, pid, start = true, adaptation = false)
        System.arraycopy(body, 0, packet, 4, body.size)
        out(packet)
    }

    private fun packetize(pid: Int, data: ByteArray, pcr: Long?, randomAccess: Boolean) {
        var pos = 0
        var first = true
        while (pos < data.size) {
            val packet = ByteArray(PACKET_SIZE)
            val withPcr = first && pcr != null
            val withRai = first && randomAccess
            // Adaptation field content (after its length byte): flags + optional PCR.
            val flagsNeeded = withPcr || withRai
            var afContent = if (flagsNeeded) 1 + (if (withPcr) 6 else 0) else 0
            var hasAf = flagsNeeded
            val remaining = data.size - pos
            var space = PACKET_SIZE - 4 - (if (hasAf) 1 + afContent else 0)
            if (remaining < space) {
                // Fill the gap with adaptation-field stuffing.
                val stuff = space - remaining
                if (hasAf) {
                    afContent += stuff
                } else {
                    hasAf = true
                    afContent = stuff - 1 // the length byte itself takes one
                }
                space = remaining
            }
            header(packet, pid, start = first, adaptation = hasAf)
            var p = 4
            if (hasAf) {
                packet[p++] = afContent.toByte()
                if (afContent > 0) {
                    var flags = 0
                    if (withRai) flags = flags or 0x40
                    if (withPcr) flags = flags or 0x10
                    packet[p++] = flags.toByte()
                    var written = 1
                    if (withPcr) {
                        val base = pcr!!
                        packet[p++] = (base shr 25).toByte()
                        packet[p++] = (base shr 17).toByte()
                        packet[p++] = (base shr 9).toByte()
                        packet[p++] = (base shr 1).toByte()
                        packet[p++] = (((base and 1) shl 7) or 0x7E).toByte()
                        packet[p++] = 0x00
                        written += 6
                    }
                    while (written < afContent) {
                        packet[p++] = 0xFF.toByte()
                        written++
                    }
                }
            }
            System.arraycopy(data, pos, packet, p, space)
            pos += space
            first = false
            out(packet)
        }
    }

    private fun header(packet: ByteArray, pid: Int, start: Boolean, adaptation: Boolean) {
        val cc = continuity.getOrDefault(pid, 0)
        continuity[pid] = (cc + 1) and 0x0F
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8 and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = ((if (adaptation) 0x30 else 0x10) or cc).toByte()
    }
}
