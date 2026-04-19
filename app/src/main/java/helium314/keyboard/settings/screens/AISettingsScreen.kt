// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp


import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ai.AiServiceSync
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SecureTextInputPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun cloudModelsFor(prefs: android.content.SharedPreferences) =
    AiServiceSync.cloudModelsWithCustom(prefs)

@Composable
fun AISettingsScreen(onClickBack: () -> Unit, onClickModelWizard: () -> Unit = {}) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val scope = rememberCoroutineScope()
    val ollamaModels = remember { mutableStateListOf<String>() }
    val openaiCompatModels = remember { mutableStateListOf<String>() }
    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle) }
    var openaiCompatStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle) }

    val ctx = LocalContext.current
    val ollamaUrl = AiServiceSync.normalizeOllamaUrl(prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL)
    val openaiCompatUrl = AiServiceSync.normalizeOllamaUrl(prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, "") ?: "")

    LaunchedEffect(openaiCompatUrl) {
        if (openaiCompatUrl.isNotBlank()) {
            openaiCompatStatus = ConnectionStatus.Connecting
            withContext(Dispatchers.IO) {
                try {
                    val key = helium314.keyboard.latin.ai.SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
                    val models = AiServiceSync.fetchOpenAiCompatibleModels(openaiCompatUrl, key)
                    openaiCompatModels.clear()
                    openaiCompatModels.addAll(models)
                    openaiCompatStatus = if (models.isNotEmpty()) ConnectionStatus.Connected(models.size)
                    else ConnectionStatus.Failed("No models found")
                } catch (e: Exception) {
                    openaiCompatStatus = ConnectionStatus.Failed(e.message ?: "Unknown error")
                }
            }
        }
    }

    // Auto-fetch Ollama models on screen open
    LaunchedEffect(ollamaUrl) {
        if (ollamaUrl.isNotBlank() && ollamaUrl != "http://localhost:11434") {
            connectionStatus = ConnectionStatus.Connecting
            withContext(Dispatchers.IO) {
                try {
                    val models = AiServiceSync.fetchOllamaModels(ollamaUrl)
                    ollamaModels.clear()
                    ollamaModels.addAll(models)
                    connectionStatus = if (models.isNotEmpty()) ConnectionStatus.Connected(models.size)
                    else ConnectionStatus.Failed("No models found")
                } catch (e: Exception) {
                    connectionStatus = ConnectionStatus.Failed(e.message ?: "Unknown error")
                }
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Local", "Cloud", "Voice", "Sync")
    val teal = brandTeal()

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_ai),
        settings = emptyList(),
        content = {
            Column(Modifier.imePadding()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = teal,
                    edgePadding = 12.dp,
                    indicator = { tabPositions ->
                        if (selectedTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = teal
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            selectedContentColor = teal,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                when (selectedTab) {
                    0 -> GeneralTab(prefs, ollamaModels, openaiCompatModels)
                    1 -> LocalTab(
                        prefs, ollamaModels, openaiCompatModels,
                        connectionStatus, openaiCompatStatus, scope, onClickModelWizard,
                        onConnectionStatusChange = { connectionStatus = it },
                        onOpenAiCompatStatusChange = { openaiCompatStatus = it }
                    )
                    2 -> CloudTab(prefs, ollamaModels)
                    3 -> VoiceTab(prefs, ollamaModels, openaiCompatModels)
                    4 -> SyncTab(prefs, scope)
                }
            }
        }
    )
}

// =============================================================================
// Editable field helpers (clear "tap to type" affordance)
// =============================================================================

@Composable
private fun EditablePrefField(
    prefs: android.content.SharedPreferences,
    prefKey: String,
    label: String,
    placeholder: String = "Tap to enter…",
    dialogDescription: String? = null,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Unspecified,
    singleLine: Boolean = false
) {
    var showDialog by remember { mutableStateOf(false) }
    val current = prefs.getString(prefKey, "") ?: ""
    EditableFieldRow(
        label = label,
        value = current,
        placeholder = placeholder,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        helium314.keyboard.settings.dialogs.TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                prefs.edit { putString(prefKey, it) }
                helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = current,
            title = { Text(label) },
            description = if (dialogDescription == null) null else { { Text(dialogDescription) } },
            singleLine = singleLine,
            keyboardType = keyboardType,
            checkTextValid = { true }
        )
    }
}

private enum class ApiAuthStyle { BEARER, QUERY_PARAM, X_API_KEY }

@Composable
private fun EditableSecretField(
    secretKey: String,
    label: String,
    placeholder: String = "Tap to enter your API key…",
    dialogDescription: String? = null,
    testUrl: String? = null,
    testAuthStyle: ApiAuthStyle = ApiAuthStyle.BEARER
) {
    var showDialog by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(helium314.keyboard.latin.ai.SecureApiKeys.getKey(secretKey)) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    EditableFieldRow(
        label = label,
        value = current,
        placeholder = placeholder,
        isSecret = true,
        onClick = { showDialog = true }
    )
    if (current.isNotBlank() && testUrl != null) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Test connection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    testStatus = "Testing..."
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val fullUrl = if (testAuthStyle == ApiAuthStyle.QUERY_PARAM) testUrl + current.trim() else testUrl
                            val url = java.net.URL(fullUrl)
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "GET"
                            when (testAuthStyle) {
                                ApiAuthStyle.BEARER -> conn.setRequestProperty("Authorization", "Bearer ${current.trim()}")
                                ApiAuthStyle.X_API_KEY -> {
                                    conn.setRequestProperty("x-api-key", current.trim())
                                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                                }
                                else -> {}
                            }
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            val code = conn.responseCode
                            conn.disconnect()
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                testStatus = if (code in 200..299) "\u2705 Connected" else "\u274C Error ($code)"
                            }
                        } catch (e: Exception) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                testStatus = "\u274C ${e.message?.take(40) ?: "Failed"}"
                            }
                        }
                    }
                }
            )
            if (testStatus != null) {
                Text(
                    testStatus!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testStatus!!.startsWith("\u2705")) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error
                )
            }
        }
    }
    if (showDialog) {
        helium314.keyboard.settings.dialogs.TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                val trimmed = it.trim()
                helium314.keyboard.latin.ai.SecureApiKeys.setKey(secretKey, trimmed)
                current = trimmed
                testStatus = null
                helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = current,
            title = { Text(label) },
            description = if (dialogDescription == null) null else { { Text(dialogDescription) } },
            singleLine = true,
            selectAllOnOpen = true,
            checkTextValid = { true }
        )
    }
}

// =============================================================================
// TAB 1: GENERAL
// =============================================================================

