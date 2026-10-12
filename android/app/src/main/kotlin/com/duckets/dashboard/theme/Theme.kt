package com.duckets.dashboard.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Refined Muse system: deep-navy ink, one disciplined blue primary,
// sky for memory, emerald for GPU/earnings. Solid surfaces, hairline
// borders, no gradient text and no glow-on-everything.
object Muse {
    val Blue = Color(0xFF2F7CF6)
    val BlueDeep = Color(0xFF1450B4)
    val BlueSoft = Color(0xFF7AB3FF)
    val Sky = Color(0xFF38BDF8)
    val Emerald = Color(0xFF2FD48C)
    val Ink = Color(0xFF060D18)
    val Night = Color(0xFF0A1626)
    val Card = Color(0xFF0F2342)
    val CardAlt = Color(0xFF0B1A33)
    val CardElevated = Color(0xFF14305E)
    val Mist = Color(0xFFF4F8FF)
    val TextDim = Color(0xFF9FB4D8)
    val TextFaint = Color(0xFF6E86AD)
    val Border = Color(0x2EFFFFFF) // white @ 18%
    val BorderStrong = Color(0x47FFFFFF)
    val Track = Color(0xFF081426)
    val Good = Color(0xFF2FD48C)
    val Warn = Color(0xFFF5A623)
    val Bad = Color(0xFFFF6B6B)
    // Legacy alias kept for call sites; now sky, not violet.
    val Purple = Sky
    val Cyan = Sky

    val CardShape = RoundedCornerShape(20.dp)
    val HeroShape = RoundedCornerShape(24.dp)
    val ChipShape = RoundedCornerShape(50)

    /** Deep navy backdrop: near-solid with a faint top light. */
    val Backdrop: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Color(0xFF0C1E3A), Night, Ink)
        )

    /** Single top highlight band for hero surfaces only. */
    val Gloss: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Color(0x24FFFFFF), Color(0x06FFFFFF), Color(0x00000000))
        )
}

private val DashboardScheme = darkColorScheme(
    primary = Muse.Blue,
    onPrimary = Color.White,
    secondary = Muse.Sky,
    background = Muse.Ink,
    onBackground = Muse.Mist,
    surface = Muse.Card,
    onSurface = Muse.Mist,
    surfaceVariant = Muse.CardAlt,
    onSurfaceVariant = Muse.TextDim,
    outline = Muse.Border,
    error = Muse.Bad,
)

@Composable
fun DashboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashboardScheme,
        typography = DashboardTypography,
        content = content,
    )
}
