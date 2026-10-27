package com.gopal.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object G {
    val Bg = Color(0xFF0B0B0E)
    val Surface = Color(0xFF16161B)
    val Surface2 = Color(0xFF202027)
    val Line = Color(0xFF2C2C35)
    val Text = Color(0xFFF4F4F6)
    val Sub = Color(0xFF9A9AA6)
    val Accent = Color(0xFF2CE5D0)
    val Accent2 = Color(0xFF7C5CFF)
    val Red = Color(0xFFFF3B5C)
    val Ok = Color(0xFF35D07F)
    val Warn = Color(0xFFFFB020)
    val VideoTrack = Color(0xFF2A3550)
    val AudioTrack = Color(0xFF1F5E4A)
    val TextTrack = Color(0xFF6A3E8F)
    val Grad = Brush.linearGradient(listOf(Accent2, Accent))
}

val Mukta = FontFamily(
    Font(R.font.mukta_regular, FontWeight.Normal),
    Font(R.font.mukta_bold, FontWeight.Bold),
    Font(R.font.mukta_extrabold, FontWeight.ExtraBold)
)

@Composable
fun GopalTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.m() = copy(fontFamily = Mukta)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = G.Accent, onPrimary = Color(0xFF00201C), secondary = G.Accent2,
            background = G.Bg, surface = G.Surface, surfaceVariant = G.Surface2, onSurface = G.Text,
            onSurfaceVariant = G.Sub, outline = G.Line, error = G.Red
        ),
        typography = Typography(
            displaySmall = base.displaySmall.m(), headlineSmall = base.headlineSmall.m().copy(fontWeight = FontWeight.ExtraBold),
            titleLarge = base.titleLarge.m().copy(fontWeight = FontWeight.Bold), titleMedium = base.titleMedium.m().copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.m().copy(fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.m(), bodyMedium = base.bodyMedium.m(), bodySmall = base.bodySmall.m(),
            labelLarge = base.labelLarge.m().copy(fontWeight = FontWeight.Bold), labelMedium = base.labelMedium.m(), labelSmall = base.labelSmall.m().copy(fontSize = 11.sp)
        ),
        content = content
    )
}
