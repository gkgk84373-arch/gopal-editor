package com.gopal.editor

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * Speed maths (pure Kotlin, unit-tested).
 *
 *  - Constant speed: 0.1x … 100x. Timeline length = source length / speed.
 *  - Curve speed: points (x = position in the clip's SOURCE range 0..1, y = speed 0.1x…10x),
 *    interpolated in log2 space so dragging a point feels even in both directions.
 *    The curve is evaluated in fixed source steps of CURVE_STEP_MS, and exactly the same steps
 *    drive the export (SpeedProvider), so the timeline length equals the exported length.
 */

const val MIN_SPEED = 0.1f
const val MAX_SPEED = 100f
const val CURVE_MIN = 0.1f
const val CURVE_MAX = 10f
const val CURVE_STEP_MS = 40L

/** Audio rule: pitch-preserved (Sonic) between these speeds, muted outside them, muted for curves. */
const val AUDIO_MIN_SPEED = 0.25f
const val AUDIO_MAX_SPEED = 4f

/** Preview (ExoPlayer) can only play up to 8x; export always uses the exact speed. */
const val PREVIEW_MAX_SPEED = 8f

data class SpeedPt(val x: Float, val y: Float)

object SpeedCurves {
    val presets: List<Pair<String, List<SpeedPt>>> = listOf(
        "Montage" to pts(0f to 1f, 0.25f to 4f, 0.5f to 0.5f, 0.75f to 4f, 1f to 1f),
        "Hero" to pts(0f to 2f, 0.35f to 2f, 0.5f to 0.2f, 0.65f to 2f, 1f to 2f),
        "Bullet" to pts(0f to 4f, 0.4f to 4f, 0.5f to 0.15f, 0.6f to 4f, 1f to 4f),
        "Jump cut" to pts(0f to 1f, 0.3f to 1f, 0.4f to 8f, 0.6f to 8f, 0.7f to 1f, 1f to 1f),
        "Flash in" to pts(0f to 8f, 0.3f to 4f, 0.6f to 1f, 1f to 1f),
        "Flash out" to pts(0f to 1f, 0.4f to 1f, 0.7f to 4f, 1f to 8f)
    )
    fun preset(name: String): List<SpeedPt>? = presets.firstOrNull { it.first == name }?.second
    val flat: List<SpeedPt> get() = pts(0f to 1f, 0.25f to 1f, 0.5f to 1f, 0.75f to 1f, 1f to 1f)
    private fun pts(vararg p: Pair<Float, Float>) = p.map { SpeedPt(it.first, it.second) }

    /** Keeps a curve valid: sorted, x clamped 0..1 with the ends pinned, y clamped to the curve range. */
    fun normalize(p: List<SpeedPt>): List<SpeedPt> {
        if (p.isEmpty()) return flat
        val s = p.map { SpeedPt(it.x.coerceIn(0f, 1f), it.y.coerceIn(CURVE_MIN, CURVE_MAX)) }.sortedBy { it.x }.toMutableList()
        s[0] = s[0].copy(x = 0f); s[s.lastIndex] = s.last().copy(x = 1f)
        if (s.size == 1) s += SpeedPt(1f, s[0].y)
        return s
    }

    /** Speed at x (0..1), log-linear between points. */
    fun valueAt(p: List<SpeedPt>, x: Float): Float {
        if (p.isEmpty()) return 1f
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        for (i in 0 until p.lastIndex) {
            val a = p[i]; val b = p[i + 1]
            if (x <= b.x) {
                val t = if (b.x - a.x < 1e-6f) 1f else (x - a.x) / (b.x - a.x)
                val la = log2(a.y); val lb = log2(b.y)
                return 2f.pow(la + (lb - la) * t)
            }
        }
        return p.last().y
    }

    /** The part of a curve between a and b (0..1), stretched back to 0..1 (used by split). */
    fun slice(p: List<SpeedPt>, a: Float, b: Float): List<SpeedPt> {
        if (b - a < 1e-4f) return listOf(SpeedPt(0f, valueAt(p, a)), SpeedPt(1f, valueAt(p, a)))
        val inner = p.filter { it.x > a && it.x < b }.map { SpeedPt((it.x - a) / (b - a), it.y) }
        return listOf(SpeedPt(0f, valueAt(p, a))) + inner + SpeedPt(1f, valueAt(p, b))
    }

    /** Mirror for a reversed clip. */
    fun mirror(p: List<SpeedPt>): List<SpeedPt> = p.map { SpeedPt(1f - it.x, it.y) }.reversed()

    private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()
}

/** Speed of one clip: constant [speed], or [curve] when it is not null. */
object SpeedMath {
    fun clampSpeed(s: Float) = s.coerceIn(MIN_SPEED, MAX_SPEED)

    /** Number of curve steps for a source length (the last step may be shorter). */
    private fun steps(srcLen: Long) = ((srcLen + CURVE_STEP_MS - 1) / CURVE_STEP_MS).toInt()

    /** Speed of curve step i (sampled at the step's middle). */
    fun stepSpeed(curve: List<SpeedPt>, srcLen: Long, i: Int): Float {
        val s0 = i * CURVE_STEP_MS
        val s1 = min(srcLen, s0 + CURVE_STEP_MS)
        val mid = (s0 + s1) / 2.0
        return SpeedCurves.valueAt(curve, if (srcLen <= 0) 0f else (mid / srcLen).toFloat()).coerceIn(CURVE_MIN, CURVE_MAX)
    }

    /** Timeline (output) length in ms of [srcLen] source ms. */
    fun outDur(srcLen: Long, speed: Float, curve: List<SpeedPt>?): Long {
        if (srcLen <= 0) return 0
        if (curve == null) return max(1L, (srcLen / clampSpeed(speed).toDouble()).roundToLong())
        var acc = 0.0
        for (i in 0 until steps(srcLen)) {
            val len = min(CURVE_STEP_MS, srcLen - i * CURVE_STEP_MS)
            acc += len / stepSpeed(curve, srcLen, i).toDouble()
        }
        return max(1L, acc.roundToLong())
    }

    /** Source offset (ms from the clip's in-point) -> timeline offset (ms from the clip's start). */
    fun srcToOut(srcLen: Long, speed: Float, curve: List<SpeedPt>?, srcOff: Long): Long {
        val o = srcOff.coerceIn(0, srcLen)
        if (curve == null) return (o / clampSpeed(speed).toDouble()).roundToLong()
        var acc = 0.0
        var i = 0
        while (i * CURVE_STEP_MS < o) {
            val s0 = i * CURVE_STEP_MS
            val len = min(CURVE_STEP_MS, o - s0)
            acc += len / stepSpeed(curve, srcLen, i).toDouble()
            i++
        }
        return acc.roundToLong()
    }

    /** Timeline offset -> source offset (inverse of [srcToOut]). */
    fun outToSrc(srcLen: Long, speed: Float, curve: List<SpeedPt>?, outOff: Long): Long {
        if (outOff <= 0) return 0
        if (curve == null) return (outOff * clampSpeed(speed).toDouble()).roundToLong().coerceIn(0, srcLen)
        var acc = 0.0
        for (i in 0 until steps(srcLen)) {
            val s0 = i * CURVE_STEP_MS
            val len = min(CURVE_STEP_MS, srcLen - s0)
            val sp = stepSpeed(curve, srcLen, i).toDouble()
            val stepOut = len / sp
            if (acc + stepOut >= outOff) return (s0 + (outOff - acc) * sp).roundToLong().coerceIn(0, srcLen)
            acc += stepOut
        }
        return srcLen
    }

    /** Average speed (source ms per timeline ms), used to turn a trim drag into source time. */
    fun meanSpeed(srcLen: Long, speed: Float, curve: List<SpeedPt>?): Double =
        if (curve == null) clampSpeed(speed).toDouble() else srcLen.toDouble() / max(1L, outDur(srcLen, speed, curve))

    /** Whether a clip's sound is kept (pitch-preserved) or muted, and why. */
    fun audioKept(speed: Float, curve: List<SpeedPt>?): Boolean =
        curve == null && clampSpeed(speed) in AUDIO_MIN_SPEED..AUDIO_MAX_SPEED

    /** Piecewise-constant schedule for Media3's SpeedProvider, in microseconds of source time. */
    fun schedule(srcLen: Long, speed: Float, curve: List<SpeedPt>?): Pair<LongArray, FloatArray> {
        if (curve == null) return longArrayOf(0) to floatArrayOf(clampSpeed(speed))
        val n = steps(srcLen)
        val starts = ArrayList<Long>(); val speeds = ArrayList<Float>()
        for (i in 0 until n) {
            val sp = stepSpeed(curve, srcLen, i)
            if (speeds.isEmpty() || abs(speeds.last() - sp) > 1e-6f) { starts += i * CURVE_STEP_MS * 1000; speeds += sp }
        }
        if (starts.isEmpty()) { starts += 0; speeds += 1f }
        return starts.toLongArray() to speeds.toFloatArray()
    }

    /** "2.5x", "0.25x", "Curve: Hero" for labels. */
    fun label(speed: Float, curveName: String?, curve: List<SpeedPt>?): String =
        if (curve != null) "↯ " + (curveName ?: "Custom") else fmtSpeed(speed)

    fun fmtSpeed(s: Float): String = when {
        s >= 10f -> "${s.roundToLong()}x"
        else -> "%.2f".format(java.util.Locale.US, s).trimEnd('0').trimEnd('.') + "x"
    }

    /** Slider position 0..1 <-> speed 0.1..100 on a log scale (1x sits at 1/3). */
    fun sliderToSpeed(p: Float): Float = clampSpeed(10f.pow(-1f + 3f * p.coerceIn(0f, 1f)))
    fun speedToSlider(s: Float): Float = ((kotlin.math.log10(clampSpeed(s).toDouble()) + 1.0) / 3.0).toFloat()
}

/** Reverse limits (decoded frames are kept on disk one GOP at a time). */
object ReverseRules {
    const val MAX_SRC_MS = 60_000L
    fun allowed(srcLenMs: Long) = srcLenMs in MIN_CLIP_MS..MAX_SRC_MS
}

/** Freeze frame length choices. */
object FreezeRules {
    const val DEFAULT_MS = 3000L
    val choicesMs = listOf(1000L, 2000L, 3000L, 5000L)
}
