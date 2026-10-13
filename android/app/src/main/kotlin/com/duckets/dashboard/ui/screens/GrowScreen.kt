package com.duckets.dashboard.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.CannaAiData
import com.duckets.dashboard.data.GrowComponent
import com.duckets.dashboard.data.GrowDevice
import com.duckets.dashboard.data.GrowEnvironment
import com.duckets.dashboard.data.GrowPort
import com.duckets.dashboard.data.GrowProvider
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardDivider
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtRelativeTime
import com.duckets.dashboard.vm.AnalyzePhase
import com.duckets.dashboard.vm.DashboardUiState
import com.duckets.dashboard.vm.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The Grow tab: the grow room in depth (GET /api/cannaai; bridge
 * 1.13+ for the AC Infinity environment, health components, and
 * photo analysis). The Overview card is the glance, this is the
 * full picture: live environment from the AC Infinity controllers
 * (collected off the Moto G), CannaAI's health broken into its
 * components so "degraded" explains itself, alerts from both
 * systems, a photo-analysis card (pick → POST → the vision model's
 * verdict lands in the history), and the analysis history itself.
 * Empty sections say so honestly instead of vanishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowScreen(state: DashboardUiState, vm: DashboardViewModel, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isPullRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val g = state.cannaai
                if (g == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text("Waiting for the first CannaAI sample from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else if (!g.reachable) {
                    // The environment is the bridge's own collector
                    // data — it stays on screen even when CannaAI's
                    // backend is down; the two are independent.
                    if (g.environment != null) {
                        item { GrowEnvironmentCard(g.environment) }
                    }
                    item { GrowUnreachableCard() }
                } else {
                    if (g.environment != null) {
                        item { GrowEnvironmentCard(g.environment) }
                    }
                    item { GrowStatusCard(g) }
                    item { GrowAlertsCard(g) }
                    item { GrowAnalyzeCard(g, state.grow.analyzePhase, vm) }
                    item { GrowHistoryCard(g) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun GrowUnreachableCard() {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Grow — CannaAI") {
                StatusChip("UNREACHABLE", Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            Text("CannaAI isn't answering the bridge — its backend is down or still starting. Nothing is shown rather than stale guesses.",
                style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
        }
    }
}

private fun cannaStatusColor(status: String?): Color = when (status?.lowercase(Locale.US)) {
    "degraded" -> Muse.Warn
    "healthy", "ok" -> Muse.Good
    else -> Muse.Good
}

private fun providerStatusColor(status: String?): Color = when (status?.lowercase(Locale.US)) {
    "available", "healthy", "ok", "up" -> Muse.Good
    "degraded", "partial" -> Muse.Warn
    "down", "unavailable", "error", "offline" -> Muse.Bad
    else -> Muse.Sky
}

@Composable
private fun GrowStatusCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Grow — CannaAI", subtitle = "grow room, live from CannaAI") {
                StatusChip((g.status ?: "UP").uppercase(Locale.US), cannaStatusColor(g.status), pulse = true)
            }
            if (g.providers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                g.providers.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    ProviderRow(p)
                }
            } else if (g.providersUpCount != null) {
                Spacer(Modifier.height(10.dp))
                KeyValueRow("AI providers", "${g.providersUpCount} up")
            }
            if (g.components.isNotEmpty()) {
                // What the status word is made of: when the chip says
                // DEGRADED, these rows say which part is unhappy.
                Spacer(Modifier.height(14.dp))
                CardDivider()
                Spacer(Modifier.height(12.dp))
                g.components.forEachIndexed { i, c ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    ComponentRow(c)
                }
            }
        }
    }
}

private fun componentStatusColor(status: String?): Color = when (status?.lowercase(Locale.US)) {
    "ok", "available", "healthy", "up", "running" -> Muse.Good
    "unreachable", "down", "error", "offline", "missing" -> Muse.Bad
    "unconfigured", "degraded", "partial", "not configured" -> Muse.Warn
    else -> Muse.Sky
}

@Composable
private fun ComponentRow(c: GrowComponent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.detail != null) {
                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (c.status != null) {
            StatusChip(c.status.uppercase(Locale.US), componentStatusColor(c.status))
        } else {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = Muse.TextFaint)
        }
    }
}

@Composable
private fun ProviderRow(p: GrowProvider) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        if (p.status != null) {
            StatusChip(p.status.uppercase(Locale.US), providerStatusColor(p.status))
        } else {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = Muse.TextFaint)
        }
    }
}

private fun fmtTempF(v: Double?): String? =
    v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f°F", it) }

private fun fmtRhPct(v: Double?): String? =
    v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%%", it) }

private fun fmtVpdKpa(v: Double?): String? =
    v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f kPa", it) }

