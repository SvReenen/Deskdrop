// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible trampoline activity that starts [VoiceRecordingService] and finishes.
 *
 * On Android 14+ (API 34), a foreground service with type `microphone` can only
 * be started from a visible activity context. A [android.service.quicksettings.TileService]
 * alone does not satisfy this requirement. This activity bridges the gap: it is
 * launched from [VoiceTileService], starts the foreground service, and immediately
 * finishes so the user never sees it.
 */
class VoiceTrampolineActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VoiceTrampoline"
        const val EXTRA_VOICE_ACTION = "voice_action"
        const val ACTION_START = "start"
        const val ACTION_START_EXECUTE = "start_execute"
        const val ACTION_TYPE_EXECUTE = "type_execute"
        const val ACTION_STOP = "stop"
        const val ACTION_REQUEST_MIC = "request_mic"
    }

    private var pendingAction: String = ACTION_START

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceService(pendingAction)
        } else {
            Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.getStringExtra(EXTRA_VOICE_ACTION) ?: ACTION_START
        Log.d(TAG, "onCreate action=$action")

        when (action) {
            ACTION_STOP -> {
                val stopIntent = Intent(this, VoiceRecordingService::class.java).apply {
                    this.action = VoiceRecordingService.ACTION_STOP
                }
                startService(stopIntent)
                finish()
            }
            ACTION_TYPE_EXECUTE -> {
                // No microphone needed, just start service for overlay
                try {
                    val startIntent = Intent(this, VoiceRecordingService::class.java).apply {
                        this.action = VoiceRecordingService.ACTION_TYPE_EXECUTE
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start type execute", e)
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            ACTION_REQUEST_MIC -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingAction = action
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                // Already granted
                finish()
            }
            ACTION_START, ACTION_START_EXECUTE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingAction = action
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    // Don't finish() here — wait for permission result
                    return
                }
                startVoiceService(action)
                finish()
            }
        }
    }

    private fun startVoiceService(action: String) {
        try {
            val serviceAction = if (action == ACTION_START_EXECUTE)
                VoiceRecordingService.ACTION_START_EXECUTE
            else
                VoiceRecordingService.ACTION_START
            val startIntent = Intent(this, VoiceRecordingService::class.java).apply {
                this.action = serviceAction
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoiceRecordingService", e)
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