@Composable
private fun GeneralTab(
    prefs: android.content.SharedPreferences,
    ollamaModels: List<String>,
    openaiCompatModels: List<String> = emptyList()
) {
    val teal = brandTeal()
    val currentFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER) ?: Defaults.PREF_AI_MODEL_FILTER

    // Shared model list used by both Essentials and Advanced
    val allInlineModels = remember(ollamaModels.size, openaiCompatModels.size, currentFilter) {
        val items = mutableListOf<Pair<String, String>>()
        if (currentFilter != "local") {
            for (m in cloudModelsFor(prefs)) {
                if (!AiServiceSync.hasApiKey(m.second)) continue
                items.add(m)
            }
        }
        if (currentFilter != "cloud") {
            for (m in ollamaModels) items.add("$m (Ollama)" to "ollama:$m")
            for (m in openaiCompatModels) items.add("$m (custom)" to "openai:$m")
        }
        items.toList()
    }
    val currentInlineModel = prefs.getString(Settings.PREF_AI_INLINE_MODEL, Defaults.PREF_AI_INLINE_MODEL) ?: Defaults.PREF_AI_INLINE_MODEL
    val currentInlineItem = allInlineModels.firstOrNull { it.second == currentInlineModel } ?: (currentInlineModel to currentInlineModel)

    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {

        // =====================================================================
        // ESSENTIALS
        // =====================================================================
        Text(
            "Essentials",
            style = MaterialTheme.typography.titleMedium,
            color = teal,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // About me / Lorebook
        EditablePrefField(
            prefs = prefs,
            prefKey = Settings.PREF_AI_LOREBOOK,
            label = stringResource(R.string.ai_lorebook_label),
            placeholder = "Tap to tell the AI about yourself…",
            dialogDescription = "Tell the AI about yourself. This context is included with every request so the AI knows your name, writing style, preferences, etc."
        )
        Text(
            "Tell the AI about yourself. This context is included with every request so the AI knows your name, writing style, preferences, etc.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Conversation model picker
        val currentConversationModel = prefs.getString(Settings.PREF_AI_CONVERSATION_MODEL, Defaults.PREF_AI_CONVERSATION_MODEL) ?: Defaults.PREF_AI_CONVERSATION_MODEL
        val currentConversationItem = allInlineModels.firstOrNull { it.second == currentConversationModel } ?: (currentConversationModel to currentConversationModel)
        var showConversationDialog by remember { mutableStateOf(false) }

        BrandCard {
            Column {
                Text(
                    stringResource(R.string.ai_conversation_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    currentConversationItem.first,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showConversationDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    stringResource(R.string.ai_conversation_model_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showConversationDialog) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showConversationDialog = false },
                items = allInlineModels,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_CONVERSATION_MODEL, it.second) }
                },
                selectedItem = allInlineModels.firstOrNull { it.second == currentConversationModel },
                title = { Text(stringResource(R.string.ai_conversation_model)) },
                getItemName = { it.first }
            )
        }

        // Inline model picker (// instructions)
        var showInlineDialog by remember { mutableStateOf(false) }

        BrandCard {
            Column {
                Text(
                    stringResource(R.string.ai_inline_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    currentInlineItem.first,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showInlineDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    stringResource(R.string.ai_inline_model_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showInlineDialog) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showInlineDialog = false },
                items = allInlineModels,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_INLINE_MODEL, it.second) }
                },
                selectedItem = allInlineModels.firstOrNull { it.second == currentInlineModel },
                title = { Text(stringResource(R.string.ai_inline_model)) },
                getItemName = { it.first }
            )
        }

        // Model filter (Show models)
        val filterItems = listOf(
            stringResource(R.string.ai_model_filter_local) to "local",
            stringResource(R.string.ai_model_filter_cloud) to "cloud",
            stringResource(R.string.ai_model_filter_both) to "both",
        )
        val currentFilterItem = filterItems.firstOrNull { it.second == currentFilter } ?: filterItems[0]
        var showFilterDialog by remember { mutableStateOf(false) }

        BrandCard {
            Column {
                Text(
                    stringResource(R.string.ai_model_filter),
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    currentFilterItem.first,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFilterDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "Choose which models appear in the picker",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showFilterDialog) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showFilterDialog = false },
                items = filterItems,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_MODEL_FILTER, it.second) }
                },
                selectedItem = currentFilterItem,
                title = { Text(stringResource(R.string.ai_model_filter)) },
                getItemName = { it.first }
            )
        }

        // Cloud fallback
        helium314.keyboard.settings.preferences.SwitchPreference(
            name = "Cloud fallback",
            key = Settings.PREF_AI_CLOUD_FALLBACK,
            default = Defaults.PREF_AI_CLOUD_FALLBACK,
            description = "Automatically use cloud if your local model is unavailable. Reverts when the server is back online."
        )

        // =====================================================================
        // ADVANCED (collapsed by default)
        // =====================================================================
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (advancedExpanded) "▲" else "▼",
                color = teal,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AnimatedVisibility(visible = advancedExpanded) {
            Column {
                // Base Model Instruction
                EditablePrefField(
                    prefs = prefs,
                    prefKey = Settings.PREF_AI_INSTRUCTION,
                    label = stringResource(R.string.ai_instruction_label),
                    placeholder = "Tap to set the AI instruction…",
                    dialogDescription = "This instruction only applies to base models. Custom models created via Ollama Model Wizard use their built-in prompt instead."
                )
                Text(
                    "This instruction only applies to base models. Custom models created via Ollama Model Wizard use their built-in prompt instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // Prompt Aliases (collapsible, under Base Model Instruction)
                PromptAliasesSection(prefs, teal)

                // Tone Chips (collapsible)
                ToneChipsSection(prefs, teal)

                // Internet tools
                helium314.keyboard.settings.preferences.SwitchPreference(
                    name = "Internet tools",
                    key = Settings.PREF_AI_ALLOW_NETWORK_TOOLS,
                    default = Defaults.PREF_AI_ALLOW_NETWORK_TOOLS,
                    description = "Web search, links, and weather"
                )

                // Device actions
                helium314.keyboard.settings.preferences.SwitchPreference(
                    name = "Device actions",
                    key = Settings.PREF_AI_ALLOW_ACTIONS,
                    default = Defaults.PREF_AI_ALLOW_ACTIONS,
                    description = "Timers, apps, calendar, calls, and more"
                )

                // MCP / Actions model picker
                val currentMcpModel = prefs.getString(Settings.PREF_AI_MCP_MODEL, Defaults.PREF_AI_MCP_MODEL) ?: Defaults.PREF_AI_MCP_MODEL
                val mcpModelDisplay = if (currentMcpModel.isEmpty()) "Default (${currentInlineItem.first})" else (allInlineModels.firstOrNull { it.second == currentMcpModel }?.first ?: currentMcpModel)
                var showMcpModelDialog by remember { mutableStateOf(false) }

                BrandCard {
                    Column {
                        Text(
                            "MCP / Actions model",
                            style = MaterialTheme.typography.labelMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            mcpModelDisplay,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMcpModelDialog = true }
                                .padding(vertical = 8.dp)
                        )
                        Text(
                            "Model used for tool calling / MCP commands. Some models handle tools much better than others.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showMcpModelDialog) {
                    val mcpItems = listOf("Default (use main model)" to "") + allInlineModels
                    helium314.keyboard.settings.dialogs.ListPickerDialog(
                        onDismissRequest = { showMcpModelDialog = false },
                        items = mcpItems,
                        onItemSelected = {
                            prefs.edit { putString(Settings.PREF_AI_MCP_MODEL, it.second) }
                        },
                        selectedItem = mcpItems.firstOrNull { it.second == currentMcpModel },
                        title = { Text("MCP / Actions model") },
                        getItemName = { it.first }
                    )
                }

                // MCP servers
                McpServersSection(prefs, teal)
            }
        }

    }
}

// ── Tone Chips ──────────────────────────────────────────────────────────

@Composable
private fun ToneChipsSection(prefs: android.content.SharedPreferences, teal: Color) {
    val storedJson = prefs.getString(Settings.PREF_AI_TONE_CHIPS, Defaults.PREF_AI_TONE_CHIPS) ?: "[]"
    val chipsJson = if (storedJson == "[]") Defaults.PREF_AI_TONE_CHIPS else storedJson
    val chips = remember(chipsJson) {
        mutableStateListOf<Pair<String, String>>().also { list ->
            try {
                val arr = org.json.JSONArray(chipsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(obj.getString("name") to obj.getString("prompt"))
                }
            } catch (_: Exception) {}
        }
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var chipsExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { chipsExpanded = !chipsExpanded }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Tone Chips",
                style = MaterialTheme.typography.titleSmall,
                color = teal,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Quick-access buttons in the AI preview panel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Text(
            if (chipsExpanded) "▲" else "▼",
            color = teal,
            style = MaterialTheme.typography.bodyMedium
        )
    }

    AnimatedVisibility(visible = chipsExpanded) {
        Column {
            chips.forEachIndexed { index, (name, prompt) ->
                BrandCard(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editIndex = index },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Text(
                            "\u2715",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable {
                                    chips.removeAt(index)
                                    saveToneChips(prefs, chips)
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("+ Add tone chip")
            }
        }
    }

    if (showAddDialog) {
        ToneChipDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, prompt ->
                chips.add(name to prompt)
                saveToneChips(prefs, chips)
                showAddDialog = false
            }
        )
    }

    if (editIndex >= 0 && editIndex < chips.size) {
        val (editName, editPrompt) = chips[editIndex]
        ToneChipDialog(
            initialName = editName,
            initialPrompt = editPrompt,
            onDismiss = { editIndex = -1 },
            onSave = { name, prompt ->
                chips[editIndex] = name to prompt
                saveToneChips(prefs, chips)
                editIndex = -1
            }
        )
    }
}

private fun saveToneChips(prefs: android.content.SharedPreferences, chips: List<Pair<String, String>>) {
    val arr = org.json.JSONArray()
    for ((name, prompt) in chips) {
        val obj = org.json.JSONObject()
        obj.put("name", name)
        obj.put("prompt", prompt)
        arr.put(obj)
    }
    prefs.edit().putString(Settings.PREF_AI_TONE_CHIPS, arr.toString()).apply()
}

@Composable
private fun ToneChipDialog(
    initialName: String = "",
    initialPrompt: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var prompt by remember { mutableStateOf(initialPrompt) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) "New Tone Chip" else "Edit Tone Chip") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Button label (e.g. Formal)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    placeholder = { Text("Rewrite this text in a formal tone. Return only the rewritten text.") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && prompt.isNotBlank()) onSave(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Prompt Aliases ──────────────────────────────────────────────────────

@Composable
private fun PromptAliasesSection(prefs: android.content.SharedPreferences, teal: Color) {
    val storedJson = prefs.getString(Settings.PREF_AI_PROMPT_ALIASES, Defaults.PREF_AI_PROMPT_ALIASES) ?: "[]"
    val aliasesJson = if (storedJson == "[]") Defaults.PREF_AI_PROMPT_ALIASES else storedJson
    val aliases = remember(aliasesJson) {
        mutableStateListOf<Pair<String, String>>().also { list ->
            try {
                val arr = org.json.JSONArray(aliasesJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(obj.getString("name") to obj.getString("prompt"))
                }
            } catch (_: Exception) {}
        }
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var aliasesExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { aliasesExpanded = !aliasesExpanded }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Prompt Aliases",
                style = MaterialTheme.typography.titleSmall,
                color = teal,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Shortcuts for inline //commands",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Text(
            if (aliasesExpanded) "▲" else "▼",
            color = teal,
            style = MaterialTheme.typography.bodyMedium
        )
    }

    AnimatedVisibility(visible = aliasesExpanded) {
        Column {
            Text(
                "Type //name to use. Chain multiple: //formal //translate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            aliases.forEachIndexed { index, (name, prompt) ->
                BrandCard(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editIndex = index },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "//$name",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Text(
                            "\u2715",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable {
                                    aliases.removeAt(index)
                                    saveAliases(prefs, aliases)
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("+ Add alias")
            }
        }
    }

    if (showAddDialog) {
        PromptAliasDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, prompt ->
                aliases.add(name to prompt)
                saveAliases(prefs, aliases)
                showAddDialog = false
            }
        )
    }

    if (editIndex >= 0 && editIndex < aliases.size) {
        val (editName, editPrompt) = aliases[editIndex]
        PromptAliasDialog(
            initialName = editName,
            initialPrompt = editPrompt,
            onDismiss = { editIndex = -1 },
            onSave = { name, prompt ->
                aliases[editIndex] = name to prompt
                saveAliases(prefs, aliases)
                editIndex = -1
            }
        )
    }
}

private fun saveAliases(prefs: android.content.SharedPreferences, aliases: List<Pair<String, String>>) {
    val arr = org.json.JSONArray()
    for ((name, prompt) in aliases) {
        val obj = org.json.JSONObject()
        obj.put("name", name)
        obj.put("prompt", prompt)
        arr.put(obj)
    }
    prefs.edit().putString(Settings.PREF_AI_PROMPT_ALIASES, arr.toString()).apply()
}

