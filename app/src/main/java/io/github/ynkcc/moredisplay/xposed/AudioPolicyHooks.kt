package io.github.ynkcc.moredisplay.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.AudioPolicyGate
import java.util.concurrent.atomic.AtomicLong

/**
 * Lets MoreDisplay's cast sound register its audio policy from inside system_server.
 *
 * `AudioService#isPolicyRegisterAllowed(AudioPolicyConfig, boolean, boolean, IMediaProjection)` checks
 * MODIFY_AUDIO_ROUTING with `Context#checkCallingPermission`, which refuses any call that isn't an IPC from another
 * process (checked in the Android 12L source). The hook answers `true` only while [AudioPolicyGate] marks the
 * module's own registration on the current thread; every other call goes to Android's check unchanged.
 */
private const val TAG = "MoreDisplay_AudioPolicy"
private val allowed = AtomicLong()

@Volatile
private var audioPolicyHookInstalled = "false"

fun XposedModule.installAudioPolicyHooks(classLoader: ClassLoader) {
    runCatching {
        val service = classLoader.loadClass("com.android.server.audio.AudioService")
        val methods = service.declaredMethods.filter {
            it.name == "isPolicyRegisterAllowed" && it.returnType == java.lang.Boolean.TYPE
        }
        check(methods.isNotEmpty()) { "AudioService.isPolicyRegisterAllowed not found" }
        methods.forEach { method ->
            hook(method).intercept { chain ->
                if (AudioPolicyGate.isTrusted()) {
                    val n = allowed.incrementAndGet()
                    Log.i(TAG, "MoreDisplay cast sound: audio policy registration allowed (#$n)")
                    true
                } else {
                    chain.proceed()
                }
            }
        }
        audioPolicyHookInstalled = "true(${methods.size})"
        log(Log.INFO, TAG, "audio policy hook installed on ${methods.size} method(s)")
    }.onFailure {
        audioPolicyHookInstalled = "false"
        log(Log.ERROR, TAG, "audio policy hook failed", it)
    }
}

fun audioPolicyHookStats(): String = "audioPolicy(installed=$audioPolicyHookInstalled allowed=${allowed.get()})"
