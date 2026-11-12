package io.github.ynkcc.moredisplay.provider

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.Transport
import io.github.ynkcc.moredisplay.dispatch.DaemonProvider
import io.github.ynkcc.moredisplay.shizuku.DaemonUserService
import io.github.ynkcc.moredisplay.shizuku.IDaemonRpc
import io.github.ynkcc.moredisplay.transport.ShizukuTransport
import rikka.shizuku.Shizuku

class LsposedDaemonProvider : DaemonProvider {
    override val privilege = Privilege.LSPOSED
    override val priority = 100

    override fun isAvailable(): Boolean =
        io.github.ynkcc.moredisplay.xposed.LsposedBridge.isInjected

    override suspend fun startDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun stopDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun connectTransport(): Result<Transport> = runCatching {
        io.github.ynkcc.moredisplay.transport.ProviderTransport(App.context)
    }
}

class ShizukuDaemonProvider : DaemonProvider {
    override val privilege = Privilege.SHELL_SHIZUKU
    override val priority = 50

    companion object {
        private const val TAG = "ShizukuProvider"
        private const val BIND_TIMEOUT_MS = 15_000L
    }

    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var serviceConnection: ServiceConnection? = null
    private var remote: IDaemonRpc? = null

    override fun isAvailable(): Boolean {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrElse {
            Log.d(TAG, "shizuku binder unavailable: ${it.message}")
            false
        }
        if (!binderAlive) return false
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse {
            Log.d(TAG, "shizuku permission check failed: ${it.message}")
            false
        }
        if (!granted) Log.d(TAG, "shizuku binder alive but permission not granted")
        return granted
    }

    override suspend fun startDaemon(): Result<Unit> = runCatching {
        val alive = runCatching { remote?.asBinder()?.isBinderAlive == true }.getOrDefault(false)
        if (alive) {
            Log.d(TAG, "shizuku daemon already bound")
            return@runCatching
        }
        val context: Context = App.context
        val args = userServiceArgs ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DaemonUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("daemon")
            .version(2)
            .also { userServiceArgs = it }

        detachLocked()

        remote = bind(args)
        if (!verifyDaemonVersion()) {
            Log.w(TAG, "daemon version mismatch, destroying stale daemon and rebinding")
            detachLocked()
            remote = bind(args)
            verifyDaemonVersion()
        }
        Log.i(TAG, "shizuku daemon bound, binder alive=${remote?.asBinder()?.isBinderAlive}")
    }

    /** Releases the current daemon connection: destroys the remote service and unbinds; tolerates repeated calls and dead binders. */
    private suspend fun detachLocked() {
        runCatching { remote?.destroy() }
            .onFailure { Log.d(TAG, "destroy stale daemon: ${it.message}") }
        remote = null
        val oldConnection = serviceConnection
        val oldArgs = userServiceArgs
        serviceConnection = null
        if (oldConnection != null && oldArgs != null) {
            withContext(Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(oldArgs, oldConnection, false) }
                    .onFailure { Log.d(TAG, "unbindUserService stale connection: ${it.message}") }
            }
        }
    }

    /** Binds the user service; transient failures are retried twice with 500ms/1s backoff. */
    private suspend fun bind(args: Shizuku.UserServiceArgs): IDaemonRpc {
        repeat(3) { attempt ->
            val connected = CompletableDeferred<IDaemonRpc>()
            lateinit var self: ServiceConnection
            self = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        connected.completeExceptionally(IllegalStateException("shizuku returned null binder for $name"))
                    } else {
                        connected.complete(IDaemonRpc.Stub.asInterface(binder))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "shizuku daemon service disconnected: $name")
                    // The old connection's disconnect callback may fire after the new connection is established; only clear remote
                    // when the disconnected connection is the currently registered one, so the new binding isn't hit by mistake.
                    if (self === serviceConnection) remote = null
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(args, self)
                }
                return withTimeout(BIND_TIMEOUT_MS) { connected.await() }
            } catch (e: Exception) {
                Log.w(TAG, "bind attempt ${attempt + 1} failed", e)
                withContext(Dispatchers.Main) {
                    runCatching { Shizuku.unbindUserService(args, self, false) }
                        .onFailure { Log.d(TAG, "unbindUserService failed attempt: ${it.message}") }
                }
                if (attempt == 2) throw e
                delay(500L * (attempt + 1))
            }
        }
        error("bind: unreachable")
    }

    private fun verifyDaemonVersion(): Boolean {
        val stub = remote ?: return false
        val response = runCatching {
            io.github.ynkcc.moredisplay.core.RpcCodec.unmarshallResponse(
                stub.invoke(io.github.ynkcc.moredisplay.core.RpcCodec.marshallRequest(
                    io.github.ynkcc.moredisplay.core.RpcRequest.Ping(0)
                ))
            )
        }.getOrElse {
            Log.e(TAG, "version probe failed", it)
            return false
        }
        val pong = response as? io.github.ynkcc.moredisplay.core.RpcResponse.Pong
        val actual = pong?.daemonVersion ?: -1
        val expected = io.github.ynkcc.moredisplay.core.DaemonProtocol.DAEMON_VERSION
        if (actual != expected) {
            Log.w(TAG, "daemon version mismatch: expected=$expected actual=$actual pid=${pong?.daemonPid}")
            return false
        }
        Log.i(TAG, "daemon version ok: v$actual pid=${pong?.daemonPid}")
        return true
    }

    override suspend fun stopDaemon(): Result<Unit> = runCatching {
        if (userServiceArgs == null) return@runCatching
        detachLocked()
        userServiceArgs = null
    }

    override suspend fun connectTransport(): Result<Transport> = runCatching {
        val binder = remote ?: error("shizuku daemon not started, call startDaemon first")
        ShizukuTransport(binder)
    }
}

class RootDaemonProvider : DaemonProvider {
    override val privilege = Privilege.ROOT
    override val priority = 10

    override fun isAvailable(): Boolean = false

    override suspend fun startDaemon(): Result<Unit> = TODO("adb/root: app_process launch kotlin daemon jar")

    override suspend fun stopDaemon(): Result<Unit> = TODO("kill root daemon process")

    override suspend fun connectTransport(): Result<Transport> = TODO("connect local abstract socket of root daemon")
}