@Composable
private fun PromptAliasDialog(
    initialName: String = "",
    initialPrompt: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var prompt by remember { mutableStateOf(initialPrompt) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) "New Prompt Alias" else "Edit Prompt Alias") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace(" ", "").replace("/", "") },
                    label = { Text("Name (e.g. formal)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    placeholder = { Text("Make this text more formal. Return only the rewritten text.") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && prompt.isNotBlank()) onSave(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// =============================================================================
// TAB 2: LOCAL
// =============================================================================

@Composable
private fun LocalTab(
    prefs: android.content.SharedPreferences,
    ollamaModels: MutableList<String>,
    openaiCompatModels: MutableList<String>,
    connectionStatus: ConnectionStatus,
    openaiCompatStatus: ConnectionStatus,
    scope: kotlinx.coroutines.CoroutineScope,
    onClickModelWizard: () -> Unit,
    onConnectionStatusChange: (ConnectionStatus) -> Unit,
    onOpenAiCompatStatusChange: (ConnectionStatus) -> Unit
) {
    val teal = brandTeal()
    val ctx = LocalContext.current
    val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
    val isLocal = currentModel.startsWith("ollama:")
    val isOnnx = currentModel.startsWith("onnx:")

    val localModelItems = remember(ollamaModels.size, currentModel) {
        val items = mutableListOf<Pair<String, String>>()
        for (model in ollamaModels) {
            items.add(model to "ollama:$model")
        }
        if (currentModel.startsWith("ollama:")) {
            val currentOllamaModel = currentModel.substringAfter("ollama:")
            if (ollamaModels.none { it == currentOllamaModel }) {
                items.add(currentOllamaModel to currentModel)
            }
        }
        if (items.isEmpty()) {
            items.add("(no models found)" to "ollama:gemma3:4b")
        }
        items.toList()
    }

    val openaiCompatModelItems = remember(openaiCompatModels.size, currentModel) {
        val items = mutableListOf<Pair<String, String>>()
        for (model in openaiCompatModels) {
            items.add(model to "openai:$model")
        }
        if (currentModel.startsWith("openai:")) {
            val currentOpenAiModel = currentModel.substringAfter("openai:")
            if (openaiCompatModels.none { it == currentOpenAiModel }) {
                items.add(currentOpenAiModel to currentModel)
            }
        }
        items.toList()
    }

    var localAdvancedExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {

        // =====================================================================
        // ESSENTIALS
        // =====================================================================
        Text(
            "Essentials",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = teal,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
        )
        Text(
            "Connects to your local Ollama server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // Ollama URL
        EditablePrefField(
            prefs = prefs,
            prefKey = Settings.PREF_OLLAMA_URL,
            label = stringResource(R.string.ollama_url),
            placeholder = "Tap to enter your Ollama URL…",
            dialogDescription = "Enter your Tailscale IP or local URL, e.g. http://100.x.x.x:11434",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            singleLine = true
        )

        // Alternate connection
        EditablePrefField(
            prefs = prefs,
            prefKey = Settings.PREF_OLLAMA_URL_FALLBACK,
            label = "Alternate connection (optional)",
            placeholder = "Tap to enter your LAN URL…",
            dialogDescription = "Used automatically when the primary URL is unreachable. Handy if Tailscale is down and you're at home, e.g. http://192.168.1.50:11434",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            singleLine = true
        )

        // Test connection button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    onConnectionStatusChange(ConnectionStatus.Connecting)
                    scope.launch(Dispatchers.IO) {
                        AiServiceSync.invalidateOllamaUrlCache()
                        val primary = AiServiceSync.normalizeOllamaUrl(
                            prefs.getString(Settings.PREF_OLLAMA_URL, Defaults.PREF_OLLAMA_URL) ?: Defaults.PREF_OLLAMA_URL
                        )
                        val fallback = AiServiceSync.normalizeOllamaUrl(
                            prefs.getString(Settings.PREF_OLLAMA_URL_FALLBACK, "") ?: ""
                        )
                        val attempts = mutableListOf<Pair<String, String>>()
                        var success: Pair<String, List<String>>? = null

                        for ((label, url) in listOf("primary" to primary, "fallback" to fallback)) {
                            if (url.isBlank()) continue
                            try {
                                val models = AiServiceSync.fetchOllamaModels(url)
                                if (models.isNotEmpty()) {
                                    success = url to models
                                    break
                                } else {
                                    attempts += url to "no models"
                                }
                            } catch (e: Exception) {
                                attempts += url to (e.message ?: "unknown error")
                            }
                        }

                        if (success != null) {
                            ollamaModels.clear()
                            ollamaModels.addAll(success.second)
                            val usedLabel = if (success.first == primary) "primary" else "fallback"
                            onConnectionStatusChange(
                                ConnectionStatus.Connected(success.second.size, "via $usedLabel: ${success.first}")
                            )
                        } else {
                            val msg = if (attempts.isEmpty()) {
                                "No URL configured"
                            } else {
                                attempts.joinToString("  |  ") { "${it.first} → ${it.second}" }
                            }
                            onConnectionStatusChange(ConnectionStatus.Failed(msg))
                        }
                    }
                },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.ollama_test_connection))
            }
            when (val status = connectionStatus) {
                is ConnectionStatus.Connecting -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = teal)
                    Text(stringResource(R.string.ollama_connecting), style = MaterialTheme.typography.bodyMedium)
                }
                is ConnectionStatus.Connected -> {
                    Column {
                        Text(
                            String.format(stringResource(R.string.ollama_connected), status.modelCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = teal
                        )
                        status.details?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = teal.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                is ConnectionStatus.Failed -> {
                    Text(
                        String.format(stringResource(R.string.ollama_connection_failed), status.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }
        }

        // Default model picker
        var showModelDialog by remember { mutableStateOf(false) }
        BrandCard {
            Column {
                Text(
                    "Default model",
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                val selectedLocal = localModelItems.firstOrNull { it.second == currentModel }
                Text(
                    if (isLocal) (selectedLocal?.first ?: currentModel.substringAfter("ollama:")) else "(select a local model)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "Selected local model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showModelDialog) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showModelDialog = false },
                items = localModelItems,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_MODEL, it.second) }
                },
                selectedItem = localModelItems.firstOrNull { it.second == currentModel },
                title = { Text("Default model") },
                getItemName = { it.first }
            )
        }

        // =====================================================================
        // ADVANCED (collapsed by default)
        // =====================================================================
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { localAdvancedExpanded = !localAdvancedExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (localAdvancedExpanded) "▲" else "▼",
                color = teal,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AnimatedVisibility(visible = localAdvancedExpanded) {
            Column {
                // Ollama Model Wizard
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandGradient)
                        .clickable { onClickModelWizard() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Ollama Model Wizard",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Pull, create, and manage Ollama models",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    Text(">>", color = Color.White, fontWeight = FontWeight.Bold)
                }

                // Other local servers (OpenAI-compatible)
                var openaiCompatExpanded by remember { mutableStateOf(false) }
                var showOpenAiModelDialog by remember { mutableStateOf(false) }
                BrandCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openaiCompatExpanded = !openaiCompatExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Use a different local server",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = teal,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "LM Studio, vLLM, llama.cpp, and other OpenAI-compatible servers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Text(
                                if (openaiCompatExpanded) "\u25B2" else "\u25BC",
                                style = MaterialTheme.typography.labelMedium,
                                color = teal
                            )
                        }
                        if (openaiCompatExpanded) {
                        Spacer(Modifier.height(8.dp))

                        EditablePrefField(
                            prefs = prefs,
                            prefKey = Settings.PREF_OPENAI_COMPAT_URL,
                            label = "Server URL",
                            placeholder = "Tap to enter your server URL…",
                            dialogDescription = "e.g. http://192.168.1.50:1234 (LM Studio default port). Make sure the server is bound to the network, not just localhost.",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            singleLine = true
                        )
                        EditablePrefField(
                            prefs = prefs,
                            prefKey = Settings.PREF_OPENAI_COMPAT_URL_FALLBACK,
                            label = "Alternate connection (optional)",
                            placeholder = "Tap to enter your LAN URL…",
                            dialogDescription = "Used automatically when the primary URL is unreachable. Handy if Tailscale is down and you're at home, e.g. http://192.168.1.50:1234",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            singleLine = true
                        )
                        EditableSecretField(
                            secretKey = Settings.PREF_OPENAI_COMPAT_API_KEY,
                            label = "API key (optional)",
                            placeholder = "Leave blank for local servers",
                            dialogDescription = "Most local servers don't require a key. Only set this if your server is behind authentication."
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    onOpenAiCompatStatusChange(ConnectionStatus.Connecting)
                                    scope.launch(Dispatchers.IO) {
                                        AiServiceSync.invalidateOpenAiCompatUrlCache()
                                        val primary = AiServiceSync.normalizeOllamaUrl(
                                            prefs.getString(Settings.PREF_OPENAI_COMPAT_URL, "") ?: ""
                                        )
                                        val fallback = AiServiceSync.normalizeOllamaUrl(
                                            prefs.getString(Settings.PREF_OPENAI_COMPAT_URL_FALLBACK, "") ?: ""
                                        )
                                        if (primary.isBlank() && fallback.isBlank()) {
                                            onOpenAiCompatStatusChange(ConnectionStatus.Failed("Set the URL first"))
                                            return@launch
                                        }
                                        val key = helium314.keyboard.latin.ai.SecureApiKeys.getKey(Settings.PREF_OPENAI_COMPAT_API_KEY)
                                        val attempts = mutableListOf<Pair<String, String>>()
                                        var success: Pair<String, List<String>>? = null
                                        for ((label, url) in listOf("primary" to primary, "fallback" to fallback)) {
                                            if (url.isBlank()) continue
                                            try {
                                                val models = AiServiceSync.fetchOpenAiCompatibleModels(url, key)
                                                if (models.isNotEmpty()) {
                                                    success = url to models
                                                    break
                                                } else {
                                                    attempts += url to "no models"
                                                }
                                            } catch (e: Exception) {
                                                attempts += url to (e.message ?: "unknown error")
                                            }
                                        }
                                        if (success != null) {
                                            openaiCompatModels.clear()
                                            openaiCompatModels.addAll(success.second)
                                            val usedLabel = if (success.first == primary) "primary" else "fallback"
                                            onOpenAiCompatStatusChange(
                                                ConnectionStatus.Connected(success.second.size, "via $usedLabel: ${success.first}")
                                            )
                                        } else {
                                            onOpenAiCompatStatusChange(
                                                ConnectionStatus.Failed(attempts.joinToString("  |  ") { "${it.first} → ${it.second}" })
                                            )
                                        }
                                    }
                                },
                                colors = brandButtonColors(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Test connection")
                            }
                            when (val status = openaiCompatStatus) {
                                is ConnectionStatus.Connecting -> {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = teal)
                                    Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
                                }
                                is ConnectionStatus.Connected -> {
                                    Column {
                                        Text(
                                            "Connected (${status.modelCount} models)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = teal
                                        )
                                        status.details?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = teal.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                                is ConnectionStatus.Failed -> {
                                    Text(
                                        "Failed: ${status.error}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {}
                            }
                        }

                        // Model picker
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Default model",
                            style = MaterialTheme.typography.labelMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold
                        )
                        val selectedOpenAi = openaiCompatModelItems.firstOrNull { it.second == currentModel }
                        val pickerLabel = when {
                            currentModel.startsWith("openai:") ->
                                selectedOpenAi?.first ?: currentModel.substringAfter("openai:")
                            openaiCompatModelItems.isEmpty() -> "(set the URL and Test connection first)"
                            else -> "(select a model)"
                        }
                        Text(
                            pickerLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = openaiCompatModelItems.isNotEmpty()) {
                                    showOpenAiModelDialog = true
                                }
                                .padding(vertical = 8.dp)
                        )
                        } // end if (openaiCompatExpanded)
                    }
                }
                if (showOpenAiModelDialog) {
                    helium314.keyboard.settings.dialogs.ListPickerDialog(
                        onDismissRequest = { showOpenAiModelDialog = false },
                        items = openaiCompatModelItems,
                        onItemSelected = {
                            prefs.edit { putString(Settings.PREF_AI_MODEL, it.second) }
                        },
                        selectedItem = openaiCompatModelItems.firstOrNull { it.second == currentModel },
                        title = { Text("Default model") },
                        getItemName = { it.first }
                    )
                }

                // On-device models (ONNX)
                OnnxSection(prefs, ctx, scope, teal)

                // Model Presets
                val combinedForLocalTab = remember(ollamaModels.size, openaiCompatModels.size) {
                    val items = mutableListOf<Pair<String, String>>()
                    ollamaModels.forEach { items.add(it to "ollama:$it") }
                    openaiCompatModels.forEach { items.add(it to "openai:$it") }
                    items.addAll(AiServiceSync.cloudModelsWithCustom(prefs))
                    items
                }
                ModelPresetsSection(prefs, teal, combinedForLocalTab)
            }
        }
    }
}

