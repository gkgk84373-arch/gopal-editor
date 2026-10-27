package com.gopal.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineOpsTest {
    private var n = 0
    @Before fun ids() { n = 0; TimelineOps.idGen = { "id${++n}" } }

    private fun base(): Timeline {
        n = 0
        var t = Timeline()
        t = TimelineOps.addVideo(t, "content://a", "a.mp4", 10_000)   // id1
        t = TimelineOps.addVideo(t, "content://b", "b.mp4", 6_000)    // id2
        return t
    }

    @Test fun magneticPositionsAndLocate() {
        val t = base()
        assertEquals(16_000, t.durationMs)
        assertEquals(10_000, t.videoStart(1))
        assertEquals(Located(0, 9_999), TimelineOps.locate(t, 9_999))
        assertEquals(Located(1, 0), TimelineOps.locate(t, 10_000))
        assertEquals(Located(1, 6_000), TimelineOps.locate(t, 99_000))   // past the end -> end of last clip
        assertEquals(1 to 2_500L, TimelineOps.sourcePos(t, 12_500))
    }

    @Test fun splitVideoKeepsSourceRanges() {
        val (t, right) = TimelineOps.split(base(), "id1", 4_000)!!
        assertEquals(3, t.video.size)
        assertEquals(0L to 4_000L, t.video[0].inMs to t.video[0].outMs)
        assertEquals(4_000L to 10_000L, t.video[1].inMs to t.video[1].outMs)
        assertEquals(right, t.video[1].id)
        assertEquals(16_000, t.durationMs)                              // split never changes length
        // split inside the 2nd clip uses that clip's local offset
        val (t2, _) = TimelineOps.split(t, "id2", 13_000)!!
        assertEquals(listOf(0L, 4_000L, 0L, 3_000L), t2.video.map { it.inMs })
    }

    @Test fun splitRejectsEdgesAndTinyPieces() {
        assertNull(TimelineOps.split(base(), "id1", 0))
        assertNull(TimelineOps.split(base(), "id1", 10_000))
        assertNull(TimelineOps.split(base(), "id1", 50))                // left piece < MIN_CLIP_MS
        assertNull(TimelineOps.split(base(), "id1", 9_950))
        assertNull(TimelineOps.split(base(), "nope", 5_000))
    }

    @Test fun splitAtPlayheadFallsBackToVideoUnderPlayhead() {
        val t = TimelineOps.addAudio(base(), "content://m", "m.mp3", 30_000, 0)   // id3, clipped to 16 s
        // selected audio contains t -> splits audio
        val (a, _) = TimelineOps.splitAtPlayhead(t, "id3", 5_000)!!
        assertEquals(2, a.audio.size); assertEquals(2, a.video.size)
        // selection that doesn't span t -> video clip under the playhead
        val t2 = TimelineOps.addCaption(t, 0, "hi", 1_000)
        val cap = t2.captions[0].id
        val (b, _) = TimelineOps.splitAtPlayhead(t2, cap, 12_000)!!
        assertEquals(3, b.video.size)
    }

    @Test fun splitAudioMovesRightHalf() {
        val t = TimelineOps.addAudio(base(), "content://m", "m.mp3", 8_000, 2_000)
        val id = t.audio[0].id
        val (s, r) = TimelineOps.split(t, id, 5_000)!!
        val right = s.audio.first { it.id == r }
        assertEquals(3_000, right.inMs); assertEquals(5_000, right.startMs); assertEquals(8_000, right.outMs)
        assertEquals(3_000, s.audio[0].outMs)
    }

    @Test fun audioIsClippedToVideoLengthWhenAdded() {
        val t = TimelineOps.addAudio(base(), "m", "m", 60_000, 10_000)
        assertEquals(6_000, t.audio[0].durMs)
    }

    @Test fun splitCaptionByWordTimes() {
        val c = Caption("c", 1_000, 3_000, "one two three", listOf(Word(1_000, 1_500, "one"), Word(1_500, 2_200, "two"), Word(2_200, 3_000, "three")))
        val t = base().copy(captions = listOf(c))
        val (s, _) = TimelineOps.split(t, "c", 2_000)!!
        assertEquals("one two", s.captions[0].text)          // "two" starts before the cut -> stays left
        assertEquals("three", s.captions[1].text)
        assertEquals(2_000L to 3_000L, s.captions[1].startMs to s.captions[1].endMs)
        assertEquals(listOf("three"), s.captions[1].words.map { it.text })
    }

    @Test fun splitCaptionTextHalves() {
        val c = Caption("c", 0, 4_000, "a b c d", listOf(Word(0, 1_000, "a"), Word(1_000, 2_000, "b"), Word(2_000, 3_000, "c"), Word(3_000, 4_000, "d")))
        val (l, r) = TimelineOps.splitText(c, 2_000)
        assertEquals("a b", l); assertEquals("c d", r)
        // no word timings: proportional by time, at a space
        val (l2, r2) = TimelineOps.splitText(Caption("x", 0, 1_000, "w1 w2 w3 w4"), 750)
        assertEquals("w1 w2 w3", l2); assertEquals("w4", r2)
    }

    @Test fun trimStartAndEndClampToSource() {
        var t = TimelineOps.trimStart(base(), "id1", 2_500)
        assertEquals(2_500, t.video[0].inMs)
        assertEquals(7_500, t.video[0].durMs)
        assertEquals(7_500, t.videoStart(1))                       // magnetic: next clip moves left
        t = TimelineOps.trimStart(t, "id1", -99_000)               // can't go before source start
        assertEquals(0, t.video[0].inMs)
        t = TimelineOps.trimStart(t, "id1", 99_000)                // keeps at least MIN_CLIP_MS
        assertEquals(MIN_CLIP_MS, t.video[0].durMs)
        var e = TimelineOps.trimEnd(base(), "id2", -2_000)
        assertEquals(4_000, e.video[1].outMs)
        e = TimelineOps.trimEnd(e, "id2", 50_000)                  // can't pass source end
        assertEquals(6_000, e.video[1].outMs)
    }

    @Test fun trimAudioLeftEdgeMovesStart() {
        val t0 = TimelineOps.addAudio(base(), "m", "m", 20_000, 3_000)   // in 0..13000 at 3 s
        val id = t0.audio[0].id
        val t = TimelineOps.trimStart(t0, id, 1_000)
        assertEquals(1_000, t.audio[0].inMs); assertEquals(4_000, t.audio[0].startMs)
        val back = TimelineOps.trimStart(t, id, -5_000)                 // limited by source start (in=0)
        assertEquals(0, back.audio[0].inMs); assertEquals(3_000, back.audio[0].startMs)
    }

    @Test fun trimCaption() {
        val t = TimelineOps.addCaption(base(), 1_000, "x", 2_000)
        val id = t.captions[0].id
        assertEquals(1_500, TimelineOps.trimStart(t, id, 500).captions[0].startMs)
        assertEquals(3_000 - MIN_CLIP_MS, TimelineOps.trimStart(t, id, 9_000).captions[0].startMs)
        assertEquals(4_000, TimelineOps.trimEnd(t, id, 1_000).captions[0].endMs)
    }

    @Test fun duplicateAndDelete() {
        val (d, nid) = TimelineOps.duplicate(base(), "id1")!!
        assertEquals(listOf("id1", nid, "id2"), d.video.map { it.id })
        assertEquals(26_000, d.durationMs)
        val a0 = TimelineOps.addAudio(base(), "m", "m", 3_000, 1_000)
        val (a, aid) = TimelineOps.duplicate(a0, a0.audio[0].id)!!
        assertEquals(4_000, a.audio.first { it.id == aid }.startMs)       // placed right after the original
        val c0 = base().copy(captions = listOf(Caption("c", 1_000, 2_000, "hi", listOf(Word(1_000, 2_000, "hi")))))
        val (c, cid) = TimelineOps.duplicate(c0, "c")!!
        val cc = c.captions.first { it.id == cid }
        assertEquals(2_000L to 3_000L, cc.startMs to cc.endMs)
        assertEquals(2_000, cc.words[0].startMs)
        val del = TimelineOps.delete(d, nid)
        assertEquals(listOf("id1", "id2"), del.video.map { it.id })
        assertNull(TimelineOps.duplicate(base(), "missing"))
    }

    @Test fun moveFreeClipsClampToZero() {
        val t = TimelineOps.addCaption(base(), 1_000, "x", 2_000)
        val id = t.captions[0].id
        val m = TimelineOps.moveTo(t, id, -500)
        assertEquals(0L to 2_000L, m.captions[0].startMs to m.captions[0].endMs)
        val r = TimelineOps.reorderVideo(base(), 1, 0)
        assertEquals(listOf("id2", "id1"), r.video.map { it.id })
    }

    @Test fun editTextDropsStaleWordTimings() {
        val t = base().copy(captions = listOf(Caption("c", 0, 1_000, "a b", listOf(Word(0, 500, "a"), Word(500, 1_000, "b")))))
        assertTrue(TimelineOps.setCaptionText(t, "c", "new text").captions[0].words.isEmpty())
        assertEquals(2, TimelineOps.setCaptionText(t, "c", "a b").captions[0].words.size)
    }

    @Test fun zoomClampsAndTicks() {
        assertEquals(Zoom.MAX_PX_PER_S, Zoom.pinch(500f, 3f))
        assertEquals(Zoom.MIN_PX_PER_S, Zoom.pinch(10f, 0.1f))
        assertEquals(120f, Zoom.pinch(60f, 2f))
        assertEquals(1_500L, Zoom.pxToMs(90f, 60f))
        assertEquals(90f, Zoom.msToPx(1_500, 60f))
        assertTrue(Zoom.msToPx(Zoom.tickMs(60f), 60f) >= 70f)
        assertEquals(250L, Zoom.tickMs(600f))
    }

    @Test fun jsonRoundTrip() {
        var t = TimelineOps.addAudio(base(), "content://m", "song.mp3", 30_000, 2_000)
        t = t.copy(captions = listOf(Caption("c", 0, 1_000, "नमस्ते दोस्तों", listOf(Word(0, 400, "नमस्ते"), Word(400, 1_000, "दोस्तों")))),
            aspect = Aspect.R4_5, fill = true, styleId = "karaoke", captionLang = "hinglish")
        t = TimelineOps.setVolume(t, "id1", 0.4f)
        assertEquals(t, TimelineJson.decode(TimelineJson.encode(t)))
        assertEquals(Timeline(), TimelineJson.decode("garbage"))
        assertNotNull(TimelineJson.decode(null))
    }
}
