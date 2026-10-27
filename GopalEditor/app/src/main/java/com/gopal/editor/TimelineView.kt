package com.gopal.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Row geometry of the timeline in px (computed from dp at draw time). */
private class Rows(d: Float) {
    val ruler = 0f to 22f * d
    val video = 28f * d to 86f * d
    val audio = 94f * d to 130f * d
    val text = 138f * d to 172f * d
    val total = 180f * d
    val handle = 14f * d
}

private enum class DragKind { PAN, TRIM_L, TRIM_R, MOVE, ZOOM }

/**
 * CapCut-style timeline: the playhead stays in the middle and the tracks slide under it.
 * One finger drags = scrub; two fingers = pinch zoom; tap = select a clip; drag the white handles of
 * the selected clip = trim; drag the body of a selected audio/text clip = move it.
 */
@Composable
fun TimelineView(
    ed: EditorState,
    thumbs: Map<String, ImageBitmap>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val tm = rememberTextMeasurer()
    val slop = LocalViewConfiguration.current.touchSlop
    Canvas(
        modifier.fillMaxWidth().height(180.dp).background(G.Bg)
            .pointerInput(ed) {
                awaitEachGesture {
                    val r = Rows(density)
                    val cx = size.width / 2f
                    fun xToMs(x: Float) = ed.playhead + Zoom.pxToMs(x - cx, ed.pxPerS)
                    fun msToX(t: Long) = cx + Zoom.msToPx(t - ed.playhead, ed.pxPerS)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p0 = down.position
                    // what did the finger land on?
                    var kind = DragKind.PAN
                    val sel = ed.selected
                    val span = sel?.let { TimelineOps.span(ed.tl, it) }
                    if (sel != null && span != null) {
                        val tr = ed.tl.trackOf(sel)
                        val row = when (tr) { Track.VIDEO -> r.video; Track.AUDIO -> r.audio; else -> r.text }
                        if (p0.y in (row.first - 8f)..(row.second + 8f)) {
                            val xl = msToX(span.first); val xr = msToX(span.last + 1)
                            kind = when {
                                abs(p0.x - xl) <= r.handle * 1.6f -> DragKind.TRIM_L
                                abs(p0.x - xr) <= r.handle * 1.6f -> DragKind.TRIM_R
                                p0.x in xl..xr && tr != Track.VIDEO -> DragKind.MOVE
                                else -> DragKind.PAN
                            }
                        }
                    }
                    val base = ed.tl
                    val startHead = ed.playhead
                    val startPx = ed.pxPerS
                    var totalDx = 0f
                    var moved = false
                    var zoomAcc = 1f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (kind != DragKind.ZOOM) { kind = DragKind.ZOOM; ed.setLive(base) }
                            zoomAcc *= ev.calculateZoom()
                            ed.pxPerS = Zoom.pinch(startPx, zoomAcc)
                            moved = true
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (kind == DragKind.ZOOM) { ev.changes.forEach { it.consume() }; continue }
                        totalDx += pressed[0].positionChange().x
                        if (!moved && abs(totalDx) > slop) moved = true
                        if (!moved) continue
                        val dMs = Zoom.pxToMs(totalDx, ed.pxPerS)
                        when (kind) {
                            DragKind.PAN -> { ed.playhead = (startHead - dMs).coerceIn(0, max(0L, ed.tl.durationMs)); onSeek(ed.playhead) }
                            DragKind.TRIM_L -> ed.setLive(TimelineOps.trimStart(base, sel!!, dMs))
                            DragKind.TRIM_R -> ed.setLive(TimelineOps.trimEnd(base, sel!!, dMs))
                            DragKind.MOVE -> ed.setLive(TimelineOps.moveTo(base, sel!!, (span!!.first + dMs)))
                            DragKind.ZOOM -> {}
                        }
                        pressed[0].consume()
                    }
                    if (kind == DragKind.TRIM_L || kind == DragKind.TRIM_R || kind == DragKind.MOVE) ed.commit(base)
                    if (!moved) {
                        // tap: select whatever is under the finger, or clear the selection
                        val t = xToMs(p0.x)
                        val hitId = when (p0.y) {
                            in r.video.first..r.video.second -> TimelineOps.locate(ed.tl, t)?.takeIf { t < ed.tl.videoDurMs && t >= 0 }?.let { ed.tl.video[it.index].id }
                            in r.audio.first..r.audio.second -> ed.tl.audio.lastOrNull { t >= it.startMs && t < it.endMs }?.id
                            in r.text.first..r.text.second -> ed.tl.captions.lastOrNull { t >= it.startMs && t < it.endMs }?.id
                            else -> null
                        }
                        ed.selected = if (hitId == ed.selected) null else hitId
                        if (hitId == null && p0.y < r.video.first) { ed.playhead = t.coerceIn(0, ed.tl.durationMs); onSeek(ed.playhead) }
                    }
                }
            }
    ) { drawTimeline(ed, thumbs, tm) }
}

