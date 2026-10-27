package com.gopal.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class SpeedMathTest {
    @Before fun ids() { var n = 0; TimelineOps.idGen = { "id${n++}" } }

    @Test fun constantSpeedDurations() {
        assertEquals(5000L, SpeedMath.outDur(10_000, 2f, null))
        assertEquals(100_000L, SpeedMath.outDur(10_000, 0.1f, null))
        assertEquals(100L, SpeedMath.outDur(10_000, 100f, null))
        assertEquals(100L, SpeedMath.outDur(10_000, 500f, null))     // clamped to 100x
        assertEquals(10_000L, SpeedMath.outDur(10_000, 1f, null))
    }

    @Test fun constantMappingRoundTrips() {
        for (t in listOf(0L, 1234L, 4999L, 5000L)) assertEquals(t, SpeedMath.srcToOut(10_000, 2f, null, SpeedMath.outToSrc(10_000, 2f, null, t)))
        assertEquals(3000L, SpeedMath.outToSrc(10_000, 2f, null, 1500))
    }

    @Test fun flatCurveEqualsSource() {
        assertEquals(10_000L, SpeedMath.outDur(10_000, 1f, SpeedCurves.flat))
        assertEquals(4321L, SpeedMath.srcToOut(10_000, 1f, SpeedCurves.flat, 4321))
    }

    @Test fun curveDurationMatchesNumericIntegral() {
        for ((name, pts) in SpeedCurves.presets) {
            val src = 7_000L
            var integral = 0.0
            val n = 70_000
            for (i in 0 until n) { val x = (i + 0.5) / n; integral += (src.toDouble() / n) / SpeedCurves.valueAt(pts, x.toFloat()) }
            val d = SpeedMath.outDur(src, 1f, pts)
            assertTrue("$name: $d vs $integral", abs(d - integral) / integral < 0.02)
        }
    }

    @Test fun curveMappingIsMonotonicAndInvertible() {
        val pts = SpeedCurves.preset("Hero")!!
        val src = 9_000L
        var prev = -1L
        for (s in 0..src step 250) {
            val o = SpeedMath.srcToOut(src, 1f, pts, s)
            assertTrue(o >= prev); prev = o
            val back = SpeedMath.outToSrc(src, 1f, pts, o)
            assertTrue("s=$s back=$back", abs(back - s) <= 25)   // within a fraction of one 40 ms step at ≤10x
        }
        assertEquals(SpeedMath.outDur(src, 1f, pts), SpeedMath.srcToOut(src, 1f, pts, src))
    }

    @Test fun scheduleMatchesTimelineLength() {
        val pts = SpeedCurves.preset("Montage")!!
        val src = 5_000L
        val (starts, speeds) = SpeedMath.schedule(src, 1f, pts)
        assertEquals(0L, starts[0])
        var out = 0.0
        for (i in starts.indices) {
            val a = starts[i] / 1000.0; val b = if (i + 1 < starts.size) starts[i + 1] / 1000.0 else src.toDouble()
            out += (b - a) / speeds[i]
        }
        assertEquals(SpeedMath.outDur(src, 1f, pts).toDouble(), out, 1.0)
    }

    @Test fun curveHelpers() {
        val n = SpeedCurves.normalize(listOf(SpeedPt(0.7f, 50f), SpeedPt(0.2f, 0.01f)))
        assertEquals(0f, n.first().x); assertEquals(1f, n.last().x)
        assertEquals(CURVE_MAX, n.last().y); assertEquals(CURVE_MIN, n.first().y)
        assertEquals(2f, SpeedCurves.valueAt(listOf(SpeedPt(0f, 1f), SpeedPt(1f, 4f)), 0.5f), 1e-4f)   // log-linear
        val m = SpeedCurves.mirror(SpeedCurves.preset("Flash in")!!)
        assertEquals(SpeedCurves.preset("Flash in")!!.first().y, m.last().y)
        val s = SpeedCurves.slice(listOf(SpeedPt(0f, 1f), SpeedPt(1f, 4f)), 0.5f, 1f)
        assertEquals(2f, s.first().y, 1e-4f); assertEquals(4f, s.last().y, 1e-4f)
    }

    @Test fun audioRule() {
        assertTrue(SpeedMath.audioKept(2f, null))
        assertTrue(SpeedMath.audioKept(0.25f, null))
        assertFalse(SpeedMath.audioKept(5f, null))
        assertFalse(SpeedMath.audioKept(0.1f, null))
        assertFalse(SpeedMath.audioKept(1f, SpeedCurves.flat))
    }

    @Test fun sliderLogScale() {
        assertEquals(1f, SpeedMath.sliderToSpeed(1f / 3f), 1e-4f)
        assertEquals(0.1f, SpeedMath.sliderToSpeed(0f), 1e-4f)
        assertEquals(100f, SpeedMath.sliderToSpeed(1f), 1e-3f)
        assertEquals(0.5f, SpeedMath.speedToSlider(SpeedMath.sliderToSpeed(0.5f)), 1e-4f)
        assertEquals(2f, snapSpeed(2.03f))
    }

    // ---------------- timeline integration ----------------
    private fun tl2(): Timeline {
        var t = TimelineOps.addVideo(Timeline(), "a", "A", 10_000)
        t = TimelineOps.addVideo(t, "b", "B", 4_000)
        t = t.copy(captions = listOf(
            Caption("c1", 2000, 4000, "inside A", listOf(Word(2000, 3000, "inside"), Word(3000, 4000, "A"))),
            Caption("c2", 11_000, 12_000, "inside B")))
        return t
    }

    @Test fun speedChangesTimelineAndCaptions() {
        val t = TimelineOps.setSpeed(tl2(), "id0", 2f, null)
        assertEquals(5000L, t.video[0].durMs)
        assertEquals(9000L, t.durationMs)
        val c1 = t.captions.first { it.id == "c1" }
        assertEquals(1000L, c1.startMs); assertEquals(2000L, c1.endMs)
        assertEquals(1500L, c1.words[1].startMs)
        val c2 = t.captions.first { it.id == "c2" }
        assertEquals(6000L, c2.startMs); assertEquals(7000L, c2.endMs)   // after clip A: shifted by -5 s
        // back to 1x restores the original timings
        val back = TimelineOps.setSpeed(t, "id0", 1f, null)
        assertEquals(tl2().captions, back.captions)
    }

    @Test fun curveSpeedOnTimeline() {
        val t = TimelineOps.setSpeed(tl2(), "id0", 1f, SpeedCurves.preset("Bullet"), "Bullet")
        val c = t.video[0]
        assertEquals(SpeedMath.outDur(10_000, 1f, c.curve), c.durMs)
        assertEquals("Bullet", c.curveName)
        assertEquals(c.durMs + 4000, t.durationMs)
        assertEquals(c.durMs, TimelineOps.locate(t, c.durMs)!!.let { if (it.index == 1) c.durMs else -1 })
    }

    @Test fun splitSpedClipKeepsLengthAndSource() {
        var t = TimelineOps.setSpeed(tl2(), "id0", 2f, null)
        val (s, right) = TimelineOps.split(t, "id0", 2000)!!
        assertEquals(4000L, s.video[0].outMs)          // 2 s of timeline = 4 s of source at 2x
        assertEquals(4000L, s.video[1].inMs)
        assertEquals(2000L, s.video[0].durMs); assertEquals(3000L, s.video[1].durMs)
        assertEquals(right, s.video[1].id)
        // curve split: total length stays within one step
        val fresh = tl2()
        t = TimelineOps.setSpeed(fresh, fresh.video[0].id, 1f, SpeedCurves.preset("Montage"), "Montage")
        val before = t.durationMs
        val (s2, _) = TimelineOps.split(t, t.video[0].id, t.video[0].durMs / 3)!!
        assertTrue(abs(s2.durationMs - before) <= 60)
    }

    @Test fun trimSpedClipMovesSourceFaster() {
        val t = TimelineOps.setSpeed(tl2(), "id0", 2f, null)
        val tr = TimelineOps.trimStart(t, "id0", 1000)
        assertEquals(2000L, tr.video[0].inMs)
        assertEquals(4000L, tr.video[0].durMs)
        assertEquals(1_000L, TimelineOps.sourcePos(t, 500)!!.second)
    }

    @Test fun freezeFrameInsertsAndShiftsCaptions() {
        val (t, id) = TimelineOps.insertFreeze(tl2(), 5000, "file:///f.mp4", "Freeze", 3000)
        assertEquals(4, t.video.size)          // A split in two + freeze + B
        assertEquals(id, t.video[1].id)
        assertTrue(t.video[1].still)
        assertEquals(5000L, t.videoStart(1))
        assertEquals(17_000L, t.durationMs)
        assertEquals(2000L, t.captions.first { it.id == "c1" }.startMs)    // before: unchanged
        assertEquals(14_000L, t.captions.first { it.id == "c2" }.startMs)  // after: +3 s
        // at a clip boundary no split happens
        val (t2, _) = TimelineOps.insertFreeze(tl2(), 10_000, "f", "F", 1000)
        assertEquals(3, t2.video.size)
        assertTrue(t2.video[1].still)
        // speed is refused on a freeze clip
        assertEquals(t, TimelineOps.setSpeed(t, id, 4f, null))
    }

    @Test fun reverseKeepsLength() {
        val t0 = TimelineOps.trimStart(tl2(), "id0", 2000)
        val t = TimelineOps.replaceWithReversed(t0, "id0", "file:///r.mp4", 8000)
        val c = t.video[0]
        assertTrue(c.reversed); assertEquals(0L, c.inMs); assertEquals(8000L, c.outMs)
        assertEquals(t0.durationMs, t.durationMs)
        assertEquals(t0.captions, t.captions)
        assertTrue(ReverseRules.allowed(60_000)); assertFalse(ReverseRules.allowed(61_000))
    }

    @Test fun jsonRoundTripWithStage2Fields() {
        var t = TimelineOps.setSpeed(tl2(), "id0", 1f, SpeedCurves.preset("Hero"), "Hero")
        t = TimelineOps.setSpeed(t, "id1", 3.5f, null)
        val fx = ClipFx("cinematic", 0.6f, "/data/x.cube", "Teal", 0.3f,
            Adjust(brightness = 0.2f, hue = -40f, blur = 0.1f, bandSat = List(8) { if (it == 5) -0.5f else 0f }))
        t = TimelineOps.setFx(t, "id0", fx)
        val back = TimelineJson.decode(TimelineJson.encode(t))
        assertEquals(t.video.map { it.copy() }, back.video)
        assertEquals(t.captions, back.captions)
        assertEquals(fx, back.video[0].fx)
    }

    @Test fun phase1JsonStillLoads() {
        val v1 = """{"v":1,"aspect":"R9_16","fill":false,"style":"classic","lang":"hi","video":[{"id":"a","uri":"content://x","name":"x.mp4","src":5000,"in":0,"out":5000,"vol":1}],"audio":[],"captions":[]}"""
        val t = TimelineJson.decode(v1)
        assertEquals(1, t.video.size)
        val c = t.video[0]
        assertEquals(1f, c.speed); assertEquals(null, c.curve); assertEquals(ClipFx(), c.fx); assertFalse(c.still)
        assertEquals(5000L, c.durMs)
    }
}
