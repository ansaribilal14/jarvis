package com.jarvis.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The JARVIS orb: broken sentinel ring + core.
 * Restrained motion: animates ONLY while the agent is working (single effect rule).
 */
@Composable
fun JarvisOrb(active: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "breathe",
    )
    val phase = if (active) sweep else 0f
    val scale = if (active) breathe else 1f

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f * 0.72f * scale

        // core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.15f)),
                center = Offset(cx, cy),
                radius = radius * 0.55f,
            ),
            radius = radius * 0.34f,
            center = Offset(cx, cy),
        )
        // outer broken ring
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(accent, accent.copy(alpha = 0.12f), accent),
                center = Offset(cx, cy),
            ),
            startAngle = phase,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
        // inner arc, counter-rotated when active
        drawArc(
            color = accent.copy(alpha = 0.55f),
            startAngle = -phase * 0.6f + 60f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - radius * 0.68f, cy - radius * 0.68f),
            size = androidx.compose.ui.geometry.Size(radius * 1.36f, radius * 1.36f),
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
