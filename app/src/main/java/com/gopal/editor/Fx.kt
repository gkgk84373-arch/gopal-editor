package com.gopal.editor

import kotlin.math.abs

/*
 * Filters + Adjust (pure Kotlin data; the GLSL in FxShader.kt reads these numbers).
 *
 * A filter is a small "recipe" for one parameterised colour shader: a 3x3 colour matrix with an
 * offset, contrast / saturation / gamma / fade (lifted blacks), warmth, split toning (a tint for
 * shadows and one for highlights), vignette, grain and glow. Intensity mixes the result with the
 * original pixel, so 0 = off and 1 = full filter. All 23 recipes are original to this app.
 */

data class FilterSpec(
    val id: String, val en: String, val hi: String,
    val mat: FloatArray = ID3,            // row-major 3x3: out = mat * rgb + off
    val off: FloatArray = Z3,
    val contrast: Float = 0f,             // -1..1
    val saturation: Float = 0f,           // -1..1 (-1 = grey)
    val gamma: Float = 1f,                // >1 darker mids, <1 brighter mids
    val fade: Float = 0f,                 // 0..1 lifts blacks
    val warmth: Float = 0f,               // -1..1
    val shadowTint: FloatArray = Z3,      // added to dark tones
    val highTint: FloatArray = Z3,        // added to bright tones
    val vignette: Float = 0f,             // 0..1
    val grain: Float = 0f,                // 0..1
    val glow: Float = 0f,                 // 0..1 brightens and blooms highlights
    val swatch: Long = 0xFF888888         // colour of the picker thumbnail
) {
    override fun equals(other: Any?) = other is FilterSpec && other.id == id
    override fun hashCode() = id.hashCode()
}

