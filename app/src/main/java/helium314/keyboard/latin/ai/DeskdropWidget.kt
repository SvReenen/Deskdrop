// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import helium314.keyboard.latin.R

/**
 * Home screen widget with quick-access buttons for Deskdrop.
 * - Mic: start voice recording (same as QS tile)
 * - Chat: open new conversation
 * - Execute: start voice recording in execute/MCP mode
 */
class DeskdropWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_VOICE = "helium314.keyboard.WIDGET_VOICE"
        private const val ACTION_WIDGET_CHAT = "helium314.keyboard.WIDGET_CHAT"
        private const val ACTION_WIDGET_EXECUTE = "helium314.keyboard.WIDGET_EXECUTE"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_VOICE -> {
                // Start voice recording via trampoline
                val i = Intent(context, VoiceTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(VoiceTrampolineActivity.EXTRA_VOICE_ACTION, VoiceTrampolineActivity.ACTION_START)
                }
                context.startActivity(i)
            }
            ACTION_WIDGET_CHAT -> {
                // Open new chat
                val i = Intent(context, ConversationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ConversationActivity.EXTRA_ACTION, ConversationActivity.ACTION_NEW_CHAT)
                }
                context.startActivity(i)
            }
            ACTION_WIDGET_EXECUTE -> {
                // Show floating MCP execute popup with text input
                val i = Intent(context, VoiceTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(VoiceTrampolineActivity.EXTRA_VOICE_ACTION, VoiceTrampolineActivity.ACTION_TYPE_EXECUTE)
                }
                context.startActivity(i)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_deskdrop)

        // Voice button
        views.setOnClickPendingIntent(
            R.id.btn_voice,
            makePendingIntent(context, ACTION_WIDGET_VOICE, 0)
        )

        // Chat button
        views.setOnClickPendingIntent(
            R.id.btn_chat,
            makePendingIntent(context, ACTION_WIDGET_CHAT, 1)
        )

        // Execute button
        views.setOnClickPendingIntent(
            R.id.btn_execute,
            makePendingIntent(context, ACTION_WIDGET_EXECUTE, 2)
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun makePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DeskdropWidget::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
