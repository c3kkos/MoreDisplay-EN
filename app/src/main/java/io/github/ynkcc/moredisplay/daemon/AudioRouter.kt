package io.github.ynkcc.moredisplay.daemon

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
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
        private const val PKG = "android.media.audiopolicy"
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
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("no AudioManager")

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val ruleClass = Class.forName("$PKG.AudioMixingRule")
        val ruleBuilder = Class.forName("$PKG.AudioMixingRule\$Builder").getConstructor().newInstance()
        val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        val addMixRule = ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        uids.forEach { addMixRule.invoke(ruleBuilder, matchUid, it) }
        val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("$PKG.AudioMix")
        val loopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
        val mixBuilder = Class.forName("$PKG.AudioMix\$Builder").getConstructor(ruleClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBack)
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

        val policyBuilderClass = Class.forName("$PKG.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

        val policyClass = Class.forName("$PKG.AudioPolicy")
        val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy) as Int
        check(result == 0) { "registerAudioPolicy failed ($result)" }

        val record = try {
            (policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as AudioRecord)
                .also { it.startRecording() }
        } catch (e: Throwable) {
            unregister(audio, policy)
            throw e
        }

        val death = IBinder.DeathRecipient {
            Log.w(TAG, "cast app died; giving display $displayId's sound back to the speaker")
            stopIfCurrent(displayId, token)
        }
        runCatching { token.linkToDeath(death, 0) }.onFailure {
            record.release()
            unregister(audio, policy)
            throw it
        }

        val pump = Thread({ pump(displayId, record, pipe, token) }, "MoreDisplay-audio-$displayId")
        synchronized(lock) { sessions[displayId] = Session(policy, record, pump, pipe, token, death) }
        pump.start()
        Log.i(TAG, "display $displayId: sound of uids $uids -> cast")
        return uids.toList()
    }

    /** Puts the sound of [displayId]'s apps back on the speaker. */
    fun stop(displayId: Int): Boolean {
        val session = synchronized(lock) { sessions.remove(displayId) } ?: return false
        runCatching { session.token.unlinkToDeath(session.death, 0) }
        runCatching { session.record.stop() }
        session.pump.interrupt()
        runCatching { session.pipe.close() }
        runCatching { session.record.release() }
        context.getSystemService(AudioManager::class.java)?.let { unregister(it, session.policy) }
        Log.i(TAG, "display $displayId: sound back to the speaker")
        return true
    }

    private fun stopIfCurrent(displayId: Int, token: IBinder) {
        val current = synchronized(lock) { sessions[displayId]?.token === token }
        if (current) stop(displayId)
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

    private fun unregister(audio: AudioManager, policy: Any) {
        runCatching {
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audio, policy)
        }.recoverCatching {
            AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policy.javaClass).invoke(audio, policy)
        }.onFailure { Log.e(TAG, "unregisterAudioPolicy failed", it) }
    }
}
