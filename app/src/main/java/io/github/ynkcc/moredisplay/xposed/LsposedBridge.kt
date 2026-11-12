package io.github.ynkcc.moredisplay.xposed

import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.transport.ProviderTransport

/**
 * Xposed module availability probe (Vector / LSPosed).
 *
 * Two situations count as "available":
 * 1. The current process was injected by the module -- flagged by `onPackageReady` when the module scope includes this app;
 * 2. The daemon engine inside system_server is ready -- only `system` needs to be in the scope,
 *    which is this project's recommended (and only required) scope; verified with a live Ping RPC.
 */
object LsposedBridge {

    @Volatile
    private var injected = false

    @Volatile
    private var daemonDetected = false

    fun markInjected() {
        injected = true
    }

    val isInjected: Boolean
        get() = injected || daemonDetected || probeDaemon()

    private fun probeDaemon(): Boolean {
        val context = runCatching { App.context }.getOrNull() ?: return false
        return ProviderTransport.ping(context).also { if (it) daemonDetected = true }
    }
}
