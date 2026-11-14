package io.github.ynkcc.moredisplay.eye

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.SizeF
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

        /**
         * The widget for the current state. On Android 12+ the launcher picks the layout by the widget's size:
         * 1x1 (eye 40dp) or 2x2 and larger (eye 72dp); older Android always uses the 1x1 layout.
         */
        private fun views(context: Context): RemoteViews {
            val small = views(context, R.layout.widget_eye)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return small
            return RemoteViews(
                mapOf(
                    SizeF(40f, 40f) to small,
                    SizeF(110f, 110f) to views(context, R.layout.widget_eye_large)
                )
            )
        }

        private fun views(context: Context, layout: Int): RemoteViews {
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
            return RemoteViews(context.packageName, layout).apply {
                // Eye on: vivid rainbow frame and open eye; eye off: washed-out frame and crossed-out eye.
                setImageViewResource(
                    R.id.eye_frame,
                    if (watching != null) R.drawable.widget_eye_frame_on else R.drawable.widget_eye_frame_off
                )
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