/**
 * The live room: AC Infinity controller readings the bridge collects
 * off the Moto G. This replaces the old CannaAI sensors card — CannaAI
 * holds no sensors; these are the actual tent numbers.
 */
@Composable
private fun GrowEnvironmentCard(env: GrowEnvironment) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Environment — AC Infinity", subtitle = "Moto G desk phone")
            env.devices.forEachIndexed { i, d ->
                if (i > 0) {
                    Spacer(Modifier.height(14.dp))
                    CardDivider()
                }
                Spacer(Modifier.height(14.dp))
                DeviceClimate(d)
            }
            if (env.devices.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("The collector hasn't reported any controllers yet.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            }
            Spacer(Modifier.height(14.dp))
            val stale = env.fetchedAtEpochSec?.let {
                System.currentTimeMillis() / 1000.0 - it > 30 * 60
            } ?: false
            Text(
                "AC Infinity · updated ${fmtRelativeTime(env.fetchedAtEpochSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (stale) Muse.Warn else Muse.TextFaint,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceClimate(d: GrowDevice) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, color = Muse.Mist,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.id != null) {
                Spacer(Modifier.width(10.dp))
                Text(d.id, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
            }
        }
        val inside = d.inside
        val hasInside = inside != null &&
            (inside.tempF != null || inside.rh != null || inside.vpdKpa != null)
        if (!hasInside) {
            Spacer(Modifier.height(6.dp))
            Text("No readings — the controller's probe is dark.",
                style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
        } else if (inside != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (inside.tempF != null) {
                    Column {
                        Text("INSIDE TEMP", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                        Text(fmtTempF(inside.tempF) ?: "—",
                            style = MaterialTheme.typography.headlineSmall, color = Muse.Mist)
                    }
                }
                if (inside.rh != null) {
                    Column {
                        Text("INSIDE RH", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                        Text(fmtRhPct(inside.rh) ?: "—",
                            style = MaterialTheme.typography.titleLarge, color = Muse.Mist)
                    }
                }
                if (inside.vpdKpa != null) {
                    Column {
                        Text("INSIDE VPD", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                        Text(fmtVpdKpa(inside.vpdKpa) ?: "—",
                            style = MaterialTheme.typography.titleLarge, color = Muse.Mist)
                    }
                }
            }
            val outside = d.outside
            val outsideLine = listOfNotNull(
                fmtTempF(outside?.tempF)?.let { "temp $it" },
                fmtRhPct(outside?.rh)?.let { "RH $it" },
                fmtVpdKpa(outside?.vpdKpa)?.let { "VPD $it" },
            ).joinToString(" · ")
            if (outsideLine.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Outside · $outsideLine",
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
            }
        }
        if (d.ports.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                d.ports.forEach { p -> PortChip(p) }
            }
        }
    }
}

@Composable
private fun PortChip(p: GrowPort) {
    val off = p.level == null
    val label = buildString {
        append(p.name)
        p.mode?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        append(" · ").append(if (off) "off" else p.level.toString())
    }
    val accent = if (off) Muse.TextFaint else Muse.Sky
    Surface(
        shape = Muse.ChipShape,
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (off) Muse.TextFaint else Muse.Mist,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1)
    }
}

private fun alertColor(level: String?): Color = when (level?.lowercase(Locale.US)) {
    "critical", "error", "high" -> Muse.Bad
    "warning", "warn", "medium" -> Muse.Warn
    else -> Muse.Sky
}

@Composable
private fun GrowAlertsCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader(
                "Alerts",
                subtitle = if (g.environment != null) "CannaAI + AC Infinity" else "active in CannaAI right now",
            )
            Spacer(Modifier.height(10.dp))
            val merged = g.alerts.map { it to "CANNAI" } +
                (g.environment?.alerts.orEmpty().map { it to "AC INFINITY" })
            if (merged.isEmpty()) {
                Text("No active alerts — CannaAI and AC Infinity both quiet.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                merged.forEachIndexed { i, (a, source) ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 5.dp).size(8.dp)
                                .clip(CircleShape).background(alertColor(a.level))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(source, style = MaterialTheme.typography.labelSmall,
                                color = if (source == "AC INFINITY") Muse.Sky else Muse.TextFaint)
                            Text(a.text, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist)
                        }
                        if (a.atEpochSec != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(fmtRelativeTime(a.atEpochSec),
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                        }
                    }
                }
            }
        }
    }
}

/** Preview decode for the analyze card: bounds-first, power-of-two
 * sample down to phone-screen size — the upload path does its own
 * EXIF-correct encode in the ViewModel; this is just for looking. */
private suspend fun decodePhotoPreview(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) > 900 && bounds.outHeight / (sample * 2) > 900) {
                sample *= 2
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input, null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()
    }

