// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.BrandHeader
import helium314.keyboard.settings.screens.brandButtonColors
import helium314.keyboard.settings.screens.brandOutlinedButtonColors
import helium314.keyboard.settings.screens.brandTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProcessStage { CONFIG, RESULT }

class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cap incoming text to protect against a hostile caller passing a huge
        // string that would OOM the keyboard. 50 KB is far more than any
        // realistic user selection.
        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.take(50_000)
            ?: ""

        AiServiceSync.setContext(this)

        enableEdgeToEdge()
        setContent {
            Theme {
                ProcessTextScreen(
                    selectedText = selectedText,
                    onCopyAndFinish = { text ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Deskdrop", text))
                        Toast.makeText(this, getString(R.string.ai_result_copied), Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

}

@Composable
private fun ProcessTextScreen(
    selectedText: String,
    onCopyAndFinish: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(ProcessStage.CONFIG) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isCustom by remember { mutableStateOf(false) }
    var instruction by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    val models = remember { mutableStateListOf<ModelItem>() }
    var ollamaLoaded by remember { mutableStateOf(false) }

    fun applySelection(idx: Int) {
        selectedIndex = idx
        if (idx in models.indices && models[idx].isCustomPreset) {
            isCustom = true
            val p = models[idx].customPrompt.ifEmpty { context.getString(R.string.ai_custom_no_prompt) }
            instruction = context.getString(R.string.ai_custom_prompt_prefix) + p
        } else {
            isCustom = false
            instruction = ""
        }
    }

    fun resolveSavedModelSelection(savedModel: String) {
        val again = models.indexOfFirst { it.modelValue == savedModel }
        if (again >= 0 && (selectedIndex !in models.indices || models[selectedIndex].modelValue != savedModel)) {
            applySelection(again)
        } else if (selectedIndex !in models.indices && models.isNotEmpty()) {
            applySelection(0)
        }
    }

    fun refreshOllamaModels() {
        ollamaLoaded = false
        scope.launch(Dispatchers.IO) {
            val ollama = loadOllamaModels(prefs, context, forceRefresh = true)
            val openai = loadOpenAiCompatibleModels(prefs, forceRefresh = true)
            withContext(Dispatchers.Main) {
                // Remove existing local entries before adding fresh ones
                val nonLocal = models.filterNot {
                    it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:")
                }
                models.clear()
                models.addAll(nonLocal)
                models.addAll(ollama)
                models.addAll(openai)
                val savedModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
                resolveSavedModelSelection(savedModel)
                ollamaLoaded = true
            }
        }
    }

    LaunchedEffect(Unit) {
        models.clear()
        models.addAll(loadCloudPresets(prefs))
        models.addAll(loadCloudModels(prefs))
        // Use the cached local lists (if any) so the picker is populated instantly.
        models.addAll(cachedOllamaModels(prefs))
        models.addAll(cachedOpenAiCompatibleModels(prefs))
        val savedModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        val initialIdx = models.indexOfFirst { it.modelValue == savedModel }
        if (initialIdx >= 0) {
            applySelection(initialIdx)
        } else if (models.isNotEmpty()) {
            applySelection(0)
        }
        scope.launch(Dispatchers.IO) {
            val ollama = loadOllamaModels(prefs, context)
            val openai = loadOpenAiCompatibleModels(prefs)
            withContext(Dispatchers.Main) {
                // Replace any cached local entries with fresh data (could be the same).
                val nonLocal = models.filterNot {
                    it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:")
                }
                models.clear()
                models.addAll(nonLocal)
                models.addAll(ollama)
                models.addAll(openai)
                resolveSavedModelSelection(savedModel)
                ollamaLoaded = true
            }
        }
    }

    fun startPreview() {
        if (selectedIndex !in models.indices) return
        val model = models[selectedIndex]
        val finalInstruction = if (model.isCustomPreset) "" else instruction
        fun runStream() {
            AiStreamBridge.start()
            val handle = AiCancelRegistry.start(ToolbarKey.AI_CLIPBOARD)
            scope.launch(Dispatchers.IO) {
                try {
                    AiServiceSync.processWithModelAndInstructionStream(
                        text = selectedText,
                        aiModel = model.modelValue,
                        instruction = finalInstruction,
                        prefs = prefs,
                        onChunk = { chunk -> AiStreamBridge.append(chunk) },
                        onComplete = {
                            AiStreamBridge.complete()
                            AiCancelRegistry.clear(handle)
                        },
                        onError = { msg ->
                            if (AiCancelRegistry.isCancelled(handle)) {
                                AiStreamBridge.cancel()
                            } else {
                                AiStreamBridge.error(msg)
                            }
                            AiCancelRegistry.clear(handle)
                        },
                        cancelHandle = handle
                    )
                } catch (e: Exception) {
                    if (AiCancelRegistry.isCancelled(handle)) {
                        AiStreamBridge.cancel()
                    } else {
                        AiStreamBridge.error(e.message ?: "Unknown error")
                    }
                    AiCancelRegistry.clear(handle)
                }
            }
        }
        AiRetryRegistry.set(::runStream)
        stage = ProcessStage.RESULT
        runStream()
    }

    if (stage == ProcessStage.RESULT) {
        AiResultViewContent(
            initialText = "",
            streaming = true,
            onCancelStream = { AiCancelRegistry.cancel() },
            onClose = {
                AiRetryRegistry.clear()
                AiStreamBridge.reset()
                stage = ProcessStage.CONFIG
            },
            onNewPrompt = {
                AiRetryRegistry.clear()
                AiStreamBridge.reset()
                stage = ProcessStage.CONFIG
            },
            onInsert = { text ->
                AiRetryRegistry.clear()
                AiStreamBridge.reset()
                onCopyAndFinish(text)
            },
            primaryActionLabel = stringResource(R.string.ai_result_copy),
            primaryActionSelectionLabel = stringResource(R.string.ai_result_copy_selection)
        )
        return
    }

    // CONFIG stage
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0x80000000)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BrandHeader(stringResource(R.string.ai_clipboard_title))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SectionLabel(stringResource(R.string.process_text_selected))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            val sc = rememberScrollState()
                            Text(
                                text = selectedText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(sc)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        val modelName = if (selectedIndex in models.indices) models[selectedIndex].displayName else "..."
                        ModelPickerRow(
                            currentModelName = modelName,
                            isLoading = !ollamaLoaded,
                            onClick = {
                                if (!ollamaLoaded) {
                                    Toast.makeText(context, context.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                                    return@ModelPickerRow
                                }
                                if (models.isEmpty()) return@ModelPickerRow
                                showModelPicker = true
                            }
                        )

                        if (isCustom) {
                            CustomPresetHint()
                        }

                        Spacer(Modifier.height(8.dp))

                        SectionLabel(stringResource(R.string.process_text_instruction_hint))
                        OutlinedTextField(
                            value = instruction,
                            onValueChange = { if (!isCustom) instruction = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 120.dp),
                            readOnly = isCustom,
                            enabled = !isCustom,
                            singleLine = false,
                            maxLines = 4
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onDismiss() }) {
                                Text(
                                    stringResource(R.string.process_text_cancel),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { startPreview() },
                                enabled = selectedIndex in models.indices &&
                                    (isCustom || instruction.isNotBlank()),
                                colors = brandButtonColors(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.process_text_process))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showModelPicker = false }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BrandHeader(stringResource(R.string.ai_choose_model))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        for ((index, item) in models.withIndex()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        applySelection(index)
                                        showModelPicker = false
                                    }
                                    .padding(horizontal = 8.dp)
                                    .heightIn(min = 44.dp)
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = index == selectedIndex,
                                    onClick = {
                                        applySelection(index)
                                        showModelPicker = false
                                    },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = brandTeal()),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = item.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { refreshOllamaModels() },
                            enabled = ollamaLoaded
                        ) {
                            Text("Refresh", color = brandTeal())
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showModelPicker = false }) {
                            Text(
                                stringResource(R.string.process_text_cancel),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
