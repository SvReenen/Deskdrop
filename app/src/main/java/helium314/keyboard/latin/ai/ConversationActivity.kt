// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.lifecycle.viewmodel.compose.viewModel
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.screens.brandButtonColors
import helium314.keyboard.settings.screens.brandTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Represents all possible ways ConversationActivity can be launched. */
sealed class LaunchIntent {
    data class Reminder(val chatId: String?, val reminderId: String) : LaunchIntent()
    data class ShareText(val text: String) : LaunchIntent()
    data class ShareMedia(val uris: List<Uri>, val text: String?) : LaunchIntent()
    data class Shortcut(val action: String, val presetIndex: Int? = null) : LaunchIntent()
    object None : LaunchIntent()
}

/**
 * Full-screen conversation UI launched from the AI_CONVERSATION toolbar button,
 * share-sheet, app shortcuts, or QS voice tile.
 */
class ConversationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_ACTION = "launch_action"
        const val ACTION_NEW_CHAT = "new_chat"
        const val ACTION_DICTATE = "dictate"
        const val ACTION_EXECUTE = "execute"
        const val ACTION_PRESET = "preset"
        const val ACTION_LAST_CHAT = "last_chat"
        const val EXTRA_PRESET_INDEX = "preset_index"
        const val EXTRA_PREFILL_TEXT = "prefill_text"

        private const val MAX_SHARE_TEXT = 50_000

        fun parseIntent(intent: Intent?): LaunchIntent {
            if (intent == null) return LaunchIntent.None
            // 1. Share intents (from share-sheet)
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(MAX_SHARE_TEXT)
                    return if (stream != null) {
                        LaunchIntent.ShareMedia(listOf(stream), text)
                    } else if (!text.isNullOrBlank()) {
                        LaunchIntent.ShareText(text)
                    } else LaunchIntent.None
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    @Suppress("DEPRECATION")
                    val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(MAX_SHARE_TEXT)
                    return if (!streams.isNullOrEmpty()) {
                        LaunchIntent.ShareMedia(streams, text)
                    } else LaunchIntent.None
                }
            }
            // 2. Prefill text (from QS voice tile)
            val prefill = intent.getStringExtra(EXTRA_PREFILL_TEXT)
            if (!prefill.isNullOrBlank()) {
                return LaunchIntent.ShareText(prefill.take(MAX_SHARE_TEXT))
            }
            // 3. App shortcuts
            val action = intent.getStringExtra(EXTRA_ACTION)
            if (action != null) {
                val presetIdx = if (intent.hasExtra(EXTRA_PRESET_INDEX))
                    intent.getIntExtra(EXTRA_PRESET_INDEX, -1).takeIf { it >= 0 }
                else null
                return LaunchIntent.Shortcut(action, presetIdx)
            }
            // 4. Reminder deep-link
            val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
            if (reminderId != null) {
                return LaunchIntent.Reminder(
                    chatId = intent.getStringExtra(EXTRA_CHAT_ID),
                    reminderId = reminderId
                )
            }
            return LaunchIntent.None
        }
    }

    private var launchIntent by mutableStateOf<LaunchIntent>(LaunchIntent.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiServiceSync.setContext(this)
        enableEdgeToEdge()
        launchIntent = parseIntent(intent)
        setContent {
            Theme {
                ConversationScreen(
                    onClose = { finish() },
                    onInsert = { text ->
                        PendingInsertBridge.writeInsert(this@ConversationActivity, text)
                        finish()
                    },
                    launchIntent = launchIntent,
                    onLaunchIntentConsumed = { launchIntent = LaunchIntent.None }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = parseIntent(intent)
    }
}

data class UiMessage(
    val role: String,                // "user" | "assistant"
    val content: String,
    val modelLabel: String = "",     // which model generated assistant replies
    val isPending: Boolean = false,  // assistant bubble waiting for response
    val isError: Boolean = false,
    val isLocalError: Boolean = false, // true when a local (Ollama) call failed — enables "try cloud?" button
    val attachments: List<AiServiceSync.MessageAttachment> = emptyList(),
    val tokenCount: Int? = null      // total tokens used for this response
)

/** Image queued in the input row, not yet sent. */
data class QueuedAttachment(
    val localPath: String,
    val mimeType: String
)

/**
 * Copy raw bytes from [srcUri] into [targetFile] without any decoding or
 * transformation. Used for PDFs (where we need the original document) and
 * any other non-image attachment that should not go through the bitmap
 * pipeline. Returns true on success.
 */
private fun ingestRawFile(
    context: Context,
    srcUri: Uri,
    targetFile: java.io.File
): Boolean {
    return try {
        targetFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(srcUri)?.use { input ->
            java.io.FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
            }
        } ?: return false
        true
    } catch (e: Exception) {
        Log.e("Conversation", "ingestRawFile failed", e)
        try { targetFile.delete() } catch (_: Exception) {}
        false
    }
}

/**
 * Decode an image file from disk, scale to a max long-side of [maxDim], and
 * write JPEG quality 85 back to [targetFile]. Returns true on success.
 */
private fun ingestImageFile(
    context: Context,
    srcUri: Uri,
    targetFile: java.io.File,
    maxDim: Int = 1568
): Boolean {
    return try {
        val original = context.contentResolver.openInputStream(srcUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return false
        val w = original.width
        val h = original.height
        val scaled = if (w <= maxDim && h <= maxDim) {
            original
        } else {
            val ratio = maxDim.toFloat() / maxOf(w, h)
            val nw = (w * ratio).toInt().coerceAtLeast(1)
            val nh = (h * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(original, nw, nh, true)
        }
        targetFile.parentFile?.mkdirs()
        java.io.FileOutputStream(targetFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        if (scaled !== original) {
            original.recycle()
            scaled.recycle()
        } else {
            scaled.recycle()
        }
        true
    } catch (e: Exception) {
        Log.e("Conversation", "ingestImageFile failed", e)
        try { targetFile.delete() } catch (_: Exception) {}
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationScreen(
    onClose: () -> Unit,
    onInsert: (String) -> Unit,
    launchIntent: LaunchIntent = LaunchIntent.None,
    onLaunchIntentConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // UI-only state (dialogs, sheets, focus, keyboard visibility)
    var keyboardVisible by remember { mutableStateOf(true) }
    var fullscreenAttachmentPath by remember { mutableStateOf<String?>(null) }
    var attachSheetOpen by remember { mutableStateOf(false) }
    var systemPromptDialogOpen by remember { mutableStateOf(false) }
    var systemPromptInput by remember { mutableStateOf("") }
    var temperaturePopupOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationStore.ChatMeta?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ConversationStore.ChatMeta?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedChatIds = remember { mutableStateListOf<String>() }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var sheetTarget by remember { mutableStateOf<ConversationStore.ChatMeta?>(null) }
    val sheetState = rememberModalBottomSheetState()
    var messageSheetTarget by remember { mutableStateOf<Int?>(null) }
    val messageSheetState = rememberModalBottomSheetState()
    var editTarget by remember { mutableStateOf<Int?>(null) }
    var editInput by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // Initialize ViewModel models + chat list on first composition
    LaunchedEffect(Unit) {
        vm.initModels()
        vm.refreshModels()
        vm.refreshChatList()
    }

    // Refresh models when returning from settings (e.g. after adding a new API key)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshModels()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Capability probe whenever model changes
    LaunchedEffect(vm.selectedModel?.modelValue) {
        vm.probeOllamaCapabilities()
    }

    // Debounced search
    LaunchedEffect(vm.searchQuery, vm.chatList.size) {
        vm.debounceSearch()
    }

    val filteredChats by remember {
        derivedStateOf {
            vm.chatList.filter { meta ->
                vm.searchMatchIds == null || meta.id in vm.searchMatchIds!!
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Reserve a chat id NOW so attachment files have a stable home, even
        // before the user has typed/sent anything.
        val chatId = vm.currentChatId ?: ConversationStore.newId().also { vm.currentChatId = it }
        val targetDir = ConversationStore.chatAttachmentsDir(context, chatId)
        scope.launch {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    if (vm.queuedAttachments.size >= 4) break
                    val targetFile = java.io.File(
                        targetDir,
                        "img_${java.util.UUID.randomUUID()}.jpg"
                    )
                    if (ingestImageFile(context, uri, targetFile)) {
                        withContext(Dispatchers.Main) {
                            vm.queuedAttachments.add(
                                QueuedAttachment(targetFile.absolutePath, "image/jpeg")
                            )
                        }
                    }
                }
            }
        }
    }

    // Camera capture — we pre-create a temp file under cache/camera_temp/ and
    // hand its FileProvider URI to the system camera. On success we run the
    // result through ingestImageFile (same downscale pipeline as gallery
    // picks), then delete the temp. The pending file+chat id live in state
    // because the launcher callback can't see the closure's `chatId` variable
    // (it's re-evaluated on each recompose).
    var pendingCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingCameraTargetChatId by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val tempFile = pendingCameraFile
        val targetChatId = pendingCameraTargetChatId
        pendingCameraFile = null
        pendingCameraTargetChatId = null
        if (!success || tempFile == null || targetChatId == null) {
            tempFile?.delete()
            return@rememberLauncherForActivityResult
        }
        val targetDir = ConversationStore.chatAttachmentsDir(context, targetChatId)
        val targetFile = java.io.File(
            targetDir,
            "img_${java.util.UUID.randomUUID()}.jpg"
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                val ok = ingestImageFile(context, Uri.fromFile(tempFile), targetFile)
                tempFile.delete()
                if (ok) {
                    withContext(Dispatchers.Main) {
                        if (vm.queuedAttachments.size < 4) {
                            vm.queuedAttachments.add(
                                QueuedAttachment(targetFile.absolutePath, "image/jpeg")
                            )
                        }
                    }
                }
            }
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val chatId = vm.currentChatId ?: ConversationStore.newId().also { vm.currentChatId = it }
        val targetDir = ConversationStore.chatAttachmentsDir(context, chatId)
        scope.launch {
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    if (vm.queuedAttachments.size >= 4) break
                    val targetFile = java.io.File(
                        targetDir,
                        "doc_${java.util.UUID.randomUUID()}.pdf"
                    )
                    if (ingestRawFile(context, uri, targetFile)) {
                        withContext(Dispatchers.Main) {
                            vm.queuedAttachments.add(
                                QueuedAttachment(targetFile.absolutePath, "application/pdf")
                            )
                        }
                    }
                }
            }
        }
    }

    val canSend by remember {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") vm.capabilityTick // observe cache refresh
            if (vm.isSending || vm.selectedModel == null) return@derivedStateOf false
            if (vm.inputText.isBlank() && vm.queuedAttachments.isEmpty()) return@derivedStateOf false
            val mv = vm.selectedModel?.modelValue ?: return@derivedStateOf false
            // Per-attachment capability check.
            vm.queuedAttachments.all { att ->
                when {
                    att.mimeType.startsWith("image/") -> AiServiceSync.supportsImageInput(mv)
                    att.mimeType == "application/pdf" -> AiServiceSync.supportsPdfInput(mv)
                    else -> false
                }
            }
        }
    }

    fun toggleKeyboard() {
        keyboardVisible = !keyboardVisible
        if (keyboardVisible) {
            focusRequester.requestFocus()
            softwareKeyboard?.show()
        } else {
            focusManager.clearFocus(force = true)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow((context as? ComponentActivity)?.window?.decorView?.windowToken, 0)
        }
    }

    // Handle all launch intents: reminders, share, shortcuts, voice prefill.
    LaunchedEffect(launchIntent) {
        when (val intent = launchIntent) {
            is LaunchIntent.None -> return@LaunchedEffect

            is LaunchIntent.Reminder -> {
                withContext(Dispatchers.IO) {
                    try { ReminderStore.markReadForChat(context, intent.chatId) } catch (_: Exception) {}
                }
                // Dismiss the system notification
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.cancel(intent.reminderId.hashCode())
                } catch (_: Exception) {}
                vm.refreshUnreadState()
                if (intent.chatId != null) {
                    val chat = withContext(Dispatchers.IO) { ConversationStore.load(context, intent.chatId) }
                    if (chat != null) {
                        vm.loadChat(intent.chatId)
                        delay(80)
                        vm.appendReminderMessage(intent.reminderId)
                    } else {
                        vm.startNewChat()
                        vm.appendReminderMessage(
                            intent.reminderId,
                            fallbackNotice = "Originele conversatie niet meer beschikbaar"
                        )
                    }
                } else {
                    if (vm.messages.isEmpty() && vm.currentChatId == null) {
                        vm.appendReminderMessage(intent.reminderId)
                    } else {
                        vm.startNewChat()
                        vm.appendReminderMessage(intent.reminderId)
                    }
                }
            }

            is LaunchIntent.ShareText -> {
                vm.startNewChat()
                vm.inputText = intent.text
            }

            is LaunchIntent.ShareMedia -> {
                vm.startNewChat()
                if (!intent.text.isNullOrBlank()) vm.inputText = intent.text
                val chatId = vm.currentChatId ?: ConversationStore.newId().also { vm.currentChatId = it }
                val targetDir = ConversationStore.chatAttachmentsDir(context, chatId)
                withContext(Dispatchers.IO) {
                    for (uri in intent.uris) {
                        if (vm.queuedAttachments.size >= 4) break
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        if (mime.startsWith("image/")) {
                            val targetFile = java.io.File(targetDir, "img_${java.util.UUID.randomUUID()}.jpg")
                            if (ingestImageFile(context, uri, targetFile)) {
                                withContext(Dispatchers.Main) {
                                    vm.queuedAttachments.add(QueuedAttachment(targetFile.absolutePath, "image/jpeg"))
                                }
                            }
                        } else {
                            val ext = if (mime == "application/pdf") "pdf" else "bin"
                            val targetFile = java.io.File(targetDir, "doc_${java.util.UUID.randomUUID()}.$ext")
                            if (ingestRawFile(context, uri, targetFile)) {
                                withContext(Dispatchers.Main) {
                                    vm.queuedAttachments.add(QueuedAttachment(targetFile.absolutePath, mime))
                                }
                            }
                        }
                    }
                }
            }

            is LaunchIntent.Shortcut -> {
                when (intent.action) {
                    ConversationActivity.ACTION_NEW_CHAT -> {
                        vm.startNewChat()
                    }
                    ConversationActivity.ACTION_LAST_CHAT -> {
                        vm.refreshChatList()
                        delay(100)
                        val lastId = vm.chatList.firstOrNull()?.id
                        if (lastId != null) vm.loadChat(lastId) else vm.startNewChat()
                    }
                    ConversationActivity.ACTION_PRESET -> {
                        vm.startNewChat()
                        val idx = intent.presetIndex
                        if (idx != null && idx in vm.allModels.indices) {
                            val preset = vm.allModels[idx]
                            vm.selectedModel = preset
                            if (preset.isCustomPreset && preset.customPrompt.isNotBlank()) {
                                vm.currentChatSystemPrompt = preset.customPrompt
                            }
                        }
                    }
                    ConversationActivity.ACTION_DICTATE -> {
                        vm.startNewChat()
                        vm.autoStartVoice = true
                    }
                    ConversationActivity.ACTION_EXECUTE -> {
                        vm.startNewChat()
                        // Select MCP model if configured
                        // First check cloud fallback so prefs are restored
                        // if the local server is back up.
                        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
                        AiServiceSync.checkCloudFallback(prefs)
                        val backupKey = "ai_cloud_fallback_backup_${Settings.PREF_AI_MCP_MODEL}"
                        val mcpModel = if (prefs.contains(backupKey)) {
                            prefs.getString(backupKey, "") ?: ""
                        } else {
                            prefs.getString(Settings.PREF_AI_MCP_MODEL, Defaults.PREF_AI_MCP_MODEL) ?: ""
                        }
                        if (mcpModel.isNotEmpty()) {
                            val match = vm.allModels.firstOrNull { it.modelValue == mcpModel }
                            if (match != null) {
                                vm.selectedModel = match
                            } else {
                                // Model not in cache yet (e.g. Ollama list
                                // not fetched); synthesize a temporary entry
                                // so we don't fall back to the cloud default.
                                val label = mcpModel.substringAfter(":").ifBlank { mcpModel }
                                val prefix = mcpModel.substringBefore(":", "")
                                val displayName = when (prefix) {
                                    "ollama" -> "$label (ollama)"
                                    "openai" -> "$label (custom)"
                                    else -> label
                                }
                                val item = ModelItem(displayName = displayName, modelValue = mcpModel)
                                vm.allModels.add(item)
                                vm.selectedModel = item
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "MCP Execute mode", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        onLaunchIntentConsumed()
    }

    LaunchedEffect(vm.drawerOpen) {
        if (vm.drawerOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboard?.hide()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow((context as? ComponentActivity)?.window?.decorView?.windowToken, 0)
            vm.refreshUnreadState()
        }
    }

    // Clear unscoped reminders (chatId=null, e.g. from execute widget) on open,
    // since there's no specific chat to navigate to for those.
    // Chat-scoped reminders are cleared when the user loads that specific chat.
    LaunchedEffect(Unit) {
        if (vm.unreadHasUnscoped) {
            withContext(Dispatchers.IO) {
                try { ReminderStore.markReadForChat(context, null) } catch (_: Exception) {}
            }
        }
        vm.refreshUnreadState()
        while (true) {
            delay(5000)
            vm.refreshUnreadState()
        }
    }

    fun handleClose() {
        when {
            selectionMode -> {
                selectionMode = false
                selectedChatIds.clear()
            }
            vm.drawerOpen -> vm.drawerOpen = false
            vm.messages.isNotEmpty() -> vm.startNewChat()
            else -> onClose()
        }
    }

    BackHandler(enabled = true) { handleClose() }

    // Auto-scroll to bottom when a new message is added
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    // Auto-scroll while streaming (last message content grows)
    val lastContentLen by remember {
        derivedStateOf { vm.messages.lastOrNull()?.content?.length ?: 0 }
    }
    LaunchedEffect(lastContentLen) {
        if (vm.isSending && vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    // Open the soft keyboard automatically when the screen is first shown
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        softwareKeyboard?.show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { handleClose() }) {
                    Text("\u2715", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.weight(1f))
                // Model picker
                Box {
                    TextButton(
                        onClick = {
                            vm.pingOllama()
                            vm.modelMenuExpanded = true
                        },
                        colors = brandButtonColors(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = (vm.selectedModel?.displayName?.take(20) ?: "(no model)") + "  \u25BE",
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = vm.modelMenuExpanded,
                        onDismissRequest = { vm.modelMenuExpanded = false },
                        // Cap the menu height so it never expands past where
                        // the keyboard normally sits. Without this cap the
                        // menu re-layouts (jumps) when the IME dismisses on
                        // item tap because its max-height grows.
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        if (vm.allModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models configured") },
                                onClick = { vm.modelMenuExpanded = false }
                            )
                        } else {
                            vm.allModels.forEach { model ->
                                val isOllamaModel = model.modelValue.startsWith("ollama:") ||
                                    model.modelValue.startsWith("openai:")
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(model.displayName)
                                            if (isOllamaModel && vm.ollamaReachable != null) {
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    Modifier
                                                        .size(8.dp)
                                                        .background(
                                                            color = if (vm.ollamaReachable == true) Color(0xFF4CAF50) else Color(0xFFE53935),
                                                            shape = CircleShape
                                                        )
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        vm.selectedModel = model
                                        vm.modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = {
                    systemPromptInput = vm.currentChatSystemPrompt
                    systemPromptDialogOpen = true
                }) {
                    Text(
                        text = if (vm.currentChatSystemPrompt.isNotBlank()) "\u2605" else "\u2606",
                        color = brandTeal(),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                // Temperature control
                Box {
                    IconButton(onClick = { temperaturePopupOpen = !temperaturePopupOpen }) {
                        Text(
                            text = if (vm.currentChatTemperature != null)
                                String.format("%.1f", vm.currentChatTemperature)
                            else "\uD83C\uDF21\uFE0F",
                            color = if (vm.currentChatTemperature != null) brandTeal() else Color.Gray,
                            fontSize = if (vm.currentChatTemperature != null) 13.sp else 16.sp,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = temperaturePopupOpen,
                        onDismissRequest = { temperaturePopupOpen = false },
                        modifier = Modifier.width(220.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Temperature",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (vm.currentChatTemperature != null) {
                                    TextButton(onClick = {
                                        vm.currentChatTemperature = null
                                        vm.saveTemperature()
                                    }) {
                                        Text("Reset", fontSize = 12.sp)
                                    }
                                }
                            }
                            Slider(
                                value = vm.currentChatTemperature ?: 0.7f,
                                onValueChange = { vm.currentChatTemperature = (Math.round(it * 10f) / 10f) },
                                onValueChangeFinished = { vm.saveTemperature() },
                                valueRange = 0f..2f,
                                steps = 19,
                                colors = SliderDefaults.colors(
                                    thumbColor = brandTeal(),
                                    activeTrackColor = brandTeal()
                                )
                            )
                            Text(
                                text = if (vm.currentChatTemperature != null)
                                    String.format("%.1f", vm.currentChatTemperature)
                                else "Default",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.drawerOpen = true }) {
                    Box {
                        Text(
                            text = "\u2630",
                            color = brandTeal(),
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (vm.unreadChatIds.isNotEmpty() || vm.unreadHasUnscoped) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-1).dp)
                                    .background(
                                        color = Color(0xFFFF9800),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }

            // Highlight match navigation
            if (vm.pendingHighlightQuery != null && vm.highlightMatchIndices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(brandTeal().copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\"${vm.pendingHighlightQuery}\"  ${vm.highlightCursor + 1}/${vm.highlightMatchIndices.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = brandTeal(),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (vm.highlightMatchIndices.isNotEmpty()) {
                                vm.highlightCursor = (vm.highlightCursor - 1 + vm.highlightMatchIndices.size) % vm.highlightMatchIndices.size
                                scope.launch { listState.animateScrollToItem(vm.highlightMatchIndices[vm.highlightCursor]) }
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("\u2191", color = brandTeal(), style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(
                        onClick = {
                            if (vm.highlightMatchIndices.isNotEmpty()) {
                                vm.highlightCursor = (vm.highlightCursor + 1) % vm.highlightMatchIndices.size
                                scope.launch { listState.animateScrollToItem(vm.highlightMatchIndices[vm.highlightCursor]) }
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("\u2193", color = brandTeal(), style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(
                        onClick = {
                            vm.pendingHighlightQuery = null
                            vm.highlightMatchIndices = emptyList()
                            vm.highlightCursor = 0
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("\u2715", color = brandTeal(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // Message list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (vm.messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Start a new chat",
                            style = MaterialTheme.typography.titleMedium,
                            color = brandTeal()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Try one of these:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        val examplePrompts = remember(vm.currentChatId) {
                            EXAMPLE_PROMPT_POOL.shuffled().take(3)
                        }
                        examplePrompts.forEach { prompt ->
                            ExamplePromptChip(
                                text = prompt,
                                onClick = {
                                    vm.inputText = prompt
                                    if (!keyboardVisible) keyboardVisible = true
                                    focusRequester.requestFocus()
                                    softwareKeyboard?.show()
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    var selectionResetKey by remember { mutableStateOf(0) }
                    androidx.compose.runtime.key(selectionResetKey) {
                    SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { selectionResetKey++ })
                            },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(vm.messages) { index, message ->
                            val prev = if (index > 0) vm.messages[index - 1] else null
                            val hideModelLabel = prev != null &&
                                prev.role == message.role &&
                                prev.modelLabel == message.modelLabel
                            val lastAssistantIdx = vm.messages.indexOfLast { it.role == "assistant" && !it.isPending }
                            val canRegenerate = !vm.isSending &&
                                message.role == "assistant" &&
                                !message.isPending &&
                                index == lastAssistantIdx
                            MessageBubble(
                                message = message,
                                hideModelLabel = hideModelLabel,
                                highlightQuery = vm.pendingHighlightQuery,
                                canRegenerate = canRegenerate,
                                onRegenerate = { vm.regenerateLast() },
                                onCopy = { text ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("AI response", text))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                onInsert = { text -> onInsert(text) },
                                onReply = { text ->
                                    vm.replyToContent = text
                                    if (!keyboardVisible) {
                                        keyboardVisible = true
                                    }
                                    focusRequester.requestFocus()
                                    softwareKeyboard?.show()
                                },
                                onTap = { messageSheetTarget = index },
                                onViewAttachment = { path -> fullscreenAttachmentPath = path },
                                onRetryCloud = if (message.isLocalError) {{ vm.retryWithCloud() }} else null
                            )
                        }
                    }
                    }
                    }
                }
            }

            // Reply banner
            if (vm.replyToContent != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(brandTeal().copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Replying to: ${vm.replyToContent?.take(80)}${if ((vm.replyToContent?.length ?: 0) > 80) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = brandTeal(),
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { vm.replyToContent = null },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("\u2715", color = brandTeal())
                    }
                }
            }

            // Queued attachments strip (above input row)
            if (vm.queuedAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.queuedAttachments) { att ->
                        AttachmentThumb(
                            path = att.localPath,
                            size = 72.dp,
                            mimeType = att.mimeType,
                            onClick = { fullscreenAttachmentPath = att.localPath },
                            onRemove = {
                                vm.queuedAttachments.remove(att)
                                try { java.io.File(att.localPath).delete() } catch (_: Exception) {}
                            }
                        )
                    }
                }
                val mv = vm.selectedModel?.modelValue
                val allOk = mv != null && vm.queuedAttachments.all { att ->
                    when {
                        att.mimeType.startsWith("image/") -> AiServiceSync.supportsImageInput(mv)
                        att.mimeType == "application/pdf" -> AiServiceSync.supportsPdfInput(mv)
                        else -> false
                    }
                }
                Text(
                    text = if (allOk)
                        "${vm.queuedAttachments.size} attachment(s) • can use significant tokens"
                    else
                        "Selected model doesn't support these files — switch model to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (allOk)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            // Input row (WhatsApp-style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { toggleKeyboard() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        text = if (keyboardVisible) "\u02C7" else "\u02C6",
                        color = brandTeal(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Capability flags for the paperclip menu (rendered inside the
                // text field as a trailing icon, WhatsApp-style).
                @Suppress("UNUSED_EXPRESSION") vm.capabilityTick // observe cache refresh
                val mvForAttach = vm.selectedModel?.modelValue
                val canAttachImage = mvForAttach?.let { AiServiceSync.supportsImageInput(it) } == true
                val canAttachPdf = mvForAttach?.let { AiServiceSync.supportsPdfInput(it) } == true
                val canAttachAny = canAttachImage || canAttachPdf
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    TextField(
                        value = vm.inputText,
                        onValueChange = { vm.inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 160.dp)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                "Message",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        },
                        trailingIcon = if (canAttachAny) {
                            {
                                IconButton(
                                    onClick = { attachSheetOpen = true },
                                    enabled = vm.queuedAttachments.size < 4
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_attach_file),
                                        contentDescription = "Attach",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (vm.queuedAttachments.size < 4) 0.65f else 0.3f
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        } else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.width(6.dp))
                val buttonEnabled = vm.isSending || canSend
                Surface(
                    onClick = {
                        if (vm.isSending) vm.stopGeneration()
                        else if (canSend) vm.sendMessage()
                    },
                    enabled = buttonEnabled,
                    shape = CircleShape,
                    color = if (buttonEnabled) brandTeal() else Color.Gray.copy(alpha = 0.4f),
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (vm.isSending) {
                            // Stop icon: white square
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(Color.White, RoundedCornerShape(2.dp))
                            )
                        } else {
                            Text(
                                text = "\u27A4",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
    }

        // Scrim behind drawer
        AnimatedVisibility(
            visible = vm.drawerOpen,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { vm.drawerOpen = false }
            )
        }

        // Right-side drawer
        AnimatedVisibility(
            visible = vm.drawerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Drawer header — swaps to a selection toolbar when multi-select is active.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) {
                            Text(
                                text = "${selectedChatIds.size} selected",
                                style = MaterialTheme.typography.titleMedium,
                                color = brandTeal(),
                                modifier = Modifier.weight(1f)
                            )
                            // Select-all over currently filtered chats.
                            IconButton(onClick = {
                                val allIds = filteredChats.map { it.id }
                                if (selectedChatIds.size == allIds.size) {
                                    selectedChatIds.clear()
                                } else {
                                    selectedChatIds.clear()
                                    selectedChatIds.addAll(allIds)
                                }
                            }) {
                                Text("\u2713", color = brandTeal(), style = MaterialTheme.typography.titleLarge)
                            }
                            IconButton(
                                onClick = { if (selectedChatIds.isNotEmpty()) bulkDeleteConfirm = true },
                                enabled = selectedChatIds.isNotEmpty()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chat_delete),
                                    contentDescription = "Delete selected",
                                    tint = if (selectedChatIds.isNotEmpty()) brandTeal()
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = {
                                selectionMode = false
                                selectedChatIds.clear()
                            }) {
                                Text("\u2715", color = brandTeal(), style = MaterialTheme.typography.titleLarge)
                            }
                        } else {
                            Text(
                                text = "Chats",
                                style = MaterialTheme.typography.titleMedium,
                                color = brandTeal(),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.startNewChat() }) {
                                Text("+", color = brandTeal(), style = MaterialTheme.typography.titleLarge)
                            }
                            IconButton(onClick = { vm.drawerOpen = false }) {
                                Text("\u2715", color = brandTeal(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    // Search field
                    OutlinedTextField(
                        value = vm.searchQuery,
                        onValueChange = { vm.searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .focusRequester(searchFocusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            softwareKeyboard?.hide()
                        }),
                        placeholder = {
                            Text(
                                "Search",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (vm.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.searchQuery = "" }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = brandTeal(),
                            cursorColor = brandTeal()
                        )
                    )
                    if (filteredChats.isEmpty()) {
                        val emptyMsg = if (vm.searchQuery.isNotEmpty()) "No matches" else "No chats yet"
                        Text(
                            text = emptyMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            val now = System.currentTimeMillis()
                            val grouped = filteredChats.groupBy { bucketLabel(it, now) }
                            grouped.forEach { (label, chats) ->
                                item(key = "h-$label") {
                                    Text(
                                        text = label.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = brandTeal().copy(alpha = 0.7f),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                items(chats, key = { it.id }) { meta ->
                                    val isCurrent = meta.id == vm.currentChatId
                                    val isSelected = meta.id in selectedChatIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isSelected -> brandTeal().copy(alpha = 0.28f)
                                                    isCurrent -> brandTeal().copy(alpha = 0.15f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    if (selectionMode) {
                                                        if (isSelected) {
                                                            selectedChatIds.remove(meta.id)
                                                            if (selectedChatIds.isEmpty()) selectionMode = false
                                                        } else {
                                                            selectedChatIds.add(meta.id)
                                                        }
                                                    } else {
                                                        vm.loadChat(meta.id, vm.searchQuery.takeIf { it.isNotBlank() })
                                                    }
                                                },
                                                onLongClick = {
                                                    if (selectionMode) {
                                                        // Already in multi-select: toggle this item
                                                        if (isSelected) {
                                                            selectedChatIds.remove(meta.id)
                                                            if (selectedChatIds.isEmpty()) selectionMode = false
                                                        } else {
                                                            selectedChatIds.add(meta.id)
                                                        }
                                                    } else {
                                                        // First long-press: show bottom sheet
                                                        sheetTarget = meta
                                                    }
                                                }
                                            )
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selectionMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .padding(end = 2.dp)
                                                    .background(
                                                        color = if (isSelected) brandTeal() else Color.Transparent,
                                                        shape = CircleShape
                                                    )
                                            ) {
                                                if (isSelected) {
                                                    Text(
                                                        text = "\u2713",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.align(Alignment.Center)
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(
                                                                color = Color.Transparent,
                                                                shape = CircleShape
                                                            )
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        if (meta.pinned) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_chat_pin),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .padding(end = 6.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = meta.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = formatChatTimestamp(meta.updatedAt, now),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                maxLines = 1
                                            )
                                        }
                                        if (meta.id in vm.unreadChatIds) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(start = 8.dp)
                                                    .size(8.dp)
                                                    .background(
                                                        color = Color(0xFFFF9800),
                                                        shape = CircleShape
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = brandTeal(),
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = brandTeal(),
                        focusedLabelColor = brandTeal(),
                        cursorColor = brandTeal()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newTitle = renameInput.trim()
                    if (newTitle.isNotEmpty()) {
                        vm.renameChat(target.id, newTitle)
                    }
                    renameTarget = null
                }) { Text("OK", color = brandTeal()) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = brandTeal())
                }
            }
        )
    }

    // Long-press bottom sheet (Pin / Rename / Delete)
    sheetTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.togglePin(target.id, target.pinned)
                            sheetTarget = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_pin),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        if (target.pinned) "Unpin" else "Pin",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            renameTarget = target
                            renameInput = target.title
                            sheetTarget = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_rename),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Rename",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val chatId = target.id
                            sheetTarget = null
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) {
                                    ConversationStore.load(context, chatId)
                                }
                                if (loaded == null) {
                                    Toast.makeText(context, "Chat not found", Toast.LENGTH_SHORT).show()
                                } else if (!ConversationExporter.share(context, loaded, "md")) {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_export),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Export (Markdown)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val chatId = target.id
                            sheetTarget = null
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) {
                                    ConversationStore.load(context, chatId)
                                }
                                if (loaded == null) {
                                    Toast.makeText(context, "Chat not found", Toast.LENGTH_SHORT).show()
                                } else if (!ConversationExporter.share(context, loaded, "zip")) {
                                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_export),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Export (Backup .zip)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deleteTarget = target
                            sheetTarget = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chat_delete),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectionMode = true
                            selectedChatIds.clear()
                            selectedChatIds.add(target.id)
                            sheetTarget = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_all),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        "Select",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // Long-press on individual message: Copy / Delete
    messageSheetTarget?.let { idx ->
        val target = vm.messages.getOrNull(idx)
        if (target == null) {
            messageSheetTarget = null
        } else {
            ModalBottomSheet(
                onDismissRequest = { messageSheetTarget = null },
                sheetState = messageSheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    if (target.role == "user" && !vm.isSending) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editTarget = idx
                                    editInput = target.content
                                    messageSheetTarget = null
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_rename),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(20.dp))
                            Text(
                                "Edit & resend",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Message", target.content))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                messageSheetTarget = null
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "\u2398",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.size(24.dp).wrapContentWidth(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Copy",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (idx in vm.messages.indices) {
                                    vm.messages.removeAt(idx)
                                    if (vm.currentChatId != null) {
                                        vm.persistCurrentChat(target.content, vm.selectedModel?.modelValue ?: "")
                                    }
                                }
                                messageSheetTarget = null
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chat_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "Delete",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    // Edit & resend dialog
    editTarget?.let { idx ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = brandTeal(),
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = editInput,
                    onValueChange = { editInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = brandTeal(),
                        focusedLabelColor = brandTeal(),
                        cursorColor = brandTeal()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newText = editInput.trim()
                    if (newText.isNotEmpty()) {
                        vm.resendFromIndex(idx, newText)
                    }
                    editTarget = null
                }) { Text("Resend", color = brandTeal()) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("Cancel", color = brandTeal())
                }
            }
        )
    }

    // System prompt dialog
    if (systemPromptDialogOpen) {
        AlertDialog(
            onDismissRequest = { systemPromptDialogOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = brandTeal(),
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("System prompt") },
            text = {
                Column {
                    Text(
                        text = "Custom instruction for this chat. Sent as a system message before every reply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = systemPromptInput,
                        onValueChange = { systemPromptInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        placeholder = {
                            Text(
                                "e.g. You are a senior Linux sysadmin. Be concise.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = brandTeal(),
                            focusedLabelColor = brandTeal(),
                            cursorColor = brandTeal()
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveSystemPrompt(systemPromptInput.trim())
                    systemPromptDialogOpen = false
                }) { Text("Save", color = brandTeal()) }
            },
            dismissButton = {
                TextButton(onClick = { systemPromptDialogOpen = false }) {
                    Text("Cancel", color = brandTeal())
                }
            }
        )
    }

    // Delete confirmation
    if (bulkDeleteConfirm) {
        val count = selectedChatIds.size
        AlertDialog(
            onDismissRequest = { bulkDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = brandTeal(),
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Delete $count chats") },
            text = { Text("Delete $count selected chat${if (count == 1) "" else "s"}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.bulkDeleteChats(selectedChatIds.toList())
                    selectedChatIds.clear()
                    selectionMode = false
                    bulkDeleteConfirm = false
                }) { Text("Delete", color = brandTeal()) }
            },
            dismissButton = {
                TextButton(onClick = { bulkDeleteConfirm = false }) {
                    Text("Cancel", color = brandTeal())
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = brandTeal(),
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Delete chat") },
            text = { Text("Delete chat \"${target.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = brandTeal()) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = brandTeal())
                }
            }
        )
    }

    fullscreenAttachmentPath?.let { p ->
        FullscreenImageDialog(path = p, onDismiss = { fullscreenAttachmentPath = null })
    }

    if (attachSheetOpen) {
        val mvSheet = vm.selectedModel?.modelValue
        val sheetCanImage = mvSheet?.let { AiServiceSync.supportsImageInput(it) } == true
        val sheetCanPdf = mvSheet?.let { AiServiceSync.supportsPdfInput(it) } == true
        AttachmentBottomSheet(
            canImage = sheetCanImage,
            canPdf = sheetCanPdf,
            onDismiss = { attachSheetOpen = false },
            onPickImage = {
                attachSheetOpen = false
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickCamera = {
                attachSheetOpen = false
                try {
                    val chatIdForShot = vm.currentChatId
                        ?: ConversationStore.newId().also { vm.currentChatId = it }
                    val tempDir = java.io.File(context.cacheDir, "camera_temp").apply { mkdirs() }
                    val tempFile = java.io.File(
                        tempDir,
                        "shot_${java.util.UUID.randomUUID()}.jpg"
                    )
                    val authority = context.getString(R.string.gesture_data_provider_authority)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, authority, tempFile
                    )
                    pendingCameraFile = tempFile
                    pendingCameraTargetChatId = chatIdForShot
                    cameraLauncher.launch(uri)
                } catch (e: Exception) {
                    Log.e("ConversationActivity", "camera launch failed", e)
                    Toast.makeText(context, "Camera niet beschikbaar", Toast.LENGTH_SHORT).show()
                }
            },
            onPickPdf = {
                attachSheetOpen = false
                pdfPickerLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }
}

private val EXAMPLE_PROMPT_POOL = listOf(
    // Tech / coding
    "Write a Python one-liner that reverses a string",
    "Explain Docker volumes in 3 sentences",
    "Suggest a small homelab project for this weekend",
    "What's the difference between TCP and UDP, like I'm 12?",
    "Give me a bash script that finds the 5 largest files in a folder",
    "Explain how SSH keys work without the math",
    "Write a regex that matches a valid IPv4 address",
    "What's a good self-hosted alternative to Google Photos?",
    "Compare SQLite, Postgres and DuckDB in one paragraph each",
    "Show me a minimal Kotlin coroutine example",
    // Writing / language
    "Rewrite this sentence to sound more confident: \"I think maybe we could try…\"",
    "Give me 5 strong synonyms for \"interesting\"",
    "Translate \"speak of the devil\" into Dutch and explain the nuance",
    "Write a haiku about a rainy Sunday",
    "Suggest a catchy name for a homelab blog",
    // Daily life / fun
    "Plan a lazy Sunday with good food and zero screens",
    "Recommend 3 board games for 4 players that aren't Catan",
    "What's a quick 15-minute pasta recipe with pantry staples?",
    "Suggest a movie if I liked Arrival and Interstellar",
    "Give me 3 stretches for someone who sits at a desk all day",
    "What's a fun fact about octopuses I probably don't know?",
    // Learning / curiosity
    "Explain quantum entanglement without using the word \"spooky\"",
    "Why is the sky blue, but sunsets are orange?",
    "Give me a 5-minute intro to stoicism",
    "How does a microwave actually heat food?",
    "What's the most efficient way to learn a new language as an adult?",
    // Creative / brainstorm
    "Invent a fictional band name and their first album title",
    "Pitch a sci-fi story in 3 sentences",
    "Brainstorm 5 weird but useful kitchen gadgets",
    "Give me a writing prompt about a lighthouse keeper",
    // Funny / silly
    "Roast my taste in music: I mostly listen to lo-fi beats and ABBA",
    "Write a dramatic breakup letter to my Wi-Fi router",
    "Pretend you're a pirate explaining how Git works",
    "What would a cat write in its diary after a long nap?",
    "Convince me that pineapple belongs on pizza, with full conviction",
    "Invent a conspiracy theory about why socks disappear in the laundry",
    "Write a Shakespearean sonnet about running out of coffee",
    "Give me the most overdramatic way to say \"I'm hungry\"",
    "If my houseplant could text me, what would its first message be?",
    "Rank breakfast cereals by how much they would survive in a zombie apocalypse"
)

private fun formatChatTimestamp(updatedAt: Long, now: Long): String {
    val day = 24 * 60 * 60 * 1000L
    val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
    val nowDay = (now + tzOffset) / day
    val itemDay = (updatedAt + tzOffset) / day
    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val dateFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    val fullFmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = updatedAt }
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    return when {
        itemDay == nowDay -> timeFmt.format(updatedAt)
        itemDay == nowDay - 1 -> "Yesterday ${timeFmt.format(updatedAt)}"
        now - updatedAt < 7 * day -> {
            val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            "${days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]} ${timeFmt.format(updatedAt)}"
        }
        cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) -> dateFmt.format(updatedAt)
        else -> fullFmt.format(updatedAt)
    }
}

private fun bucketLabel(meta: ConversationStore.ChatMeta, now: Long): String {
    if (meta.pinned) return "Pinned"
    val day = 24 * 60 * 60 * 1000L
    val tzOffset = java.util.TimeZone.getDefault().getOffset(now)
    val nowDay = (now + tzOffset) / day
    val itemDay = (meta.updatedAt + tzOffset) / day
    return when {
        itemDay == nowDay -> "Today"
        itemDay == nowDay - 1 -> "Yesterday"
        now - meta.updatedAt < 7 * day -> "Last 7 days"
        now - meta.updatedAt < 30 * day -> "Last 30 days"
        else -> "Older"
    }
}

private fun applyHighlight(annotated: AnnotatedString, query: String?): AnnotatedString {
    if (query.isNullOrBlank()) return annotated
    val text = annotated.text
    val q = query.lowercase()
    val ranges = mutableListOf<IntRange>()
    val lower = text.lowercase()
    var i = 0
    while (i < lower.length) {
        val idx = lower.indexOf(q, i)
        if (idx < 0) break
        ranges.add(idx until idx + q.length)
        i = idx + q.length
    }
    if (ranges.isEmpty()) return annotated
    return buildAnnotatedString {
        append(annotated)
        ranges.forEach { r ->
            addStyle(
                SpanStyle(background = Color(0xFFFFEB3B), color = Color.Black),
                r.first, r.last + 1
            )
        }
    }
}

@Composable
private fun ExamplePromptChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = brandTeal().copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

private sealed class MdSegment {
    data class Text(val text: String) : MdSegment()
    data class Code(val language: String, val code: String) : MdSegment()
    data class ToolCall(
        val name: String,
        val args: String,
        val result: String,
        val isError: Boolean
    ) : MdSegment()
}

private const val TOOL_MARKER_OPEN = "\u27E6TOOL\u27E7"
private const val TOOL_MARKER_CLOSE = "\u27E6/TOOL\u27E7"

// Regex that matches both old (⟦TOOL⟧) and nonce (⟦TOOL_abc12345⟧) marker formats.
private val TOOL_OPEN_REGEX = Regex("\u27E6TOOL(?:_[a-f0-9]{8})?\u27E7")
private val TOOL_CLOSE_REGEX = Regex("\u27E6/TOOL(?:_[a-f0-9]{8})?\u27E7")

/**
 * Replace ⟦TOOL⟧...⟦/TOOL⟧ blocks with a short plain-text representation so
 * copy / insert / reply actions produce something readable instead of raw
 * JSON markers.
 */
private fun stripToolMarkers(content: String): String {
    if (!TOOL_OPEN_REGEX.containsMatchIn(content)) return content
    val sb = StringBuilder()
    var i = 0
    while (i < content.length) {
        val openMatch = TOOL_OPEN_REGEX.find(content, i) ?: run {
            sb.append(content, i, content.length); break
        }
        sb.append(content, i, openMatch.range.first)
        val jsonStart = openMatch.range.last + 1
        val closeMatch = TOOL_CLOSE_REGEX.find(content, jsonStart) ?: run {
            sb.append(content, openMatch.range.first, content.length); break
        }
        try {
            val obj = org.json.JSONObject(content.substring(jsonStart, closeMatch.range.first))
            val name = obj.optString("name")
            val isErr = obj.optBoolean("error")
            sb.append(if (isErr) "[tool error: $name]" else "[tool: $name]")
        } catch (_: Exception) { /* swallow malformed block */ }
        i = closeMatch.range.last + 1
        if (i < content.length && content[i] == '\n') i++
    }
    return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * Splits a message body into a sequence of plain-text and fenced-code segments.
 * Triple-backtick code fences with optional language tag are recognized; an
 * unterminated fence (e.g. while still streaming) is rendered as a code block
 * with whatever has arrived so far.
 */
private fun parseMarkdownSegments(input: String): List<MdSegment> {
    val out = mutableListOf<MdSegment>()
    // First pass: split on ⟦TOOL⟧ / ⟦TOOL_nonce⟧ markers so tool-call JSON
    // blobs don't get scanned for code fences (rendered as structured cards).
    var i = 0
    while (i < input.length) {
        val openMatch = TOOL_OPEN_REGEX.find(input, i) ?: run {
            parseCodeSegments(input.substring(i), out)
            break
        }
        val start = openMatch.range.first
        if (start > i) parseCodeSegments(input.substring(i, start), out)
        val jsonStart = openMatch.range.last + 1
        val closeMatch = TOOL_CLOSE_REGEX.find(input, jsonStart) ?: run {
            // Unterminated marker — fall back to raw text rendering.
            parseCodeSegments(input.substring(start), out)
            break
        }
        val json = input.substring(jsonStart, closeMatch.range.first)
        try {
            val obj = org.json.JSONObject(json)
            out.add(MdSegment.ToolCall(
                name = obj.optString("name"),
                args = obj.optString("args"),
                result = obj.optString("result"),
                isError = obj.optBoolean("error")
            ))
        } catch (_: Exception) {
            parseCodeSegments(input.substring(start, closeMatch.range.last + 1), out)
        }
        i = closeMatch.range.last + 1
        if (i < input.length && input[i] == '\n') i++
    }
    return out
}

private fun parseCodeSegments(input: String, out: MutableList<MdSegment>) {
    var i = 0
    while (i < input.length) {
        val fence = input.indexOf("```", i)
        if (fence < 0) {
            if (i < input.length) out.add(MdSegment.Text(input.substring(i)))
            break
        }
        if (fence > i) out.add(MdSegment.Text(input.substring(i, fence)))
        val afterFence = fence + 3
        // Optional language identifier on the same line as the opening fence
        val nl = input.indexOf('\n', afterFence)
        val lang: String
        val codeStart: Int
        if (nl < 0) {
            lang = ""
            codeStart = afterFence
        } else {
            val maybeLang = input.substring(afterFence, nl).trim()
            if (maybeLang.matches(Regex("[A-Za-z0-9_+\\-.]*"))) {
                lang = maybeLang
                codeStart = nl + 1
            } else {
                lang = ""
                codeStart = afterFence
            }
        }
        val closeFence = input.indexOf("```", codeStart)
        if (closeFence < 0) {
            // Unterminated (streaming-in-progress) — render whatever we have
            out.add(MdSegment.Code(lang, input.substring(codeStart)))
            break
        }
        out.add(MdSegment.Code(lang, input.substring(codeStart, closeFence).trimEnd('\n')))
        i = closeFence + 3
        if (i < input.length && input[i] == '\n') i++
    }
}

/**
 * Inline markdown for non-code segments:
 *   **bold**   `inline code`   *italic* / _italic_   [label](url)
 *
 * Links use LinkAnnotation.Url so the system opens them in the browser
 * on tap. Italic and bold can nest (we apply bold first, then italic).
 */
private fun renderInlineMarkdown(text: String): AnnotatedString {
    val linkColor = Color(0xFF00BFA5)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val linkStart = findLinkStart(text, i)
            val boldStart = text.indexOf("**", i)
            val codeStart = text.indexOf('`', i)
            val italicStart = findItalicStart(text, i)
            val autoUrlStart = findAutoUrlStart(text, i)

            val candidates = listOfNotNull(
                if (linkStart >= 0) linkStart to "link" else null,
                if (boldStart >= 0) boldStart to "bold" else null,
                if (codeStart >= 0) codeStart to "code" else null,
                if (italicStart >= 0) italicStart to "italic" else null,
                if (autoUrlStart >= 0) autoUrlStart to "autolink" else null,
            )
            if (candidates.isEmpty()) {
                append(text.substring(i))
                break
            }
            val (pos, kind) = candidates.minBy { it.first }
            if (pos > i) append(text.substring(i, pos))

            when (kind) {
                "bold" -> {
                    val end = text.indexOf("**", pos + 2)
                    if (end < 0) { append(text.substring(pos)); break }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(pos + 2, end))
                    }
                    i = end + 2
                }
                "code" -> {
                    val end = text.indexOf('`', pos + 1)
                    if (end < 0) { append(text.substring(pos)); break }
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33808080)
                        )
                    ) {
                        append(text.substring(pos + 1, end))
                    }
                    i = end + 1
                }
                "italic" -> {
                    val marker = text[pos]
                    val end = text.indexOf(marker, pos + 1)
                    if (end < 0 || end == pos + 1) {
                        append(text[pos].toString()); i = pos + 1
                    } else {
                        withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(text.substring(pos + 1, end))
                        }
                        i = end + 1
                    }
                }
                "autolink" -> {
                    val end = findAutoUrlEnd(text, pos)
                    val url = text.substring(pos, end)
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Url(
                            url,
                            androidx.compose.ui.text.TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                )
                            )
                        )
                    ) {
                        append(url)
                    }
                    i = end
                }
                "link" -> {
                    val labelEnd = text.indexOf(']', pos + 1)
                    val urlStart = if (labelEnd >= 0 && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') labelEnd + 2 else -1
                    val urlEnd = if (urlStart > 0) text.indexOf(')', urlStart) else -1
                    if (labelEnd < 0 || urlStart < 0 || urlEnd < 0) {
                        append(text[pos].toString()); i = pos + 1
                    } else {
                        val label = text.substring(pos + 1, labelEnd)
                        val url = text.substring(urlStart, urlEnd)
                        withLink(
                            androidx.compose.ui.text.LinkAnnotation.Url(
                                url,
                                androidx.compose.ui.text.TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                    )
                                )
                            )
                        ) {
                            append(label)
                        }
                        i = urlEnd + 1
                    }
                }
            }
        }
    }
}

/**
 * Index of the next bare http:// or https:// URL at or after [from], or -1.
 * Skips URLs that are part of a markdown `[label](url)` construct (those
 * are handled by the "link" branch) and URLs directly following `](` so
 * we don't double-linkify them.
 */
private fun findAutoUrlStart(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        val http = text.indexOf("http", i)
        if (http < 0) return -1
        val isUrl = (http + 7 <= text.length && text.substring(http, http + 7) == "http://") ||
                (http + 8 <= text.length && text.substring(http, http + 8) == "https://")
        if (!isUrl) { i = http + 1; continue }
        // Skip if this URL is inside the (url) part of a markdown link
        val prev = if (http > 0) text[http - 1] else ' '
        if (prev == '(' && http >= 2 && text[http - 2] == ']') { i = http + 1; continue }
        // Skip if the URL is surrounded by backticks (inline code)
        if (prev == '`') { i = http + 1; continue }
        return http
    }
    return -1
}

/**
 * End index (exclusive) of a bare URL starting at [start]. Stops at the
 * first whitespace, closing bracket/paren, or trailing punctuation that
 * is unlikely to belong to the URL (.,;:!?).
 */
private fun findAutoUrlEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length) {
        val c = text[end]
        if (c.isWhitespace() || c == '<' || c == '>' || c == '"' || c == '\'' ||
            c == '`' || c == '|' || c == '[' || c == ']' || c == '{' || c == '}') break
        end++
    }
    // Trim trailing punctuation that usually isn't part of the URL.
    while (end > start + 1) {
        val c = text[end - 1]
        if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')') end--
        else break
    }
    return end
}

/** Index of a valid `[label](url)` start at or after [from], or -1. */
private fun findLinkStart(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        val b = text.indexOf('[', i)
        if (b < 0) return -1
        val close = text.indexOf(']', b + 1)
        if (close > 0 && close + 1 < text.length && text[close + 1] == '(' && text.indexOf(')', close + 2) > 0) {
            return b
        }
        i = b + 1
    }
    return -1
}

/**
 * Single-char italic marker (* or _). Skips ** (bold) and word-internal
 * underscores so `snake_case` doesn't render half the word as italic.
 */
private fun findItalicStart(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c == '*') {
            // Skip bold markers
            if (i + 1 < text.length && text[i + 1] == '*') { i += 2; continue }
            // Must have a valid closer
            if (text.indexOf('*', i + 1) > 0) return i
        } else if (c == '_') {
            val prevAlnum = i > 0 && text[i - 1].isLetterOrDigit()
            val nextAlnum = i + 1 < text.length && text[i + 1].isLetterOrDigit()
            if (!prevAlnum && nextAlnum && text.indexOf('_', i + 1) > 0) return i
        }
        i++
    }
    return -1
}

/**
 * Block-level markdown: splits a text run into headings, bullet lists,
 * numbered lists, blockquotes and plain paragraphs. Used by MessageBubble
 * to render each block with the appropriate composable.
 */
private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val index: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            out.add(MdBlock.Paragraph(paragraph.toString().trimEnd('\n')))
            paragraph.clear()
        }
    }
    val lines = input.split('\n')
    for (line in lines) {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("### ") -> { flushParagraph(); out.add(MdBlock.Heading(3, trimmed.removePrefix("### "))) }
            trimmed.startsWith("## ") -> { flushParagraph(); out.add(MdBlock.Heading(2, trimmed.removePrefix("## "))) }
            trimmed.startsWith("# ") -> { flushParagraph(); out.add(MdBlock.Heading(1, trimmed.removePrefix("# "))) }
            trimmed.startsWith("> ") -> { flushParagraph(); out.add(MdBlock.Quote(trimmed.removePrefix("> "))) }
            (trimmed.startsWith("- ") || trimmed.startsWith("* ")) && indent <= 3 -> {
                flushParagraph(); out.add(MdBlock.Bullet(trimmed.substring(2)))
            }
            trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                flushParagraph()
                val dot = trimmed.indexOf('.')
                out.add(MdBlock.Numbered(trimmed.substring(0, dot), trimmed.substring(dot + 2)))
            }
            line.isBlank() -> {
                flushParagraph()
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    return out
}

@Composable
private fun RenderMarkdownBlock(block: MdBlock, highlightQuery: String?) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = applyHighlight(renderInlineMarkdown(block.text), highlightQuery),
                style = style,
                fontWeight = FontWeight.Bold,
                color = onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }
        is MdBlock.Bullet -> {
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = brandTeal()
                )
                Text(
                    text = applyHighlight(renderInlineMarkdown(block.text), highlightQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface
                )
            }
        }
        is MdBlock.Numbered -> {
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = "${block.index}. ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = brandTeal(),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = applyHighlight(renderInlineMarkdown(block.text), highlightQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface
                )
            }
        }
        is MdBlock.Quote -> {
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(brandTeal().copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = applyHighlight(renderInlineMarkdown(block.text), highlightQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface.copy(alpha = 0.85f),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
        is MdBlock.Paragraph -> {
            val annotated = applyHighlight(renderInlineMarkdown(block.text), highlightQuery)
            if (annotated.isNotEmpty()) {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface
                )
            }
        }
    }
}

@Composable
private fun CodeBlockView(
    language: String,
    code: String,
    onCopy: (String) -> Unit,
    highlightQuery: String? = null
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (language.isBlank()) "code" else language,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onCopy(code) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "\u2398",
                        color = Color(0xFFB0B0B0),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val hScroll = rememberScrollState()
            Text(
                text = applyHighlight(AnnotatedString(code), highlightQuery),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE6E6E6),
                modifier = Modifier.horizontalScroll(hScroll)
            )
        }
    }
}

/**
 * Decodes a (small) bitmap from disk for thumbnail rendering. Caches by path
 * within the composition so repeated draws don't keep hitting decoder.
 */
@Composable
private fun rememberThumbnailBitmap(path: String, maxSize: Int = 256): android.graphics.Bitmap? {
    return remember(path, maxSize) {
        try {
            if (path.endsWith(".pdf", ignoreCase = true)) {
                renderPdfFirstPage(path, maxSize)
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                while (maxDim / sample > maxSize) sample *= 2
                val realOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, realOpts)
            }
        } catch (e: Exception) {
            Log.e("Conversation", "thumbnail decode failed for $path", e)
            null
        }
    }
}

