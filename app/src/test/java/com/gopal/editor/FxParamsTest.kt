package com.gopal.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxParamsTest {
    @Test fun twentyPlusUniqueFilters() {
        val real = Filters.all.filter { it.id != "none" }
        assertTrue(real.size >= 20)
        assertEquals(Filters.all.size, Filters.all.map { it.id }.toSet().size)
        assertEquals(Filters.NONE, Filters.byId("does-not-exist"))
    }

    @Test fun neutralLook() {
        assertTrue(FxParams.NEUTRAL.neutral)
        assertTrue(ClipFx().isNeutral)
        assertTrue(ClipFx(filterId = "vintage", filterAmt = 0f).isNeutral)
        assertFalse(ClipFx(filterId = "vintage").isNeutral)
        assertFalse(ClipFx(adjust = Adjust(sharpen = 0.1f)).isNeutral)
    }

    @Test fun intensityZeroIsOriginalAndOneIsFull() {
        val px = floatArrayOf(0.8f, 0.4f, 0.2f)
        for (f in Filters.all) {
            val off = FxReference.filter(FxParams.of(ClipFx(f.id, 0f), null), px)
            for (i in 0..2) assertEquals(f.id, px[i], off[i], 1e-6f)
            val full = FxReference.filter(FxParams.of(ClipFx(f.id, 1f), null), px)
            full.forEach { assertTrue(f.id, it in 0f..1f) }
        }
        val half = FxReference.filter(FxParams.of(ClipFx("bw", 0.5f), null), px)
        val full = FxReference.filter(FxParams.of(ClipFx("bw", 1f), null), px)
        assertEquals((px[0] + full[0]) / 2, half[0], 1e-5f)
    }

    @Test fun blackAndWhiteIsGrey() {
        val o = FxReference.filter(FxParams.of(ClipFx("bw", 1f), null), floatArrayOf(0.9f, 0.1f, 0.3f))
        assertEquals(o[0], o[1], 1e-5f); assertEquals(o[1], o[2], 1e-5f)
    }

    @Test fun warmIsWarmerCoolIsCooler() {
        val g = floatArrayOf(0.5f, 0.5f, 0.5f)
        val w = FxReference.filter(FxParams.of(ClipFx("warm"), null), g)
        val c = FxReference.filter(FxParams.of(ClipFx("cool"), null), g)
        assertTrue(w[0] > w[2]); assertTrue(c[2] > c[0])
    }

    @Test fun packing() {
        val a = Adjust(brightness = 0.1f, contrast = -0.2f, saturation = 0.3f, hue = 45f, temperature = 0.5f, tint = -0.6f,
            vignette = 0.7f, sharpen = 0.8f, blur = 0.9f, bandSat = List(8) { it / 10f }, bandLight = List(8) { -it / 10f })
        val p = FxParams.of(ClipFx(adjust = a), null)
        assertEquals(listOf(0.1f, -0.2f, 0.3f, 45f), p.a0.toList())
        assertEquals(listOf(0.5f, -0.6f, 0.7f, 0.8f), p.a1.toList())
        assertEquals(0.9f, p.blur)
        assertEquals(listOf(0f, 0.1f, 0.2f, 0.3f), p.bandSatA.toList())
        assertEquals(listOf(0.4f, 0.5f, 0.6f, 0.7f), p.bandSatB.toList())
        assertEquals(-0.7f, p.bandLitB[3])
        assertTrue(p.usesBands)
        assertEquals(0f, p.filterAmt)
    }

    @Test fun hslBandWeightsAddUpToOne() {
        var h = 0f
        while (h < 360f) {
            val sum = (0 until HslBands.N).sumOf { HslBands.weight(it, h).toDouble() }
            assertEquals("hue $h", 1.0, sum, 1e-4)
            h += 7.5f
        }
        assertEquals(1f, HslBands.weight(0, 0f), 1e-6f)
        assertEquals(1f, HslBands.weight(5, 240f), 1e-6f)
        assertEquals(0.5f, HslBands.weight(0, 330f), 1e-4f)   // half way between magenta (300) and red (360)
        assertEquals(0.5f, HslBands.weight(7, 330f), 1e-4f)
        val (lo, hi) = HslBands.widths()
        assertEquals(60f, lo[0]); assertEquals(30f, hi[0])
    }

    @Test fun adjustKeysClampAndReset() {
        var a = Adjust()
        a = AdjustKey.HUE.set(a, 500f); assertEquals(180f, a.hue)
        a = AdjustKey.VIGNETTE.set(a, -1f); assertEquals(0f, a.vignette)
        a = AdjustKey.BRIGHTNESS.set(a, 0.25f); assertEquals("25", AdjustKey.BRIGHTNESS.display(a))
        a = AdjustKey.entries.fold(a) { acc, k -> k.set(acc, 0f) }
        assertTrue(a.isNeutral)
    }

    @Test fun shaderSourceHasEveryUniformTheProgramSets() {
        val names = listOf("uTexSampler", "uLut", "uTexel", "uAspect", "uTime", "uMat0", "uMat1", "uMat2", "uOff", "uF0", "uF1",
            "uShadowTint", "uHighTint", "uFilterAmt", "uA0", "uA1", "uBlur", "uBandHueA", "uBandHueB", "uBandSatA", "uBandSatB",
            "uBandLitA", "uBandLitB", "uBandLoA", "uBandLoB", "uBandHiA", "uBandHiB", "uUseBands", "uLutSize", "uLutAmt")
        names.forEach { assertTrue(it, Regex("uniform\\s+\\w+\\s+$it\\s*;").containsMatchIn(FxGlsl.FRAGMENT)) }
        assertTrue(FxGlsl.VERTEX.contains("aFramePosition"))
    }
}
