package com.gopal.editor

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * The timeline model. Pure Kotlin (no Android types) so every edit is unit-tested on the JVM.
 *
 *  - VIDEO track is "magnetic" like CapCut's main track: clips sit back to back, their
 *    timeline position is the sum of the clips before them.
 *  - AUDIO track: free clips with their own timeline start (music, voice files).
 *  - TEXT track: captions with start/end on the timeline (+ optional word timings).
 * All times are milliseconds.
 */

const val MIN_CLIP_MS = 100L

enum class Aspect(val label: String, val w: Int, val h: Int) {
    R9_16("9:16", 9, 16), R16_9("16:9", 16, 9), R1_1("1:1", 1, 1), R4_5("4:5", 4, 5);
    val ratio: Float get() = w.toFloat() / h
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: R9_16 }
}

enum class Track { VIDEO, AUDIO, TEXT }

data class VideoClip(
    val id: String, val uri: String, val name: String,
    val srcDurMs: Long, val inMs: Long, val outMs: Long, val volume: Float = 1f
) { val durMs: Long get() = outMs - inMs }

data class AudioClip(
    val id: String, val uri: String, val name: String,
    val srcDurMs: Long, val inMs: Long, val outMs: Long, val startMs: Long, val volume: Float = 1f
) {
    val durMs: Long get() = outMs - inMs
    val endMs: Long get() = startMs + durMs
}

data class Word(val startMs: Long, val endMs: Long, val text: String)

data class Caption(val id: String, val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList()) {
    val durMs: Long get() = endMs - startMs
}

data class Timeline(
    val video: List<VideoClip> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val captions: List<Caption> = emptyList(),
    val aspect: Aspect = Aspect.R9_16,
    val fill: Boolean = false,          // false = fit with bars, true = crop to fill
    val styleId: String = "classic",
    val captionLang: String = "hi"
) {
    val videoDurMs: Long get() = video.sumOf { it.durMs }
    /** Length of the edit = the main video track (or the audio, if there is no video yet). */
    val durationMs: Long get() = if (video.isNotEmpty()) videoDurMs else (audio.maxOfOrNull { it.endMs } ?: 0L)
    fun videoStart(index: Int): Long = video.take(index).sumOf { it.durMs }
    fun trackOf(id: String): Track? = when {
        video.any { it.id == id } -> Track.VIDEO
        audio.any { it.id == id } -> Track.AUDIO
        captions.any { it.id == id } -> Track.TEXT
        else -> null
    }
    fun captionAt(t: Long): Caption? = captions.firstOrNull { t >= it.startMs && t < it.endMs && it.text.isNotBlank() }
}

data class Located(val index: Int, val offsetMs: Long)

object TimelineOps {
    var idGen: () -> String = { UUID.randomUUID().toString().substring(0, 8) }

    /** Which video clip is under timeline time [t], and how far into it (t == total -> last clip end). */
    fun locate(tl: Timeline, t: Long): Located? {
        if (tl.video.isEmpty()) return null
        var acc = 0L
        tl.video.forEachIndexed { i, c ->
            if (t < acc + c.durMs) return Located(i, max(0L, t - acc))
            acc += c.durMs
        }
        return Located(tl.video.lastIndex, tl.video.last().durMs)
    }

    /** Source position of the frame shown at timeline time [t]. */
    fun sourcePos(tl: Timeline, t: Long): Pair<Int, Long>? = locate(tl, t)?.let { it.index to tl.video[it.index].inMs + it.offsetMs }

    // ---------------- add ----------------
    fun addVideo(tl: Timeline, uri: String, name: String, durMs: Long): Timeline =
        tl.copy(video = tl.video + VideoClip(idGen(), uri, name, durMs, 0, durMs))

    fun addAudio(tl: Timeline, uri: String, name: String, durMs: Long, atMs: Long): Timeline {
        val len = if (tl.video.isNotEmpty()) min(durMs, max(MIN_CLIP_MS, tl.durationMs - atMs)) else durMs
        return tl.copy(audio = tl.audio + AudioClip(idGen(), uri, name, durMs, 0, max(MIN_CLIP_MS, len), max(0L, atMs)))
    }

