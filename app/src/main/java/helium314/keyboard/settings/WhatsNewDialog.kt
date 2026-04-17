// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R

private val Teal = Color(0xFF1A9E8F)
private val TealDark = Color(0xFF0D6B60)
private val DarkBg = Color(0xFF1A1A2E)
private val CardBg = Color(0xFF16213E)

data class WhatsNewFeature(
    val icon: String,
    val title: String,
    val description: String,
    val screenshotRes: Int? = null,
    val videoRes: Int? = null,
    val inlineIconRes: Int? = null
)

val WHATS_NEW_VERSION = "1.3"

val WHATS_NEW_FEATURES = listOf(
    WhatsNewFeature(
        "\uD83C\uDFA8",
        "Tone Adjustment",
        "Rewrite your text as Formal, Casual, Friendly, Shorter, Longer, fix Grammar, or translate.\n\nTap the [icon] AI Tone button on your toolbar. Customize your chips in Settings > AI > General > Advanced > Tone Chips.",
        videoRes = R.raw.whats_new_tone_video,
        inlineIconRes = R.drawable.ic_ai_tone
    ),
    WhatsNewFeature(
        "\u26A1",
        "Inline Commands",
        "Instantly transform your text with shortcuts like //formal, //grammar, //summarize, //shorten, or //reply.\n\nType your text, add the command at the end, and tap the AI button. Manage your aliases in Settings > AI > General > Advanced > Prompt Aliases.",
        R.drawable.whats_new_aliases
    ),
    WhatsNewFeature(
        "\uD83D\uDD17",
        "Command Chaining",
        "Chain multiple commands in one go. For example: //formal //english first makes your text formal, then translates it.\n\nWorks with any combination of commands."
    ),
    WhatsNewFeature(
        "\uD83D\uDCCB",
        "Clipboard Context",
        "Copy a message you received, type your reply, then tap AI Tone. The AI understands the context and adjusts your reply accordingly.\n\nDismiss the clipboard indicator with the X if you don't need it."
    ),
    WhatsNewFeature(
        "\u2699\uFE0F",
        "Fully Customizable",
        "Create your own //commands and tone chips in Settings > AI > General > Advanced."
    ),
    WhatsNewFeature(
        "\uD83D\uDCF1",
        "v1.3 - Home screen widgets",
        "New individual widgets for Voice, Chat, and Execute. Each with a settings button to configure model and voice mode right from your home screen."
    ),
    WhatsNewFeature(
        "\uD83D\uDD14",
        "v1.3 - Update notifications",
        "Deskdrop can now check for updates automatically. Enable it in Settings > About > Auto-check for updates, or use the Check for updates button on the main screen."
    ),
    WhatsNewFeature(
        "\uD83D\uDCA1",
        "v1.3 - Tips carousel",
        "Discover features with the new tips carousel on the settings screen. Shows a random tip each time you open settings."
    ),
)

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(DarkBg)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header with gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Teal, TealDark)
                                )
                            )
                            .padding(vertical = 28.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "What's New",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "v$WHATS_NEW_VERSION",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Teal glow line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Teal.copy(alpha = 0.3f),
                                        Teal,
                                        Teal.copy(alpha = 0.3f)
                                    )
                                )
                            )
                    )

                    // Feature list
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WHATS_NEW_FEATURES.forEachIndexed { index, feature ->
                            var itemVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(100L + index * 80L)
                                itemVisible = true
                            }
                            AnimatedVisibility(
                                visible = itemVisible,
                                enter = slideInVertically(
                                    initialOffsetY = { it / 2 },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(250))
                            ) {
                                FeatureCard(feature)
                            }
                        }
                    }

                    // Bottom button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Teal
                            )
                        ) {
                            Text(
                                text = "Got it!",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(feature: WhatsNewFeature) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = feature.icon,
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 14.dp, top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (feature.inlineIconRes != null) {
                    val annotated = buildAnnotatedString {
                        val parts = feature.description.split("[icon]")
                        append(parts[0])
                        if (parts.size > 1) {
                            appendInlineContent("icon", "[icon]")
                            append(parts[1])
                        }
                    }
                    val inlineContent = mapOf(
                        "icon" to InlineTextContent(
                            Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.Center)
                        ) {
                            Image(
                                painter = painterResource(id = feature.inlineIconRes),
                                contentDescription = "AI Tone",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    )
                    Text(
                        text = annotated,
                        inlineContent = inlineContent,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                } else {
                    Text(
                        text = feature.description,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Video if available
        if (feature.videoRes != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/${feature.videoRes}")
                            setVideoURI(uri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(540f / 996f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
        // Screenshot if available
        else if (feature.screenshotRes != null) {
            Image(
                painter = painterResource(id = feature.screenshotRes),
                contentDescription = feature.title,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 0.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}
