@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gopal.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt
import android.media.MediaExtractor
import android.media.MediaFormat

// ---------------- export settings (pure, unit-tested) ----------------
data class ExportSettings(val shortSide: Int = 1080, val fps: Int = 30, val bitrateMbps: Int = 0 /* 0 = auto */)

object ExportMath {
    /** Output size for an aspect ratio and a "p" value (short side), rounded to even numbers for the encoder. */
    fun outputSize(a: Aspect, shortSide: Int): Pair<Int, Int> {
        fun even(x: Float) = (x / 2f).roundToInt() * 2
        return if (a.w <= a.h) shortSide to even(shortSide * a.h.toFloat() / a.w) else even(shortSide * a.w.toFloat() / a.h) to shortSide
    }
    /** Auto bitrate ≈ 0.1 bit per pixel per frame (1080p30 portrait ≈ 6.2 Mbps), clamped 2–60 Mbps. */
    fun autoBitrate(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps / 10).toInt().coerceIn(2_000_000, 60_000_000)
    fun bitrate(s: ExportSettings, w: Int, h: Int): Int = if (s.bitrateMbps > 0) s.bitrateMbps * 1_000_000 else autoBitrate(w, h, s.fps)
    /** Rough output size for the UI. */
    fun estimateMb(bps: Int, durMs: Long): Float = (bps + 128_000).toFloat() * durMs / 1000f / 8f / 1_000_000f
}

/** What the phone's hardware encoders can do (4K is offered only if this says so). */
object EncoderCaps {
    data class Pick(val mime: String, val label: String)

    fun pick(w: Int, h: Int, fps: Int): Pick? {
        for ((mime, label) in listOf(MimeTypes.VIDEO_H264 to "H.264", MimeTypes.VIDEO_H265 to "HEVC")) {
            if (supports(mime, w, h, fps)) return Pick(mime, label)
        }
        return null
    }

    fun supports(mime: String, w: Int, h: Int, fps: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && run {
                val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: return@run false
                (vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, fps.toDouble())) ||
                    (vc.isSizeSupported(h, w) && vc.areSizeAndRateSupported(h, w, fps.toDouble()))
            }
        }
    }.getOrDefault(false)
}

// ---------------- probing ----------------
object MediaProbe {
    fun durationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
    }

    fun thumbnail(ctx: Context, uri: Uri, out: File, atMs: Long = 500): File? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val b = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = 320f / max(b.width, b.height)
            val small = Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            out
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }
}

// ---------------- silence (gaps on the audio track) ----------------
object Wav {
    const val RATE = 44100
    /** 16-bit mono PCM WAV of [ms] silence. */
    fun silence(ms: Long): ByteArray {
        val samples = (RATE.toLong() * ms / 1000).toInt().coerceAtLeast(1)
        val data = samples * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        return b.array()
    }
    fun silenceFile(ctx: Context, ms: Long): File {
        val f = File(ctx.cacheDir, "silence/s_$ms.wav")
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(silence(ms)) }
        return f
    }
}

/** Plan for the audio track sequence: clips in time order, gaps filled with silence, clipped to [totalMs]. */
data class AudioPiece(val clip: AudioClip?, val durMs: Long)

object AudioPlan {
    fun pieces(clips: List<AudioClip>, totalMs: Long): List<AudioPiece> {
        val out = ArrayList<AudioPiece>()
        var t = 0L
        for (c0 in clips.sortedBy { it.startMs }) {
            if (t >= totalMs) break
            var c = c0
            if (c.startMs < t) { // overlap with the previous clip: drop the overlapped head
                val cut = t - c.startMs
                if (cut >= c.durMs - MIN_CLIP_MS) continue
                c = c.copy(inMs = c.inMs + cut, startMs = t)
            }
            if (c.startMs > t) { out += AudioPiece(null, c.startMs - t); t = c.startMs }
            val len = minOf(c.durMs, totalMs - t)
            if (len < MIN_CLIP_MS) break
            out += AudioPiece(c.copy(outMs = c.inMs + len), len)
            t += len
        }
        if (totalMs - t >= MIN_CLIP_MS && out.isNotEmpty()) out += AudioPiece(null, totalMs - t)
        return out
    }
}

// ---------------- burn-in overlay ----------------
/**
 * The caption picture for one output frame size, shared by every clip's overlay (clips of a
 * sequence are processed one after another), so only two full-frame bitmaps exist per export.
 * Two bitmaps are swapped so Media3 re-uploads the texture only when the picture changes.
 */
