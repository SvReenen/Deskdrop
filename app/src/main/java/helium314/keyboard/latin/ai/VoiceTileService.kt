// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import helium314.keyboard.latin.R

/**
 * Quick Settings tile that toggles voice recording via [VoiceRecordingService].
 * Tap to start recording, tap again (or use the notification stop button) to
 * stop, transcribe, and open [ConversationActivity] with the transcript.
 *
 * On Android 14+ (API 34), a microphone foreground service cannot be started
 * directly from a TileService. We use [VoiceTrampolineActivity] as an invisible
 * bridge to satisfy the "visible activity" requirement.
 */
class VoiceTileService : TileService() {

    companion object {
        private const val TAG = "VoiceTileService"

        fun requestListeningState(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, VoiceTileService::class.java)
                )
            } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening, isRecording=${VoiceRecordingService.isRecording.get()}")
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val recording = VoiceRecordingService.isRecording.get()
        Log.d(TAG, "onClick, isRecording=$recording")

        val action = if (recording) VoiceTrampolineActivity.ACTION_STOP else VoiceTrampolineActivity.ACTION_START
        val trampolineIntent = Intent(this, VoiceTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(VoiceTrampolineActivity.EXTRA_VOICE_ACTION, action)
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, trampolineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(trampolineIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch trampoline", e)
        }

        // Tile state will be updated via requestListeningState from VoiceRecordingService
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (VoiceRecordingService.isRecording.get()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.voice_recording_stop)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_voice_label)
        }
        tile.updateTile()
    }
}
