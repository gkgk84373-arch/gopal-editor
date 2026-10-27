package com.gopal.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the encoder check found, per resolution (computed once on the device). */
data class EncoderSupport(val ok720: Boolean = true, val ok1080: Boolean = true, val ok4k: Boolean = false, val label4k: String? = null)

@Composable
fun ExportScreen(
    tl: Timeline,
    settings: ExportSettings,
    support: EncoderSupport,
    ui: ExportUi,
    onSettings: (ExportSettings) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("Export", "एक्सपोर्ट"), "${tl.aspect.label} · ${fmtTime(tl.durationMs)} · ${tl.captions.size} " + L.t("captions", "कैप्शन"), onBack = if (ui.running) null else onBack)
        when {
            ui.running -> Progress(ui.progress, onCancel)
            ui.file != null -> Done(ui, onSave, onShare, onBack)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                val (w, h) = ExportMath.outputSize(tl.aspect, settings.shortSide)
                SectionTitle(L.t("Resolution", "रिज़ॉल्यूशन"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip2("720p", settings.shortSide == 720, enabled = support.ok720) { onSettings(settings.copy(shortSide = 720)) }
                    Chip2("1080p", settings.shortSide == 1080, enabled = support.ok1080) { onSettings(settings.copy(shortSide = 1080)) }
                    Chip2("4K", settings.shortSide == 2160, enabled = support.ok4k) { onSettings(settings.copy(shortSide = 2160)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (support.ok4k) L.t("4K supported by this phone's ${support.label4k} encoder", "इस फ़ोन का ${support.label4k} encoder 4K सपोर्ट करता है")
                     else L.t("4K is off: this phone's video encoder doesn't report 4K support", "4K बंद है: इस फ़ोन का video encoder 4K सपोर्ट नहीं बताता"),
                    color = if (support.ok4k) G.Ok else G.Warn, fontSize = 12.sp)
                SectionTitle(L.t("Frame rate", "फ़्रेम रेट"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 30, 60).forEach { f -> Chip2("$f fps", settings.fps == f) { onSettings(settings.copy(fps = f)) } }
                }
                Spacer(Modifier.height(6.dp))
                Text(L.t("Frames are never invented: 60 fps needs a 60 fps source; lower values drop frames evenly.",
                    "फ़्रेम नए नहीं बनते: 60 fps के लिए वीडियो 60 fps का होना चाहिए; कम वाले में फ़्रेम बराबर हटते हैं।"), color = G.Sub, fontSize = 12.sp)
                SectionTitle(L.t("Bitrate", "बिटरेट"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 4, 8, 16, 40).forEach { b ->
                        Chip2(if (b == 0) L.t("Auto", "ऑटो") else "$b", settings.bitrateMbps == b) { onSettings(settings.copy(bitrateMbps = b)) }
                    }
                }
                val bps = ExportMath.bitrate(settings, w, h)
                Spacer(Modifier.height(6.dp))
                Text("${w}×$h · ${"%.1f".format(bps / 1e6)} Mbps · ≈ ${"%.0f".format(ExportMath.estimateMb(bps, tl.durationMs))} MB", color = G.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                SectionTitle(L.t("Included", "शामिल"))
                Card2 {
                    Text("• ${tl.video.size} " + L.t("video clip(s), trimmed", "वीडियो क्लिप (ट्रिम के साथ)"), color = G.Text, fontSize = 14.sp)
                    Text("• ${tl.audio.size} " + L.t("audio clip(s) mixed in", "ऑडियो क्लिप मिक्स"), color = G.Text, fontSize = 14.sp)
                    Text("• ${tl.captions.size} " + L.t("captions burned in · style ", "कैप्शन वीडियो में · स्टाइल ") + L.t(Templates.byId(tl.styleId).en, Templates.byId(tl.styleId).hi), color = G.Text, fontSize = 14.sp)
                }
                if (ui.error != null) { Spacer(Modifier.height(10.dp)); Text("❌ " + ui.error, color = Color(0xFFFF8A9E), fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(L.t("Export video", "वीडियो एक्सपोर्ट करें"), Modifier.fillMaxWidth(), icon = GI.Export, onClick = onStart)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun Progress(p: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator({ p / 100f }, Modifier.size(180.dp), color = G.Accent, strokeWidth = 12.dp, trackColor = G.Surface2)
            Text("$p%", color = G.Text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(24.dp))
        Text(L.t("Exporting… keep the app open", "एक्सपोर्ट हो रहा है… ऐप खुला रखें"), color = G.Text, fontWeight = FontWeight.Bold)
        Text(L.t("Captions are being burned into the video", "कैप्शन वीडियो में जोड़े जा रहे हैं"), color = G.Sub, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        GhostButton(L.t("Cancel", "रद्द करें"), onClick = onCancel)
    }
}

@Composable
private fun Done(ui: ExportUi, onSave: () -> Unit, onShare: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Text("✅", fontSize = 56.sp)
        Text(L.t("Export finished", "एक्सपोर्ट पूरा हुआ"), color = G.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(ui.file?.name ?: "", color = G.Sub, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
        when (ui.savedToGallery) {
            true -> Text(L.t("Saved to Gallery › Movies/GopalEditor", "गैलरी में सेव › Movies/GopalEditor"), color = G.Ok, fontWeight = FontWeight.Bold)
            false -> Text(L.t("Saved in the app's folder (no storage permission)", "ऐप के फ़ोल्डर में सेव (स्टोरेज इजाज़त नहीं)"), color = G.Warn)
            null -> PrimaryButton(L.t("Save to Gallery", "गैलरी में सेव करें"), Modifier.fillMaxWidth(), icon = GI.Download, onClick = onSave)
        }
        Spacer(Modifier.height(10.dp))
        GhostButton(L.t("Share", "शेयर करें"), Modifier.fillMaxWidth(), icon = Icons.Default.Share, tint = G.Accent, onClick = onShare)
        Spacer(Modifier.height(10.dp))
        GhostButton(L.t("Back to editor", "एडिटर पर वापस"), Modifier.fillMaxWidth(), onClick = onBack)
        if (ui.error != null) Text(ui.error, color = Color(0xFFFF8A9E), fontSize = 12.sp)
    }
}
