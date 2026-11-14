package io.github.ynkcc.moredisplay.daemon

/**
 * Marks MoreDisplay's own audio policy registration (cast sound) on the current thread.
 *
 * Inside system_server, `AudioService#isPolicyRegisterAllowed` checks MODIFY_AUDIO_ROUTING with
 * `Context#checkCallingPermission`, which always refuses a call that isn't an IPC from another process, so
 * system_server's own code can't register a policy although it holds the permission. The module hooks that method
 * (see `AudioPolicyHooks`) and allows the call only while [trusted] runs on this thread; every other caller is
 * checked by Android as usual. The registration is a direct, synchronous call on the same thread (AudioService is
 * a local binder in system_server), so a thread-local flag is exact.
 */
object AudioPolicyGate {
    private val active = ThreadLocal<Boolean>()

    fun isTrusted(): Boolean = active.get() == true

    fun <T> trusted(block: () -> T): T {
        active.set(true)
        try {
            return block()
        } finally {
            active.remove()
        }
    }
}
