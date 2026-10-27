package com.gopal.editor

import org.json.JSONArray
import org.json.JSONObject

/* Prompts and reply parsing for the AI tools (pure Kotlin, unit-tested). */

object ScriptWriter {
    val LANGS = listOf("hi" to "हिंदी", "hinglish" to "Hinglish", "en" to "English")
    val LENGTHS = listOf(15, 30, 60, 90)

    fun system(lang: String) = when (lang) {
        "en" -> "You write short, punchy video scripts for Instagram Reels and short vertical videos. Write in simple English."
        "hinglish" -> "You write short, punchy Reels scripts in Hinglish (Hindi written in Roman letters, the way Indians type on WhatsApp)."
        else -> "आप Instagram Reels और छोटे वर्टिकल वीडियो के लिए छोटी, दमदार स्क्रिप्ट लिखते हैं। सरल, बोलचाल की हिंदी में, देवनागरी लिपि में लिखें।"
    }

    fun prompt(topic: String, lang: String, seconds: Int, tone: String = ""): String = buildString {
        append("Topic: ").append(topic.trim()).append('\n')
        append("Length: about ").append(seconds).append(" seconds of speaking (about ").append(seconds * 2 + 5).append(" words).\n")
        if (tone.isNotBlank()) append("Tone: ").append(tone.trim()).append('\n')
        append("Format:\nHOOK: one line that stops the scroll (first 3 seconds)\nSCRIPT: the lines to speak, one per line\nCTA: one closing line (like/follow/comment)\n")
        append(when (lang) { "en" -> "Language: English."; "hinglish" -> "Language: Hinglish in Roman letters."; else -> "Language: Hindi in Devanagari." })
        append(" No stage directions, no emojis in the spoken lines, no markdown.")
    }

    /** Removes markdown the models add anyway (** and #). */
    fun clean(reply: String): String = TranscriptParser.stripFences(reply).lines()
        .joinToString("\n") { it.replace("**", "").replace(Regex("^#+\\s*"), "") }.trim()
}

data class HookIdeas(val hooks: List<String>, val titles: List<String>, val hashtags: List<String>) {
    val isEmpty: Boolean get() = hooks.isEmpty() && titles.isEmpty() && hashtags.isEmpty()

    companion object {
        fun prompt(topic: String, captions: List<String>, lang: String): String = buildString {
            append("Give ideas for a short vertical video (Reel/Short).\n")
            if (topic.isNotBlank()) append("Topic: ").append(topic.trim()).append('\n')
            if (captions.isNotEmpty()) append("What is said in the video: ").append(captions.joinToString(" ").take(1500)).append('\n')
            append(if (lang == "en") "Write in English. " else "Write hooks and titles in simple Hindi (Devanagari); hashtags can mix Hindi and English. ")
            append("Reply ONLY with JSON: {\"hooks\": [5 scroll-stopping first lines], \"titles\": [5 short titles], \"hashtags\": [15 hashtags starting with #]}")
        }

        /** Tolerant: JSON (even inside ``` or extra words), else HOOKS:/TITLES:/HASHTAGS: sections. */
        fun parse(reply: String): HookIdeas {
            val s = TranscriptParser.stripFences(reply)
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a >= 0 && b > a) runCatching {
                val o = JSONObject(s.substring(a, b + 1))
                fun list(k: String): List<String> {
                    val arr = o.optJSONArray(k) ?: return emptyList()
                    return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
                }
                val r = HookIdeas(list("hooks"), list("titles"), list("hashtags").map(::tag).distinct())
                if (!r.isEmpty) return r
            }
            val hooks = ArrayList<String>(); val titles = ArrayList<String>(); val tags = ArrayList<String>()
            var cur: MutableList<String>? = null
            s.lines().forEach { raw ->
                val l = raw.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim()
                val up = l.uppercase()
                when {
                    up.startsWith("HOOK") -> cur = hooks
                    up.startsWith("TITLE") -> cur = titles
                    up.startsWith("HASHTAG") -> { cur = tags; tags += Regex("#\\S+").findAll(l).map { it.value } }
                    l.isEmpty() -> {}
                    cur === tags -> tags += Regex("#\\S+").findAll(l).map { it.value }
                    else -> cur?.add(l)
                }
            }
            return HookIdeas(hooks, titles, tags.map(::tag).distinct())
        }

        private fun tag(t: String): String = "#" + t.trim().trimStart('#').replace(Regex("\\s+"), "")
    }
}

/** Caption translate + language correction. Timings stay; only text changes. Results are previewed before applying. */
object CaptionAi {
    const val CHUNK = 40
    val TARGETS = listOf(
        "Hindi (Devanagari)" to "हिंदी", "English" to "English", "Hinglish (Hindi in Roman letters)" to "Hinglish",
        "Punjabi (Gurmukhi)" to "ਪੰਜਾਬੀ", "Marathi" to "मराठी", "Gujarati" to "ગુજરાતી", "Bengali" to "বাংলা",
        "Tamil" to "தமிழ்", "Telugu" to "తెలుగు", "Urdu" to "اردو"
    )

    fun translatePrompt(lines: List<String>, target: String): String = buildString {
        append("Translate each subtitle line into ").append(target).append(". Keep it short and natural for on-screen subtitles. ")
        append("Keep names as they are. Reply with exactly ${lines.size} lines in the form N|text and nothing else.\n\n")
        lines.forEachIndexed { i, l -> append(i + 1).append('|').append(l.replace('\n', ' ')).append('\n') }
    }

    fun fixPrompt(lines: List<String>): String = buildString {
        append("These are auto-generated video subtitles. Fix ONLY spelling, grammar, matras and punctuation in each line. ")
        append("Do NOT translate, do NOT change the script (Devanagari stays Devanagari, Roman stays Roman), do NOT add or remove meaning. ")
        append("If a line is already correct, return it unchanged. Reply with exactly ${lines.size} lines in the form N|text and nothing else.\n\n")
        lines.forEachIndexed { i, l -> append(i + 1).append('|').append(l.replace('\n', ' ')).append('\n') }
    }

    fun chunks(lines: List<String>): List<IntRange> = lines.indices.chunked(CHUNK).map { it.first()..it.last() }

    /** Preview rows: index, old text, new text. */
    data class Change(val index: Int, val old: String, val new: String) { val changed: Boolean get() = old.trim() != new.trim() }

    fun diff(caps: List<Caption>, newLines: List<String>): List<Change> = caps.mapIndexed { i, c -> Change(i, c.text, newLines.getOrNull(i) ?: c.text) }

    /** Applies the accepted lines; word timings are re-spread when the words changed. */
    fun apply(caps: List<Caption>, newLines: List<String>): List<Caption> = Romanizer.apply(caps, newLines)
}

/** Picture sizes asked from the image provider for each aspect ratio (multiples of 16). */
object AiImageSize {
    fun forAspect(a: Aspect, sticker: Boolean): Pair<Int, Int> = if (sticker) 768 to 768 else when (a) {
        Aspect.R9_16 -> 720 to 1280
        Aspect.R16_9 -> 1280 to 720
        Aspect.R1_1 -> 1024 to 1024
        Aspect.R4_5 -> 864 to 1080
    }

    fun prompt(user: String, sticker: Boolean): String =
        if (sticker) user.trim() + ", sticker style, bold outline, centered subject, plain white background, no text"
        else user.trim() + ", vertical video background, high detail, no text, no watermark"
}
