// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Text-to-speech service supporting Android TTS (local) and ElevenLabs (cloud).
 * Call [speak] to read text aloud. Call [shutdown] when done.
 */
object TtsService {

    private const val TAG = "TtsService"
    private const val ELEVENLABS_URL = "https://api.elevenlabs.io/v1/text-to-speech/"
    private const val DEFAULT_VOICE = "nPczCjzI2devNBz1zQrb" // Brian (deep, male)
    private const val DEFAULT_MODEL = "eleven_multilingual_v2"

    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var mediaPlayer: MediaPlayer? = null

    fun init(context: Context) {
        if (androidTts != null) return
        androidTts = TextToSpeech(context.applicationContext) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                androidTts?.language = Locale.getDefault()
            }
        }
    }

    fun speak(text: String, prefs: SharedPreferences, context: Context) {
        if (text.isBlank()) return
        val engine = prefs.getString(Settings.PREF_TTS_ENGINE, "android") ?: "android"
        when (engine) {
            "elevenlabs" -> speakElevenLabs(text, prefs, context)
            else -> speakAndroid(text)
        }
    }

    private fun speakAndroid(text: String) {
        val tts = androidTts ?: return
        if (!androidTtsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "deskdrop_tts")
    }

    private fun speakElevenLabs(text: String, prefs: SharedPreferences, context: Context) {
        val apiKey = SecureApiKeys.getKey(Settings.PREF_ELEVENLABS_API_KEY)
        if (apiKey.isBlank()) {
            Log.w(TAG, "ElevenLabs API key not set, falling back to Android TTS")
            speakAndroid(text)
            return
        }
        val voiceId = prefs.getString(Settings.PREF_ELEVENLABS_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

        Thread {
            try {
                val conn = URL("$ELEVENLABS_URL$voiceId").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("xi-api-key", apiKey)
                conn.doOutput = true

                // Cap text to avoid exceeding free tier limits per request
                val cappedText = if (text.length > 2400) text.substring(0, 2400) else text

                val body = org.json.JSONObject().apply {
                    put("text", cappedText)
                    put("model_id", DEFAULT_MODEL)
                    put("voice_settings", org.json.JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    })
                }

                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                if (conn.responseCode != 200) {
                    Log.w(TAG, "ElevenLabs error ${conn.responseCode}, falling back to Android TTS")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { speakAndroid(text) }
                    return@Thread
                }

                // Write audio to temp file and play
                val tempFile = File(context.cacheDir, "tts_response.mp3")
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            setOnCompletionListener {
                                it.release()
                                mediaPlayer = null
                                tempFile.delete()
                            }
                            prepare()
                            start()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Playback error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs request failed", e)
            }
        }.start()
    }

    fun stop() {
        androidTts?.stop()
        mediaPlayer?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    fun shutdown() {
        stop()
        androidTts?.shutdown()
        androidTts = null
        androidTtsReady = false
    }
}
