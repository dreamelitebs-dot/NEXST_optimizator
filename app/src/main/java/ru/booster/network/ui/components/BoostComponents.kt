package ru.booster.network.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.booster.network.ui.theme.NeonCyan
import ru.booster.network.ui.theme.NeonMagenta
import ru.booster.network.ui.theme.pingColor

/**
 * Cyberpunk "Boost" orb — concentric neon rings, rotating radar sweep while
 * [scanning], pulsing glow when boost is [active]. Tap handled by parent.
 */
@Composable
fun BoostButton(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    scanning: Boolean = false,
    progress: Float = 0f,
    pingMs: Int = -1,
    content: @Composable () -> Unit = {}
) {
    val transition = rememberInfiniteTransition(label = "boost")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (scanning || active) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 4000 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    val ringColor = if (active) NeonCyan else Color(pingColor(pingMs).red,
        pingColor(pingMs).green, pingColor(pingMs).blue, 0.9f)

    Box(modifier = modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().background(Color.Transparent)) {
            val c = center
            val rOuter = size.minDimension / 2f
            val rPulse = rOuter * 0.78f * pulse

            // outer dashed hex-ish ring
            drawCircle(
                color = Color(0xFF1C2740),
                radius = rOuter,
                center = c,
                style = Stroke(width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
            )
            // progress arc (scan sweep fill)
            if (progress > 0f) drawArc(
                brush = Brush.sweepGradient(listOf(NeonMagenta.copy(alpha = 0f), NeonMagenta)),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(c.x - rOuter, c.y - rOuter),
                size = androidx.compose.ui.geometry.Size(rOuter * 2, rOuter * 2),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
            // radar line
            if (scanning || active) {
                val rad = Math.toRadians(sweepAngle.toDouble())
                drawLine(
                    color = ringColor,
                    start = c,
                    end = Offset(c.x + rPulse * kotlin.math.cos(rad).toFloat(),
                                 c.y + rPulse * kotlin.math.sin(rad).toFloat()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            // inner core
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(if (active) NeonCyan.copy(alpha = 0.35f) else Color(0xFF131A2B),
                           Color(0xFF0D1220))
                ),
                radius = rOuter * 0.62f,
                center = c
            )
            drawCircle(color = ringColor, radius = rOuter * 0.62f, center = c,
                style = Stroke(width = 2.dp.toPx()))
        }
        content()
    }
}

/** Real-time latency sparkline for the stats card. */
@Composable
fun PingGraph(
    samples: List<Int>,           // latency ms, -1 == loss
    modifier: Modifier = Modifier,
    maxMs: Int = 200
) {
    Canvas(modifier = modifier) {
        if (samples.isEmpty()) return@Canvas
        val gridColor = Color(0xFF1C2740)
        // horizontal grid lines at 50/100/150 ms
        for (g in 1..3) {
            val y = size.height * g / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val step = size.width / (samples.size - 1).coerceAtLeast(1)
        val points = samples.mapIndexed { i, v ->
            val clamped = v.coerceAtLeast(0)
            Offset(i * step, size.height * (1f - clamped.toFloat() / maxMs.coerceAtLeast(1)))
        }
        // area fill
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points.first().x, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, size.height)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(NeonCyan.copy(alpha = 0.30f), Color.Transparent)))
        // stroke with loss markers
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            },
            color = NeonCyan, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        samples.forEachIndexed { i, v ->
            if (v < 0) drawCircle(NeonMagenta, radius = 3.dp.toPx(), center = points[i])
        }
    }
}

/** Small labelled metric chip. */
@Composable
fun StatChip(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(
            value, style = MaterialTheme.typography.titleMedium, color = valueColor
        )
        androidx.compose.material3.Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
