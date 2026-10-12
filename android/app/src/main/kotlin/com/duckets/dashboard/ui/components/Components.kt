package com.duckets.dashboard.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.theme.Muse

@Composable
fun MuseCard(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = if (hero) Muse.HeroShape else Muse.CardShape
    Surface(
        modifier = modifier.border(1.dp, Muse.Border, shape),
        shape = shape,
        color = if (hero) Muse.CardElevated else Muse.Card,
        shadowElevation = if (hero) 8.dp else 4.dp,
    ) {
        Box {
            if (hero) {
                Box(Modifier.fillMaxWidth().height(72.dp).background(Muse.Gloss))
            }
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
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelMedium, color = Muse.TextDim)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, sizeDp: Int = 8) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseAlpha",
    )
    Box(modifier.size(sizeDp.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    Surface(
        modifier = modifier, shape = Muse.ChipShape,
        color = color.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (pulse) PulseDot(color, sizeDp = 7)
            else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Muse.Mist) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W700),
            color = valueColor, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 10.dp), color = Muse.Border.copy(alpha = 0.6f), thickness = 1.dp)
}

@Composable
fun MeterBar(
    fraction: Float, color: Color, modifier: Modifier = Modifier,
    trackColor: Color = Muse.Track, heightDp: Int = 8,
) {
    // NaN.coerceIn() returns NaN — sanitize before it can reach layout.
    val safeFraction = if (fraction.isFinite()) fraction else 0f
    val animated by animateFloatAsState(
        targetValue = safeFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "meter",
    )
    Box(modifier.fillMaxWidth().height(heightDp.dp).clip(Muse.ChipShape).background(trackColor)) {
        if (animated > 0.005f) {
            Box(Modifier.fillMaxWidth(animated).fillMaxSize().clip(Muse.ChipShape)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
        }
    }
}

/** Segmented horizontal bar (CPU split, memory breakdown). Segments are (fraction, color). */
@Composable
fun StackedBar(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier, heightDp: Int = 12) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.001f)
    Row(modifier.fillMaxWidth().height(heightDp.dp).clip(RoundedCornerShape(6.dp)).background(Muse.Track)) {
        segments.forEach { (frac, color) ->
            val w = (frac / total).coerceIn(0f, 1f)
            if (w > 0.002f) Box(Modifier.weight(w).fillMaxSize().background(color))
        }
    }
}

@Composable
fun ArcGauge(
    label: String, valueText: String, fraction: Float, color: Color,
    modifier: Modifier = Modifier, subText: String? = null,
) {
    val safeFraction = if (fraction.isFinite()) fraction else 0f
    val animated by animateFloatAsState(
        targetValue = safeFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "gauge",
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 11.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(color = Muse.Track, startAngle = 150f, sweepAngle = 240f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
                val sweep = 240f * animated
                if (sweep > 0.5f) {
                    drawArc(color = color, startAngle = 150f, sweepAngle = sweep,
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round))
                    // endpoint dot
                    val angleRad = Math.toRadians((150f + sweep).toDouble())
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val r = (size.width - inset * 2) / 2f
                    drawCircle(color = Color.White, radius = 3.dp.toPx(),
                        center = Offset(cx + (r * kotlin.math.cos(angleRad)).toFloat(),
                            cy + (r * kotlin.math.sin(angleRad)).toFloat()))
                }
            }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(valueText, style = MaterialTheme.typography.headlineMedium, color = Muse.Mist, maxLines = 1)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelMedium, color = Muse.Mist, textAlign = TextAlign.Center)
        if (subText != null) {
            Text(subText, style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Sparkline(
    data: List<Float>, color: Color, modifier: Modifier = Modifier,
    maxValue: Float? = null, heightDp: Int = 36,
    description: String? = null,
) {
    // A single non-finite sample would poison max/min and blank the chart.
    val clean = remember(data) { data.filter { it.isFinite() } }
    Canvas(modifier.height(heightDp.dp).fillMaxWidth()
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        if (clean.size < 2) {
            drawLine(color.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = 1.5.dp.toPx())
            return@Canvas
        }
        val dataMax = clean.maxOrNull() ?: 1f
        val dataMin = clean.minOrNull() ?: 0f
        val max = (maxValue ?: dataMax).coerceAtLeast(0.001f)
        // Pad the range so a steady low signal does not glue to the floor.
        val lo = if (maxValue != null) 0f else (dataMin * 0.7f).coerceAtLeast(0f)
        val hi = if (maxValue != null) max else (dataMax * 1.15f).coerceAtLeast(lo + 0.001f)
        val padTop = 3.dp.toPx(); val padBottom = 3.dp.toPx()
        val usableH = size.height - padTop - padBottom
        val stepX = size.width / (clean.size - 1)
        fun yFor(v: Float): Float {
            val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            return padTop + (1f - t) * usableH
        }
        val path = Path()
        clean.forEachIndexed { i, v ->
            val x = i * stepX; val y = yFor(v)
            if (i == 0) path.moveTo(x, y) else {
                val prevX = (i - 1) * stepX; val prevY = yFor(clean[i - 1])
                val midX = (prevX + x) / 2f
                path.cubicTo(midX, prevY, midX, y, x, y)
            }
        }
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f))))
        drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        val lastY = yFor(clean.last())
        drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(size.width - 2.dp.toPx(), lastY))
        drawCircle(color = Color.White, radius = 1.4.dp.toPx(), center = Offset(size.width - 2.dp.toPx(), lastY))
    }
}

/** Shimmering placeholder while the first sample loads. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 3) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val a by transition.animateFloat(0.25f, 0.6f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "shimmerAlpha")
    MuseCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Muse.TextFaint.copy(alpha = a)))
            repeat(lines) {
                Box(Modifier.fillMaxWidth(if (it == lines - 1) 0.6f else 1f).height(12.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Muse.TextFaint.copy(alpha = a * 0.7f)))
            }
        }
    }
}
