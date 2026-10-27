package com.gopal.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptParserTest {

    // Shape of OpenAI whisper-1 verbose_json with timestamp_granularities[]=segment&word
    private val openAi = """
    {"task":"transcribe","language":"hindi","duration":5.2,"text":"नमस्ते दोस्तों। आज हम वीडियो बनाएंगे।",
     "segments":[
       {"id":0,"seek":0,"start":0.0,"end":1.8,"text":" नमस्ते दोस्तों।","tokens":[1,2],"temperature":0.0,"avg_logprob":-0.3,"compression_ratio":1.1,"no_speech_prob":0.01},
       {"id":1,"seek":0,"start":1.8,"end":5.2,"text":" आज हम वीडियो बनाएंगे।","tokens":[3],"temperature":0.0,"avg_logprob":-0.2,"compression_ratio":1.0,"no_speech_prob":0.02}],
     "words":[{"word":"नमस्ते","start":0.0,"end":0.82},{"word":"दोस्तों।","start":0.82,"end":1.8},
              {"word":"आज","start":2.0,"end":2.3},{"word":"हम","start":2.3,"end":2.6},{"word":"वीडियो","start":2.6,"end":3.4},{"word":"बनाएंगे।","start":3.4,"end":5.2}]}
    """

    @Test fun whisperVerboseAttachesWordsToSegments() {
        val s = TranscriptParser.whisperVerbose(openAi)
        assertEquals(2, s.size)
        assertEquals(0L to 1_800L, s[0].startMs to s[0].endMs)
        assertEquals("नमस्ते दोस्तों।", s[0].text)
        assertEquals(listOf("नमस्ते", "दोस्तों।"), s[0].words.map { it.text })
        assertEquals(4, s[1].words.size)
        assertEquals(820L, s[0].words[0].endMs)
    }

    @Test fun whisperGroqStyleSegmentsWithoutWords() {
        val groq = """{"text":" Hello bhai","x_groq":{"id":"req_1"},"segments":[{"id":0,"start":0.5,"end":2.25,"text":" Hello bhai"}]}"""
        val s = TranscriptParser.whisperVerbose(groq)
        assertEquals(1, s.size); assertEquals(500L, s[0].startMs); assertEquals(2_250L, s[0].endMs); assertTrue(s[0].words.isEmpty())
    }

    @Test fun whisperWordsOnly() {
        val s = TranscriptParser.whisperVerbose("""{"text":"hi there","words":[{"word":"hi","start":0.1,"end":0.4},{"word":"there","start":0.4,"end":0.9}]}""")
        assertEquals(1, s.size); assertEquals(100L to 900L, s[0].startMs to s[0].endMs); assertEquals(2, s[0].words.size)
        assertTrue(TranscriptParser.whisperVerbose("""{"text":""}""").isEmpty())
    }

    @Test fun geminiPlainFencedAndClockStrings() {
        val a = TranscriptParser.gemini("""[{"start":0,"end":1.5,"text":"नमस्ते"},{"start":1.5,"end":3.25,"text":"दोस्तों"}]""")
        assertEquals(listOf(0L, 1_500L), a.map { it.startMs }); assertEquals(3_250L, a[1].endMs)
        val b = TranscriptParser.gemini("```json\n[{\"start\":\"00:01.200\",\"end\":\"00:00:03,500\",\"text\":\"hello\"}]\n```")
        assertEquals(1_200L to 3_500L, b[0].startMs to b[0].endMs)
        val c = TranscriptParser.gemini("""{"segments":[{"start_time":"1:02","end_time":"1:04.5","text":"x"},{"start":"9","end":"8","text":""}]}""")
        assertEquals(1, c.size); assertEquals(62_000L to 64_500L, c[0].startMs to c[0].endMs)
    }

    @Test(expected = TranscriptParseException::class)
    fun geminiNotJson() { TranscriptParser.gemini("Sorry, I can't do that") }

    @Test fun huggingFaceChunks() {
        val s = TranscriptParser.huggingFace("""{"text":"a b","chunks":[{"timestamp":[0.0,1.2],"text":" a"},{"timestamp":[1.2,null],"text":" b"}]}""")
        assertEquals(2, s.size); assertEquals(1_200L, s[1].startMs); assertEquals(3_200L, s[1].endMs)
    }

    @Test fun clockFormats() {
        assertEquals(83_500L, TranscriptParser.clock("83.5"))
        assertEquals(83_500L, TranscriptParser.clock("01:23.5"))
        assertEquals(83_500L, TranscriptParser.clock("00:01:23,500"))
        assertEquals(2_000L, TranscriptParser.clock("2s"))
        assertEquals(0L, TranscriptParser.clock("abc"))
    }

    @Test fun builderChunksByLengthAndSentence() {
        TimelineOps.idGen = { "c" }
        val segs = TranscriptParser.whisperVerbose(openAi)
        val caps = CaptionBuilder.build(segs, offsetMs = 1_000)
        assertEquals("नमस्ते दोस्तों।", caps[0].text)
        assertEquals(1_000L, caps[0].startMs)                  // offset applied
        assertEquals(1_000L, caps[0].words[0].startMs)
        assertTrue(caps.all { it.text.length <= CaptionBuilder.MAX_CHARS })
        for (i in 0 until caps.size - 1) assertTrue(caps[i].endMs <= caps[i + 1].startMs)
        // a long segment without words is split and timed evenly
        val long = Segment(0, 10_000, (1..20).joinToString(" ") { "word$it" })
        val parts = CaptionBuilder.build(listOf(long))
        assertTrue(parts.size >= 4)
        assertEquals(0L, parts.first().startMs); assertEquals(10_000L, parts.last().endMs)
        assertTrue(parts.all { it.endMs - it.startMs <= CaptionBuilder.MAX_MS + 1 })
    }

    @Test fun activeWordFollowsTime() {
        val c = Caption("c", 0, 3_000, "a b c", listOf(Word(0, 1_000, "a"), Word(1_000, 2_000, "b"), Word(2_000, 3_000, "c")))
        assertEquals(0, CaptionBuilder.activeWord(c, 10))
        assertEquals(1, CaptionBuilder.activeWord(c, 1_500))
        assertEquals(2, CaptionBuilder.activeWord(c, 2_999))
        assertEquals(1, CaptionBuilder.activeWord(Caption("x", 0, 1_000, "aa bb"), 600))   // even timing fallback
    }

    @Test fun romanizerPromptParseApply() {
        val lines = listOf("मैं ठीक हूँ", "आप कैसे हो?")
        val p = Romanizer.prompt(lines)
        assertTrue(p.contains("1|मैं ठीक हूँ") && p.contains("2|आप कैसे हो?") && p.contains("exactly 2 lines"))
        val out = Romanizer.parse("```\n1| Main theek hoon\n2. Aap kaise ho?\n3| extra\n```", lines)
        assertEquals(listOf("Main theek hoon", "Aap kaise ho?"), out)
        assertEquals(listOf("Main theek hoon", "आप कैसे हो?"), Romanizer.parse("1|Main theek hoon", lines))
        val caps = listOf(Caption("a", 0, 900, "मैं ठीक हूँ", listOf(Word(0, 300, "मैं"), Word(300, 600, "ठीक"), Word(600, 900, "हूँ"))))
        val r = Romanizer.apply(caps, listOf("Main theek hoon"))
        assertEquals(listOf("Main", "theek", "hoon"), r[0].words.map { it.text })
        assertEquals(listOf(0L, 300L, 600L), r[0].words.map { it.startMs })
    }
}
