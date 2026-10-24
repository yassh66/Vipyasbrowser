package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.YasCyan
import com.example.ui.theme.YasPurple

/**
 * Distinctive YAS Browser Emblem:
 * A luxury geometric crest with cyber-glassmorphic styling, avoiding duplicated text.
 */
@Composable
fun YASLogoEmblem(
    size: Dp = 48.dp,
    primaryColor: Color = YasCyan,
    secondaryColor: Color = YasPurple,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.22f),
                        secondaryColor.copy(alpha = 0.15f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.8f),
                        secondaryColor.copy(alpha = 0.6f)
                    )
                ),
                shape = RoundedCornerShape(size * 0.28f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.65f)) {
            val w = this.size.width
            val h = this.size.height

            // Dynamic futuristic Y emblem path
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.18f)
                lineTo(w * 0.50f, h * 0.52f)
                lineTo(w * 0.82f, h * 0.18f)
                moveTo(w * 0.50f, h * 0.52f)
                lineTo(w * 0.50f, h * 0.86f)
            }

            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                style = Stroke(width = w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
    }
}

/**
 * Glassmorphic surface modifier helper for elegant translucent cards.
 */
fun Modifier.glassmorphic(
    backgroundColor: Color = Color(0xCC131A2E),
    borderColor: Color = Color(0x3300E5FF),
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .border(borderWidth, borderColor, RoundedCornerShape(cornerRadius))
