package com.gopal.editor

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToLong

/** A transcript segment as a provider returned it (times in ms on the audio that was sent). */
data class Segment(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList())

class TranscriptParseException(msg: String) : Exception(msg)

object TranscriptParser {

    /**
     * OpenAI / Groq Whisper `response_format=verbose_json` with
     * `timestamp_granularities[]=segment` and `timestamp_granularities[]=word`.
     * Words arrive in a top-level `words` array (some servers also nest them per segment);
     * they are attached to the segment whose time range contains them.
     */
    fun whisperVerbose(json: String): List<Segment> {
        val o = runCatching { JSONObject(json) }.getOrElse { throw TranscriptParseException("not JSON: ${it.message}") }
        val words = ArrayList<Word>()
        o.optJSONArray("words")?.let { readWords(it, words) }
        val segs = o.optJSONArray("segments")
        val out = ArrayList<Segment>()
        if (segs != null) for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            val st = sec(s.opt("start")); val en = sec(s.opt("end"))
            val nested = ArrayList<Word>()
            s.optJSONArray("words")?.let { readWords(it, nested) }
            out += Segment(st, max(en, st + 1), s.optString("text").trim(), nested)
        }
        if (out.isEmpty()) {
            val text = o.optString("text").trim()
            if (text.isEmpty()) return emptyList()
            // no segments: build one from the words (or the whole duration)
            val end = words.lastOrNull()?.endMs ?: sec(o.opt("duration"))
            return listOf(Segment(words.firstOrNull()?.startMs ?: 0, max(end, 1), text, words))
        }
        if (words.isEmpty()) return out
        // attach top-level words to segments by midpoint
        return out.mapIndexed { i, s ->
            if (s.words.isNotEmpty()) s else {
                val nextStart = out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                s.copy(words = words.filter { val mid = (it.startMs + it.endMs) / 2; (i == 0 || mid >= s.startMs) && mid < nextStart })
            }
        }
    }

    private fun readWords(a: JSONArray, into: MutableList<Word>) {
        for (j in 0 until a.length()) {
            val w = a.optJSONObject(j) ?: continue
            val t = (w.optString("word").ifEmpty { w.optString("text") }).trim()
            if (t.isEmpty()) continue
            into += Word(sec(w.opt("start")), sec(w.opt("end")), t)
        }
    }

    /**
     * Gemini: we ask for `application/json` = [{"start": 1.2, "end": 3.4, "text": "..."}].
     * Accepts seconds as numbers or strings ("1.2", "00:01.200", "00:00:01,200", "1:02"),
     * a wrapping object ({"segments":[...]}) and ```json fences.
     */
    fun gemini(text: String): List<Segment> {
        val body = stripFences(text)
        val arr: JSONArray = runCatching { JSONArray(body) }.getOrElse {
            val o = runCatching { JSONObject(body) }.getOrElse { throw TranscriptParseException("Gemini reply is not JSON") }
            o.optJSONArray("segments") ?: o.optJSONArray("captions") ?: throw TranscriptParseException("no segments in Gemini reply")
        }
        val out = ArrayList<Segment>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val t = s.optString("text").trim()
            if (t.isEmpty()) continue
            val st = clock(s.opt("start") ?: s.opt("start_time"))
            val en = clock(s.opt("end") ?: s.opt("end_time"))
            out += Segment(st, max(en, st + 1), t)
        }
        return out.sortedBy { it.startMs }
    }

    /** Hugging Face whisper pipeline: {"text": "...", "chunks": [{"timestamp": [0.0, 2.5], "text": "..."}]} */
    fun huggingFace(json: String): List<Segment> {
        val o = runCatching { JSONObject(json) }.getOrElse { throw TranscriptParseException("HF reply is not JSON") }
        val ch = o.optJSONArray("chunks")
        if (ch == null || ch.length() == 0) {
            val t = o.optString("text").trim()
            return if (t.isEmpty()) emptyList() else listOf(Segment(0, 1, t))
        }
        return (0 until ch.length()).mapNotNull { i ->
            val c = ch.getJSONObject(i)
            val ts = c.optJSONArray("timestamp")
            val st = sec(ts?.opt(0)); val en = if (ts == null || ts.isNull(1)) st + 2000 else sec(ts.opt(1))
            c.optString("text").trim().takeIf { it.isNotEmpty() }?.let { Segment(st, max(en, st + 1), it) }
        }
    }

    fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            t = t.substringBeforeLast("```")
        }
        return t.trim()
    }

    private fun sec(v: Any?): Long = when (v) {
        is Number -> (v.toDouble() * 1000).roundToLong()
        is String -> clock(v)
        else -> 0L
    }

    /** "83.5" | "01:23.5" | "00:01:23,500" | 83.5 -> ms */
    fun clock(v: Any?): Long {
        if (v is Number) return (v.toDouble() * 1000).roundToLong()
        val s = (v as? String)?.trim()?.replace(',', '.')?.removeSuffix("s") ?: return 0L
        if (s.isEmpty()) return 0L
        val parts = s.split(':')
        return runCatching {
            var secs = 0.0
            for (p in parts) secs = secs * 60 + p.toDouble()
            (secs * 1000).roundToLong()
        }.getOrDefault(0L)
    }
}

