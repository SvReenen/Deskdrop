// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import helium314.keyboard.latin.R

class DeskdropWidgetExecute : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_EXECUTE = "helium314.keyboard.WIDGET_EXECUTE"
        private const val ACTION_WIDGET_EXECUTE_SETTINGS = "helium314.keyboard.WIDGET_EXECUTE_SETTINGS"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_execute)
            views.setOnClickPendingIntent(
                R.id.btn_execute,
                PendingIntent.getBroadcast(
                    context, 12,
                    Intent(context, DeskdropWidgetExecute::class.java).apply { action = ACTION_WIDGET_EXECUTE },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.btn_execute_settings,
                PendingIntent.getBroadcast(
                    context, 14,
                    Intent(context, DeskdropWidgetExecute::class.java).apply { action = ACTION_WIDGET_EXECUTE_SETTINGS },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_EXECUTE -> {
                context.startActivity(
                    Intent(context, VoiceTrampolineActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(VoiceTrampolineActivity.EXTRA_VOICE_ACTION, VoiceTrampolineActivity.ACTION_TYPE_EXECUTE)
                    }
                )
            }
            ACTION_WIDGET_EXECUTE_SETTINGS -> {
                context.startActivity(
                    Intent(context, ExecuteSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}
