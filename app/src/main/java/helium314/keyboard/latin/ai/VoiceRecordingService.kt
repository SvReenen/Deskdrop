// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings as AndroidSettings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service that records audio via [WhisperRecorder], then transcribes
 * and launches [ConversationActivity] with the transcript pre-filled.
 *
 * Shows a floating stop button overlay when SYSTEM_ALERT_WINDOW is granted.
 * - Tap stop = transcribe + AI process + open in Deskdrop
 * - Tap share = transcribe + AI process + Android share sheet
 * - Tap execute = transcribe + run MCP tools via floating popup
 * - Long-press any button = pick voice mode first
 */
class VoiceRecordingService : Service() {

    companion object {
        const val ACTION_START = "helium314.keyboard.VOICE_START"
        const val ACTION_STOP = "helium314.keyboard.VOICE_STOP"
        const val ACTION_STOP_WITH_MODE = "helium314.keyboard.VOICE_STOP_MODE"
        const val ACTION_STOP_AND_SHARE = "helium314.keyboard.VOICE_STOP_SHARE"
        const val ACTION_STOP_AND_EXECUTE = "helium314.keyboard.VOICE_STOP_EXECUTE"
        const val ACTION_START_EXECUTE = "helium314.keyboard.VOICE_START_EXECUTE"
        const val ACTION_TYPE_EXECUTE = "helium314.keyboard.VOICE_TYPE_EXECUTE"
        const val EXTRA_VOICE_MODE = "voice_mode_index"
        private const val CHANNEL_ID = "deskdrop_voice"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "VoiceRecordingService"

        val isRecording = AtomicBoolean(false)
    }

    private enum class StopDestination { DESKDROP, SHARE, EXECUTE }

    private var recorder: WhisperRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    /** Transcript buffered by Google STT, waiting for user to pick a destination. */
    @Volatile private var pendingTranscript: String? = null
    /** Observable state for the overlay composable: true when STT transcript is ready. */
    private val sttReady = mutableStateOf(false)
    /** Observable state: true when AI is processing the transcript. */
    private val isProcessing = mutableStateOf(false)
    private var overlayView: ComposeView? = null
    private var executeOverlayView: ComposeView? = null
    private var executeLifecycleOwner: ServiceLifecycleOwner? = null
    private var pendingDestination: StopDestination = StopDestination.DESKDROP
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promptOverlayPermission() {
        Toast.makeText(this, "Allow \"Display over other apps\" for Deskdrop", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) { /* device doesn't support direct navigation */ }
    }

