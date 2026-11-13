package io.github.ynkcc.moredisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings back the saved displays and policies after a reboot.
 *
 * Connecting the repository is all it takes: a successful connect recreates the saved displays and
 * re-applies the saved policies (see DisplayRepository.restoreSavedState). A few retries cover the case
 * where the system_server side is not answering yet right after boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Stay inside the ~10 s a broadcast receiver is given.
                withTimeoutOrNull(9_000) {
                    repeat(ATTEMPTS) { attempt ->
                        val result = App.displays().connect()
                        if (result.isSuccess) {
                            Log.i(TAG, "boot restore done (attempt ${attempt + 1})")
                            return@withTimeoutOrNull
                        }
                        Log.w(TAG, "boot connect failed (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
                        delay(RETRY_DELAY_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "boot restore failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MoreDisplay_Boot"
        const val ATTEMPTS = 4
        const val RETRY_DELAY_MS = 2_000L
    }
}