private val ID3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
private val Z3 = floatArrayOf(0f, 0f, 0f)
private fun v3(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
/** Grey matrix with a colour cast (for B&W / sepia families). */
private fun mono(r: Float = 1f, g: Float = 1f, b: Float = 1f) = floatArrayOf(
    0.299f * r, 0.587f * r, 0.114f * r, 0.299f * g, 0.587f * g, 0.114f * g, 0.299f * b, 0.587f * b, 0.114f * b)
/** Channel gains. */
private fun gains(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, g, 0f, 0f, 0f, b)

object Filters {
    val NONE = FilterSpec("none", "Original", "ओरिजिनल", swatch = 0xFF3A3A44)
    val all: List<FilterSpec> = listOf(
        NONE,
        FilterSpec("vintage", "Vintage", "विंटेज", mat = gains(1.06f, 0.98f, 0.82f), contrast = -0.12f, saturation = -0.25f, fade = 0.12f,
            shadowTint = v3(0.04f, 0.02f, -0.02f), highTint = v3(0.04f, 0.03f, -0.03f), vignette = 0.35f, grain = 0.15f, swatch = 0xFFC9A46B),
        FilterSpec("cinematic", "Cinematic", "सिनेमैटिक", contrast = 0.18f, saturation = -0.1f, gamma = 1.05f,
            shadowTint = v3(-0.03f, 0.02f, 0.06f), highTint = v3(0.05f, 0.02f, -0.04f), vignette = 0.4f, swatch = 0xFF2F5D6B),
        FilterSpec("bw", "B&W", "ब्लैक एंड व्हाइट", mat = mono(), contrast = 0.1f, swatch = 0xFF9A9A9A),
        FilterSpec("warm", "Warm", "वॉर्म", warmth = 0.45f, saturation = 0.08f, swatch = 0xFFE59A4F),
        FilterSpec("cool", "Cool", "कूल", warmth = -0.45f, saturation = 0.02f, swatch = 0xFF5B8FD9),
        FilterSpec("neon", "Neon", "नियॉन", contrast = 0.22f, saturation = 0.6f, shadowTint = v3(0.06f, -0.02f, 0.1f),
            highTint = v3(-0.02f, 0.06f, 0.08f), glow = 0.35f, swatch = 0xFFE040FB),
        FilterSpec("teal_orange", "Teal-Orange", "टील-ऑरेंज", contrast = 0.15f, saturation = 0.15f,
            shadowTint = v3(-0.06f, 0.03f, 0.08f), highTint = v3(0.08f, 0.02f, -0.06f), swatch = 0xFF2CA6A4),
        FilterSpec("fade", "Fade", "फ़ेड", contrast = -0.2f, saturation = -0.2f, fade = 0.22f, swatch = 0xFFB8B0A8),
        FilterSpec("matte", "Matte", "मैट", contrast = -0.1f, saturation = -0.12f, fade = 0.15f, gamma = 0.95f,
            highTint = v3(-0.02f, -0.01f, 0f), swatch = 0xFF7C8A86),
        FilterSpec("sepia", "Sepia", "सेपिया", mat = mono(1.07f, 0.88f, 0.66f), contrast = 0.04f, swatch = 0xFF9C7146),
        FilterSpec("noir", "Noir", "नॉयर", mat = mono(), contrast = 0.45f, gamma = 1.15f, vignette = 0.55f, grain = 0.2f, swatch = 0xFF222222),
        FilterSpec("vivid", "Vivid", "विविड", contrast = 0.15f, saturation = 0.45f, swatch = 0xFF2ECC71),
        FilterSpec("pastel", "Pastel", "पेस्टल", contrast = -0.18f, saturation = -0.25f, gamma = 0.85f, fade = 0.1f,
            highTint = v3(0.03f, 0.0f, 0.04f), swatch = 0xFFF5B5C8),
        FilterSpec("cyberpunk", "Cyberpunk", "साइबरपंक", mat = gains(1.05f, 0.85f, 1.15f), contrast = 0.25f, saturation = 0.3f,
            shadowTint = v3(0.02f, -0.02f, 0.12f), highTint = v3(0.1f, -0.02f, 0.06f), glow = 0.2f, swatch = 0xFF7B2FF7),
        FilterSpec("sunset", "Sunset", "सनसेट", warmth = 0.35f, saturation = 0.2f, shadowTint = v3(0.04f, -0.01f, 0.05f),
            highTint = v3(0.09f, 0.03f, -0.05f), swatch = 0xFFFF7043),
        FilterSpec("moody", "Moody", "मूडी", contrast = 0.2f, saturation = -0.3f, gamma = 1.12f, shadowTint = v3(0f, 0.02f, 0.04f),
            vignette = 0.45f, swatch = 0xFF37474F),
        FilterSpec("film", "Film", "फ़िल्म", contrast = 0.08f, saturation = -0.08f, fade = 0.08f,
            shadowTint = v3(0f, 0.025f, 0.02f), highTint = v3(0.03f, 0.02f, -0.01f), grain = 0.3f, swatch = 0xFF8D7B5C),
        FilterSpec("retro", "Retro", "रेट्रो", mat = gains(1.08f, 1.0f, 0.78f), contrast = 0.12f, saturation = -0.05f, fade = 0.1f,
            shadowTint = v3(0.0f, 0.03f, 0.05f), vignette = 0.3f, grain = 0.12f, swatch = 0xFFD4A15A),
        FilterSpec("cold_blue", "Cold Blue", "कोल्ड ब्लू", mat = gains(0.88f, 0.98f, 1.15f), warmth = -0.25f, saturation = -0.15f,
            contrast = 0.1f, swatch = 0xFF4FC3F7),
        FilterSpec("golden", "Golden", "गोल्डन", mat = gains(1.1f, 1.02f, 0.8f), warmth = 0.2f, saturation = 0.1f, glow = 0.15f,
            highTint = v3(0.05f, 0.03f, -0.04f), swatch = 0xFFFFC107),
        FilterSpec("dreamy", "Dreamy", "ड्रीमी", contrast = -0.12f, saturation = 0.05f, gamma = 0.9f, glow = 0.45f,
            highTint = v3(0.03f, 0.0f, 0.05f), swatch = 0xFFCE93D8),
        FilterSpec("chrome", "Chrome", "क्रोम", contrast = 0.2f, saturation = 0.15f, mat = gains(1.02f, 1.0f, 1.04f),
            shadowTint = v3(0f, 0.01f, 0.03f), swatch = 0xFFB0BEC5),
        FilterSpec("mint", "Mint", "मिंट", mat = gains(0.94f, 1.06f, 1.0f), saturation = -0.05f, fade = 0.06f,
            highTint = v3(-0.02f, 0.04f, 0.02f), swatch = 0xFF80CBC4)
    )
    fun byId(id: String?): FilterSpec = all.firstOrNull { it.id == id } ?: NONE
}

/** HSL bands (degrees of hue). Neighbour-to-neighbour tents, so the weights always add up to 1. */
object HslBands {
    val centers = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)
    val namesEn = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
    val namesHi = listOf("लाल", "नारंगी", "पीला", "हरा", "एक्वा", "नीला", "बैंगनी", "मैजेंटा")
    val swatches = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60)
    const val N = 8

    /** Signed hue distance a-b in -180..180. */
    fun diff(a: Float, b: Float): Float = ((a - b + 540f) % 360f) - 180f

    /** Weight of band [i] at hue [h] (same maths as the shader). */
    fun weight(i: Int, h: Float): Float {
        val c = centers[i]
        val prev = centers[(i + N - 1) % N]; val next = centers[(i + 1) % N]
        val lo = ((c - prev + 360f) % 360f).let { if (it == 0f) 360f else it }
        val hi = ((next - c + 360f) % 360f).let { if (it == 0f) 360f else it }
        val d = diff(h, c)
        return if (d < 0) maxOf(0f, 1f + d / lo) else maxOf(0f, 1f - d / hi)
    }
    /** (left width, right width) per band, packed for the shader. */
    fun widths(): Pair<FloatArray, FloatArray> {
        val lo = FloatArray(N); val hi = FloatArray(N)
        for (i in 0 until N) {
            val c = centers[i]
            lo[i] = (c - centers[(i + N - 1) % N] + 360f) % 360f
            hi[i] = (centers[(i + 1) % N] - c + 360f) % 360f
        }
        return lo to hi
    }
}

