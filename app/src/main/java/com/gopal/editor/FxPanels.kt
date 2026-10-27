package com.gopal.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/* Stage 2A panels: Filters, Adjust, Speed (constant + curve graph). */

@Composable
private fun NoClip() {
    Text(L.t("Add or select a video clip", "कोई वीडियो क्लिप जोड़ें या चुनें"), color = G.Sub)
}

@Composable
private fun AmountSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String,
                         onChange: (Float) -> Unit, onDone: () -> Unit, onReset: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = G.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(value, onChange, Modifier.weight(1f).height(32.dp), valueRange = range, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = G.Accent, activeTrackColor = G.Accent, inactiveTrackColor = G.Line))
        Text(shown, Modifier.width(40.dp), color = G.Sub, fontSize = 11.sp)
        if (onReset != null) Text("↺", Modifier.clip(CircleShape).clickable(onClick = onReset).padding(horizontal = 6.dp), color = G.Sub, fontSize = 15.sp)
    }
}

/** Live edits while a slider moves (no undo entry), one undo step when the finger lifts. */
private class LiveEdit(private val ed: EditorState) {
    private var base: Timeline? = null
    fun live(t: Timeline) { if (base == null) base = ed.tl; ed.setLive(t) }
    fun done() { base?.let { ed.commit(it) }; base = null }
    /** The timeline from before this drag (so repeated speed changes don't accumulate rounding). */
    fun baseOr(t: Timeline): Timeline = base ?: t
}

// ---------------------------------------------------------------- Filters
@Composable
fun FiltersPanel(ed: EditorState, luts: List<LutEntity>, onImportLut: () -> Unit, onDeleteLut: (LutEntity) -> Unit = {}) {
    val live = remember(ed) { LiveEdit(ed) }
    PanelFrame(L.t("Filters", "फ़िल्टर"), ed, 300) {
        val c = ed.fxTarget ?: run { NoClip(); return@PanelFrame }
        val fx = c.fx
        // swatch strip
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filters.all.forEach { f ->
                val sel = f.id == fx.filterId
                Column(Modifier.width(62.dp).clickable { ed.edit(TimelineOps.setFx(ed.tl, c.id, fx.copy(filterId = f.id, filterAmt = if (sel) fx.filterAmt else 1f))) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(f.swatch), Color(f.swatch).copy(alpha = 0.55f), Color(0xFF101014))))
                        .then(if (sel) Modifier.border(2.dp, G.Accent, RoundedCornerShape(12.dp)) else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (f.id == "none") Text("⊘", color = G.Sub, fontSize = 20.sp)
                    }
                    Text(L.t(f.en, f.hi), color = if (sel) G.Accent else G.Text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (fx.filterId != "none") AmountSlider(L.t("Intensity", "तीव्रता"), fx.filterAmt, 0f..1f, "${(fx.filterAmt * 100).toInt()}%",
            { live.live(TimelineOps.setFx(ed.tl, c.id, c.fx.copy(filterAmt = it))) }, live::done)
        // LUTs
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LUT", color = G.Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Chip2(L.t("+ Import .cube", "+ .cube जोड़ें"), false, onClick = onImportLut)
            Chip2(L.t("None", "कोई नहीं"), fx.lutPath == null) { ed.edit(TimelineOps.setFx(ed.tl, c.id, fx.copy(lutPath = null, lutName = null))) }
            luts.forEach { l ->
                Chip2(l.name, fx.lutPath == l.path) { ed.edit(TimelineOps.setFx(ed.tl, c.id, fx.copy(lutPath = l.path, lutName = l.name, lutAmt = 1f))) }
            }
        }
        if (fx.lutPath != null) AmountSlider(L.t("LUT amount", "LUT मात्रा"), fx.lutAmt, 0f..1f, "${(fx.lutAmt * 100).toInt()}%",
            { live.live(TimelineOps.setFx(ed.tl, c.id, c.fx.copy(lutAmt = it))) }, live::done)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip2(L.t("Apply to all clips", "सभी क्लिप पर लगाएँ"), false) {
                ed.edit(TimelineOps.setFx(ed.tl, c.id, fx, all = true)); ed.note = L.t("Look copied to every clip", "यह लुक सभी क्लिप पर लगा")
            }
        }
    }
}

