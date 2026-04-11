package helium314.keyboard.latin.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WhisperRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var recording = false
    private val pcmBuffer = ByteArrayOutputStream()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    fun start() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        pcmBuffer.reset()
        recording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                }
            }
        }
        recordingThread?.start()
    }

    fun stop(): File? {
        recording = false
        try {
            recordingThread?.join(2000)
        } catch (_: InterruptedException) {}
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData: ByteArray
        synchronized(pcmBuffer) {
            pcmData = pcmBuffer.toByteArray()
        }

        if (pcmData.isEmpty()) return null

        return try {
            val wavFile = File(context.cacheDir, "whisper_recording.wav")
            FileOutputStream(wavFile).use { out ->
                writeWavHeader(out, pcmData.size)
                out.write(pcmData)
            }
            wavFile
        } catch (_: IOException) {
            null
        }
    }

    fun isRecording(): Boolean = recording

    fun release() {
        recording = false
        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {}
        recordingThread = null
        audioRecord?.release()
        audioRecord = null
    }

    private fun writeWavHeader(out: FileOutputStream, pcmDataSize: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataChunkSize = pcmDataSize
        val fileSize = 36 + dataChunkSize

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(fileSize))
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToLittleEndian(16)) // chunk size
        out.write(shortToLittleEndian(1)) // PCM format
        out.write(shortToLittleEndian(channels))
        out.write(intToLittleEndian(SAMPLE_RATE))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(bitsPerSample))

        // data chunk
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataChunkSize))
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }
}