data class Adjust(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val hue: Float = 0f,          // -180..180 degrees, whole picture
    val temperature: Float = 0f,  // -1..1 (blue .. orange)
    val tint: Float = 0f,         // -1..1 (green .. magenta)
    val vignette: Float = 0f,     // 0..1
    val sharpen: Float = 0f,      // 0..1
    val blur: Float = 0f,         // 0..1
    val bandHue: List<Float> = ZERO8,   // -1..1 -> ±30° per band
    val bandSat: List<Float> = ZERO8,   // -1..1
    val bandLight: List<Float> = ZERO8  // -1..1
) {
    val isNeutral: Boolean get() = this == Adjust()
    companion object { val ZERO8: List<Float> = List(HslBands.N) { 0f } }
}

/** One clip's look. A LUT is a .cube file the user imported (stored inside the app). */
data class ClipFx(
    val filterId: String = "none",
    val filterAmt: Float = 1f,
    val lutPath: String? = null,
    val lutName: String? = null,
    val lutAmt: Float = 1f,
    val adjust: Adjust = Adjust()
) {
    val isNeutral: Boolean get() = (filterId == "none" || filterAmt <= 0f) && (lutPath == null || lutAmt <= 0f) && adjust.isNeutral
}

/** Adjust slider rows: key, labels, range. Shared by the UI and the tests. */
enum class AdjustKey(val en: String, val hi: String, val min: Float, val max: Float) {
    BRIGHTNESS("Brightness", "ब्राइटनेस", -1f, 1f),
    CONTRAST("Contrast", "कॉन्ट्रास्ट", -1f, 1f),
    SATURATION("Saturation", "सैचुरेशन", -1f, 1f),
    HUE("Hue", "ह्यू", -180f, 180f),
    TEMPERATURE("Temperature", "टेम्परेचर", -1f, 1f),
    TINT("Tint", "टिंट", -1f, 1f),
    VIGNETTE("Vignette", "विग्नेट", 0f, 1f),
    SHARPEN("Sharpen", "शार्पन", 0f, 1f),
    BLUR("Blur", "ब्लर", 0f, 1f);

    fun get(a: Adjust): Float = when (this) {
        BRIGHTNESS -> a.brightness; CONTRAST -> a.contrast; SATURATION -> a.saturation; HUE -> a.hue
        TEMPERATURE -> a.temperature; TINT -> a.tint; VIGNETTE -> a.vignette; SHARPEN -> a.sharpen; BLUR -> a.blur
    }
    fun set(a: Adjust, v0: Float): Adjust {
        val v = v0.coerceIn(min, max)
        return when (this) {
            BRIGHTNESS -> a.copy(brightness = v); CONTRAST -> a.copy(contrast = v); SATURATION -> a.copy(saturation = v)
            HUE -> a.copy(hue = v); TEMPERATURE -> a.copy(temperature = v); TINT -> a.copy(tint = v)
            VIGNETTE -> a.copy(vignette = v); SHARPEN -> a.copy(sharpen = v); BLUR -> a.copy(blur = v)
        }
    }
    /** Shown value: -100..100 (or degrees for hue). */
    fun display(a: Adjust): String = if (this == HUE) "${get(a).toInt()}°" else "${(get(a) * 100).toInt()}"
}

/**
 * Everything the shader needs for one frame, already packed as uniform arrays.
 * Pure Kotlin so the packing is unit-tested; FxShader.kt just uploads these.
 */
