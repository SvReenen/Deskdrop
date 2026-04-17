// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.screens.BrandHeader
import helium314.keyboard.settings.screens.brandButtonColors

class ExecuteSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = DeviceProtectedUtils.getSharedPreferences(this)
        val currentModel = prefs.getString(Settings.PREF_AI_MCP_MODEL, "") ?: ""

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
                        ExecuteSettingsContent(
                            initialModel = currentModel,
                            models = models,
                            ollamaLoaded = ollamaLoaded,
                            prefs = prefs,
                            onSave = { model ->
                                prefs.edit().putString(Settings.PREF_AI_MCP_MODEL, model).apply()
                                Toast.makeText(this@ExecuteSettingsActivity, getString(R.string.model_saved), Toast.LENGTH_SHORT).show()
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
private fun ExecuteSettingsContent(
    initialModel: String,
    models: MutableList<ModelItem>,
    ollamaLoaded: BooleanArray,
    prefs: android.content.SharedPreferences,
    onSave: (model: String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedModel by remember { mutableStateOf(initialModel) }
    var isOllamaLoading by remember { mutableStateOf(!ollamaLoaded[0]) }

    LaunchedEffect(Unit) {
        while (!ollamaLoaded[0]) {
            kotlinx.coroutines.delay(200)
            isOllamaLoading = !ollamaLoaded[0]
        }
        isOllamaLoading = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        BrandHeader(stringResource(R.string.widget_execute_description))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val modelDisplayName = if (selectedModel.isEmpty()) {
                stringResource(R.string.ai_voice_model_default)
            } else {
                formatModelName(selectedModel, prefs)
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
                onClick = { onSave(selectedModel) },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

private fun formatModelName(modelId: String, prefs: android.content.SharedPreferences): String {
    for (m in AiServiceSync.cloudModelsWithCustom(prefs)) {
        if (m.second == modelId) return m.first
    }
    if (modelId.startsWith("ollama:")) return modelId.substring(7) + " (Ollama)"
    return modelId
}