// ---------------------------------------------------------------- Adjust
@Composable
fun AdjustPanel(ed: EditorState) {
    val live = remember(ed) { LiveEdit(ed) }
    var band by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }  // 0 = basic, 1 = HSL
    PanelFrame(L.t("Adjust", "एडजस्ट"), ed, 320) {
        val c = ed.fxTarget ?: run { NoClip(); return@PanelFrame }
        val a = c.fx.adjust
        fun setA(n: Adjust) = live.live(TimelineOps.setFx(ed.tl, c.id, c.fx.copy(adjust = n)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip2(L.t("Basic", "बेसिक"), tab == 0) { tab = 0 }
            Chip2("HSL", tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            Chip2(L.t("Reset all", "सब रीसेट"), false, enabled = !a.isNeutral) { ed.edit(TimelineOps.setFx(ed.tl, c.id, c.fx.copy(adjust = Adjust()))) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                AdjustKey.entries.forEach { k ->
                    AmountSlider(L.t(k.en, k.hi), k.get(a), k.min..k.max, k.display(a),
                        { setA(k.set(c.fx.adjust, it)) }, live::done,
                        onReset = { ed.edit(TimelineOps.setFx(ed.tl, c.id, c.fx.copy(adjust = k.set(c.fx.adjust, 0f)))) })
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    HslBands.swatches.forEachIndexed { i, sw ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(sw))
                            .then(if (band == i) Modifier.border(3.dp, G.Text, CircleShape) else Modifier).clickable { band = i })
                    }
                }
                Text(L.t(HslBands.namesEn[band], HslBands.namesHi[band]), color = G.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                fun setBand(which: Int, v: Float) {
                    val cur = c.fx.adjust
                    val n = when (which) {
                        0 -> cur.copy(bandHue = cur.bandHue.toMutableList().also { it[band] = v })
                        1 -> cur.copy(bandSat = cur.bandSat.toMutableList().also { it[band] = v })
                        else -> cur.copy(bandLight = cur.bandLight.toMutableList().also { it[band] = v })
                    }
                    setA(n)
                }
                AmountSlider(L.t("Hue", "ह्यू"), a.bandHue[band], -1f..1f, "${(a.bandHue[band] * 30).toInt()}°", { setBand(0, it) }, live::done,
                    onReset = { setBand(0, 0f); live.done() })
                AmountSlider(L.t("Saturation", "सैचुरेशन"), a.bandSat[band], -1f..1f, "${(a.bandSat[band] * 100).toInt()}", { setBand(1, it) }, live::done,
                    onReset = { setBand(1, 0f); live.done() })
                AmountSlider(L.t("Lightness", "लाइटनेस"), a.bandLight[band], -1f..1f, "${(a.bandLight[band] * 100).toInt()}", { setBand(2, it) }, live::done,
                    onReset = { setBand(2, 0f); live.done() })
            }
        }
    }
}