class FxParams(
    val mat: FloatArray, val off: FloatArray,
    val f0: FloatArray,      // contrast, saturation, gamma, fade
    val f1: FloatArray,      // warmth, grain, vignette, glow
    val shadowTint: FloatArray, val highTint: FloatArray,
    val filterAmt: Float,
    val a0: FloatArray,      // brightness, contrast, saturation, hue(deg)
    val a1: FloatArray,      // temperature, tint, vignette, sharpen
    val blur: Float,
    val bandHueA: FloatArray, val bandHueB: FloatArray,
    val bandSatA: FloatArray, val bandSatB: FloatArray,
    val bandLitA: FloatArray, val bandLitB: FloatArray,
    val lut: LutData?, val lutAmt: Float,
    val neutral: Boolean
) {
    val usesBands: Boolean get() = listOf(bandHueA, bandHueB, bandSatA, bandSatB, bandLitA, bandLitB).any { a -> a.any { abs(it) > 1e-4f } }

    companion object {
        val NEUTRAL: FxParams = of(ClipFx(), null)

        fun of(fx: ClipFx, lut: LutData?): FxParams {
            val f = Filters.byId(fx.filterId)
            val a = fx.adjust
            fun four(l: List<Float>, from: Int) = FloatArray(4) { (l.getOrElse(from + it) { 0f }).coerceIn(-1f, 1f) }
            val amt = if (f.id == "none") 0f else fx.filterAmt.coerceIn(0f, 1f)
            val lutOn = lut != null && fx.lutPath != null
            return FxParams(
                mat = f.mat.copyOf(), off = f.off.copyOf(),
                f0 = floatArrayOf(f.contrast, f.saturation, f.gamma, f.fade),
                f1 = floatArrayOf(f.warmth, f.grain, f.vignette, f.glow),
                shadowTint = f.shadowTint.copyOf(), highTint = f.highTint.copyOf(),
                filterAmt = amt,
                a0 = floatArrayOf(a.brightness, a.contrast, a.saturation, a.hue),
                a1 = floatArrayOf(a.temperature, a.tint, a.vignette, a.sharpen),
                blur = a.blur,
                bandHueA = four(a.bandHue, 0), bandHueB = four(a.bandHue, 4),
                bandSatA = four(a.bandSat, 0), bandSatB = four(a.bandSat, 4),
                bandLitA = four(a.bandLight, 0), bandLitB = four(a.bandLight, 4),
                lut = if (lutOn) lut else null, lutAmt = if (lutOn) fx.lutAmt.coerceIn(0f, 1f) else 0f,
                neutral = amt == 0f && !lutOn && a.isNeutral
            )
        }
    }
}

/**
 * CPU reference of the filter part of the shader for ONE pixel (r,g,b in 0..1), used by tests
 * to pin the maths (intensity 0 = original, matrices, contrast/saturation, clamping).
 */
object FxReference {
    fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    fun filter(p: FxParams, rgb: FloatArray): FloatArray {
        val m = p.mat
        var r = m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2] + p.off[0]
        var g = m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2] + p.off[1]
        var b = m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2] + p.off[2]
        // warmth
        r += p.f1[0] * 0.08f; b -= p.f1[0] * 0.08f
        // contrast
        val k = 1f + p.f0[0]
        r = (r - 0.5f) * k + 0.5f; g = (g - 0.5f) * k + 0.5f; b = (b - 0.5f) * k + 0.5f
        // saturation
        val l = luma(r, g, b); val s = 1f + p.f0[1]
        r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
        // split toning
        val l2 = luma(r, g, b).coerceIn(0f, 1f)
        val sh = 1f - smooth(0f, 0.55f, l2); val hl = smooth(0.45f, 1f, l2)
        r += p.shadowTint[0] * sh + p.highTint[0] * hl
        g += p.shadowTint[1] * sh + p.highTint[1] * hl
        b += p.shadowTint[2] * sh + p.highTint[2] * hl
        // gamma + fade
        r = Math.pow(r.coerceIn(0f, 1f).toDouble(), p.f0[2].toDouble()).toFloat()
        g = Math.pow(g.coerceIn(0f, 1f).toDouble(), p.f0[2].toDouble()).toFloat()
        b = Math.pow(b.coerceIn(0f, 1f).toDouble(), p.f0[2].toDouble()).toFloat()
        val fd = p.f0[3]
        r = r * (1f - fd) + fd * 0.5f * 0.6f + fd * 0.2f * r
        g = g * (1f - fd) + fd * 0.5f * 0.6f + fd * 0.2f * g
        b = b * (1f - fd) + fd * 0.5f * 0.6f + fd * 0.2f * b
        val t = p.filterAmt
        return floatArrayOf(
            (rgb[0] + (r.coerceIn(0f, 1f) - rgb[0]) * t),
            (rgb[1] + (g.coerceIn(0f, 1f) - rgb[1]) * t),
            (rgb[2] + (b.coerceIn(0f, 1f) - rgb[2]) * t))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
}