    private fun openAppSettings(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pendingDestination = StopDestination.DESKDROP
                startRecording()
            }
            ACTION_START_EXECUTE -> {
                pendingDestination = StopDestination.EXECUTE
                startRecording(executeMode = true)
            }
            ACTION_STOP -> {
                if (pendingTranscript != null) {
                    processPendingTranscript(null, pendingDestination)
                } else if (speechRecognizer != null) {
                    // Still listening; force stop so onResults fires
                    speechRecognizer?.stopListening()
                } else {
                    stopRecordingAndTranscribe(null, pendingDestination)
                }
            }
            ACTION_STOP_AND_SHARE -> {
                val mode = intent.getIntExtra(EXTRA_VOICE_MODE, -1)
                if (pendingTranscript != null) {
                    processPendingTranscript(if (mode >= 0) mode else null, StopDestination.SHARE)
                } else if (speechRecognizer != null) {
                    pendingDestination = StopDestination.SHARE
                    speechRecognizer?.stopListening()
                } else {
                    stopRecordingAndTranscribe(if (mode >= 0) mode else null, StopDestination.SHARE)
                }
            }
            ACTION_STOP_AND_EXECUTE -> {
                if (pendingTranscript != null) {
                    processPendingTranscript(null, StopDestination.EXECUTE)
                } else if (speechRecognizer != null) {
                    pendingDestination = StopDestination.EXECUTE
                    speechRecognizer?.stopListening()
                } else {
                    stopRecordingAndTranscribe(null, StopDestination.EXECUTE)
                }
            }
            ACTION_TYPE_EXECUTE -> {
                showTypeExecuteOverlay()
            }
            ACTION_STOP_WITH_MODE -> {
                val mode = intent.getIntExtra(EXTRA_VOICE_MODE, -1)
                if (pendingTranscript != null) {
                    processPendingTranscript(if (mode >= 0) mode else null, StopDestination.DESKDROP)
                } else if (speechRecognizer != null) {
                    speechRecognizer?.stopListening()
                } else {
                    stopRecordingAndTranscribe(if (mode >= 0) mode else null, StopDestination.DESKDROP)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(executeMode: Boolean = false) {
        if (isRecording.get()) return
        val prefs = DeviceProtectedUtils.getSharedPreferences(this)
        val engine = prefs.getString(Settings.PREF_AI_VOICE_ENGINE, Defaults.PREF_AI_VOICE_ENGINE)

        // Google STT engine
        if (engine != "whisper") {
            startGoogleSttRecording(executeMode)
            return
        }

        // Whisper engine: check URL
        val whisperUrl = prefs.getString(Settings.PREF_WHISPER_URL, Defaults.PREF_WHISPER_URL) ?: ""
        if (whisperUrl.isBlank()) {
            val dummyNotification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_mic)
                .setContentTitle("Starting...")
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, dummyNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, dummyNotification)
            }
            Toast.makeText(this, "Set a Whisper URL in AI settings to use voice recording", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            val rec = WhisperRecorder(this)
            rec.start()
            recorder = rec
            isRecording.set(true)

            val stopIntent = Intent(this, VoiceRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPending = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_mic)
                .setContentTitle(getString(R.string.voice_recording_title))
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(R.string.voice_recording_stop),
                        stopPending
                    ).build()
                )
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            vibrate(100)
            if (executeMode) showSimpleStopOverlay() else showStopOverlay()
            VoiceTileService.requestListeningState(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording.set(false)
            stopSelf()
        }
    }

    private fun startGoogleSttRecording(executeMode: Boolean) {
        pendingTranscript = null
        sttReady.value = false
        // Permission check (service cannot request permission, just fail gracefully)
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val dummyNotification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_mic)
                .setContentTitle("Starting...")
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, dummyNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, dummyNotification)
            }
            openAppSettings("Allow microphone permission for Deskdrop")
            stopSelf()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val dummyNotification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shortcut_mic)
                .setContentTitle("Starting...")
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, dummyNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, dummyNotification)
            }
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        isRecording.set(true)

        val stopIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_mic)
            .setContentTitle(getString(R.string.voice_recording_title))
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.voice_recording_stop),
                    stopPending
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        vibrate(100)
        if (executeMode) showSimpleStopOverlay() else showStopOverlay()
        VoiceTileService.requestListeningState(this)

        val destination = pendingDestination
        val svc = this

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    // Don't remove overlay here; keep it visible so user can pick destination.
                    // Overlay is removed in processPendingTranscript or onError.
                }
                override fun onError(error: Int) {
                    isRecording.set(false)
                    removeStopOverlay()
                    VoiceTileService.requestListeningState(svc)
                    if (error != SpeechRecognizer.ERROR_CLIENT) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            else -> "Speech error ($error)"
                        }
                        Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()
                    }
                    cleanupSpeechRecognizer()
                    stopSelf()
                }
                override fun onResults(results: Bundle?) {
                    isRecording.set(false)
                    VoiceTileService.requestListeningState(svc)
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    cleanupSpeechRecognizer()

                    if (transcript.isBlank()) {
                        Toast.makeText(svc, "No speech detected", Toast.LENGTH_SHORT).show()
                        removeStopOverlay()
                        stopSelf()
                        return
                    }

                    // Buffer transcript; overlay stays visible so user can pick destination
                    pendingTranscript = transcript
                    sttReady.value = true
                    vibrate(50)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            }
            sr.startListening(intent)
        }
    }

    private fun cleanupSpeechRecognizer() {
        speechRecognizer?.let {
            try { it.stopListening(); it.destroy() } catch (_: Exception) {}
        }
        speechRecognizer = null
    }

    /** Process a buffered Google STT transcript through AI voice mode + destination. */
    private fun processPendingTranscript(modeOverride: Int?, destination: StopDestination) {
        val transcript = pendingTranscript
        pendingTranscript = null
        sttReady.value = false
        VoiceTileService.requestListeningState(this)

        if (transcript.isNullOrBlank()) {
            removeStopOverlay()
            Toast.makeText(this, "No speech detected", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        if (destination == StopDestination.EXECUTE) {
            removeStopOverlay()
            showExecuteOverlay(transcript)
            return
        }

        // Show spinner in overlay while processing
        isProcessing.value = true
        val svc = this
        thread {
            try {
                val prefs = DeviceProtectedUtils.getSharedPreferences(svc)
                AiServiceSync.setContext(svc)
                val voiceModeIndex = modeOverride ?: prefs.getInt(Settings.PREF_AI_VOICE_MODE, Defaults.PREF_AI_VOICE_MODE)
                val prompt = resolveVoiceModePrompt(voiceModeIndex, prefs)
                val model = resolveVoiceModel(prefs)

                val processed = try {
                    AiServiceSync.processWithModelAndInstruction(transcript, model, prompt, prefs)
                } catch (e: Exception) {
                    Log.e(TAG, "AI processing failed, using raw transcript", e)
                    transcript
                }
                val finalText = processed.ifBlank { transcript }

                mainHandler.post {
                    isProcessing.value = false
                    removeStopOverlay()
                }

                when (destination) {
                    StopDestination.DESKDROP -> {
                        val chatIntent = Intent(svc, ConversationActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(ConversationActivity.EXTRA_PREFILL_TEXT, finalText)
                        }
                        startActivity(chatIntent)
                    }
                    StopDestination.SHARE -> {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, finalText)
                        }
                        val chooser = Intent.createChooser(shareIntent, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(chooser)
                    }
                    else -> {}
                }
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                mainHandler.post {
                    isProcessing.value = false
                    removeStopOverlay()
                    Toast.makeText(svc, "Processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
        }
    }

    private fun stopRecordingAndTranscribe(modeOverride: Int?, destination: StopDestination) {
        val rec = recorder
        if (rec == null || !isRecording.get()) {
            // If there's a pending transcript (user tapped a destination button), process it
            if (pendingTranscript != null) {
                processPendingTranscript(modeOverride, destination)
            } else {
                stopSelf()
            }
            return
        }

        isRecording.set(false)
        vibrate(50)
        // Don't remove overlay; keep it so user can pick destination after transcription
        VoiceTileService.requestListeningState(this)

        // Toast so user knows something is happening
        mainHandler.post {
            Toast.makeText(this, getString(R.string.voice_transcribing), Toast.LENGTH_SHORT).show()
        }

        val transcribingNotification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_mic)
            .setContentTitle(getString(R.string.voice_transcribing))
            .setOngoing(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, transcribingNotification)

        thread {
            try {
                val wavFile = rec.stop()
                rec.release()
                recorder = null

                if (wavFile == null || !wavFile.exists()) {
                    mainHandler.post { removeStopOverlay() }
                    stopSelf()
                    return@thread
                }

                AiServiceSync.setContext(this@VoiceRecordingService)
                val prefs = DeviceProtectedUtils.getSharedPreferences(this@VoiceRecordingService)
                val transcript = AiServiceSync.transcribeWithWhisper(wavFile, prefs)
                // Clean up audio file after transcription
                try { wavFile.delete() } catch (_: Exception) {}

                if (transcript.isBlank()) {
                    mainHandler.post {
                        removeStopOverlay()
                        Toast.makeText(this@VoiceRecordingService, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                    stopSelf()
                    return@thread
                }

                // Buffer transcript; overlay stays visible so user can pick destination
                mainHandler.post {
                    pendingTranscript = transcript
                    sttReady.value = true
                    vibrate(50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post {
                    removeStopOverlay()
                    Toast.makeText(this@VoiceRecordingService, "Transcription failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
        }
    }

    // ── Voice mode resolution ─────────────────────────────────────────────

    private fun resolveVoiceModePrompt(mode: Int, prefs: SharedPreferences): String {
        val defaultPrompts = Defaults.AI_VOICE_MODE_PROMPTS
        val builtinCount = defaultPrompts.size

        if (mode < builtinCount) {
            val custom = prefs.getString("ai_voice_prompt_$mode", null)
            if (!custom.isNullOrEmpty()) return custom
            return defaultPrompts[mode]
        } else {
            try {
                val arr = JSONArray(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]"))
                val ci = mode - builtinCount
                if (ci < arr.length()) {
                    return arr.getJSONObject(ci).getString("prompt")
                }
            } catch (_: Exception) {}
            return defaultPrompts[0]
        }
    }

    private fun resolveVoiceModel(prefs: SharedPreferences): String {
        AiServiceSync.checkCloudFallback(prefs)
        val voiceModel = prefs.getString(Settings.PREF_AI_VOICE_MODEL, "") ?: ""
        if (voiceModel.isEmpty()) {
            return prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        }
        return voiceModel
    }

    private fun resolveMcpModel(prefs: SharedPreferences): String {
        AiServiceSync.checkCloudFallback(prefs)
        val mcpModel = prefs.getString(Settings.PREF_AI_MCP_MODEL, Defaults.PREF_AI_MCP_MODEL) ?: ""
        if (mcpModel.isEmpty()) {
            return prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        }
        return mcpModel
    }

    private fun loadVoiceModeNames(prefs: SharedPreferences): List<String> {
        val names = mutableListOf<String>()
        names.addAll(Defaults.AI_VOICE_MODE_NAMES)
        try {
            val arr = JSONArray(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]"))
            for (i in 0 until arr.length()) {
                names.add(arr.getJSONObject(i).getString("name"))
            }
        } catch (_: Exception) {}
        return names
    }

    // ── Floating stop button overlay ──────────────────────────────────────

    private fun showStopOverlay() {
        if (!AndroidSettings.canDrawOverlays(this)) {
            Log.d(TAG, "No overlay permission, skipping floating stop button")
            promptOverlayPermission()
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

            val prefs = DeviceProtectedUtils.getSharedPreferences(this)
            val voiceModeNames = loadVoiceModeNames(prefs)
            val savedMode = prefs.getInt(Settings.PREF_AI_VOICE_MODE, Defaults.PREF_AI_VOICE_MODE)
            val service = this

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    var expanded by remember { mutableStateOf(false) }
                    var expandedForShare by remember { mutableStateOf(false) }

                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Voice mode picker (shown on long press of either button)
                        AnimatedVisibility(
                            visible = expanded,
                            enter = fadeIn(animationSpec = tween(200)),
                            exit = fadeOut(animationSpec = tween(150))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .shadow(12.dp, RoundedCornerShape(16.dp))
                                    .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                                    .widthIn(max = 250.dp)
                                    .padding(vertical = 8.dp)
                            ) {
                                voiceModeNames.forEachIndexed { index, name ->
                                    val isSelected = index == savedMode
                                    Text(
                                        text = if (expandedForShare) "$name  \u2197" else name,
                                        color = if (isSelected) Color(0xFFE53935) else Color.White,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clickable {
                                                if (expandedForShare) {
                                                    val intent = Intent(service, VoiceRecordingService::class.java).apply {
                                                        action = ACTION_STOP_AND_SHARE
                                                        putExtra(EXTRA_VOICE_MODE, index)
                                                    }
                                                    startService(intent)
                                                } else {
                                                    val intent = Intent(service, VoiceRecordingService::class.java).apply {
                                                        action = ACTION_STOP_WITH_MODE
                                                        putExtra(EXTRA_VOICE_MODE, index)
                                                    }
                                                    startService(intent)
                                                }
                                            }
                                            .padding(horizontal = 20.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }

                        // Share + Stop + Execute buttons
                        val processing = isProcessing.value
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Share button (left) — hidden during processing
                            if (!processing) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(6.dp, CircleShape)
                                        .background(Color(0xFF2196F3), CircleShape)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = {
                                                    if (expanded) {
                                                        expanded = false
                                                    } else {
                                                        val shareIntent = Intent(service, VoiceRecordingService::class.java).apply {
                                                            action = ACTION_STOP_AND_SHARE
                                                        }
                                                        startService(shareIntent)
                                                    }
                                                },
                                                onLongPress = {
                                                    expandedForShare = true
                                                    expanded = !expanded
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_share),
                                        contentDescription = "Share",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // Stop / Chat / Processing button (center, larger)
                            val ready = sttReady.value
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .then(if (!ready && !processing) Modifier.alpha(pulseAlpha) else Modifier)
                                    .shadow(8.dp, CircleShape)
                                    .background(
                                        when {
                                            processing -> Color(0xFF26A69A)
                                            ready -> Color(0xFF26A69A)
                                            else -> Color(0xFFE53935)
                                        },
                                        CircleShape
                                    )
                                    .then(if (!processing) Modifier.pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                if (expanded) {
                                                    expanded = false
                                                } else {
                                                    val stopIntent = Intent(service, VoiceRecordingService::class.java).apply {
                                                        action = ACTION_STOP
                                                    }
                                                    startService(stopIntent)
                                                }
                                            },
                                            onLongPress = {
                                                expandedForShare = false
                                                expanded = !expanded
                                            }
                                        )
                                    } else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (processing) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(
                                            if (ready) R.drawable.ic_shortcut_chat else R.drawable.ic_stop_recording
                                        ),
                                        contentDescription = if (ready) "Chat" else getString(R.string.voice_recording_stop),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Execute / MCP button (right) — hidden during processing
                            if (!processing) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .shadow(6.dp, CircleShape)
                                        .background(Color(0xFFFF9800), CircleShape)
                                        .clickable {
                                            val executeIntent = Intent(service, VoiceRecordingService::class.java).apply {
                                                action = ACTION_STOP_AND_EXECUTE
                                            }
                                            startService(executeIntent)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_execute),
                                        contentDescription = "Execute",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 200
            }

            wm.addView(composeView, params)
            overlayView = composeView
            Log.d(TAG, "Floating stop button shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
            Toast.makeText(this, getString(R.string.voice_recording_title), Toast.LENGTH_SHORT).show()
        }
    }

    /** Simple stop-only overlay for execute mode (no share/mode buttons) */
    private fun showSimpleStopOverlay() {
        if (!AndroidSettings.canDrawOverlays(this)) {
            promptOverlayPermission()
            return
        }
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            val service = this

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .alpha(pulseAlpha)
                            .shadow(8.dp, CircleShape)
                            .background(Color(0xFFFF9800), CircleShape)
                            .clickable {
                                val stopIntent = Intent(service, VoiceRecordingService::class.java).apply {
                                    action = ACTION_STOP
                                }
                                startService(stopIntent)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop_recording),
                            contentDescription = getString(R.string.voice_recording_stop),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 200
            }

            wm.addView(composeView, params)
            overlayView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show simple stop overlay", e)
            Toast.makeText(this, getString(R.string.voice_recording_title), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeStopOverlay() {
        mainHandler.post {
            try {
                overlayView?.let {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay", e)
            }
            overlayView = null
        }
    }

    // ── Execute overlay (floating command popup) ──────────────────────────

    private fun showExecuteOverlay(transcript: String) {
        if (!AndroidSettings.canDrawOverlays(this)) {
            // Fallback: open in Deskdrop
            val chatIntent = Intent(this, ConversationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ConversationActivity.EXTRA_PREFILL_TEXT, transcript)
            }
            startActivity(chatIntent)
            stopSelf()
            return
        }

        val prefs = DeviceProtectedUtils.getSharedPreferences(this)

        // Update notification
        val execNotification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_mic)
            .setContentTitle("Voice command ready")
            .setOngoing(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, execNotification)

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            executeLifecycleOwner = lifecycleOwner

            val service = this
            // Mutable state holders shared between compose and background thread
            val statusText = mutableStateOf("")
            val resultText = mutableStateOf("")
            val isProcessing = mutableStateOf(false)
            val isDone = mutableStateOf(false)
            // Conversation history for follow-up messages
            val chatHistory = java.util.Collections.synchronizedList(mutableListOf<AiServiceSync.ChatMessage>())

            fun sendMessage(userText: String) {
                chatHistory.add(AiServiceSync.ChatMessage(role = "user", content = userText))
                isProcessing.value = true
                isDone.value = false
                statusText.value = "Executing..."
                resultText.value = ""
                thread {
                    executeCommand(chatHistory, prefs, statusText, resultText, isDone, isProcessing)
                }
            }

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    val status by remember { statusText }
                    val result by remember { resultText }
                    val processing by remember { isProcessing }
                    val done by remember { isDone }
                    var replyText by remember { mutableStateOf("") }
                    var isListening by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .shadow(16.dp, RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(20.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Close button (top right)
                        if (!processing) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "\u2715",
                                    color = Color(0xFF888888),
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clickable {
                                            removeExecuteOverlay()
                                            stopSelf()
                                        }
                                        .padding(4.dp)
                                )
                            }
                        }

                        // Transcript
                        Text(
                            text = "\u201C$transcript\u201D",
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Status / Result
                        if (status.isNotEmpty() && !done) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFFF9800),
                                    strokeWidth = 2.dp
                                )
                                Text(status, color = Color(0xFFFF9800), fontSize = 13.sp)
                            }
                        }
                        if (result.isNotEmpty()) {
                            val resultScrollState = rememberScrollState()
                            LaunchedEffect(result) {
                                resultScrollState.animateScrollTo(resultScrollState.maxValue)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 100.dp)
                                    .verticalScroll(resultScrollState)
                                    .padding(bottom = 12.dp)
                            ) {
                                Text(
                                    text = result,
                                    color = if (done) Color(0xFF4CAF50) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (done) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // Send button (first message, before any execution)
                        if (!processing && !done && chatHistory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .shadow(6.dp, RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFF9800), RoundedCornerShape(12.dp))
                                    .clickable { sendMessage(transcript) }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_execute),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Send", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Reply row (shown after execution is done)
                        if (done && !processing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Mic button for voice reply
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isListening) Color(0xFFE53935) else Color(0xFF424242),
                                            CircleShape
                                        )
                                        .clickable {
                                            if (isListening) {
                                                // Cannot programmatically stop Google STT from overlay,
                                                // it auto-stops on silence
                                            } else {
                                                if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                                                    Toast.makeText(service, "Speech not available", Toast.LENGTH_SHORT).show()
                                                    return@clickable
                                                }
                                                if (Build.VERSION.SDK_INT >= 23 &&
                                                    service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                                    service.openAppSettings("Allow microphone permission for Deskdrop")
                                                    return@clickable
                                                }
                                                val sr = SpeechRecognizer.createSpeechRecognizer(service)
                                                sr.setRecognitionListener(object : RecognitionListener {
                                                    override fun onReadyForSpeech(params: Bundle?) {
                                                        mainHandler.post { isListening = true }
                                                    }
                                                    override fun onEndOfSpeech() {}
                                                    override fun onResults(results: Bundle?) {
                                                        mainHandler.post { isListening = false }
                                                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                                            ?.firstOrNull() ?: ""
                                                        sr.destroy()
                                                        if (text.isNotBlank()) {
                                                            mainHandler.post { sendMessage(text) }
                                                        }
                                                    }
                                                    override fun onError(error: Int) {
                                                        mainHandler.post { isListening = false }
                                                        sr.destroy()
                                                        if (error != SpeechRecognizer.ERROR_CLIENT) {
                                                            mainHandler.post {
                                                                Toast.makeText(service, "Speech error: $error", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                    override fun onBeginningOfSpeech() {}
                                                    override fun onRmsChanged(rmsdB: Float) {}
                                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                                    override fun onPartialResults(partialResults: Bundle?) {}
                                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                                })
                                                val sttIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                                }
                                                sr.startListening(sttIntent)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shortcut_mic),
                                        contentDescription = "Voice reply",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Text input
                                androidx.compose.material3.TextField(
                                    value = replyText,
                                    onValueChange = { replyText = it },
                                    placeholder = { Text("Reply...", fontSize = 13.sp) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp, max = 80.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 14.sp
                                    ),
                                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF2A2A2A),
                                        unfocusedContainerColor = Color(0xFF2A2A2A),
                                        cursorColor = Color(0xFFFF9800),
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(18.dp),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onSend = {
                                            if (replyText.isNotBlank()) {
                                                val msg = replyText
                                                replyText = ""
                                                sendMessage(msg)
                                            }
                                        }
                                    )
                                )

                                // Send reply button
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFFF9800), CircleShape)
                                        .clickable {
                                            if (replyText.isNotBlank()) {
                                                val msg = replyText
                                                replyText = ""
                                                sendMessage(msg)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_execute),
                                        contentDescription = "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            wm.addView(composeView, params)
            executeOverlayView = composeView
            Log.d(TAG, "Execute overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show execute overlay", e)
            stopSelf()
        }
    }

    private fun executeCommand(
        chatHistory: MutableList<AiServiceSync.ChatMessage>,
        prefs: SharedPreferences,
        statusText: androidx.compose.runtime.MutableState<String>,
        resultText: androidx.compose.runtime.MutableState<String>,
        isDone: androidx.compose.runtime.MutableState<Boolean>,
        isProcessing: androidx.compose.runtime.MutableState<Boolean>
    ) {
        try {
            val model = resolveMcpModel(prefs)
            val tz = java.util.TimeZone.getDefault()
            val now = java.util.Date()
            val cal = java.util.Calendar.getInstance(tz)
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = tz }
            val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.ENGLISH).apply { timeZone = tz }
            val todayStr = dateFmt.format(now)
            val todayDay = dayFmt.format(now)
            cal.time = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val tomorrowStr = dateFmt.format(cal.time)
            val tomorrowDay = dayFmt.format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val dayAfterStr = dateFmt.format(cal.time)

            val dateSystemPrompt = "CRITICAL DATE REFERENCE — do NOT call get_datetime, use these pre-resolved dates:\n" +
                "• today = $todayDay $todayStr\n" +
                "• tomorrow = $tomorrowDay $tomorrowStr\n" +
                "• day after tomorrow = $dayAfterStr\n" +
                "When the user says 'morgen' or 'tomorrow', pass start='$tomorrowStr' to the calendar tool. NEVER pass '$todayStr' for tomorrow."

            var finalResult = ""
            val latch = java.util.concurrent.CountDownLatch(1)

            AiServiceSync.chatCompletionWithTools(
                messages = chatHistory.toList(),
                aiModel = model,
                prefs = prefs,
                extraSystemPrompt = dateSystemPrompt,
                onChunk = { chunk ->
                    // Filter out tool markers for display
                    if (!chunk.contains("\u27E6TOOL_") && !chunk.contains("\u27E6/TOOL_")) {
                        mainHandler.post { resultText.value = (resultText.value + chunk).take(500) }
                    } else {
                        // Extract tool name from marker for status
                        try {
                            val json = chunk.substringAfter("\u27E7").substringBefore("\u27E6")
                            val name = org.json.JSONObject(json).optString("name", "")
                            if (name.isNotEmpty()) {
                                mainHandler.post { statusText.value = "Tool: $name" }
                            }
                        } catch (_: Exception) {}
                    }
                },
                onComplete = { text ->
                    finalResult = text
                    latch.countDown()
                },
                onError = { error ->
                    finalResult = if (error.isNotEmpty()) error else "Cancelled"
                    latch.countDown()
                }
            )

            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)

            // Strip tool markers from final result
            val cleanResult = finalResult
                .replace(Regex("\u27E6TOOL_[a-f0-9]+\u27E7.*?\u27E6/TOOL_[a-f0-9]+\u27E7", RegexOption.DOT_MATCHES_ALL), "")
                .trim()

            // Add assistant reply to history for follow-ups
            if (cleanResult.isNotEmpty()) {
                chatHistory.add(AiServiceSync.ChatMessage(role = "assistant", content = cleanResult))
            }

            mainHandler.post {
                statusText.value = ""
                resultText.value = if (cleanResult.isNotEmpty()) "\u2713 $cleanResult" else "\u2713 Done"
                isDone.value = true
                isProcessing.value = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Execute failed", e)
            mainHandler.post {
                statusText.value = ""
                resultText.value = "Error: ${e.message}"
                isDone.value = true
                isProcessing.value = false
            }
        }
    }

    private fun showTypeExecuteOverlay() {
        if (!AndroidSettings.canDrawOverlays(this)) {
            promptOverlayPermission()
            stopSelf()
            return
        }

        val prefs = DeviceProtectedUtils.getSharedPreferences(this)
        // Minimal foreground notification to keep service alive (no microphone type needed)
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_mic)
            .setContentTitle("MCP Execute")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Mic permission not granted yet — run without foreground type
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            executeLifecycleOwner = lifecycleOwner

            val statusText = mutableStateOf("")
            val resultText = mutableStateOf("")
            val isProcessing = mutableStateOf(false)
            val isDone = mutableStateOf(false)
            val chatHistory = java.util.Collections.synchronizedList(mutableListOf<AiServiceSync.ChatMessage>())
            val service = this

            fun sendFollowUp(userText: String) {
                chatHistory.add(AiServiceSync.ChatMessage(role = "user", content = userText))
                isProcessing.value = true
                isDone.value = false
                statusText.value = "Executing..."
                resultText.value = ""
                thread {
                    executeCommand(chatHistory, prefs, statusText, resultText, isDone, isProcessing)
                }
            }

            val isListeningState = mutableStateOf(false)
            var whisperRec: WhisperRecorder? = null
            val voiceEngine = prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_VOICE_ENGINE,
                helium314.keyboard.latin.settings.Defaults.PREF_AI_VOICE_ENGINE) ?: "google"

            fun toggleVoice(onResult: (String) -> Unit) {
                if (Build.VERSION.SDK_INT >= 23 &&
                    service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    service.openAppSettings("Allow microphone permission for Deskdrop")
                    return
                }
                if (voiceEngine == "whisper") {
                    val wr = whisperRec
                    if (wr != null) {
                        whisperRec = null
                        isListeningState.value = false
                        mainHandler.post { statusText.value = "Transcribing..." }
                        thread {
                            val wavFile = wr.stop()
                            if (wavFile == null) {
                                mainHandler.post { statusText.value = "" }
                                return@thread
                            }
                            val transcription = AiServiceSync.transcribeWithWhisper(wavFile, prefs, null)
                            mainHandler.post {
                                statusText.value = ""
                                if (transcription.startsWith("[Whisper")) {
                                    Toast.makeText(service, transcription, Toast.LENGTH_LONG).show()
                                } else if (transcription.isNotBlank()) {
                                    onResult(transcription)
                                }
                            }
                        }
                    } else {
                        val newRec = WhisperRecorder(service)
                        newRec.start()
                        whisperRec = newRec
                        isListeningState.value = true
                    }
                } else {
                    if (isListeningState.value) return
                    if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                        Toast.makeText(service, "Speech not available", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val sr = SpeechRecognizer.createSpeechRecognizer(service)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            mainHandler.post { isListeningState.value = true }
                        }
                        override fun onEndOfSpeech() {}
                        override fun onResults(results: Bundle?) {
                            mainHandler.post { isListeningState.value = false }
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull() ?: ""
                            sr.destroy()
                            if (text.isNotBlank()) mainHandler.post { onResult(text) }
                        }
                        override fun onError(error: Int) {
                            mainHandler.post { isListeningState.value = false }
                            sr.destroy()
                            if (error != SpeechRecognizer.ERROR_CLIENT) {
                                mainHandler.post { Toast.makeText(service, "Speech error: $error", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val sttIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    sr.startListening(sttIntent)
                }
            }

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    val status by remember { statusText }
                    val result by remember { resultText }
                    val processing by remember { isProcessing }
                    val done by remember { isDone }
                    var inputText by remember { mutableStateOf("") }
                    var replyText by remember { mutableStateOf("") }
                    val isListening by remember { isListeningState }
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(300)
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }

                    Column(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .shadow(16.dp, RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(20.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header + close
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ai_actions),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isListening) "Listening..." else "AI Actions",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isListening) Color(0xFFE53935) else Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (!processing) {
                                Text(
                                    text = "\u2715",
                                    color = Color(0xFF888888),
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .clickable {
                                            removeExecuteOverlay()
                                            stopSelf()
                                        }
                                        .padding(4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        // Text input + mic (first message)
                        if (!processing && !done && chatHistory.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(80.dp)
                                        .background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                        .focusRequester(focusRequester),
                                    decorationBox = { inner ->
                                        Box {
                                            if (inputText.isEmpty()) {
                                                Text("Type a command...", color = Color(0xFF666666), fontSize = 15.sp)
                                            }
                                            inner()
                                        }
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(
                                            if (isListening) Color(0xFFE53935) else Color(0xFF424242),
                                            CircleShape
                                        )
                                        .clickable {
                                            toggleVoice { text -> sendFollowUp(text) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shortcut_mic),
                                        contentDescription = "Voice input",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Status / Result
                        if (status.isNotEmpty() && !done) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFFF9800),
                                    strokeWidth = 2.dp
                                )
                                Text(status, color = Color(0xFFFF9800), fontSize = 13.sp)
                            }
                        }
                        if (result.isNotEmpty()) {
                            val resultScrollState = rememberScrollState()
                            LaunchedEffect(result) {
                                resultScrollState.animateScrollTo(resultScrollState.maxValue)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 100.dp)
                                    .verticalScroll(resultScrollState)
                                    .padding(bottom = 12.dp)
                            ) {
                                Text(
                                    text = result,
                                    color = if (done) Color(0xFF4CAF50) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (done) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // Send button (first message)
                        if (!processing && !done && chatHistory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .shadow(6.dp, RoundedCornerShape(12.dp))
                                    .background(
                                        if (inputText.isNotBlank()) Color(0xFFFF9800) else Color(0xFF555555),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable(enabled = inputText.isNotBlank()) {
                                        val command = inputText.trim()
                                        inputText = ""
                                        sendFollowUp(command)
                                    }
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_execute),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Send", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Reply row (shown after execution is done)
                        if (done && !processing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Mic button for voice reply
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isListening) Color(0xFFE53935) else Color(0xFF424242),
                                            CircleShape
                                        )
                                        .clickable {
                                            toggleVoice { text -> sendFollowUp(text) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shortcut_mic),
                                        contentDescription = "Voice reply",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Text input for reply
                                androidx.compose.material3.TextField(
                                    value = replyText,
                                    onValueChange = { replyText = it },
                                    placeholder = { Text("Reply...", fontSize = 13.sp) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp, max = 80.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 14.sp
                                    ),
                                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF2A2A2A),
                                        unfocusedContainerColor = Color(0xFF2A2A2A),
                                        cursorColor = Color(0xFFFF9800),
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(18.dp),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onSend = {
                                            if (replyText.isNotBlank()) {
                                                val msg = replyText
                                                replyText = ""
                                                sendFollowUp(msg)
                                            }
                                        }
                                    )
                                )

                                // Send reply button
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFFF9800), CircleShape)
                                        .clickable {
                                            if (replyText.isNotBlank()) {
                                                val msg = replyText
                                                replyText = ""
                                                sendFollowUp(msg)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_overlay_execute),
                                        contentDescription = "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            wm.addView(composeView, params)
            executeOverlayView = composeView
            Log.d(TAG, "Type execute overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show type execute overlay", e)
            stopSelf()
        }
    }

    private fun removeExecuteOverlay() {
        mainHandler.post {
            try {
                executeOverlayView?.let {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove execute overlay", e)
            }
            executeOverlayView = null
            executeLifecycleOwner = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        removeStopOverlay()
        removeExecuteOverlay()
        cleanupSpeechRecognizer()
        if (isRecording.get()) {
            try {
                recorder?.stop()
                recorder?.release()
            } catch (_: Exception) {}
            isRecording.set(false)
        }
        recorder = null
    }

    private fun vibrate(durationMs: Long) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tile_voice_label),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
