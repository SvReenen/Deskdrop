// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

data class ButtonRowData(
    @DrawableRes val iconRes: Int,
    val label: String,
    val description: String,
)

data class InlineIcon(
    @DrawableRes val iconRes: Int,
    val placeholder: String,
)

data class TipCardData(
    val title: String,
    val body: String,
    val inlineIcons: List<InlineIcon> = emptyList(),
    val bullets: List<String> = emptyList(),
    val buttonRows: List<ButtonRowData> = emptyList(),
    val iconRow: List<Int> = emptyList(),
    val code: List<String> = emptyList(),
    val footer: String? = null,
)

object TipsData {
    val all: List<TipCardData> = listOf(
        TipCardData(
            title = "Long press every AI button",
            body = "These buttons can all be long pressed to configure their own model and instruction:",
            buttonRows = listOf(
                ButtonRowData(R.drawable.ic_ai_assist, "AI Assist", "Set a custom model and instruction"),
                ButtonRowData(R.drawable.ic_ai_tone, "AI Tone", "Pick a dedicated model for tone rewrites"),
                ButtonRowData(R.drawable.ic_ai_voice, "AI Voice", "Choose a voice-optimised model"),
                ButtonRowData(R.drawable.ic_ai_slot_1, "AI Slot 1\u20134", "Save a prompt, name, and model per slot"),
            ),
        ),
        TipCardData(
            title = "Inline commands",
            body = "Type a command anywhere in your text and\npress {ai_assist} to rewrite your message in place, commands removed.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_assist, "{ai_assist}"),
            ),
            code = listOf(
                "Can you send me the report //formal",
                "yo whats up //casual",
                "Thanks a lot //translate nl",
                "Write an email about next week //slot1",
            ),
        ),
        TipCardData(
            title = "Chain commands",
            body = "Stack multiple commands in one message. They run left to right, each building on the previous result. Fully customizable in AI Settings > Advanced > Prompt Aliases.",
            code = listOf(
                "meeting tomorrow 9am //formal //translate nl",
                "status update //concise //email",
            ),
            footer = "Polish and translate in one shot.",
        ),
        TipCardData(
            title = "Voice input",
            body = "Long press {ai_voice} to choose between Whisper (self-hosted) or Android Speech Recognition. Comes with 6 pre-configured voice modes like Smart, Translate, Formal, and Bullet Points. Fully customizable in AI Settings > Voice.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_voice, "{ai_voice}"),
            ),
        ),
        TipCardData(
            title = "AI Slots: your own presets",
            body = "Four customisable AI buttons on the toolbar. Long press any slot to give it a name, model, and prompt.",
            iconRow = listOf(R.drawable.ic_ai_slot_1, R.drawable.ic_ai_slot_2, R.drawable.ic_ai_slot_3, R.drawable.ic_ai_slot_4),
        ),
        TipCardData(
            title = "Tone chips",
            body = "Tap {ai_tone} to get a row of tone chips below your text. Fully customizable in AI Settings > Advanced > Tone Chips.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_tone, "{ai_tone}"),
            ),
        ),
        TipCardData(
            title = "Cloud fallback",
            body = "Running Ollama and your server goes down? Deskdrop can transparently swap to your cloud model until the server is back. Disabled by default, enable it in AI Settings > General.",
        ),
        TipCardData(
            title = "Clipboard as context",
            body = "Copy something, then tap {ai_clipboard} to use your clipboard as context. Ask things like 'translate this' or 'reply to this' without pasting first.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_clipboard, "{ai_clipboard}"),
            ),
        ),
        TipCardData(
            title = "Conversation mode",
            body = "Tap {ai_conversation} to open a full chat interface. Have a back-and-forth conversation with AI without leaving your current app.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_conversation, "{ai_conversation}"),
            ),
        ),
        TipCardData(
            title = "AI Actions",
            body = "Tap {ai_actions} to run actions like setting reminders, making phone calls, sending texts, or looking up contacts directly from your keyboard.",
            inlineIcons = listOf(
                InlineIcon(R.drawable.ic_ai_actions, "{ai_actions}"),
            ),
        ),
        TipCardData(
            title = "Share to Deskdrop",
            body = "Select text in any app and choose Deskdrop from the menu. Or share images and PDFs to open a chat with the shared content as context.",
        ),
        TipCardData(
            title = "Home screen widgets",
            body = "Add Deskdrop widgets to your home screen for one-tap access to Voice, Chat, and Execute. Available as a combined widget or as individual buttons.",
        ),
        TipCardData(
            title = "Customize your toolbar",
            body = "Not seeing a button? Go to Settings > Toolbar > Select pinned toolbar keys to add or remove buttons from your toolbar.",
        ),
        TipCardData(
            title = "Emoji \uD83D\uDE00",
            body = "Add a dedicated emoji key to your keyboard in Settings > Preferences > Emoji key. Or hold down the comma (,) button and swipe right to open the emoji window.",
        ),
        TipCardData(
            title = "Stay up to date",
            body = "Enable automatic update checks in Settings > About > Auto-check for updates. Or use the Check for updates button on the main settings screen.",
        ),
    )
}

