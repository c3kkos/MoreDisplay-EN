package io.github.ynkcc.moredisplay.eye

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.ynkcc.moredisplay.R

/** Home-screen widget: an eye that toggles the viewer on the last watched display. */
class EyeWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Redraws every eye widget on the home screen. */
        fun updateAll(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, EyeWidgetProvider::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            }
        }

        private fun views(context: Context): RemoteViews {
            val enabled = EyeSettings.isEnabled(EyeControl.WIDGET)
            val watching = ViewerController.watching.value
            val label = when {
                !enabled -> "Off in Settings"
                watching != null -> "#$watching"
                else -> EyeSettings.lastRawId()?.let { "#$it" } ?: "No display"
            }
            val click = PendingIntent.getActivity(
                context, 1,
                EyeToggleActivity.intent(context, EyeControl.WIDGET),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(context.packageName, R.layout.widget_eye).apply {
                setImageViewResource(R.id.eye_icon, if (watching != null) R.drawable.ic_eye else R.drawable.ic_eye_off)
                setTextViewText(R.id.eye_label, label)
                setOnClickPendingIntent(R.id.eye_root, click)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, views(context))
    }
}
