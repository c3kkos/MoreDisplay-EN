package io.github.ynkcc.moredisplay.eye

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.data.StateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * The single entry point of every eye control (card button, Quick Settings tile, widget, adb).
 *
 * Android shows one picture-in-picture window at a time, so there is one viewer: showing another display
 * switches the open viewer to it.
 */
object ViewerController {

    private val watchingFlow = MutableStateFlow<Int?>(null)

    /** The display shown in the viewer right now, or null. */
    val watching: StateFlow<Int?> = watchingFlow

    @Volatile
    private var viewer: WeakReference<ViewerActivity>? = null

    /**
     * Applies [request] to [displayId]. [context] must be allowed to start an activity (an Activity, or a
     * tile/widget trampoline). Returns a short human-readable result.
     */
    fun apply(context: Context, request: EyeRequest, displayId: Int): String =
        when (EyeLogic.decide(request, displayId, watchingFlow.value)) {
            EyeLogic.Action.SHOW -> {
                EyeSettings.rememberLast(displayId)
                context.startActivity(ViewerActivity.intent(context, displayId))
                "Eye on: showing display #$displayId"
            }
            EyeLogic.Action.HIDE -> {
                hide()
                "Eye off: display #$displayId hidden"
            }
            EyeLogic.Action.NOTHING ->
                if (request == EyeRequest.ON) "Already showing display #$displayId"
                else "Display #$displayId is not being shown"
        }

    /** Closes the viewer, sending the display's frames back to its sink. */
    fun hide() {
        val activity = viewer?.get() ?: return
        Handler(Looper.getMainLooper()).post { activity.finish() }
    }

    /** The display tile/widget controls act on: the last watched one, if it still exists. */
    suspend fun lastTarget(): Int? = EyeLogic.resolveTarget(
        explicitId = null,
        lastKey = EyeSettings.lastKey(),
        lastRawId = EyeSettings.lastRawId(),
        saved = StateStore.load(),
        managedIds = managedIds()
    )

    /** Ids of the displays MoreDisplay manages right now (connects first if needed). */
    suspend fun managedIds(): Set<Int> {
        val repo = App.displays()
        if (repo.connectionStatus.value != ConnectionStatus.CONNECTED) repo.connect()
        return repo.listDisplays().getOrNull()?.map { it.displayId }?.toSet() ?: emptySet()
    }

    internal fun onShowing(activity: ViewerActivity, displayId: Int) {
        viewer = WeakReference(activity)
        watchingFlow.value = displayId
        refreshControls(activity)
    }

    internal fun onClosed(activity: ViewerActivity) {
        if (viewer?.get() === activity) {
            viewer = null
            watchingFlow.value = null
        }
        refreshControls(activity)
    }

    /** Lets the Quick Settings tile and the widgets redraw their state. */
    fun refreshControls(context: Context) {
        EyeTileService.requestUpdate(context)
        EyeWidgetProvider.updateAll(context)
    }
}
