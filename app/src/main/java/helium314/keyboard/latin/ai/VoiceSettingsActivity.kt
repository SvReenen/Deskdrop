// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.screens.BrandHeader
import helium314.keyboard.settings.screens.brandButtonColors
import helium314.keyboard.settings.screens.brandTeal
import org.json.JSONArray

class VoiceSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = DeviceProtectedUtils.getSharedPreferences(this)
        val builtinNames = Defaults.AI_VOICE_MODE_NAMES
        val savedMode = prefs.getInt(Settings.PREF_AI_VOICE_MODE, Defaults.PREF_AI_VOICE_MODE)
        val builtinCount = builtinNames.size

        val customNames = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]"))
            for (i in 0 until arr.length()) {
                customNames.add(arr.getJSONObject(i).getString("name"))
            }
        } catch (_: Exception) {}

        val currentVoiceModel = prefs.getString(Settings.PREF_AI_VOICE_MODEL, "") ?: ""
        val currentEngine = prefs.getString(Settings.PREF_AI_VOICE_ENGINE, Defaults.PREF_AI_VOICE_ENGINE) ?: Defaults.PREF_AI_VOICE_ENGINE
        val validSavedMode = if (savedMode < builtinCount + customNames.size) savedMode else 0

        val models = mutableListOf<ModelItem>()
        models.add(ModelItem(displayName = getString(R.string.ai_voice_model_default), modelValue = ""))
        models.addAll(loadCloudModels(prefs))
        val cachedLocal = cachedOllamaModels(prefs) + cachedOpenAiCompatibleModels(prefs)
        models.addAll(cachedLocal)
        val ollamaLoaded = booleanArrayOf(cachedLocal.isNotEmpty())

        val modelFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER)
        if (modelFilter == "cloud") ollamaLoaded[0] = true

        Thread {
            if (modelFilter == "cloud") return@Thread
            val ollamaUrl = AiServiceSync.resolveOllamaBaseUrl(prefs)
            val ollamaItems = mutableListOf<ModelItem>()
            try {
                val ollamaModels = AiServiceSync.fetchOllamaModels(ollamaUrl)
                for (model in ollamaModels) {
                    ollamaItems.add(ModelItem(
                        displayName = "$model${getString(R.string.ai_model_suffix_ollama)}",
                        modelValue = "ollama:$model"
                    ))
                }
            } catch (_: Exception) {}
            val openaiItems = fetchOpenAiCompatModelsForPrefs(prefs)
            runOnUiThread {
                models.removeAll { it.modelValue.startsWith("ollama:") || it.modelValue.startsWith("openai:") }
                models.addAll(ollamaItems)
                models.addAll(openaiItems)
                ollamaLoaded[0] = true
            }
        }.start()

        setContent {
            MaterialTheme {
                Dialog(onDismissRequest = { finish() }) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        VoiceSettingsContent(
                            builtinNames = builtinNames,
                            customNames = customNames,
                            initialMode = validSavedMode,
                            initialModel = currentVoiceModel,
                            initialEngine = currentEngine,
                            models = models,
                            ollamaLoaded = ollamaLoaded,
                            prefs = prefs,
                            onSave = { model, engine, mode ->
                                prefs.edit().putString(Settings.PREF_AI_VOICE_MODEL, model).apply()
                                prefs.edit().putString(Settings.PREF_AI_VOICE_ENGINE, engine).apply()
                                prefs.edit().putInt(Settings.PREF_AI_VOICE_MODE, mode).apply()
                                val modeName = if (mode < builtinCount) builtinNames[mode]
                                else customNames.getOrElse(mode - builtinCount) { "?" }
                                Toast.makeText(this@VoiceSettingsActivity, String.format(getString(R.string.ai_voice_saved), modeName), Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            onCancel = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSettingsContent(
    builtinNames: Array<String>,
    customNames: List<String>,
    initialMode: Int,
    initialModel: String,
    initialEngine: String,
    models: MutableList<ModelItem>,
    ollamaLoaded: BooleanArray,
    prefs: android.content.SharedPreferences,
    onSave: (model: String, engine: String, mode: Int) -> Unit,
    onCancel: () -> Unit
) {
    var selectedModel by remember { mutableStateOf(initialModel) }
    var selectedEngine by remember { mutableStateOf(initialEngine) }
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0]) }
    val builtinCount = builtinNames.size

    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0]
        }
        isOllamaLoading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
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
                formatModelDisplayName(selectedModel, prefs)
            }
            var showModelDropdown by remember { mutableStateOf(false) }
            ModelPickerRow(
                currentModelName = modelDisplayName,
                isLoading = isOllamaLoading,
                onClick = { showModelDropdown = true }
            )
            DropdownMenu(
                expanded = showModelDropdown,
                onDismissRequest = { showModelDropdown = false }
            ) {
                models.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.displayName) },
                        onClick = {
                            selectedModel = item.modelValue
                            showModelDropdown = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
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
        }

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
    }
}

private fun formatModelDisplayName(modelId: String, prefs: android.content.SharedPreferences): String {
    for (m in AiServiceSync.cloudModelsWithCustom(prefs)) {
        if (m.second == modelId) return m.first
    }
    if (modelId.startsWith("ollama:")) {
        return modelId.substring(7) + " (Ollama)"
    }
    return modelId
}
