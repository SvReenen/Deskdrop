package helium314.keyboard.latin.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.LongBuffer

object OnnxInferenceService {
    private const val TAG = "OnnxInference"
    private const val MAX_TOKENS = 64
    private const val MODEL_DIR = "onnx_models"
    private const val UNLOAD_DELAY_MS = 10L * 60 * 1000 // 10 minutes

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: T5Tokenizer? = null
    private var lastUsedTime = 0L
    private var unloadThread: Thread? = null

    @Synchronized
    fun isModelLoaded(): Boolean = encoderSession != null && decoderSession != null && tokenizer != null

    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun findFile(dir: File, vararg names: String): File? =
        names.map { File(dir, it) }.firstOrNull { it.exists() }

    fun hasModelFiles(context: Context): Boolean {
        val dir = getModelDir(context)
        return findFile(dir, "encoder_model.onnx", "encoder.onnx") != null
                && findFile(dir, "decoder_model_merged.onnx", "decoder_model.onnx", "decoder.onnx") != null
                && findFile(dir, "tokenizer.json") != null
    }

    @Synchronized
    fun loadModel(context: Context): Boolean {
        if (isModelLoaded()) return true

        val dir = getModelDir(context)
        val encoderFile = findFile(dir, "encoder_model.onnx", "encoder.onnx")
        val decoderFile = findFile(dir, "decoder_model_merged.onnx", "decoder_model.onnx", "decoder.onnx")
        val tokenizerFile = findFile(dir, "tokenizer.json")

        if (encoderFile == null || decoderFile == null || tokenizerFile == null) {
            Log.w(TAG, "Model files missing in ${dir.absolutePath}")
            return false
        }

        return try {
            val ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }

            encoderSession = ortEnv.createSession(encoderFile.absolutePath, sessionOptions)
            decoderSession = ortEnv.createSession(decoderFile.absolutePath, sessionOptions)
            tokenizer = T5Tokenizer(tokenizerFile)
            env = ortEnv

            scheduleUnload()
            Log.d(TAG, "Model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            unloadModel()
            false
        }
    }

    @Synchronized
    fun unloadModel() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        tokenizer = null
        Log.d(TAG, "Model unloaded")
    }

    private fun scheduleUnload() {
        lastUsedTime = System.currentTimeMillis()
        unloadThread?.interrupt()
        unloadThread = Thread {
            try {
                Thread.sleep(UNLOAD_DELAY_MS)
                if (System.currentTimeMillis() - lastUsedTime >= UNLOAD_DELAY_MS) {
                    unloadModel()
                }
            } catch (_: InterruptedException) {}
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun process(text: String, context: Context, cancelHandle: AiCancelRegistry.CancelHandle? = null): String {
        if (!loadModel(context)) {
            return "[ONNX model not loaded. Place encoder.onnx, decoder.onnx, and tokenizer.json in the app's onnx_models directory]"
        }

        lastUsedTime = System.currentTimeMillis()
        scheduleUnload()

        return try {
            runInference(text, cancelHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            "[ONNX inference error: ${e.message}]"
        }
    }

    private fun runInference(text: String, cancelHandle: AiCancelRegistry.CancelHandle? = null): String {
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer not loaded")
        val encoder = encoderSession ?: throw IllegalStateException("Encoder not loaded")
        val decoder = decoderSession ?: throw IllegalStateException("Decoder not loaded")
        val ortEnv = env ?: throw IllegalStateException("Environment not initialized")

        // Encode input
        val inputIds = tok.encode(text)
        val inputLength = inputIds.size.toLong()

        val inputIdsTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
            longArrayOf(1, inputLength)
        )
        val attentionMask = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(LongArray(inputIds.size) { 1L }),
            longArrayOf(1, inputLength)
        )

        // Run encoder
        val encoderInputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMask
        )
        val encoderOutput = encoder.run(encoderInputs)
        val hiddenStates = encoderOutput[0] as OnnxTensor

        // Auto-regressive decoding
        val generatedIds = mutableListOf(T5Tokenizer.PAD_TOKEN_ID)

        for (step in 0 until MAX_TOKENS) {
            if (cancelHandle?.cancelled?.get() == true) {
                encoderOutput.close()
                inputIdsTensor.close()
                attentionMask.close()
                return ""
            }
            val decoderInputIds = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(generatedIds.map { it.toLong() }.toLongArray()),
                longArrayOf(1, generatedIds.size.toLong())
            )

            // use_cache_branch: false = no KV cache (process full sequence each step)
            val useCacheBranch = OnnxTensor.createTensor(
                ortEnv, booleanArrayOf(false)
            )

            val decoderInputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to decoderInputIds,
                "encoder_hidden_states" to hiddenStates,
                "encoder_attention_mask" to attentionMask
            )

            // Add use_cache_branch and empty past_key_values if the merged decoder expects them
            val decoderInputNames = decoder.inputNames
            if ("use_cache_branch" in decoderInputNames) {
                decoderInputs["use_cache_branch"] = useCacheBranch
            }
            // Supply empty past_key_values tensors for any pkv inputs
            val pkvTensors = mutableListOf<OnnxTensor>()
            for (name in decoderInputNames) {
                if (name.startsWith("past_key_values")) {
                    // Detect dims from model input shape; fallback to T5-small defaults
                    val inputInfo = decoder.inputInfo[name]
                    val shape = (inputInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
                    val numHeads = shape?.getOrNull(1) ?: 6L
                    val headDim = shape?.getOrNull(3) ?: 64L
                    val emptyPkv = OnnxTensor.createTensor(
                        ortEnv,
                        java.nio.FloatBuffer.wrap(FloatArray(0)),
                        longArrayOf(1, numHeads, 0, headDim)
                    )
                    decoderInputs[name] = emptyPkv
                    pkvTensors.add(emptyPkv)
                }
            }

            val decoderOutput = decoder.run(decoderInputs)
            val logits = decoderOutput[0] as OnnxTensor

            // Get logits for last token position
            val logitsArray = logits.floatBuffer
            val vocabSize = logitsArray.remaining() / generatedIds.size
            val lastTokenLogits = FloatArray(vocabSize)
            logitsArray.position((generatedIds.size - 1) * vocabSize)
            logitsArray.get(lastTokenLogits)

            // Greedy: pick token with highest logit
            var bestId = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in lastTokenLogits.indices) {
                if (lastTokenLogits[i] > bestScore) {
                    bestScore = lastTokenLogits[i]
                    bestId = i
                }
            }

            if (bestId == T5Tokenizer.EOS_TOKEN_ID) break
            generatedIds.add(bestId)

            decoderInputIds.close()
            useCacheBranch.close()
            pkvTensors.forEach { it.close() }
            decoderOutput.close()
        }

        val result = tok.decode(generatedIds)
        encoderOutput.close()
        inputIdsTensor.close()
        attentionMask.close()

        return result.ifBlank { text }
    }
}
