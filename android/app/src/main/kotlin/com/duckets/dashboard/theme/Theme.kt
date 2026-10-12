package com.duckets.dashboard.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Muse Companion visual system, carried over 1:1:
// Facebook blue (#1877F2) primary, deep-navy ink surfaces, glossy 22dp cards.
object Muse {
    val Blue = Color(0xFF1877F2)
    val BlueDeep = Color(0xFF0A3F86)
    val BlueSoft = Color(0xFF3D8BFF)
    val Ink = Color(0xFF07101C)
    val Night = Color(0xFF0C1828)
    val Card = Color(0xFF14305A)
    val CardAlt = Color(0xFF13233C)
    val Mist = Color(0xFFF4F8FF)
    val TextDim = Color(0xFF9FB4D8)
    val TextFaint = Color(0xFF61779C)
    val Border = Color(0x38FFFFFF) // white @ 22%
    val Good = Color(0xFF2FD574)
    val Warn = Color(0xFFFFB02E)
    val Bad = Color(0xFFFF5C5C)
    val Purple = Color(0xFF9A6BFF)
    val Cyan = Color(0xFF39C6F3)

    val CardShape = RoundedCornerShape(22.dp)
    val ChipShape = RoundedCornerShape(50)

    /** Deep navy backdrop gradient, top-lit like the Muse app. */
    val Backdrop: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Color(0xFF0E2142), Night, Ink, Color(0xFF04080F))
        )

    /** Subtle gloss band used on hero cards. */
    val Gloss: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Color(0x2EFFFFFF), Color(0x08FFFFFF), Color(0x00000000))
        )
}

private val DashboardScheme = darkColorScheme(
    primary = Muse.Blue,
    onPrimary = Color.White,
    secondary = Muse.BlueSoft,
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
