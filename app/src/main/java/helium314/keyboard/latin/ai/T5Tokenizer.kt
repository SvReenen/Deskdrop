package helium314.keyboard.latin.ai

import android.util.Log
import org.json.JSONObject
import java.io.File

class T5Tokenizer(vocabFile: File) {
    private val vocab = mutableMapOf<String, Int>()
    private val reverseVocab = mutableMapOf<Int, String>()

    companion object {
        private const val TAG = "T5Tokenizer"
        const val PAD_TOKEN_ID = 0
        const val EOS_TOKEN_ID = 1
        const val UNK_TOKEN_ID = 2
        private const val SPACE_CHAR = '\u2581' // SentencePiece space marker
    }

    init {
        loadVocabulary(vocabFile)
        Log.d(TAG, "Loaded vocabulary with ${vocab.size} tokens")
    }

    private fun loadVocabulary(file: File) {
        val content = file.readText()
        try {
            // Try HuggingFace tokenizer.json format
            val json = JSONObject(content)
            val model = json.getJSONObject("model")
            val vocabArray = model.getJSONArray("vocab")
            for (i in 0 until vocabArray.length()) {
                val entry = vocabArray.getJSONArray(i)
                val token = entry.getString(0)
                val id = entry.getInt(1)
                vocab[token] = id
                reverseVocab[id] = token
            }
        } catch (e: Exception) {
            // Fallback: try TSV format (token\tid per line)
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val token = parts[0]
                    val id = parts[1].toIntOrNull() ?: return@forEachLine
                    vocab[token] = id
                    reverseVocab[id] = token
                }
            }
        }
    }

    fun encode(text: String): List<Int> {
        // SentencePiece: replace spaces with special char, prepend space marker
        val processed = SPACE_CHAR + text.replace(' ', SPACE_CHAR)
        val tokens = mutableListOf<Int>()

        var i = 0
        while (i < processed.length) {
            var bestMatch = ""
            var bestId = UNK_TOKEN_ID

            // Greedy longest-match tokenization
            for (end in minOf(processed.length, i + 32) downTo i + 1) {
                val candidate = processed.substring(i, end)
                val id = vocab[candidate]
                if (id != null) {
                    bestMatch = candidate
                    bestId = id
                    break
                }
            }

            if (bestMatch.isEmpty()) {
                // Character-level fallback
                val char = processed[i].toString()
                tokens.add(vocab[char] ?: UNK_TOKEN_ID)
                i++
            } else {
                tokens.add(bestId)
                i += bestMatch.length
            }
        }

        tokens.add(EOS_TOKEN_ID)
        return tokens
    }

    fun decode(tokenIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == PAD_TOKEN_ID || id == EOS_TOKEN_ID) continue
            val token = reverseVocab[id] ?: continue
            sb.append(token)
        }
        return sb.toString()
            .replace(SPACE_CHAR, ' ')
            .trim()
    }
}