/** Turns provider segments into on-screen captions (short, readable chunks) on the timeline. */
object CaptionBuilder {
    const val MAX_CHARS = 32
    const val MAX_MS = 3500L

    fun build(segs: List<Segment>, offsetMs: Long = 0, maxChars: Int = MAX_CHARS, maxMs: Long = MAX_MS): List<Caption> {
        val out = ArrayList<Caption>()
        for (s in segs) {
            val words = if (s.words.isNotEmpty()) s.words else evenWords(s)
            var cur = ArrayList<Word>()
            fun flush() {
                if (cur.isEmpty()) return
                val st = cur.first().startMs; val en = max(cur.last().endMs, st + MIN_CLIP_MS)
                out += Caption(TimelineOps.idGen(), st + offsetMs, en + offsetMs, cur.joinToString(" ") { it.text },
                    cur.map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) })
                cur = ArrayList()
            }
            for (wd in words) {
                val len = cur.sumOf { it.text.length + 1 } + wd.text.length
                if (cur.isNotEmpty() && (len > maxChars || wd.endMs - cur.first().startMs > maxMs)) flush()
                cur.add(wd)
                if (wd.text.endsWith('.') || wd.text.endsWith('?') || wd.text.endsWith('!') || wd.text.endsWith('।')) flush()
            }
            flush()
        }
        // never overlap: clip each caption's end to the next start
        return out.sortedBy { it.startMs }.let { list ->
            list.mapIndexed { i, c ->
                val next = list.getOrNull(i + 1)?.startMs
                if (next != null && c.endMs > next && next - c.startMs >= MIN_CLIP_MS) c.copy(endMs = next) else c
            }
        }
    }

    /** Spreads a segment's time evenly over its words, weighted by length (no word timestamps available). */
    fun evenWords(s: Segment): List<Word> {
        val toks = s.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val total = toks.sumOf { it.length + 1 }.toDouble()
        var acc = 0.0
        return toks.map { t ->
            val st = s.startMs + ((acc / total) * (s.endMs - s.startMs)).roundToLong()
            acc += t.length + 1
            val en = s.startMs + ((acc / total) * (s.endMs - s.startMs)).roundToLong()
            Word(st, en, t)
        }
    }

    /** Index of the word being spoken at [t] (karaoke highlight); words without timing are spread evenly. */
    fun activeWord(c: Caption, t: Long): Int {
        val ws = c.words.ifEmpty { evenWords(Segment(c.startMs, c.endMs, c.text)) }
        if (ws.isEmpty()) return -1
        for (i in ws.indices.reversed()) if (t >= ws[i].startMs) return i
        return 0
    }
}

/** Hinglish: romanise Hindi caption lines through the user's chat model, keeping every timing. */
object Romanizer {
    fun prompt(lines: List<String>): String = buildString {
        append("Transliterate each Hindi line into Hinglish: everyday Roman script as Indians type on WhatsApp ")
        append("(e.g. \"मैं ठीक हूँ\" -> \"Main theek hoon\"). Keep English words as they are. Do NOT translate. ")
        append("Reply with exactly ${lines.size} lines in the form N|text and nothing else.\n\n")
        lines.forEachIndexed { i, l -> append(i + 1).append('|').append(l.replace('\n', ' ')).append('\n') }
    }

    /** Parses "N|text" lines; missing lines keep the original text. */
    fun parse(reply: String, original: List<String>): List<String> {
        val map = HashMap<Int, String>()
        TranscriptParser.stripFences(reply).lines().forEach { line ->
            val m = Regex("^\\s*(\\d+)\\s*[|.:)]\\s*(.*)$").find(line) ?: return@forEach
            val n = m.groupValues[1].toInt()
            val t = m.groupValues[2].trim()
            if (n in 1..original.size && t.isNotEmpty()) map[n - 1] = t
        }
        return original.indices.map { map[it] ?: original[it] }
    }

    /** Applies romanised lines to captions; word timings are re-spread across the new words. */
    fun apply(caps: List<Caption>, lines: List<String>): List<Caption> = caps.mapIndexed { i, c ->
        val t = lines.getOrNull(i) ?: return@mapIndexed c
        if (t == c.text) c else {
            val ws = if (c.words.isNotEmpty()) {
                val toks = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (toks.size == c.words.size) c.words.mapIndexed { k, w -> w.copy(text = toks[k]) }
                else CaptionBuilder.evenWords(Segment(c.startMs, c.endMs, t))
            } else emptyList()
            c.copy(text = t, words = ws)
        }
    }
}
