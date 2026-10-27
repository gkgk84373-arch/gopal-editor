package com.gopal.editor

import java.io.File
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * A 3D colour LUT. [data] holds size³ RGB triples (0..1), red changing fastest, then green,
 * then blue — the order .cube files use.
 */
class LutData(val size: Int, val data: FloatArray, val title: String? = null) {
    init { require(data.size == size * size * size * 3) { "LUT data has ${data.size} values, expected ${size * size * size * 3}" } }

    fun at(r: Int, g: Int, b: Int, ch: Int): Float = data[((b * size + g) * size + r) * 3 + ch]

    /**
     * The LUT as one 2D RGBA8 texture: size*size wide, size tall. Blue slice k sits at x = k*size,
     * red runs along x inside the slice, green along y. The shader samples two slices and mixes.
     */
    fun toAtlasRgba(): ByteBuffer {
        val w = size * size
        val buf = ByteBuffer.allocateDirect(w * size * 4).order(ByteOrder.nativeOrder())
        for (g in 0 until size) for (x in 0 until w) {
            val b = x / size; val r = x % size
            for (ch in 0 until 3) buf.put(to8(at(r, g, b, ch)))
            buf.put(255.toByte())
        }
        buf.flip()
        return buf
    }

    /** CPU trilinear lookup (reference for the shader; used by tests). */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        fun split(v: Float): Triple<Int, Int, Float> { val x = v.coerceIn(0f, 1f) * n; val i = x.toInt().coerceAtMost(n - 1).coerceAtLeast(0); return Triple(i, (i + 1).coerceAtMost(n), x - i) }
        val (r0, r1, fr) = split(r); val (g0, g1, fg) = split(g); val (b0, b1, fb) = split(b)
        return FloatArray(3) { ch ->
            fun l(a: Float, c: Float, t: Float) = a + (c - a) * t
            val c00 = l(at(r0, g0, b0, ch), at(r1, g0, b0, ch), fr)
            val c10 = l(at(r0, g1, b0, ch), at(r1, g1, b0, ch), fr)
            val c01 = l(at(r0, g0, b1, ch), at(r1, g0, b1, ch), fr)
            val c11 = l(at(r0, g1, b1, ch), at(r1, g1, b1, ch), fr)
            l(l(c00, c10, fg), l(c01, c11, fg), fb)
        }
    }

    companion object {
        fun to8(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        fun identity(size: Int): LutData {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
            }
            return LutData(size, d, "identity")
        }
    }
}

class CubeParseException(msg: String) : Exception(msg)

/** Adobe/Resolve ".cube" 3D LUT parser (TITLE, LUT_3D_SIZE, DOMAIN_MIN/MAX, comments, any whitespace). */
object CubeLut {
    const val MIN_SIZE = 2
    const val MAX_SIZE = 65   // 65*65 = 4225 px texture width; bigger LUTs are refused

    fun parse(text: String): LutData = parse(text.reader())

    fun parse(reader: Reader): LutData {
        var size = -1
        var title: String? = null
        val dmin = floatArrayOf(0f, 0f, 0f); val dmax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var n = 0
        var lineNo = 0
        reader.buffered().useLines { lines ->
            for (raw in lines) {
                lineNo++
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val first = line.first()
                if (first.isLetter()) {
                    val key = line.substringBefore(' ').substringBefore('\t').uppercase()
                    val rest = line.substring(key.length).trim()
                    when (key) {
                        "TITLE" -> title = rest.trim('"')
                        "LUT_3D_SIZE" -> {
                            size = rest.toIntOrNull() ?: throw CubeParseException("line $lineNo: bad LUT_3D_SIZE")
                            if (size !in MIN_SIZE..MAX_SIZE) throw CubeParseException("LUT_3D_SIZE $size not supported (allowed $MIN_SIZE..$MAX_SIZE)")
                            data = FloatArray(size * size * size * 3)
                        }
                        "LUT_1D_SIZE" -> throw CubeParseException("1D LUTs are not supported; use a 3D .cube")
                        "DOMAIN_MIN" -> nums(rest, lineNo).copyInto(dmin)
                        "DOMAIN_MAX" -> nums(rest, lineNo).copyInto(dmax)
                        "LUT_3D_INPUT_RANGE" -> { val v = rest.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }; if (v.size == 2) { dmin.fill(v[0]); dmax.fill(v[1]) } }
                        else -> { /* unknown keyword: ignore (some tools add their own) */ }
                    }
                    continue
                }
                val d = data ?: throw CubeParseException("line $lineNo: values before LUT_3D_SIZE")
                val v = nums(line, lineNo)
                if (n >= size * size * size) throw CubeParseException("line $lineNo: more than ${size * size * size} entries")
                for (ch in 0 until 3) {
                    val span = dmax[ch] - dmin[ch]
                    if (span <= 0f) throw CubeParseException("DOMAIN_MAX must be above DOMAIN_MIN")
                    d[n * 3 + ch] = ((v[ch] - dmin[ch]) / span).coerceIn(0f, 1f)
                }
                n++
            }
        }
        if (size < 0) throw CubeParseException("missing LUT_3D_SIZE")
        if (n != size * size * size) throw CubeParseException("expected ${size * size * size} entries, found $n")
        return LutData(size, data!!, title)
    }

    private fun nums(s: String, lineNo: Int): FloatArray {
        val p = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (p.size < 3) throw CubeParseException("line $lineNo: expected 3 numbers")
        return FloatArray(3) { p[it].toFloatOrNull() ?: throw CubeParseException("line $lineNo: '${p[it]}' is not a number") }
    }
}

/** Parsed LUTs by file path (parsed once, shared by preview and export). */
object LutCache {
    private val map = ConcurrentHashMap<String, LutData>()
    fun get(path: String?): LutData? {
        if (path == null) return null
        map[path]?.let { return it }
        val f = File(path)
        if (!f.isFile) return null
        return runCatching { f.reader().use { CubeLut.parse(it) } }.getOrNull()?.also { map[path] = it }
    }
    fun put(path: String, lut: LutData) { map[path] = lut }
}
