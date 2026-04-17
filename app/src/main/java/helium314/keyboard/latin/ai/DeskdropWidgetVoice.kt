// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import helium314.keyboard.latin.R

class DeskdropWidgetVoice : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_VOICE = "helium314.keyboard.WIDGET_VOICE"
        private const val ACTION_WIDGET_VOICE_SETTINGS = "helium314.keyboard.WIDGET_VOICE_SETTINGS"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_voice)
            views.setOnClickPendingIntent(
                R.id.btn_voice,
                PendingIntent.getBroadcast(
                    context, 10,
                    Intent(context, DeskdropWidgetVoice::class.java).apply { action = ACTION_WIDGET_VOICE },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.btn_voice_settings,
                PendingIntent.getBroadcast(
                    context, 13,
                    Intent(context, DeskdropWidgetVoice::class.java).apply { action = ACTION_WIDGET_VOICE_SETTINGS },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_VOICE -> {
                context.startActivity(
                    Intent(context, VoiceTrampolineActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(VoiceTrampolineActivity.EXTRA_VOICE_ACTION, VoiceTrampolineActivity.ACTION_START)
                    }
                )
            }
            ACTION_WIDGET_VOICE_SETTINGS -> {
                context.startActivity(
                    Intent(context, VoiceSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}