@Composable
fun TipsCarousel() {
    val tips = TipsData.all
    val startPage = remember { (0 until tips.size).random() }
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { tips.size })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp),
            pageSpacing = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            TipCardCompact(tips[page])
        }

        Spacer(Modifier.height(10.dp))

        // Dot indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tips.forEachIndexed { index, _ ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) brandTeal()
                            else brandTeal().copy(alpha = 0.25f)
                        )
                )
            }
        }
    }
}

@Composable
private fun TipCardCompact(tip: TipCardData) {
    val borderColor = brandTeal().copy(alpha = 0.35f)
    val scrollState = rememberScrollState()
    val thumbColor = brandTeal().copy(alpha = 0.55f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = brandSurface(),
    ) {
        Column(
            modifier = Modifier
                .verticalScrollbar(scrollState, thumbColor)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                "DID YOU KNOW?",
                color = brandTeal(),
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tip.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (tip.inlineIcons.isEmpty()) {
                Text(
                    tip.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            } else {
                val annotated = buildAnnotatedString {
                    var remaining = tip.body
                    for (icon in tip.inlineIcons) {
                        val idx = remaining.indexOf(icon.placeholder)
                        if (idx >= 0) {
                            append(remaining.substring(0, idx))
                            appendInlineContent(icon.placeholder, icon.placeholder)
                            remaining = remaining.substring(idx + icon.placeholder.length)
                        }
                    }
                    append(remaining)
                }
                val inlineContent = tip.inlineIcons.associate { icon ->
                    icon.placeholder to InlineTextContent(
                        Placeholder(1.4.em, 1.4.em, PlaceholderVerticalAlign.Center)
                    ) {
                        Image(
                            painter = painterResource(icon.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    inlineContent = inlineContent,
                )
            }
            if (tip.buttonRows.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tip.buttonRows.forEach { row ->
                    ButtonRowItem(row)
                }
            }
            if (tip.iconRow.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    tip.iconRow.forEach { iconRes ->
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            if (tip.bullets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tip.bullets.forEach { b ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "•",
                            color = brandTeal(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp),
                            fontSize = 12.sp,
                        )
                        Text(
                            b,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            if (tip.code.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tip.code.forEach { snippet ->
                    CodeSnippetCompact(snippet)
                }
            }
            if (tip.footer != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tip.footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = brandTeal(),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ButtonRowItem(row: ButtonRowData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(row.iconRes),
            contentDescription = row.label,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            row.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun CodeSnippetCompact(text: String) {
    val bg = if (isSystemInDarkTheme()) Color(0xFF0A1A18) else Color(0xFFF1F8F6)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = brandTeal(),
        )
    }
}

// Draws a subtle thumb on the right edge of a vertically scrollable element.
// Only appears when the content actually overflows (maxValue > 0).
private fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    val max = scrollState.maxValue
    if (max <= 0) return@drawWithContent
    val viewport = size.height
    val totalContent = viewport + max
    val thumbHeight = (viewport * viewport / totalContent).coerceAtLeast(24f)
    val thumbTop = (scrollState.value.toFloat() / max) * (viewport - thumbHeight)
    val thumbWidth = 3.dp.toPx()
    val rightInset = 4.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset(size.width - thumbWidth - rightInset, thumbTop),
        size = Size(thumbWidth, thumbHeight),
    )
}
