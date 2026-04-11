// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.app.AlertDialog
import android.content.SharedPreferences
import android.text.method.TextKeyListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.showImeComposeDialog
import helium314.keyboard.latin.utils.showImePickerDialog
import helium314.keyboard.settings.screens.BrandHeader
import helium314.keyboard.settings.screens.brandButtonColors
import helium314.keyboard.settings.screens.brandOutlinedButtonColors
import helium314.keyboard.settings.screens.brandTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ════════════════════════════════════════════════════════════════════
// Data models
// ════════════════════════════════════════════════════════════════════

data class ModelItem(
    val displayName: String,
    val modelValue: String,
    val isCustomPreset: Boolean = false,
    val customPrompt: String = ""
)

// ════════════════════════════════════════════════════════════════════
// Model list loading (replaces 3x duplicated Java code)
// ════════════════════════════════════════════════════════════════════

fun loadCloudPresets(prefs: SharedPreferences): List<ModelItem> {
    val items = mutableListOf<ModelItem>()
    try {
        val presetsJson = prefs.getString(Settings.PREF_AI_CLOUD_PRESETS, Defaults.PREF_AI_CLOUD_PRESETS)
        val arr = JSONArray(presetsJson)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            var name = obj.optString("name", "Preset ${i + 1}")
            val model = obj.optString("model", "")
            val prompt = obj.optString("prompt", "")
            if (name.isEmpty()) name = "Preset ${i + 1}"
            val modelShort = if (model.contains(":")) model.substringAfter(":") else model
            items.add(ModelItem(
                displayName = "$name ($modelShort)",
                modelValue = model,
                isCustomPreset = prompt.isNotEmpty(),
                customPrompt = prompt
            ))
        }
    } catch (_: Exception) {}
    return items
}

fun loadCloudModels(prefs: SharedPreferences): List<ModelItem> {
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "local") return emptyList()
    return AiServiceSync.CLOUD_MODELS
        .filter { AiServiceSync.hasApiKey(it.second) }
        .map { ModelItem(displayName = it.first, modelValue = it.second) }
}

private const val OLLAMA_MODELS_CACHE_KEY = "ollama_models_cache_v1"

internal fun writeOllamaCache(prefs: SharedPreferences, ollamaUrl: String, items: List<ModelItem>) {
    try {
        val arr = org.json.JSONArray()
        for (item in items) {
            arr.put(org.json.JSONObject().apply {
                put("displayName", item.displayName)
                put("modelValue", item.modelValue)
                put("isCustomPreset", item.isCustomPreset)
                put("customPrompt", item.customPrompt)
            })
        }
        val payload = org.json.JSONObject().apply {
            put("url", ollamaUrl)
            put("ts", System.currentTimeMillis())
            put("items", arr)
        }
        prefs.edit().putString(OLLAMA_MODELS_CACHE_KEY, payload.toString()).apply()
    } catch (_: Exception) { /* best-effort cache */ }
}

private fun readOllamaCache(prefs: SharedPreferences, vararg validUrls: String): List<ModelItem>? {
    return try {
        val raw = prefs.getString(OLLAMA_MODELS_CACHE_KEY, null) ?: return null
        val obj = org.json.JSONObject(raw)
        val cachedUrl = obj.optString("url")
        val accepted = validUrls.any { it.isNotBlank() && it == cachedUrl }
        if (!accepted) return null
        val arr = obj.optJSONArray("items") ?: return null
        val out = mutableListOf<ModelItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(
                ModelItem(
                    displayName = it.optString("displayName"),
                    modelValue = it.optString("modelValue"),
                    isCustomPreset = it.optBoolean("isCustomPreset", false),
                    customPrompt = it.optString("customPrompt", "")
                )
            )
        }
        out
    } catch (_: Exception) {
        null
    }
}

/**
 * Synchronously read the cached Ollama models for the current Ollama URL.
 * Returns empty list if no cache, URL changed, or filter excludes local models.
 */
fun cachedOllamaModels(prefs: SharedPreferences): List<ModelItem> {
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") return emptyList()
    // Read both primary + fallback directly from prefs (no network probe — must be instant
    // for UI thread / slot picker contexts).
    val primary = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL
    )
    val fallback = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OLLAMA_URL_FALLBACK, Defaults.PREF_OLLAMA_URL_FALLBACK) ?: ""
    )
    return readOllamaCache(prefs, primary, fallback) ?: emptyList()
}

// ---- OpenAI-compatible (LM Studio / vLLM / llama.cpp / ...) ----

private const val OPENAI_COMPAT_MODELS_CACHE_KEY = "openai_compat_models_cache_v1"

private fun writeOpenAiCompatCache(prefs: SharedPreferences, baseUrl: String, items: List<ModelItem>) {
    try {
        val arr = org.json.JSONArray()
        for (item in items) {
            arr.put(org.json.JSONObject().apply {
                put("displayName", item.displayName)
                put("modelValue", item.modelValue)
            })
        }
        val payload = org.json.JSONObject().apply {
            put("url", baseUrl)
            put("ts", System.currentTimeMillis())
            put("items", arr)
        }
        prefs.edit().putString(OPENAI_COMPAT_MODELS_CACHE_KEY, payload.toString()).apply()
    } catch (_: Exception) {}
}

private fun readOpenAiCompatCache(prefs: SharedPreferences, vararg validUrls: String): List<ModelItem>? {
    return try {
        val raw = prefs.getString(OPENAI_COMPAT_MODELS_CACHE_KEY, null) ?: return null
        val obj = org.json.JSONObject(raw)
        val cachedUrl = obj.optString("url")
        val accepted = validUrls.any { it.isNotBlank() && it == cachedUrl }
        if (!accepted) return null
        val arr = obj.optJSONArray("items") ?: return null
        val out = mutableListOf<ModelItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(
                ModelItem(
                    displayName = it.optString("displayName"),
                    modelValue = it.optString("modelValue")
                )
            )
        }
        out
    } catch (_: Exception) {
        null
    }
}

fun cachedOpenAiCompatibleModels(prefs: SharedPreferences): List<ModelItem> {
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") return emptyList()
    // Read both primary + fallback directly from prefs (no network probe).
    val primary = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, Defaults.PREF_OPENAI_COMPAT_URL) ?: ""
    )
    val fallback = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, Defaults.PREF_OPENAI_COMPAT_URL_FALLBACK) ?: ""
    )
    if (primary.isEmpty() && fallback.isEmpty()) return emptyList()
    return readOpenAiCompatCache(prefs, primary, fallback) ?: emptyList()
}

suspend fun loadOpenAiCompatibleModels(
    prefs: SharedPreferences,
    forceRefresh: Boolean = false
): List<ModelItem> = withContext(Dispatchers.IO) {
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") return@withContext emptyList()
    val primary = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, Defaults.PREF_OPENAI_COMPAT_URL) ?: ""
    )
    val fallback = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, Defaults.PREF_OPENAI_COMPAT_URL_FALLBACK) ?: ""
    )
    if (primary.isEmpty() && fallback.isEmpty()) return@withContext emptyList()
    if (!forceRefresh) {
        val cached = readOpenAiCompatCache(prefs, primary, fallback)
        if (cached != null) return@withContext cached
    }
    val baseUrl = AiServiceSync.resolveOpenAiCompatBaseUrl(prefs)
    if (baseUrl.isEmpty()) return@withContext readOpenAiCompatCache(prefs, primary, fallback) ?: emptyList()
    try {
        val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
        val models = AiServiceSync.fetchOpenAiCompatibleModels(baseUrl, apiKey)
        val items = models.map { model ->
            ModelItem(
                displayName = "$model (custom)",
                modelValue = "openai:$model"
            )
        }
        writeOpenAiCompatCache(prefs, baseUrl, items)
        items
    } catch (_: Exception) {
        readOpenAiCompatCache(prefs, primary, fallback) ?: emptyList()
    }
}

/**
 * Synchronously fetch OpenAI-compatible model list from prefs (used by Thread-based dialog loaders).
 * Writes the cache as a side effect so future entry points get an instant first paint.
 */
