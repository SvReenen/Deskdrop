// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [ConversationActivity]'s chat screen.
 *
 * Holds all business state (messages, chat metadata, model selection, sending
 * status) and the functions that mutate it (send, load, persist, delete, …).
 * Pure-UI state (dialog visibility, bottom-sheet targets, focus requesters)
 * stays in the `@Composable` layer.
 *
 * Every `var … by mutableStateOf()` property is directly observable by Compose
 * without `StateFlow` or `collectAsState()` — Compose tracks reads at snapshot
 * level regardless of where the state object lives.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext get() = getApplication<Application>()
    val prefs: SharedPreferences by lazy {
        DeviceProtectedUtils.getSharedPreferences(appContext)
    }

    // ── Model picker ────────────────────────────────────────────────────

    val allModels = mutableStateListOf<ModelItem>()
    var selectedModel by mutableStateOf<ModelItem?>(null)
    var modelMenuExpanded by mutableStateOf(false)
    var capabilityTick by mutableStateOf(0)
    var ollamaReachable by mutableStateOf<Boolean?>(null)  // null = unknown, true/false = ping result

    fun initModels() {
        if (allModels.isNotEmpty()) return
        allModels.addAll(loadCloudPresets(prefs))
        allModels.addAll(loadCloudModels(prefs))
        allModels.addAll(cachedOllamaModels(prefs))
        allModels.addAll(cachedOpenAiCompatibleModels(prefs))
        val conversationModelValue = prefs.getString(
            Settings.PREF_AI_CONVERSATION_MODEL,
            Defaults.PREF_AI_CONVERSATION_MODEL
        ) ?: Defaults.PREF_AI_CONVERSATION_MODEL
        val fallbackModelValue = prefs.getString(Settings.PREF_AI_MODEL, "") ?: ""

        // If the configured model isn't in the list (e.g. cache is empty after fresh install),
        // synthesize a ModelItem so the user isn't stuck with no model selected.
        for (mv in listOf(conversationModelValue, fallbackModelValue)) {
            if (mv.isNotBlank() && allModels.none { it.modelValue == mv }) {
                val label = mv.substringAfter(":").ifBlank { mv }
                val prefix = mv.substringBefore(":", "")
                val displayName = when (prefix) {
                    "ollama" -> "$label (Ollama)"
                    "openai" -> "$label (custom)"
                    else -> label
                }
                allModels.add(ModelItem(displayName = displayName, modelValue = mv))
            }
        }

        selectedModel = allModels.firstOrNull { it.modelValue == conversationModelValue }
            ?: allModels.firstOrNull { it.modelValue == fallbackModelValue }
            ?: allModels.firstOrNull()
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val freshOllama = loadOllamaModels(prefs, appContext, forceRefresh = true)
            val freshOpenAi = loadOpenAiCompatibleModels(prefs, forceRefresh = true)
            val freshCloud = loadCloudModels(prefs)
            val freshPresets = loadCloudPresets(prefs)
            withContext(Dispatchers.Main) {
                val currentValue = selectedModel?.modelValue
                allModels.clear()
                allModels.addAll(freshPresets)
                allModels.addAll(freshCloud)
                allModels.addAll(freshOllama)
                allModels.addAll(freshOpenAi)
                // Re-select the same model if still available
                if (currentValue != null) {
                    val match = allModels.firstOrNull { it.modelValue == currentValue }
                    if (match != null) {
                        selectedModel = match
                    } else {
                        // Synthesize if configured model not in fetched list
                        val label = currentValue.substringAfter(":").ifBlank { currentValue }
                        val prefix = currentValue.substringBefore(":", "")
                        val displayName = when (prefix) {
                            "ollama" -> "$label (Ollama)"
                            "openai" -> "$label (custom)"
                            else -> label
                        }
                        val synthetic = ModelItem(displayName = displayName, modelValue = currentValue)
                        allModels.add(synthetic)
                        selectedModel = synthetic
                    }
                }
            }
        }
    }

    fun pingOllama() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
            if (baseUrl.isBlank()) {
                withContext(Dispatchers.Main) { ollamaReachable = false }
                return@launch
            }
            val reachable = try {
                val url = java.net.URL("$baseUrl/api/tags")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                val ok = conn.responseCode == 200
                conn.disconnect()
                ok
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { ollamaReachable = reachable }
        }
    }

    fun probeOllamaCapabilities() {
        val mv = selectedModel?.modelValue ?: return
        if (!mv.startsWith("ollama:")) return
        val name = mv.removePrefix("ollama:")
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
            if (baseUrl.isBlank()) return@launch
            val caps = AiServiceSync.fetchOllamaCapabilities(baseUrl, name)
            if (caps != null) withContext(Dispatchers.Main) { capabilityTick++ }
        }
    }

    // ── Messages + chat state ───────────────────────────────────────────

    val messages = mutableStateListOf<UiMessage>()
    var inputText by mutableStateOf("")
    var isSending by mutableStateOf(false)
    var activeCancelHandle by mutableStateOf<AiCancelRegistry.CancelHandle?>(null)
    var replyToContent by mutableStateOf<String?>(null)

    // Multimodal attachments queued for the next user message
    val queuedAttachments = mutableStateListOf<QueuedAttachment>()

    // Persistence
    var currentChatId by mutableStateOf<String?>(null)
    var currentChatTitle by mutableStateOf<String?>(null)
    var currentChatCreatedAt by mutableStateOf(0L)
    var currentChatPinned by mutableStateOf(false)
    var currentChatSystemPrompt by mutableStateOf("")
    var currentChatTemperature by mutableStateOf<Float?>(null)

    // Drawer chat list
    val chatList = mutableStateListOf<ConversationStore.ChatMeta>()
    var drawerOpen by mutableStateOf(false)

    // Search
    var searchQuery by mutableStateOf("")
    val chatContentCache = mutableMapOf<String, ConversationStore.StoredChat>()
    var searchMatchIds by mutableStateOf<Set<String>?>(null)

    // Unread reminder state
    var unreadChatIds by mutableStateOf<Set<String>>(emptySet())
    var unreadHasUnscoped by mutableStateOf(false)

    // Voice recording auto-start (triggered by dictate shortcut or QS tile)
    var autoStartVoice by mutableStateOf(false)

    // Highlight (in-chat search after drawer search click)
    var pendingHighlightQuery by mutableStateOf<String?>(null)
    var highlightMatchIndices by mutableStateOf<List<Int>>(emptyList())
    var highlightCursor by mutableStateOf(0)

    // ── Scroll events (consumed by UI layer) ────────────────────────────

    val scrollToEvent = Channel<Int>(Channel.CONFLATED)

    // ── Business logic ──────────────────────────────────────────────────

    fun refreshUnreadState() {
        viewModelScope.launch {
            val (ids, unscoped) = withContext(Dispatchers.IO) {
                ReminderStore.unreadChatIds(appContext) to ReminderStore.hasUnreadUnscoped(appContext)
            }
            unreadChatIds = ids
            unreadHasUnscoped = unscoped
        }
    }

    fun refreshChatList() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) { ConversationStore.listAll(appContext) }
            chatList.clear()
            chatList.addAll(fresh)
        }
    }

    suspend fun refreshChatListSync() {
        val fresh = withContext(Dispatchers.IO) { ConversationStore.listAll(appContext) }
        chatList.clear()
        chatList.addAll(fresh)
    }

    fun debounceSearch() {
        viewModelScope.launch {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) {
                searchMatchIds = null
                return@launch
            }
            delay(200)
            if (searchQuery.trim().lowercase() != q) return@launch // superseded
            val snapshot = chatList.toList()
            val matches = withContext(Dispatchers.IO) {
                snapshot.forEach { meta ->
                    if (meta.id !in chatContentCache) {
                        ConversationStore.load(appContext, meta.id)?.let {
                            chatContentCache[meta.id] = it
                        }
                    }
                }
                snapshot.mapNotNull { meta ->
                    val chat = chatContentCache[meta.id] ?: return@mapNotNull null
                    val titleHit = chat.title.lowercase().contains(q)
                    val msgHit = chat.messages.any { it.content.lowercase().contains(q) }
                    if (titleHit || msgHit) meta.id else null
                }.toSet()
            }
            searchMatchIds = matches
        }
    }

    fun persistCurrentChat(firstUserText: String, modelValue: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = currentChatId ?: ConversationStore.newId().also { currentChatId = it }
            val title = currentChatTitle ?: ConversationStore.deriveTitle(firstUserText).also { currentChatTitle = it }
            if (currentChatCreatedAt == 0L) currentChatCreatedAt = now
            val stored = ConversationStore.StoredChat(
                id = id,
                title = title,
                model = modelValue,
                createdAt = currentChatCreatedAt,
                updatedAt = now,
                messages = messages
                    .filter { !it.isPending }
                    .map {
                        ConversationStore.StoredMessage(
                            role = it.role,
                            content = it.content,
                            modelLabel = it.modelLabel,
                            isError = it.isError,
                            attachments = it.attachments.map { a ->
                                ConversationStore.StoredAttachment(a.path, a.mimeType)
                            },
                            tokenCount = it.tokenCount
                        )
                    },
                pinned = currentChatPinned,
                systemPrompt = currentChatSystemPrompt,
                temperature = currentChatTemperature
            )
            withContext(Dispatchers.IO) { ConversationStore.save(appContext, stored) }
            chatContentCache.remove(stored.id)
            refreshChatListSync()
        }
    }

    fun stopGeneration() {
        val h = activeCancelHandle ?: return
        h.cancelled.set(true)
        val conn = h.connection
        if (conn != null) {
            Thread { try { conn.disconnect() } catch (_: Exception) {} }.start()
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        val model = selectedModel ?: return
        val attachmentsSnapshot = queuedAttachments.toList()
        if ((text.isEmpty() && attachmentsSnapshot.isEmpty()) || isSending) return

        val replySnapshot = replyToContent
        pendingHighlightQuery = null
        highlightMatchIndices = emptyList()
        highlightCursor = 0
        val userAttachments = attachmentsSnapshot.map {
            AiServiceSync.MessageAttachment(it.localPath, it.mimeType)
        }
        if (currentChatId == null) {
            currentChatId = ConversationStore.newId()
        }
        messages.add(UiMessage(role = "user", content = text, attachments = userAttachments))
        messages.add(UiMessage(role = "assistant", content = "", modelLabel = model.displayName, isPending = true))
        inputText = ""
        queuedAttachments.clear()
        isSending = true
        replyToContent = null

        val toolMarkerRegex = Regex("\\n?\u27E6TOOL(?:_[a-f0-9]{8})?\u27E7[\\s\\S]*?\u27E6/TOOL(?:_[a-f0-9]{8})?\u27E7\\n?")
        fun stripMarkers(s: String): String = toolMarkerRegex.replace(s, "").trim()

        val baseHistory = if (replySnapshot != null) {
            listOf(
                AiServiceSync.ChatMessage(role = "assistant", content = stripMarkers(replySnapshot)),
                AiServiceSync.ChatMessage(role = "user", content = stripMarkers(text), attachments = userAttachments)
            )
        } else {
            messages
                .filter { !it.isPending && !it.isError }
                .map {
                    AiServiceSync.ChatMessage(
                        role = it.role,
                        content = stripMarkers(it.content),
                        attachments = it.attachments
                    )
                }
                .filter { it.content.isNotBlank() || it.attachments.isNotEmpty() }
        }

        fun truncateHistory(
            msgs: List<AiServiceSync.ChatMessage>,
            maxTokens: Int = 12_000,
            alwaysKeepLast: Int = 6,
            maxImages: Int = 3
        ): List<AiServiceSync.ChatMessage> {
            if (msgs.size <= alwaysKeepLast) return msgs
            fun estimateTokens(m: AiServiceSync.ChatMessage): Int {
                val textTokens = m.content.length / 3
                val imageTokens = m.attachments.count { it.mimeType.startsWith("image/") } * 1000
                return textTokens + imageTokens
            }
            val system = msgs.filter { it.role == "system" }
            val conversation = msgs.filter { it.role != "system" }
            if (conversation.size <= alwaysKeepLast) return msgs
            val tail = conversation.takeLast(alwaysKeepLast)
            val candidates = conversation.dropLast(alwaysKeepLast)
            val systemCost = system.sumOf { estimateTokens(it) }
            val tailCost = tail.sumOf { estimateTokens(it) }
            var remaining = maxTokens - systemCost - tailCost
            val included = mutableListOf<AiServiceSync.ChatMessage>()
            for (m in candidates.reversed()) {
                val cost = estimateTokens(m)
                if (remaining - cost < 0) break
                remaining -= cost
                included.add(0, m)
            }
            val result = system + included + tail
            var imagesSeen = 0
            return result.reversed().map { m ->
                val imageCount = m.attachments.count { it.mimeType.startsWith("image/") }
                if (imageCount == 0 || imagesSeen + imageCount <= maxImages) {
                    imagesSeen += imageCount
                    m
                } else {
                    val kept = m.attachments.filter { !it.mimeType.startsWith("image/") }
                    val stripped = imageCount - (kept.size - m.attachments.count { !it.mimeType.startsWith("image/") })
                    imagesSeen += maxOf(0, imageCount - stripped)
                    m.copy(
                        content = m.content + if (stripped > 0) "\n[${stripped} image(s) omitted from context]" else "",
                        attachments = kept
                    )
                }
            }.reversed()
        }

        val historyForApi = run {
            val withSystem = if (currentChatSystemPrompt.isNotBlank()) {
                listOf(AiServiceSync.ChatMessage(role = "system", content = currentChatSystemPrompt)) + baseHistory
            } else baseHistory
            truncateHistory(withSystem)
        }

        val handle = AiCancelRegistry.CancelHandle(
            helium314.keyboard.latin.utils.ToolbarKey.AI_CONVERSATION
        )
        activeCancelHandle = handle
        val temp = currentChatTemperature

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val useTools = AiServiceSync.backendSupportsTools(model.modelValue)
                    val streamFn: (
                        List<AiServiceSync.ChatMessage>, String, SharedPreferences,
                        (String) -> Unit, (String) -> Unit, (String) -> Unit,
                        AiCancelRegistry.CancelHandle?
                    ) -> Unit = if (useTools) { msgs, m, p, oc, oco, oe, ch ->
                        AiServiceSync.chatCompletionWithTools(msgs, m, p, oc, oco, oe, ch, temperature = temp)
                    } else { msgs, m, p, oc, oco, oe, ch ->
                        AiServiceSync.chatCompletionMultiTurnStream(msgs, m, p, oc, oco, oe, ch, temperature = temp)
                    }
                    AiChatContextRegistry.set(currentChatId)
                    streamFn(
                        historyForApi,
                        model.modelValue,
                        prefs,
                        { delta ->
                            viewModelScope.launch(Dispatchers.Main) {
                                val idx = messages.indexOfLast { it.isPending }
                                if (idx >= 0) {
                                    val cur = messages[idx]
                                    messages[idx] = cur.copy(content = cur.content + delta)
                                }
                            }
                        },
                        { cleanFinal ->
                            viewModelScope.launch(Dispatchers.Main) {
                                val idx = messages.indexOfLast { it.isPending }
                                if (idx >= 0) {
                                    val cur = messages[idx]
                                    val tokens = handle.tokenUsage?.total
                                    messages[idx] = cur.copy(content = cleanFinal, isPending = false, tokenCount = tokens)
                                }
                                isSending = false
                                activeCancelHandle = null
                                persistCurrentChat(text.ifBlank { "Image" }, model.modelValue)
                            }
                        },
                        { errMsg ->
                            viewModelScope.launch(Dispatchers.Main) {
                                val idx = messages.indexOfLast { it.isPending }
                                if (errMsg.isEmpty()) {
                                    if (idx >= 0) {
                                        val cur = messages[idx]
                                        if (cur.content.isEmpty()) {
                                            messages.removeAt(idx)
                                        } else {
                                            messages[idx] = cur.copy(isPending = false)
                                        }
                                    }
                                } else if (idx >= 0) {
                                    val isLocal = model.modelValue.startsWith("ollama:") ||
                                        model.modelValue.startsWith("openai:")
                                    val isConnErr = "not reachable" in errMsg ||
                                        "timed out" in errMsg ||
                                        "Connection lost" in errMsg
                                    messages[idx] = UiMessage(
                                        role = "assistant",
                                        content = errMsg,
                                        modelLabel = model.displayName,
                                        isPending = false,
                                        isError = true,
                                        isLocalError = isLocal && isConnErr
                                    )
                                }
                                isSending = false
                                activeCancelHandle = null
                                if (errMsg.isEmpty() || !errMsg.startsWith("[")) {
                                    persistCurrentChat(text.ifBlank { "Image" }, model.modelValue)
                                }
                            }
                        },
                        handle
                    )
                } catch (e: Exception) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val idx = messages.indexOfLast { it.isPending }
                        if (idx >= 0) {
                            messages[idx] = UiMessage(
                                role = "assistant",
                                content = "[Error: ${e.message ?: "unknown"}]",
                                modelLabel = model.displayName,
                                isPending = false,
                                isError = true
                            )
                        }
                        isSending = false
                        activeCancelHandle = null
                    }
                } finally {
                    AiChatContextRegistry.clear()
                }
            }
        }
    }

    /**
     * Retry the last failed message using the first available cloud model.
     * Called when a local model fails and the user taps "Try cloud?".
     */
    fun retryWithCloud() {
        if (isSending) return
        val cloudModel = allModels.firstOrNull { m ->
            val v = m.modelValue
            v.startsWith("groq:") || v.startsWith("openrouter:") ||
                v.startsWith("gemini:") || v.startsWith("anthropic:") ||
                v.startsWith("openai-cloud:")
        } ?: return
        selectedModel = cloudModel
        regenerateLast()
    }

    fun regenerateLast() {
        if (isSending) return
        val lastAssistantIdx = messages.indexOfLast { it.role == "assistant" && !it.isPending }
        if (lastAssistantIdx < 0) return
        val userIdx = (lastAssistantIdx - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return
        val userText = messages[userIdx].content
        while (messages.size > userIdx) messages.removeAt(messages.size - 1)
        val savedInput = inputText
        inputText = userText
        sendMessage()
        inputText = savedInput
    }

    fun resendFromIndex(idx: Int, newText: String) {
        if (isSending) return
        if (idx !in messages.indices) return
        while (messages.size > idx) messages.removeAt(messages.size - 1)
        val savedInput = inputText
        inputText = newText
        sendMessage()
        inputText = savedInput
    }

    fun startNewChat() {
        messages.clear()
        currentChatId = null
        currentChatTitle = null
        currentChatCreatedAt = 0L
        currentChatPinned = false
        currentChatSystemPrompt = ""
        currentChatTemperature = null
        replyToContent = null
        pendingHighlightQuery = null
        highlightMatchIndices = emptyList()
        highlightCursor = 0
        queuedAttachments.forEach {
            try { java.io.File(it.localPath).delete() } catch (_: Exception) {}
        }
        queuedAttachments.clear()
        drawerOpen = false
    }

    fun loadChat(id: String, highlightQuery: String? = null) {
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { ConversationStore.load(appContext, id) }
                ?: return@launch
            messages.clear()
            chat.messages.forEach {
                messages.add(
                    UiMessage(
                        role = it.role,
                        content = it.content,
                        modelLabel = it.modelLabel,
                        isPending = false,
                        isError = it.isError,
                        attachments = it.attachments.map { a ->
                            AiServiceSync.MessageAttachment(a.path, a.mimeType)
                        },
                        tokenCount = it.tokenCount
                    )
                )
            }
            currentChatId = chat.id
            currentChatTitle = chat.title
            currentChatCreatedAt = chat.createdAt
            currentChatPinned = chat.pinned
            currentChatSystemPrompt = chat.systemPrompt
            currentChatTemperature = chat.temperature
            allModels.firstOrNull { it.modelValue == chat.model }?.let { selectedModel = it }
            replyToContent = null
            drawerOpen = false
            withContext(Dispatchers.IO) {
                try { ReminderStore.markReadForChat(appContext, chat.id) } catch (_: Exception) {}
            }
            refreshUnreadState()
            val hq = highlightQuery?.takeIf { it.isNotBlank() }
            pendingHighlightQuery = hq
            if (hq != null) {
                val matches = messages.indices.filter {
                    messages[it].content.contains(hq, ignoreCase = true)
                }
                highlightMatchIndices = matches
                highlightCursor = 0
                if (matches.isNotEmpty()) {
                    delay(50)
                    scrollToEvent.trySend(matches[0])
                }
            } else {
                highlightMatchIndices = emptyList()
                highlightCursor = 0
            }
        }
    }

    fun appendReminderMessage(reminderId: String, fallbackNotice: String? = null) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ReminderStore.get(appContext, reminderId) }
            val body = when {
                r != null && fallbackNotice != null -> "\u23F0 Reminder: ${r.message}\n\n($fallbackNotice)"
                r != null -> "\u23F0 Reminder: ${r.message}"
                fallbackNotice != null -> "\u23F0 Reminder\n\n($fallbackNotice)"
                else -> "\u23F0 Reminder"
            }
            messages.add(
                UiMessage(
                    role = "assistant",
                    content = body,
                    modelLabel = "",
                    isPending = false,
                    isError = false
                )
            )
        }
    }

    fun deleteChat(id: String) {
        val wasCurrent = id == currentChatId
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ConversationStore.delete(appContext, id) }
            chatContentCache.remove(id)
            refreshChatListSync()
            if (wasCurrent) startNewChat()
        }
    }

    fun bulkDeleteChats(ids: List<String>) {
        val currentWasDeleted = currentChatId != null && currentChatId in ids
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { ConversationStore.delete(appContext, it) }
            }
            ids.forEach { chatContentCache.remove(it) }
            refreshChatListSync()
            if (currentWasDeleted) startNewChat()
        }
    }

    fun renameChat(id: String, newTitle: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ConversationStore.rename(appContext, id, newTitle) }
            chatContentCache.remove(id)
            if (id == currentChatId) currentChatTitle = newTitle
            refreshChatListSync()
        }
    }

    fun togglePin(id: String, currentlyPinned: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ConversationStore.setPinned(appContext, id, !currentlyPinned) }
            chatContentCache.remove(id)
            if (id == currentChatId) currentChatPinned = !currentlyPinned
            refreshChatListSync()
        }
    }

    fun saveTemperature() {
        if (currentChatId != null && messages.any { !it.isPending }) {
            val lastUserText = messages.lastOrNull { it.role == "user" }?.content ?: ""
            persistCurrentChat(lastUserText, selectedModel?.modelValue ?: "")
        }
    }

    fun saveSystemPrompt(prompt: String) {
        currentChatSystemPrompt = prompt
        if (currentChatId != null && messages.any { !it.isPending }) {
            val lastUserText = messages.lastOrNull { it.role == "user" }?.content ?: ""
            persistCurrentChat(lastUserText, selectedModel?.modelValue ?: "")
        }
    }
}
