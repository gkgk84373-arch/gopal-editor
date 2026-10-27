@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gopal.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gives the shader the look for a frame. Preview: the clip under the playhead. Export: one clip's fixed look. */
fun interface FxSource { fun at(presentationTimeUs: Long): FxParams }

/**
 * One Media3 GlEffect that draws filters + LUT + Adjust in a single pass with our own GLSL.
 * The same class goes to ExoPlayer.setVideoEffects (preview) and to Transformer (export).
 */
class GopalFxEffect(private val source: FxSource) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = GopalFxProgram(source, useHdr)
}

internal class GopalFxProgram(private val source: FxSource, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var w = 1
    private var h = 1
    private var lutTex = 0
    private var lutUploaded: LutData? = null
    private var dummyTex = 0
    private val bandWidths = HslBands.widths()

    init {
        try {
            program = GlProgram(FxGlsl.VERTEX, FxGlsl.FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun texture(width: Int, height: Int, pixels: ByteBuffer): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        return ids[0]
    }

    private fun deleteTex(id: Int) { if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0) }

    private fun lutTexture(lut: LutData?): Int {
        if (lut == null) {
            if (dummyTex == 0) dummyTex = texture(1, 1, ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(0, 0, 0, -1)).also { it.flip() })
            return dummyTex
        }
        if (lut !== lutUploaded) {
            deleteTex(lutTex)
            lutTex = texture(lut.size * lut.size, lut.size, lut.toAtlasRgba())
            lutUploaded = lut
        }
        return lutTex
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = source.at(presentationTimeUs)
        try {
            val lt = lutTexture(p.lut)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uLut", lt, 1)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / w, 1f / h))
            program.setFloatUniform("uAspect", w.toFloat() / h.coerceAtLeast(1))
            program.setFloatUniform("uTime", (presentationTimeUs % 100_000_000L) / 1_000_000f)
            program.setFloatsUniform("uMat0", floatArrayOf(p.mat[0], p.mat[1], p.mat[2]))
            program.setFloatsUniform("uMat1", floatArrayOf(p.mat[3], p.mat[4], p.mat[5]))
            program.setFloatsUniform("uMat2", floatArrayOf(p.mat[6], p.mat[7], p.mat[8]))
            program.setFloatsUniform("uOff", p.off)
            program.setFloatsUniform("uF0", p.f0)
            program.setFloatsUniform("uF1", p.f1)
            program.setFloatsUniform("uShadowTint", p.shadowTint)
            program.setFloatsUniform("uHighTint", p.highTint)
            program.setFloatUniform("uFilterAmt", p.filterAmt)
            program.setFloatsUniform("uA0", p.a0)
            program.setFloatsUniform("uA1", p.a1)
            program.setFloatUniform("uBlur", p.blur)
            program.setFloatsUniform("uBandHueA", p.bandHueA); program.setFloatsUniform("uBandHueB", p.bandHueB)
            program.setFloatsUniform("uBandSatA", p.bandSatA); program.setFloatsUniform("uBandSatB", p.bandSatB)
            program.setFloatsUniform("uBandLitA", p.bandLitA); program.setFloatsUniform("uBandLitB", p.bandLitB)
            program.setFloatsUniform("uBandLoA", bandWidths.first.copyOfRange(0, 4)); program.setFloatsUniform("uBandLoB", bandWidths.first.copyOfRange(4, 8))
            program.setFloatsUniform("uBandHiA", bandWidths.second.copyOfRange(0, 4)); program.setFloatsUniform("uBandHiB", bandWidths.second.copyOfRange(4, 8))
            program.setFloatUniform("uUseBands", if (p.usesBands) 1f else 0f)
            program.setFloatUniform("uLutSize", (p.lut?.size ?: 2).toFloat())
            program.setFloatUniform("uLutAmt", p.lutAmt)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            deleteTex(lutTex); deleteTex(dummyTex)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/** The GLSL (OpenGL ES 2.0, so it runs on every phone Media3 supports). */
object FxGlsl {
    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

    const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uTime;
uniform vec3 uMat0; uniform vec3 uMat1; uniform vec3 uMat2; uniform vec3 uOff;
uniform vec4 uF0;   // contrast, saturation, gamma, fade
uniform vec4 uF1;   // warmth, grain, vignette, glow
uniform vec3 uShadowTint; uniform vec3 uHighTint;
uniform float uFilterAmt;
uniform vec4 uA0;   // brightness, contrast, saturation, hue(deg)
uniform vec4 uA1;   // temperature, tint, vignette, sharpen
uniform float uBlur;
uniform vec4 uBandHueA; uniform vec4 uBandHueB;
uniform vec4 uBandSatA; uniform vec4 uBandSatB;
uniform vec4 uBandLitA; uniform vec4 uBandLitB;
uniform vec4 uBandLoA; uniform vec4 uBandLoB;
uniform vec4 uBandHiA; uniform vec4 uBandHiB;
uniform float uUseBands;
uniform float uLutSize;
uniform float uLutAmt;
varying vec2 vUv;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uTime * 7.13) * 43758.5453); }

vec3 rgb2hsl(vec3 c) {
  float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
  float l = (mx + mn) * 0.5; float d = mx - mn;
  if (d < 1e-5) return vec3(0.0, 0.0, l);
  float s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
  float h;
  if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
  else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
  else h = (c.r - c.g) / d + 4.0;
  return vec3(h * 60.0, s, l);
}
float hue2c(float p, float q, float t) {
  if (t < 0.0) t += 1.0; if (t > 1.0) t -= 1.0;
  if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
  if (t < 0.5) return q;
  if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
  return p;
}
vec3 hsl2rgb(vec3 hsl) {
  float h = hsl.x / 360.0; float s = hsl.y; float l = hsl.z;
  if (s < 1e-5) return vec3(l);
  float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s; float p = 2.0 * l - q;
  return vec3(hue2c(p, q, h + 1.0 / 3.0), hue2c(p, q, h), hue2c(p, q, h - 1.0 / 3.0));
}
float hdiff(float a, float b) { return mod(a - b + 540.0, 360.0) - 180.0; }
float tent(float h, float c, float lo, float hi) {
  float d = hdiff(h, c);
  return d < 0.0 ? max(0.0, 1.0 + d / lo) : max(0.0, 1.0 - d / hi);
}

vec3 lutLookup(vec3 c) {
  float n = uLutSize;
  float bz = clamp(c.b, 0.0, 1.0) * (n - 1.0);
  float s0 = floor(bz); float s1 = min(s0 + 1.0, n - 1.0); float f = bz - s0;
  float x = clamp(c.r, 0.0, 1.0) * (n - 1.0) + 0.5;
  float y = (clamp(c.g, 0.0, 1.0) * (n - 1.0) + 0.5) / n;
  vec3 a = texture2D(uLut, vec2((x + s0 * n) / (n * n), y)).rgb;
  vec3 b = texture2D(uLut, vec2((x + s1 * n) / (n * n), y)).rgb;
  return mix(a, b, f);
}

void main() {
  vec4 src = texture2D(uTexSampler, vUv);
  vec3 c = src.rgb;

  // ---- blur (adjust) : 13-tap disc, radius up to ~10 px ----
  if (uBlur > 0.001) {
    vec2 r = uTexel * (1.0 + uBlur * 9.0);
    vec3 acc = c * 2.0;
    acc += texture2D(uTexSampler, vUv + vec2( 1.0, 0.0) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2(-1.0, 0.0) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2( 0.0, 1.0) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2( 0.0,-1.0) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2( 0.7, 0.7) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2(-0.7, 0.7) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2( 0.7,-0.7) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2(-0.7,-0.7) * r).rgb;
    acc += texture2D(uTexSampler, vUv + vec2( 2.0, 0.0) * r).rgb * 0.5;
    acc += texture2D(uTexSampler, vUv + vec2(-2.0, 0.0) * r).rgb * 0.5;
    acc += texture2D(uTexSampler, vUv + vec2( 0.0, 2.0) * r).rgb * 0.5;
    acc += texture2D(uTexSampler, vUv + vec2( 0.0,-2.0) * r).rgb * 0.5;
    c = acc / 12.0;
  }
  // ---- sharpen (adjust) : unsharp mask ----
  if (uA1.w > 0.001) {
    vec3 n4 = texture2D(uTexSampler, vUv + vec2(uTexel.x, 0.0)).rgb + texture2D(uTexSampler, vUv - vec2(uTexel.x, 0.0)).rgb
            + texture2D(uTexSampler, vUv + vec2(0.0, uTexel.y)).rgb + texture2D(uTexSampler, vUv - vec2(0.0, uTexel.y)).rgb;
    c = c + (c - n4 * 0.25) * uA1.w * 2.0;
  }
  c = clamp(c, 0.0, 1.0);

  // ---- filter ----
  if (uFilterAmt > 0.001) {
    vec3 f = vec3(dot(uMat0, c), dot(uMat1, c), dot(uMat2, c)) + uOff;
    f.r += uF1.x * 0.08; f.b -= uF1.x * 0.08;
    f = (f - 0.5) * (1.0 + uF0.x) + 0.5;
    float l = dot(f, LUMA);
    f = l + (f - l) * (1.0 + uF0.y);
    float l2 = clamp(dot(f, LUMA), 0.0, 1.0);
    f += uShadowTint * (1.0 - smoothstep(0.0, 0.55, l2)) + uHighTint * smoothstep(0.45, 1.0, l2);
    f = pow(clamp(f, 0.0, 1.0), vec3(uF0.z));
    f = f * (1.0 - uF0.w) + uF0.w * 0.3 + uF0.w * 0.2 * f;
    if (uF1.w > 0.001) {
      float hl = smoothstep(0.55, 1.0, dot(f, LUMA));
      f += f * hl * uF1.w * 0.6 + vec3(uF1.w * 0.04);
    }
    if (uF1.z > 0.001) {
      vec2 q = (vUv - 0.5) * vec2(uAspect, 1.0) / max(uAspect, 1.0);
      f *= 1.0 - uF1.z * smoothstep(0.25, 0.85, length(q) * 1.4);
    }
    if (uF1.y > 0.001) f += (hash(vUv * 1000.0) - 0.5) * uF1.y * 0.18;
    c = mix(c, clamp(f, 0.0, 1.0), uFilterAmt);
  }

  // ---- imported .cube LUT ----
  if (uLutAmt > 0.001) c = mix(c, lutLookup(c), uLutAmt);

  // ---- adjust ----
  c += uA0.x * 0.35;                                         // brightness
  c = (c - 0.5) * (1.0 + uA0.y * (uA0.y > 0.0 ? 1.0 : 0.8)) + 0.5;   // contrast
  float la = dot(c, LUMA);
  c = la + (c - la) * (1.0 + uA0.z);                          // saturation
  c.r += uA1.x * 0.12; c.b -= uA1.x * 0.12;                  // temperature
  c.g -= uA1.y * 0.10; c.r += uA1.y * 0.04; c.b += uA1.y * 0.04;  // tint (+ = magenta)
  c = clamp(c, 0.0, 1.0);
  if (abs(uA0.w) > 0.01 || uUseBands > 0.5) {
    vec3 hsl = rgb2hsl(c);
    float h = hsl.x;
    vec4 wA = vec4(tent(h, 0.0, uBandLoA.x, uBandHiA.x), tent(h, 30.0, uBandLoA.y, uBandHiA.y),
                   tent(h, 60.0, uBandLoA.z, uBandHiA.z), tent(h, 120.0, uBandLoA.w, uBandHiA.w));
    vec4 wB = vec4(tent(h, 180.0, uBandLoB.x, uBandHiB.x), tent(h, 240.0, uBandLoB.y, uBandHiB.y),
                   tent(h, 270.0, uBandLoB.z, uBandHiB.z), tent(h, 300.0, uBandLoB.w, uBandHiB.w));
    float dh = dot(wA, uBandHueA) + dot(wB, uBandHueB);
    float ds = dot(wA, uBandSatA) + dot(wB, uBandSatB);
    float dl = dot(wA, uBandLitA) + dot(wB, uBandLitB);
    float chroma = clamp(hsl.y * 2.0, 0.0, 1.0);             // greys are left alone by band edits
    hsl.x = mod(h + uA0.w + dh * 30.0 * chroma + 360.0, 360.0);
    hsl.y = clamp(hsl.y * (1.0 + ds * chroma), 0.0, 1.0);
    hsl.z = clamp(hsl.z + dl * 0.25 * chroma * (1.0 - abs(hsl.z * 2.0 - 1.0)), 0.0, 1.0);
    c = hsl2rgb(hsl);
  }
  if (uA1.z > 0.001) {                                        // vignette
    vec2 q = (vUv - 0.5) * vec2(uAspect, 1.0) / max(uAspect, 1.0);
    c *= 1.0 - uA1.z * smoothstep(0.2, 0.9, length(q) * 1.4);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
}
