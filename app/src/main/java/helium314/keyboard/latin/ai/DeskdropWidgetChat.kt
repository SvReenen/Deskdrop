// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import helium314.keyboard.latin.R

class DeskdropWidgetChat : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_CHAT = "helium314.keyboard.WIDGET_CHAT"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_chat)
            views.setOnClickPendingIntent(
                R.id.btn_chat,
                PendingIntent.getBroadcast(
                    context, 11,
                    Intent(context, DeskdropWidgetChat::class.java).apply { action = ACTION_WIDGET_CHAT },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_CHAT) {
            context.startActivity(
                Intent(context, ConversationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ConversationActivity.EXTRA_ACTION, ConversationActivity.ACTION_NEW_CHAT)
                }
            )
        }
    }
}