class CaptionFrames(private val renderer: CaptionRenderer, private val caps: List<Caption>, private val tpl: CaptionTemplate, val w: Int, val h: Int) {
    private var bufs: Array<Bitmap>? = null
    private var cur = 0
    private var lastKey: String? = null
    private val animated = tpl.hl != Highlight.NONE

    @Synchronized fun at(t: Long): Bitmap {
        val b = bufs ?: arrayOf(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)).also { bufs = it }
        val c = caps.firstOrNull { t >= it.startMs && t < it.endMs && it.text.isNotBlank() }
        val key = when {
            c == null -> "none"
            animated -> "${c.id}:${CaptionBuilder.activeWord(c, t)}:${if (tpl.hl == Highlight.WORD) t / 33 else 0}"
            else -> c.id
        }
        if (key != lastKey) {
            cur = 1 - cur
            b[cur].eraseColor(Color.TRANSPARENT)
            renderer.draw(Canvas(b[cur]), w, h, c, tpl, t)
            lastKey = key
        }
        return b[cur]
    }
}

/**
 * Caption overlay for ONE video clip of the composition. The clip's first frame is taken as its
 * timeline start, so the mapping holds whatever timestamp offset Media3 gives later items.
 */
class TimelineCaptionOverlay(private val frames: CaptionFrames, private val clipTimelineStartMs: Long) : BitmapOverlay() {
    private var firstPtsUs = Long.MIN_VALUE
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = presentationTimeUs
        return frames.at(clipTimelineStartMs + (presentationTimeUs - firstPtsUs) / 1000)
    }
}

private fun volumeProcessors(v: Float): List<AudioProcessor> {
    if (v in 0.99f..1.01f) return emptyList()
    val p = ChannelMixingAudioProcessor()
    for (ch in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(v))
    return listOf(p)
}

private fun clipped(uri: String, inMs: Long, outMs: Long): MediaItem = MediaItem.Builder().setUri(Uri.parse(uri))
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(inMs).setEndPositionMs(outMs).build())
    .build()

private fun runTransformer(t: Transformer, comp: Composition, out: File, onProgress: (Int) -> Unit, cont: kotlinx.coroutines.CancellableContinuation<Unit>) {
    t.start(comp, out.absolutePath)
    val hd = Handler(Looper.getMainLooper())
    val ph = ProgressHolder()
    val poll = object : Runnable {
        override fun run() {
            if (!cont.isActive) return
            if (t.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
            hd.postDelayed(this, 250)
        }
    }
    hd.post(poll)
    cont.invokeOnCancellation { hd.post { t.cancel(); out.delete() } }
}

private fun listener(out: File, cont: kotlinx.coroutines.CancellableContinuation<Unit>) = object : Transformer.Listener {
    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
        out.delete(); if (cont.isActive) cont.resumeWithException(exportException)
    }
}

/** Exports the whole timeline: clips, music track, aspect ratio, captions burned in. Call on the main thread. */
suspend fun exportTimeline(
    ctx: Context, tl: Timeline, s: ExportSettings, out: File, onProgress: (Int) -> Unit
) = suspendCancellableCoroutine<Unit> { cont ->
    val (w, h) = ExportMath.outputSize(tl.aspect, s.shortSide)
    val enc = EncoderCaps.pick(w, h, s.fps)
    val renderer = CaptionRenderer(FontBank(ctx))
    val tpl = Templates.byId(tl.styleId)
    val layout = if (tl.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
    val frames = CaptionFrames(renderer, tl.captions, tpl, w, h)
    val videoItems = tl.video.mapIndexed { i, c ->
        val fx = buildList {
            if (s.fps < 60) add(FrameDropEffect.createDefaultFrameDropEffect(s.fps.toFloat()))   // only ever drops frames
            add(Presentation.createForWidthAndHeight(w, h, layout))
            if (tl.captions.isNotEmpty()) add(OverlayEffect(ImmutableList.of<TextureOverlay>(TimelineCaptionOverlay(frames, tl.videoStart(i)))))
        }
        EditedMediaItem.Builder(clipped(c.uri, c.inMs, c.outMs)).setEffects(Effects(volumeProcessors(c.volume), fx)).build()
    }
    val seqs = mutableListOf(EditedMediaItemSequence(videoItems))
    val plan = AudioPlan.pieces(tl.audio, tl.videoDurMs)
    if (plan.any { it.clip != null }) {
        seqs += EditedMediaItemSequence(plan.map { p ->
            val c = p.clip
            if (c == null) EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(Wav.silenceFile(ctx, p.durMs)))).build()
            else EditedMediaItem.Builder(clipped(c.uri, c.inMs, c.outMs)).setRemoveVideo(true).setEffects(Effects(volumeProcessors(c.volume), emptyList())).build()
        })
    }
    val comp = Composition.Builder(seqs)
        .experimentalSetForceAudioTrack(true)    // clips without sound still get a (silent) audio track
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
    val bps = ExportMath.bitrate(s, w, h)
    val t = Transformer.Builder(ctx)
        .setVideoMimeType(enc?.mime ?: MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bps).build())
                .setEnableFallback(true)
                .build()
        )
        .addListener(listener(out, cont)).build()
    runTransformer(t, comp, out, onProgress, cont)
}

