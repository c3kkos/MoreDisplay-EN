package io.github.ynkcc.moredisplay.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.FileInputStream

private const val TAG = "MoreDisplay_Encoder"

/**
 * Hardware H.264 encoder fed by a surface. Emits Annex-B access units; key frames (one per second) carry the
 * SPS/PPS in front so every HLS segment can be decoded on its own. When the display doesn't change, the encoder
 * repeats the last frame so the stream keeps flowing.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    bitrate: Int,
    fps: Int,
    private val onFrame: (annexB: ByteArray, ptsUs: Long, key: Boolean) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    /** Encoded size (the display's size, rounded down to even numbers if the encoder needs it). */
    val width: Int
    val height: Int
    val inputSurface: Surface

    @Volatile
    private var running = true
    private var config: ByteArray? = null
    private val drain = Thread({ drainLoop() }, "MoreDisplay-video-enc")

    init {
        var w = width and 1.inv()
        var h = height and 1.inv()
        val configured = runCatching { configure(w, h, bitrate, fps) }.recoverCatching {
            // Some encoders only accept sizes that are multiples of 16.
            Log.w(TAG, "encoder refused ${w}x$h (${it.message}); trying a multiple of 16")
            codec.reset()
            w = width and 15.inv()
            h = height and 15.inv()
            configure(w, h, bitrate, fps)
        }
        configured.getOrThrow()
        this.width = w
        this.height = h
        inputSurface = codec.createInputSurface()
    }

    private fun configure(w: Int, h: Int, bitrate: Int, fps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        codec.start()
        drain.start()
    }

    fun stop() {
        running = false
        runCatching { codec.signalEndOfInputStream() }
        runCatching { drain.join(1_000) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = runCatching { codec.dequeueOutputBuffer(info, 20_000) }.getOrElse { break }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val frame = if (key) (config ?: ByteArray(0)) + bytes else bytes
                    runCatching { onFrame(frame, info.presentationTimeUs, key) }
                        .onFailure { Log.e(TAG, "video frame sink failed", it) }
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }
}

/**
 * AAC-LC encoder for the cast sound: reads 48 kHz stereo 16-bit PCM from the pipe filled by system_server and
 * emits ADTS frames. When no sound comes (nothing playing), it encodes silence so the stream never stalls.
 * Timestamps follow the same clock as the video (`System.nanoTime`).
 */
class AudioEncoder(
    private val pipe: ParcelFileDescriptor,
    private val onFrame: (adts: ByteArray, ptsUs: Long) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * 2
        private const val CHUNK = BYTES_PER_SECOND / 50 // 20 ms
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @Volatile
    private var running = true
    private val input = FileInputStream(pipe.fileDescriptor)
    private val feeder = Thread({ feedLoop() }, "MoreDisplay-audio-in")
    private val drain = Thread({ drainLoop() }, "MoreDisplay-audio-enc")

    fun start() {
        codec.start()
        feeder.start()
        drain.start()
    }

    fun stop() {
        running = false
        runCatching { pipe.close() }
        runCatching { feeder.join(1_000) }
        runCatching { drain.join(1_000) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun feedLoop() {
        val chunk = ByteArray(CHUNK)
        var anchorUs = System.nanoTime() / 1_000
        var samples = 0L // per channel, since anchorUs
        while (running) {
            val nowUs = System.nanoTime() / 1_000
            val producedUs = anchorUs + samples * 1_000_000 / SAMPLE_RATE
            val available = runCatching { input.available() }.getOrElse { break }
            var n = 0
            if (available >= 4) {
                n = runCatching { input.read(chunk, 0, minOf(available, chunk.size) and 3.inv()) }.getOrElse { break }
                if (n < 0) break
            }
            if (n == 0) {
                if (nowUs - producedUs > 200_000) {
                    // Nothing playing for 200 ms: 20 ms of silence keeps the stream going.
                    chunk.fill(0)
                    n = CHUNK
                } else {
                    Thread.sleep(5)
                    continue
                }
            }
            var ptsUs = producedUs
            if (kotlin.math.abs(ptsUs - nowUs) > 300_000) {
                // The sound clock drifted from the video clock: re-anchor on it.
                anchorUs = nowUs
                samples = 0
                ptsUs = nowUs
            }
            queue(chunk, n, ptsUs)
            samples += n / (CHANNELS * 2)
        }
        runCatching {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun queue(data: ByteArray, length: Int, ptsUs: Long) {
        var offset = 0
        while (offset < length && running) {
            val index = runCatching { codec.dequeueInputBuffer(20_000) }.getOrElse { return }
            if (index < 0) continue
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            val count = minOf(buffer.remaining(), length - offset)
            buffer.put(data, offset, count)
            val pts = ptsUs + (offset / (CHANNELS * 2)) * 1_000_000L / SAMPLE_RATE
            codec.queueInputBuffer(index, 0, count, pts, 0)
            offset += count
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = runCatching { codec.dequeueOutputBuffer(info, 20_000) }.getOrElse { break }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val aac = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(aac)
                val frame = TsMuxer.adtsHeader(aac.size, SAMPLE_RATE, CHANNELS) + aac
                runCatching { onFrame(frame, info.presentationTimeUs) }
                    .onFailure { Log.e(TAG, "audio frame sink failed", it) }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }
}