fun fetchOpenAiCompatModelsForPrefs(prefs: SharedPreferences): List<ModelItem> {
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") return emptyList()
    val primary = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, Defaults.PREF_OPENAI_COMPAT_URL) ?: ""
    )
    val fallback = AiServiceSync.normalizeOllamaUrl(
        prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, Defaults.PREF_OPENAI_COMPAT_URL_FALLBACK) ?: ""
    )
    val baseUrl = AiServiceSync.resolveOpenAiCompatBaseUrl(prefs)
    if (baseUrl.isEmpty()) return readOpenAiCompatCache(prefs, primary, fallback) ?: emptyList()
    return try {
        val apiKey = SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
        val models = AiServiceSync.fetchOpenAiCompatibleModels(baseUrl, apiKey)
        val items = models.map { model ->
            ModelItem(
                displayName = "$model (custom)",
                modelValue = "openai:$model"
            )
        }
        writeOpenAiCompatCache(prefs, baseUrl, items)
        items
    } catch (_: Exception) {
        readOpenAiCompatCache(prefs, primary, fallback) ?: emptyList()
    }
}

suspend fun loadOllamaModels(
    prefs: SharedPreferences,
    context: android.content.Context,
    forceRefresh: Boolean = false
): List<ModelItem> =
    withContext(Dispatchers.IO) {
        val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
        if (modelFilter == "cloud") return@withContext emptyList()
        val primary = AiServiceSync.normalizeOllamaUrl(
            prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL
        )
        val fallback = AiServiceSync.normalizeOllamaUrl(
            prefs.getString(Settings.PREF_OLLAMA_URL_FALLBACK, Defaults.PREF_OLLAMA_URL_FALLBACK) ?: ""
        )
        if (!forceRefresh) {
            val cached = readOllamaCache(prefs, primary, fallback)
            if (cached != null) return@withContext cached
        }
        val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
        try {
            val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
            val items = ollamaModels.map { model ->
                val suffix = context.getString(R.string.ai_model_suffix_ollama)
                val details = AiServiceSync.fetchModelDetails(ollamaUrl, model)
                val isCustom = details != null && details.parentModel.isNotBlank()
                val prompt = if (isCustom && details != null && details.system.isNotBlank()) details.system else ""
                ModelItem(
                    displayName = "$model$suffix",
                    modelValue = "ollama:$model",
                    isCustomPreset = isCustom,
                    customPrompt = prompt
                )
            }
            writeOllamaCache(prefs, ollamaUrl, items)
            items
        } catch (_: Exception) {
            // On failure, fall back to cache from either configured URL.
            readOllamaCache(prefs, primary, fallback) ?: emptyList()
        }
    }

