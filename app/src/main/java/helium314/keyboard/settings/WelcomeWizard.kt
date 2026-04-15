// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
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
    "Llama 4 Scout (Groq)" to "groq:meta-llama/llama-4-scout-17b-16e-instruct",
    "Gemini 2.5 Flash" to "gemini:gemini-2.5-flash",
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
    val activity = ctx as? android.app.Activity
    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = originalOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val prefs = ctx.prefs()
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val setupDone = prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_DESKDROP_SETUP_V2, false)

    fun determineSetupStep(): Int {
        val enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
        val current = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
        Log.d("WelcomeWizard", "determineSetupStep: enabled=$enabled, current=$current, setupDone=$setupDone")
        return when {
            !enabled -> 2
            !current -> 3
            !setupDone -> 4
            else -> -1
        }
    }
    var step by remember { mutableIntStateOf(
        if (setupDone && determineSetupStep() == -1) -1 else 0
    ) }
    var selectedMode by remember { mutableStateOf("cloud") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(step) {
        if (step == 3)
            scope.launch {
                while (step == 3 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = when (selectedMode) {
                    "local" -> if (!setupDone) { selectedMode = "cloud"; 4 } else { finish(); return@launch }
                    else -> 11 // Quick start → Groq setup
                }
            }
    }

    // AI setup state
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
    var groqApiKey by rememberSaveable { mutableStateOf("") }
    var geminiApiKey by rememberSaveable { mutableStateOf("") }
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
            // Save keys
            if (groqApiKey.isNotBlank()) {
                helium314.keyboard.latin.ai.SecureApiKeys.setKey(helium314.keyboard.latin.settings.Settings.PREF_GROQ_API_KEY, groqApiKey)
            }
            if (geminiApiKey.isNotBlank()) {
                helium314.keyboard.latin.ai.SecureApiKeys.setKey(helium314.keyboard.latin.settings.Settings.PREF_GEMINI_API_KEY, geminiApiKey)
            }
            // Auto-select model: prefer Groq (faster), fall back to Gemini
            val autoModel = when {
                groqApiKey.isNotBlank() -> "groq:meta-llama/llama-4-scout-17b-16e-instruct"
                geminiApiKey.isNotBlank() -> "gemini:gemini-2.5-flash"
                else -> selectedCloudModel
            }
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, autoModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_INLINE_MODEL, autoModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_CONVERSATION_MODEL, autoModel)
            editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_VOICE_MODEL, autoModel)
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
        if (step != 0) return
        Column(Modifier.padding(bottom = 4.dp)) {
            if (step == 0) {
                Text(
                    "Use AI in any app\nfrom your keyboard",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = titleColor,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Rewrite, translate, and improve text instantly",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = Color(0xFFEAEAEA),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (step <= 3) {
                Text(
                    stringResource(R.string.setup_steps_title, "Deskdrop"),
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
    fun SetupHeader() {
        if (step in 1..3) {
            val totalSteps = 3
            val progress = step.toFloat() / totalSteps
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = BitmapPainter(
                        BitmapFactory.decodeResource(LocalContext.current.resources, R.mipmap.ic_launcher_foreground)
                            .asImageBitmap()
                    ),
                    contentDescription = "Deskdrop",
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier.weight(1f).height(6.dp)
                        .background(DeskdropTeal.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier.height(6.dp)
                            .fillMaxWidth(progress)
                            .background(DeskdropTeal, RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }

    @Composable
    fun ColumnScope.SetupStep(title: String, description: String, buttonText: String, action: () -> Unit) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = DeskdropTeal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
        Button(
            onClick = action,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
        ) {
            Text(buttonText, style = MaterialTheme.typography.titleMedium)
        }
    }

    @Composable fun steps() {
        if (step == 0)
            Step0 { step = 1 }
        else if (step == 1)
            // Step 1: Quick vs Advanced choice
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SetupHeader()
                Text(
                    "How do you want to start?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DeskdropTeal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can always change this later in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                ModeCard(
                    title = "\u26A1 Quick Start",
                    description = "Start instantly with a free cloud model",
                    detail = "No setup needed",
                    selected = selectedMode == "cloud"
                ) { selectedMode = "cloud" }
                Spacer(Modifier.height(12.dp))
                ModeCard(
                    title = "\uD83D\uDD27 Advanced Setup",
                    description = "Connect your own models and tools",
                    detail = "Ollama, LM Studio, cloud APIs, and more",
                    selected = selectedMode == "local"
                ) { selectedMode = "local" }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val setupStep = determineSetupStep()
                        step = if (setupStep == -1 || setupStep == 4) {
                            if (selectedMode == "local") { selectedMode = "cloud"; 4 } else 11
                        } else setupStep
                    },
                    Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                ) { Text("Continue", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { saveAiAndClose() }, Modifier.fillMaxWidth()) {
                    Text("Decide later")
                }
            }
        else if (step in 2..3)
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SetupHeader()
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    val nextStep = determineSetupStep()
                    step = if (nextStep == -1 || nextStep == 4) {
                        if (selectedMode == "local") { selectedMode = "cloud"; 4 } else 11
                    } else nextStep
                }
                if (step == 2) {
                    SetupStep(
                        "Turn on Deskdrop",
                        "Turn on Deskdrop to use it as your keyboard.",
                        "Open Keyboard Settings"
                    ) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    }
                } else { // step 3
                    SetupStep(
                        "Switch to Deskdrop",
                        "Select Deskdrop as your active keyboard.",
                        "Switch Keyboard"
                    ) { imm.showInputMethodPicker()  }
                }
            }
        else // AI setup steps (4+)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {

                when (step) {

                    // Step 4: Cloud vs Local choice (Advanced users only)
                    4 -> {
                        Text(
                            "How do you want to use Deskdrop?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = DeskdropTeal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You can change this later",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(24.dp))
                        ModeCard(
                            title = "\u2601\uFE0F Cloud",
                            description = "Start instantly with built-in AI",
                            detail = "No setup needed",
                            selected = selectedMode == "cloud"
                        ) { selectedMode = "cloud" }
                        Spacer(Modifier.height(12.dp))
                        ModeCard(
                            title = "\uD83E\uDDE0 Local",
                            description = "Connect your own models",
                            detail = "Maximum privacy and control",
                            hint = "Works great with Ollama",
                            selected = selectedMode == "local"
                        ) { selectedMode = "local" }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = if (selectedMode == "local") 5 else 6 },
                            Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Continue", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 1 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 5: Local (Ollama) config
                    5 -> {
                        Text("Connect to Ollama", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(4.dp))
                        Text("Run AI locally. No data leaves your device.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(20.dp))
                        SettingRow("Ollama URL", ollamaUrl) { showUrlDialog = true }
                        Spacer(Modifier.height(4.dp))
                        Text("Default Ollama address. Change if your server runs elsewhere.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        SettingRow(
                            "Alternate connection (optional)",
                            if (ollamaFallbackUrl.isBlank()) "(not set)" else ollamaFallbackUrl,
                            isOptional = true
                        ) { showOllamaFallbackUrlDialog = true }
                        Spacer(Modifier.height(4.dp))
                        Text("For LAN, Tailscale, or remote access",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(16.dp))
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
                            ) { Text(if (ollamaStatus is OllamaStatus.Connected) "Test again" else stringResource(R.string.ollama_test_connection)) }
                            when (val s = ollamaStatus) {
                                is OllamaStatus.Connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = DeskdropTeal)
                                is OllamaStatus.Connected -> Text("\u2705 Connected to Ollama \u2014 ${s.count} models available", color = DeskdropTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                is OllamaStatus.Failed -> Text("\u274C ${s.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                is OllamaStatus.Idle -> {}
                            }
                        }
                        if (ollamaModels.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SettingRow("Model", selectedOllamaModel.ifBlank { "(select)" }) { showModelDialog = true }
                        }

                        // Collapsible OpenAI-compatible section
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openAiCompatExpanded = !openAiCompatExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (openAiCompatExpanded) "\u25B2" else "\u25BC",
                                color = DeskdropTeal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Use a different local server",
                                style = MaterialTheme.typography.titleSmall,
                                color = DeskdropTeal,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (!openAiCompatExpanded) {
                            Text("LM Studio, vLLM, llama.cpp, KoboldCpp",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.padding(start = 24.dp))
                        }
                        if (openAiCompatExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Connect to other local servers (LM Studio, vLLM, llama.cpp). Default ports: LM Studio 1234, vLLM 8000, llama.cpp 8080.",
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
                                "Alternate connection (optional)",
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
                            onClick = { step = 8 },
                            Modifier.fillMaxWidth(),
                            enabled = selectedOllamaModel.isNotBlank() && ollamaStatus is OllamaStatus.Connected,
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Continue") }
                        if (!(selectedOllamaModel.isNotBlank() && ollamaStatus is OllamaStatus.Connected)) {
                            Spacer(Modifier.height(4.dp))
                            Text("Test your connection to continue",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 6: Cloud config
                    6 -> {
                        var showGroqKeyDialog by rememberSaveable { mutableStateOf(false) }
                        var showGeminiKeyDialog by rememberSaveable { mutableStateOf(false) }
                        var showGroqGuideVideo by rememberSaveable { mutableStateOf(false) }

                        Text("Cloud AI Setup", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(8.dp))
                        Text("Cloud models require a free API key",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(24.dp))

                        // Groq section
                        Text("Groq (recommended)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Fast and free", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        SettingRow("Paste your API key", if (groqApiKey.isBlank()) "(not set)" else "\u2022".repeat(minOf(groqApiKey.length, 20))) { showGroqKeyDialog = true }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Get API key", color = DeskdropTeal) }
                            OutlinedButton(
                                onClick = { showGroqGuideVideo = !showGroqGuideVideo },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (showGroqGuideVideo) "\u25A0 Hide" else "\u25B6 Guide", color = DeskdropTeal) }
                        }
                        if (showGroqGuideVideo) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx2 ->
                                        android.widget.VideoView(ctx2).apply {
                                            val mc = android.widget.MediaController(ctx2)
                                            mc.setAnchorView(this)
                                            setMediaController(mc)
                                            val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${helium314.keyboard.latin.R.raw.onboarding_groq_guide_video}")
                                            setVideoURI(uri)
                                            setOnPreparedListener { mp ->
                                                mp.isLooping = true
                                                mp.setVolume(0f, 0f)
                                                start()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(886f / 1520f).clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Gemini section
                        Text("Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        SettingRow("Paste your API key", if (geminiApiKey.isBlank()) "(not set)" else "\u2022".repeat(minOf(geminiApiKey.length, 20))) { showGeminiKeyDialog = true }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Get Gemini API key", color = DeskdropTeal) }
                        Text(
                            "Gemini availability depends on your country.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Add at least one key to continue. You can add more later in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 8 },
                            Modifier.fillMaxWidth(),
                            enabled = groqApiKey.isNotBlank() || geminiApiKey.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Continue") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = 4 }, Modifier.fillMaxWidth()) { Text("Back") }

                        if (showGroqKeyDialog) {
                            TextInputDialog(
                                onDismissRequest = { showGroqKeyDialog = false },
                                onConfirmed = { groqApiKey = it; showGroqKeyDialog = false },
                                initialText = groqApiKey,
                                title = { Text("Groq API Key") },
                                checkTextValid = { it.isNotBlank() },
                            )
                        }
                        if (showGeminiKeyDialog) {
                            TextInputDialog(
                                onDismissRequest = { showGeminiKeyDialog = false },
                                onConfirmed = { geminiApiKey = it; showGeminiKeyDialog = false },
                                initialText = geminiApiKey,
                                title = { Text("Gemini API Key") },
                                checkTextValid = { it.isNotBlank() },
                            )
                        }
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
                        ) { Text("Continue") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step = 8 },
                            Modifier.fillMaxWidth()
                        ) { Text("Skip for now") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = if (selectedMode == "local") 5 else 6 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 8: Try AI
                    8 -> {
                        Text("Try it out", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(4.dp))
                        Text("See how AI improves your text",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(16.dp))
                        val providerLabel = if (selectedMode == "local") "Ollama ($selectedOllamaModel)"
                        else when {
                            groqApiKey.isNotBlank() -> "Groq (cloud)"
                            geminiApiKey.isNotBlank() -> "Gemini (cloud)"
                            else -> wizardCloudModels.firstOrNull { it.second == selectedCloudModel }?.first ?: selectedCloudModel
                        }
                        Text("AI powered by $providerLabel",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(20.dp))

                        // Input preview
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp)) {
                            Text("\"this app is cool but idk how to say it\"",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }

                        Spacer(Modifier.height(12.dp))

                        var testStatus by remember { mutableStateOf<String?>(null) }
                        var testRunning by remember { mutableStateOf(false) }
                        var testDone by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (testRunning) return@Button
                                testRunning = true
                                testStatus = null
                                val editor = prefs.edit()
                                if (selectedMode == "local" && selectedOllamaModel.isNotBlank()) {
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, "ollama:$selectedOllamaModel")
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_OLLAMA_URL, helium314.keyboard.latin.ai.AiServiceSync.normalizeOllamaUrl(ollamaUrl))
                                } else if (selectedMode == "cloud") {
                                    val autoModel = when {
                                        groqApiKey.isNotBlank() -> "groq:meta-llama/llama-4-scout-17b-16e-instruct"
                                        geminiApiKey.isNotBlank() -> "gemini:gemini-2.5-flash"
                                        else -> selectedCloudModel
                                    }
                                    editor.putString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, autoModel)
                                    if (groqApiKey.isNotBlank()) helium314.keyboard.latin.ai.SecureApiKeys.setKey(helium314.keyboard.latin.settings.Settings.PREF_GROQ_API_KEY, groqApiKey)
                                    if (geminiApiKey.isNotBlank()) helium314.keyboard.latin.ai.SecureApiKeys.setKey(helium314.keyboard.latin.settings.Settings.PREF_GEMINI_API_KEY, geminiApiKey)
                                }
                                editor.apply()
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            helium314.keyboard.latin.ai.AiServiceSync.processWithModelAndInstruction(
                                                "this app is cool but idk how to say it",
                                                prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_AI_MODEL, "") ?: "",
                                                "You are a friendly assistant. Rewrite the user's text to sound more polished, confident, and enthusiastic. Make it sound impressive and positive. Keep it to one short sentence. Reply with only the rewritten text, nothing else.",
                                                prefs
                                            )
                                        }
                                        testStatus = if (result.isNotBlank()) result else null
                                        testDone = result.isNotBlank()
                                        testRunning = false
                                    } catch (e: Exception) {
                                        testStatus = "\u274C ${e.message}"
                                        testRunning = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !testRunning && !testDone,
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) {
                            if (testRunning) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("AI is thinking...", color = Color.White)
                            } else if (testDone) {
                                Text("\u2705 Done", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            } else {
                                Text("Try AI now", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            }
                        }

                        // Result appears after test
                        if (testStatus != null) {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().background(
                                if (testStatus!!.startsWith("\u274C")) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else DeskdropTeal.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp)
                            ).padding(12.dp)) {
                                Column {
                                    if (!testStatus!!.startsWith("\u274C")) {
                                        Text("\u2192 AI result:", style = MaterialTheme.typography.labelSmall, color = DeskdropTeal)
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Text(
                                        testStatus!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (testStatus!!.startsWith("\u274C")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 4
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        if (testDone) {
                            Button(
                                onClick = { step = 9 },
                                Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                            ) { Text("Continue", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                        } else {
                            OutlinedButton(
                                onClick = { step = 9 },
                                Modifier.fillMaxWidth()
                            ) { Text("Skip") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { step = if (selectedMode == "local") 5 else 6 }, Modifier.fillMaxWidth()) { Text("Back") }
                    }

                    // Step 9: Have fun (Advanced done)
                    9 -> {
                        Text(
                            "You're all set!",
                            style = MaterialTheme.typography.headlineMedium,
                            color = DeskdropTeal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open any app and start typing.\nTap the AI button when you need it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEAEAEA),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.havefun_video}")
                                    setVideoURI(uri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        mp.setVolume(0f, 0f)
                                        start()
                                    }
                                    setOnErrorListener { _, _, _ -> true }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { saveAiAndClose() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) {
                            Text("Got it!", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }
                    // Step 10: Quick Start — You're all set
                    10 -> {
                        Text(
                            "You're all set!",
                            style = MaterialTheme.typography.headlineMedium,
                            color = DeskdropTeal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open any app and start typing.\nTap the AI button when you need it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEAEAEA),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.havefun_video}")
                                    setVideoURI(uri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        mp.setVolume(0f, 0f)
                                        start()
                                    }
                                    setOnErrorListener { _, _, _ -> true }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { saveAiAndClose() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) {
                            Text("Got it!", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }

                    // Step 11: Quick Start — Groq API key
                    11 -> {
                        var showQuickGroqKeyDialog by rememberSaveable { mutableStateOf(false) }
                        var showQuickGuideVideo by rememberSaveable { mutableStateOf(false) }

                        Text("Add AI to your keyboard", style = MaterialTheme.typography.titleLarge, color = DeskdropTeal)
                        Spacer(Modifier.height(4.dp))
                        Text("Get a free Groq API key to enable AI features",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(24.dp))

                        SettingRow("Paste your API key", if (groqApiKey.isBlank()) "(not set)" else "\u2022".repeat(minOf(groqApiKey.length, 20))) { showQuickGroqKeyDialog = true }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Get API key", color = DeskdropTeal) }
                            OutlinedButton(
                                onClick = { showQuickGuideVideo = !showQuickGuideVideo },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (showQuickGuideVideo) "\u25A0 Hide" else "\u25B6 Guide", color = DeskdropTeal) }
                        }
                        if (showQuickGuideVideo) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx2 ->
                                        android.widget.VideoView(ctx2).apply {
                                            val mc = android.widget.MediaController(ctx2)
                                            mc.setAnchorView(this)
                                            setMediaController(mc)
                                            val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${helium314.keyboard.latin.R.raw.onboarding_groq_guide_video}")
                                            setVideoURI(uri)
                                            setOnPreparedListener { mp ->
                                                mp.isLooping = true
                                                mp.setVolume(0f, 0f)
                                                start()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(886f / 1520f).clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { step = 10 },
                            Modifier.fillMaxWidth().height(52.dp),
                            enabled = groqApiKey.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
                        ) { Text("Continue", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step = 10 },
                            Modifier.fillMaxWidth()
                        ) { Text("Skip for now") }

                        if (showQuickGroqKeyDialog) {
                            TextInputDialog(
                                onDismissRequest = { showQuickGroqKeyDialog = false },
                                onConfirmed = { groqApiKey = it; showQuickGroqKeyDialog = false },
                                initialText = groqApiKey,
                                title = { Text("Groq API Key") },
                                checkTextValid = { it.isNotBlank() },
                            )
                        }
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
                // Logo overlay top-left
                Image(
                    painter = BitmapPainter(
                        BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher_foreground)
                            .asImageBitmap()
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).align(Alignment.TopStart)
                )
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
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.onboarding_video}")
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        mp.setVolume(0f, 0f)
                        start()
                    }
                    setOnErrorListener { _, _, _ -> true }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(886f / 1750f)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DeskdropTeal)
        ) {
            Text("Try it now!", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
private fun ModeCard(title: String, description: String, detail: String, hint: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) DeskdropTeal.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = DeskdropTeal, fontWeight = FontWeight.Medium)
            }
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
