package io.github.ynkcc.moredisplay.eye

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.ynkcc.moredisplay.R

/** Quick Settings tile: toggles the viewer on the last watched display. */
class EyeTileService : TileService() {

    companion object {
        /** Asks the system to refresh the tile (it only redraws while the shade is open). */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, EyeTileService::class.java))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = EyeSettings.isEnabled(EyeControl.QUICK_TILE)
        val watching = ViewerController.watching.value
        tile.label = "Eye"
        tile.icon = Icon.createWithResource(this, if (watching != null) R.drawable.ic_eye else R.drawable.ic_eye_off)
        tile.state = when {
            !enabled -> Tile.STATE_UNAVAILABLE
            watching != null -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !enabled -> "Off in Settings"
            watching != null -> "Showing #$watching"
            else -> EyeSettings.lastRawId()?.let { "Hidden · #$it" } ?: "No display yet"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (!EyeSettings.isEnabled(EyeControl.QUICK_TILE)) return
        val intent = EyeToggleActivity.intent(this, EyeControl.QUICK_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
