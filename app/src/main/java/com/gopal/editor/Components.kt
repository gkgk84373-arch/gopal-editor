package com.gopal.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Card2(modifier: Modifier = Modifier, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.Surface)
            .border(1.dp, G.Line, RoundedCornerShape(18.dp)).padding(pad),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = G.Sub)
}

@Composable
fun Chip2(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> G.Surface; selected -> G.Accent; else -> G.Surface2 }
    val fg = when { !enabled -> G.Sub.copy(alpha = 0.5f); selected -> Color(0xFF00201C); else -> G.Text }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg)
            .border(1.dp, if (selected) G.Accent else G.Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (enabled) G.Grad else androidx.compose.ui.graphics.SolidColor(G.Surface2))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (enabled) Color.White else G.Sub, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, tint: Color = G.Text, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(G.Surface2).border(1.dp, G.Line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (enabled) tint else G.Sub, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = G.Text, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (enabled) tint else G.Sub.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) G.Text else G.Sub.copy(alpha = 0.4f), maxLines = 1)
    }
}

@Composable
fun Dot(color: Color = G.Red, size: Dp = 9.dp) { Box(Modifier.size(size).clip(CircleShape).background(color)) }

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 3.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = G.Text)
            }
        } else Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = G.Text, maxLines = 1)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = G.Sub, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun LangToggle(onLang: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(G.Surface2).padding(3.dp)) {
        listOf("hi" to "हिं", "en" to "EN").forEach { (code, label) ->
            val sel = L.lang == code
            Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (sel) G.Accent else Color.Transparent)
                .clickable { onLang(code) }.padding(horizontal = 12.dp, vertical = 5.dp),
                color = if (sel) Color(0xFF00201C) else G.Sub, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

fun fmtTime(ms: Long, tenths: Boolean = false): String {
    val s = ms / 1000
    return if (tenths) "%02d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100) else "%02d:%02d".format(s / 60, s % 60)
}

val ScreenPad = PaddingValues(horizontal = 16.dp)
