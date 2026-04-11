// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiServiceSync
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DeskdropTeal = Color(0xFF2D8B7A)

private val wizardCloudModels = listOf(
    "Gemini 2.5 Flash" to "gemini:gemini-2.5-flash",
    "Llama 4 Scout (Groq)" to "groq:meta-llama/llama-4-scout-17b-16e-instruct",
    "Llama 3.3 70B (Groq)" to "groq:llama-3.3-70b-versatile",
    "Gemma 2 9B (Groq)" to "groq:gemma2-9b-it",
    "Gemma 3 27B (OpenRouter)" to "openrouter:google/gemma-3-27b-it:free",
    "Llama 4 Scout (OpenRouter)" to "openrouter:meta-llama/llama-4-scout:free",
    "Mistral Small 24B (OpenRouter)" to "openrouter:mistralai/mistral-small-3.1-24b-instruct:free",
    "Qwen3 30B (OpenRouter)" to "openrouter:qwen/qwen3-30b-a3b:free",
)

private sealed class OllamaStatus {
    data object Idle : OllamaStatus()
    data object Connecting : OllamaStatus()
    data class Connected(val count: Int) : OllamaStatus()
    data class Failed(val error: String) : OllamaStatus()
}

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val setupDone = prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_DESKDROP_SETUP_V2, false)

    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        !setupDone -> 4 // IME ready, but AI not configured yet
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(step) {
        if (step == 2)
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                if (!setupDone) step = 4 else step = 3
            }
    }

    // AI setup state
    var selectedMode by rememberSaveable { mutableStateOf("cloud") }
    var ollamaUrl by rememberSaveable {
        mutableStateOf(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL)
    }
    var ollamaFallbackUrl by rememberSaveable {
        mutableStateOf(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL_FALLBACK, "") ?: "")
    }
    var showOllamaFallbackUrlDialog by rememberSaveable { mutableStateOf(false) }
    var ollamaStatus by remember { mutableStateOf<OllamaStatus>(OllamaStatus.Idle) }
    val ollamaModels = remember { mutableStateListOf<String>() }
    var selectedOllamaModel by rememberSaveable { mutableStateOf("") }
    var selectedCloudModel by rememberSaveable { mutableStateOf(wizardCloudModels.first().second) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var aboutMe by rememberSaveable {
        mutableStateOf(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_LOREBOOK, Defaults.PREF_AI_LOREBOOK) ?: "")
    }
    var allowNetworkTools by rememberSaveable {
        mutableStateOf(prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_AI_ALLOW_NETWORK_TOOLS, Defaults.PREF_AI_ALLOW_NETWORK_TOOLS))
    }
    var allowActions by rememberSaveable {
        mutableStateOf(prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_AI_ALLOW_ACTIONS, Defaults.PREF_AI_ALLOW_ACTIONS))
    }

    // Permission launchers for device actions (calendar, notifications)
    val calendarPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results don't matter — best effort */ }
    var notifGranted by remember { mutableStateOf(
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
    ) }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }

    // OpenAI-compatible (LM Studio / vLLM / llama.cpp / ...) optional config
    var openAiCompatExpanded by rememberSaveable { mutableStateOf(false) }
    var openAiCompatUrl by rememberSaveable {
        mutableStateOf(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_OPENAI_COMPAT_URL, "") ?: "")
    }
    var openAiCompatFallbackUrl by rememberSaveable {
        mutableStateOf(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, "") ?: "")
    }
    var openAiCompatKey by rememberSaveable { mutableStateOf("") }
    var showOpenAiCompatUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenAiCompatFallbackUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenAiCompatKeyDialog by rememberSaveable { mutableStateOf(false) }

    fun saveAiAndClose() {
        val editor = prefs.edit()
        editor.putBoolean(helium314.keyboard.latin.settings.Settings.PREF_DESKDROP_SETUP_V2, true)
        editor.putBoolean(helium314.keyboard.latin.settings.Settings.PREF_AI_ALLOW_NETWORK_TOOLS, allowNetworkTools)
        editor.putBoolean(helium314.keyboard.latin.settings.Settings.PREF_AI_ALLOW_ACTIONS, allowActions)
        if (aboutMe.isNotBlank()) {
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_LOREBOOK, aboutMe.trim())
        }
        if (selectedMode == "local" && selectedOllamaModel.isNotBlank()) {
            val model = "ollama:$selectedOllamaModel"
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL, helium314.keyboard.latin.ai.AiServiceSync.normalizeOllamaUrl(ollamaUrl))
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, model)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_INLINE_MODEL, model)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_CONVERSATION_MODEL, model)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_VOICE_MODEL, model)
        }
        if (selectedMode == "local" && ollamaFallbackUrl.isNotBlank()) {
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL_FALLBACK, helium314.keyboard.latin.ai.AiServiceSync.normalizeOllamaUrl(ollamaFallbackUrl))
        }
        if (selectedMode == "local" && openAiCompatUrl.isNotBlank()) {
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OPENAI_COMPAT_URL, openAiCompatUrl)
            if (openAiCompatKey.isNotBlank()) {
                helium314.keyboard.latin.ai.SecureApiKeys.setKey(helium314.keyboard.latin.settings.Settings.PREF_OPENAI_COMPAT_API_KEY, openAiCompatKey)
            }
        }
        if (selectedMode == "local" && openAiCompatFallbackUrl.isNotBlank()) {
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, helium314.keyboard.latin.ai.AiServiceSync.normalizeOllamaUrl(openAiCompatFallbackUrl))
        }
        if (selectedMode == "cloud") {
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, selectedCloudModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_INLINE_MODEL, selectedCloudModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_CONVERSATION_MODEL, selectedCloudModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_VOICE_MODEL, selectedCloudModel)
            val provider = selectedCloudModel.substringBefore(":")
            val keyPref = when (provider) {
                "gemini" -> helium314.keyboard.latin.settings.Settings.PREF_GEMINI_API_KEY
                "groq" -> helium314.keyboard.latin.settings.Settings.PREF_GROQ_API_KEY
                "openrouter" -> helium314.keyboard.latin.settings.Settings.PREF_OPENROUTER_API_KEY
                else -> null
            }
            if (keyPref != null && apiKey.isNotBlank()) {
                helium314.keyboard.latin.ai.SecureApiKeys.setKey(keyPref, apiKey)
            }
        }
        val commitResult = editor.commit()
        Log.d("WelcomeWizard", "commit()=$commitResult, setup_v2=${prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_DESKDROP_SETUP_V2, false)}, model=${prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, "")}")
        if (!commitResult) {
            editor.apply()
            Log.w("WelcomeWizard", "commit() failed, used apply() as fallback")
        }
        android.widget.Toast.makeText(ctx, "Try the AI buttons on your keyboard toolbar!", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    // Original wizard styling
    val useWideLayout = isWideScreen()
    val stepBackgroundColor = Color(ContextCompat.getColor(ctx, R.color.setup_step_background))
    val textColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_action))
    val textColorDim = textColor.copy(alpha = 0.5f)
    val titleColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_title))
    val appName = stringResource(ctx.applicationInfo.labelRes)

    @Composable fun bigText() {
        val resource = when {
            step == 0 -> R.string.setup_welcome_title
            step <= 3 -> R.string.setup_steps_title
            else -> R.string.onboarding_title
        }
        Column(Modifier.padding(bottom = 36.dp)) {
            if (step <= 3) {
                Text(
                    stringResource(resource, appName),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    color = titleColor,
                )
                if (JniUtils.sHaveGestureLib)
                    Text(
                        stringResource(R.string.setup_welcome_additional_description),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.End,
                        color = titleColor,
                        modifier = Modifier.fillMaxWidth()
                    )
            } else {
                Text(
                    stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    color = DeskdropTeal,
                )
                Text(
                    stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = titleColor.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    fun ColumnScope.Step(step: Int, title: String, instruction: String, actionText: String, icon: Painter, action: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", color = if (step == 1) titleColor else textColorDim)
            Text("2", color = if (step == 2) titleColor else textColorDim)
            Text("3", color = if (step == 3) titleColor else textColorDim)
        }
        Column(Modifier
            .background(color = stepBackgroundColor)
            .padding(16.dp)
        ) {
            Text(title)
            Text(instruction, style = MaterialTheme.typography.bodyLarge.merge(color = textColor))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clickable { action() }
                .background(color = stepBackgroundColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.padding(end = 6.dp).size(32.dp), tint = textColor)
            Text(actionText, Modifier.weight(1f))
        }
    }

    @Composable fun steps() {
        if (step == 0)
            Step0 { step = 1 }
        else if (step in 1..3)
            Column {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    step = determineStep()
                }
                if (step == 1) {
                    Step(
                        step,
                        stringResource(R.string.setup_step1_title, appName),
                        stringResource(R.string.setup_step1_instruction, appName),
                        stringResource(R.string.setup_step1_action),
                        painterResource(R.drawable.ic_setup_key)
                    ) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    }
                } else if (step == 2) {
                    Step(
                        step,
                        stringResource(R.string.setup_step2_title, appName),
                        stringResource(R.string.setup_step2_instruction, appName),
                        stringResource(R.string.setup_step2_action),
                        painterResource(R.drawable.ic_setup_select),
                        imm::showInputMethodPicker
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { close() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.sym_keyboard_language_switch),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_step3_action), Modifier.weight(1f))
                    }
                } else { // step 3
                    Step(
                        step,
                        stringResource(R.string.setup_step3_title),
                        stringResource(R.string.setup_step3_instruction, appName),
                        stringResource(R.string.setup_step3_action),
                        painterResource(R.drawable.sym_keyboard_language_switch),
                        close
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { step = 4 }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_setup_check),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_finish_action), Modifier.weight(1f))
                    }
                }
            }
        else // AI setup steps (4-9)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Progress indicator for AI steps (4-9)
                val aiStep = step - 3 // 1..6
                val totalAiSteps = 6
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..totalAiSteps) {
                        Box(
                            modifier = Modifier
                                .size(if (i == aiStep) 10.dp else 8.dp)
                                .background(
                                    color = if (i <= aiStep) DeskdropTeal else DeskdropTeal.copy(alpha = 0.25f),
                                    shape = CircleShape
                                )
                        )
                        if (i < totalAiSteps) Spacer(Modifier.width(8.dp))
                    }
                }

                when (step) {
                    // Step 4: Choose mode
                    4 -> {
                        Text(
                            "How do you want to use AI?",
                            style = MaterialTheme.typography.titleLarge,
                            color = DeskdropTeal
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You can always change this later in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        ModeCard("Local (Ollama)", "Run AI on your own hardware. Fully private.", selectedMode == "local") { selectedMode = "local" }
                        Spacer(Modifier.height(8.dp))
                        ModeCard("Cloud", "Use free cloud models from Groq, OpenRouter or Gemini.", selectedMode == "cloud") { selectedMode = "cloud" }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = if (selectedMode == "local") 5 else 6 },
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Next") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { saveAiAndClose() }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }

                    // Step 5: Local (Ollama) config
                    5 -> {
                        Text("Ollama Setup", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(16.dp))
                        SettingRow("Ollama URL", ollamaUrl) { showUrlDialog = true }
                        Spacer(Modifier.height(8.dp))
                        SettingRow(
                            "LAN fallback URL (optional)",
                            if (ollamaFallbackUrl.isBlank()) "(not set)" else ollamaFallbackUrl,
                            isOptional = true
                        ) { showOllamaFallbackUrlDialog = true }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    ollamaStatus = OllamaStatus.Connecting
                                    scope.launch {
                                        try {
                                            val models = withContext(Dispatchers.IO) { AiServiceSync.fetchOllamaModels(AiServiceSync.normalizeOllamaUrl(ollamaUrl)) }
                                            ollamaModels.clear(); ollamaModels.addAll(models)
                                            if (models.isNotEmpty() && selectedOllamaModel.isBlank()) selectedOllamaModel = models.first()
                                            ollamaStatus = if (models.isNotEmpty()) OllamaStatus.Connected(models.size) else OllamaStatus.Failed("No models found")
                                        } catch (e: Exception) {
                                            ollamaStatus = OllamaStatus.Failed(e.message ?: "Unknown error")
                                        }
                                    }
                                },
                                enabled = ollamaStatus !is OllamaStatus.Connecting && ollamaUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                            ) { Text(stringResource(R.string.ollama_test_connection)) }
                            when (val s = ollamaStatus) {
                                is OllamaStatus.Connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = DeskdropTeal)
                                is OllamaStatus.Connected -> Text("Connected (${s.count} models)", color = DeskdropTeal, style = MaterialTheme.typography.bodySmall)
                                is OllamaStatus.Failed -> Text(s.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                is OllamaStatus.Idle -> {}
                            }
                        }
                        if (ollamaModels.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SettingRow("Model", selectedOllamaModel.ifBlank { "(select)" }) { showModelDialog = true }
                        }

                        // Collapsible OpenAI-compatible section (LM Studio / vLLM / llama.cpp)
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openAiCompatExpanded = !openAiCompatExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (openAiCompatExpanded) "▲" else "▼",
                                color = DeskdropTeal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Other server (LM Studio, vLLM, llama.cpp)",
                                style = MaterialTheme.typography.titleSmall,
                                color = DeskdropTeal,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (openAiCompatExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Optional. Connect to any OpenAI-compatible server. Default ports: LM Studio 1234, vLLM 8000, llama.cpp 8080.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(12.dp))
                            SettingRow(
                                "Server URL",
                                if (openAiCompatUrl.isBlank()) "(not set)" else openAiCompatUrl,
                                isOptional = true
                            ) { showOpenAiCompatUrlDialog = true }
                            Spacer(Modifier.height(8.dp))
                            SettingRow(
                                "LAN fallback URL (optional)",
                                if (openAiCompatFallbackUrl.isBlank()) "(not set)" else openAiCompatFallbackUrl,
                                isOptional = true
                            ) { showOpenAiCompatFallbackUrlDialog = true }
                            Spacer(Modifier.height(8.dp))
                            SettingRow(
                                "API key (optional)",
                                if (openAiCompatKey.isBlank()) "(not set)" else "\u2022".repeat(minOf(openAiCompatKey.length, 20)),
                                isOptional = true
                            ) { showOpenAiCompatKeyDialog = true }
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 7 },
                            Modifier.fillMaxWidth(),
                            enabled = selectedOllamaModel.isNotBlank() && ollamaStatus is OllamaStatus.Connected,
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Next") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 6: Cloud config
                    6 -> {
                        Text("Cloud AI Setup", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(8.dp))
                        Text("Pick a model. Free models from Groq and OpenRouter require no API key.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(16.dp))
                        val displayName = wizardCloudModels.firstOrNull { it.second == selectedCloudModel }?.first ?: selectedCloudModel
                        SettingRow("Model", displayName) { showModelDialog = true }
                        Spacer(Modifier.height(12.dp))
                        val provider = selectedCloudModel.substringBefore(":")
                        val needsKey = provider == "gemini"
                        val keyLabel = when (provider) {
                            "gemini" -> "Gemini API Key (required)"
                            "groq" -> "Groq API Key (optional)"
                            "openrouter" -> "OpenRouter API Key (optional)"
                            else -> "API Key"
                        }
                        SettingRow(keyLabel, if (apiKey.isBlank()) "(not set)" else "\u2022".repeat(minOf(apiKey.length, 20))) { showApiKeyDialog = true }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No key yet? Get a free one:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/")))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Get free Groq key", color = DeskdropTeal) }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/")))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Get free Gemini key", color = DeskdropTeal) }
                        Text(
                            "Gemini availability depends on your country.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (!needsKey) {
                            Spacer(Modifier.height(8.dp))
                            Text("Groq and OpenRouter free models work without an API key.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 7 },
                            Modifier.fillMaxWidth(),
                            enabled = !needsKey || apiKey.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Next") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 7: About me
                    7 -> {
                        Text(
                            stringResource(R.string.onboarding_about_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = DeskdropTeal
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.onboarding_about_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = aboutMe,
                            onValueChange = { aboutMe = it },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            placeholder = { Text(stringResource(R.string.onboarding_about_hint), style = MaterialTheme.typography.bodyMedium) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DeskdropTeal,
                                cursorColor = DeskdropTeal
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 8 },
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Next") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step = 8 },
                            Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.onboarding_skip)) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = if (selectedMode == "local") 5 else 6 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 8: Tool permissions
                    8 -> {
                        Text("What can the AI do?", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "These settings control what tools the AI can use. You can always change them later in AI Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Internet access", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "Web search, fetch URLs, weather",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = allowNetworkTools,
                                onCheckedChange = { allowNetworkTools = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = DeskdropTeal)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Device actions", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "Set timers, open apps, manage calendar",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = allowActions,
                                onCheckedChange = { enabled ->
                                    allowActions = enabled
                                    if (enabled) {
                                        calendarPermLauncher.launch(arrayOf(
                                            android.Manifest.permission.READ_CALENDAR,
                                            android.Manifest.permission.WRITE_CALENDAR
                                        ))
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = DeskdropTeal)
                            )
                        }

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Notifications", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Reminders, alerts, and status updates",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                Switch(
                                    checked = notifGranted,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = DeskdropTeal)
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 9 },
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Next") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 7 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 9: Done
                    9 -> {
                        Text("You're all set!", style = MaterialTheme.typography.headlineSmall, color = DeskdropTeal, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        val modelDisplay = if (selectedMode == "local") "Local: $selectedOllamaModel"
                        else "Cloud: ${wizardCloudModels.firstOrNull { it.second == selectedCloudModel }?.first ?: selectedCloudModel}"
                        Box(Modifier.fillMaxWidth().background(DeskdropTeal.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                            Column {
                                Text("Active model:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Text(modelDisplay, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }

                        // Test message
                        Spacer(Modifier.height(12.dp))
                        var testStatus by remember { mutableStateOf<String?>(null) }
                        var testRunning by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = {
                                if (testRunning) return@OutlinedButton
                                testRunning = true
                                testStatus = null
                                // Temporarily save model settings so the test uses the configured model
                                val editor = prefs.edit()
                                if (selectedMode == "local" && selectedOllamaModel.isNotBlank()) {
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, "ollama:$selectedOllamaModel")
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL, helium314.keyboard.latin.ai.AiServiceSync.normalizeOllamaUrl(ollamaUrl))
                                } else if (selectedMode == "cloud") {
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, selectedCloudModel)
                                    val provider = selectedCloudModel.substringBefore(":")
                                    val keyPref = when (provider) {
                                        "gemini" -> helium314.keyboard.latin.settings.Settings.PREF_GEMINI_API_KEY
                                        "groq" -> helium314.keyboard.latin.settings.Settings.PREF_GROQ_API_KEY
                                        "openrouter" -> helium314.keyboard.latin.settings.Settings.PREF_OPENROUTER_API_KEY
                                        else -> null
                                    }
                                    if (keyPref != null && apiKey.isNotBlank()) {
                                        helium314.keyboard.latin.ai.SecureApiKeys.setKey(keyPref, apiKey)
                                    }
                                }
                                editor.apply()
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            helium314.keyboard.latin.ai.AiServiceSync.processWithModelAndInstruction(
                                                "Hello! This is a test message.",
                                                prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, "") ?: "",
                                                "You are a friendly assistant. Reply briefly to the user's message.",
                                                prefs
                                            )
                                        }
                                        testStatus = if (result.isNotBlank()) "\u2705 $result" else "\u274C No response received"
                                        testRunning = false
                                    } catch (e: Exception) {
                                        testStatus = "\u274C ${e.message}"
                                        testRunning = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !testRunning
                        ) {
                            if (testRunning) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeskdropTeal)
                                Spacer(Modifier.width(8.dp))
                                Text("Testing...", color = DeskdropTeal)
                            } else {
                                Text("Send a test message", color = DeskdropTeal)
                            }
                        }
                        if (testStatus != null) {
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().background(
                                if (testStatus!!.startsWith("Error")) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else DeskdropTeal.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp)
                            ).padding(12.dp)) {
                                Text(
                                    testStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testStatus!!.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 4
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Your AI toolbar", style = MaterialTheme.typography.titleMedium, color = DeskdropTeal)
                        Spacer(Modifier.height(8.dp))
                        Text("These buttons are pinned to your keyboard toolbar:",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_assist, "AI Assist", "Improve, rewrite or translate your text")
                        Spacer(Modifier.height(4.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_voice, "AI Voice", "Dictate and let AI clean up your speech")
                        Spacer(Modifier.height(4.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_clipboard, "AI Clipboard", "Process copied text with AI")
                        Spacer(Modifier.height(4.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_slot_1, "Slots 1-4", "Quick-access AI actions (configure in Settings)")
                        Spacer(Modifier.height(4.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_conversation, "AI Chat", "Open Deskdrop chat for multi-turn conversations")
                        Spacer(Modifier.height(4.dp))
                        ToolbarHintIcon(R.drawable.ic_ai_actions, "AI Actions", "Voice commands: set timers, search the web, manage calendar (enable in toolbar settings)")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { saveAiAndClose() }, Modifier.fillMaxWidth(), enabled = selectedMode != "local" || selectedOllamaModel.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)) {
                            Text("Start using Deskdrop")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 8 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }
                }
            }
    }

    Surface {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row {
                        Box(Modifier.weight(0.4f)) { bigText() }
                        Box(Modifier.weight(0.6f)) { steps() }
                    }
                else
                    Column {
                        bigText()
                        steps()
                    }
            }
        }
    }

    // Dialogs (outside Surface, safe for IME)
    if (showUrlDialog) {
        TextInputDialog(
            onDismissRequest = { showUrlDialog = false },
            onConfirmed = { ollamaUrl = it; ollamaStatus = OllamaStatus.Idle; showUrlDialog = false },
            title = { Text("Ollama URL") },
            description = { Text("Your Ollama server address, e.g. http://192.168.1.50:11434 or a Tailscale/VPN IP") },
            initialText = ollamaUrl, singleLine = true, keyboardType = KeyboardType.Uri,
            checkTextValid = { it.isNotBlank() }
        )
    }
    if (showApiKeyDialog) {
        val provider = selectedCloudModel.substringBefore(":")
        TextInputDialog(
            onDismissRequest = { showApiKeyDialog = false },
            onConfirmed = { apiKey = it; showApiKeyDialog = false },
            title = { Text(when (provider) { "gemini" -> "Gemini API Key"; "groq" -> "Groq API Key"; "openrouter" -> "OpenRouter API Key"; else -> "API Key" }) },
            initialText = apiKey, singleLine = true, checkTextValid = { true }
        )
    }
    if (showOllamaFallbackUrlDialog) {
        TextInputDialog(
            onDismissRequest = { showOllamaFallbackUrlDialog = false },
            onConfirmed = { ollamaFallbackUrl = it; showOllamaFallbackUrlDialog = false },
            title = { Text("LAN fallback URL") },
            description = { Text("Used automatically when the primary URL is unreachable, e.g. a local IP as backup: http://192.168.1.50:11434") },
            initialText = ollamaFallbackUrl, singleLine = true, keyboardType = KeyboardType.Uri,
            checkTextValid = { true }
        )
    }
    if (showOpenAiCompatUrlDialog) {
        TextInputDialog(
            onDismissRequest = { showOpenAiCompatUrlDialog = false },
            onConfirmed = { openAiCompatUrl = it; showOpenAiCompatUrlDialog = false },
            title = { Text("Server URL") },
            description = { Text("e.g. http://192.168.1.50:1234 (LM Studio default port)") },
            initialText = openAiCompatUrl, singleLine = true, keyboardType = KeyboardType.Uri,
            checkTextValid = { true }
        )
    }
    if (showOpenAiCompatFallbackUrlDialog) {
        TextInputDialog(
            onDismissRequest = { showOpenAiCompatFallbackUrlDialog = false },
            onConfirmed = { openAiCompatFallbackUrl = it; showOpenAiCompatFallbackUrlDialog = false },
            title = { Text("LAN fallback URL") },
            description = { Text("Used automatically when the primary URL is unreachable, e.g. http://192.168.1.50:1234") },
            initialText = openAiCompatFallbackUrl, singleLine = true, keyboardType = KeyboardType.Uri,
            checkTextValid = { true }
        )
    }
    if (showOpenAiCompatKeyDialog) {
        TextInputDialog(
            onDismissRequest = { showOpenAiCompatKeyDialog = false },
            onConfirmed = { openAiCompatKey = it; showOpenAiCompatKeyDialog = false },
            title = { Text("API key (optional)") },
            description = { Text("Most local servers don't need a key. Leave blank if unsure.") },
            initialText = openAiCompatKey, singleLine = true,
            checkTextValid = { true }
        )
    }
    if (showModelDialog) {
        if (step == 5 && ollamaModels.isNotEmpty()) {
            ListPickerDialog(
                onDismissRequest = { showModelDialog = false },
                items = ollamaModels.toList(),
                onItemSelected = { selectedOllamaModel = it; showModelDialog = false },
                selectedItem = selectedOllamaModel.ifBlank { null },
                title = { Text("Select Model") }, getItemName = { it },
                confirmImmediately = false
            )
        } else if (step == 6) {
            ListPickerDialog(
                onDismissRequest = { showModelDialog = false },
                items = wizardCloudModels,
                onItemSelected = {
                    val newProvider = it.second.substringBefore(":")
                    val oldProvider = selectedCloudModel.substringBefore(":")
                    selectedCloudModel = it.second
                    if (oldProvider != newProvider) apiKey = ""
                    showModelDialog = false
                },
                selectedItem = wizardCloudModels.firstOrNull { it.second == selectedCloudModel },
                title = { Text("Select Model") }, getItemName = { it.first },
                confirmImmediately = false
            )
        }
    }
}

@Composable
fun Step0(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.setup_welcome_image), null)
        Row(Modifier.clickable { onClick() }
            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.setup_start_action),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun ModeCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) DeskdropTeal.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ToolbarHintIcon(iconRes: Int, name: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, isOptional: Boolean = false, onClick: () -> Unit) {
    val isEmpty = value.isBlank() || value == "(not set)" || value == "(select)"
    val highlight = isEmpty && !isOptional
    val borderColor = if (highlight) DeskdropTeal else DeskdropTeal.copy(alpha = 0.4f)
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (highlight) 1.5.dp else 1.dp,
            borderColor
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = DeskdropTeal,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isEmpty) "Tap to enter…" else value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEmpty) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface,
                    fontStyle = if (isEmpty) androidx.compose.ui.text.font.FontStyle.Italic
                                else androidx.compose.ui.text.font.FontStyle.Normal
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "EDIT",
                style = MaterialTheme.typography.labelMedium,
                color = DeskdropTeal,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(device = "spec:orientation=landscape,width=400dp,height=780dp")
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
