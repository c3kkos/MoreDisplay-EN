package io.github.ynkcc.moredisplay.daemon

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream

/**
 * Cast sound (system_server only): takes the sound of some apps off the device's speaker and writes it, as raw
 * PCM (48 kHz, stereo, 16-bit), into a pipe that the MoreDisplay app reads and encodes for the Chromecast.
 *
 * Uses Android's dynamic audio policy (the mechanism behind cast/projection audio): one `AudioMix` matching the
 * apps' uids with route flag LOOP_BACK only, so their sound goes to the capture **instead of** the speaker. These
 * are system APIs (`android.media.audiopolicy`), reached by reflection; system_server holds
 * `MODIFY_AUDIO_ROUTING`. Apps that forbid capture of their sound are respected by Android: nothing is captured.
 *
 * Unregistering the policy (stop, the app's pipe closing, or the app dying, via [token]) puts the sound back on
 * the speaker.
 */
class AudioRouter(private val context: Context) {

    companion object {
        private const val TAG = "MoreDisplay_Audio"
        const val SAMPLE_RATE = 48_000
    }

    private class Session(
        val policy: Any,
        val record: AudioRecord,
        val pump: Thread,
        val pipe: ParcelFileDescriptor,
        val token: IBinder,
        val death: IBinder.DeathRecipient
    )

    private val lock = Any()
    private val sessions = HashMap<Int, Session>()

    /** Starts capturing [uids] for [displayId] into [pipe]. Returns the uids actually routed. */
    fun start(displayId: Int, uids: Set<Int>, pipe: ParcelFileDescriptor, token: IBinder): List<Int> {
        require(uids.isNotEmpty()) {
            "no app is set to \"Open on\" display $displayId, so there is no sound to cast"
        }
        stop(displayId)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val capture = HiddenApi.captureUidsLoopback(context, uids, format)
        val policy = capture.policy
        val record = capture.record

        val death = IBinder.DeathRecipient {
            Log.w(TAG, "cast app died; giving display $displayId's sound back to the speaker")
            stopIfCurrent(displayId, token)
        }
        runCatching { token.linkToDeath(death, 0) }.onFailure {
            record.release()
            HiddenApi.unregisterAudioPolicy(context, policy)
            throw it
        }

        val pump = Thread({ pump(displayId, record, pipe, token) }, "MoreDisplay-audio-$displayId")
        synchronized(lock) { sessions[displayId] = Session(policy, record, pump, pipe, token, death) }
        pump.start()
        Log.i(TAG, "display $displayId: sound of uids $uids -> cast")
        return uids.toList()
    }

    /** Puts the sound of [displayId]'s apps back on the speaker (only [token]'s session, if given). */
    fun stop(displayId: Int, token: IBinder? = null): Boolean {
        val session = synchronized(lock) {
            val current = sessions[displayId] ?: return false
            if (token != null && current.token !== token) return false
            sessions.remove(displayId)
        } ?: return false
        runCatching { session.token.unlinkToDeath(session.death, 0) }
        runCatching { session.record.stop() }
        session.pump.interrupt()
        runCatching { session.pipe.close() }
        runCatching { session.record.release() }
        HiddenApi.unregisterAudioPolicy(context, session.policy)
        Log.i(TAG, "display $displayId: sound back to the speaker")
        return true
    }

    private fun stopIfCurrent(displayId: Int, token: IBinder) {
        stop(displayId, token)
    }

    private fun pump(displayId: Int, record: AudioRecord, pipe: ParcelFileDescriptor, token: IBinder) {
        val buffer = ByteArray(3_840) // 20 ms
        runCatching {
            FileOutputStream(pipe.fileDescriptor).use { out ->
                while (!Thread.currentThread().isInterrupted) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
        }.onFailure { Log.i(TAG, "display $displayId: audio pipe closed (${it.message})") }
        stopIfCurrent(displayId, token)
    }
}