// ---------------------------------------------------------------- Speed
@Composable
fun SpeedPanel(ed: EditorState, onFreeze: (Long) -> Unit, onReverse: (String) -> Unit) {
    val live = remember(ed) { LiveEdit(ed) }
    var mode by remember { mutableStateOf(if (ed.fxTarget?.curve != null) 1 else 0) }
    PanelFrame(L.t("Speed", "स्पीड"), ed, 340) {
        val c = ed.fxTarget ?: run { NoClip(); return@PanelFrame }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip2(L.t("Normal", "नॉर्मल"), mode == 0) { mode = 0; if (c.curve != null) ed.edit(TimelineOps.setSpeed(ed.tl, c.id, c.speed, null)) }
            Chip2(L.t("Curve", "कर्व"), mode == 1) { mode = 1; if (c.curve == null) ed.edit(TimelineOps.setSpeed(ed.tl, c.id, 1f, SpeedCurves.flat, null)) }
            Spacer(Modifier.weight(1f))
            Text("${fmtTime(c.srcLenMs)} → ${fmtTime(c.durMs, true)}", color = G.Sub, fontSize = 11.sp)
        }
        if (c.still) { Text(L.t("Freeze frames keep 1x. Drag the clip edge to change length.", "फ़्रीज़ फ़्रेम 1x ही रहता है।"), color = G.Sub, fontSize = 12.sp); return@PanelFrame }
        if (mode == 0) {
            Text(SpeedMath.fmtSpeed(c.speed), color = G.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Slider(SpeedMath.speedToSlider(c.speed), { p ->
                val s = snapSpeed(SpeedMath.sliderToSpeed(p))
                live.live(TimelineOps.setSpeed(live.baseOr(ed.tl), c.id, s, null))
            }, onValueChangeFinished = live::done,
                colors = SliderDefaults.colors(thumbColor = G.Accent, activeTrackColor = G.Accent, inactiveTrackColor = G.Line))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 10f, 50f, 100f).forEach { s ->
                    Chip2(SpeedMath.fmtSpeed(s), abs(c.speed - s) < 0.001f) { ed.edit(TimelineOps.setSpeed(ed.tl, c.id, s, null)) }
                }
            }
            Text(if (c.audioKept) L.t("Sound: kept, pitch preserved", "आवाज़: रहेगी, पिच वही") else
                L.t("Sound: muted (below 0.25x / above 4x)", "आवाज़: बंद (0.25x से कम / 4x से ज़्यादा)"), color = G.Sub, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip2(L.t("Custom", "कस्टम"), c.curveName == null) { ed.edit(TimelineOps.setSpeed(ed.tl, c.id, 1f, c.curve ?: SpeedCurves.flat, null)) }
                SpeedCurves.presets.forEach { (name, pts) -> Chip2(name, c.curveName == name) { ed.edit(TimelineOps.setSpeed(ed.tl, c.id, 1f, pts, name)) } }
            }
            Spacer(Modifier.height(4.dp))
            CurveEditor(c.curve ?: SpeedCurves.flat,
                playX = TimelineOps.locate(ed.tl, ed.playhead)?.let { l -> if (ed.tl.video[l.index].id == c.id && c.srcLenMs > 0) c.outToSrc(l.offsetMs).toFloat() / c.srcLenMs else null },
                onChange = { pts -> live.live(TimelineOps.setSpeed(live.baseOr(ed.tl), c.id, 1f, pts, null)) }, onDone = live::done)
            Text(L.t("Drag points · tap to add · long-press to remove. Sound is muted on curves.",
                "पॉइंट खींचें · टैप से जोड़ें · देर तक दबाकर हटाएँ। कर्व पर आवाज़ बंद रहती है।"), color = G.Sub, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("Freeze:", "फ़्रीज़:"), color = G.Text, fontSize = 12.sp)
            FreezeRules.choicesMs.forEach { ms -> Chip2("${ms / 1000}s", false, enabled = ed.busy == null) { onFreeze(ms) } }
            Spacer(Modifier.width(6.dp))
            Chip2(if (c.reversed) L.t("⟲ Un-reverse", "⟲ सीधा करें") else L.t("⟲ Reverse", "⟲ रिवर्स"), c.reversed, enabled = ed.busy == null) { onReverse(c.id) }
        }
    }
}

/** Snaps to round speeds near the slider position so 1x, 2x … are easy to hit. */
fun snapSpeed(s: Float): Float {
    val marks = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 5f, 10f, 20f, 50f, 100f)
    marks.forEach { m -> if (abs(log10(s) - log10(m)) < 0.025f) return m }
    return if (s < 10f) (s * 20f).toInt() / 20f else s.toInt().toFloat()
}