    fun addCaption(tl: Timeline, atMs: Long, text: String, durMs: Long = 2000): Timeline =
        tl.copy(captions = (tl.captions + Caption(idGen(), atMs, atMs + durMs, text)).sortedBy { it.startMs })

    // ---------------- split ----------------
    /**
     * Splits the clip [id] at timeline time [t]. Returns the new timeline and the id of the right half,
     * or null when [t] is not strictly inside the clip (each half must keep >= MIN_CLIP_MS).
     */
    fun split(tl: Timeline, id: String, t: Long): Pair<Timeline, String>? {
        when (tl.trackOf(id)) {
            Track.VIDEO -> {
                val i = tl.video.indexOfFirst { it.id == id }
                val c = tl.video[i]
                val off = t - tl.videoStart(i)
                if (off < MIN_CLIP_MS || c.durMs - off < MIN_CLIP_MS) return null
                val left = c.copy(outMs = c.inMs + off)
                val right = c.copy(id = idGen(), inMs = c.inMs + off)
                val v = tl.video.toMutableList().apply { set(i, left); add(i + 1, right) }
                return tl.copy(video = v) to right.id
            }
            Track.AUDIO -> {
                val i = tl.audio.indexOfFirst { it.id == id }
                val c = tl.audio[i]
                val off = t - c.startMs
                if (off < MIN_CLIP_MS || c.durMs - off < MIN_CLIP_MS) return null
                val left = c.copy(outMs = c.inMs + off)
                val right = c.copy(id = idGen(), inMs = c.inMs + off, startMs = c.startMs + off)
                val a = tl.audio.toMutableList().apply { set(i, left); add(i + 1, right) }
                return tl.copy(audio = a) to right.id
            }
            Track.TEXT -> {
                val i = tl.captions.indexOfFirst { it.id == id }
                val c = tl.captions[i]
                if (t - c.startMs < MIN_CLIP_MS || c.endMs - t < MIN_CLIP_MS) return null
                val (lt, rt) = splitText(c, t)
                val left = c.copy(endMs = t, text = lt, words = c.words.filter { it.startMs < t })
                val right = c.copy(id = idGen(), startMs = t, text = rt, words = c.words.filter { it.startMs >= t })
                val cs = tl.captions.toMutableList().apply { set(i, left); add(i + 1, right) }
                return tl.copy(captions = cs) to right.id
            }
            null -> return null
        }
    }

    /** Text halves for a caption split: by word timings if present, else by the time fraction (at a space). */
    fun splitText(c: Caption, t: Long): Pair<String, String> {
        if (c.words.isNotEmpty()) {
            val l = c.words.filter { it.startMs < t }.joinToString(" ") { it.text.trim() }
            val r = c.words.filter { it.startMs >= t }.joinToString(" ") { it.text.trim() }
            return l to r
        }
        val tokens = c.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return c.text to c.text
        val frac = (t - c.startMs).toDouble() / c.durMs
        val k = (tokens.size * frac).roundToLong().toInt().coerceIn(1, tokens.size - 1)
        return tokens.take(k).joinToString(" ") to tokens.drop(k).joinToString(" ")
    }

    /** Split whatever sits under the playhead: the selected clip if it spans [t], otherwise the video clip. */
    fun splitAtPlayhead(tl: Timeline, selected: String?, t: Long): Pair<Timeline, String>? {
        if (selected != null) split(tl, selected, t)?.let { return it }
        val loc = locate(tl, t) ?: return null
        return split(tl, tl.video[loc.index].id, t)
    }

    // ---------------- trim ----------------
    /** Moves the LEFT edge by [deltaMs] (positive = shorter). Clamped to the source and MIN_CLIP_MS. */
    fun trimStart(tl: Timeline, id: String, deltaMs: Long): Timeline = when (tl.trackOf(id)) {
        Track.VIDEO -> tl.copy(video = tl.video.map {
            if (it.id != id) it else it.copy(inMs = (it.inMs + deltaMs).coerceIn(0L, it.outMs - MIN_CLIP_MS))
        })
        Track.AUDIO -> tl.copy(audio = tl.audio.map {
            if (it.id != id) it else {
                // left edge can't go before source start or before timeline 0
                val lo = max(-it.inMs, -it.startMs)
                val d = deltaMs.coerceIn(lo, it.durMs - MIN_CLIP_MS)
                it.copy(inMs = it.inMs + d, startMs = it.startMs + d)
            }
        })
        Track.TEXT -> tl.copy(captions = tl.captions.map {
            if (it.id != id) it else it.copy(startMs = (it.startMs + deltaMs).coerceIn(0L, it.endMs - MIN_CLIP_MS))
        })
        null -> tl
    }

