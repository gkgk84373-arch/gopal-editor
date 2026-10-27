package com.gopal.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LutParserTest {
    private fun cube(size: Int, header: String = "", f: (Float, Float, Float) -> Triple<Float, Float, Float> = { r, g, b -> Triple(r, g, b) }): String {
        val sb = StringBuilder("# made in a test\nTITLE \"Test LUT\"\n$header\nLUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val (x, y, z) = f(r / (size - 1f), g / (size - 1f), b / (size - 1f))
            sb.append("$x $y $z\n")
        }
        return sb.toString()
    }

    @Test fun parsesIdentityWithTitleAndComments() {
        val l = CubeLut.parse(cube(3))
        assertEquals(3, l.size)
        assertEquals("Test LUT", l.title)
        assertEquals(1f, l.at(2, 0, 0, 0), 1e-6f)   // red fastest
        assertEquals(1f, l.at(0, 2, 0, 1), 1e-6f)
        assertEquals(1f, l.at(0, 0, 2, 2), 1e-6f)
        val s = l.sample(0.3f, 0.6f, 0.9f)
        assertEquals(0.3f, s[0], 1e-5f); assertEquals(0.6f, s[1], 1e-5f); assertEquals(0.9f, s[2], 1e-5f)
    }

    @Test fun redFastestOrderIsRespected() {
        // swap red and blue: output r = input b
        val l = CubeLut.parse(cube(4) { r, g, b -> Triple(b, g, r) })
        val s = l.sample(1f, 0f, 0f)
        assertEquals(0f, s[0], 1e-5f); assertEquals(1f, s[2], 1e-5f)
    }

    @Test fun domainIsNormalised() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
            (0 until 8).joinToString("\n") { i -> val r = i % 2; val g = (i / 2) % 2; val b = i / 4; "${r * 2} ${g * 2} ${b * 2}" }
        val l = CubeLut.parse(text)
        assertEquals(1f, l.at(1, 1, 1, 0), 1e-6f)
        assertEquals(0f, l.at(0, 0, 0, 2), 1e-6f)
    }

    @Test fun tabsBlankLinesAndInlineComments() {
        val text = "\n\nLUT_3D_SIZE\t2\n" + (0 until 8).joinToString("\n") { "0.5\t0.25   0.125 # c" } + "\n\n"
        val l = CubeLut.parse(text)
        assertEquals(0.25f, l.at(1, 0, 1, 1), 1e-6f)
    }

    @Test fun errors() {
        fun bad(t: String, contains: String) = try { CubeLut.parse(t); fail("should fail: $t") } catch (e: CubeParseException) {
            assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
        }
        bad("0 0 0\n", "before LUT_3D_SIZE")
        bad("TITLE \"x\"\n", "missing LUT_3D_SIZE")
        bad("LUT_3D_SIZE 2\n0 0 0\n", "expected 8 entries, found 1")
        bad("LUT_1D_SIZE 1024\n", "1D")
        bad("LUT_3D_SIZE 99\n", "not supported")
        bad("LUT_3D_SIZE 2\n0 0 x\n", "not a number")
        bad(cube(2) + "1 1 1\n", "more than 8")
    }

    @Test fun atlasLayout() {
        val l = LutData.identity(4)
        val buf = l.toAtlasRgba()
        assertEquals(16 * 4 * 4, buf.remaining())
        // pixel (x = slice b*4 + r, y = g): b=3, r=1, g=2 -> rgb (1/3, 2/3, 1)
        val x = 3 * 4 + 1; val y = 2
        val o = (y * 16 + x) * 4
        assertEquals(85, buf.get(o).toInt() and 0xFF)
        assertEquals(170, buf.get(o + 1).toInt() and 0xFF)
        assertEquals(255, buf.get(o + 2).toInt() and 0xFF)
        assertEquals(255, buf.get(o + 3).toInt() and 0xFF)
    }

    @Test fun fxParamsCarryLutOnlyWhenSet() {
        val lut = LutData.identity(2)
        assertEquals(0f, FxParams.of(ClipFx(), lut).lutAmt, 0f)
        val p = FxParams.of(ClipFx(lutPath = "/x.cube", lutAmt = 0.4f), lut)
        assertEquals(0.4f, p.lutAmt, 1e-6f)
        assertTrue(!p.neutral)
    }
}