// ════════════════════════════════════════════════════════════════════
// Shared composables
// ════════════════════════════════════════════════════════════════════

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = brandTeal(),
        modifier = modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun ModelPickerRow(
    currentModelName: String,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionLabel(stringResource(R.string.ai_model))
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = currentModelName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = brandTeal()
                    )
                } else {
                    Text(
                        text = "\u25BE",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun ImeInstructionField(
    editText: EditText,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 200.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        AndroidView(
            factory = { editText },
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
fun CustomPresetHint() {
    Text(
        text = stringResource(R.string.ai_custom_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * Shared instruction-editing block used by instruction, slot and clipboard
 * config dialogs. Renders: "Instruction" label + Clear button + the EditText
 * field + (when !isCustom) the AI Prompt generation section with its own
 * cancellable in-flight state.
 *
 * Extracted to eliminate copy-paste drift between AiInstructionContent and
 * AiSlotContent.
 */
@Composable
private fun InstructionBlock(
    ime: LatinIME,
    prefs: SharedPreferences,
    models: MutableList<ModelItem>,
    instrInput: EditText,
    isCustom: Boolean,
    ollamaLoaded: BooleanArray
) {
    var isGenerating by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionLabel(
            stringResource(R.string.ai_instruction),
            modifier = Modifier.weight(1f)
        )
        if (!isCustom) {
            TextButton(onClick = {
                instrInput.setText("")
                ime.setDialogCursorPos(0)
            }) {
                Text(
                    stringResource(R.string.ai_clear_text),
                    color = brandTeal(),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    ImeInstructionField(
        editText = instrInput,
        isReadOnly = isCustom
    )

    if (!isCustom) {
        Spacer(Modifier.height(4.dp))
        AiPromptSection(
            enabled = ollamaLoaded[0],
            isGenerating = isGenerating,
            onGenerate = {
                val description = instrInput.text.toString().trim()
                if (description.isEmpty()) return@AiPromptSection
                if (!ollamaLoaded[0]) {
                    Toast.makeText(ime, ime.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                    return@AiPromptSection
                }
                val names = models.map { it.displayName }.toTypedArray()
                showImePickerDialog(
                    ime = ime,
                    title = ime.getString(R.string.ai_choose_model),
                    items = names,
                    onItemSelected = { which ->
                        isGenerating = true
                        val chosenModel = models[which].modelValue
                        val handle = AiCancelRegistry.start(helium314.keyboard.latin.utils.ToolbarKey.AI_ASSIST)
                        Thread {
                            try {
                                val result = AiServiceSync.generateSystemPromptWithModel(
                                    description, chosenModel, prefs, handle
                                )
                                ime.mHandler.post {
                                    val cancelled = AiCancelRegistry.isCancelled(handle)
                                    AiCancelRegistry.clear(handle)
                                    isGenerating = false
                                    if (!cancelled) {
                                        instrInput.setText(result)
                                    }
                                }
                            } catch (e: Exception) {
                                ime.mHandler.post {
                                    val cancelled = AiCancelRegistry.isCancelled(handle)
                                    AiCancelRegistry.clear(handle)
                                    isGenerating = false
                                    if (!cancelled) {
                                        Toast.makeText(
                                            ime,
                                            String.format(ime.getString(R.string.ai_error_prefix), e.message),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }.start()
                    }
                )
            },
            onCancel = { AiCancelRegistry.cancel() }
        )
    }
}

@Composable
fun AiPromptSection(
    enabled: Boolean,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onCancel: () -> Unit
) {
    Column {
        if (isGenerating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = brandTeal()
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_generating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) {
                    Text(
                        stringResource(R.string.process_text_cancel),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        } else {
            TextButton(
                onClick = onGenerate,
                enabled = enabled
            ) {
                Text(
                    text = stringResource(R.string.ai_prompt_button),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            text = stringResource(R.string.ai_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

// ════════════════════════════════════════════════════════════════════
// Dialog implementations
// ════════════════════════════════════════════════════════════════════

/**
 * Creates the custom EditText that fakes focus for cursor visibility
 * while keeping FLAG_NOT_FOCUSABLE on the window.
 */
fun createImeEditText(
    context: android.content.Context,
    ime: LatinIME
): EditText {
    return object : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null
        override fun isFocused(): Boolean = true
        override fun hasWindowFocus(): Boolean = true
    }.apply {
        minLines = 2
        gravity = Gravity.TOP or Gravity.START
        textSize = 14f
        setTextColor(MaterialThemeCompat.textColor(context))
        setHintTextColor(MaterialThemeCompat.secondaryTextColor(context))
        isCursorVisible = true
        // Enable vertical scrolling within the EditText so long prompts are readable.
        // We handle scrolling manually because returning true from the touch
        // listener (needed to keep editing/saving working) prevents the EditText
        // from processing scroll gestures on its own.
        isVerticalScrollBarEnabled = true
        maxLines = 8
        var touchStartY = 0f
        var totalDrag = 0f
        setOnTouchListener { v, event ->
            val et = v as EditText
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    totalDrag = 0f
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = touchStartY - event.y
                    totalDrag += kotlin.math.abs(dy)
                    if (et.layout != null) {
                        val contentHeight = et.layout.height + et.paddingTop + et.paddingBottom
                        val viewHeight = et.height
                        if (contentHeight > viewHeight) {
                            val maxScroll = contentHeight - viewHeight
                            val newScroll = (et.scrollY + dy.toInt()).coerceIn(0, maxScroll)
                            et.scrollTo(0, newScroll)
                        }
                    }
                    touchStartY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    // Only set cursor on tap, not after a drag/scroll gesture.
                    if (totalDrag < 20f) {
                        val offset = et.getOffsetForPosition(event.x, event.y)
                        if (offset >= 0) {
                            ime.setDialogCursorPos(offset)
                        }
                    }
                }
            }
            true
        }
    }
}

/**
 * Helper to get theme-aware colors for EditText (since it lives outside Compose).
 */
object MaterialThemeCompat {
    fun textColor(context: android.content.Context): Int {
        val nightMode = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (nightMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()
    }

    fun secondaryTextColor(context: android.content.Context): Int {
        val nightMode = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (nightMode) 0xFFBBBBBB.toInt() else 0xFF757575.toInt()
    }
}

// ════════════════════════════════════════════════════════════════════
// showAiVoiceModeDialog
// ════════════════════════════════════════════════════════════════════

fun showAiVoiceModeDialog(ime: LatinIME) {
    try {
        val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
        val builtinNames = Defaults.AI_VOICE_MODE_NAMES
        val savedMode = prefs.getInt(Settings.PREF_AI_VOICE_MODE, Defaults.PREF_AI_VOICE_MODE)
        val builtinCount = builtinNames.size

        // Load custom mode names
        val customNames = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]"))
            for (i in 0 until arr.length()) {
                customNames.add(arr.getJSONObject(i).getString("name"))
            }
        } catch (_: Exception) {}

        val currentVoiceModel = prefs.getString(Settings.PREF_AI_VOICE_MODEL, "") ?: ""
        val currentEngine = prefs.getString(Settings.PREF_AI_VOICE_ENGINE, Defaults.PREF_AI_VOICE_ENGINE) ?: Defaults.PREF_AI_VOICE_ENGINE
        val clipContent = ime.clipboardHistoryManager.retrieveClipboardContent()
        val hasClipboard = clipContent != null && clipContent.isNotEmpty()

        // Build model list: "Default" + cloud models + cached local (refreshed by bg thread)
        val models = mutableListOf<ModelItem>()
        models.add(ModelItem(displayName = ime.getString(R.string.ai_voice_model_default), modelValue = ""))
        models.addAll(loadCloudModels(prefs))
        val cachedLocal = cachedOllamaModels(prefs) + cachedOpenAiCompatibleModels(prefs)
        models.addAll(cachedLocal)

        val ollamaLoaded = booleanArrayOf(cachedLocal.isNotEmpty())

        val validSavedMode = if (savedMode < builtinCount + customNames.size) savedMode else 0
        val dialogHolder = arrayOfNulls<AlertDialog>(1)

        val dialog = showImeComposeDialog(
            ime = ime,
            chromeless = true,
            content = {
                AiVoiceModeContent(
                    ime = ime,
                    prefs = prefs,
                    builtinNames = builtinNames,
                    customNames = customNames,
                    initialMode = validSavedMode,
                    initialModel = currentVoiceModel,
                    initialEngine = currentEngine,
                    hasClipboard = hasClipboard,
                    clipContent = clipContent?.toString() ?: "",
                    models = models,
                    ollamaLoaded = ollamaLoaded,
                    onReplyCardClick = { model ->
                        prefs.edit().putString(Settings.PREF_AI_VOICE_MODEL, model).apply()
                        prefs.edit().putBoolean("ai_voice_reply_mode", true).apply()
                        dialogHolder[0]?.dismiss()
                        ime.startAiVoiceRecognition()
                    },
                    onSave = { model, engine, mode ->
                        prefs.edit().putString(Settings.PREF_AI_VOICE_MODEL, model).apply()
                        prefs.edit().putString(Settings.PREF_AI_VOICE_ENGINE, engine).apply()
                        prefs.edit().putInt(Settings.PREF_AI_VOICE_MODE, mode).apply()
                        val modeName = if (mode < builtinCount) builtinNames[mode]
                        else customNames.getOrElse(mode - builtinCount) { "?" }
                        Toast.makeText(ime, String.format(ime.getString(R.string.ai_voice_saved), modeName), Toast.LENGTH_SHORT).show()
                        dialogHolder[0]?.dismiss()
                    },
                    onCancel = { dialogHolder[0]?.dismiss() }
                )
            }
        )
        dialogHolder[0] = dialog

        // Fetch Ollama models in background
        val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
        if (modelFilter == "cloud") {
            ollamaLoaded[0] = true
        }
        Thread {
            if (modelFilter == "cloud") return@Thread
            val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
            val ollamaItems = mutableListOf<ModelItem>()
            try {
                val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
                for (model in ollamaModels) {
                    ollamaItems.add(ModelItem(
                        displayName = "$model${ime.getString(R.string.ai_model_suffix_ollama)}",
                        modelValue = "ollama:$model"
                    ))
                }
            } catch (_: Exception) {}
            val openaiItems = fetchOpenAiCompatModelsForPrefs(prefs)
            ime.mHandler.post {
                models.removeAll { it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:") }
                models.addAll(ollamaItems)
                models.addAll(openaiItems)
                ollamaLoaded[0] = true
            }
        }.start()
    } catch (e: Exception) {
        android.util.Log.e("AiDialogs", "showAiVoiceModeDialog error", e)
        Toast.makeText(
            ime,
            String.format(ime.getString(R.string.ai_error_prefix), e.message ?: ime.getString(R.string.ai_voice_error)),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun AiVoiceModeContent(
    ime: LatinIME,
    prefs: SharedPreferences,
    builtinNames: Array<String>,
    customNames: List<String>,
    initialMode: Int,
    initialModel: String,
    initialEngine: String,
    hasClipboard: Boolean,
    clipContent: String,
    models: MutableList<ModelItem>,
    ollamaLoaded: BooleanArray,
    onReplyCardClick: (model: String) -> Unit,
    onSave: (model: String, engine: String, mode: Int) -> Unit,
    onCancel: () -> Unit
) {
    var selectedModel by remember { mutableStateOf(initialModel) }
    var selectedEngine by remember { mutableStateOf(initialEngine) }
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0]) }
    val builtinCount = builtinNames.size

    // Poll for Ollama loaded state
    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0]
        }
        isOllamaLoading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Branded title header
        BrandHeader(stringResource(R.string.ai_voice_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
        // Model picker
        val modelDisplayName = if (selectedModel.isEmpty()) {
            stringResource(R.string.ai_voice_model_default)
        } else {
            formatModelDisplayName(selectedModel)
        }
        ModelPickerRow(
            currentModelName = modelDisplayName,
            isLoading = isOllamaLoading,
            onClick = {
                if (!ollamaLoaded[0]) {
                    Toast.makeText(ime, ime.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                    return@ModelPickerRow
                }
                val names = models.map { it.displayName }.toTypedArray()
                val currentIdx = models.indexOfFirst { it.modelValue == selectedModel }.coerceAtLeast(0)
                showImePickerDialog(
                    ime = ime,
                    title = ime.getString(R.string.ai_model),
                    items = names,
                    selectedIndex = currentIdx,
                    onItemSelected = { which ->
                        selectedModel = models[which].modelValue
                    }
                )
            }
        )

        Spacer(Modifier.height(8.dp))

        // Reply to clipboard card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (hasClipboard) brandTeal().copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (hasClipboard) BorderStroke(1.dp, brandTeal().copy(alpha = 0.5f)) else null,
            onClick = {
                if (hasClipboard) {
                    onReplyCardClick(selectedModel)
                }
            },
            enabled = hasClipboard
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "\uD83D\uDCCB  " + stringResource(R.string.ai_voice_reply_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (hasClipboard) brandTeal()
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (hasClipboard)
                            stringResource(R.string.ai_voice_reply_description)
                        else stringResource(R.string.ai_voice_no_clipboard),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (hasClipboard && clipContent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val preview = if (clipContent.length > 80) clipContent.substring(0, 80) + "\u2026" else clipContent
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            text = "\uD83D\uDCCB ${if (expanded) clipContent else preview}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
                }
                if (hasClipboard) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "\u25B6",
                        color = brandTeal(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // Speech engine picker
        SectionLabel(stringResource(R.string.ai_voice_speech_engine))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { selectedEngine = "google" }
            ) {
                RadioButton(
                    selected = selectedEngine == "google",
                    onClick = { selectedEngine = "google" },
                    colors = RadioButtonDefaults.colors(selectedColor = brandTeal())
                )
                Text("Google", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { selectedEngine = "whisper" }
            ) {
                RadioButton(
                    selected = selectedEngine == "whisper",
                    onClick = { selectedEngine = "whisper" },
                    colors = RadioButtonDefaults.colors(selectedColor = brandTeal())
                )
                Text(stringResource(R.string.ai_voice_engine_whisper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // Mode radio buttons
        SectionLabel(stringResource(R.string.ai_voice_mode_label))
        Column {
            // Built-in modes
            builtinNames.forEachIndexed { i, name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = i }
                        .heightIn(min = 36.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == i,
                        onClick = { selectedMode = i },
                        colors = RadioButtonDefaults.colors(selectedColor = brandTeal()),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            // Custom modes
            customNames.forEachIndexed { i, name ->
                val idx = builtinCount + i
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = idx }
                        .heightIn(min = 36.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == idx,
                        onClick = { selectedMode = idx },
                        colors = RadioButtonDefaults.colors(selectedColor = brandTeal()),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        } // end scrollable content Column

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(R.string.process_text_cancel),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSave(selectedModel, selectedEngine, selectedMode) },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    } // end outer Column

}

private fun formatModelDisplayName(modelId: String): String {
    for (m in AiServiceSync.CLOUD_MODELS) {
        if (m.second == modelId) return m.first
    }
    if (modelId.startsWith("ollama:")) {
        return modelId.substring(7) + " (Ollama)"
    }
    return modelId
}

// ════════════════════════════════════════════════════════════════════
// showAiInstructionDialog
// ════════════════════════════════════════════════════════════════════

fun showAiInstructionDialog(ime: LatinIME) {
    val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
    val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
    val currentInstruction = prefs.getString(Settings.PREF_AI_INSTRUCTION, Defaults.PREF_AI_INSTRUCTION) ?: Defaults.PREF_AI_INSTRUCTION
    val dialogContext = helium314.keyboard.latin.utils.getPlatformDialogThemeContext(ime)

    val instrInput = createImeEditText(dialogContext, ime)

    // State holders accessed by both Compose and dialog buttons
    val models = mutableListOf<ModelItem>()
    models.addAll(loadCloudPresets(prefs))
    models.addAll(loadCloudModels(prefs))

    val currentModelName = prefs.getString(Settings.PREF_AI_MODEL + "_name", "") ?: ""
    var currentSel = 0
    // First: try exact match on both modelValue + displayName
    if (currentModelName.isNotEmpty()) {
        for (i in models.indices) {
            if (models[i].modelValue == currentModel && models[i].displayName == currentModelName) {
                currentSel = i; break
            }
        }
    } else {
        for (i in models.indices) {
            if (models[i].modelValue == currentModel) { currentSel = i; break }
        }
    }
    if (currentModel.startsWith("ollama:")) {
        // Will be updated when Ollama models load
    }

    val selectionHolder = intArrayOf(currentSel)
    val ollamaLoaded = booleanArrayOf(false)

    // Set initial instruction
    if (currentSel in models.indices && models[currentSel].isCustomPreset) {
        val p = models[currentSel].customPrompt
        val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
        instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
        instrInput.keyListener = null
        instrInput.isFocusable = false
        instrInput.alpha = 0.5f
    } else {
        instrInput.setText(currentInstruction)
    }

    val dialogHolder = arrayOfNulls<AlertDialog>(1)

    val dialog = showImeComposeDialog(
        ime = ime,
        chromeless = true,
        onDismiss = { ime.setDialogEditText(null) },
        content = {
            AiInstructionContent(
                ime = ime,
                prefs = prefs,
                models = models,
                initialSelection = currentSel,
                instrInput = instrInput,
                ollamaLoaded = ollamaLoaded,
                selectionHolder = selectionHolder,
                needsOllamaWait = (currentModel.startsWith("ollama:") || currentModel.startsWith("openai:")) && currentSel <= 0,
                onSave = { s ->
                    val editor = prefs.edit()
                    if (s in models.indices) {
                        editor.putString(Settings.PREF_AI_MODEL, models[s].modelValue)
                        editor.putString(Settings.PREF_AI_MODEL + "_name", models[s].displayName)
                    }
                    if (s in models.indices && !models[s].isCustomPreset) {
                        editor.putString(Settings.PREF_AI_INSTRUCTION, instrInput.text.toString())
                    }
                    editor.commit()
                    dialogHolder[0]?.dismiss()
                },
                onCancel = { dialogHolder[0]?.dismiss() }
            )
        }
    )
    dialogHolder[0] = dialog

    ime.setDialogEditText(instrInput)

    // Fetch Ollama models in background
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") {
        ollamaLoaded[0] = true
    }
    fun applyOllamaListToClipboardDialog(ollamaItems: List<ModelItem>) {
        // Replace any existing local entries in models with the new list
        val toRemove = models.filter {
            it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:")
        }
        models.removeAll(toRemove)
        models.addAll(ollamaItems)
        // Update selection if current model is Ollama
        if (currentModel.startsWith("ollama:") || currentModel.startsWith("openai:")) {
            for (i in models.indices) {
                if (models[i].modelValue == currentModel) {
                    selectionHolder[0] = i
                    if (models[i].isCustomPreset) {
                        val p = models[i].customPrompt
                        val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
                        instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
                        instrInput.keyListener = null
                        instrInput.isFocusable = false
                        instrInput.alpha = 0.5f
                    }
                    break
                }
            }
        }
    }

    // Show cached local list immediately so the picker is populated instantly
    if (modelFilter != "cloud") {
        val cached = cachedOllamaModels(prefs) + cachedOpenAiCompatibleModels(prefs)
        if (cached.isNotEmpty()) {
            applyOllamaListToClipboardDialog(cached)
            ollamaLoaded[0] = true
        }
    }

    Thread {
        if (modelFilter == "cloud") return@Thread
        val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
        val ollamaItems = mutableListOf<ModelItem>()
        try {
            val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
            for (model in ollamaModels) {
                val suffix = ime.getString(R.string.ai_model_suffix_ollama)
                val details = AiServiceSync.fetchModelDetails(ollamaUrl, model)
                val isCustom = details != null && details.parentModel.isNotBlank()
                val prompt = if (isCustom && details != null && details.system.isNotBlank()) details.system else ""
                ollamaItems.add(ModelItem(
                    displayName = "$model$suffix",
                    modelValue = "ollama:$model",
                    isCustomPreset = isCustom,
                    customPrompt = prompt
                ))
            }
            // Update cache so other entry points (ProcessTextActivity, etc.) get an instant first paint
            writeOllamaCache(prefs, ollamaUrl, ollamaItems)
        } catch (_: Exception) {}
        val openaiItems = fetchOpenAiCompatModelsForPrefs(prefs)
        ime.mHandler.post {
            applyOllamaListToClipboardDialog(ollamaItems + openaiItems)
            ollamaLoaded[0] = true
        }
    }.start()
}

@Composable
private fun AiInstructionContent(
    ime: LatinIME,
    prefs: SharedPreferences,
    models: MutableList<ModelItem>,
    initialSelection: Int,
    instrInput: EditText,
    ollamaLoaded: BooleanArray,
    selectionHolder: IntArray,
    needsOllamaWait: Boolean,
    onSave: (selectedIndex: Int) -> Unit,
    onCancel: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(initialSelection) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0] && needsOllamaWait) }
    var isCustom by remember {
        mutableStateOf(initialSelection in models.indices && models[initialSelection].isCustomPreset)
    }
    var userHasChanged by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0] && needsOllamaWait
        }
        isOllamaLoading = false
        if (!userHasChanged && selectionHolder[0] != selectedIndex) {
            selectedIndex = selectionHolder[0]
            isCustom = selectedIndex in models.indices && models[selectedIndex].isCustomPreset
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BrandHeader(stringResource(R.string.ai_settings_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val modelName = if (selectedIndex in models.indices) models[selectedIndex].displayName else "..."
            ModelPickerRow(
                currentModelName = modelName,
                isLoading = isOllamaLoading,
                onClick = {
                    if (!ollamaLoaded[0]) {
                        Toast.makeText(ime, ime.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                        return@ModelPickerRow
                    }
                    val names = models.map { it.displayName }.toTypedArray()
                    showImePickerDialog(
                        ime = ime,
                        title = ime.getString(R.string.ai_choose_model),
                        items = names,
                        selectedIndex = selectedIndex,
                        onItemSelected = { which ->
                            selectedIndex = which
                            selectionHolder[0] = which
                            userHasChanged = true
                            val custom = which in models.indices && models[which].isCustomPreset
                            isCustom = custom
                            if (custom) {
                                val p = models[which].customPrompt
                                val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
                                instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
                                instrInput.keyListener = null
                                instrInput.isFocusable = false
                                instrInput.alpha = 0.5f
                            } else {
                                instrInput.setText(prefs.getString(Settings.PREF_AI_INSTRUCTION, Defaults.PREF_AI_INSTRUCTION))
                                instrInput.keyListener = TextKeyListener.getInstance()
                                instrInput.isFocusableInTouchMode = true
                                instrInput.alpha = 1f
                            }
                        }
                    )
                }
            )

            if (isCustom) {
                CustomPresetHint()
            }

            Spacer(Modifier.height(8.dp))

            InstructionBlock(
                ime = ime,
                prefs = prefs,
                models = models,
                instrInput = instrInput,
                isCustom = isCustom,
                ollamaLoaded = ollamaLoaded
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(R.string.process_text_cancel),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSave(selectedIndex) },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// showSlotConfigDialog
// ════════════════════════════════════════════════════════════════════

fun showSlotConfigDialog(ime: LatinIME, slotNumber: Int) {
    val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
    val modelPrefKey = "ai_slot_${slotNumber}_model"
    val instrPrefKey = "ai_slot_${slotNumber}_instruction"
    val currentModel = prefs.getString(modelPrefKey, "") ?: ""
    val currentInstruction = prefs.getString(instrPrefKey, "") ?: ""
    val dialogContext = helium314.keyboard.latin.utils.getPlatformDialogThemeContext(ime)

    val instrInput = createImeEditText(dialogContext, ime)

    val models = mutableListOf<ModelItem>()
    val presets = loadCloudPresets(prefs)
    models.addAll(presets)
    models.addAll(loadCloudModels(prefs))
    // Pre-populate from cache so the picker is instant; the background Thread below
    // refreshes from the live server and replaces these entries.
    val cachedLocal = cachedOllamaModels(prefs) + cachedOpenAiCompatibleModels(prefs)
    models.addAll(cachedLocal)
    val presetCount = presets.size

    val namePrefKey = "${modelPrefKey}_name"
    val currentModelName = prefs.getString(namePrefKey, "") ?: ""
    var initialSel = -1
    if (currentModel.isNotEmpty()) {
        // First: try exact match on both modelValue + displayName (disambiguates presets sharing a model)
        if (currentModelName.isNotEmpty()) {
            for (i in models.indices) {
                if (models[i].modelValue == currentModel && models[i].displayName == currentModelName) {
                    initialSel = i; break
                }
            }
        }
        // Fallback: match on modelValue only (backward compat)
        if (initialSel < 0) {
            for (i in models.indices) {
                if (models[i].modelValue == currentModel) { initialSel = i; break }
            }
        }
    }

    // Set initial instruction
    if (initialSel >= 0 && initialSel < models.size && models[initialSel].isCustomPreset) {
        val p = models[initialSel].customPrompt
        val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
        instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
        instrInput.keyListener = null
        instrInput.isFocusable = false
        instrInput.alpha = 0.5f
    } else if (initialSel < 0) {
        // No model match: clear any leaked preset instruction
        val isPresetLeak = presets.any { it.customPrompt == currentInstruction && it.customPrompt.isNotEmpty() }
        instrInput.setText(if (isPresetLeak || currentModel.isEmpty()) "" else currentInstruction)
    } else {
        instrInput.setText(currentInstruction)
    }

    val selectionHolder = intArrayOf(initialSel)
    val ollamaLoaded = booleanArrayOf(cachedLocal.isNotEmpty())
    val dialogHolder = arrayOfNulls<AlertDialog>(1)

    val dialog = showImeComposeDialog(
        ime = ime,
        chromeless = true,
        onDismiss = { ime.setDialogEditText(null) },
        content = {
            AiSlotContent(
                ime = ime,
                prefs = prefs,
                slotNumber = slotNumber,
                models = models,
                presetCount = presetCount,
                initialSelection = initialSel,
                instrInput = instrInput,
                ollamaLoaded = ollamaLoaded,
                selectionHolder = selectionHolder,
                needsOllamaWait = (currentModel.startsWith("ollama:") || currentModel.startsWith("openai:")) && initialSel < 0,
                modelPrefKey = modelPrefKey,
                instrPrefKey = instrPrefKey,
                onSave = { s ->
                    val editor = prefs.edit()
                    if (s >= 0 && s < models.size) {
                        editor.putString(modelPrefKey, models[s].modelValue)
                        editor.putString(namePrefKey, models[s].displayName)
                    } else if (s == -1) {
                        editor.putString(modelPrefKey, "")
                        editor.putString(namePrefKey, "")
                    }
                    if (s >= 0 && s < models.size && models[s].isCustomPreset) {
                        editor.putString(instrPrefKey, models[s].customPrompt)
                    } else {
                        editor.putString(instrPrefKey, instrInput.text.toString())
                    }
                    editor.commit()
                    dialogHolder[0]?.dismiss()
                },
                onCancel = { dialogHolder[0]?.dismiss() }
            )
        }
    )
    dialogHolder[0] = dialog

    ime.setDialogEditText(instrInput)

    // Fetch Ollama models
    val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
    if (modelFilter == "cloud") {
        ollamaLoaded[0] = true
    }
    Thread {
        if (modelFilter == "cloud") return@Thread
        val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
        val ollamaItems = mutableListOf<ModelItem>()
        try {
            val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
            for (model in ollamaModels) {
                val suffix = ime.getString(R.string.ai_model_suffix_ollama)
                val details = AiServiceSync.fetchModelDetails(ollamaUrl, model)
                val isCustom = details != null && details.parentModel.isNotBlank()
                val prompt = if (isCustom && details != null && details.system.isNotBlank()) details.system else ""
                ollamaItems.add(ModelItem(
                    displayName = "$model$suffix",
                    modelValue = "ollama:$model",
                    isCustomPreset = isCustom,
                    customPrompt = prompt
                ))
            }
        } catch (_: Exception) {}
        val openaiItems = fetchOpenAiCompatModelsForPrefs(prefs)
        ime.mHandler.post {
            // Replace any cached local entries with the freshly fetched ones.
            models.removeAll { it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:") }
            models.addAll(ollamaItems)
            models.addAll(openaiItems)
            if (currentModel.startsWith("ollama:") || currentModel.startsWith("openai:")) {
                for (i in models.indices) {
                    if (models[i].modelValue == currentModel) {
                        selectionHolder[0] = i
                        if (models[i].isCustomPreset) {
                            val p = models[i].customPrompt
                            val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
                            instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
                            instrInput.keyListener = null
                            instrInput.isFocusable = false
                            instrInput.alpha = 0.5f
                        }
                        break
                    }
                }
            }
            ollamaLoaded[0] = true
        }
    }.start()
}

@Composable
private fun AiSlotContent(
    ime: LatinIME,
    prefs: SharedPreferences,
    slotNumber: Int,
    models: MutableList<ModelItem>,
    presetCount: Int,
    initialSelection: Int,
    instrInput: EditText,
    ollamaLoaded: BooleanArray,
    selectionHolder: IntArray,
    needsOllamaWait: Boolean,
    modelPrefKey: String,
    instrPrefKey: String,
    onSave: (selectedIndex: Int) -> Unit,
    onCancel: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(initialSelection) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0] && needsOllamaWait) }
    var isCustom by remember {
        mutableStateOf(initialSelection >= 0 && initialSelection < models.size && models[initialSelection].isCustomPreset)
    }
    var userHasChanged by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0] && needsOllamaWait
        }
        isOllamaLoading = false
        if (!userHasChanged && selectionHolder[0] != selectedIndex) {
            selectedIndex = selectionHolder[0]
            isCustom = selectedIndex >= 0 && selectedIndex < models.size && models[selectedIndex].isCustomPreset
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BrandHeader(String.format(stringResource(R.string.ai_slot_title), slotNumber))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val modelName = when {
                selectedIndex < 0 -> ime.getString(R.string.ai_slot_none)
                selectedIndex in models.indices -> models[selectedIndex].displayName
                else -> "..."
            }
            ModelPickerRow(
                currentModelName = modelName,
                isLoading = isOllamaLoading,
                onClick = {
                    if (!ollamaLoaded[0]) {
                        Toast.makeText(ime, ime.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                        return@ModelPickerRow
                    }
                    val pickerNames = mutableListOf(ime.getString(R.string.ai_slot_none))
                    pickerNames.addAll(models.map { it.displayName })
                    showImePickerDialog(
                        ime = ime,
                        title = ime.getString(R.string.ai_choose_model),
                        items = pickerNames.toTypedArray(),
                        selectedIndex = if (selectedIndex < 0) 0 else selectedIndex + 1,
                        onItemSelected = { which ->
                            userHasChanged = true
                            if (which == 0) {
                                selectedIndex = -1
                                selectionHolder[0] = -1
                                isCustom = false
                                instrInput.setText("")
                                instrInput.keyListener = TextKeyListener.getInstance()
                                instrInput.isFocusableInTouchMode = true
                                instrInput.alpha = 1f
                            } else {
                                val modelIdx = which - 1
                                selectedIndex = modelIdx
                                selectionHolder[0] = modelIdx
                                val custom = modelIdx in models.indices && models[modelIdx].isCustomPreset
                                isCustom = custom
                                if (custom) {
                                    val p = models[modelIdx].customPrompt
                                    val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
                                    instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
                                    instrInput.keyListener = null
                                    instrInput.isFocusable = false
                                    instrInput.alpha = 0.5f
                                } else {
                                    instrInput.setText("")
                                    instrInput.keyListener = TextKeyListener.getInstance()
                                    instrInput.isFocusableInTouchMode = true
                                    instrInput.alpha = 1f
                                }
                            }
                        }
                    )
                }
            )

            if (isCustom) {
                CustomPresetHint()
            }

            Spacer(Modifier.height(8.dp))

            InstructionBlock(
                ime = ime,
                prefs = prefs,
                models = models,
                instrInput = instrInput,
                isCustom = isCustom,
                ollamaLoaded = ollamaLoaded
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    stringResource(R.string.process_text_cancel),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSave(selectedIndex) },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// showAiClipboardDialog
// ════════════════════════════════════════════════════════════════════

fun showAiClipboardDialog(ime: LatinIME) {
    val clipboard = ime.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
        Toast.makeText(ime, ime.getString(R.string.ai_clipboard_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val clip = clipboard.primaryClip
    if (clip == null || clip.itemCount == 0) {
        Toast.makeText(ime, ime.getString(R.string.ai_clipboard_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val clipText = clip.getItemAt(0).text
    if (clipText.isNullOrEmpty()) {
        Toast.makeText(ime, ime.getString(R.string.ai_clipboard_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val clipboardContent = clipText.toString()

    val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
    val dialogContext = helium314.keyboard.latin.utils.getPlatformDialogThemeContext(ime)

    val instrInput = createImeEditText(dialogContext, ime).apply {
        hint = ime.getString(R.string.process_text_instruction_hint)
    }

    // Model lists
    val models = mutableListOf<ModelItem>()
    val presets = loadCloudPresets(prefs)
    models.addAll(presets)
    models.addAll(loadCloudModels(prefs))
    val cachedLocal = cachedOllamaModels(prefs) + cachedOpenAiCompatibleModels(prefs)
    models.addAll(cachedLocal)

    val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
    var initialSel = 0
    for (i in models.indices) {
        if (models[i].modelValue == currentModel) { initialSel = i; break }
    }

    // Pre-fill instruction. If the selected model is a custom preset, lock the
    // field (matching instruction/slot dialog behavior).
    if (initialSel in models.indices && models[initialSel].isCustomPreset) {
        val p = models[initialSel].customPrompt
        val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
        instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
        instrInput.keyListener = null
        instrInput.isFocusable = false
        instrInput.alpha = 0.5f
    }

    val selectionHolder = intArrayOf(initialSel)
    val ollamaLoaded = booleanArrayOf(cachedLocal.isNotEmpty())
    val dialogHolder = arrayOfNulls<AlertDialog>(1)

    val dialog = showImeComposeDialog(
        ime = ime,
        chromeless = true,
        onDismiss = { ime.setDialogEditText(null) },
        content = {
            AiClipboardContent(
                ime = ime,
                prefs = prefs,
                clipboardContent = clipboardContent,
                models = models,
                initialSelection = initialSel,
                instrInput = instrInput,
                ollamaLoaded = ollamaLoaded,
                selectionHolder = selectionHolder,
                dismissDialog = { dialogHolder[0]?.dismiss() }
            )
        }
    )
    dialogHolder[0] = dialog

    ime.setDialogEditText(instrInput)

    // Fetch local models (Ollama + OpenAI-compatible) async
    Thread {
        val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
        if (modelFilter == "cloud") {
            ime.mHandler.post { ollamaLoaded[0] = true }
            return@Thread
        }
        val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
        val ollamaItems = mutableListOf<ModelItem>()
        try {
            val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
            for (m in ollamaModels) {
                ollamaItems.add(ModelItem(
                    displayName = "$m (Ollama)",
                    modelValue = "ollama:$m"
                ))
            }
        } catch (_: Exception) {}
        val openaiItems = fetchOpenAiCompatModelsForPrefs(prefs)
        ime.mHandler.post {
            models.removeAll { it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:") }
            models.addAll(ollamaItems)
            models.addAll(openaiItems)
            for (i in models.indices) {
                if (models[i].modelValue == currentModel) {
                    selectionHolder[0] = i
                    break
                }
            }
            ollamaLoaded[0] = true
        }
    }.start()
}

@Composable
private fun AiClipboardContent(
    ime: LatinIME,
    prefs: SharedPreferences,
    clipboardContent: String,
    models: MutableList<ModelItem>,
    initialSelection: Int,
    instrInput: EditText,
    ollamaLoaded: BooleanArray,
    selectionHolder: IntArray,
    dismissDialog: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(initialSelection) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0]) }
    var isProcessing by remember { mutableStateOf(false) }
    var isCustom by remember {
        mutableStateOf(initialSelection in models.indices && models[initialSelection].isCustomPreset)
    }

    fun startAiCall(directSend: Boolean) {
        val instruction = instrInput.text.toString().trim()
        if (instruction.isEmpty()) {
            Toast.makeText(ime, ime.getString(R.string.ai_clipboard_enter_instruction), Toast.LENGTH_SHORT).show()
            return
        }
        if (models.isEmpty() || selectedIndex !in models.indices) {
            Toast.makeText(ime, ime.getString(R.string.ai_clipboard_no_models), Toast.LENGTH_SHORT).show()
            return
        }
        val model = models[selectedIndex].modelValue

        if (!directSend) {
            // Streaming preview path: open ResultViewActivity immediately, stream tokens into bridge
            val text = clipboardContent
            val key = helium314.keyboard.latin.utils.ToolbarKey.AI_CLIPBOARD
            fun runStream() {
                AiStreamBridge.start()
                ime.setAiProcessingForKey(true, key)
                val handle = AiCancelRegistry.start(key)
                Thread {
                    try {
                        AiServiceSync.processWithModelAndInstructionStream(
                            text, model, instruction, prefs,
                            onChunk = { chunk -> ime.mHandler.post { AiStreamBridge.append(chunk) } },
                            onComplete = { ime.mHandler.post {
                                AiStreamBridge.complete()
                                AiCancelRegistry.clear(handle)
                                ime.setAiProcessingForKey(false, key)
                            } },
                            onError = { msg -> ime.mHandler.post {
                                if (AiCancelRegistry.isCancelled(handle)) {
                                    AiStreamBridge.cancel()
                                } else {
                                    AiStreamBridge.error(msg)
                                }
                                AiCancelRegistry.clear(handle)
                                ime.setAiProcessingForKey(false, key)
                            } },
                            cancelHandle = handle
                        )
                    } catch (e: Exception) {
                        ime.mHandler.post {
                            if (AiCancelRegistry.isCancelled(handle)) {
                                AiStreamBridge.cancel()
                            } else {
                                AiStreamBridge.error(e.message ?: "Unknown error")
                            }
                            AiCancelRegistry.clear(handle)
                            ime.setAiProcessingForKey(false, key)
                        }
                    }
                }.start()
            }
            AiRetryRegistry.set(::runStream)
            runStream()
            dismissDialog()
            val intent = android.content.Intent(ime, ResultViewActivity::class.java)
            intent.putExtra("streaming", true)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ime.startActivity(intent)
            return
        }

        // Direct send path (no streaming): keep dialog open with spinner until done
        isProcessing = true
        ime.setAiProcessingForKey(true, helium314.keyboard.latin.utils.ToolbarKey.AI_CLIPBOARD)
        val handle = AiCancelRegistry.start(helium314.keyboard.latin.utils.ToolbarKey.AI_CLIPBOARD)
        Thread {
            try {
                val result = AiServiceSync.processWithModelAndInstruction(
                    clipboardContent, model, instruction, prefs, handle
                )
                ime.mHandler.post {
                    val cancelled = AiCancelRegistry.isCancelled(handle)
                    AiCancelRegistry.clear(handle)
                    ime.setAiProcessingForKey(false, helium314.keyboard.latin.utils.ToolbarKey.AI_CLIPBOARD)
                    if (cancelled) {
                        isProcessing = false
                        return@post
                    }
                    dismissDialog()
                    ime.inputLogic.mConnection.commitText(result, 1)
                }
            } catch (e: Exception) {
                ime.mHandler.post {
                    val cancelled = AiCancelRegistry.isCancelled(handle)
                    AiCancelRegistry.clear(handle)
                    isProcessing = false
                    ime.setAiProcessingForKey(false, helium314.keyboard.latin.utils.ToolbarKey.AI_CLIPBOARD)
                    if (cancelled) return@post
                    Toast.makeText(ime, String.format(ime.getString(R.string.ai_error_prefix), e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0]
            if (selectionHolder[0] != selectedIndex) {
                selectedIndex = selectionHolder[0]
            }
        }
        isOllamaLoading = false
        if (selectionHolder[0] != selectedIndex) {
            selectedIndex = selectionHolder[0]
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BrandHeader(stringResource(R.string.ai_clipboard_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Clipboard content preview
            SectionLabel(stringResource(R.string.process_text_selected))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 100.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                val scrollState = rememberScrollState()
                Text(
                    text = clipboardContent,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(scrollState)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Model picker
            val modelName = if (selectedIndex in models.indices) models[selectedIndex].displayName else "..."
            ModelPickerRow(
                currentModelName = modelName,
                isLoading = isOllamaLoading,
                onClick = {
                    if (!ollamaLoaded[0]) {
                        Toast.makeText(ime, ime.getString(R.string.ai_loading_models), Toast.LENGTH_SHORT).show()
                        return@ModelPickerRow
                    }
                    if (models.isEmpty()) return@ModelPickerRow
                    val names = models.map { it.displayName }.toTypedArray()
                    showImePickerDialog(
                        ime = ime,
                        title = ime.getString(R.string.ai_choose_model),
                        items = names,
                        onItemSelected = { which ->
                            selectedIndex = which
                            val custom = which in models.indices && models[which].isCustomPreset
                            isCustom = custom
                            if (custom) {
                                val p = models[which].customPrompt
                                val displayPrompt = p.ifEmpty { ime.getString(R.string.ai_custom_no_prompt) }
                                instrInput.setText(ime.getString(R.string.ai_custom_prompt_prefix) + displayPrompt)
                                instrInput.keyListener = null
                                instrInput.isFocusable = false
                                instrInput.alpha = 0.5f
                            } else {
                                instrInput.setText("")
                                instrInput.keyListener = TextKeyListener.getInstance()
                                instrInput.isFocusableInTouchMode = true
                                instrInput.alpha = 1f
                            }
                        }
                    )
                }
            )

            if (isCustom) {
                CustomPresetHint()
            }

            Spacer(Modifier.height(8.dp))

            // Instruction field
            SectionLabel(stringResource(R.string.process_text_instruction_hint))
            ImeInstructionField(
                editText = instrInput,
                isReadOnly = isCustom
            )
        } // end scrollable content

        // Bottom area: either buttons or processing indicator
        if (isProcessing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = brandTeal()
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ai_generating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { AiCancelRegistry.cancel() }) {
                    Text(
                        stringResource(R.string.process_text_cancel),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { dismissDialog() }) {
                    Text(
                        stringResource(R.string.process_text_cancel),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { startAiCall(directSend = false) }) {
                    Text(
                        stringResource(R.string.process_text_preview),
                        color = brandTeal()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { startAiCall(directSend = true) },
                    colors = brandButtonColors(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.process_text_insert))
                }
            }
        }
    } // end outer Column
}

// ════════════════════════════════════════════════════════════════════
// AiResultViewContent — shared result-view UI used by both
// ResultViewActivity and ProcessTextActivity
// ════════════════════════════════════════════════════════════════════

@Composable
fun AiResultViewContent(
    initialText: String,
    streaming: Boolean,
    onCancelStream: () -> Unit,
    onClose: () -> Unit,
    onNewPrompt: () -> Unit,
    onInsert: (String) -> Unit,
    primaryActionLabel: String? = null,
    primaryActionSelectionLabel: String? = null
) {
    val context = LocalContext.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialText)) }
    val selection = textFieldValue.selection
    val hasSelection = !selection.collapsed && selection.min >= 0 && selection.max <= textFieldValue.text.length

    val streamText by AiStreamBridge.text.collectAsState()
    val streamState by AiStreamBridge.state.collectAsState()
    val isStreaming = streaming && streamState == AiStreamBridge.State.STREAMING
    val isError = streaming && streamState == AiStreamBridge.State.ERROR

    // While streaming, drive the text field from the bridge
    LaunchedEffect(streamText, streaming, streamState) {
        if (streaming && streamState != AiStreamBridge.State.IDLE) {
            if (textFieldValue.text != streamText) {
                textFieldValue = TextFieldValue(
                    text = streamText,
                    selection = TextRange(streamText.length)
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0x80000000)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .padding(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Text(
                        stringResource(R.string.ai_result_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = brandTeal()
                    )
                    Text(
                        if (isStreaming) stringResource(R.string.ai_generating) else stringResource(R.string.ai_result_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val scrollState = rememberScrollState()
                    val defaultFling = ScrollableDefaults.flingBehavior()
                    val dampenedFling = remember(defaultFling) {
                        object : FlingBehavior {
                            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                                return with(defaultFling) { performFling(initialVelocity * 0.3f) }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .verticalScroll(scrollState, flingBehavior = dampenedFling)
                            .padding(12.dp)
                    ) {
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { if (!isStreaming) textFieldValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = isStreaming,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (isError) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (AiStreamBridge.errorMessage ?: "Error").trim('[', ']'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (AiRetryRegistry.hasAction()) {
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { AiRetryRegistry.retry() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Retry", fontSize = 13.sp, color = brandTeal())
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isStreaming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = brandTeal()
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                if (isStreaming) onCancelStream() else onClose()
                            },
                            colors = brandOutlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (isStreaming) stringResource(R.string.ai_result_cancel) else stringResource(R.string.ai_result_close),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = { onNewPrompt() },
                            enabled = !isStreaming,
                            colors = brandOutlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.ai_result_new_prompt), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val toInsert = if (hasSelection) {
                                    textFieldValue.text.substring(selection.min, selection.max)
                                } else {
                                    textFieldValue.text
                                }
                                onInsert(toInsert)
                            },
                            enabled = !isStreaming,
                            colors = brandButtonColors(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            val primaryLabel = if (hasSelection) {
                                primaryActionSelectionLabel ?: stringResource(R.string.ai_result_insert_selection)
                            } else {
                                primaryActionLabel ?: stringResource(R.string.ai_result_insert)
                            }
                            Text(
                                primaryLabel,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Long-press confirmation on the AI_CONVERSATION toolbar key. Shows
 * "Mark all X unread reminders as read?" and, on confirm, clears every
 * unread reminder across all chats. Used as the bulk-cleanup escape
 * hatch after a boot/update where many reminders fire at once and are
 * each tied to a different chat.
 */
fun showMarkAllRemindersReadDialog(ime: helium314.keyboard.latin.LatinIME) {
    try {
        val unreadCount = try {
            ReminderStore.all(ime).count { it.unread }
        } catch (_: Exception) { 0 }
        if (unreadCount == 0) {
            android.widget.Toast.makeText(ime, "No unread reminders", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        helium314.keyboard.latin.utils.showImeComposeDialog(
            ime = ime,
            title = "Mark all reminders as read?",
            positiveButton = helium314.keyboard.latin.utils.DialogButton("Mark as read") { d ->
                try { ReminderStore.markAllRead(ime) } catch (_: Exception) {}
                try { ime.refreshReminderAccent() } catch (_: Exception) {}
                android.widget.Toast.makeText(ime, "Cleared $unreadCount reminders", android.widget.Toast.LENGTH_SHORT).show()
                d.dismiss()
            },
            negativeButton = helium314.keyboard.latin.utils.DialogButton("Cancel") { d -> d.dismiss() },
            content = {
                androidx.compose.material3.Text(
                    text = "$unreadCount unread reminder${if (unreadCount == 1) "" else "s"} across your conversations will be cleared.",
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        )
    } catch (e: Exception) {
        android.util.Log.w("AiDialogComponents", "showMarkAllRemindersReadDialog failed: ${e.message}")
    }
}

// ════════════════════════════════════════════════════════════════════
// showAiActionsDialog — IME-attached dialog for AI Actions / MCP
// ════════════════════════════════════════════════════════════════════

fun showAiActionsDialog(ime: LatinIME) {
    val prefs = DeviceProtectedUtils.getSharedPreferences(ime)
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val dialogContext = helium314.keyboard.latin.utils.getPlatformDialogThemeContext(ime)

    val statusText = mutableStateOf("")
    val resultText = mutableStateOf("")
    val isProcessing = mutableStateOf(false)
    val isDone = mutableStateOf(false)
    val chatHistory = java.util.Collections.synchronizedList(mutableListOf<AiServiceSync.ChatMessage>())

    // EditTexts for keyboard input routing via setDialogEditText
    val nightMode = (dialogContext.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val borderColor = if (nightMode)
        android.graphics.Color.parseColor("#444444") else android.graphics.Color.parseColor("#CCCCCC")
    fun styleEditText(et: EditText) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(2, borderColor)
            cornerRadius = 24f
        }
        et.background = bg
        et.setPadding(32, 24, 32, 24)
    }
    val commandInput = createImeEditText(dialogContext, ime).apply {
        hint = "Type a command..."
        minLines = 2
        maxLines = 4
        styleEditText(this)
    }
    val replyInput = createImeEditText(dialogContext, ime).apply {
        hint = "Reply..."
        minLines = 1
        maxLines = 2
        styleEditText(this)
    }

    var dialogRef: android.app.AlertDialog? = null

    fun resolveMcpModel(): String {
        AiServiceSync.checkCloudFallback(prefs)
        val mcpModel = prefs.getString(Settings.PREF_AI_MCP_MODEL, Defaults.PREF_AI_MCP_MODEL) ?: ""
        return if (mcpModel.isEmpty()) {
            prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
        } else mcpModel
    }

    fun executeCommand() {
        try {
            val model = resolveMcpModel()
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
                    if (!chunk.contains("\u27E6TOOL_") && !chunk.contains("\u27E6/TOOL_")) {
                        handler.post { resultText.value = (resultText.value + chunk).take(500) }
                    } else {
                        try {
                            val json = chunk.substringAfter("\u27E7").substringBefore("\u27E6")
                            val name = org.json.JSONObject(json).optString("name", "")
                            if (name.isNotEmpty()) {
                                handler.post { statusText.value = "Tool: $name" }
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

            val cleanResult = finalResult
                .replace(Regex("\u27E6TOOL_[a-f0-9]+\u27E7.*?\u27E6/TOOL_[a-f0-9]+\u27E7", RegexOption.DOT_MATCHES_ALL), "")
                .trim()

            if (cleanResult.isNotEmpty()) {
                chatHistory.add(AiServiceSync.ChatMessage(role = "assistant", content = cleanResult))
            }

            handler.post {
                statusText.value = ""
                resultText.value = if (cleanResult.isNotEmpty()) "\u2713 $cleanResult" else "\u2713 Done"
                isDone.value = true
                isProcessing.value = false
                // Switch keyboard routing to reply EditText
                ime.setDialogEditText(replyInput)
            }
        } catch (e: Exception) {
            android.util.Log.e("AiActions", "Execute failed", e)
            handler.post {
                statusText.value = ""
                resultText.value = "Error: ${e.message}"
                isDone.value = true
                isProcessing.value = false
                ime.setDialogEditText(replyInput)
            }
        }
    }

    fun sendMessage(text: String) {
        chatHistory.add(AiServiceSync.ChatMessage(role = "user", content = text))
        isProcessing.value = true
        isDone.value = false
        statusText.value = "Executing..."
        resultText.value = ""
        kotlin.concurrent.thread { executeCommand() }
    }

    var whisperRecorder: WhisperRecorder? = null
    val isListening = mutableStateOf(false)
    val engine = prefs.getString(Settings.PREF_AI_VOICE_ENGINE, Defaults.PREF_AI_VOICE_ENGINE) ?: "google"

    fun checkMicPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            ime.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val micIntent = android.content.Intent(ime, VoiceTrampolineActivity::class.java)
            micIntent.action = VoiceTrampolineActivity.ACTION_REQUEST_MIC
            micIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ime.startActivity(micIntent)
            return false
        }
        return true
    }

    fun toggleVoice(onResult: (String) -> Unit) {
        if (!checkMicPermission()) return

        if (engine == "whisper") {
            // Whisper: toggle recording
            val wr = whisperRecorder
            if (wr != null) {
                // Stop and transcribe
                whisperRecorder = null
                isListening.value = false
                handler.post { statusText.value = "Transcribing..." }
                kotlin.concurrent.thread {
                    val wavFile = wr.stop()
                    if (wavFile == null) {
                        handler.post { statusText.value = "" }
                        return@thread
                    }
                    val transcription = AiServiceSync.transcribeWithWhisper(wavFile, prefs, null)
                    handler.post {
                        statusText.value = ""
                        if (transcription.startsWith("[Whisper")) {
                            Toast.makeText(ime, transcription, Toast.LENGTH_LONG).show()
                        } else if (transcription.isNotBlank()) {
                            onResult(transcription)
                        }
                    }
                }
            } else {
                // Start recording
                val newRecorder = WhisperRecorder(ime)
                newRecorder.start()
                whisperRecorder = newRecorder
                isListening.value = true
            }
        } else {
            // Google STT
            if (isListening.value) return
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ime)) {
                Toast.makeText(ime, "Speech not available", Toast.LENGTH_SHORT).show()
                return
            }
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(ime)
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    handler.post { isListening.value = true }
                }
                override fun onEndOfSpeech() {}
                override fun onResults(results: android.os.Bundle?) {
                    handler.post { isListening.value = false }
                    val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    sr.destroy()
                    if (text.isNotBlank()) handler.post { onResult(text) }
                }
                override fun onError(error: Int) {
                    handler.post { isListening.value = false }
                    sr.destroy()
                    if (error != android.speech.SpeechRecognizer.ERROR_CLIENT) {
                        handler.post { Toast.makeText(ime, "Speech error: $error", Toast.LENGTH_SHORT).show() }
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val sttIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            sr.startListening(sttIntent)
        }
    }

    val dialog = showImeComposeDialog(
        ime = ime,
        chromeless = true,
        centerOnScreen = true,
        onDismiss = {
            ime.setDialogEditText(null)
            whisperRecorder?.stop()
            whisperRecorder = null
        }
    ) {
        val status by remember { statusText }
        val result by remember { resultText }
        val processing by remember { isProcessing }
        val done by remember { isDone }
        val listening by remember { isListening }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_ai_actions),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (listening) "Listening..." else "AI Actions",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (listening) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!processing) {
                    Text(
                        text = "\u2715",
                        color = Color(0xFF888888),
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable { dialogRef?.dismiss() }
                            .padding(4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Command input (first message)
            if (!processing && !done && chatHistory.isEmpty()) {
                AndroidView(
                    factory = { commandInput },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 120.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mic button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (listening) Color(0xFFE53935) else Color(0xFF424242),
                                CircleShape
                            )
                            .clickable {
                                toggleVoice { text -> sendMessage(text) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_shortcut_mic),
                            contentDescription = "Voice input",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // Send button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(
                                if (commandInput.text.isNotBlank()) Color(0xFFFF9800) else Color(0xFF555555),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                val command = commandInput.text.toString().trim()
                                if (command.isNotBlank()) {
                                    commandInput.setText("")
                                    sendMessage(command)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_overlay_execute),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Send", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
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
                        .heightIn(max = 80.dp)
                        .verticalScroll(resultScrollState)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = result,
                        color = if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = if (done) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Reply row (after execution done)
            if (done && !processing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Mic button for reply
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (listening) Color(0xFFE53935) else Color(0xFF424242),
                                CircleShape
                            )
                            .clickable {
                                toggleVoice { text -> sendMessage(text) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_shortcut_mic),
                            contentDescription = "Voice reply",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AndroidView(
                        factory = { replyInput },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 80.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFF9800), CircleShape)
                            .clickable {
                                val msg = replyInput.text.toString().trim()
                                if (msg.isNotBlank()) {
                                    replyInput.setText("")
                                    sendMessage(msg)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_overlay_execute),
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    dialogRef = dialog
    // Disable default dialog window animation to prevent jank
    dialog.window?.setWindowAnimations(0)
    ime.setDialogEditText(commandInput)
}