@Composable
private fun GrowAnalyzeCard(g: CannaAiData, phase: AnalyzePhase, vm: DashboardViewModel) {
    val context = LocalContext.current
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var strain by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickedUri = uri }
    LaunchedEffect(pickedUri) {
        thumbnail = pickedUri?.let { decodePhotoPreview(context, it) }
    }
    val pending = g.analyze?.pending == true
    val sending = phase == AnalyzePhase.Sending
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Analyze a plant photo", subtitle = "CannaAI vision · LM Studio")
            Spacer(Modifier.height(12.dp))
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Picked plant photo",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (pickedUri == null) {
                Text("Pick a photo of a plant and the vision model will diagnose it. It takes a few minutes; the verdict lands at the top of the analysis history below.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                shape = Muse.CardShape,
            ) {
                Text(if (pickedUri == null) "Choose a plant photo" else "Choose a different photo",
                    color = Muse.BlueSoft)
            }
            if (pickedUri != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = strain,
                    onValueChange = { strain = it },
                    label = { Text("Strain (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        pickedUri?.let { vm.submitAnalyze(it, strain.ifBlank { null }, null) }
                    },
                    enabled = !pending && !sending,
                    colors = ButtonDefaults.buttonColors(containerColor = Muse.Blue),
                    shape = Muse.CardShape,
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), color = Muse.Mist, strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sending…")
                    } else {
                        Text("Analyze photo")
                    }
                }
            }
            // Status lines, most specific first: the bridge's own
            // pending flag outranks our local submit bookkeeping.
            when {
                pending -> {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), color = Muse.Sky, strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Analyzing…" +
                                (g.analyze?.startedAtEpochSec?.let {
                                    " started ${fmtRelativeTime(it)}"
                                } ?: "") +
                                " — the vision model takes a few minutes.",
                            style = MaterialTheme.typography.bodyMedium, color = Muse.Sky,
                        )
                    }
                }
                sending -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Sending the photo to the bridge…",
                        style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                }
                phase == AnalyzePhase.Queued -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Analysis queued — the vision model takes a few minutes. The result will appear at the top of the history below.",
                        style = MaterialTheme.typography.bodyMedium, color = Muse.Sky)
                }
                phase == AnalyzePhase.AlreadyRunning -> {
                    Spacer(Modifier.height(12.dp))
                    Text("An analysis is already running — its result will land in the history below.",
                        style = MaterialTheme.typography.bodyMedium, color = Muse.Warn)
                }
                phase is AnalyzePhase.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    Text(phase.message,
                        style = MaterialTheme.typography.bodyMedium, color = Muse.Bad)
                }
                g.analyze?.lastError != null -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Last analysis failed: ${g.analyze.lastError}",
                        style = MaterialTheme.typography.bodyMedium, color = Muse.Bad)
                }
            }
        }
    }
}

/** "85%" / "85.5%" — bridge passes CannaAI's confidence through raw. */
private fun fmtConfidence(v: Double?): String? {
    if (v == null || !v.isFinite()) return null
    return if (v == v.toLong().toDouble()) "${v.toLong()}% confidence"
    else String.format(Locale.US, "%.1f%% confidence", v)
}

/** "health 75" — health_score rides the same raw pass-through. */
private fun fmtHealthScore(v: Double?): String? {
    if (v == null || !v.isFinite()) return null
    return if (v == v.toLong().toDouble()) "health ${v.toLong()}"
    else String.format(Locale.US, "health %.1f", v)
}

@Composable
private fun GrowHistoryCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Analysis history", subtitle = "plant photos, diagnosed by CannaAI")
            Spacer(Modifier.height(10.dp))
            if (g.history.isEmpty()) {
                Text("No analyses yet — the tent-photo pipeline posts here.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                g.history.forEachIndexed { i, e ->
                    if (i > 0) CardDivider()
                    Text(e.text ?: "Analysis",
                        style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
                    val subline = listOfNotNull(
                        e.strain?.takeIf { it.isNotBlank() },
                        fmtConfidence(e.confidence),
                        fmtHealthScore(e.healthScore),
                    ).joinToString(" · ")
                    if (subline.isNotEmpty()) {
                        Text(subline, style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
                    }
                    if (e.atEpochSec != null) {
                        Text("${fmtClock(e.atEpochSec)} · ${fmtRelativeTime(e.atEpochSec)}",
                            style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                    }
                    // When CannaAI had no diagnosis, the bridge falls
                    // back to the notes as the headline — don't print
                    // the same text twice.
                    val notes = e.notes?.takeIf { it.isNotBlank() && it != e.text }
                    if (notes != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                    }
                }
            }
        }
    }
}