/** The edit's sound (main track clips, in timeline time) as one .m4a for speech-to-text. */
suspend fun extractTimelineAudio(ctx: Context, tl: Timeline, out: File, onProgress: (Int) -> Unit = {}) = suspendCancellableCoroutine<Unit> { cont ->
    val items = tl.video.map { c -> EditedMediaItem.Builder(clipped(c.uri, c.inMs, c.outMs)).setRemoveVideo(true).build() }
    val comp = Composition.Builder(listOf(EditedMediaItemSequence(items))).experimentalSetForceAudioTrack(true).build()
    val t = Transformer.Builder(ctx).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(listener(out, cont)).build()
    runTransformer(t, comp, out, onProgress, cont)
}

/** ADTS framing so Gemini receives "audio/aac" (a format it documents) instead of MP4/M4A. No re-encode. */
object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    fun freqIndex(rate: Int): Int = RATES.indexOf(rate).let { if (it >= 0) it else 4 }
    fun parseAsc(asc: ByteArray): Triple<Int, Int, Int> {
        require(asc.size >= 2) { "bad AAC config" }
        val b0 = asc[0].toInt() and 0xFF; val b1 = asc[1].toInt() and 0xFF
        var aot = b0 shr 3
        val freq = ((b0 and 0x07) shl 1) or (b1 shr 7)
        val ch = (b1 shr 3) and 0x0F
        if (aot == 5 || aot == 29) aot = 2
        if (aot !in 1..4) aot = 2
        return Triple(aot, freq, ch)
    }
    fun header(payloadLen: Int, aot: Int, freqIdx: Int, chCfg: Int): ByteArray {
        val len = payloadLen + 7
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(),
            (((aot - 1) shl 6) or (freqIdx shl 2) or (chCfg shr 2)).toByte(),
            (((chCfg and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }
    fun m4aToAdts(input: File, out: File): File {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(input.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_AAC }
                ?: throw RuntimeException("no AAC track")
            val f = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val csd = f.getByteBuffer("csd-0")
            val (aot, fi, ch) = if (csd != null) { val b = ByteArray(csd.remaining()); csd.duplicate().get(b); parseAsc(b) }
                else Triple(2, freqIndex(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val buf = ByteBuffer.allocate(1 shl 20); val arr = ByteArray(1 shl 20)
            DataOutputStream(out.outputStream().buffered()).use { os ->
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.get(arr, 0, n)
                    os.write(header(n, aot, fi, ch)); os.write(arr, 0, n)
                    ex.advance()
                }
            }
        } finally { ex.release() }
        return out
    }
}

/** Copies the export into the gallery (Movies/GopalEditor). Returns false if it had to stay in app storage. */
suspend fun saveToGallery(ctx: Context, f: File, legacyWriteGranted: Boolean): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 29) {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GopalEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw RuntimeException("MediaStore error")
        try {
            (cr.openOutputStream(u) ?: throw RuntimeException("MediaStore error")).use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { cr.delete(u, null, null); throw e }
        return@withContext true
    }
    if (legacyWriteGranted) {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "GopalEditor")
        if (dir.exists() || dir.mkdirs()) {
            val d = File(dir, f.name)
            f.copyTo(d, true)
            MediaScannerConnection.scanFile(ctx, arrayOf(d.absolutePath), arrayOf("video/mp4"), null)
            return@withContext true
        }
    }
    f.copyTo(File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), f.name), true)
    false
}
