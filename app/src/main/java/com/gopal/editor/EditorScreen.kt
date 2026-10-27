package com.gopal.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Callbacks from the editor screen (kept as one object so the screen stays previewable). */
class EditorActions(
    val onBack: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onAddClip: () -> Unit = {},
    val onAddAudio: () -> Unit = {},
    val onAutoCaptions: (String) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onApi: () -> Unit = {},
    // ---- stage 2A ----
    val luts: List<LutEntity> = emptyList(),
    val onImportLut: () -> Unit = {},
    val onFreeze: (Long) -> Unit = {},
    val onReverse: (String) -> Unit = {},
    val onLookChanged: () -> Unit = {}
)

@Composable
fun EditorScreen(
    ed: EditorState,
    actions: EditorActions,
    thumbs: Map<String, ImageBitmap> = emptyMap(),
    video: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val renderer = remember { CaptionRenderer(FontBank(ctx)) }
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        // ---- top bar ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = actions.onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "close", tint = G.Text)
            }
            Text(ed.name, Modifier.weight(1f).padding(horizontal = 6.dp), color = G.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ed.tl.aspect.label, Modifier.clip(RoundedCornerShape(8.dp)).background(G.Surface2).clickable { ed.panel = Panel.ASPECT }
                .padding(horizontal = 10.dp, vertical = 6.dp), color = G.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(G.Accent).clickable(enabled = ed.tl.video.isNotEmpty(), onClick = actions.onExport)
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(GI.Export, null, tint = Color(0xFF00201C), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(L.t("Export", "एक्सपोर्ट"), color = Color(0xFF00201C), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
        }
        // ---- preview ----
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val ar = ed.tl.aspect.ratio
            val boxMod = if (maxWidth / maxHeight > ar) Modifier.fillMaxHeight().aspectRatio(ar) else Modifier.fillMaxWidth().aspectRatio(ar)
            Box(boxMod.background(Color(0xFF050507))) {
                video()
                val tpl = Templates.byId(ed.tl.styleId)
                Canvas(Modifier.fillMaxSize()) {
                    val c = ed.tl.captionAt(ed.playhead)
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), c, tpl, ed.playhead) }
                }
            }
            if (ed.tl.video.isEmpty()) Text(L.t("Add a video to start", "शुरू करने के लिए वीडियो जोड़ें"), color = G.Sub)
        }
        // ---- transport row ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${fmtTime(ed.playhead, true)} / ${fmtTime(ed.tl.durationMs)}", color = G.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(G.Surface2).clickable(onClick = actions.onPlayPause), contentAlignment = Alignment.Center) {
                Icon(if (ed.playing) GI.Pause else Icons.Default.PlayArrow, "play", tint = G.Text)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = ed.canUndo) { ed.undo() }, contentAlignment = Alignment.Center) {
                    Icon(GI.Undo, "undo", tint = if (ed.canUndo) G.Text else G.Sub.copy(alpha = 0.3f))
                }
            }
        }
        // ---- status ----
        ed.busy?.let { msg ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Text(msg, color = G.Accent, fontSize = 12.sp)
                if (ed.progress >= 0f) LinearProgressIndicator({ ed.progress }, Modifier.fillMaxWidth(), color = G.Accent, trackColor = G.Surface2)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = G.Accent, trackColor = G.Surface2)
            }
        }
        ed.note?.let { n ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(G.Surface2).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f), color = G.Text, fontSize = 12.sp, maxLines = 3)
                Icon(Icons.Default.Close, null, tint = G.Sub, modifier = Modifier.size(18.dp).clickable { ed.note = null })
            }
        }
        // ---- timeline ----
        TimelineView(ed, thumbs, actions.onSeek)
        // ---- bottom: panel or toolbar ----
        when (ed.panel) {
            Panel.CAPTIONS -> CaptionsPanel(ed, actions)
            Panel.STYLE -> StylePanel(ed, renderer)
            Panel.ASPECT -> AspectPanel(ed)
            Panel.VOLUME -> VolumePanel(ed)
            Panel.FILTERS -> FiltersPanel(ed, actions.luts, actions.onImportLut)
            Panel.ADJUST -> AdjustPanel(ed)
            Panel.SPEED -> SpeedPanel(ed, actions.onFreeze, actions.onReverse)
            Panel.NONE -> Toolbar(ed, actions)
        }
    }
}