/** Curve graph: x = clip position, y = speed on a log scale 0.1x..10x (1x in the middle). */
@Composable
fun CurveEditor(points: List<SpeedPt>, playX: Float?, onChange: (List<SpeedPt>) -> Unit, onDone: () -> Unit) {
    var drag by remember { mutableIntStateOf(-1) }
    val pts by androidx.compose.runtime.rememberUpdatedState(points)
    fun yToSpeed(y: Float, h: Float) = 10f.pow(1f - 2f * (y / h).coerceIn(0f, 1f)).coerceIn(CURVE_MIN, CURVE_MAX)
    fun speedToY(s: Float, h: Float) = (1f - log10(s.coerceIn(CURVE_MIN, CURVE_MAX))) / 2f * h
    Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(G.Surface2)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val x = (o.x / w).coerceIn(0.02f, 0.98f)
                        if (pts.none { abs(it.x - x) < 0.04f }) { onChange(SpeedCurves.normalize(pts + SpeedPt(x, yToSpeed(o.y, h)))); onDone() }
                    },
                    onLongPress = { o ->
                        val w = size.width.toFloat()
                        val i = pts.indices.minByOrNull { abs(pts[it].x * w - o.x) } ?: return@detectTapGestures
                        if (i != 0 && i != pts.lastIndex && pts.size > 2) { onChange(pts.filterIndexed { k, _ -> k != i }); onDone() }
                    })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        drag = pts.indices.minByOrNull { i -> Offset(pts[i].x * w - o.x, speedToY(pts[i].y, h) - o.y).getDistance() } ?: -1
                    },
                    onDragEnd = { drag = -1; onDone() }, onDragCancel = { drag = -1; onDone() },
                    onDrag = { ch, _ ->
                        val i = drag; if (i < 0) return@detectDragGestures
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val p = ch.position
                        val lo = if (i == 0) 0f else pts[i - 1].x + 0.02f
                        val hi = if (i == pts.lastIndex) 1f else pts[i + 1].x - 0.02f
                        val nx = if (i == 0) 0f else if (i == pts.lastIndex) 1f else (p.x / w).coerceIn(lo, hi)
                        onChange(pts.toMutableList().also { it[i] = SpeedPt(nx, yToSpeed(p.y, h)) })
                    })
            }) {
            val w = size.width; val h = size.height
            // grid: 10x, 1x, 0.1x
            listOf(10f, 1f, 0.1f).forEach { s ->
                val y = speedToY(s, h)
                drawLine(if (s == 1f) Color(0x66FFFFFF) else Color(0x22FFFFFF), Offset(0f, y), Offset(w, y), 1.5f,
                    pathEffect = if (s == 1f) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            // curve
            val path = Path()
            val n = 80
            for (k in 0..n) {
                val x = k / n.toFloat()
                val y = speedToY(SpeedCurves.valueAt(pts, x), h)
                if (k == 0) path.moveTo(0f, y) else path.lineTo(x * w, y)
            }
            drawPath(path, G.Accent, style = Stroke(width = 4f))
            pts.forEachIndexed { i, p ->
                val c = Offset(p.x * w, speedToY(p.y, h))
                drawCircle(Color.White, if (i == drag) 13f else 10f, c)
                drawCircle(G.Accent2, if (i == drag) 8f else 6f, c)
            }
            playX?.let { x -> drawLine(Color.White, Offset(x * w, 0f), Offset(x * w, h), 2f) }
        }
        Text("10x", Modifier.align(Alignment.TopStart).padding(4.dp), color = G.Sub, fontSize = 9.sp)
        Text("1x", Modifier.align(Alignment.CenterStart).padding(4.dp), color = G.Sub, fontSize = 9.sp)
        Text("0.1x", Modifier.align(Alignment.BottomStart).padding(4.dp), color = G.Sub, fontSize = 9.sp)
    }
}
