// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// BinnenshuisAI brand palette
val BrandTeal = Color(0xFF1A9E8F)
val BrandTealDark = Color(0xFF158578)
val BrandTealLight = Color(0xFFD5F0EB)
val BrandTealSubtle = Color(0xFFB0E8DE)
val BrandGradientStart = Color(0xFF1A8A8A)
val BrandGradientEnd = Color(0xFF2DB89A)

// Dark mode adjusted variants
val BrandTealDarkMode = Color(0xFF25BBA9)
val BrandTealSubtleDark = Color(0xFF0D2E2A)
val BrandTealLightDark = Color(0xFF112926)

val BrandGradient = Brush.linearGradient(
    colors = listOf(BrandGradientStart, BrandGradientEnd),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

@Composable
fun brandTeal(): Color = if (isSystemInDarkTheme()) BrandTealDarkMode else BrandTeal

@Composable
fun brandSurface(): Color = if (isSystemInDarkTheme()) BrandTealSubtleDark else BrandTealLight

@Composable
fun brandSurfaceVariant(): Color = if (isSystemInDarkTheme()) BrandTealLightDark else BrandTealSubtle

@Composable
fun brandButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = BrandTeal,
    contentColor = Color.White
)

@Composable
fun brandOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = brandTeal()
)

@Composable
fun brandSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = brandTeal(),
    activeTrackColor = brandTeal(),
    inactiveTrackColor = brandSurfaceVariant()
)

@Composable
fun BrandHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BrandGradient)
            .height(56.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

/**
 * A clearly editable field row: bordered, labelled, with a "Tap to enter…"
 * placeholder when empty and an edit hint on the right. Use this for any AI
 * setting where users may not realise they can type something in.
 */
@Composable
fun EditableFieldRow(
    label: String,
    value: String,
    placeholder: String = "Tap to enter…",
    isSecret: Boolean = false,
    onClick: () -> Unit
) {
    val isEmpty = value.isBlank()
    val displayValue = when {
        isEmpty -> placeholder
        isSecret -> "••••••" + value.takeLast(4)
        else -> value
    }
    val borderColor = brandTeal().copy(alpha = 0.4f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = brandTeal(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEmpty) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface,
                    fontStyle = if (isEmpty) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "EDIT",
                style = MaterialTheme.typography.labelMedium,
                color = brandTeal(),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = brandSurface(),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}