    /** Moves the RIGHT edge by [deltaMs] (positive = longer). */
    fun trimEnd(tl: Timeline, id: String, deltaMs: Long): Timeline = when (tl.trackOf(id)) {
        Track.VIDEO -> tl.copy(video = tl.video.map {
            if (it.id != id) it else it.copy(outMs = (it.outMs + deltaMs).coerceIn(it.inMs + MIN_CLIP_MS, it.srcDurMs))
        })
        Track.AUDIO -> tl.copy(audio = tl.audio.map {
            if (it.id != id) it else it.copy(outMs = (it.outMs + deltaMs).coerceIn(it.inMs + MIN_CLIP_MS, it.srcDurMs))
        })
        Track.TEXT -> tl.copy(captions = tl.captions.map {
            if (it.id != id) it else it.copy(endMs = max(it.startMs + MIN_CLIP_MS, it.endMs + deltaMs))
        })
        null -> tl
    }

    // ---------------- delete / duplicate / move ----------------
    fun delete(tl: Timeline, id: String): Timeline = tl.copy(
        video = tl.video.filterNot { it.id == id },
        audio = tl.audio.filterNot { it.id == id },
        captions = tl.captions.filterNot { it.id == id }
    )

    /** Duplicates [id] right after itself. Returns the timeline and the copy's id. */
    fun duplicate(tl: Timeline, id: String): Pair<Timeline, String>? {
        val nid = idGen()
        return when (tl.trackOf(id)) {
            Track.VIDEO -> {
                val i = tl.video.indexOfFirst { it.id == id }
                tl.copy(video = tl.video.toMutableList().apply { add(i + 1, tl.video[i].copy(id = nid)) }) to nid
            }
            Track.AUDIO -> {
                val i = tl.audio.indexOfFirst { it.id == id }
                val c = tl.audio[i]
                tl.copy(audio = tl.audio.toMutableList().apply { add(i + 1, c.copy(id = nid, startMs = c.endMs)) }) to nid
            }
            Track.TEXT -> {
                val i = tl.captions.indexOfFirst { it.id == id }
                val c = tl.captions[i]
                val shift = c.durMs
                val copy = c.copy(id = nid, startMs = c.endMs, endMs = c.endMs + shift,
                    words = c.words.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) })
                tl.copy(captions = (tl.captions + copy).sortedBy { it.startMs }) to nid
            }
            null -> null
        }
    }

    /** Drags a free clip (audio / caption) to a new start time (>= 0). Video clips reorder instead. */
    fun moveTo(tl: Timeline, id: String, newStartMs: Long): Timeline {
        val s = max(0L, newStartMs)
        return when (tl.trackOf(id)) {
            Track.AUDIO -> tl.copy(audio = tl.audio.map { if (it.id == id) it.copy(startMs = s) else it })
            Track.TEXT -> tl.copy(captions = tl.captions.map {
                if (it.id != id) it else {
                    val d = s - it.startMs
                    it.copy(startMs = s, endMs = it.endMs + d, words = it.words.map { w -> w.copy(startMs = w.startMs + d, endMs = w.endMs + d) })
                }
            }.sortedBy { it.startMs })
            else -> tl
        }
    }

    fun reorderVideo(tl: Timeline, from: Int, to: Int): Timeline {
        if (from !in tl.video.indices) return tl
        val v = tl.video.toMutableList()
        val c = v.removeAt(from)
        v.add(to.coerceIn(0, v.size), c)
        return tl.copy(video = v)
    }

    fun setCaptionText(tl: Timeline, id: String, text: String): Timeline =
        tl.copy(captions = tl.captions.map {
            if (it.id != id) it
            // edited text no longer matches the word timings -> drop them (highlight falls back to even timing)
            else it.copy(text = text, words = if (text.trim() == it.text.trim()) it.words else emptyList())
        })

    fun setVolume(tl: Timeline, id: String, v: Float): Timeline = tl.copy(
        video = tl.video.map { if (it.id == id) it.copy(volume = v.coerceIn(0f, 2f)) else it },
        audio = tl.audio.map { if (it.id == id) it.copy(volume = v.coerceIn(0f, 2f)) else it }
    )

    /** Clip start/end on the timeline (for drawing and hit tests). */
    fun span(tl: Timeline, id: String): LongRange? {
        tl.video.forEachIndexed { i, c -> if (c.id == id) { val s = tl.videoStart(i); return s until s + c.durMs } }
        tl.audio.firstOrNull { it.id == id }?.let { return it.startMs until it.endMs }
        tl.captions.firstOrNull { it.id == id }?.let { return it.startMs until it.endMs }
        return null
    }
}

