package com.duckets.dashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.theme.Muse

/** The signature Muse card: deep-blue fill, 22dp radius, hairline white border, gloss band. */
@Composable
fun MuseCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.border(1.dp, Muse.Border, Muse.CardShape),
        shape = Muse.CardShape,
        color = Muse.Card,
        shadowElevation = 6.dp,
    ) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Muse.Gloss)
            )
            content()
        }
    }
}

@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Muse.TextDim,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = Muse.ChipShape,
        color = color.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(Muse.ChipShape).background(color))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Muse.Mist) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W700),
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Horizontal meter with a glossy gradient fill. */
@Composable
fun MeterBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = Color(0xFF0A1B33),
) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(Muse.ChipShape)
            .background(trackColor)
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxSize()
                    .clip(Muse.ChipShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.75f), color)
                        )
                    )
            )
        }
    }
}

/**
 * Big arc gauge in the Muse style: 240° sweep, rounded caps, bright value arc
 * over a deep track, value text in the middle.
 */
@Composable
fun ArcGauge(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    subText: String? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(118.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 11.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                // track
                drawArc(
                    color = Color(0xFF0A1B33),
                    startAngle = 150f, sweepAngle = 240f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // value
                val sweep = 240f * fraction.coerceIn(0f, 1f)
                if (sweep > 0.5f) {
                    drawArc(
                        color = color,
                        startAngle = 150f, sweepAngle = sweep,
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Muse.Mist,
                )
                if (subText != null) {
                    Text(subText, style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Muse.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** Minimal sparkline with a soft area fill. */
@Composable
fun Sparkline(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
) {
    Canvas(modifier.height(36.dp).fillMaxWidth()) {
        if (data.size < 2) return@Canvas
        val max = (maxValue ?: (data.maxOrNull() ?: 1f)).coerceAtLeast(0.001f)
        val stepX = size.width / (data.size - 1)
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / max).coerceIn(0f, 1f) * (size.height - 2.dp.toPx())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // area fill
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, color = color.copy(alpha = 0.14f))
        drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}
