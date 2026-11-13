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
    private var soundOn = false

    /** Why there's no sound on the TV, if there isn't. */
    var soundNote: String? = null
        private set

    /** The frame hub, for the eye to show the display while casting. */
    val frameHub: FrameHub? get() = hub

    /** Starts everything and waits for the first segments. Returns the URL for the Chromecast. */
    suspend fun start(): String {
        try {
            val address = localAddress(context)
                ?: throw IllegalStateException("Connect the tablet to the same Wi-Fi as the Chromecast")
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

            val segmenter = HlsSegmenter(hasAudio = pcmIn != null)
            val encoder = VideoEncoder(width, height, config.bitrateFor(width, height), config.fps) { data, pts, key ->
                segmenter.video(data, pts, key)
            }
            video = encoder
            audio = pcmIn?.let { pfd -> AudioEncoder(pfd) { frame, pts -> segmenter.audio(frame, pts) } }

            val frames = FrameHub(width, height).also { hub = it }
            frames.start()
            frames.setOutput("encoder", encoder.inputSurface, timestamps = true)
            encoder.start()
            audio?.start()

            repo.attachViewer(displayId, frames.inputSurface, token).getOrThrow()
            hubAttached = true

            server = HlsServer(segmenter, address).also { it.start() }
            withTimeoutOrNull(15_000) {
                while (segmenter.ready() < 2) delay(100)
            } ?: throw IllegalStateException("The video encoder produced no picture")
            Log.i(TAG, "display $displayId: ${encoder.width}x${encoder.height} streaming at ${server!!.playlistUrl}")
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
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(2_000) { repo.detachViewer(displayId) } }
        }
        if (soundOn) {
            soundOn = false
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(2_000) { repo.stopAudioCapture(displayId) } }
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
