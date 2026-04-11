package helium314.keyboard.latin.ai

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException

object AiServiceSync {

    /** Max bytes we'll ever read from a single non-streaming AI response. */
    private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024 // 10 MB

    /** Max characters per line when reading streaming responses. Prevents OOM from models
     *  that hallucinate endless output without newlines. Lines beyond this limit are truncated. */
    private const val MAX_LINE_CHARS = 1_000_000 // 1 MB

    /**
     * Read lines from a [java.io.BufferedReader] with a per-line character limit.
     * Standard [useLines] has no guard against a single line consuming all memory.
     */
    private inline fun java.io.BufferedReader.boundedLines(
        maxLineChars: Int = MAX_LINE_CHARS,
        block: (Sequence<String>) -> Unit
    ) {
        val reader = this
        val seq = generateSequence {
            val sb = StringBuilder()
            var ch: Int
            while (reader.read().also { ch = it } != -1) {
                if (ch == '\n'.code) break
                if (sb.length < maxLineChars) sb.append(ch.toChar())
            }
            if (ch == -1 && sb.isEmpty()) null else sb.toString()
        }
        try { block(seq) } finally { close() }
    }

    /**
     * Read an InputStream as UTF-8 text, but stop once [maxBytes] have been
     * consumed. Protects against a misbehaving or hostile server that streams
     * an unbounded body and would otherwise OOM the keyboard process. If the
     * limit is reached, the partial body is returned and downstream JSON
     * parsing will fail gracefully via the opt* chains.
     */
    private fun readBounded(stream: java.io.InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        stream.use { s ->
            while (total < maxBytes) {
                val toRead = minOf(buffer.size, maxBytes - total)
                val n = s.read(buffer, 0, toRead)
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Strip reasoning-model thinking blocks from AI output before returning to the user.
     *
     * Models like DeepSeek R1, QwQ, Qwen3 (thinking mode), Sky-T1 and the various
     * R1-Distill variants wrap their internal chain-of-thought in tags that would
     * otherwise land directly in the user's text field. Ollama's native API separates
     * `thinking` from `response` server-side so those come through clean, but LM Studio,
     * vLLM, llama.cpp and custom Ollama modelfiles still embed the tags in content.
     *
     * Handles the common tag variants seen in the wild:
     *   <think>...</think>, <thinking>...</thinking>, <reasoning>...</reasoning>,
     *   <|thinking|>...<|/thinking|>, and stray leading tags without a matching close.
     */
    private val THINK_BLOCK_REGEX = Regex(
        "<(?:think|thinking|reasoning)>[\\s\\S]*?</(?:think|thinking|reasoning)>|" +
        "<\\|(?:think|thinking|reasoning)\\|>[\\s\\S]*?<\\|/(?:think|thinking|reasoning)\\|>",
        RegexOption.IGNORE_CASE
    )
    private val STRAY_THINK_OPEN_REGEX = Regex(
        "^\\s*<(?:think|thinking|reasoning)>[\\s\\S]*?(?:</(?:think|thinking|reasoning)>|$)",
        RegexOption.IGNORE_CASE
    )

    private fun stripThinking(text: String): String {
        if (text.isEmpty()) return text
        var out = THINK_BLOCK_REGEX.replace(text, "")
        // Handle unbalanced: opening tag with no close (truncated reasoning output).
        out = STRAY_THINK_OPEN_REGEX.replace(out, "")
        return out.trim()
    }

    /**
     * Normalize an Ollama base URL: trim whitespace + trailing slashes and
     * prepend "http://" if the user typed only a host[:port] without a scheme.
     * Rejects any scheme other than http/https (e.g. file://, content://, gopher://)
     * to prevent accidental local-file reads or exotic SSRF paths.
     */
    @JvmStatic
    fun normalizeOllamaUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        // Reject anything that *looks* like a non-http scheme (file://, content://, etc.)
        if (trimmed.contains("://")) return ""
        return "http://$trimmed"
    }

    // --- Ollama URL fallback resolver --------------------------------------
    // Tries the primary URL (e.g. Tailscale) first; if unreachable within a
    // short timeout, falls back to the LAN URL. The chosen result is cached
    // briefly so a burst of calls doesn't probe repeatedly.
    @Volatile private var cachedOllamaUrlKey: String = ""
    @Volatile private var cachedOllamaResolvedUrl: String = ""
    @Volatile private var cachedOllamaResolvedExpiry: Long = 0L
    private const val OLLAMA_URL_CACHE_MS = 30_000L

    private fun probeOllama(baseUrl: String, timeoutMs: Int = 1500): Boolean {
        if (baseUrl.isBlank()) return false
        var conn: HttpURLConnection? = null
        return try {
            val u = URL("$baseUrl/api/tags")
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun resolveOllamaBaseUrl(prefs: SharedPreferences): String {
        val primary = normalizeOllamaUrl(prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL)
        val fallbackRaw = prefs.getString(Settings.PREF_OLLAMA_URL_FALLBACK, Defaults.PREF_OLLAMA_URL_FALLBACK) ?: ""
        val fallback = normalizeOllamaUrl(fallbackRaw)
        if (fallback.isBlank()) return primary

        val key = "$primary|$fallback"
        val now = System.currentTimeMillis()
        if (key == cachedOllamaUrlKey && now < cachedOllamaResolvedExpiry && cachedOllamaResolvedUrl.isNotEmpty()) {
            return cachedOllamaResolvedUrl
        }
        val chosen = when {
            probeOllama(primary) -> primary
            probeOllama(fallback) -> fallback
            else -> primary // give up; let the real call surface the error
        }
        cachedOllamaUrlKey = key
        cachedOllamaResolvedUrl = chosen
        cachedOllamaResolvedExpiry = now + OLLAMA_URL_CACHE_MS
        return chosen
    }

    @JvmStatic
    fun invalidateOllamaUrlCache() {
        cachedOllamaUrlKey = ""
        cachedOllamaResolvedUrl = ""
        cachedOllamaResolvedExpiry = 0L
    }

    // --- OpenAI-compatible URL fallback resolver ---------------------------
    @Volatile private var cachedOpenAiCompatUrlKey: String = ""
    @Volatile private var cachedOpenAiCompatResolvedUrl: String = ""
    @Volatile private var cachedOpenAiCompatResolvedExpiry: Long = 0L

    private fun probeOpenAiCompat(baseUrl: String, apiKey: String, timeoutMs: Int = 1500): Boolean {
        if (baseUrl.isBlank()) return false
        var conn: HttpURLConnection? = null
        return try {
            val u = URL("$baseUrl/v1/models")
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            // Accept any 2xx — some servers return 200 even without listing models
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun resolveOpenAiCompatBaseUrl(prefs: SharedPreferences): String {
        val primary = normalizeOllamaUrl(prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, Defaults.PREF_OPENAI_COMPAT_URL) ?: "")
        val fallbackRaw = prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, Defaults.PREF_OPENAI_COMPAT_URL_FALLBACK) ?: ""
        val fallback = normalizeOllamaUrl(fallbackRaw)
        if (fallback.isBlank()) return primary

        val key = "$primary|$fallback"
        val now = System.currentTimeMillis()
        if (key == cachedOpenAiCompatUrlKey && now < cachedOpenAiCompatResolvedExpiry && cachedOpenAiCompatResolvedUrl.isNotEmpty()) {
            return cachedOpenAiCompatResolvedUrl
        }
        val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
        val chosen = when {
            probeOpenAiCompat(primary, apiKey) -> primary
            probeOpenAiCompat(fallback, apiKey) -> fallback
            else -> primary
        }
        cachedOpenAiCompatUrlKey = key
        cachedOpenAiCompatResolvedUrl = chosen
        cachedOpenAiCompatResolvedExpiry = now + OLLAMA_URL_CACHE_MS
        return chosen
    }

    @JvmStatic
    fun invalidateOpenAiCompatUrlCache() {
        cachedOpenAiCompatUrlKey = ""
        cachedOpenAiCompatResolvedUrl = ""
        cachedOpenAiCompatResolvedExpiry = 0L
    }

    /** Map an HTTP status code to a friendly, actionable error message. */
    /**
     * Parse "pseudo tool calls" that some models (notably Llama 4 Scout on
     * Groq) emit as plain text instead of using the proper tool_calls field.
     *
     * Observed format: `⟦{"name":"web_search","parameters":{"query":"…"}}⟧`
     * We also tolerate the ASCII variants `[[…]]` and `<|tool_call|>…<|/tool_call|>`
     * just in case the tokenizer maps different brackets on different runs.
     *
     * Both `parameters` and `arguments` are accepted as the args key, matching
     * what various Llama variants emit.
     *
     * Returns the stripped text (with the pseudo-call markers removed) and
     * the extracted tool calls. If no markers are found, returns the original
     * text and an empty list.
     */
    private fun extractInlinePseudoToolCalls(
        text: String
    ): Pair<String, List<AiToolRegistry.ToolCall>> {
        val calls = mutableListOf<AiToolRegistry.ToolCall>()
        val patterns = listOf(
            Regex("⟦\\s*(\\{.*?\\})\\s*⟧", RegexOption.DOT_MATCHES_ALL),
            Regex("\\[\\[\\s*(\\{.*?\\})\\s*\\]\\]", RegexOption.DOT_MATCHES_ALL),
            Regex("<\\|tool_call\\|>\\s*(\\{.*?\\})\\s*<\\|/tool_call\\|>", RegexOption.DOT_MATCHES_ALL)
        )
        var stripped = text
        for (regex in patterns) {
            for (m in regex.findAll(stripped).toList()) {
                val raw = m.groupValues[1]
                val parsed = try { JSONObject(raw) } catch (_: Exception) { null } ?: continue
                val name = parsed.optString("name").takeIf { it.isNotBlank() } ?: continue
                val args = parsed.optJSONObject("parameters")
                    ?: parsed.optJSONObject("arguments")
                    ?: JSONObject()
                calls.add(AiToolRegistry.ToolCall(AiToolRegistry.newCallId(), name, args))
            }
            stripped = regex.replace(stripped, "").trim()
        }
        return stripped to calls
    }

    private fun friendlyHttpError(provider: String, code: Int, body: String? = null): String {
        val bodyLower = body?.lowercase().orEmpty()
        // Some providers (notably Gemini) return 400 for invalid API keys.
        if (code == 400 && ("api key" in bodyLower || "api_key" in bodyLower)) {
            return "[$provider: invalid API key — check it in AI Settings]"
        }
        // Gemini: country / region not supported
        if ((code == 400 || code == 403) &&
            ("user location" in bodyLower ||
             "location is not supported" in bodyLower ||
             "country" in bodyLower && "not supported" in bodyLower)) {
            return "[$provider: not available in your country — try Groq or OpenRouter]"
        }
        // Gemini / safety: content blocked
        if (code == 400 &&
            ("safety" in bodyLower || "blocked" in bodyLower || "harm_category" in bodyLower)) {
            return "[$provider: response blocked by safety filter — rephrase the input]"
        }
        // Quota / billing exceeded (sometimes 400 instead of 429)
        if (code == 400 && ("quota" in bodyLower || "billing" in bodyLower)) {
            return "[$provider: quota exceeded — try again later or check billing]"
        }
        // Model not found / deprecated (sometimes 400)
        if (code == 400 &&
            ("model_not_found" in bodyLower ||
             "model not found" in bodyLower ||
             "decommissioned" in bodyLower ||
             "no longer supported" in bodyLower)) {
            return "[$provider: model not available — pick another in AI Settings]"
        }
        // Context / token limit
        if (code == 400 &&
            ("context length" in bodyLower ||
             "maximum context" in bodyLower ||
             "too many tokens" in bodyLower ||
             "token limit" in bodyLower)) {
            return "[$provider: input too long for this model]"
        }
        return when (code) {
            400 -> if (!body.isNullOrBlank()) "[$provider: bad request — ${body.take(120)}]"
                   else "[$provider: bad request]"
            401, 403 -> "[$provider: invalid API key — check it in AI Settings]"
            404 -> "[$provider: model not found — pick another in AI Settings]"
            408 -> "[$provider: request timed out — try again]"
            413 -> "[$provider: input too long for this model]"
            429 -> "[$provider: rate limit reached — wait a moment and retry]"
            500, 502, 503, 504 -> "[$provider server error ($code) — try again later]"
            else -> if (!body.isNullOrBlank()) "[$provider error $code: ${body.take(120)}]"
                    else "[$provider error $code]"
        }
    }

    /** Map an exception from a network call to a friendly, actionable error message. */
    private fun friendlyNetworkError(provider: String, e: Exception, isLocal: Boolean = false): String {
        // Local connection failure → invalidate all caches so cloud fallback
        // activates on the very next AI call (no waiting for 30s cache expiry)
        if (isLocal) {
            invalidateLocalReachableCache()
            invalidateOllamaUrlCache()
            invalidateOpenAiCompatUrlCache()
        }
        val msg = (e.message ?: "").lowercase()
        val target = if (isLocal) "$provider not reachable — check IP/port/Tailscale"
                     else "Cannot reach $provider — check your internet connection"
        return when {
            e is UnknownHostException ||
                "unable to resolve host" in msg ||
                "no address associated" in msg -> "[$target]"
            e is ConnectException ||
                "connection refused" in msg ||
                "failed to connect" in msg ||
                "econnrefused" in msg -> "[$target]"
            e is SocketTimeoutException ||
                "timeout" in msg -> if (isLocal)
                    "[$provider timed out — model loading or server slow]"
                else "[$provider timed out — try again]"
            "broken pipe" in msg ||
                "software caused connection abort" in msg ||
                "stream closed" in msg -> "[Connection lost — try again]"
            else -> "[$provider error: ${e.message ?: "unknown"}]"
        }
    }


    @JvmField
    val CLOUD_MODELS: List<Pair<String, String>> = listOf(
        "Gemini 2.5 Flash" to "gemini:gemini-2.5-flash",
        "Claude Sonnet 4.5 (Anthropic)" to "anthropic:claude-sonnet-4-5",
        "Claude Opus 4.1 (Anthropic)" to "anthropic:claude-opus-4-1",
        "Claude Haiku 4.5 (Anthropic)" to "anthropic:claude-haiku-4-5",
        "GPT-4o (OpenAI)" to "openai-cloud:gpt-4o",
        "GPT-4o mini (OpenAI)" to "openai-cloud:gpt-4o-mini",
        "GPT-4.1 (OpenAI)" to "openai-cloud:gpt-4.1",
        "Llama 4 Scout (Groq)" to "groq:meta-llama/llama-4-scout-17b-16e-instruct",
        "Llama 3.3 70B (Groq)" to "groq:llama-3.3-70b-versatile",
        "Gemma 2 9B (Groq)" to "groq:gemma2-9b-it",
        "Gemma 3 27B (OpenRouter)" to "openrouter:google/gemma-3-27b-it:free",
        "Llama 4 Scout (OpenRouter)" to "openrouter:meta-llama/llama-4-scout:free",
        "Mistral Small 24B (OpenRouter)" to "openrouter:mistralai/mistral-small-3.1-24b-instruct:free",
        "Qwen3 30B (OpenRouter)" to "openrouter:qwen/qwen3-30b-a3b:free",
    )

    /**
     * Check if a cloud model has the required API key configured.
     */
    @JvmStatic
    fun hasApiKey(modelValue: String): Boolean {
        val provider = modelValue.substringBefore(":")
        val keyPref = when (provider) {
            "gemini" -> Settings.PREF_GEMINI_API_KEY
            "groq" -> Settings.PREF_GROQ_API_KEY
            "openrouter" -> Settings.PREF_OPENROUTER_API_KEY
            "anthropic" -> Settings.PREF_ANTHROPIC_API_KEY
            "openai-cloud" -> Settings.PREF_OPENAI_API_KEY
            else -> return false
        }
        return SecureApiKeys.getKey(keyPref).isNotBlank()
    }

    /**
     * Resolve the cloud model to fall back to when local backends are unreachable.
     * Priority: current default model (if cloud) → first cloud model with a valid API key.
     * Returns null if no cloud model is available.
     */
    @JvmStatic
    fun resolveCloudFallbackModel(prefs: SharedPreferences): String? {
        val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: ""
        val currentBackend = currentModel.substringBefore(":")
        // If the default model is already a cloud model with a key, use it
        if (currentBackend !in listOf("ollama", "openai", "onnx") && hasApiKey(currentModel)) {
            return currentModel
        }
        // Otherwise find the first cloud model with a configured API key
        return CLOUD_MODELS.firstOrNull { hasApiKey(it.second) }?.second
    }

    private fun isLocalBackend(backend: String): Boolean = backend in listOf("ollama", "openai")

    // --- Global cloud fallback: settings override ----------------------------
    // When enabled and the local server is unreachable, ALL model preferences
    // are temporarily overwritten with the cloud fallback model. Original values
    // are stored in backup prefs. When connectivity is restored, originals are
    // restored. The UI shows the currently active model at all times.
    private const val BACKUP_PREFIX = "ai_cloud_fallback_backup_"
    private const val FALLBACK_ACTIVE_KEY = "ai_cloud_fallback_active"
    @Volatile private var cachedLocalReachable = true
    @Volatile private var cachedLocalReachableExpiry = 0L
    private const val LOCAL_REACHABLE_CACHE_MS = 30_000L

    /** All model pref keys that should be overridden during cloud fallback. */
    private val MODEL_PREF_KEYS = listOf(
        Settings.PREF_AI_MODEL,
        "ai_slot_1_model", "ai_slot_2_model", "ai_slot_3_model", "ai_slot_4_model",
        Settings.PREF_AI_INLINE_MODEL, Settings.PREF_AI_CONVERSATION_MODEL,
        Settings.PREF_AI_VOICE_MODEL, Settings.PREF_AI_MCP_MODEL
    )

    /**
     * Check local server reachability and activate/deactivate cloud fallback.
     * Call this before any AI operation. It probes the server (cached for 30s)
     * and overwrites/restores model prefs as needed.
     */
    @JvmStatic
    fun checkCloudFallback(prefs: SharedPreferences) {
        if (!prefs.getBoolean(Settings.PREF_AI_CLOUD_FALLBACK, Defaults.PREF_AI_CLOUD_FALLBACK)) {
            // Setting disabled — if fallback was active, restore originals
            if (prefs.getBoolean(FALLBACK_ACTIVE_KEY, false)) {
                deactivateCloudFallback(prefs)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now < cachedLocalReachableExpiry) return // use cached state, prefs already correct

        // Determine which local backend(s) the user has configured
        val ollamaUrl = normalizeOllamaUrl(prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: "")
        val ollamaFallback = normalizeOllamaUrl(prefs.getString(Settings.PREF_OLLAMA_URL_FALLBACK, "") ?: "")
        val openaiUrl = normalizeOllamaUrl(prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, "") ?: "")
        val openAiFallback = normalizeOllamaUrl(prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, "") ?: "")

        val ollamaReachable = ollamaUrl.isBlank() ||
            probeOllama(ollamaUrl) || (ollamaFallback.isNotBlank() && probeOllama(ollamaFallback))
        val openaiReachable = openaiUrl.isBlank() || run {
            val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
            probeOpenAiCompat(openaiUrl, apiKey) || (openAiFallback.isNotBlank() && probeOpenAiCompat(openAiFallback, apiKey))
        }
        val reachable = ollamaReachable && openaiReachable
        cachedLocalReachable = reachable
        cachedLocalReachableExpiry = now + LOCAL_REACHABLE_CACHE_MS

        val fallbackActive = prefs.getBoolean(FALLBACK_ACTIVE_KEY, false)
        if (!reachable && !fallbackActive) {
            activateCloudFallback(prefs)
        } else if (reachable && fallbackActive) {
            deactivateCloudFallback(prefs)
        }
    }

    /**
     * Save current local model settings to backup prefs, then overwrite
     * all local model prefs with the cloud fallback model.
     */
    private fun activateCloudFallback(prefs: SharedPreferences) {
        val cloudModel = resolveCloudFallbackModel(prefs) ?: return
        val editor = prefs.edit()
        for (key in MODEL_PREF_KEYS) {
            val current = prefs.getString(key, "") ?: ""
            // Save original value (only if not already backed up)
            if (!prefs.contains("$BACKUP_PREFIX$key")) {
                editor.putString("$BACKUP_PREFIX$key", current)
            }
            // Override local models with cloud; leave cloud models and empty (inherit) as-is
            val backend = current.substringBefore(":")
            if (current.isNotEmpty() && isLocalBackend(backend)) {
                editor.putString(key, cloudModel)
            }
        }
        editor.putBoolean(FALLBACK_ACTIVE_KEY, true)
        editor.apply()
        Log.d("AiToolCall", "Cloud fallback activated → $cloudModel")
    }

    /**
     * Restore original model settings from backup prefs.
     */
    private fun deactivateCloudFallback(prefs: SharedPreferences) {
        val editor = prefs.edit()
        for (key in MODEL_PREF_KEYS) {
            val backupKey = "$BACKUP_PREFIX$key"
            if (prefs.contains(backupKey)) {
                val original = prefs.getString(backupKey, "") ?: ""
                editor.putString(key, original)
                editor.remove(backupKey)
            }
        }
        editor.putBoolean(FALLBACK_ACTIVE_KEY, false)
        editor.apply()
        Log.d("AiToolCall", "Cloud fallback deactivated — local models restored")
    }

    /** Force re-probe on next call (e.g. after settings change or manual retry). */
    @JvmStatic
    fun invalidateLocalReachableCache() {
        cachedLocalReachableExpiry = 0L
    }

    /** Check if cloud fallback is currently active (for UI indicators). */
    @JvmStatic
    fun isCloudFallbackActive(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(FALLBACK_ACTIVE_KEY, false)

    private var appContext: Context? = null

    @JvmStatic
    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /** Package-internal accessor so [AiToolRegistry] can reach the app context
     *  without new plumbing. Returns null if [setContext] hasn't run yet. */
    internal fun appContext(): Context? = appContext

    // Cache which models are custom (have a system prompt via parent_model)
    private val customModelCache = mutableMapOf<String, Boolean>()

    @JvmStatic
    fun isCustomModel(baseUrl: String, model: String): Boolean = isCustomOllamaModel(baseUrl, model)

    private fun isCustomOllamaModel(baseUrl: String, model: String): Boolean {
        customModelCache[model]?.let { return it }
        val details = fetchModelDetails(baseUrl, model)
        val isCustom = details != null && details.parentModel.isNotBlank()
        customModelCache[model] = isCustom
        return isCustom
    }

    @JvmStatic
    fun clearModelCache() { customModelCache.clear() }

    @JvmStatic
    @JvmOverloads
    fun processInline(
        content: String,
        instruction: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        checkCloudFallback(prefs)
        val aiModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        val prompt = if (content.isBlank()) instruction else "$instruction\n\nText:\n$content"

        val colonIndex = aiModel.indexOf(':')
        if (colonIndex > 0) {
            val backend = aiModel.substring(0, colonIndex)
            val model = aiModel.substring(colonIndex + 1)
            return when (backend) {
                "onnx" -> {
                    val ctx = appContext ?: return "[ONNX: no context available]"
                    OnnxInferenceService.process("$instruction: $content", ctx, cancelHandle)
                }
                "ollama" -> callOllama(prompt, model, prefs, cancelHandle)
                "gemini" -> callGemini(prompt, model, prefs, cancelHandle)
                "groq" -> callOpenAICompatible(prompt, model, "https://api.groq.com/openai/v1/chat/completions", SecureApiKeys.getKey(Settings.PREF_GROQ_API_KEY), "Groq", cancelHandle)
                "openrouter" -> callOpenAICompatible(prompt, model, "https://openrouter.ai/api/v1/chat/completions", SecureApiKeys.getKey(Settings.PREF_OPENROUTER_API_KEY), "OpenRouter", cancelHandle)
                "openai-cloud" -> callOpenAICompatible(prompt, model, "https://api.openai.com/v1/chat/completions", SecureApiKeys.getKey(Settings.PREF_OPENAI_API_KEY), "OpenAI", cancelHandle)
                "anthropic" -> callAnthropicChat(
                    listOf(ChatMessage(role = "user", content = prompt)),
                    model,
                    SecureApiKeys.getKey(Settings.PREF_ANTHROPIC_API_KEY),
                    cancelHandle
                )
                "openai" -> {
                    val baseUrl = resolveOpenAiCompatBaseUrl(prefs)
                    if (baseUrl.isEmpty()) return "[Set your OpenAI-compatible server URL in keyboard settings]"
                    callOpenAICompatible(
                        prompt, model,
                        "$baseUrl/v1/chat/completions",
                        SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY),
                        "OpenAI-compatible",
                        cancelHandle,
                        requireApiKey = false,
                        isLocal = true
                    )
                }
                else -> callGemini(prompt, model, prefs, cancelHandle)
            }
        }
        return "[Invalid inline model: $aiModel]"
    }

    @JvmStatic
    @JvmOverloads
    fun process(
        text: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        checkCloudFallback(prefs)
        val aiModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        return processWithModel(text, aiModel, prefs, cancelHandle)
    }

    @JvmStatic
    @JvmOverloads
    fun processWithModel(
        text: String,
        aiModel: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        val instruction = prefs.getString(Settings.PREF_AI_INSTRUCTION, Defaults.PREF_AI_INSTRUCTION) ?: Defaults.PREF_AI_INSTRUCTION
        return processWithModelAndInstruction(text, aiModel, instruction, prefs, cancelHandle)
    }

    @JvmStatic
    @JvmOverloads
    fun processWithModelAndInstruction(
        text: String,
        aiModel: String,
        instruction: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        // Cloud fallback: if the passed model is local but fallback is active,
        // substitute with the cloud model to avoid a long connection timeout
        val effectiveModel = run {
            checkCloudFallback(prefs)
            val backend = aiModel.substringBefore(":")
            if (isLocalBackend(backend) && isCloudFallbackActive(prefs)) {
                resolveCloudFallbackModel(prefs) ?: aiModel
            } else aiModel
        }
        @Suppress("NAME_SHADOWING")
        val aiModel = effectiveModel

        // Inject lorebook as context prefix if set
        val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
        val fullInstruction = if (lorebook.isNotBlank()) {
            "About the user:\n$lorebook\n\n$instruction"
        } else {
            instruction
        }
        val prompt = "$fullInstruction\n\nText:\n$text"

        val colonIndex = aiModel.indexOf(':')
        if (colonIndex > 0) {
            val backend = aiModel.substring(0, colonIndex)
            val model = aiModel.substring(colonIndex + 1)
            return when (backend) {
                "onnx" -> {
                    val ctx = appContext ?: return "[ONNX: no context available]"
                    // T5 works best with short, direct prompts
                    val onnxPrompt = "$fullInstruction: $text"
                    OnnxInferenceService.process(onnxPrompt, ctx, cancelHandle)
                }
                "ollama" -> {
                    val baseUrl = resolveOllamaBaseUrl(prefs)
                    // Custom models have their own system prompt; skip AI Instruction but keep lorebook
                    val ollamaPrompt = if (isCustomOllamaModel(baseUrl, model)) {
                        if (lorebook.isNotBlank()) "About the user:\n$lorebook\n\n$text" else text
                    } else prompt
                    callOllama(ollamaPrompt, model, prefs, cancelHandle)
                }
                "gemini" -> callGemini(prompt, model, prefs, cancelHandle)
                "groq" -> callOpenAICompatible(
                    prompt, model,
                    "https://api.groq.com/openai/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_GROQ_API_KEY),
                    "Groq",
                    cancelHandle
                )
                "openrouter" -> callOpenAICompatible(
                    prompt, model,
                    "https://openrouter.ai/api/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_OPENROUTER_API_KEY),
                    "OpenRouter",
                    cancelHandle
                )
                "openai-cloud" -> callOpenAICompatible(
                    prompt, model,
                    "https://api.openai.com/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_OPENAI_API_KEY),
                    "OpenAI",
                    cancelHandle
                )
                "anthropic" -> callAnthropicChat(
                    listOf(ChatMessage(role = "user", content = prompt)),
                    model,
                    SecureApiKeys.getKey(Settings.PREF_ANTHROPIC_API_KEY),
                    cancelHandle
                )
                "openai" -> {
                    val baseUrl = resolveOpenAiCompatBaseUrl(prefs)
                    if (baseUrl.isEmpty()) return "[Set your OpenAI-compatible server URL in keyboard settings]"
                    callOpenAICompatible(
                        prompt, model,
                        "$baseUrl/v1/chat/completions",
                        SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY),
                        "OpenAI-compatible",
                        cancelHandle,
                        requireApiKey = false,
                        isLocal = true
                    )
                }
                else -> callGemini(prompt, model, prefs, cancelHandle)
            }
        }

        // Fallback for old prefs format
        val backend = prefs.getString(Settings.PREF_AI_BACKEND, Defaults.PREF_AI_BACKEND) ?: Defaults.PREF_AI_BACKEND
        return when (backend) {
            "ollama" -> {
                val model = prefs.getString(Settings.PREF_OLLAMA_MODEL, Defaults.PREF_OLLAMA_MODEL) ?: Defaults.PREF_OLLAMA_MODEL
                callOllama(prompt, model, prefs, cancelHandle)
            }
            else -> callGemini(prompt, "gemini-2.5-flash", prefs, cancelHandle)
        }
    }

    @JvmStatic
    fun fetchOllamaModels(baseUrl: String): List<String> {
        val url = URL("$baseUrl/api/tags")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode != 200) return emptyList()
            val response = readBounded(conn.inputStream)
            val json = JSONObject(response)
            val models = json.optJSONArray("models") ?: return emptyList()
            return (0 until models.length()).mapNotNull {
                models.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun callGemini(
        prompt: String,
        model: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        val apiKey = SecureApiKeys.getKey(Settings.PREF_GEMINI_API_KEY)
        if (apiKey.isBlank()) return "[Set your Gemini API key in keyboard settings]"

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val error = conn.errorStream?.bufferedReader()?.readText()
                return friendlyHttpError("Gemini", conn.responseCode, error)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val geminiUsage = json.optJSONObject("usageMetadata")
            if (geminiUsage != null) {
                val p = geminiUsage.optInt("promptTokenCount", 0)
                val c = geminiUsage.optInt("candidatesTokenCount", 0)
                if (p > 0 || c > 0) cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(p, c)
            }
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
            return if (text.isNullOrEmpty()) "[Gemini: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError("Gemini", e, isLocal = false)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOpenAICompatible(
        prompt: String,
        model: String,
        endpoint: String,
        apiKey: String,
        providerName: String,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        requireApiKey: Boolean = true,
        isLocal: Boolean = false
    ): String {
        if (requireApiKey && apiKey.isBlank()) return "[Set your $providerName API key in keyboard settings]"

        val url = URL(endpoint)
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            ))
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = if (isLocal) 120000 else 60000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val error = conn.errorStream?.bufferedReader()?.readText()
                return friendlyHttpError(providerName, conn.responseCode, error)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val oaiUsage = json.optJSONObject("usage")
            if (oaiUsage != null) {
                val p = oaiUsage.optInt("prompt_tokens", 0)
                val c = oaiUsage.optInt("completion_tokens", 0)
                if (p > 0 || c > 0) cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(p, c)
            }
            val rawText = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
            val text = rawText?.let { stripThinking(it) }
            return if (text.isNullOrEmpty()) "[$providerName: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError(providerName, e, isLocal = isLocal)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Multi-turn chat (Conversation mode)
    // ════════════════════════════════════════════════════════════════════

    data class MessageAttachment(
        @JvmField val path: String,
        @JvmField val mimeType: String
    )

    data class ChatMessage(
        @JvmField val role: String,     // "user" | "assistant" | "system" | "tool"
        @JvmField val content: String,
        @JvmField val attachments: List<MessageAttachment> = emptyList(),
        /** Non-null on assistant messages that requested tool calls. */
        @JvmField val toolCalls: List<AiToolRegistry.ToolCall>? = null,
        /** Non-null on role="tool" messages, linking a result to its call. */
        @JvmField val toolCallId: String? = null,
        /** Non-null on role="tool" messages, the name of the tool that ran. */
        @JvmField val toolName: String? = null
    )

    /**
     * Vision-capable models that accept image inputs. Anything not in this set
     * silently ignores attached images, so we gate the picker UI on this check.
     */
    private val VISION_CAPABLE_EXACT = setOf(
        "anthropic:claude-sonnet-4-5",
        "anthropic:claude-opus-4-1",
        "anthropic:claude-haiku-4-5",
        "openai-cloud:gpt-4o",
        "openai-cloud:gpt-4o-mini",
        "openai-cloud:gpt-4.1",
        "gemini:gemini-2.5-flash"
    )

    private val VISION_CAPABLE_OLLAMA_PREFIXES = listOf(
        "llava", "llama3.2-vision", "llama4", "qwen2-vl", "qwen2.5vl",
        "qwen3-vl", "qwen3.5", "qwen3.5-omni",
        "minicpm-v", "bakllava", "moondream", "granite3.2-vision", "gemma3"
    )

    /**
     * Runtime-detected Ollama capability cache. Key = raw model name (without
     * "ollama:" prefix), value = set of capability strings from `/api/show`
     * (e.g. "vision", "tools", "completion"). Populated lazily on background
     * threads by [fetchOllamaCapabilities] so `isVisionCapable` stays sync.
     */
    private val ollamaCapabilityCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * Query `/api/show` and return the capabilities set for [modelName], or
     * null on failure. Cached after the first successful call so switching
     * chats doesn't re-hit the server.
     */
    @JvmStatic
    fun fetchOllamaCapabilities(baseUrl: String, modelName: String): Set<String>? {
        ollamaCapabilityCache[modelName]?.let { return it }
        if (baseUrl.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl/api/show")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val body = JSONObject().apply { put("name", modelName) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val response = readBounded(conn.inputStream)
            val json = JSONObject(response)
            val arr = json.optJSONArray("capabilities") ?: return null
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) out.add(s.lowercase())
            }
            ollamaCapabilityCache[modelName] = out
            out
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Synchronous check without network calls. Returns:
     *  - true  → we know this model supports vision (cache hit or hardcoded)
     *  - false → we know it doesn't (cache hit "no vision") OR we don't know
     *
     * For Ollama models not yet in the cache this falls back to the prefix
     * list so the UI has a reasonable default before the background fetch
     * populates [ollamaCapabilityCache]. Once the cache populates, recompose
     * will pick up the new truth.
     */
    @JvmStatic
    fun isVisionCapable(modelValue: String): Boolean {
        if (modelValue in VISION_CAPABLE_EXACT) return true
        if (modelValue.startsWith("ollama:")) {
            val name = modelValue.removePrefix("ollama:")
            // 1. Runtime cache from /api/show (authoritative if present)
            ollamaCapabilityCache[name]?.let { caps ->
                return "vision" in caps
            }
            // 2. Prefix fallback (used until the cache populates)
            val lower = name.lowercase()
            return VISION_CAPABLE_OLLAMA_PREFIXES.any { lower.startsWith(it) }
        }
        // Self-hosted OpenAI-compatible (LM Studio, vLLM, llama.cpp): user knows
        // which model is loaded, so we let them try regardless.
        if (modelValue.startsWith("openai:")) return true
        return false
    }

    /** Clear the runtime capability cache (e.g. after a pull). */
    @JvmStatic
    fun clearOllamaCapabilityCache() { ollamaCapabilityCache.clear() }

    /** Alias kept for clarity at call sites that gate on image input. */
    @JvmStatic
    fun supportsImageInput(modelValue: String): Boolean = isVisionCapable(modelValue)

    /**
     * Models that accept PDF documents directly in the API call (no client-side
     * rendering). Anthropic and Gemini both have native PDF understanding —
     * everything else falls back to a PdfRenderer→image-pages pipeline at send
     * time, which works on any vision-capable model.
     */
    @JvmStatic
    fun isNativePdfCapable(modelValue: String): Boolean {
        return modelValue.startsWith("anthropic:") || modelValue.startsWith("gemini:")
    }

    /**
     * True if the model can take PDF input either natively or via the
     * image-rendering fallback (i.e. it has vision support).
     */
    @JvmStatic
    fun supportsPdfInput(modelValue: String): Boolean {
        return isNativePdfCapable(modelValue) || isVisionCapable(modelValue)
    }

    /**
     * Render a PDF on disk to a list of JPEG images (one per page) and return
     * them as MessageAttachment entries pointing at the rendered files. Used
     * to expand PDF attachments for backends without native PDF support.
     *
     * Pages are cached next to the source PDF in `<pdf>_pages/page_N.jpg`, so
     * a re-send (regenerate, edit, etc.) is fast. Hard cap at [maxPages] to
     * avoid token explosions on long documents.
     */
    @JvmStatic
    fun renderPdfToImageAttachments(pdfPath: String, maxPages: Int = 20): List<MessageAttachment> {
        val src = java.io.File(pdfPath)
        if (!src.exists()) return emptyList()
        val cacheDir = java.io.File(src.parentFile, "${src.nameWithoutExtension}_pages")
        cacheDir.mkdirs()

        var renderer: android.graphics.pdf.PdfRenderer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = android.os.ParcelFileDescriptor.open(src, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd)
            val total = minOf(renderer.pageCount, maxPages)
            val out = mutableListOf<MessageAttachment>()
            for (i in 0 until total) {
                val cached = java.io.File(cacheDir, "page_${i + 1}.jpg")
                if (!cached.exists()) {
                    val page = renderer.openPage(i)
                    // Target ~1568px on the long side, matching the image
                    // ingest pipeline. PDF pageWidth/Height are in points (1/72").
                    val targetLong = 1568
                    val ratio = targetLong.toFloat() / maxOf(page.width, page.height)
                    val w = (page.width * ratio).toInt().coerceAtLeast(1)
                    val h = (page.height * ratio).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    // White background — PdfRenderer composites onto transparency.
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    java.io.FileOutputStream(cached).use { fos ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                    bmp.recycle()
                    page.close()
                }
                out.add(MessageAttachment(cached.absolutePath, "image/jpeg"))
            }
            out
        } catch (e: Exception) {
            Log.e("AiServiceSync", "renderPdfToImageAttachments failed for $pdfPath", e)
            emptyList()
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Preprocess [messages] for a given backend. PDF attachments on backends
     * without native PDF support (everything except Anthropic / Gemini) are
     * expanded to rendered image pages so the rest of the per-backend builders
     * can stay simple. Backends with native support pass through unchanged.
     */
    private fun expandAttachmentsForBackend(messages: List<ChatMessage>, backend: String): List<ChatMessage> {
        val nativePdf = backend == "anthropic" || backend == "gemini"
        if (nativePdf) return messages
        // Quick check: any PDFs at all? Avoid copying the list otherwise.
        val anyPdf = messages.any { msg -> msg.attachments.any { it.mimeType == "application/pdf" } }
        if (!anyPdf) return messages
        return messages.map { msg ->
            if (msg.attachments.none { it.mimeType == "application/pdf" }) msg
            else {
                val expanded = mutableListOf<MessageAttachment>()
                for (a in msg.attachments) {
                    if (a.mimeType == "application/pdf") {
                        expanded.addAll(renderPdfToImageAttachments(a.path))
                    } else {
                        expanded.add(a)
                    }
                }
                msg.copy(attachments = expanded)
            }
        }
    }

    /**
     * Read a file from disk and return its base64 encoding, or null on failure.
     * Used to embed image attachments into multimodal API requests.
     */
    private fun encodeFileToBase64(path: String): String? {
        return try {
            val bytes = java.io.File(path).readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("AiServiceSync", "encodeFileToBase64 failed for $path", e)
            null
        }
    }

    /**
     * Ollama uses string content + a separate `images` base64 array. Other
     * fields (audio, etc.) are not supported here.
     */
    private fun buildOllamaMessageJson(m: ChatMessage): JSONObject {
        val obj = JSONObject().apply {
            put("role", m.role)
            put("content", m.content)
        }
        if (m.attachments.isNotEmpty()) {
            val imgs = JSONArray()
            for (a in m.attachments) {
                if (!a.mimeType.startsWith("image/")) continue
                val b64 = encodeFileToBase64(a.path) ?: continue
                imgs.put(b64)
            }
            if (imgs.length() > 0) obj.put("images", imgs)
        }
        // Assistant message with tool_calls (Ollama /api/chat format)
        if (m.toolCalls != null && m.toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            for (tc in m.toolCalls) {
                arr.put(JSONObject().apply {
                    put("function", JSONObject().apply {
                        put("name", tc.name)
                        put("arguments", tc.args)
                    })
                })
            }
            obj.put("tool_calls", arr)
        }
        // Tool role: Ollama uses role="tool" with name + content
        if (m.role == "tool" && m.toolName != null) {
            obj.put("name", m.toolName)
        }
        return obj
    }

    /**
     * OpenAI Chat Completions multimodal format. Returns either a plain string
     * (no attachments — keeps wire shape minimal) or a content-parts array.
     */
    private fun buildOpenAIMessageJson(m: ChatMessage): JSONObject {
        // Tool result message
        if (m.role == "tool") {
            return JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", m.toolCallId ?: "")
                put("content", m.content)
            }
        }
        val obj = JSONObject().apply { put("role", m.role) }
        // Assistant message that requested tool calls
        if (m.role == "assistant" && m.toolCalls != null && m.toolCalls.isNotEmpty()) {
            obj.put("content", if (m.content.isNotEmpty()) m.content else JSONObject.NULL)
            val arr = JSONArray()
            for (tc in m.toolCalls) {
                arr.put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tc.name)
                        put("arguments", tc.args.toString())
                    })
                })
            }
            obj.put("tool_calls", arr)
            return obj
        }
        if (m.attachments.isEmpty()) {
            obj.put("content", m.content)
            return obj
        }
        val parts = JSONArray()
        for (a in m.attachments) {
            if (!a.mimeType.startsWith("image/")) continue
            val b64 = encodeFileToBase64(a.path) ?: continue
            parts.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:${a.mimeType};base64,$b64")
                })
            })
        }
        if (m.content.isNotEmpty()) {
            parts.put(JSONObject().apply {
                put("type", "text")
                put("text", m.content)
            })
        }
        // Fallback when all attachments failed to encode and no text — keep
        // request well-formed by sending an empty user note.
        if (parts.length() == 0) {
            obj.put("content", m.content)
        } else {
            obj.put("content", parts)
        }
        return obj
    }

    /**
     * Anthropic Messages API multimodal format. Same shape rules as OpenAI but
     * uses image-blocks with base64 source instead of image_url.
     */
    private fun buildAnthropicMessageJson(m: ChatMessage): JSONObject {
        // Tool result: wrap as user message with a tool_result content block.
        if (m.role == "tool") {
            return JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", m.toolCallId ?: "")
                    put("content", m.content)
                }))
            }
        }
        // Assistant message that requested tool calls: emit text (if any) then tool_use blocks.
        if (m.role == "assistant" && m.toolCalls != null && m.toolCalls.isNotEmpty()) {
            val parts = JSONArray()
            if (m.content.isNotEmpty()) {
                parts.put(JSONObject().apply {
                    put("type", "text")
                    put("text", m.content)
                })
            }
            for (tc in m.toolCalls) {
                parts.put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", tc.id)
                    put("name", tc.name)
                    put("input", tc.args)
                })
            }
            return JSONObject().apply {
                put("role", "assistant")
                put("content", parts)
            }
        }
        val obj = JSONObject().apply {
            put("role", if (m.role == "assistant") "assistant" else "user")
        }
        if (m.attachments.isEmpty()) {
            obj.put("content", m.content)
            return obj
        }
        val parts = JSONArray()
        for (a in m.attachments) {
            val b64 = encodeFileToBase64(a.path) ?: continue
            when {
                a.mimeType.startsWith("image/") -> parts.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", a.mimeType)
                        put("data", b64)
                    })
                })
                a.mimeType == "application/pdf" -> parts.put(JSONObject().apply {
                    put("type", "document")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "application/pdf")
                        put("data", b64)
                    })
                })
            }
        }
        if (m.content.isNotEmpty()) {
            parts.put(JSONObject().apply {
                put("type", "text")
                put("text", m.content)
            })
        }
        if (parts.length() == 0) obj.put("content", m.content)
        else obj.put("content", parts)
        return obj
    }

    /**
     * Gemini parts builder. Inline image bytes are base64'd and tagged with
     * mimeType. Used inside the contents[].parts JSONArray.
     */
    private fun buildGeminiPartsArray(m: ChatMessage): JSONArray {
        val parts = JSONArray()
        for (a in m.attachments) {
            val ok = a.mimeType.startsWith("image/") || a.mimeType == "application/pdf"
            if (!ok) continue
            val b64 = encodeFileToBase64(a.path) ?: continue
            parts.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", a.mimeType)
                    put("data", b64)
                })
            })
        }
        if (m.content.isNotEmpty() || parts.length() == 0) {
            parts.put(JSONObject().put("text", m.content))
        }
        return parts
    }

    /**
     * Run a multi-turn chat completion. Dispatches by provider prefix on the
     * model value (e.g. "ollama:llama3", "openai:...", "groq:...").
     *
     * Applies stripThinking to the final output so reasoning-model tags don't
     * leak into the conversation bubble.
     */
    @JvmStatic
    @JvmOverloads
    fun chatCompletionMultiTurn(
        messages: List<ChatMessage>,
        aiModel: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        temperature: Float? = null
    ): String {
        if (messages.isEmpty()) return "[No messages]"
        // Global cloud fallback: check server and override prefs if needed
        checkCloudFallback(prefs)
        val colonIndex = aiModel.indexOf(':')
        if (colonIndex <= 0) return "[Invalid model: $aiModel]"
        val backend = aiModel.substring(0, colonIndex)
        val model = aiModel.substring(colonIndex + 1)

        val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
        val systemPrompt = if (lorebook.isNotBlank()) "About the user:\n$lorebook" else ""

        @Suppress("NAME_SHADOWING")
        val messages = expandAttachmentsForBackend(messages, backend)

        return when (backend) {
            "ollama" -> {
                val baseUrl = resolveOllamaBaseUrl(prefs)
                if (baseUrl.isEmpty()) "[Set Ollama URL in keyboard settings]"
                else callOllamaChat(messages, model, baseUrl, cancelHandle, systemPrompt, temperature)
            }
            "openai" -> {
                val baseUrl = resolveOpenAiCompatBaseUrl(prefs)
                if (baseUrl.isEmpty()) return "[Set your OpenAI-compatible server URL in keyboard settings]"
                val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
                callOpenAICompatibleChat(
                    messages, model,
                    "$baseUrl/v1/chat/completions",
                    apiKey, "OpenAI-compatible", cancelHandle,
                    requireApiKey = false, isLocal = true,
                    systemPrompt = systemPrompt, temperature = temperature
                )
            }
            "groq" -> callOpenAICompatibleChat(
                messages, model,
                "https://api.groq.com/openai/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_GROQ_API_KEY),
                "Groq", cancelHandle,
                systemPrompt = systemPrompt, temperature = temperature
            )
            "openrouter" -> callOpenAICompatibleChat(
                messages, model,
                "https://openrouter.ai/api/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_OPENROUTER_API_KEY),
                "OpenRouter", cancelHandle,
                systemPrompt = systemPrompt, temperature = temperature
            )
            "openai-cloud" -> callOpenAICompatibleChat(
                messages, model,
                "https://api.openai.com/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_OPENAI_API_KEY),
                "OpenAI", cancelHandle,
                systemPrompt = systemPrompt, temperature = temperature
            )
            "anthropic" -> callAnthropicChat(
                messages, model,
                SecureApiKeys.getKey(Settings.PREF_ANTHROPIC_API_KEY),
                cancelHandle,
                systemPrompt = systemPrompt, temperature = temperature
            )
            "gemini" -> callGeminiChat(messages, model, prefs, cancelHandle, temperature)
            "onnx" -> {
                val concatenated = messages.joinToString("\n\n") { "${it.role}: ${it.content}" }
                processWithModel(concatenated, aiModel, prefs, cancelHandle)
            }
            else -> "[Unknown backend: $backend]"
        }
    }

    /**
     * Streaming variant of [chatCompletionMultiTurn] for the Conversation UI.
     *
     * Real token streaming for Ollama (`/api/chat` with `stream: true`) and
     * OpenAI-compatible / Groq / OpenRouter (SSE). Cloud backends like Gemini
     * and ONNX fall back to non-streaming and emit the full result as a single
     * chunk on completion.
     *
     * - [onChunk] is invoked once per delta on the calling (background) thread.
     * - [onComplete] is invoked with the final, cleaned (post-stripThinking) text.
     * - [onError] is invoked with a friendly error string. On cancellation it is
     *   invoked with an empty string so the caller can preserve the partial.
     */
    @JvmStatic
    @JvmOverloads
    fun chatCompletionMultiTurnStream(
        messages: List<ChatMessage>,
        aiModel: String,
        prefs: SharedPreferences,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        temperature: Float? = null
    ) {
        if (messages.isEmpty()) { onError("[No messages]"); return }
        // Global cloud fallback: check server and override prefs if needed
        checkCloudFallback(prefs)
        val colonIndex = aiModel.indexOf(':')
        if (colonIndex <= 0) { onError("[Invalid model: $aiModel]"); return }
        val backend = aiModel.substring(0, colonIndex)
        val model = aiModel.substring(colonIndex + 1)

        val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
        val systemPrompt = if (lorebook.isNotBlank()) "About the user:\n$lorebook" else ""

        @Suppress("NAME_SHADOWING")
        val messages = expandAttachmentsForBackend(messages, backend)

        when (backend) {
            "ollama" -> {
                val baseUrl = resolveOllamaBaseUrl(prefs)
                if (baseUrl.isEmpty()) { onError("[Set Ollama URL in keyboard settings]"); return }
                callOllamaChatStream(messages, model, baseUrl, systemPrompt, onChunk, onComplete, onError, cancelHandle, temperature)
            }
            "openai" -> {
                val baseUrl = resolveOpenAiCompatBaseUrl(prefs)
                if (baseUrl.isEmpty()) { onError("[Set your OpenAI-compatible server URL in keyboard settings]"); return }
                val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
                callOpenAICompatibleChatStream(
                    messages, model, "$baseUrl/v1/chat/completions", apiKey,
                    "OpenAI-compatible", systemPrompt, onChunk, onComplete, onError,
                    cancelHandle, requireApiKey = false, isLocal = true, temperature = temperature
                )
            }
            "groq" -> callOpenAICompatibleChatStream(
                messages, model, "https://api.groq.com/openai/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_GROQ_API_KEY),
                "Groq", systemPrompt, onChunk, onComplete, onError, cancelHandle,
                temperature = temperature
            )
            "openrouter" -> callOpenAICompatibleChatStream(
                messages, model, "https://openrouter.ai/api/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_OPENROUTER_API_KEY),
                "OpenRouter", systemPrompt, onChunk, onComplete, onError, cancelHandle,
                temperature = temperature
            )
            "openai-cloud" -> callOpenAICompatibleChatStream(
                messages, model, "https://api.openai.com/v1/chat/completions",
                SecureApiKeys.getKey(Settings.PREF_OPENAI_API_KEY),
                "OpenAI", systemPrompt, onChunk, onComplete, onError, cancelHandle,
                temperature = temperature
            )
            "anthropic" -> callAnthropicChatStream(
                messages, model,
                SecureApiKeys.getKey(Settings.PREF_ANTHROPIC_API_KEY),
                systemPrompt, onChunk, onComplete, onError, cancelHandle, temperature
            )
            else -> {
                // Gemini, ONNX, etc.: non-streaming fallback
                try {
                    val result = chatCompletionMultiTurn(messages, aiModel, prefs, cancelHandle, temperature)
                    if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                    if (result.startsWith("[") && result.endsWith("]")) onError(result)
                    else {
                        onChunk(result)
                        onComplete(result)
                    }
                } catch (e: Exception) {
                    if (cancelHandle?.cancelled?.get() == true) onError("")
                    else onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun callOllamaChatStream(
        messages: List<ChatMessage>,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        temperature: Float? = null
    ) {
        val url = URL("$baseUrl/api/chat")
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            if (temperature != null) put("options", JSONObject().put("temperature", temperature.toDouble()))
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            }
            for (m in messages) {
                arr.put(buildOllamaMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        val accumulator = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                onError(friendlyHttpError("Ollama", conn.responseCode, errBody))
                return
            }
            conn.inputStream.bufferedReader().boundedLines { lines ->
                for (line in lines) {
                    if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                    if (line.isBlank()) continue
                    try {
                        val json = JSONObject(line)
                        val chunk = json.optJSONObject("message")?.optString("content", "") ?: ""
                        if (chunk.isNotEmpty()) {
                            accumulator.append(chunk)
                            onChunk(chunk)
                        }
                        if (json.optBoolean("done", false)) {
                            val prompt = json.optInt("prompt_eval_count", 0)
                            val completion = json.optInt("eval_count", 0)
                            if (prompt > 0 || completion > 0) {
                                cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(prompt, completion)
                            }
                            onComplete(stripThinking(accumulator.toString()))
                            return
                        }
                    } catch (_: Exception) { /* skip malformed line */ }
                }
            }
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onComplete(stripThinking(accumulator.toString()))
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onError(friendlyNetworkError("Ollama", e, isLocal = true))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOpenAICompatibleChatStream(
        messages: List<ChatMessage>,
        model: String,
        endpoint: String,
        apiKey: String,
        providerName: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        requireApiKey: Boolean = true,
        isLocal: Boolean = false,
        temperature: Float? = null
    ) {
        if (requireApiKey && apiKey.isBlank()) {
            onError("[Set your $providerName API key in keyboard settings]"); return
        }
        val url = URL(endpoint)
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            if (temperature != null) put("temperature", temperature.toDouble())
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            }
            for (m in messages) {
                arr.put(buildOpenAIMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = if (isLocal) 120000 else 60000

        val accumulator = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                onError(friendlyHttpError(providerName, conn.responseCode, errBody))
                return
            }
            conn.inputStream.bufferedReader().boundedLines { lines ->
                for (raw in lines) {
                    if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        onComplete(stripThinking(accumulator.toString()))
                        return
                    }
                    if (payload.isEmpty()) continue
                    try {
                        val json = JSONObject(payload)
                        val chunk = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content", "") ?: ""
                        if (chunk.isNotEmpty()) {
                            accumulator.append(chunk)
                            onChunk(chunk)
                        }
                        // Some providers include usage in the final chunk before [DONE]
                        val usage = json.optJSONObject("usage")
                        if (usage != null) {
                            val prompt = usage.optInt("prompt_tokens", 0)
                            val completion = usage.optInt("completion_tokens", 0)
                            if (prompt > 0 || completion > 0) {
                                cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(prompt, completion)
                            }
                        }
                    } catch (_: Exception) { /* skip */ }
                }
            }
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onComplete(stripThinking(accumulator.toString()))
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onError(friendlyNetworkError(providerName, e, isLocal = isLocal))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOllamaChat(
        messages: List<ChatMessage>,
        model: String,
        baseUrl: String,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        systemPrompt: String = "",
        temperature: Float? = null
    ): String {
        val url = URL("$baseUrl/api/chat")
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            if (temperature != null) put("options", JSONObject().put("temperature", temperature.toDouble()))
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            for (m in messages) {
                arr.put(buildOllamaMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                return friendlyHttpError("Ollama", conn.responseCode, errBody)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val prompt = json.optInt("prompt_eval_count", 0)
            val completion = json.optInt("eval_count", 0)
            if (prompt > 0 || completion > 0) {
                cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(prompt, completion)
            }
            val text = stripThinking(
                json.optJSONObject("message")?.optString("content", "")?.trim() ?: ""
            )
            return if (text.isEmpty()) "[Ollama: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError("Ollama", e, isLocal = true)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Native Gemini multi-turn chat. Unlike processWithModel this does NOT inject the
     * "Improve this text" edit instruction — it's meant for conversational use.
     * The lorebook (if set) is passed as a systemInstruction.
     */
    private fun callGeminiChat(
        messages: List<ChatMessage>,
        model: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        temperature: Float? = null
    ): String {
        val apiKey = SecureApiKeys.getKey(Settings.PREF_GEMINI_API_KEY)
        if (apiKey.isBlank()) return "[Set your Gemini API key in keyboard settings]"

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val body = JSONObject().apply {
            val contents = JSONArray()
            for (m in messages) {
                val role = if (m.role == "assistant") "model" else "user"
                contents.put(JSONObject().apply {
                    put("role", role)
                    put("parts", buildGeminiPartsArray(m))
                })
            }
            put("contents", contents)
            val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
            if (lorebook.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", "About the user:\n$lorebook")))
                })
            }
            if (temperature != null) {
                put("generationConfig", JSONObject().put("temperature", temperature.toDouble()))
            }
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val error = conn.errorStream?.bufferedReader()?.readText()
                return friendlyHttpError("Gemini", conn.responseCode, error)
            }
            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val geminiUsage = json.optJSONObject("usageMetadata")
            if (geminiUsage != null) {
                val p = geminiUsage.optInt("promptTokenCount", 0)
                val c = geminiUsage.optInt("candidatesTokenCount", 0)
                if (p > 0 || c > 0) cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(p, c)
            }
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
            return if (text.isNullOrEmpty()) "[Gemini: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError("Gemini", e, isLocal = false)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOpenAICompatibleChat(
        messages: List<ChatMessage>,
        model: String,
        endpoint: String,
        apiKey: String,
        providerName: String,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        requireApiKey: Boolean = true,
        isLocal: Boolean = false,
        systemPrompt: String = "",
        temperature: Float? = null
    ): String {
        if (requireApiKey && apiKey.isBlank()) return "[Set your $providerName API key in keyboard settings]"

        val url = URL(endpoint)
        val body = JSONObject().apply {
            put("model", model)
            if (temperature != null) put("temperature", temperature.toDouble())
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            for (m in messages) {
                arr.put(buildOpenAIMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = if (isLocal) 120000 else 60000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val error = conn.errorStream?.bufferedReader()?.readText()
                return friendlyHttpError(providerName, conn.responseCode, error)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val oaiUsage = json.optJSONObject("usage")
            if (oaiUsage != null) {
                val p = oaiUsage.optInt("prompt_tokens", 0)
                val c = oaiUsage.optInt("completion_tokens", 0)
                if (p > 0 || c > 0) cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(p, c)
            }
            val rawText = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
            val text = rawText?.let { stripThinking(it) }
            return if (text.isNullOrEmpty()) "[$providerName: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError(providerName, e, isLocal = isLocal)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callAnthropicChat(
        messages: List<ChatMessage>,
        model: String,
        apiKey: String,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        systemPrompt: String = "",
        temperature: Float? = null
    ): String {
        if (apiKey.isBlank()) return "[Set your Anthropic API key in keyboard settings]"

        val url = URL("https://api.anthropic.com/v1/messages")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (temperature != null) put("temperature", temperature.toDouble())
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            val arr = JSONArray()
            for (m in messages) {
                if (m.role == "system") continue
                arr.put(buildAnthropicMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val error = conn.errorStream?.bufferedReader()?.readText()
                return friendlyHttpError("Anthropic", conn.responseCode, error)
            }
            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val contentArr = json.optJSONArray("content") ?: return "[Anthropic: empty response]"
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    sb.append(block.optString("text", ""))
                }
            }
            val text = stripThinking(sb.toString().trim())
            // Parse token usage
            val usage = json.optJSONObject("usage")
            if (usage != null && cancelHandle != null) {
                cancelHandle.tokenUsage = AiCancelRegistry.TokenUsage(
                    promptTokens = usage.optInt("input_tokens", 0),
                    completionTokens = usage.optInt("output_tokens", 0)
                )
            }
            return if (text.isEmpty()) "[Anthropic: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError("Anthropic", e, isLocal = false)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callAnthropicChatStream(
        messages: List<ChatMessage>,
        model: String,
        apiKey: String,
        systemPrompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        temperature: Float? = null
    ) {
        if (apiKey.isBlank()) {
            onError("[Set your Anthropic API key in keyboard settings]"); return
        }
        val url = URL("https://api.anthropic.com/v1/messages")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            if (temperature != null) put("temperature", temperature.toDouble())
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            val arr = JSONArray()
            for (m in messages) {
                if (m.role == "system") continue
                arr.put(buildAnthropicMessageJson(m))
            }
            put("messages", arr)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        val accumulator = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                onError(friendlyHttpError("Anthropic", conn.responseCode, errBody))
                return
            }
            conn.inputStream.bufferedReader().boundedLines { lines ->
                for (raw in lines) {
                    if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    try {
                        val json = JSONObject(payload)
                        val type = json.optString("type", "")
                        when (type) {
                            "content_block_delta" -> {
                                val delta = json.optJSONObject("delta")
                                if (delta?.optString("type") == "text_delta") {
                                    val text = delta.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        accumulator.append(text)
                                        onChunk(text)
                                    }
                                }
                            }
                            "message_delta" -> {
                                val usage = json.optJSONObject("usage")
                                if (usage != null) {
                                    val output = usage.optInt("output_tokens", 0)
                                    val existing = cancelHandle?.tokenUsage
                                    cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(existing?.promptTokens ?: 0, output)
                                }
                            }
                            "message_start" -> {
                                val usage = json.optJSONObject("message")?.optJSONObject("usage")
                                if (usage != null) {
                                    val input = usage.optInt("input_tokens", 0)
                                    val existing = cancelHandle?.tokenUsage
                                    cancelHandle?.tokenUsage = AiCancelRegistry.TokenUsage(input, existing?.completionTokens ?: 0)
                                }
                            }
                            "message_stop" -> {
                                onComplete(stripThinking(accumulator.toString()))
                                return
                            }
                            "error" -> {
                                val msg = json.optJSONObject("error")?.optString("message") ?: "stream error"
                                onError("[Anthropic: $msg]")
                                return
                            }
                        }
                    } catch (_: Exception) { /* skip */ }
                }
            }
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onComplete(stripThinking(accumulator.toString()))
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) onError("")
            else onError(friendlyNetworkError("Anthropic", e, isLocal = false))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    data class ModelDetails(
        val parentModel: String,
        val system: String,
        val temperature: Float,
        val numCtx: Int
    )

    @JvmStatic
    fun fetchModelDetails(baseUrl: String, modelName: String): ModelDetails? {
        val url = URL("$baseUrl/api/show")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        try {
            val body = JSONObject().apply { put("name", modelName) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val response = readBounded(conn.inputStream)
            val json = JSONObject(response)
            val details = json.optJSONObject("details")
            val parentModel = details?.optString("parent_model", "") ?: ""
            val system = json.optString("system", "")
            val modelfile = json.optString("modelfile", "")
            var temperature = 0.2f
            var numCtx = 4096
            for (line in modelfile.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("PARAMETER temperature")) {
                    temperature = trimmed.substringAfterLast(" ").toFloatOrNull() ?: 0.2f
                } else if (trimmed.startsWith("PARAMETER num_ctx")) {
                    numCtx = trimmed.substringAfterLast(" ").toIntOrNull() ?: 4096
                }
            }
            return ModelDetails(parentModel, system, temperature, numCtx)
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    data class ModelsSplit(val baseModels: List<String>, val customModels: List<String>)

    @JvmStatic
    fun fetchModelsSplit(baseUrl: String): ModelsSplit {
        val allModels = fetchOllamaModels(baseUrl)
        val base = mutableListOf<String>()
        val custom = mutableListOf<String>()
        for (model in allModels) {
            val details = fetchModelDetails(baseUrl, model)
            if (details != null && details.parentModel.isNotBlank()) {
                custom.add(model)
            } else {
                base.add(model)
            }
        }
        return ModelsSplit(base, custom)
    }

    @JvmStatic
    fun createModel(baseUrl: String, name: String, from: String, system: String, temperature: Float, numCtx: Int): String? {
        val url = URL("$baseUrl/api/create")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000
        try {
            val body = JSONObject().apply {
                put("model", name)
                put("from", from)
                if (system.isNotBlank()) {
                    put("system", system)
                }
                put("parameters", JSONObject().apply {
                    put("temperature", temperature.toDouble())
                    put("num_ctx", numCtx)
                    put("top_p", 0.9)
                    put("repeat_penalty", 1.1)
                    put("num_gpu", 99)
                })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                return error
            }
            // Ollama streams status lines; read all to completion
            readBounded(conn.inputStream)
            clearModelCache()
            return null // success
        } catch (e: Exception) {
            return e.message ?: "Unknown error"
        } finally {
            conn.disconnect()
        }
    }

    @JvmStatic
    fun deleteModel(baseUrl: String, name: String): Boolean {
        val url = URL("$baseUrl/api/delete")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        try {
            val body = JSONObject().apply { put("name", name) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            return conn.responseCode == 200
        } catch (e: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streaming variant for the clipboard AI preview flow.
     * For Ollama: real token streaming via /api/generate with stream=true.
     * For other backends: falls back to non-streaming and emits the full result as one chunk.
     * Callbacks are invoked on the calling thread; caller is responsible for posting to main if needed.
     */
    @JvmStatic
    @JvmOverloads
    fun processWithModelAndInstructionStream(
        text: String,
        aiModel: String,
        instruction: String,
        prefs: SharedPreferences,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ) {
        // Cloud fallback: substitute local model if fallback is active
        val effectiveModel = run {
            checkCloudFallback(prefs)
            val backend = aiModel.substringBefore(":")
            if (isLocalBackend(backend) && isCloudFallbackActive(prefs)) {
                resolveCloudFallbackModel(prefs) ?: aiModel
            } else aiModel
        }
        @Suppress("NAME_SHADOWING")
        val aiModel = effectiveModel

        val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
        val fullInstruction = if (lorebook.isNotBlank()) {
            "About the user:\n$lorebook\n\n$instruction"
        } else {
            instruction
        }
        val prompt = "$fullInstruction\n\nText:\n$text"

        val colonIndex = aiModel.indexOf(':')
        if (colonIndex <= 0) {
            // Fallback old prefs format — use non-streaming
            val result = processWithModelAndInstruction(text, aiModel, instruction, prefs, cancelHandle)
            if (result.startsWith("[") && result.endsWith("]")) {
                onError(result)
            } else {
                onChunk(result)
                onComplete()
            }
            return
        }

        val backend = aiModel.substring(0, colonIndex)
        val model = aiModel.substring(colonIndex + 1)

        if (backend == "ollama") {
            val baseUrl = resolveOllamaBaseUrl(prefs)
            val ollamaPrompt = if (isCustomOllamaModel(baseUrl, model)) {
                if (lorebook.isNotBlank()) "About the user:\n$lorebook\n\n$text" else text
            } else prompt
            callOllamaStream(ollamaPrompt, model, baseUrl, onChunk, onComplete, onError, cancelHandle)
            return
        }

        // Fallback for cloud / ONNX: run non-streaming, emit whole result
        try {
            val result = processWithModelAndInstruction(text, aiModel, instruction, prefs, cancelHandle)
            if (result.startsWith("[") && result.endsWith("]")) {
                onError(result)
            } else {
                onChunk(result)
                onComplete()
            }
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    private fun callOllamaStream(
        prompt: String,
        model: String,
        baseUrl: String,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ) {
        val url = URL("$baseUrl/api/generate")
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("stream", true)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) {
                    onError("")
                } else {
                    val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    onError(friendlyHttpError("Ollama", conn.responseCode, errBody))
                }
                return
            }

            conn.inputStream.bufferedReader().boundedLines { lines ->
                for (line in lines) {
                    if (cancelHandle?.cancelled?.get() == true) {
                        onError("")
                        return
                    }
                    if (line.isBlank()) continue
                    try {
                        val json = JSONObject(line)
                        val chunk = json.optString("response", "")
                        if (chunk.isNotEmpty()) onChunk(chunk)
                        if (json.optBoolean("done", false)) {
                            onComplete()
                            return
                        }
                    } catch (_: Exception) {
                        // Skip malformed line
                    }
                }
            }
            if (cancelHandle?.cancelled?.get() == true) {
                onError("")
            } else {
                onComplete()
            }
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) {
                onError("")
            } else {
                onError(friendlyNetworkError("Ollama", e, isLocal = true))
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOllama(
        prompt: String,
        model: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        val baseUrl = resolveOllamaBaseUrl(prefs)

        val url = URL("$baseUrl/api/generate")
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("stream", false)
        }

        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ""
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                return friendlyHttpError("Ollama", conn.responseCode, errBody)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return ""
            val json = JSONObject(response)
            val text = stripThinking(json.optString("response", "").trim())
            return if (text.isEmpty()) "[Ollama: empty or unexpected response]" else text
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return ""
            return friendlyNetworkError("Ollama", e, isLocal = true)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    @JvmStatic
    @JvmOverloads
    fun transcribeWithWhisper(
        audioFile: java.io.File,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        val baseUrl = getWhisperBaseUrl(prefs)
        val whisperModel = prefs.getString(Settings.PREF_WHISPER_MODEL, Defaults.PREF_WHISPER_MODEL) ?: Defaults.PREF_WHISPER_MODEL

        // Try OpenAI-compat endpoint first (Speaches, faster-whisper-server, openai-whisper-server)
        val r1 = tryWhisperEndpoint(audioFile, "$baseUrl/v1/audio/transcriptions", whisperModel, includeModelField = true, cancelHandle)
        if (r1.success) {
            audioFile.delete()
            return r1.text
        }
        if (cancelHandle?.cancelled?.get() == true) {
            audioFile.delete()
            return ""
        }
        // Fall back to whisper.cpp /inference only when the route is clearly missing.
        val shouldFallback = r1.httpCode == 404 || r1.httpCode == 405 || r1.httpCode == 501
        if (!shouldFallback) {
            audioFile.delete()
            return r1.text
        }
        Log.d("Whisper", "v1/audio/transcriptions HTTP ${r1.httpCode}, trying /inference")
        val r2 = tryWhisperEndpoint(audioFile, "$baseUrl/inference", whisperModel, includeModelField = false, cancelHandle)
        audioFile.delete()
        if (r2.success) return r2.text
        return r2.text
    }

    private data class WhisperResult(val success: Boolean, val text: String, val httpCode: Int, val rawBody: String = "")

    private fun tryWhisperEndpoint(
        audioFile: java.io.File,
        endpoint: String,
        whisperModel: String,
        includeModelField: Boolean,
        cancelHandle: AiCancelRegistry.CancelHandle?
    ): WhisperResult {
        val boundary = "----WhisperBoundary${System.currentTimeMillis()}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        try {
            conn.outputStream.use { out ->
                val writer = out.bufferedWriter()

                // file field
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                writer.write("Content-Type: audio/wav\r\n\r\n")
                writer.flush()
                audioFile.inputStream().use { it.copyTo(out) }
                out.flush()
                writer.write("\r\n")

                if (includeModelField) {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                    writer.write("$whisperModel\r\n")
                }

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
                writer.write("json\r\n")

                writer.write("--$boundary--\r\n")
                writer.flush()
            }

            val code = conn.responseCode
            if (code != 200) {
                if (cancelHandle?.cancelled?.get() == true) return WhisperResult(false, "", code)
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null } ?: ""
                Log.w("Whisper", "$endpoint HTTP $code body=$errorBody")
                return WhisperResult(false, friendlyHttpError("Whisper", code, errorBody), code, errorBody)
            }

            val response = readBounded(conn.inputStream)
            if (cancelHandle?.cancelled?.get() == true) return WhisperResult(false, "", code)
            val text = try {
                JSONObject(response).optString("text", response).trim()
            } catch (_: Exception) {
                // /inference with response_format=text returns raw text
                response.trim()
            }
            return WhisperResult(true, text, code)
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) return WhisperResult(false, "", -1)
            Log.w("Whisper", "$endpoint exception: ${e.message}")
            return WhisperResult(false, friendlyNetworkError("Whisper", e, isLocal = true), -1)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // --- Whisper URL fallback resolver ----------------------------------------
    @Volatile private var cachedWhisperUrlKey: String = ""
    @Volatile private var cachedWhisperResolvedUrl: String = ""
    @Volatile private var cachedWhisperResolvedExpiry: Long = 0L

    private fun probeWhisper(baseUrl: String, timeoutMs: Int = 1500): Boolean {
        if (baseUrl.isBlank()) return false
        var conn: HttpURLConnection? = null
        return try {
            val u = URL("$baseUrl/v1/models")
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun getWhisperBaseUrl(prefs: SharedPreferences): String {
        val primary = normalizeOllamaUrl(
            prefs.getString(Settings.PREF_WHISPER_URL, Defaults.PREF_WHISPER_URL) ?: ""
        ).ifBlank {
            // Default: derive from Ollama host on port 8080
            val ollamaUrl = resolveOllamaBaseUrl(prefs)
            try {
                val parsed = java.net.URI(ollamaUrl)
                "${parsed.scheme}://${parsed.host}:8080"
            } catch (_: Exception) {
                "http://localhost:8080"
            }
        }
        val fallback = normalizeOllamaUrl(
            prefs.getString(Settings.PREF_WHISPER_URL_FALLBACK, Defaults.PREF_WHISPER_URL_FALLBACK) ?: ""
        )
        if (fallback.isBlank()) return primary

        val key = "$primary|$fallback"
        val now = System.currentTimeMillis()
        if (key == cachedWhisperUrlKey && now < cachedWhisperResolvedExpiry && cachedWhisperResolvedUrl.isNotEmpty()) {
            return cachedWhisperResolvedUrl
        }
        val chosen = when {
            probeWhisper(primary) -> primary
            probeWhisper(fallback) -> fallback
            else -> primary
        }
        cachedWhisperUrlKey = key
        cachedWhisperResolvedUrl = chosen
        cachedWhisperResolvedExpiry = now + OLLAMA_URL_CACHE_MS
        return chosen
    }

    @JvmStatic
    @JvmOverloads
    fun fetchOpenAiCompatibleModels(baseUrl: String, apiKey: String = ""): List<String> {
        if (baseUrl.isBlank()) return emptyList()
        val url = URL("$baseUrl/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        try {
            if (conn.responseCode != 200) return emptyList()
            val response = readBounded(conn.inputStream)
            val json = JSONObject(response)
            val data = json.optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull {
                data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun fetchWhisperModels(baseUrl: String): List<String> {
        val url = URL("$baseUrl/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        try {
            if (conn.responseCode != 200) return emptyList()
            val response = readBounded(conn.inputStream)
            val json = JSONObject(response)
            val data = json.getJSONArray("data")
            return (0 until data.length()).map { data.getJSONObject(it).getString("id") }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun downloadWhisperModel(baseUrl: String, modelId: String): String? {
        val url = URL("$baseUrl/v1/models/${java.net.URLEncoder.encode(modelId, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 600000 // 10 min for large model downloads
        try {
            val response = readBounded(conn.inputStream)
            return if (conn.responseCode == 200 || conn.responseCode == 201) null
            else "Error ${conn.responseCode}: $response"
        } catch (e: Exception) {
            return e.message ?: "Unknown error"
        } finally {
            conn.disconnect()
        }
    }

    fun deleteWhisperModel(baseUrl: String, modelId: String): Boolean {
        val url = URL("$baseUrl/v1/models/${java.net.URLEncoder.encode(modelId, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        return try {
            conn.responseCode == 200 || conn.responseCode == 204
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }


    private const val PROMPT_SCHRIJVER_SYSTEM = """BELANGRIJKSTE REGEL: Je output begint ALTIJD direct met "Je bent een..." en eindigt met de laatste instructie. NOOIT inleidingen, afsluitingen, voorbeelden, kopjes, of scheidingslijnen toevoegen. NOOIT woorden als "Natuurlijk", "Hier is", "Voorbeeld", "Prompt:", "---" gebruiken.

Je bent een expert in Prompt Engineering voor AI-assistenten. De gebruiker geeft jou een korte, simpele wens. Jouw taak is om deze wens te vertalen naar een uitgebreide, professionele en veilige SYSTEM prompt die een ander AI-model zal instrueren.

Regels:
- Schrijf in de gebiedende wijs (bijv: 'Je bent een...', 'Je schrijft altijd...').
- Structureer de prompt logisch met duidelijke instructies over toon en format.
- Geef UITSLUITEND de system prompt terug. Niets anders."""

    @JvmStatic
    @JvmOverloads
    fun generateSystemPromptWithModel(
        description: String,
        aiModel: String,
        prefs: SharedPreferences,
        cancelHandle: AiCancelRegistry.CancelHandle? = null
    ): String {
        val instruction = PROMPT_SCHRIJVER_SYSTEM + "\n\nSchrijf een system prompt voor een AI-model met deze beschrijving:"
        return processWithModelAndInstruction(description, aiModel, instruction, prefs, cancelHandle)
    }

    @JvmStatic
    fun generateSystemPrompt(baseUrl: String, model: String, description: String): String {
        val prompt = "Schrijf een system prompt voor een AI-model met deze beschrijving: $description"

        val url = URL("$baseUrl/api/generate")
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("system", PROMPT_SCHRIJVER_SYSTEM)
            put("stream", false)
        }

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 120000

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
            throw Exception("Ollama fout: $error")
        }

        val response = readBounded(conn.inputStream)
        val json = JSONObject(response)
        return json.optString("response", "").trim()
    }

    data class PullProgress(val status: String, val completed: Long, val total: Long)

    @JvmStatic
    fun pullModel(baseUrl: String, modelName: String, onProgress: (PullProgress) -> Unit): String? {
        val url = URL("$baseUrl/api/pull")
        val body = JSONObject().apply {
            put("name", modelName)
            put("stream", true)
        }

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 600000

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText()
                if (errorBody != null) {
                    // Try to extract readable error from JSON response
                    try {
                        val errorJson = JSONObject(errorBody)
                        val errorMsg = errorJson.optString("error", "")
                        if (errorMsg.isNotBlank()) return errorMsg
                    } catch (_: Exception) { }
                    return errorBody
                }
                return "HTTP ${conn.responseCode}"
            }

            conn.inputStream.bufferedReader().boundedLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    val error = json.optString("error", "")
                    if (error.isNotBlank()) return error
                    val status = json.optString("status", "")
                    val completed = json.optLong("completed", 0)
                    val total = json.optLong("total", 0)
                    onProgress(PullProgress(status, completed, total))
                }
            }
            clearModelCache()
            return null
        } catch (e: Exception) {
            return e.message ?: "Unknown error"
        } finally {
            conn.disconnect()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Tool calling (v1)
    //
    // Only Ollama + Anthropic in this round. The loop runs at most 5
    // rounds: model → tool calls → local execute → feed results back → …
    // Each tool invocation is echoed inline via [onChunk] so the user sees
    // what's happening without any new bubble UI.
    // ════════════════════════════════════════════════════════════════════

    @JvmStatic
    fun backendSupportsTools(aiModel: String): Boolean {
        // Everything except ONNX (local tiny models, no tool-calling support)
        return !aiModel.startsWith("onnx:")
    }

    private enum class ToolProvider { GEMINI, OPENAI, ANTHROPIC, OLLAMA }

    /**
     * Return a deep copy of a tool's JSON Schema, sanitized for a specific
     * provider's tool-declaration format. Most providers accept draft-07
     * JSON Schema, but Gemini uses an OpenAPI 3.0 subset that rejects e.g.
     * `type: ["number", "string"]` union arrays. Never mutates the input.
     */
    private fun sanitizeSchemaForProvider(schema: JSONObject, provider: ToolProvider): JSONObject {
        val copy = JSONObject(schema.toString())
        when (provider) {
            ToolProvider.GEMINI -> {
                // Gemini's Schema type is an OpenAPI 3.0 subset — collapse
                // every `type` array to a single string, and strip keywords
                // it doesn't understand (oneOf/anyOf/allOf/not, const, etc.).
                sanitizeForGeminiInPlace(copy)
            }
            ToolProvider.OPENAI,
            ToolProvider.ANTHROPIC,
            ToolProvider.OLLAMA -> {
                // These accept draft-07 / JSON Schema including `type` arrays.
                // Passthrough. (OpenAI 'strict' function-calling mode would
                // need extra sanitization, but we don't enable strict mode.)
            }
        }
        return copy
    }

    private fun sanitizeForGeminiInPlace(node: Any?) {
        when (node) {
            is JSONObject -> {
                val t = node.opt("type")
                if (t is JSONArray && t.length() > 0) {
                    val types = (0 until t.length()).map { t.optString(it) }
                    // Prefer the most permissive numeric form, then string.
                    val chosen = when {
                        "number" in types -> "number"
                        "integer" in types -> "integer"
                        "string" in types -> "string"
                        else -> types.first()
                    }
                    node.put("type", chosen)
                }
                // Drop keywords Gemini's schema doesn't support.
                for (k in listOf("oneOf", "anyOf", "allOf", "not", "const", "\$ref", "\$schema")) {
                    if (node.has(k)) node.remove(k)
                }
                val keys = node.keys().asSequence().toList() // snapshot — we may mutate
                for (k in keys) sanitizeForGeminiInPlace(node.opt(k))
            }
            is JSONArray -> {
                for (i in 0 until node.length()) sanitizeForGeminiInPlace(node.opt(i))
            }
        }
    }

    private fun buildToolsForOllama(tools: List<AiToolRegistry.ToolDef>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", sanitizeSchemaForProvider(t.parametersSchema, ToolProvider.OLLAMA))
                })
            })
        }
        return arr
    }

    private fun buildToolsForAnthropic(tools: List<AiToolRegistry.ToolDef>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", sanitizeSchemaForProvider(t.parametersSchema, ToolProvider.ANTHROPIC))
            })
        }
        return arr
    }

    /** Gemini v1beta format: functionDeclarations with name/description/parameters. */
    private fun buildToolsForGemini(tools: List<AiToolRegistry.ToolDef>): JSONArray {
        val decls = JSONArray()
        for (t in tools) {
            decls.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("parameters", sanitizeSchemaForProvider(t.parametersSchema, ToolProvider.GEMINI))
            })
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    /**
     * MCP tool-name prefix. An MCP tool's name is exposed to the LLM as
     * `mcp__<serverId>__<toolName>`. Underscores (not hyphens or colons)
     * because some providers restrict tool names to `[a-zA-Z0-9_-]`.
     */
    private const val MCP_PREFIX = "mcp__"

    private data class McpRouteEntry(
        val server: McpRegistry.McpServer,
        val originalName: String
    )

    /**
     * Build the combined tool list (built-ins + MCP) and a routing map for
     * dispatching tool calls. Any MCP server that fails to list tools is
     * silently skipped — we don't want a broken server to break chat.
     */
    private fun buildCombinedTools(
        prefs: SharedPreferences,
        allowNetwork: Boolean,
        allowActions: Boolean
    ): Pair<List<AiToolRegistry.ToolDef>, Map<String, McpRouteEntry>> {
        val combined = mutableListOf<AiToolRegistry.ToolDef>()
        combined.addAll(AiToolRegistry.availableTools(allowNetwork, allowActions))
        val routeMap = mutableMapOf<String, McpRouteEntry>()
        val servers = try { McpRegistry.listEnabledServers(prefs) } catch (_: Exception) { emptyList() }
        for (server in servers) {
            val mcpTools = try { McpRegistry.getToolsForServer(server) } catch (_: Exception) { emptyList() }
            for (mt in mcpTools) {
                val exposedName = "$MCP_PREFIX${server.id}__${mt.name}"
                // Guard: some providers cap tool names around 64 chars
                val safeName = if (exposedName.length > 64) exposedName.take(64) else exposedName
                combined.add(
                    AiToolRegistry.ToolDef(
                        name = safeName,
                        description = "[${server.name}] ${mt.description}",
                        parametersSchema = mt.inputSchema
                    )
                )
                routeMap[safeName] = McpRouteEntry(server, mt.name)
            }
        }
        return combined to routeMap
    }

    /**
     * Result shape from one round of a tool-capable backend call.
     * Either [text] is non-empty (model produced a final answer) OR
     * [toolCalls] is non-empty (model wants us to run tools first).
     */
    private data class ToolRoundResult(
        val text: String,
        val toolCalls: List<AiToolRegistry.ToolCall>,
        val error: String? = null
    )

    private fun callOllamaChatWithToolsRound(
        messages: List<ChatMessage>,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        tools: List<AiToolRegistry.ToolDef>,
        cancelHandle: AiCancelRegistry.CancelHandle?,
        temperature: Float? = null
    ): ToolRoundResult {
        val url = URL("$baseUrl/api/chat")
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            if (temperature != null) put("options", JSONObject().put("temperature", temperature.toDouble()))
            put("tools", buildToolsForOllama(tools))
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
            }
            for (m in messages) arr.put(buildOllamaMessageJson(m))
            put("messages", arr)
        }
        Log.d("AiToolCall", "Ollama REQUEST → model=$model messages=${messages.size} tools=${tools.size}")
        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 180000
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                ToolRoundResult("", emptyList(), friendlyHttpError("Ollama", conn.responseCode, errBody))
            } else {
                val response = readBounded(conn.inputStream)
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                Log.d("AiToolCall", "Ollama RESPONSE → status=${conn.responseCode} len=${response.length}")
                val json = JSONObject(response)
                // Accumulate token usage across rounds
                val p = json.optInt("prompt_eval_count", 0)
                val c = json.optInt("eval_count", 0)
                if ((p > 0 || c > 0) && cancelHandle != null) {
                    val prev = cancelHandle.tokenUsage
                    cancelHandle.tokenUsage = AiCancelRegistry.TokenUsage(
                        (prev?.promptTokens ?: 0) + p,
                        (prev?.completionTokens ?: 0) + c
                    )
                }
                val msg = json.optJSONObject("message")
                val text = msg?.optString("content", "") ?: ""
                val calls = mutableListOf<AiToolRegistry.ToolCall>()
                val toolCallsArr = msg?.optJSONArray("tool_calls")
                if (toolCallsArr != null) {
                    for (i in 0 until toolCallsArr.length()) {
                        val tc = toolCallsArr.optJSONObject(i) ?: continue
                        val fn = tc.optJSONObject("function") ?: continue
                        val name = fn.optString("name")
                        if (name.isBlank()) continue
                        val argsRaw = fn.opt("arguments")
                        val args = when (argsRaw) {
                            is JSONObject -> argsRaw
                            is String -> try { JSONObject(argsRaw) } catch (_: Exception) { JSONObject() }
                            else -> JSONObject()
                        }
                        calls.add(AiToolRegistry.ToolCall(AiToolRegistry.newCallId(), name, args))
                    }
                }
                ToolRoundResult(text, calls)
            }
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) ToolRoundResult("", emptyList(), "")
            else ToolRoundResult("", emptyList(), friendlyNetworkError("Ollama", e, isLocal = true))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callAnthropicChatWithToolsRound(
        messages: List<ChatMessage>,
        model: String,
        apiKey: String,
        systemPrompt: String,
        tools: List<AiToolRegistry.ToolDef>,
        cancelHandle: AiCancelRegistry.CancelHandle?,
        temperature: Float? = null
    ): ToolRoundResult {
        if (apiKey.isBlank()) return ToolRoundResult("", emptyList(), "[Set your Anthropic API key in keyboard settings]")
        val url = URL("https://api.anthropic.com/v1/messages")
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (temperature != null) put("temperature", temperature.toDouble())
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("tools", buildToolsForAnthropic(tools))
            val arr = JSONArray()
            for (m in messages) {
                if (m.role == "system") continue
                arr.put(buildAnthropicMessageJson(m))
            }
            put("messages", arr)
        }
        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                ToolRoundResult("", emptyList(), friendlyHttpError("Anthropic", conn.responseCode, errBody))
            } else {
                val response = readBounded(conn.inputStream)
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val json = JSONObject(response)
                val contentArr = json.optJSONArray("content") ?: return ToolRoundResult("", emptyList(), "[Anthropic: empty response]")
                // Accumulate token usage across rounds
                val usage = json.optJSONObject("usage")
                if (usage != null && cancelHandle != null) {
                    val inp = usage.optInt("input_tokens", 0)
                    val out = usage.optInt("output_tokens", 0)
                    val prev = cancelHandle.tokenUsage
                    cancelHandle.tokenUsage = AiCancelRegistry.TokenUsage(
                        (prev?.promptTokens ?: 0) + inp,
                        (prev?.completionTokens ?: 0) + out
                    )
                }
                val sb = StringBuilder()
                val calls = mutableListOf<AiToolRegistry.ToolCall>()
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> sb.append(block.optString("text", ""))
                        "tool_use" -> {
                            val id = block.optString("id")
                            val name = block.optString("name")
                            val input = block.optJSONObject("input") ?: JSONObject()
                            if (name.isNotBlank() && id.isNotBlank()) {
                                calls.add(AiToolRegistry.ToolCall(id, name, input))
                            }
                        }
                    }
                }
                ToolRoundResult(sb.toString(), calls)
            }
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) ToolRoundResult("", emptyList(), "")
            else ToolRoundResult("", emptyList(), friendlyNetworkError("Anthropic", e, isLocal = false))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun callOpenAICompatibleChatWithToolsRound(
        messages: List<ChatMessage>,
        model: String,
        endpoint: String,
        apiKey: String,
        providerName: String,
        systemPrompt: String,
        requireApiKey: Boolean,
        isLocal: Boolean,
        tools: List<AiToolRegistry.ToolDef>,
        cancelHandle: AiCancelRegistry.CancelHandle?,
        temperature: Float? = null
    ): ToolRoundResult {
        if (requireApiKey && apiKey.isBlank()) {
            return ToolRoundResult("", emptyList(), "[Set your $providerName API key in keyboard settings]")
        }
        val url = URL(endpoint)
        val body = JSONObject().apply {
            put("model", model)
            if (temperature != null) put("temperature", temperature.toDouble())
            put("tools", JSONArray().apply {
                for (t in tools) {
                    put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", sanitizeSchemaForProvider(t.parametersSchema, ToolProvider.OPENAI))
                        })
                    })
                }
            })
            val arr = JSONArray()
            if (systemPrompt.isNotBlank()) {
                arr.put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
            }
            for (m in messages) arr.put(buildOpenAIMessageJson(m))
            put("messages", arr)
        }
        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = if (isLocal) 180000 else 120000
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                ToolRoundResult("", emptyList(), friendlyHttpError(providerName, conn.responseCode, errBody))
            } else {
                val response = readBounded(conn.inputStream)
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val json = JSONObject(response)
                // Accumulate token usage across rounds
                val usage = json.optJSONObject("usage")
                if (usage != null && cancelHandle != null) {
                    val inp = usage.optInt("prompt_tokens", 0)
                    val out = usage.optInt("completion_tokens", 0)
                    val prev = cancelHandle.tokenUsage
                    cancelHandle.tokenUsage = AiCancelRegistry.TokenUsage(
                        (prev?.promptTokens ?: 0) + inp,
                        (prev?.completionTokens ?: 0) + out
                    )
                }
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content", "") ?: ""
                val calls = mutableListOf<AiToolRegistry.ToolCall>()
                val toolCallsArr = msg?.optJSONArray("tool_calls")
                android.util.Log.i(
                    "AiToolCall",
                    "[$providerName] round tools_offered=${tools.size}(${tools.joinToString(",") { it.name }}) " +
                        "native_tool_calls=${toolCallsArr?.length() ?: 0} text_len=${text.length}"
                )
                if (toolCallsArr != null) {
                    for (i in 0 until toolCallsArr.length()) {
                        val tc = toolCallsArr.optJSONObject(i) ?: continue
                        val id = tc.optString("id", AiToolRegistry.newCallId())
                        val fn = tc.optJSONObject("function") ?: continue
                        val name = fn.optString("name")
                        if (name.isBlank()) continue
                        val argsStr = fn.optString("arguments", "{}")
                        val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                        calls.add(AiToolRegistry.ToolCall(id, name, args))
                    }
                }
                // Fallback: some models (notably Llama 4 Scout on Groq) emit
                // pseudo tool calls as plain text wrapped in mathematical
                // brackets ⟦…⟧ instead of using the proper tool_calls field.
                // Extract those and strip the markers from the visible text.
                if (calls.isEmpty() && text.isNotEmpty()) {
                    val (strippedText, extracted) = extractInlinePseudoToolCalls(text)
                    if (extracted.isNotEmpty()) {
                        calls.addAll(extracted)
                        ToolRoundResult(strippedText, calls)
                    } else {
                        ToolRoundResult(text, calls)
                    }
                } else {
                    ToolRoundResult(text, calls)
                }
            }
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) ToolRoundResult("", emptyList(), "")
            else ToolRoundResult("", emptyList(), friendlyNetworkError(providerName, e, isLocal = isLocal))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Gemini tool-round. Gemini uses a different shape than OpenAI/Anthropic:
     * assistant messages with function calls use `role="model"` + `parts`
     * containing `functionCall`, and tool results use `role="user"` + `parts`
     * with `functionResponse`. We rebuild the `contents` array from scratch
     * each round instead of reusing [buildGeminiPartsArray], because multimodal
     * attachments don't mix with tool calling in v1.
     */
    private fun callGeminiChatWithToolsRound(
        messages: List<ChatMessage>,
        model: String,
        apiKey: String,
        systemPrompt: String,
        tools: List<AiToolRegistry.ToolDef>,
        cancelHandle: AiCancelRegistry.CancelHandle?,
        temperature: Float? = null
    ): ToolRoundResult {
        if (apiKey.isBlank()) return ToolRoundResult("", emptyList(), "[Set your Gemini API key in keyboard settings]")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val contents = JSONArray()
        for (m in messages) {
            if (m.role == "system") continue
            when (m.role) {
                "tool" -> {
                    // functionResponse part
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", m.toolName ?: "")
                                put("response", JSONObject().put("content", m.content))
                            })
                        }))
                    })
                }
                "assistant" -> {
                    val parts = JSONArray()
                    if (m.content.isNotEmpty()) {
                        parts.put(JSONObject().put("text", m.content))
                    }
                    if (m.toolCalls != null) {
                        for (tc in m.toolCalls) {
                            parts.put(JSONObject().apply {
                                put("functionCall", JSONObject().apply {
                                    put("name", tc.name)
                                    put("args", tc.args)
                                })
                            })
                        }
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
                    contents.put(JSONObject().apply {
                        put("role", "model")
                        put("parts", parts)
                    })
                }
                else -> {
                    // user
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", buildGeminiPartsArray(m))
                    })
                }
            }
        }
        val body = JSONObject().apply {
            put("contents", contents)
            put("tools", buildToolsForGemini(tools))
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
            }
            if (temperature != null) {
                put("generationConfig", JSONObject().put("temperature", temperature.toDouble()))
            }
        }
        val conn = url.openConnection() as HttpURLConnection
        cancelHandle?.connection = conn
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                ToolRoundResult("", emptyList(), friendlyHttpError("Gemini", conn.responseCode, errBody))
            } else {
                val response = readBounded(conn.inputStream)
                if (cancelHandle?.cancelled?.get() == true) return ToolRoundResult("", emptyList(), "")
                val json = JSONObject(response)
                // Accumulate token usage across rounds
                val usageMeta = json.optJSONObject("usageMetadata")
                if (usageMeta != null && cancelHandle != null) {
                    val inp = usageMeta.optInt("promptTokenCount", 0)
                    val out = usageMeta.optInt("candidatesTokenCount", 0)
                    val prev = cancelHandle.tokenUsage
                    cancelHandle.tokenUsage = AiCancelRegistry.TokenUsage(
                        (prev?.promptTokens ?: 0) + inp,
                        (prev?.completionTokens ?: 0) + out
                    )
                }
                val partsArr = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val sb = StringBuilder()
                val calls = mutableListOf<AiToolRegistry.ToolCall>()
                if (partsArr != null) {
                    for (i in 0 until partsArr.length()) {
                        val p = partsArr.optJSONObject(i) ?: continue
                        val textPart = p.optString("text", "")
                        if (textPart.isNotEmpty()) sb.append(textPart)
                        val fc = p.optJSONObject("functionCall")
                        if (fc != null) {
                            val name = fc.optString("name")
                            val args = fc.optJSONObject("args") ?: JSONObject()
                            if (name.isNotBlank()) {
                                calls.add(AiToolRegistry.ToolCall(AiToolRegistry.newCallId(), name, args))
                            }
                        }
                    }
                }
                ToolRoundResult(sb.toString(), calls)
            }
        } catch (e: Exception) {
            if (cancelHandle?.cancelled?.get() == true) ToolRoundResult("", emptyList(), "")
            else ToolRoundResult("", emptyList(), friendlyNetworkError("Gemini", e, isLocal = false))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Run a chat completion with local tool execution. Max 5 tool rounds,
     * then the final assistant text is returned. Intermediate tool activity
     * is streamed to [onChunk] as plain text lines so the user sees what
     * the model is doing. This is v1 — no approval UI, only the three
     * built-in side-effect-free tools from [AiToolRegistry].
     */
    @JvmStatic
    @JvmOverloads
    fun chatCompletionWithTools(
        messages: List<ChatMessage>,
        aiModel: String,
        prefs: SharedPreferences,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        cancelHandle: AiCancelRegistry.CancelHandle? = null,
        extraSystemPrompt: String? = null,
        temperature: Float? = null
    ) {
        if (messages.isEmpty()) { onError("[No messages]"); return }
        val colonIndex = aiModel.indexOf(':')
        if (colonIndex <= 0) { onError("[Invalid model: $aiModel]"); return }
        val backend = aiModel.substring(0, colonIndex)
        val model = aiModel.substring(colonIndex + 1)

        val lorebook = prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: ""
        val allowNetwork = prefs.getBoolean(Settings.PREF_AI_ALLOW_NETWORK_TOOLS, Defaults.PREF_AI_ALLOW_NETWORK_TOOLS)
        val allowActions = prefs.getBoolean(Settings.PREF_AI_ALLOW_ACTIONS, Defaults.PREF_AI_ALLOW_ACTIONS)

        // Combine built-in tools with MCP tools from enabled servers (+ dispatch map)
        val (tools, mcpRoutes) = buildCombinedTools(prefs, allowNetwork, allowActions)

        // Build the system prompt. When network tools are active, prepend a
        // strong directive so models (especially Llama-class on Groq) don't
        // lazily fall back to "here's a URL you can check yourself".
        val systemPromptParts = mutableListOf<String>()
        if (tools.any { it.name == "web_search" }) {
            systemPromptParts.add(
                "You have a web_search tool that returns live search results. " +
                "MANDATORY: whenever the user asks about anything time-sensitive, recent, " +
                "a specific schedule, sports result, news item, match fixture, release date, " +
                "price, stock, weather, or any fact you are not 100% certain about, you MUST " +
                "call web_search first and then answer from the results. NEVER reply with " +
                "\"I don't have access to that information\" or \"check this website\" before " +
                "trying web_search. NEVER emit tool calls as plain text inside brackets — use " +
                "the provider's native tool_calls mechanism."
            )
        }
        if (tools.any { it.name == "get_datetime" }) {
            systemPromptParts.add(
                "For any question involving 'today', 'tomorrow', 'yesterday', 'now', the " +
                "current year, or relative dates, call get_datetime first — your training " +
                "data is out of date."
            )
        }
        if (lorebook.isNotBlank()) systemPromptParts.add("About the user:\n$lorebook")
        if (!extraSystemPrompt.isNullOrBlank()) systemPromptParts.add(extraSystemPrompt)
        val systemPrompt = systemPromptParts.joinToString("\n\n")

        @Suppress("NAME_SHADOWING")
        val expanded = expandAttachmentsForBackend(messages, backend)
        val convo = expanded.toMutableList()

        val accumulatedText = StringBuilder()
        // Per-session nonce makes the tool marker unpredictable: even if old
        // markers leak into history (e.g. via user copy-paste), they won't match
        // this session's nonce and can't trigger unintended tool execution.
        val toolNonce = java.util.UUID.randomUUID().toString().take(8)
        val maxRounds = 5
        var round = 0
        while (round < maxRounds) {
            if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
            val result = when (backend) {
                "ollama" -> {
                    val baseUrl = resolveOllamaBaseUrl(prefs)
                    if (baseUrl.isEmpty()) { onError("[Set Ollama URL in keyboard settings]"); return }
                    callOllamaChatWithToolsRound(convo, model, baseUrl, systemPrompt, tools, cancelHandle, temperature)
                }
                "anthropic" -> {
                    val apiKey = SecureApiKeys.getKey(Settings.PREF_ANTHROPIC_API_KEY)
                    callAnthropicChatWithToolsRound(convo, model, apiKey, systemPrompt, tools, cancelHandle, temperature)
                }
                "openai" -> {
                    val baseUrl = resolveOpenAiCompatBaseUrl(prefs)
                    if (baseUrl.isEmpty()) { onError("[Set your OpenAI-compatible server URL in keyboard settings]"); return }
                    val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
                    callOpenAICompatibleChatWithToolsRound(
                        convo, model, "$baseUrl/v1/chat/completions", apiKey,
                        "OpenAI-compatible", systemPrompt,
                        requireApiKey = false, isLocal = true, tools = tools, cancelHandle = cancelHandle,
                        temperature = temperature
                    )
                }
                "openai-cloud" -> callOpenAICompatibleChatWithToolsRound(
                    convo, model, "https://api.openai.com/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_OPENAI_API_KEY),
                    "OpenAI", systemPrompt,
                    requireApiKey = true, isLocal = false, tools = tools, cancelHandle = cancelHandle,
                    temperature = temperature
                )
                "groq" -> callOpenAICompatibleChatWithToolsRound(
                    convo, model, "https://api.groq.com/openai/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_GROQ_API_KEY),
                    "Groq", systemPrompt,
                    requireApiKey = true, isLocal = false, tools = tools, cancelHandle = cancelHandle,
                    temperature = temperature
                )
                "openrouter" -> callOpenAICompatibleChatWithToolsRound(
                    convo, model, "https://openrouter.ai/api/v1/chat/completions",
                    SecureApiKeys.getKey(Settings.PREF_OPENROUTER_API_KEY),
                    "OpenRouter", systemPrompt,
                    requireApiKey = true, isLocal = false, tools = tools, cancelHandle = cancelHandle,
                    temperature = temperature
                )
                "gemini" -> callGeminiChatWithToolsRound(
                    convo, model,
                    SecureApiKeys.getKey(Settings.PREF_GEMINI_API_KEY),
                    systemPrompt, tools, cancelHandle, temperature
                )
                else -> {
                    onError("[Tool calling not supported for backend: $backend]"); return
                }
            }
            if (result.error != null) {
                if (result.error.isEmpty()) onError("") else onError(result.error)
                return
            }
            if (result.text.isNotEmpty()) {
                onChunk(result.text)
                accumulatedText.append(result.text)
            }
            if (result.toolCalls.isEmpty()) {
                // No more tool calls — final answer.
                val finalText = stripThinking(accumulatedText.toString().trim())
                if (finalText.isEmpty()) onError("[Empty response]")
                else onComplete(finalText)
                return
            }

            // Append assistant message with tool calls to the conversation
            convo.add(ChatMessage(
                role = "assistant",
                content = result.text,
                toolCalls = result.toolCalls
            ))

            // Execute each tool and emit a structured marker block so the UI
            // can render it as a collapsible card. Format:
            //   ⟦TOOL⟧{"name":..., "args":..., "result":..., "error":...}⟦/TOOL⟧
            // Pre-compute the set of tool names that were offered to the model.
            // Reject any call whose name isn't in this set — prevents hallucinated
            // or injected tool names from being dispatched.
            val offeredNames = tools.map { it.name }.toSet() + mcpRoutes.keys
            for (call in result.toolCalls) {
                if (cancelHandle?.cancelled?.get() == true) { onError(""); return }
                if (call.name !in offeredNames) {
                    Log.w("AiServiceSync", "Model requested tool '${call.name}' not in offered list, skipping")
                    continue
                }
                val mcpRoute = mcpRoutes[call.name]
                val displayName = mcpRoute?.let { "${it.server.name}/${it.originalName}" } ?: call.name
                val toolResult = if (mcpRoute != null) {
                    try {
                        McpClient.callTool(mcpRoute.server, mcpRoute.originalName, call.args)
                    } catch (e: Exception) {
                        AiToolRegistry.ToolResult("MCP call failed: ${e.message}", isError = true)
                    }
                } else {
                    AiToolRegistry.execute(call)
                }
                val marker = JSONObject().apply {
                    put("name", displayName)
                    put("args", call.args.toString())
                    put("result", toolResult.content)
                    put("error", toolResult.isError)
                }
                val block = "\n\u27E6TOOL_${toolNonce}\u27E7${marker}\u27E6/TOOL_${toolNonce}\u27E7\n"
                onChunk(block)
                accumulatedText.append(block)
                convo.add(ChatMessage(
                    role = "tool",
                    content = toolResult.content,
                    toolCallId = call.id,
                    toolName = call.name
                ))
            }
            round++
        }
        // Hit the round cap — return whatever we accumulated.
        val finalText = stripThinking(accumulatedText.toString().trim())
        if (finalText.isEmpty()) onError("[Tool loop exceeded $maxRounds rounds]")
        else onComplete(finalText + "\n\n[Tool loop cap of $maxRounds rounds reached]")
    }
}