@Composable
private fun Toolbar(ed: EditorState, a: EditorActions) {
    var editing by remember { mutableStateOf<Caption?>(null) }
    val sel = ed.selected
    val track = sel?.let { ed.tl.trackOf(it) }
    Row(Modifier.fillMaxWidth().background(G.Surface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (sel != null && track != null) {
            ToolButton(Icons.Default.Close, L.t("Done", "ठीक")) { ed.selected = null }
            ToolButton(GI.Cut, L.t("Split", "स्प्लिट")) {
                TimelineOps.split(ed.tl, sel, ed.playhead)?.let { (t, right) -> ed.edit(t); ed.selected = right }
                    ?: run { ed.note = L.t("Move the playhead inside the clip to split", "स्प्लिट के लिए playhead को क्लिप के अंदर रखें") }
            }
            ToolButton(GI.Copy, L.t("Duplicate", "डुप्लीकेट")) { TimelineOps.duplicate(ed.tl, sel)?.let { (t, id) -> ed.edit(t); ed.selected = id } }
            ToolButton(Icons.Default.Delete, L.t("Delete", "डिलीट"), tint = G.Red) { ed.edit(TimelineOps.delete(ed.tl, sel)); ed.selected = null }
            if (track == Track.TEXT) ToolButton(Icons.Default.Edit, L.t("Edit text", "टेक्स्ट बदलें")) { editing = ed.tl.captions.firstOrNull { it.id == sel } }
            if (track == Track.VIDEO) {
                ToolButton(GI.Speed, L.t("Speed", "स्पीड")) { ed.panel = Panel.SPEED }
                ToolButton(GI.Filter, L.t("Filters", "फ़िल्टर")) { ed.panel = Panel.FILTERS }
                ToolButton(GI.Tune, L.t("Adjust", "एडजस्ट")) { ed.panel = Panel.ADJUST }
                ToolButton(GI.Reverse, L.t("Reverse", "रिवर्स"), enabled = ed.busy == null) { a.onReverse(sel) }
            }
            if (track != Track.TEXT) ToolButton(GI.Music, L.t("Volume", "आवाज़")) { ed.panel = Panel.VOLUME }
            if (track == Track.TEXT) ToolButton(GI.TextT, L.t("Style", "स्टाइल")) { ed.panel = Panel.STYLE }
        } else {
            ToolButton(Icons.Default.Add, L.t("Add clip", "क्लिप जोड़ें"), onClick = a.onAddClip)
            ToolButton(GI.Cut, L.t("Split", "स्प्लिट"), enabled = ed.tl.video.isNotEmpty()) {
                TimelineOps.splitAtPlayhead(ed.tl, null, ed.playhead)?.let { (t, right) -> ed.edit(t); ed.selected = right }
            }
            ToolButton(GI.CC, L.t("Captions", "कैप्शन"), tint = G.Accent) { ed.panel = Panel.CAPTIONS }
            ToolButton(GI.Filter, L.t("Filters", "फ़िल्टर"), enabled = ed.tl.video.isNotEmpty()) { ed.panel = Panel.FILTERS }
            ToolButton(GI.Tune, L.t("Adjust", "एडजस्ट"), enabled = ed.tl.video.isNotEmpty()) { ed.panel = Panel.ADJUST }
            ToolButton(GI.Speed, L.t("Speed", "स्पीड"), enabled = ed.tl.video.isNotEmpty()) { ed.panel = Panel.SPEED }
            ToolButton(GI.Freeze, L.t("Freeze", "फ़्रीज़"), enabled = ed.tl.video.isNotEmpty() && ed.busy == null) { a.onFreeze(FreezeRules.DEFAULT_MS) }
            ToolButton(GI.Music, L.t("Audio", "ऑडियो"), onClick = a.onAddAudio)
            ToolButton(GI.TextT, L.t("Text", "टेक्स्ट")) {
                val t = TimelineOps.addCaption(ed.tl, ed.playhead, L.t("Your text", "आपका टेक्स्ट"))
                ed.edit(t); ed.selected = t.captions.firstOrNull { it.startMs == ed.playhead }?.id
            }
            ToolButton(GI.Sparkle, L.t("Style", "स्टाइल")) { ed.panel = Panel.STYLE }
            ToolButton(GI.Aspect, L.t("Ratio", "रेशियो")) { ed.panel = Panel.ASPECT }
        }
    }
    editing?.let { c -> TextEditDialog(c.text, onDismiss = { editing = null }) { txt -> ed.edit(TimelineOps.setCaptionText(ed.tl, c.id, txt)); editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Card2 {
            Text(L.t("Edit caption", "कैप्शन बदलें"), color = G.Text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(L.t("Cancel", "रद्द"), Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("OK", Modifier.weight(1f)) { onOk(t) }
            }
        }
    }
}

@Composable
internal fun PanelFrame(title: String, ed: EditorState, height: Int = 280, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().height(height.dp).background(G.Surface).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = G.Text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Box(Modifier.size(36.dp).clip(CircleShape).background(G.Surface2).clickable { ed.panel = Panel.NONE }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, tint = G.Text, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun CaptionsPanel(ed: EditorState, a: EditorActions) {
    var mode by remember { mutableStateOf(ed.tl.captionLang.takeIf { it in listOf("hi", "en", "hinglish") } ?: "hi") }
    var editing by remember { mutableStateOf<Caption?>(null) }
    PanelFrame(L.t("Auto Captions", "ऑटो कैप्शन"), ed, 300) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip2("हिंदी", mode == "hi") { mode = "hi" }
            Chip2("English", mode == "en") { mode = "en" }
            Chip2("Hinglish", mode == "hinglish") { mode = "hinglish" }
            Spacer(Modifier.weight(1f))
            if (ed.busy != null) CircularProgressIndicator(Modifier.size(22.dp), color = G.Accent, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(if (ed.tl.captions.isEmpty()) L.t("Generate captions", "कैप्शन बनाएँ") else L.t("Re-generate (replaces list)", "फिर से बनाएँ (लिस्ट बदलेगी)"),
            Modifier.fillMaxWidth(), enabled = ed.busy == null && ed.tl.video.isNotEmpty(), icon = GI.Sparkle) { a.onAutoCaptions(mode) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(ed.tl.captions, key = { _, c -> c.id }) { _, c ->
                val active = ed.playhead in c.startMs until c.endMs
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) G.Accent.copy(alpha = 0.14f) else G.Surface2)
                    .clickable { ed.playhead = c.startMs; a.onSeek(c.startMs); ed.selected = c.id }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(c.startMs, true), color = G.Sub, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Text(c.text, Modifier.weight(1f), color = G.Text, fontSize = 14.sp, maxLines = 2)
                    Icon(Icons.Default.Edit, null, tint = G.Sub, modifier = Modifier.size(18.dp).clickable { editing = c })
                }
            }
        }
    }
    editing?.let { c -> TextEditDialog(c.text, onDismiss = { editing = null }) { txt -> ed.edit(TimelineOps.setCaptionText(ed.tl, c.id, txt)); editing = null } }
}

@Composable
fun StylePanel(ed: EditorState, renderer: CaptionRenderer) {
    val demo = Caption("demo", 0, 1200, "Gopal एडिटर", listOf(Word(0, 600, "Gopal"), Word(600, 1200, "एडिटर")))
    PanelFrame(L.t("Caption style", "कैप्शन स्टाइल"), ed, 300) {
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Templates.all, key = { it.id }) { tpl ->
                val sel = tpl.id == ed.tl.styleId
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { ed.edit(ed.tl.copy(styleId = tpl.id)) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2B3440)).border(2.dp, if (sel) G.Accent else Color.Transparent, RoundedCornerShape(12.dp))) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), demo, tpl.copy(yK = 0.5f, sizeK = tpl.sizeK.coerceAtMost(1.25f) * 2.6f), 700) }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(L.t(tpl.en, tpl.hi), fontSize = 11.sp, color = if (sel) G.Accent else G.Text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AspectPanel(ed: EditorState) {
    PanelFrame(L.t("Aspect ratio", "आस्पेक्ट रेशियो"), ed, 200) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Aspect.entries.forEach { a ->
                val sel = ed.tl.aspect == a
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) G.Accent.copy(alpha = 0.15f) else G.Surface2)
                    .clickable { ed.edit(ed.tl.copy(aspect = a)) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.height((36 * minOf(1f, 1f / a.ratio)).dp).aspectRatio(a.ratio).border(2.dp, if (sel) G.Accent else G.Sub, RoundedCornerShape(4.dp)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, color = if (sel) G.Accent else G.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip2(L.t("Fit (bars)", "फ़िट (पट्टी)"), !ed.tl.fill) { ed.edit(ed.tl.copy(fill = false)) }
            Chip2(L.t("Fill (crop)", "भरें (क्रॉप)"), ed.tl.fill) { ed.edit(ed.tl.copy(fill = true)) }
        }
    }
}

@Composable
private fun VolumePanel(ed: EditorState) {
    val id = ed.selected
    val v = ed.tl.video.firstOrNull { it.id == id }?.volume ?: ed.tl.audio.firstOrNull { it.id == id }?.volume
    PanelFrame(L.t("Volume", "आवाज़"), ed, 160) {
        if (id == null || v == null) { Text(L.t("Select a video or audio clip", "कोई वीडियो या ऑडियो क्लिप चुनें"), color = G.Sub); return@PanelFrame }
        Text("${(v * 100).toInt()}%", color = G.Text, fontWeight = FontWeight.Bold)
        Slider(v, { ed.setLive(TimelineOps.setVolume(ed.tl, id, it)) }, valueRange = 0f..2f,
            onValueChangeFinished = { ed.mediaVersion++ },
            colors = SliderDefaults.colors(thumbColor = G.Accent, activeTrackColor = G.Accent))
    }
}