private fun DrawScope.drawTimeline(ed: EditorState, thumbs: Map<String, ImageBitmap>, tm: TextMeasurer) {
    val r = Rows(density)
    val cx = size.width / 2f
    val pps = ed.pxPerS
    fun x(t: Long) = cx + Zoom.msToPx(t - ed.playhead, pps)
    val tl = ed.tl
    val small = TextStyle(color = G.Sub, fontSize = 10.sp)
    val label = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    // ruler
    val tick = Zoom.tickMs(pps)
    val first = max(0L, ((ed.playhead - Zoom.pxToMs(cx, pps)) / tick) * tick)
    var t = first
    val end = ed.playhead + Zoom.pxToMs(cx, pps) + tick
    while (t <= end) {
        val xx = x(t)
        drawLine(G.Line, Offset(xx, r.ruler.second - 6f * density), Offset(xx, r.ruler.second), strokeWidth = density)
        val half = x(t + tick / 2)
        drawCircle(G.Line, 1.2f * density, Offset(half, r.ruler.second - 3f * density))
        val lay = tm.measure(fmtTime(t, tick < 1000), small)
        drawText(lay, topLeft = Offset(xx - lay.size.width / 2f, 2f * density))
        t += tick
    }

    val cr = CornerRadius(8f * density)
    // video track
    var acc = 0L
    tl.video.forEach { c ->
        val l = x(acc); val rr = x(acc + c.durMs)
        if (rr > 0 && l < size.width) {
            val top = r.video.first; val h = r.video.second - r.video.first
            drawRoundRect(G.VideoTrack, Offset(l + density, top), Size(max(1f, rr - l - 2 * density), h), cr)
            val img = thumbs[c.uri]
            if (img != null) clipRect(l + density, top, rr - density, top + h) {
                val tw = h * img.width / img.height.coerceAtLeast(1)
                var xx = l + density
                while (xx < rr) {
                    if (xx + tw > 0 && xx < size.width) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                        IntOffset(xx.roundToInt(), top.roundToInt()), IntSize(tw.roundToInt().coerceAtLeast(1), h.roundToInt()), alpha = 0.85f)
                    xx += tw
                }
            }
            if (rr - l > 60 * density) {
                val lay = tm.measure(fmtTime(c.durMs, true), label)
                drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(l + 6 * density, top + 4 * density), Size(lay.size.width + 8 * density, lay.size.height.toFloat()), CornerRadius(4 * density))
                drawText(lay, topLeft = Offset(l + 10 * density, top + 4 * density))
            }
        }
        acc += c.durMs
    }
    // audio track
    tl.audio.forEach { c -> clipBlock(c.id, c.startMs, c.endMs, r.audio, G.AudioTrack, "♪ " + c.name, tm, label, ::x) }
    if (tl.audio.isEmpty()) hint("+ " + L.t("Audio", "ऑडियो"), r.audio, tm, small)
    // text track
    tl.captions.forEach { c -> clipBlock(c.id, c.startMs, c.endMs, r.text, G.TextTrack, c.text, tm, label, ::x) }
    if (tl.captions.isEmpty()) hint("+ " + L.t("Text / captions", "टेक्स्ट / कैप्शन"), r.text, tm, small)

    // selection + trim handles
    ed.selected?.let { id ->
        val sp = TimelineOps.span(tl, id) ?: return@let
        val row = when (tl.trackOf(id)) { Track.VIDEO -> r.video; Track.AUDIO -> r.audio; else -> r.text }
        val l = x(sp.first); val rr = x(sp.last + 1)
        val top = row.first; val h = row.second - row.first
        drawRoundRect(Color.White, Offset(l, top), Size(rr - l, h), cr, style = Stroke(2.5f * density))
        for (hx in listOf(l - r.handle + 2 * density, rr - 2 * density)) {
            drawRoundRect(Color.White, Offset(hx, top), Size(r.handle, h), CornerRadius(4 * density))
            drawLine(G.Bg, Offset(hx + r.handle / 2, top + h * 0.3f), Offset(hx + r.handle / 2, top + h * 0.7f), strokeWidth = 2 * density)
        }
    }
    // playhead
    drawLine(Color.White, Offset(cx, r.ruler.second - 2 * density), Offset(cx, r.total), strokeWidth = 2f * density)
    val tri = Path().apply { moveTo(cx - 6 * density, r.ruler.second - 8 * density); lineTo(cx + 6 * density, r.ruler.second - 8 * density); lineTo(cx, r.ruler.second); close() }
    drawPath(tri, Color.White)
}

private fun DrawScope.clipBlock(
    id: String, s: Long, e: Long, row: Pair<Float, Float>, color: Color, text: String,
    tm: TextMeasurer, style: TextStyle, x: (Long) -> Float
) {
    val l = x(s); val rr = x(e)
    if (rr < 0 || l > size.width) return
    val top = row.first; val h = row.second - row.first
    drawRoundRect(color, Offset(l + density, top), Size(max(1f, rr - l - 2 * density), h), CornerRadius(6f * density))
    val w = (rr - l - 12 * density).roundToInt()
    if (w > 16 * density) {
        val lay = tm.measure(text.replace('\n', ' '), style, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = w))
        drawText(lay, topLeft = Offset(l + 6 * density, top + (h - lay.size.height) / 2))
    }
}

private fun DrawScope.hint(text: String, row: Pair<Float, Float>, tm: TextMeasurer, style: TextStyle) {
    val cx = size.width / 2f
    val lay = tm.measure(text, style)
    val top = row.first; val h = row.second - row.first
    drawRoundRect(G.Surface, Offset(cx + 8 * density, top), Size(lay.size.width + 20 * density, h), CornerRadius(6f * density))
    drawText(lay, topLeft = Offset(cx + 18 * density, top + (h - lay.size.height) / 2))
}

