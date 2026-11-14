package io.github.ynkcc.moredisplay.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.ynkcc.moredisplay.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Everything on the tablet side of one cast: display frames → [FrameHub] → [VideoEncoder] (+ [AudioEncoder]) →
 * [HlsSegmenter] → [HlsServer]. The virtual display itself is never touched; only its output is switched to the
 * hub, as the eye does.
 */
class CastPipeline(
    private val context: Context,
    val displayId: Int,
    private val width: Int,
    private val height: Int,
    private val config: CastConfig
) {
    companion object {
        private const val TAG = "MoreDisplay_Cast"

        /** The tablet's IPv4 address on Wi-Fi (or Ethernet), which the Chromecast can reach. */
        fun localAddress(context: Context): InetAddress? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val networks = if (cm == null) emptyList() else listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
            for (network in networks.distinct()) {
                val caps = cm?.getNetworkCapabilities(network) ?: continue
                cm ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) continue
                cm.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.let { return it }
            }
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            }.getOrNull()
        }
    }

    private val token = Binder()
    private var hub: FrameHub? = null
    private var video: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var server: HlsServer? = null
    private var hubAttached = false
    private var segmenter: HlsSegmenter? = null

    /** Requests the Chromecast made to the stream server so far. */
    val streamRequests: Int get() = server?.requests ?: 0

    /**
     * Where the picture got to, for error messages and the log: frames from the display, frames drawn into the
     * encoder (repeats included), frames and key frames encoded, finished segments, Chromecast requests.
     */
    fun diagnostics(): String =
        "display frames ${hub?.framesIn ?: 0}, drawn ${hub?.framesDrawn ?: 0}, " +
            "encoded ${video?.encodedFrames ?: 0}, key frames ${video?.keyFrames ?: 0}, " +
            "segments ${segmenter?.ready() ?: 0}, requests ${server?.requests ?: 0}"
    private var soundOn = false

    /** Why there's no sound on the TV, if there isn't. */
    var soundNote: String? = null
        private set


    /**
     * Starts everything and waits for the first segments. Returns the URL for the Chromecast.
     *
     * [onHubAttached] runs as soon as the display's picture goes to the frame hub (before the wait), so an open
     * eye can switch to the hub right away instead of freezing.
     */
    suspend fun start(onHubAttached: (FrameHub) -> Unit = {}): String {
        try {
            val address = localAddress(context)
                ?: throw CastException(CastError.NO_WIFI, "connect the tablet to the same Wi-Fi as the Chromecast")
            val repo = App.displays()

            // Sound first: it decides whether the stream has an audio track.
            var pcmIn: ParcelFileDescriptor? = null
            if (config.sound) {
                val pipe = ParcelFileDescriptor.createPipe()
                val result = repo.startAudioCapture(displayId, pipe[1], token)
                runCatching { pipe[1].close() } // our copy of the write end; system_server keeps its own
                result.onSuccess {
                    pcmIn = pipe[0]
                    soundOn = true
                }.onFailure {
                    runCatching { pipe[0].close() }
                    soundNote = it.message
                    Log.w(TAG, "no cast sound: ${it.message}")
                }
            } else {
                soundNote = "Sound is turned off in Settings → Cast"
            }

            val segmenter = HlsSegmenter(
                hasAudio = pcmIn != null,
                targetUs = config.segmentUs,
                keep = if (config.smooth) 8 else 12,
                window = if (config.smooth) 5 else 6
            ).also { this.segmenter = it }
            val bitrate = config.bitrateFor(width, height)
            val encoder = try {
                VideoEncoder(width, height, bitrate, config.fps) { data, pts, key -> segmenter.video(data, pts, key) }
                    .also { it.start() }
            } catch (e: Exception) {
                throw CastException(
                    CastError.ENCODER_START,
                    "the video encoder refused ${width}x$height @ ${config.fps} fps, ${bitrate / 1_000} kbit/s " +
                        "(${e.javaClass.simpleName}: ${e.message})", e
                )
            }
            video = encoder
            audio = pcmIn?.let { pfd -> AudioEncoder(pfd) { frame, pts -> segmenter.audio(frame, pts) } }
            audio?.start()

            val frames = FrameHub(width, height, config.fps).also { hub = it }
            try {
                frames.start()
                frames.setOutput("encoder", encoder.inputSurface, timestamps = true)
            } catch (e: Exception) {
                throw CastException(CastError.GPU, "the picture copier (OpenGL) failed: ${e.message}", e)
            }

            repo.attachViewer(displayId, frames.inputSurface, token).onFailure {
                throw CastException(CastError.DISPLAY_OUTPUT, "couldn't take display #$displayId's picture: ${it.message}", it)
            }
            hubAttached = true
            onHubAttached(frames)

            server = HlsServer(segmenter, address).also { it.start() }
            withTimeoutOrNull(20_000) {
                while (segmenter.ready() < 2) delay(100)
            } ?: throw CastException(CastError.NO_PICTURE, "no video after 20 s (${diagnostics()})")
            Log.i(TAG, "display $displayId: ${encoder.width}x${encoder.height} streaming at ${server!!.playlistUrl} (${diagnostics()})")
            return server!!.playlistUrl
        } catch (e: Throwable) {
            stop()
            throw e
        }
    }

    fun stop() {
        val repo = App.displays()
        if (hubAttached) {
            hubAttached = false
            repo.detachViewerLater(displayId, token) // tied to this cast's token: never hits a newer viewer
        }
        if (soundOn) {
            soundOn = false
            repo.stopAudioCaptureLater(displayId, token)
        }
        server?.stop()
        audio?.stop()
        video?.stop()
        hub?.release()
        server = null
        audio = null
        video = null
        hub = null
        Log.i(TAG, "display $displayId: cast pipeline stopped")
    }
}