/** Timeline zoom: pixels per second, pinch-scaled and clamped. */
object Zoom {
    const val MIN_PX_PER_S = 8f
    const val MAX_PX_PER_S = 600f
    const val DEFAULT_PX_PER_S = 60f
    fun pinch(current: Float, factor: Float): Float = (current * factor).coerceIn(MIN_PX_PER_S, MAX_PX_PER_S)
    fun msToPx(ms: Long, pxPerS: Float): Float = ms * pxPerS / 1000f
    fun pxToMs(px: Float, pxPerS: Float): Long = (px * 1000f / pxPerS).roundToLong()
    /** Ruler tick spacing (ms) that keeps labels roughly >= 70 px apart. */
    fun tickMs(pxPerS: Float): Long {
        val steps = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000)
        return steps.firstOrNull { msToPx(it, pxPerS) >= 70f } ?: steps.last()
    }
}

/** JSON (de)serialisation for Room autosave. */
object TimelineJson {
    fun encode(tl: Timeline): String = JSONObject().apply {
        put("v", 1)
        put("aspect", tl.aspect.name); put("fill", tl.fill); put("style", tl.styleId); put("lang", tl.captionLang)
        put("video", JSONArray().apply { tl.video.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("uri", c.uri); put("name", c.name); put("src", c.srcDurMs); put("in", c.inMs); put("out", c.outMs); put("vol", c.volume.toDouble())
        }) } })
        put("audio", JSONArray().apply { tl.audio.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("uri", c.uri); put("name", c.name); put("src", c.srcDurMs); put("in", c.inMs); put("out", c.outMs); put("start", c.startMs); put("vol", c.volume.toDouble())
        }) } })
        put("captions", JSONArray().apply { tl.captions.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("s", c.startMs); put("e", c.endMs); put("t", c.text)
            put("w", JSONArray().apply { c.words.forEach { wd -> put(JSONArray().put(wd.startMs).put(wd.endMs).put(wd.text)) } })
        }) } })
    }.toString()

    fun decode(s: String?): Timeline {
        if (s.isNullOrBlank()) return Timeline()
        return runCatching {
            val o = JSONObject(s)
            val v = o.optJSONArray("video") ?: JSONArray()
            val a = o.optJSONArray("audio") ?: JSONArray()
            val c = o.optJSONArray("captions") ?: JSONArray()
            Timeline(
                video = (0 until v.length()).map { i -> v.getJSONObject(i).let {
                    VideoClip(it.getString("id"), it.getString("uri"), it.optString("name"), it.getLong("src"), it.getLong("in"), it.getLong("out"), it.optDouble("vol", 1.0).toFloat())
                } },
                audio = (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    AudioClip(it.getString("id"), it.getString("uri"), it.optString("name"), it.getLong("src"), it.getLong("in"), it.getLong("out"), it.getLong("start"), it.optDouble("vol", 1.0).toFloat())
                } },
                captions = (0 until c.length()).map { i -> c.getJSONObject(i).let { j ->
                    val ws = j.optJSONArray("w") ?: JSONArray()
                    Caption(j.getString("id"), j.getLong("s"), j.getLong("e"), j.getString("t"),
                        (0 until ws.length()).map { k -> ws.getJSONArray(k).let { Word(it.getLong(0), it.getLong(1), it.getString(2)) } })
                } },
                aspect = Aspect.parse(o.optString("aspect")),
                fill = o.optBoolean("fill", false),
                styleId = o.optString("style", "classic"),
                captionLang = o.optString("lang", "hi")
            )
        }.getOrDefault(Timeline())
    }
}
