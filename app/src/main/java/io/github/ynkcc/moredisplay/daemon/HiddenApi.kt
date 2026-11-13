package io.github.ynkcc.moredisplay.daemon

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import android.view.InputEvent
import java.lang.reflect.Method

/**
 * Every hidden Android API the daemons call by reflection, in one place, with the differences between Android
 * versions and one log tag (`MoreDisplay_HiddenApi`). Used from system_server (Vector) and from the Shizuku shell
 * service, both of which may call them. Lookups are cached; a failure throws with the API's name in the message.
 *
 * Not here: the virtual display creation in the engines and the system_server hooks, which are tied to their own
 * code paths.
 */
internal object HiddenApi {
    private const val TAG = "MoreDisplay_HiddenApi"

    private fun <T> api(name: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        Log.w(TAG, "$name failed: ${cause.javaClass.simpleName}: ${cause.message}")
        throw IllegalStateException("$name: ${cause.message ?: cause.javaClass.simpleName}", cause)
    }

    // ---- Displays ----

    /**
     * Every logical display id that exists right now (`DisplayManagerGlobal`).
     * Android 14+: `getDisplayIds(boolean includeDisabled)`; Android 10–13: `getDisplayIds()`.
     */
    fun displayIds(): IntArray = api("DisplayManagerGlobal.getDisplayIds") {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("no DisplayManagerGlobal")
        val methods = global.javaClass.methods.filter { it.name == "getDisplayIds" && it.returnType == IntArray::class.java }
        val withFlag = methods.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE }
        val noArgs = methods.firstOrNull { it.parameterCount == 0 }
        when {
            withFlag != null -> withFlag.invoke(global, false)
            noArgs != null -> noArgs.invoke(global)
            else -> throw NoSuchMethodException("getDisplayIds() / getDisplayIds(Z)")
        } as IntArray
    }

    // ---- Input ----

    private const val INJECT_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC

    /** Android 14+: `InputManagerGlobal`; Android 10–13: `InputManager.getInstance()`. */
    private val injector: Pair<Any, Method> by lazy {
        val global = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
        val instance = (global ?: Class.forName("android.hardware.input.InputManager"))
            .getMethod("getInstance").invoke(null) ?: throw IllegalStateException("no InputManager instance")
        instance to instance.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private val setDisplayIdMethod: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /** Injects [event] into [displayId] (needs INJECT_EVENTS: system_server and shell have it). */
    fun injectInput(event: InputEvent, displayId: Int): Boolean = api("injectInputEvent") {
        setDisplayIdMethod.invoke(event, displayId)
        injector.second.invoke(injector.first, event, INJECT_ASYNC) as Boolean
    }

    // ---- Tasks ----

    private fun activityTaskManager(): Any =
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            ?: throw IllegalStateException("no ActivityTaskManager service")

    /** Moves [taskId] (and its display) to the top, giving it input focus. Needs MANAGE_ACTIVITY_TASKS. */
    fun setFocusedTask(taskId: Int) = api("setFocusedTask") {
        val atm = activityTaskManager()
        atm.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType).invoke(atm, taskId)
        Unit
    }

    /** The first visible root task on [displayId], or null (`getAllRootTaskInfosOnDisplay`, Android 12+). */
    fun topTaskOn(displayId: Int): Int? = api("getAllRootTaskInfosOnDisplay") {
        val atm = activityTaskManager()
        val tasks = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            .invoke(atm, displayId) as List<*>
        tasks.firstOrNull { info ->
            info != null && (runCatching { info.javaClass.getField("visible").getBoolean(info) }.getOrNull()
                ?: runCatching { info.javaClass.getField("isVisible").getBoolean(info) }.getOrDefault(true))
        }?.let { it.javaClass.getField("taskId").getInt(it) }
    }

    // ---- Audio policy (system APIs in android.media.audiopolicy; needs MODIFY_AUDIO_ROUTING) ----

    private const val AUDIO = "android.media.audiopolicy"

    /** A registered dynamic audio policy with one loop-back mix, and its capture. */
    class LoopbackCapture(val policy: Any, val record: AudioRecord)

    /**
     * Routes the sound of [uids] into a capture **instead of** the speaker (loop-back only), in [format].
     * Unregister with [unregisterAudioPolicy].
     */
    fun captureUidsLoopback(context: Context, uids: Set<Int>, format: AudioFormat): LoopbackCapture =
        api("audio policy loop-back capture") {
            val audio = context.getSystemService(AudioManager::class.java) ?: throw IllegalStateException("no AudioManager")

            val ruleClass = Class.forName("$AUDIO.AudioMixingRule")
            val ruleBuilder = Class.forName("$AUDIO.AudioMixingRule\$Builder").getConstructor().newInstance()
            val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            val addMixRule = ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            uids.forEach { addMixRule.invoke(ruleBuilder, matchUid, it) }
            val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

            val mixClass = Class.forName("$AUDIO.AudioMix")
            val loopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val mixBuilder = Class.forName("$AUDIO.AudioMix\$Builder").getConstructor(ruleClass).newInstance(rule)
            mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBack)
            val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

            val policyBuilderClass = Class.forName("$AUDIO.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

            val policyClass = Class.forName("$AUDIO.AudioPolicy")
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy) as Int
            check(result == 0) { "registerAudioPolicy failed ($result)" }

            val record = try {
                (policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as? AudioRecord
                    ?: throw IllegalStateException("createAudioRecordSink returned null"))
                    .also { it.startRecording() }
            } catch (e: Throwable) {
                unregisterAudioPolicy(context, policy)
                throw e
            }
            LoopbackCapture(policy, record)
        }

    /** Unregisters a policy from [captureUidsLoopback] (the apps' sound goes back to the speaker). */
    fun unregisterAudioPolicy(context: Context, policy: Any) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audio, policy)
        }.recoverCatching {
            AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policy.javaClass).invoke(audio, policy)
        }.onFailure { Log.e(TAG, "unregisterAudioPolicy failed", it) }
    }
}
