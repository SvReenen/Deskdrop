// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.IconOrImage
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAI: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                CompactPreference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    icon = R.drawable.ic_settings_languages,
                    onClick = onClickLanguage,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_preferences),
                    icon = R.drawable.ic_settings_preferences,
                    onClick = onClickPreferences,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_appearance),
                    icon = R.drawable.ic_settings_appearance,
                    onClick = onClickAppearance,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_toolbar),
                    icon = R.drawable.ic_settings_toolbar,
                    onClick = onClickToolbar,
                )
                if (JniUtils.sHaveGestureLib) {
                    CompactPreference(
                        name = stringResource(R.string.settings_screen_gesture),
                        icon = R.drawable.ic_settings_gesture,
                        onClick = onClickGestureTyping,
                    )
                }
                // we don't even show the menu if data gathering phase ended more than 2 weeks ago
                if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS) {
                    CompactPreference(
                        name = stringResource(R.string.gesture_data_screen),
                        icon = R.drawable.ic_settings_gesture,
                        onClick = onClickDataGathering,
                    )
                }
                CompactPreference(
                    name = stringResource(R.string.settings_screen_correction),
                    icon = R.drawable.ic_settings_correction,
                    onClick = onClickTextCorrection,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_secondary_layouts),
                    icon = R.drawable.ic_ime_switcher,
                    onClick = onClickLayouts,
                )
                CompactPreference(
                    name = stringResource(R.string.dictionary_settings_category),
                    icon = R.drawable.ic_dictionary,
                    onClick = onClickDictionaries,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_ai),
                    icon = R.drawable.ic_ai_assist,
                    onClick = onClickAI,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_advanced),
                    icon = R.drawable.ic_settings_advanced,
                    onClick = onClickAdvanced,
                )
                CompactPreference(
                    name = stringResource(R.string.settings_screen_about),
                    icon = R.drawable.ic_settings_about,
                    onClick = onClickAbout,
                )

                Spacer(Modifier.weight(1f))

                CheckForUpdateButton()

                TipsCarousel()
            }
        }
    }
}

@Composable
private fun CompactPreference(
    name: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconOrImage(icon, name, 24)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!description.isNullOrEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NextScreenIcon()
    }
}

@Composable
private fun CheckForUpdateButton() {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<helium314.keyboard.latin.ai.UpdateChecker.UpdateResult?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = brandTeal(),
            )
        } else if (result != null) {
            when {
                result!!.found == true -> {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result!!.downloadUrl))
                            context.startActivity(intent)
                        },
                        colors = brandButtonColors(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.download_version, result!!.version ?: ""))
                    }
                }
                result!!.found == false -> {
                    Text(
                        stringResource(R.string.up_to_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.update_check_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        } else {
            TextButton(onClick = {
                checking = true
                result = null
                helium314.keyboard.latin.ai.UpdateChecker.checkNow(context) { r ->
                    checking = false
                    result = r
                }
            }) {
                Text(stringResource(R.string.check_for_updates), color = brandTeal())
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