/** Render the first page of a PDF as a bitmap, scaled to ~maxSize on the long side. */
private fun renderPdfFirstPage(pdfPath: String, maxSize: Int): android.graphics.Bitmap? {
    val src = java.io.File(pdfPath)
    if (!src.exists()) return null
    var renderer: android.graphics.pdf.PdfRenderer? = null
    var pfd: android.os.ParcelFileDescriptor? = null
    return try {
        pfd = android.os.ParcelFileDescriptor.open(src, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = android.graphics.pdf.PdfRenderer(pfd)
        if (renderer.pageCount == 0) return null
        val page = renderer.openPage(0)
        val ratio = maxSize.toFloat() / maxOf(page.width, page.height)
        val w = (page.width * ratio).toInt().coerceAtLeast(1)
        val h = (page.height * ratio).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        bmp
    } catch (e: Exception) {
        Log.e("Conversation", "renderPdfFirstPage failed for $pdfPath", e)
        null
    } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}

@Composable
private fun AttachmentThumb(
    path: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    mimeType: String = "image/jpeg",
    onRemove: (() -> Unit)? = null
) {
    val isPdf = mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)
    Box(
        modifier = Modifier.size(size)
    ) {
        val bmp = rememberThumbnailBitmap(path)
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = if (isPdf) "pdf attachment" else "image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPdf) Color.White else Color.Transparent)
                    .clickable { onClick() }
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxSize().clickable { onClick() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (isPdf) "\uD83D\uDCC4" else "\uD83D\uDDBC")
                }
            }
        }
        if (isPdf) {
            // PDF badge in the bottom-left corner so the user can tell at a
            // glance this is a document, not an image.
            Surface(
                color = brandTeal(),
                shape = RoundedCornerShape(topEnd = 6.dp, bottomStart = 8.dp),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = "PDF",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (onRemove != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = CircleShape,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .clickable { onRemove() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("\u2715", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * WhatsApp-style attachment chooser. Bottom sheet with large coloured tiles
 * for each file type the current model supports. Tiles are only rendered for
 * capabilities that the selected model can actually accept, so the user
 * never sees an option that would be rejected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentBottomSheet(
    canImage: Boolean,
    canPdf: Boolean,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickCamera: () -> Unit,
    onPickPdf: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Bestand toevoegen",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (canPdf) {
                    AttachmentTile(
                        emoji = "\uD83D\uDCC4",
                        label = "Document",
                        bgColor = Color(0xFF8E5BEF),
                        onClick = onPickPdf
                    )
                }
                if (canImage) {
                    AttachmentTile(
                        emoji = "\uD83D\uDDBC",
                        label = "Galerij",
                        bgColor = Color(0xFF1A9E8F),
                        onClick = onPickImage
                    )
                }
                if (canImage) {
                    AttachmentTile(
                        emoji = "\uD83D\uDCF7",
                        label = "Camera",
                        bgColor = Color(0xFFE7543A),
                        onClick = onPickCamera
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    emoji: String,
    label: String,
    bgColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = bgColor.copy(alpha = 0.18f),
            modifier = Modifier.size(60.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun FullscreenImageDialog(path: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val bmp = rememberThumbnailBitmap(path, maxSize = 2048)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UiMessage,
    hideModelLabel: Boolean,
    highlightQuery: String?,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onCopy: (String) -> Unit,
    onInsert: (String) -> Unit,
    onReply: (String) -> Unit,
    onTap: () -> Unit,
    onViewAttachment: (String) -> Unit = {},
    onRetryCloud: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> brandTeal().copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isUser && !hideModelLabel && message.modelLabel.isNotEmpty()) {
            Text(
                text = message.modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        val hasCodeBlocks = !isUser && !message.isError && message.content.contains("```")
        val bubbleMaxWidth = if (hasCodeBlocks) 360.dp else 320.dp
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                .wrapContentWidth()
                // Tap opens the message actions sheet (edit & resend, copy, delete).
                // Long-press is deliberately NOT intercepted here so it falls through
                // to the enclosing SelectionContainer for native text selection.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(bottom = if (message.content.isNotEmpty()) 6.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(message.attachments) { att ->
                            AttachmentThumb(
                                path = att.path,
                                size = 96.dp,
                                mimeType = att.mimeType,
                                onClick = { onViewAttachment(att.path) }
                            )
                        }
                    }
                }
                if (message.isPending && message.content.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = brandTeal()
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else if (isUser || message.isError) {
                    Text(
                        text = applyHighlight(AnnotatedString(message.content), highlightQuery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (message.isLocalError && onRetryCloud != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onRetryCloud,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.heightIn(min = 28.dp)
                        ) {
                            Text(
                                "Try with cloud model?",
                                color = brandTeal(),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                } else {
                    val segments = remember(message.content) { parseMarkdownSegments(message.content) }
                    segments.forEachIndexed { idx, seg ->
                        when (seg) {
                            is MdSegment.Text -> {
                                val blocks = remember(seg.text) { parseMarkdownBlocks(seg.text) }
                                blocks.forEach { block ->
                                    RenderMarkdownBlock(block, highlightQuery)
                                }
                            }
                            is MdSegment.Code -> {
                                if (idx > 0) Spacer(Modifier.height(4.dp))
                                CodeBlockView(
                                    language = seg.language,
                                    code = seg.code,
                                    onCopy = onCopy,
                                    highlightQuery = highlightQuery
                                )
                                if (idx < segments.size - 1) Spacer(Modifier.height(4.dp))
                            }
                            is MdSegment.ToolCall -> {
                                if (idx > 0) Spacer(Modifier.height(3.dp))
                                ToolCallCard(seg.name, seg.args, seg.result, seg.isError)
                                if (idx < segments.size - 1) Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }
            }
        }
        if (!isUser && !message.isError && !message.isPending) {
            val actionColor = brandTeal().copy(alpha = 0.75f)
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cleanContent = remember(message.content) { stripToolMarkers(message.content) }
                IconButton(
                    onClick = { onReply(cleanContent) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(
                        text = "\u21A9",
                        color = actionColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { onCopy(cleanContent) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(
                        text = "\u2398",
                        color = actionColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (canRegenerate) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { onRegenerate() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "\u21BB",
                            color = actionColor,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                TextButton(
                    onClick = { onInsert(cleanContent) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 28.dp)
                ) {
                    Text("Insert", color = actionColor, style = MaterialTheme.typography.labelMedium)
                }
                if (message.tokenCount != null && message.tokenCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${message.tokenCount} tk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Collapsible card rendering an MCP / built-in tool call. Collapsed shows the
 * tool name and a status icon; tap to expand and reveal arguments + raw result.
 */
@Composable
private fun ToolCallCard(name: String, args: String, result: String, isError: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val icon = if (isError) "\u26A0" else "\uD83D\uDD27"
    val borderColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                      else brandTeal().copy(alpha = 0.5f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$icon  $name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "\u25B4" else "\u25BE",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (expanded) {
                if (args.isNotBlank() && args != "{}") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "args",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = args,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
                if (result.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isError) "error" else "result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}
