package com.example.bbcplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PlayerWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("bbc_player", Context.MODE_PRIVATE)
            val fileName = prefs.getString("last_name", null)?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.widget_no_audio)
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widgetFileName, fileName)
            views.setOnClickPendingIntent(
                R.id.widgetResumeButton,
                activityIntent(context, appWidgetId * 10 + 1, MainActivity.ACTION_RESUME_LAST)
            )
            views.setOnClickPendingIntent(
                R.id.widgetDriveButton,
                activityIntent(context, appWidgetId * 10 + 2, MainActivity.ACTION_PICK_DRIVE)
            )
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                activityIntent(context, appWidgetId * 10 + 3, Intent.ACTION_MAIN)
            )
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun activityIntent(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