@Composable
private fun OnnxSection(
    prefs: android.content.SharedPreferences,
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    teal: Color
) {
    val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
    val isOnnx = currentModel.startsWith("onnx:")
    var hasModel by remember { mutableStateOf(helium314.keyboard.latin.ai.OnnxInferenceService.hasModelFiles(ctx)) }
    var importStatus by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val modelDir = helium314.keyboard.latin.ai.OnnxInferenceService.getModelDir(ctx)
            var copied = 0
            for (uri in uris) {
                val fileName = getFileName(ctx, uri) ?: continue
                val targetName = when {
                    fileName.startsWith("tokenizer") && fileName.endsWith(".json") -> "tokenizer.json"
                    fileName.contains("encoder") && fileName.endsWith(".onnx") -> "encoder_model.onnx"
                    fileName.contains("decoder") && fileName.contains("merged") && fileName.endsWith(".onnx") -> "decoder_model_merged.onnx"
                    fileName.contains("decoder") && fileName.endsWith(".onnx") -> "decoder_model.onnx"
                    else -> null
                }
                if (targetName != null) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            java.io.File(modelDir, targetName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        copied++
                    } catch (e: Exception) {
                        Log.e("AISettings", "Failed to copy $fileName -> $targetName", e)
                    }
                }
            }
            importStatus = "Imported $copied file(s)"
            hasModel = helium314.keyboard.latin.ai.OnnxInferenceService.hasModelFiles(ctx)
        }
    }

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
        color = brandSurfaceVariant()
    )

    var onnxExpanded by remember { mutableStateOf(false) }
    BrandCard {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onnxExpanded = !onnxExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "On-device (ONNX)",
                        style = MaterialTheme.typography.titleMedium,
                        color = teal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (hasModel) "T5 (on-device)" else "No model loaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text(
                    if (onnxExpanded) "\u25B2" else "\u25BC",
                    style = MaterialTheme.typography.labelMedium,
                    color = teal
                )
            }
            if (onnxExpanded) {
            Spacer(Modifier.height(8.dp))

            if (!isOnnx) {
                OutlinedButton(
                    onClick = { prefs.edit { putString(Settings.PREF_AI_MODEL, "onnx:t5") } },
                    colors = brandOutlinedButtonColors(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) { Text("Activate ONNX") }
            }

            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                colors = brandButtonColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) { Text("Import model files") }

            if (importStatus.isNotBlank()) {
                Text(
                    importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = teal,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Text(
                "Select encoder_model.onnx, decoder_model_merged.onnx, and tokenizer.json from your Downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "Browse T5 ONNX models on HuggingFace",
                style = MaterialTheme.typography.bodySmall,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/models?search=t5+onnx&sort=downloads")))
                    }
            )

            if (hasModel) {
                var loadStatus by remember { mutableStateOf("") }
                OutlinedButton(
                    onClick = {
                        loadStatus = "Loading..."
                        scope.launch(Dispatchers.IO) {
                            val ok = helium314.keyboard.latin.ai.OnnxInferenceService.loadModel(ctx)
                            loadStatus = if (ok) "Model loaded" else "Failed to load"
                        }
                    },
                    colors = brandOutlinedButtonColors(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) { Text("Test load model") }
                if (loadStatus.isNotBlank()) {
                    Text(
                        loadStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadStatus == "Model loaded") teal else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            } // end if (onnxExpanded)
        }
    }
}

// =============================================================================
// TAB 3: CLOUD
// =============================================================================

@Composable
private fun CloudTab(
    prefs: android.content.SharedPreferences,
    ollamaModels: List<String>
) {
    val teal = brandTeal()
    val currentModel = prefs.getString(Settings.PREF_AI_MODEL, Defaults.PREF_AI_MODEL) ?: Defaults.PREF_AI_MODEL
    val isCloud = !currentModel.startsWith("ollama:") && !currentModel.startsWith("onnx:")

    val ctx = LocalContext.current
    var cloudAdvancedExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {

        // =====================================================================
        // ESSENTIALS
        // =====================================================================
        Text(
            "Essentials",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = teal,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
        )
        Text(
            "Add one API key to use cloud models",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // Default cloud model picker
        var showModelDialog by remember { mutableStateOf(false) }
        BrandCard {
            Column {
                Text(
                    "Default cloud model",
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                val currentCloudModels = cloudModelsFor(prefs)
                val selectedCloud = currentCloudModels.firstOrNull { it.second == currentModel }
                Text(
                    if (isCloud) (selectedCloud?.first ?: currentModel) else "(select a cloud model)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "Used when cloud is enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showModelDialog) {
            val currentCloudModels = cloudModelsFor(prefs)
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showModelDialog = false },
                items = currentCloudModels,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_MODEL, it.second) }
                },
                selectedItem = currentCloudModels.firstOrNull { it.second == currentModel },
                title = { Text("Default cloud model") },
                getItemName = { it.first }
            )
        }

        // Groq API key
        EditableSecretField(
            secretKey = Settings.PREF_GROQ_API_KEY,
            label = stringResource(R.string.groq_api_key),
            placeholder = "Paste your API key",
            testUrl = "https://api.groq.com/openai/v1/models"
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            Text(
                "Recommended for quick setup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Get Groq API key",
                style = MaterialTheme.typography.bodySmall,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/")))
                }
            )
        }

        // Gemini API key
        EditableSecretField(
            secretKey = Settings.PREF_GEMINI_API_KEY,
            label = stringResource(R.string.gemini_api_key),
            placeholder = "Paste your API key",
            testUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=",
            testAuthStyle = ApiAuthStyle.QUERY_PARAM
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            Spacer(Modifier.weight(1f))
            Text(
                "Get Gemini API key",
                style = MaterialTheme.typography.bodySmall,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/")))
                }
            )
        }

        // =====================================================================
        // ADVANCED (collapsed by default)
        // =====================================================================
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { cloudAdvancedExpanded = !cloudAdvancedExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (cloudAdvancedExpanded) "▲" else "▼",
                color = teal,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AnimatedVisibility(visible = cloudAdvancedExpanded) {
            Column {
                // OpenRouter API key
                EditableSecretField(
                    secretKey = Settings.PREF_OPENROUTER_API_KEY,
                    label = stringResource(R.string.openrouter_api_key),
                    placeholder = "Paste your API key",
                    testUrl = "https://openrouter.ai/api/v1/models"
                )

                // OpenRouter custom model IDs
                var customModels by remember {
                    mutableStateOf(
                        prefs.getString(Settings.PREF_OPENROUTER_CUSTOM_MODELS, Defaults.PREF_OPENROUTER_CUSTOM_MODELS)
                            ?: Defaults.PREF_OPENROUTER_CUSTOM_MODELS
                    )
                }
                androidx.compose.material3.OutlinedTextField(
                    value = customModels,
                    onValueChange = {
                        customModels = it
                        prefs.edit { putString(Settings.PREF_OPENROUTER_CUSTOM_MODELS, it) }
                    },
                    label = { Text("Custom OpenRouter models") },
                    placeholder = { Text("e.g. anthropic/claude-3.5-sonnet") },
                    supportingText = { Text("One model ID per line. Shows up in model pickers.") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = brandOutlinedTextFieldColors(teal),
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // Anthropic API key
                EditableSecretField(
                    secretKey = Settings.PREF_ANTHROPIC_API_KEY,
                    label = stringResource(R.string.anthropic_api_key),
                    placeholder = "Paste your API key",
                    testUrl = "https://api.anthropic.com/v1/models",
                    testAuthStyle = ApiAuthStyle.X_API_KEY
                )

                // OpenAI API key
                EditableSecretField(
                    secretKey = Settings.PREF_OPENAI_API_KEY,
                    label = stringResource(R.string.openai_api_key),
                    placeholder = "Paste your API key",
                    testUrl = "https://api.openai.com/v1/models"
                )

                // Search providers
                Spacer(Modifier.height(12.dp))
                Text(
                    "Search providers",
                    style = MaterialTheme.typography.titleSmall,
                    color = teal,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                EditableSecretField(
                    secretKey = Settings.PREF_TAVILY_API_KEY,
                    label = stringResource(R.string.tavily_api_key),
                    placeholder = "Paste your API key"
                )
                Text(
                    "Recommended. Search engine built for LLMs. Free key at tavily.com (1000 credits/month).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                EditableSecretField(
                    secretKey = Settings.PREF_BRAVE_SEARCH_API_KEY,
                    label = stringResource(R.string.brave_search_api_key),
                    placeholder = "Paste your API key"
                )
                Text(
                    "Optional fallback. Free key at api.search.brave.com (2000 queries/month).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // Model Presets section — combined list (cloud + cached local)
                val combinedForCloudTab = remember(ollamaModels.size) {
                    val items = mutableListOf<Pair<String, String>>()
                    items.addAll(AiServiceSync.cloudModelsWithCustom(prefs))
                    helium314.keyboard.latin.ai.cachedOllamaModels(prefs).forEach {
                        items.add(it.displayName to it.modelValue)
                    }
                    helium314.keyboard.latin.ai.cachedOpenAiCompatibleModels(prefs).forEach {
                        items.add(it.displayName to it.modelValue)
                    }
                    items
                }
                ModelPresetsSection(prefs, teal, combinedForCloudTab)
            }
        }
    }
}

@Composable
private fun ModelPresetsSection(
    prefs: android.content.SharedPreferences,
    teal: Color,
    availableModels: List<Pair<String, String>>
) {
    var presetsJson by remember { mutableStateOf(prefs.getString(Settings.PREF_AI_CLOUD_PRESETS, Defaults.PREF_AI_CLOUD_PRESETS) ?: "[]") }
    val presets = remember(presetsJson) { parsePresets(presetsJson) }

    fun savePresets(list: List<CloudPreset>) {
        val json = presetsToJson(list)
        prefs.edit { putString(Settings.PREF_AI_CLOUD_PRESETS, json) }
        presetsJson = json
    }

    Text(
        "Model Presets",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = teal,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
    )
    Text(
        "Create named presets that pair a model (cloud, Ollama, or OpenAI-compatible) with a prompt. Assign them to shortcut keys for quick access.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // Existing presets
    for (i in presets.indices) {
        var expanded by remember { mutableStateOf(false) }
        var editName by remember(presetsJson, i) { mutableStateOf(presets[i].name) }
        var editPrompt by remember(presetsJson, i) { mutableStateOf(presets[i].prompt) }
        var showModelPicker by remember { mutableStateOf(false) }
        var editModel by remember(presetsJson, i) { mutableStateOf(presets[i].model) }

        BrandCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            presets[i].name.ifBlank { "Unnamed preset" },
                            style = MaterialTheme.typography.labelMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold
                        )
                        val modelDisplay = availableModels.firstOrNull { it.second == presets[i].model }?.first ?: presets[i].model
                        Text(
                            modelDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        if (expanded) "\u25B2" else "\u25BC",
                        style = MaterialTheme.typography.labelMedium,
                        color = teal
                    )
                }
                if (expanded) {
                    androidx.compose.material3.OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = brandOutlinedTextFieldColors(teal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )

                    // Model picker
                    val modelDisplay = availableModels.firstOrNull { it.second == editModel }?.first ?: editModel
                    OutlinedButton(
                        onClick = { showModelPicker = true },
                        colors = brandOutlinedButtonColors(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Model: $modelDisplay") }

                    androidx.compose.material3.OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        label = { Text("Prompt (optional)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 80.dp, max = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = brandOutlinedTextFieldColors(teal),
                        minLines = 3
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val updated = presets.toMutableList()
                                updated.removeAt(i)
                                savePresets(updated)
                            },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                        Button(
                            onClick = {
                                val updated = presets.toMutableList()
                                updated[i] = presets[i].copy(name = editName, model = editModel, prompt = editPrompt)
                                savePresets(updated)
                                expanded = false
                            },
                            colors = brandButtonColors(),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Save", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        if (showModelPicker) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showModelPicker = false },
                items = availableModels,
                onItemSelected = { editModel = it.second },
                selectedItem = availableModels.firstOrNull { it.second == editModel },
                title = { Text("Choose model") },
                getItemName = { it.first }
            )
        }
    }

    // Add preset button
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        OutlinedButton(
            onClick = {
                val defaultModel = availableModels.firstOrNull()?.second ?: Defaults.PREF_AI_MODEL
                val updated = presets.toMutableList()
                updated.add(CloudPreset(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "",
                    model = defaultModel,
                    prompt = ""
                ))
                savePresets(updated)
            },
            colors = brandOutlinedButtonColors(),
            shape = RoundedCornerShape(8.dp)
        ) { Text("+ Add preset") }
    }
}

// =============================================================================
// TAB 4: VOICE
// =============================================================================

@Composable
private fun VoiceTab(
    prefs: android.content.SharedPreferences,
    ollamaModels: List<String> = emptyList(),
    openaiCompatModels: List<String> = emptyList()
) {
    val teal = brandTeal()
    val currentFilter = prefs.getString(Settings.PREF_AI_MODEL_FILTER, Defaults.PREF_AI_MODEL_FILTER) ?: Defaults.PREF_AI_MODEL_FILTER
    val allModels = remember(ollamaModels.size, openaiCompatModels.size, currentFilter) {
        val items = mutableListOf<Pair<String, String>>()
        if (currentFilter != "local") {
            for (m in cloudModelsFor(prefs)) {
                if (!AiServiceSync.hasApiKey(m.second)) continue
                items.add(m)
            }
        }
        if (currentFilter != "cloud") {
            for (m in ollamaModels) items.add("$m (Ollama)" to "ollama:$m")
            for (m in openaiCompatModels) items.add("$m (custom)" to "openai:$m")
        }
        items.toList()
    }
    val currentVoiceModel = prefs.getString(Settings.PREF_AI_VOICE_MODEL, Defaults.PREF_AI_VOICE_MODEL) ?: Defaults.PREF_AI_VOICE_MODEL
    val currentVoiceItem = allModels.firstOrNull { it.second == currentVoiceModel } ?: (currentVoiceModel to currentVoiceModel)
    var showVoiceModelDialog by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
        BrandCard {
            Column {
                Text(
                    "Default voice model",
                    style = MaterialTheme.typography.labelMedium,
                    color = teal,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (currentVoiceModel.isBlank()) "(uses main model)" else currentVoiceItem.first,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showVoiceModelDialog = true }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "Model used for voice transcription processing. Leave empty to use the main AI model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (showVoiceModelDialog) {
            helium314.keyboard.settings.dialogs.ListPickerDialog(
                onDismissRequest = { showVoiceModelDialog = false },
                items = allModels,
                onItemSelected = {
                    prefs.edit { putString(Settings.PREF_AI_VOICE_MODEL, it.second) }
                },
                selectedItem = allModels.firstOrNull { it.second == currentVoiceModel },
                title = { Text("Default voice model") },
                getItemName = { it.first }
            )
        }
        VoicePromptsSection(prefs)
        TtsSection(prefs, teal)
    }
}

@Composable
private fun TtsSection(prefs: android.content.SharedPreferences, teal: Color) {
    BrandCard {
        Column {
            Text(
                "Text-to-Speech",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = teal
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Enable TTS with the speaker icon in the chat screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))

            val ttsEngine = remember { mutableStateOf(prefs.getString(Settings.PREF_TTS_ENGINE, "android") ?: "android") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        ttsEngine.value = "android"
                        prefs.edit().putString(Settings.PREF_TTS_ENGINE, "android").apply()
                    }
                ) {
                    RadioButton(
                        selected = ttsEngine.value == "android",
                        onClick = {
                            ttsEngine.value = "android"
                            prefs.edit().putString(Settings.PREF_TTS_ENGINE, "android").apply()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = teal)
                    )
                    Text("Android TTS", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        ttsEngine.value = "elevenlabs"
                        prefs.edit().putString(Settings.PREF_TTS_ENGINE, "elevenlabs").apply()
                    }
                ) {
                    RadioButton(
                        selected = ttsEngine.value == "elevenlabs",
                        onClick = {
                            ttsEngine.value = "elevenlabs"
                            prefs.edit().putString(Settings.PREF_TTS_ENGINE, "elevenlabs").apply()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = teal)
                    )
                    Text("ElevenLabs", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (ttsEngine.value == "elevenlabs") {
                Spacer(Modifier.height(8.dp))
                EditableSecretField(
                    secretKey = Settings.PREF_ELEVENLABS_API_KEY,
                    label = "ElevenLabs API key",
                    placeholder = "Paste your API key"
                )
                Text(
                    "Free tier: 10,000 characters/month. Get a key at elevenlabs.io",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                Spacer(Modifier.height(8.dp))

                val voices = listOf(
                    "nPczCjzI2devNBz1zQrb" to "Brian (deep male)",
                    "onwK4e9ZLuTAKqWW03F9" to "Daniel (British broadcaster)",
                    "pNInz6obpgDQGcFmaJgB" to "Adam (dominant male)",
                    "cjVigY5qzO86Huf0OWal" to "Eric (smooth male)",
                    "IKne3meq5aSn9XLyUdCD" to "Charlie (deep, energetic)",
                    "TX3LPaxmHKxFdv7VOQHJ" to "Liam (casual male)",
                    "pqHfZKP75CvOlQylNhV4" to "Bill (wise, mature)",
                    "EXAVITQu4vr4xnSDxMaL" to "Sarah (confident female)",
                    "Xb7hH8MSUJpSbSDYk0k2" to "Alice (British female)",
                    "pFZP5JQG7iQjIQuC4Bku" to "Lily (British actress)",
                    "JBFqnCBsd6RMkjVDRZzb" to "George (British storyteller)",
                )
                val currentVoice = remember { mutableStateOf(prefs.getString(Settings.PREF_ELEVENLABS_VOICE, "nPczCjzI2devNBz1zQrb") ?: "nPczCjzI2devNBz1zQrb") }
                var voiceDropdownOpen by remember { mutableStateOf(false) }
                var customVoiceId by remember { mutableStateOf(prefs.getString("elevenlabs_custom_voice_id", "") ?: "") }

                Text(
                    "Voice",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Box {
                    val allVoices = voices + if (customVoiceId.isNotBlank()) listOf(customVoiceId to "Custom ($customVoiceId)") else emptyList()
                    TextButton(
                        onClick = { voiceDropdownOpen = true },
                        colors = brandButtonColors(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = (allVoices.firstOrNull { it.first == currentVoice.value }?.second ?: "Brian") + "  \u25BE",
                            color = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = voiceDropdownOpen,
                        onDismissRequest = { voiceDropdownOpen = false }
                    ) {
                        allVoices.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    currentVoice.value = id
                                    prefs.edit().putString(Settings.PREF_ELEVENLABS_VOICE, id).apply()
                                    voiceDropdownOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Custom voice ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = customVoiceId,
                    onValueChange = {
                        customVoiceId = it
                        prefs.edit().putString("elevenlabs_custom_voice_id", it).apply()
                    },
                    placeholder = { Text("Paste voice ID from elevenlabs.io", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = teal,
                        cursorColor = teal
                    )
                )
            }
        }
    }
}

// =============================================================================
// VOICE PROMPTS SECTION (moved from original, unchanged)
// =============================================================================

@Composable
private fun VoicePromptsSection(prefs: android.content.SharedPreferences) {
    val teal = brandTeal()
    val builtinNames = helium314.keyboard.latin.settings.Defaults.AI_VOICE_MODE_NAMES
    val defaultPrompts = helium314.keyboard.latin.settings.Defaults.AI_VOICE_MODE_PROMPTS
    val builtinCount = builtinNames.size

    // Load custom modes
    var customModesJson by remember { mutableStateOf(prefs.getString(Settings.PREF_AI_VOICE_CUSTOM_MODES, "[]") ?: "[]") }
    val customModes = remember(customModesJson) {
        try {
            val arr = org.json.JSONArray(customModesJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("name") to obj.getString("prompt")
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveCustomModes(modes: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        for ((name, prompt) in modes) {
            val obj = org.json.JSONObject()
            obj.put("name", name)
            obj.put("prompt", prompt)
            arr.put(obj)
        }
        val json = arr.toString()
        prefs.edit { putString(Settings.PREF_AI_VOICE_CUSTOM_MODES, json) }
        customModesJson = json
    }

    // Speech engine picker
    var selectedEngine by remember { mutableStateOf(prefs.getString(Settings.PREF_AI_VOICE_ENGINE, Defaults.PREF_AI_VOICE_ENGINE) ?: "google") }
    var whisperUrl by remember { mutableStateOf(prefs.getString(Settings.PREF_WHISPER_URL, Defaults.PREF_WHISPER_URL) ?: "") }
    var whisperUrlFallback by remember { mutableStateOf(prefs.getString(Settings.PREF_WHISPER_URL_FALLBACK, Defaults.PREF_WHISPER_URL_FALLBACK) ?: "") }

    BrandCard {
        Column {
            Text(
                "Speech engine",
                style = MaterialTheme.typography.titleMedium,
                color = teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                androidx.compose.material3.FilterChip(
                    selected = selectedEngine == "google",
                    onClick = {
                        selectedEngine = "google"
                        prefs.edit { putString(Settings.PREF_AI_VOICE_ENGINE, "google") }
                    },
                    label = { Text("Google", color = MaterialTheme.colorScheme.onSurface) }
                )
                androidx.compose.material3.FilterChip(
                    selected = selectedEngine == "whisper",
                    onClick = {
                        selectedEngine = "whisper"
                        prefs.edit { putString(Settings.PREF_AI_VOICE_ENGINE, "whisper") }
                    },
                    label = { Text("Whisper (server)", color = MaterialTheme.colorScheme.onSurface) }
                )
            }
            if (selectedEngine == "google") {
                Text(
                    "Uses your device's speech recognition. For offline use, download your language in: Settings > Google > Voice > Offline speech recognition.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "Requires a Whisper-compatible server (e.g. speaches). Leave URL empty to use your Ollama server host on port 8080.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = whisperUrl,
                    onValueChange = {
                        whisperUrl = it
                        prefs.edit { putString(Settings.PREF_WHISPER_URL, it) }
                    },
                    label = { Text("Whisper server URL", color = MaterialTheme.colorScheme.onSurface) },
                    placeholder = { Text("e.g. http://192.168.1.100:8080", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = brandOutlinedTextFieldColors(teal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = whisperUrlFallback,
                    onValueChange = {
                        whisperUrlFallback = it
                        prefs.edit { putString(Settings.PREF_WHISPER_URL_FALLBACK, it) }
                    },
                    label = { Text("Fallback URL (optional)", color = MaterialTheme.colorScheme.onSurface) },
                    placeholder = { Text("e.g. http://192.168.1.100:8080", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = brandOutlinedTextFieldColors(teal),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )

                // Whisper model management
                WhisperModelSection(prefs, teal)
            }
        }
    }

    // Section header
    Text(
        "Voice Prompts",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = teal,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
    )
    Text(
        "Edit prompts per voice mode. Changes are used when voice input is processed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    // Built-in modes
    for (i in 0 until builtinCount) {
        val storedPrompt = prefs.getString("ai_voice_prompt_$i", null)
        var prompt by remember(i) { mutableStateOf(storedPrompt ?: defaultPrompts[i]) }
        var expanded by remember { mutableStateOf(false) }
        val isCustomized = storedPrompt != null && storedPrompt != defaultPrompts[i]

        BrandCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        builtinNames[i],
                        style = MaterialTheme.typography.labelMedium,
                        color = teal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (expanded) "\u25B2" else "\u25BC",
                        style = MaterialTheme.typography.labelMedium,
                        color = teal
                    )
                }
                if (expanded) {
                    androidx.compose.material3.OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 80.dp, max = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 3
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        if (isCustomized) {
                            OutlinedButton(
                                onClick = {
                                    prompt = defaultPrompts[i]
                                    prefs.edit { remove("ai_voice_prompt_$i") }
                                },
                                colors = brandOutlinedButtonColors(),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
                        }
                        Button(
                            onClick = {
                                if (prompt == defaultPrompts[i]) {
                                    prefs.edit { remove("ai_voice_prompt_$i") }
                                } else {
                                    prefs.edit { putString("ai_voice_prompt_$i", prompt) }
                                }
                                expanded = false
                            },
                            colors = brandButtonColors(),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Save", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }

    // Custom modes
    for (i in customModes.indices) {
        var name by remember(customModesJson, i) { mutableStateOf(customModes[i].first) }
        var prompt by remember(customModesJson, i) { mutableStateOf(customModes[i].second) }
        var expanded by remember { mutableStateOf(false) }

        BrandCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name.ifBlank { "Custom ${i + 1}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = teal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (expanded) "\u25B2" else "\u25BC",
                        style = MaterialTheme.typography.labelMedium,
                        color = teal
                    )
                }
                if (expanded) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 80.dp, max = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 3
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val updated = customModes.toMutableList()
                                updated.removeAt(i)
                                saveCustomModes(updated)
                                val currentMode = prefs.getInt(Settings.PREF_AI_VOICE_MODE, 0)
                                val deletedModeIdx = builtinCount + i
                                if (currentMode == deletedModeIdx) {
                                    prefs.edit { putInt(Settings.PREF_AI_VOICE_MODE, 0) }
                                } else if (currentMode > deletedModeIdx) {
                                    prefs.edit { putInt(Settings.PREF_AI_VOICE_MODE, currentMode - 1) }
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                        Button(
                            onClick = {
                                val updated = customModes.toMutableList()
                                updated[i] = name to prompt
                                saveCustomModes(updated)
                                expanded = false
                            },
                            colors = brandButtonColors(),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("Save", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }

    // Add mode button
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        OutlinedButton(
            onClick = {
                val updated = customModes.toMutableList()
                updated.add("Custom ${updated.size + 1}" to "")
                saveCustomModes(updated)
            },
            colors = brandOutlinedButtonColors(),
            shape = RoundedCornerShape(8.dp)
        ) { Text("+ Add voice mode") }
    }
}

// =============================================================================
// WHISPER MODEL SECTION (unchanged from original)
// =============================================================================

@Composable
private fun WhisperModelSection(prefs: android.content.SharedPreferences, teal: Color) {
    val scope = rememberCoroutineScope()
    var selectedModel by remember { mutableStateOf(prefs.getString(Settings.PREF_WHISPER_MODEL, Defaults.PREF_WHISPER_MODEL) ?: Defaults.PREF_WHISPER_MODEL) }
    val installedModels = remember { mutableStateListOf<String>() }
    var isLoading by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }

    val curatedWhisperModels = listOf(
        "Systran/faster-whisper-tiny" to "39MB, fastest, low quality",
        "Systran/faster-whisper-base" to "74MB, fast, basic quality",
        "Systran/faster-whisper-small" to "244MB, good balance of speed and quality",
        "Systran/faster-whisper-medium" to "769MB, high quality",
        "Systran/faster-whisper-large-v1" to "1.5GB, original large model",
        "Systran/faster-whisper-large-v2" to "1.5GB, improved large model",
        "Systran/faster-whisper-large-v3" to "1.5GB, best accuracy",
    )

    fun refreshModels() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            isLoading = true
            try {
                val baseUrl = AiServiceSync.getWhisperBaseUrl(prefs)
                val models = AiServiceSync.fetchWhisperModels(baseUrl)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installedModels.clear()
                    installedModels.addAll(models)
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    statusMessage = "Cannot connect to Whisper server"
                    statusIsError = true
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refreshModels() }

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = brandSurfaceVariant()
    )

    Text(
        "Whisper models",
        style = MaterialTheme.typography.titleSmall,
        color = teal,
        fontWeight = FontWeight.Bold
    )

    if (isLoading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = teal)
            Text("Loading...", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        if (installedModels.isEmpty() && !isDownloading) {
            Text(
                "No models installed. Download one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            for (model in installedModels) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = model == selectedModel,
                            onClick = {
                                selectedModel = model
                                prefs.edit { putString(Settings.PREF_WHISPER_MODEL, model) }
                            },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = teal)
                        )
                        Text(
                            model.removePrefix("Systran/faster-whisper-"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    OutlinedButton(
                        onClick = { deleteConfirm = model },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(width = 70.dp, height = 32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (isDownloading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                color = teal
            )
        }

        if (statusMessage.isNotBlank()) {
            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else teal,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = brandSurfaceVariant()
        )
        Text(
            "Download model",
            style = MaterialTheme.typography.bodySmall,
            color = teal,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        for ((modelId, desc) in curatedWhisperModels) {
            val isInstalled = modelId in installedModels
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        modelId.removePrefix("Systran/faster-whisper-"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (isInstalled) {
                    Text("Installed", style = MaterialTheme.typography.labelSmall, color = teal)
                } else {
                    OutlinedButton(
                        onClick = {
                            isDownloading = true
                            statusMessage = "Downloading ${modelId.removePrefix("Systran/faster-whisper-")}..."
                            statusIsError = false
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val baseUrl = AiServiceSync.getWhisperBaseUrl(prefs)
                                val error = AiServiceSync.downloadWhisperModel(baseUrl, modelId)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isDownloading = false
                                    if (error == null) {
                                        statusMessage = "${modelId.removePrefix("Systran/faster-whisper-")} downloaded successfully"
                                        statusIsError = false
                                        if (installedModels.isEmpty()) {
                                            selectedModel = modelId
                                            prefs.edit { putString(Settings.PREF_WHISPER_MODEL, modelId) }
                                        }
                                        refreshModels()
                                    } else {
                                        statusMessage = "Download failed: $error"
                                        statusIsError = true
                                    }
                                }
                            }
                        },
                        enabled = !isDownloading,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(width = 100.dp, height = 32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        colors = brandOutlinedButtonColors()
                    ) {
                        Text("Download", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Delete confirmation
    deleteConfirm?.let { model ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete model") },
            text = { Text("Delete '$model' from Whisper server?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val toDelete = model
                        deleteConfirm = null
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val baseUrl = AiServiceSync.getWhisperBaseUrl(prefs)
                            val ok = AiServiceSync.deleteWhisperModel(baseUrl, toDelete)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (ok) {
                                    statusMessage = "'$toDelete' deleted"
                                    statusIsError = false
                                    if (selectedModel == toDelete && installedModels.size > 1) {
                                        val next = installedModels.first { it != toDelete }
                                        selectedModel = next
                                        prefs.edit { putString(Settings.PREF_WHISPER_MODEL, next) }
                                    }
                                    refreshModels()
                                } else {
                                    statusMessage = "Error deleting model"
                                    statusIsError = true
                                }
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

// =============================================================================
// CLOUD PRESET DATA MODEL
// =============================================================================

data class CloudPreset(
    val id: String,
    val name: String,
    val model: String,
    val prompt: String
)

private fun parsePresets(json: String): List<CloudPreset> {
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CloudPreset(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                name = obj.optString("name", ""),
                model = obj.optString("model", ""),
                prompt = obj.optString("prompt", "")
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun presetsToJson(presets: List<CloudPreset>): String {
    val arr = org.json.JSONArray()
    for (p in presets) {
        val obj = org.json.JSONObject()
        obj.put("id", p.id)
        obj.put("name", p.name)
        obj.put("model", p.model)
        obj.put("prompt", p.prompt)
        arr.put(obj)
    }
    return arr.toString()
}

// =============================================================================
// HELPERS
// =============================================================================

@Composable
private fun brandOutlinedTextFieldColors(teal: Color) =
    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = teal,
        focusedLabelColor = teal,
        cursorColor = teal
    )

private fun getFileName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
        }
    }
    return uri.lastPathSegment
}

private sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val modelCount: Int, val details: String? = null) : ConnectionStatus()
    data class Failed(val error: String) : ConnectionStatus()
}

fun createAISettings(context: Context) = listOf(
    Setting(context, Settings.PREF_AI_BACKEND, R.string.ai_backend, R.string.ai_backend_summary) { setting ->
        ListPreference(
            setting,
            listOf("Gemini (cloud)" to "gemini", "Ollama (local)" to "ollama"),
            Defaults.PREF_AI_BACKEND
        )
    },
    Setting(context, Settings.PREF_GEMINI_API_KEY, R.string.gemini_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_GEMINI_API_KEY)
    },
    Setting(context, Settings.PREF_GROQ_API_KEY, R.string.groq_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_GROQ_API_KEY)
    },
    Setting(context, Settings.PREF_OPENROUTER_API_KEY, R.string.openrouter_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_OPENROUTER_API_KEY)
    },
    Setting(context, Settings.PREF_ANTHROPIC_API_KEY, R.string.anthropic_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_ANTHROPIC_API_KEY)
    },
    Setting(context, Settings.PREF_OPENAI_API_KEY, R.string.openai_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_OPENAI_API_KEY)
    },
    Setting(context, Settings.PREF_BRAVE_SEARCH_API_KEY, R.string.brave_search_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_BRAVE_SEARCH_API_KEY)
    },
    Setting(context, Settings.PREF_TAVILY_API_KEY, R.string.tavily_api_key) { setting ->
        SecureTextInputPreference(setting, Defaults.PREF_TAVILY_API_KEY)
    },
    Setting(context, Settings.PREF_OLLAMA_URL, R.string.ollama_url) { setting ->
        TextInputPreference(setting, Defaults.PREF_OLLAMA_URL)
    },
    Setting(context, Settings.PREF_OLLAMA_MODEL, R.string.ollama_model) { setting ->
        TextInputPreference(setting, Defaults.PREF_OLLAMA_MODEL)
    },
    Setting(context, Settings.PREF_AI_INSTRUCTION, R.string.ai_instruction_label, R.string.ai_instruction_summary) { setting ->
        TextInputPreference(setting, Defaults.PREF_AI_INSTRUCTION)
    },
    Setting(context, Settings.PREF_AI_LOREBOOK, R.string.ai_lorebook_label, R.string.ai_lorebook_summary) { setting ->
        TextInputPreference(setting, Defaults.PREF_AI_LOREBOOK)
    },
)

@Composable
private fun McpServersSection(
    prefs: android.content.SharedPreferences,
    teal: Color
) {
    // Local state: re-read on each edit via a counter
    var reloadCounter by remember { mutableStateOf(0) }
    val servers = remember(reloadCounter) {
        helium314.keyboard.latin.ai.McpRegistry.listAllServers(prefs)
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<helium314.keyboard.latin.ai.McpRegistry.McpServer?>(null) }

    Text(
        "MCP Servers",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = teal,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
    )
    Text(
        "Model Context Protocol servers expose extra tools (Home Assistant, filesystem, etc.) to the AI. Tools are listed at the start of each chat turn.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    for (server in servers) {
        BrandCard {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            server.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = teal,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            server.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            if (server.transport == helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_SSE)
                                "Legacy SSE" else "Streamable HTTP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = server.enabled,
                        onCheckedChange = { enabled ->
                            helium314.keyboard.latin.ai.McpRegistry.setEnabled(prefs, server.id, enabled)
                            reloadCounter++
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = { editingServer = server },
                        colors = brandOutlinedButtonColors(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Edit", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = {
                            helium314.keyboard.latin.ai.McpRegistry.removeServer(prefs, server.id)
                            reloadCounter++
                        },
                        colors = brandOutlinedButtonColors(),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }

    BrandCard {
        Button(
            onClick = { showAddDialog = true },
            colors = brandButtonColors(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ Add MCP server", style = MaterialTheme.typography.labelSmall) }
    }

    if (showAddDialog || editingServer != null) {
        val existing = editingServer
        var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
        var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
        var token by remember(existing?.id) { mutableStateOf(existing?.token ?: "") }
        var transport by remember(existing?.id) {
            mutableStateOf(existing?.transport ?: helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_STREAMABLE)
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingServer = null
            },
            title = { Text(if (existing != null) "Edit MCP server" else "Add MCP server") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Home Assistant") },
                        singleLine = true,
                        colors = brandOutlinedTextFieldColors(teal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("http://192.168.1.10:3000/mcp") },
                        singleLine = true,
                        colors = brandOutlinedTextFieldColors(teal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Bearer token (optional)") },
                        singleLine = true,
                        colors = brandOutlinedTextFieldColors(teal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Transport",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = transport == helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_STREAMABLE,
                            onClick = { transport = helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_STREAMABLE },
                            label = { Text("Streamable HTTP", color = MaterialTheme.colorScheme.onSurface) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = transport == helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_SSE,
                            onClick = { transport = helium314.keyboard.latin.ai.McpRegistry.TRANSPORT_SSE },
                            label = { Text("Legacy SSE", color = MaterialTheme.colorScheme.onSurface) }
                        )
                    }
                    Text(
                        "Streamable HTTP is the modern transport (spec 2025-03-26). Use Legacy SSE for older servers like Home Assistant's native MCP Server integration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && url.isNotBlank()) {
                            if (existing != null) {
                                helium314.keyboard.latin.ai.McpRegistry.updateServer(
                                    prefs,
                                    existing.copy(
                                        name = name.trim(),
                                        url = url.trim(),
                                        token = token.trim(),
                                        transport = transport
                                    )
                                )
                            } else {
                                helium314.keyboard.latin.ai.McpRegistry.addServer(
                                    prefs, name.trim(), url.trim(), token.trim(),
                                    enabled = true, transport = transport
                                )
                            }
                            reloadCounter++
                            showAddDialog = false
                            editingServer = null
                        }
                    },
                    colors = brandButtonColors()
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showAddDialog = false
                        editingServer = null
                    },
                    colors = brandOutlinedButtonColors()
                ) { Text("Cancel") }
            }
        )
    }
}

// =============================================================================
// Sync Tab
// =============================================================================

@Composable
private fun SyncTab(prefs: android.content.SharedPreferences, scope: kotlinx.coroutines.CoroutineScope) {
    val teal = brandTeal()
    val ctx = LocalContext.current
    var syncStatus by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf(
        helium314.keyboard.latin.ai.SyncManager.getLastSyncTime(ctx)
    ) }
    var isPaired by remember { mutableStateOf(helium314.keyboard.latin.ai.SyncManager.isPaired(ctx)) }
    var discoveredServer by remember { mutableStateOf<helium314.keyboard.latin.ai.DiscoveryListener.DiscoveredServer?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }

    // QR scanner launcher
    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(helium314.keyboard.latin.ai.QrScannerActivity.RESULT_QR_DATA) ?: return@rememberLauncherForActivityResult
            try {
                val obj = org.json.JSONObject(raw)
                val token = obj.optString("token", "")
                val port = obj.optInt("port", 5391)
                val tsIp = obj.optString("tailscale", "")
                val hostname = obj.optString("hostname", "")
                val lanArr = obj.optJSONArray("lan")
                val lanIps = mutableListOf<String>()
                if (lanArr != null) {
                    for (i in 0 until lanArr.length()) {
                        lanArr.optString(i)?.let { if (it.isNotBlank()) lanIps.add(it) }
                    }
                }

                if (token.isNotBlank()) {
                    helium314.keyboard.latin.ai.SyncManager.savePairing(ctx, token, lanIps, tsIp, port, hostname)
                    // Set server URL to first available
                    val url = if (lanIps.isNotEmpty()) "http://${lanIps[0]}:$port"
                              else if (tsIp.isNotBlank()) "http://$tsIp:$port"
                              else ""
                    if (url.isNotBlank()) {
                        prefs.edit().putString(Settings.PREF_SYNC_SERVER_URL, url).apply()
                    }
                    isPaired = true
                    syncStatus = "Paired with $hostname!"
                }
            } catch (_: Exception) {
                syncStatus = "Invalid QR code"
            }
        }
    }
    val pairedHostname = remember {
        ctx.getSharedPreferences("deskdrop_sync", Context.MODE_PRIVATE)
            .getString("paired_hostname", "") ?: ""
    }

    // Auto-discovery bij openen
    LaunchedEffect(Unit) {
        if (!isPaired) {
            isDiscovering = true
            discoveredServer = helium314.keyboard.latin.ai.DiscoveryListener.listenOnce()
            isDiscovering = false
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Desktop Sync", style = MaterialTheme.typography.titleMedium, color = teal)
        Text(
            "Sync conversations with the Deskdrop desktop app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        if (isPaired) {
            // Paired state
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(teal.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("Connected to", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(pairedHostname.ifBlank { "Desktop" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = teal)
            }

            // Sync button
            Button(
                onClick = {
                    isSyncing = true
                    syncStatus = "Syncing..."
                    scope.launch {
                        // Resolve best URL first
                        val url = helium314.keyboard.latin.ai.SyncManager.resolveServerUrl(ctx)
                        if (url == null) {
                            syncStatus = "Error: desktop not reachable"
                            isSyncing = false
                            return@launch
                        }
                        val token = prefs.getString(Settings.PREF_SYNC_TOKEN, "") ?: ""
                        val result = helium314.keyboard.latin.ai.SyncManager.sync(ctx, url, token)
                        isSyncing = false
                        lastSync = helium314.keyboard.latin.ai.SyncManager.getLastSyncTime(ctx)
                        syncStatus = if (result.success)
                            "Synced: ${result.pulled} pulled, ${result.pushed} pushed"
                        else
                            "Error: ${result.error ?: "Unknown"}"
                    }
                },
                enabled = !isSyncing,
                colors = brandButtonColors()
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isSyncing) "Syncing..." else "Sync now")
            }

            // Unpair
            OutlinedButton(
                onClick = {
                    prefs.edit()
                        .putBoolean(Settings.PREF_SYNC_ENABLED, false)
                        .putString(Settings.PREF_SYNC_TOKEN, "")
                        .putString(Settings.PREF_SYNC_SERVER_URL, "")
                        .apply()
                    ctx.getSharedPreferences("deskdrop_sync", Context.MODE_PRIVATE).edit().clear().apply()
                    isPaired = false
                    syncStatus = ""
                },
                colors = brandOutlinedButtonColors()
            ) { Text("Unpair") }

            // Manual settings (collapsed)
            EditablePrefField(prefs = prefs, prefKey = Settings.PREF_SYNC_SERVER_URL, label = "Server URL", placeholder = "auto", singleLine = true)

        } else {
            // Not paired
            if (isDiscovering) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = teal, strokeWidth = 2.dp)
                    Text("Searching for Deskdrop on your network...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            if (discoveredServer != null) {
                val server = discoveredServer!!
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(teal.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column {
                        Text("Deskdrop found!", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = teal)
                        Text(server.hostname, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            // Scan QR button
            Button(
                onClick = {
                    val intent = android.content.Intent(ctx, helium314.keyboard.latin.ai.QrScannerActivity::class.java)
                    qrLauncher.launch(intent)
                },
                colors = brandButtonColors(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Scan QR code from desktop") }

            // Manual pairing
            Spacer(Modifier.height(8.dp))
            Text("Manual setup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

            EditablePrefField(prefs = prefs, prefKey = Settings.PREF_SYNC_SERVER_URL, label = "Server URL", placeholder = "http://192.168.x.x:5391", singleLine = true)
            EditablePrefField(prefs = prefs, prefKey = Settings.PREF_SYNC_TOKEN, label = "Token", placeholder = "Paste from desktop", singleLine = true)

            Button(
                onClick = {
                    val url = prefs.getString(Settings.PREF_SYNC_SERVER_URL, "") ?: ""
                    val token = prefs.getString(Settings.PREF_SYNC_TOKEN, "") ?: ""
                    if (url.isBlank() || token.isBlank()) {
                        syncStatus = "Fill in URL and token"
                        return@Button
                    }
                    syncStatus = "Connecting..."
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { helium314.keyboard.latin.ai.SyncClient.testConnection(url, token) }
                        if (ok) {
                            val lanIps = discoveredServer?.lanIps ?: listOf(java.net.URI(url).host)
                            val tsIp = discoveredServer?.tailscaleIp ?: ""
                            val port = discoveredServer?.port ?: try { java.net.URI(url).port } catch (_: Exception) { 5391 }
                            val hostname = discoveredServer?.hostname ?: ""
                            helium314.keyboard.latin.ai.SyncManager.savePairing(ctx, token, lanIps, tsIp, port, hostname)
                            isPaired = true
                            syncStatus = "Paired!"
                        } else {
                            syncStatus = "Connection failed"
                        }
                    }
                },
                colors = brandButtonColors()
            ) { Text("Connect") }

            // Retry discovery
            if (!isDiscovering && discoveredServer == null) {
                OutlinedButton(
                    onClick = {
                        isDiscovering = true
                        scope.launch {
                            discoveredServer = helium314.keyboard.latin.ai.DiscoveryListener.listenOnce()
                            isDiscovering = false
                        }
                    },
                    colors = brandOutlinedButtonColors()
                ) { Text("Search again") }
            }
        }

        // Status
        if (syncStatus.isNotEmpty()) {
            Text(
                syncStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (syncStatus.startsWith("Error") || syncStatus == "Connection failed") MaterialTheme.colorScheme.error
                    else if (syncStatus.startsWith("Synced") || syncStatus == "Paired!") teal
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (lastSync > 0) {
            val ago = (System.currentTimeMillis() - lastSync) / 1000
            val agoText = when {
                ago < 60 -> "just now"
                ago < 3600 -> "${ago / 60} min ago"
                ago < 86400 -> "${ago / 3600} hours ago"
                else -> "${ago / 86400} days ago"
            }
            Text(
                "Last sync: $agoText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AISettingsScreen(onClickBack = {}, onClickModelWizard = {})
        }
    }
}
