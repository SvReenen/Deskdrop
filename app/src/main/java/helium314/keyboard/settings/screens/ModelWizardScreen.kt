// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.ai.AiServiceSync
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val contextSteps = listOf(2048, 4096, 8192, 16384, 32768)
private val protectedModels = emptyList<String>()

private data class OllamaModel(val name: String, val size: String, val description: String)
private val curatedModels = listOf(
    OllamaModel("gemma3:4b", "3.3 GB", "Fast, lightweight, good for simple tasks"),
    OllamaModel("gemma3:12b", "8.1 GB", "Balanced performance and quality"),
    OllamaModel("gemma3:27b", "17 GB", "High quality, needs more resources"),
    OllamaModel("phi4-mini", "2.5 GB", "Microsoft, very compact"),
    OllamaModel("llama3.2:3b", "2.0 GB", "Meta, fast and small"),
    OllamaModel("llama3:8b", "4.7 GB", "Meta, solid all-rounder"),
    OllamaModel("mistral:7b", "4.1 GB", "Mistral AI, good for text tasks"),
    OllamaModel("mistral-small3.1:24b", "15 GB", "Mistral AI, high quality"),
    OllamaModel("qwen3:8b", "4.9 GB", "Alibaba, strong multilingual"),
)

@Composable
fun ModelWizardScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scope = rememberCoroutineScope()
    val ollamaUrl = AiServiceSync.normalizeOllamaUrl(prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL)

    val teal = brandTeal()

    // Form state
    var modelName by remember { mutableStateOf("") }
    var baseModel by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var temperature by remember { mutableFloatStateOf(0.2f) }
    var ctxIndex by remember { mutableIntStateOf(1) }
    var isEditing by remember { mutableStateOf(false) }

    // Lists
    val baseModels = remember { mutableStateListOf<String>() }
    val customModels = remember { mutableStateListOf<String>() }

    // Status
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var showBaseModelPicker by remember { mutableStateOf(false) }
    var showPromptModelPicker by remember { mutableStateOf(false) }
    var isGeneratingPrompt by remember { mutableStateOf(false) }
    var deleteConfirmModel by remember { mutableStateOf<String?>(null) }
    var showPullModelPicker by remember { mutableStateOf(false) }
    var pullModelCustomName by remember { mutableStateOf("") }
    var isPulling by remember { mutableStateOf(false) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var pullStatus by remember { mutableStateOf("") }
    var showDownloadSection by remember { mutableStateOf(false) }
    var deleteBaseConfirmModel by remember { mutableStateOf<String?>(null) }
    var showCustomModelsSection by remember { mutableStateOf(false) }
    var pullErrorDialog by remember { mutableStateOf<String?>(null) }

    val nameRegex = remember { Regex("^[a-z0-9][a-z0-9._-]*$") }
    val nameValid = modelName.isBlank() || nameRegex.matches(modelName)

    val tealTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = teal,
        focusedLabelColor = teal,
        cursorColor = teal
    )

    fun resetForm() {
        modelName = ""
        baseModel = ""
        systemPrompt = ""
        temperature = 0.2f
        ctxIndex = 1
        isEditing = false
    }

    fun refreshModels(showLoading: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (showLoading) isLoading = true
            try {
                val split = AiServiceSync.fetchModelsSplit(ollamaUrl)
                withContext(Dispatchers.Main) {
                    baseModels.clear()
                    baseModels.addAll(split.baseModels)
                    customModels.clear()
                    customModels.addAll(split.customModels)
                    if (baseModel.isBlank() && split.baseModels.isNotEmpty()) {
                        baseModel = split.baseModels.first()
                    }
                }
                // Update shared Ollama cache so slot/instruction dialogs see the models
                val allModels = split.baseModels + split.customModels
                val suffix = " (Ollama)"
                val cacheItems = allModels.map { model ->
                    val details = AiServiceSync.fetchModelDetails(ollamaUrl, model)
                    val isCustom = details != null && details.parentModel.isNotBlank()
                    val prompt = if (isCustom && details != null && details.system.isNotBlank()) details.system else ""
                    helium314.keyboard.latin.ai.ModelItem(
                        displayName = "$model$suffix",
                        modelValue = "ollama:$model",
                        isCustomPreset = isCustom,
                        customPrompt = prompt
                    )
                }
                helium314.keyboard.latin.ai.writeOllamaCache(prefs, ollamaUrl, cacheItems)
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Cannot connect to Ollama"
                    statusIsError = true
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refreshModels(showLoading = true) }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Model Wizard",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
            // Brand header
            BrandHeader("Model Wizard")

            // About me / Lorebook
            var lorebookText by remember {
                mutableStateOf(prefs.getString(Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: "")
            }
            var lorebookExpanded by remember { mutableStateOf(lorebookText.isNotBlank()) }

            BrandCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "About me",
                            style = MaterialTheme.typography.titleMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { lorebookExpanded = !lorebookExpanded }) {
                            Text(
                                if (lorebookExpanded) "Collapse" else if (lorebookText.isNotBlank()) "Edit" else "Set up",
                                color = teal
                            )
                        }
                    }
                    Text(
                        "Tell the AI about yourself. This is sent with every request so it knows your name, style, and preferences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lorebookExpanded) {
                        OutlinedTextField(
                            value = lorebookText,
                            onValueChange = {
                                lorebookText = it
                                prefs.edit().putString(Settings.PREF_AI_LOREBOOK, it).apply()
                            },
                            placeholder = { Text("E.g. My name is Alex. I'm a software developer. I prefer informal, concise communication.") },
                            minLines = 3,
                            maxLines = 6,
                            colors = tealTextFieldColors,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else if (lorebookText.isNotBlank()) {
                        Text(
                            lorebookText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Pull model section
            fun startPull(name: String) {
                isPulling = true
                pullProgress = 0f
                pullStatus = "Starting download..."
                statusMessage = ""
                scope.launch(Dispatchers.IO) {
                    val error = AiServiceSync.pullModel(ollamaUrl, name) { progress ->
                        if (progress.total > 0) {
                            pullProgress = progress.completed.toFloat() / progress.total
                        }
                        pullStatus = progress.status
                    }
                    withContext(Dispatchers.Main) {
                        isPulling = false
                        pullStatus = ""
                        if (error == null) {
                            statusMessage = "Model '$name' downloaded successfully"
                            statusIsError = false
                            pullModelCustomName = ""
                            refreshModels()
                        } else {
                            statusMessage = "Error downloading model: $error"
                            statusIsError = true
                            pullErrorDialog = error
                        }
                    }
                }
            }

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = teal)
                    Text("Loading models...", modifier = Modifier.padding(start = 12.dp))
                }
            } else {
                // Form card
                BrandCard {
                    Column {
                        Text(
                            if (isEditing) "Update model" else "New model",
                            style = MaterialTheme.typography.titleMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Model name
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it.lowercase() },
                            label = { Text("Model name") },
                            isError = !nameValid,
                            supportingText = if (!nameValid) {{ Text("Only lowercase letters, digits, dots and dashes") }} else null,
                            enabled = !isEditing,
                            singleLine = true,
                            colors = tealTextFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Base model picker
                        OutlinedButton(
                            onClick = { showBaseModelPicker = true },
                            colors = brandOutlinedButtonColors(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Text(if (baseModel.isNotBlank()) "Base model: $baseModel" else "Select base model")
                        }

                        if (showBaseModelPicker && baseModels.isNotEmpty()) {
                            helium314.keyboard.settings.dialogs.ListPickerDialog(
                                onDismissRequest = { showBaseModelPicker = false },
                                items = baseModels.map { it to it },
                                onItemSelected = { baseModel = it.second },
                                selectedItem = baseModels.map { it to it }.firstOrNull { it.first == baseModel },
                                title = { Text("Base model") },
                                getItemName = { it.first }
                            )
                        }

                        // System prompt
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "System prompt",
                                style = MaterialTheme.typography.bodyMedium,
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )
                            if (isGeneratingPrompt) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = teal
                                    )
                                    Text(
                                        "Generating prompt...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = teal,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { showPromptModelPicker = true },
                                    enabled = systemPrompt.isNotBlank(),
                                    colors = brandOutlinedButtonColors(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("✨ AI Prompt", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text(
                            "Briefly describe what the model should do. Press 'AI Prompt' to automatically convert your description into a professional system prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        val consumeScroll = remember {
                            object : NestedScrollConnection {
                                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                    return Offset(0f, available.y)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            placeholder = { Text("E.g. a model that writes summaries of medical letters") },
                            minLines = 3,
                            maxLines = 6,
                            colors = tealTextFieldColors,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).nestedScroll(consumeScroll)
                        )

                        if (showPromptModelPicker) {
                            val allModels = (baseModels + customModels).map { it to it }
                            helium314.keyboard.settings.dialogs.ListPickerDialog(
                                onDismissRequest = { showPromptModelPicker = false },
                                items = allModels,
                                onItemSelected = { selected ->
                                    showPromptModelPicker = false
                                    isGeneratingPrompt = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val result = AiServiceSync.generateSystemPrompt(ollamaUrl, selected.second, systemPrompt)
                                            withContext(Dispatchers.Main) {
                                                systemPrompt = result
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                statusMessage = "Error generating prompt: ${e.message}"
                                                statusIsError = true
                                            }
                                        }
                                        withContext(Dispatchers.Main) { isGeneratingPrompt = false }
                                    }
                                },
                                title = { Text("Choose model for prompt") },
                                getItemName = { it.first }
                            )
                        }

                        // Temperature slider
                        Text(
                            "Creativity: ${"%.1f".format(temperature)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            "Controls how predictable or varied the model's responses are. Lower values give more consistent results.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Precise", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = temperature,
                                onValueChange = { temperature = (Math.round(it * 10) / 10f) },
                                valueRange = 0f..1f,
                                steps = 9,
                                colors = brandSliderColors(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text("Creative", style = MaterialTheme.typography.bodySmall)
                        }

                        // Context window slider
                        Text(
                            "Memory: ${contextSteps[ctxIndex]} tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            "How much text the model can consider at once. Higher values use more resources but allow longer conversations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Short", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = ctxIndex.toFloat(),
                                onValueChange = { ctxIndex = it.toInt() },
                                valueRange = 0f..4f,
                                steps = 3,
                                colors = brandSliderColors(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text("Long", style = MaterialTheme.typography.bodySmall)
                        }

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isEditing) {
                                OutlinedButton(
                                    onClick = { resetForm() },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Cancel")
                                }
                            }
                            Button(
                                onClick = {
                                    if (modelName.isBlank() || baseModel.isBlank() || !nameValid) return@Button
                                    isCreating = true
                                    statusMessage = ""
                                    scope.launch(Dispatchers.IO) {
                                        val error = AiServiceSync.createModel(
                                            ollamaUrl, modelName, baseModel,
                                            systemPrompt, temperature, contextSteps[ctxIndex]
                                        )
                                        withContext(Dispatchers.Main) {
                                            isCreating = false
                                            if (error == null) {
                                                statusMessage = if (isEditing) "Model '$modelName' updated" else "Model '$modelName' created"
                                                statusIsError = false
                                                resetForm()
                                                refreshModels()
                                            } else {
                                                statusMessage = "Error: $error"
                                                statusIsError = true
                                            }
                                        }
                                    }
                                },
                                enabled = modelName.isNotBlank() && baseModel.isNotBlank() && nameValid && !isCreating,
                                colors = brandButtonColors(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isCreating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Text(if (isEditing) "Update model" else "Create model")
                                }
                            }
                        }
                    }
                }

                // Status message
                if (statusMessage.isNotBlank()) {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsError) MaterialTheme.colorScheme.error else teal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Download section
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                    color = brandSurfaceVariant()
                )
                OutlinedButton(
                    onClick = { showDownloadSection = !showDownloadSection },
                    colors = brandOutlinedButtonColors(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Text(if (isPulling) "Downloading..." else "Manage models on your Ollama server (${baseModels.size})")
                }

                if (showDownloadSection || isPulling) {
                    // Installed base models
                    if (baseModels.isNotEmpty()) {
                        BrandCard {
                            Column {
                                Text(
                                    "Installed models",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = teal,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Text(
                                    "Base models on your Ollama server. Deleting a model frees disk space.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                for (model in baseModels) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            model,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { deleteBaseConfirmModel = model },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(width = 100.dp, height = 36.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            contentPadding = ButtonDefaults.ContentPadding
                                        ) {
                                            Text("Delete", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Download section
                    BrandCard {
                        Column {
                            Text(
                                "Download model",
                                style = MaterialTheme.typography.titleMedium,
                                color = teal,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                "Download a model to your Ollama server. Choose from the list or enter a custom model name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            OutlinedButton(
                                onClick = { showPullModelPicker = true },
                                enabled = !isPulling,
                                colors = brandOutlinedButtonColors(),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Browse models")
                            }

                            if (showPullModelPicker) {
                                val items = curatedModels.map { "${it.name} - ${it.size}" to it.name }
                                helium314.keyboard.settings.dialogs.ListPickerDialog(
                                    onDismissRequest = { showPullModelPicker = false },
                                    items = items,
                                    onItemSelected = { selected ->
                                        showPullModelPicker = false
                                        startPull(selected.second)
                                    },
                                    title = { Text("Choose a model to download") },
                                    getItemName = { it.first }
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = brandSurfaceVariant()
                            )
                            Text(
                                "Or enter an exact model name (e.g. llama3:8b, codellama:13b).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                onClick = { uriHandler.openUri("https://ollama.com/library") },
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text(
                                    "Browse all models at ollama.com/library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = teal
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = pullModelCustomName,
                                    onValueChange = { pullModelCustomName = it.lowercase() },
                                    placeholder = { Text("e.g. codellama:13b") },
                                    singleLine = true,
                                    enabled = !isPulling,
                                    colors = tealTextFieldColors,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { if (pullModelCustomName.isNotBlank()) startPull(pullModelCustomName) },
                                    enabled = pullModelCustomName.isNotBlank() && !isPulling,
                                    colors = brandButtonColors(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Pull")
                                }
                            }

                            if (isPulling) {
                                LinearProgressIndicator(
                                    progress = { pullProgress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    color = teal,
                                )
                                Text(
                                    pullStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Custom models list
                if (customModels.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                        color = brandSurfaceVariant()
                    )
                    OutlinedButton(
                        onClick = { showCustomModelsSection = !showCustomModelsSection },
                        colors = brandOutlinedButtonColors(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        Text("Manage custom models (${customModels.size})")
                    }
                }

                if (showCustomModelsSection && customModels.isNotEmpty()) {
                    for (model in customModels) {
                        val isProtected = model in protectedModels
                        BrandCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                val details = AiServiceSync.fetchModelDetails(ollamaUrl, model)
                                                withContext(Dispatchers.Main) {
                                                    if (details != null) {
                                                        modelName = model.substringBefore(":")
                                                        baseModel = details.parentModel.ifBlank { baseModels.firstOrNull() ?: "" }
                                                        systemPrompt = details.system
                                                        temperature = details.temperature
                                                        ctxIndex = contextSteps.indexOfFirst { it >= details.numCtx }.takeIf { it >= 0 } ?: 1
                                                        isEditing = true
                                                    }
                                                }
                                            }
                                        },
                                        colors = brandOutlinedButtonColors(),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(width = 90.dp, height = 36.dp),
                                        contentPadding = ButtonDefaults.ContentPadding
                                    ) {
                                        Text("Edit", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!isProtected) {
                                        OutlinedButton(
                                            onClick = { deleteConfirmModel = model },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(width = 100.dp, height = 36.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            contentPadding = ButtonDefaults.ContentPadding
                                        ) {
                                            Text("Delete", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // Column
            } // Scaffold
        }
    )

    // Delete base model confirmation dialog
    deleteBaseConfirmModel?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteBaseConfirmModel = null },
            title = { Text("Delete base model") },
            text = { Text("Are you sure you want to delete '$model'? This will free disk space on your Ollama server. You can re-download it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = model
                        deleteBaseConfirmModel = null
                        scope.launch(Dispatchers.IO) {
                            val ok = AiServiceSync.deleteModel(ollamaUrl, toDelete)
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    statusMessage = "Model '$toDelete' deleted"
                                    statusIsError = false
                                    refreshModels()
                                } else {
                                    statusMessage = "Error deleting model"
                                    statusIsError = true
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteBaseConfirmModel = null }) { Text("Cancel") }
            }
        )
    }

    // Delete custom model confirmation dialog
    deleteConfirmModel?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteConfirmModel = null },
            title = { Text("Delete model") },
            text = { Text("Are you sure you want to delete '$model'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = model
                        deleteConfirmModel = null
                        scope.launch(Dispatchers.IO) {
                            val ok = AiServiceSync.deleteModel(ollamaUrl, toDelete)
                            withContext(Dispatchers.Main) {
                                if (ok) {
                                    statusMessage = "Model '$toDelete' deleted"
                                    statusIsError = false
                                    refreshModels()
                                } else {
                                    statusMessage = "Error deleting model"
                                    statusIsError = true
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmModel = null }) { Text("Cancel") }
            }
        )
    }

    // Pull error dialog
    pullErrorDialog?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { pullErrorDialog = null },
            title = { Text("Download failed") },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = { pullErrorDialog = null }) { Text("OK") }
            }
        )
    }
}
