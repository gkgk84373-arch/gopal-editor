@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gopal.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reverse: our own MediaCodec pass, no ffmpeg.
 *
 *  1. Scan the video samples of the clip range and find the key frames (GOP starts).
 *  2. Walk the GOPs from the LAST to the first. For each GOP: seek to its key frame, decode it,
 *     keep the frames that fall in the range as raw I420 in a temp file (one GOP at a time on disk).
 *  3. Feed that GOP's frames to an H.264 encoder newest-first, with mirrored timestamps.
 *
 * Output: a silent MP4 with the same rotation. Limit: [ReverseRules.MAX_SRC_MS] of source.
 */
object VideoReverser {
    class ReverseException(msg: String) : Exception(msg)

    private data class Sample(val ptsUs: Long, val sync: Boolean)

    suspend fun reverse(ctx: Context, uri: Uri, inMs: Long, outMs: Long, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!ReverseRules.allowed(outMs - inMs)) throw ReverseException("Reverse works on clips up to ${ReverseRules.MAX_SRC_MS / 1000} s")
        val inUs = inMs * 1000; val outUs = outMs * 1000
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val tmp = File(ctx.cacheDir, "reverse_gop.yuv")
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw ReverseException("No video track")
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() } else 30

            // ---- 1. scan ----
            val samples = ArrayList<Sample>()
            ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                samples += Sample(t, (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                if (t > outUs + 3_000_000) break
                if (!ex.advance()) break
            }
            val syncs = samples.filter { it.sync && it.ptsUs < outUs }.map { it.ptsUs }.distinct().sorted()
            val wanted = samples.map { it.ptsUs }.filter { it in inUs until outUs }.distinct().sorted()
            if (syncs.isEmpty() || wanted.isEmpty()) throw ReverseException("Could not read frames from this clip")
            val lastPts = wanted.last()

            // ---- codecs ----
            decoder = MediaCodec.createDecoderByType(mime).apply {
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                configure(fmt, null, null, 0); start()
            }
            val info = MediaCodec.BufferInfo()
            var frameW = 0; var frameH = 0; var frameBytes = 0
            var done = 0
            var outTrack = -1
            var muxing = false

            fun drainEncoder(endOfStream: Boolean) {
                val enc = encoder ?: return
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outTrack = muxer!!.addTrack(enc.outputFormat); muxer!!.start(); muxing = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer!!.writeSampleData(outTrack, buf, info) }
                            enc.releaseOutputBuffer(idx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            fun startEncoder(w: Int, h: Int) {
                val f = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 6).toInt().coerceIn(2_000_000, 40_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 120))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                encoder = MediaCodec.createEncoderByType(MimeTypes.VIDEO_H264).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
            }

            val frameBuf = ByteArray(0).let { arrayOf(it) }
            // ---- 2+3. GOPs, last first ----
            for (k in syncs.indices.reversed()) {
                coroutineContext.ensureActive()
                val gopStart = syncs[k]
                val gopEnd = if (k + 1 < syncs.size) syncs[k + 1] else Long.MAX_VALUE
                val need = wanted.filter { it >= gopStart && it < gopEnd }
                if (need.isEmpty()) continue
                val slot = HashMap<Long, Int>().apply { need.forEachIndexed { i, t -> put(t, i) } }
                val filled = BooleanArray(need.size)
                RandomAccessFile(tmp, "rw").use { raf ->
                    decoder.flush()
                    ex.seekTo(gopStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var inputDone = false
                    var got = 0
                    var idle = 0
                    while (got < need.size && idle < 400) {
                        if (!inputDone) {
                            val ii = decoder.dequeueInputBuffer(5_000)
                            if (ii >= 0) {
                                val n = decoder.getInputBuffer(ii)!!.let { ex.readSampleData(it, 0) }
                                val t = ex.sampleTime
                                if (n < 0 || (gopEnd != Long.MAX_VALUE && k + 2 < syncs.size && t >= syncs[k + 2]) || t > outUs + 3_000_000) {
                                    decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                                } else { decoder.queueInputBuffer(ii, 0, n, t, 0); ex.advance() }
                            }
                        }
                        val oi = decoder.dequeueOutputBuffer(info, 5_000)
                        if (oi >= 0) {
                            idle = 0
                            val s = slot[info.presentationTimeUs]
                            if (s != null && !filled[s] && info.size >= 0) {
                                val img = decoder.getOutputImage(oi) ?: throw ReverseException("Decoder gave no image")
                                if (frameBytes == 0) {
                                    val cr = img.cropRect
                                    frameW = cr.width() and 1.inv(); frameH = cr.height() and 1.inv()
                                    frameBytes = frameW * frameH * 3 / 2
                                    frameBuf[0] = ByteArray(frameBytes)
                                    startEncoder(frameW, frameH)
                                }
                                YuvCopy.imageToI420(img, frameW, frameH, frameBuf[0])
                                img.close()
                                raf.seek(s.toLong() * frameBytes); raf.write(frameBuf[0])
                                filled[s] = true; got++
                            }
                            decoder.releaseOutputBuffer(oi, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        } else if (inputDone) idle++
                    }
                    if (frameBytes == 0) throw ReverseException("Could not decode this clip")
                    // encode this GOP newest-first; a frame the decoder skipped repeats its neighbour
                    var lastGood = -1
                    for (i in need.indices.reversed()) {
                        coroutineContext.ensureActive()
                        val src = if (filled[i]) i else (lastGood.takeIf { it >= 0 } ?: filled.indexOfFirst { it }.takeIf { it >= 0 } ?: continue)
                        if (filled[i]) lastGood = i
                        raf.seek(src.toLong() * frameBytes); raf.readFully(frameBuf[0])
                        val enc = encoder!!
                        var ii: Int
                        do { ii = enc.dequeueInputBuffer(10_000); if (ii < 0) drainEncoder(false) } while (ii < 0)
                        val img = enc.getInputImage(ii) ?: throw ReverseException("Encoder gave no input image")
                        YuvCopy.i420ToImage(frameBuf[0], frameW, frameH, img)
                        enc.queueInputBuffer(ii, 0, frameBytes, lastPts - need[i], 0)
                        drainEncoder(false)
                        done++
                        onProgress(done.toFloat() / wanted.size)
                    }
                }
            }
            val enc = encoder ?: throw ReverseException("Nothing was decoded")
            var ii: Int
            do { ii = enc.dequeueInputBuffer(10_000); if (ii < 0) drainEncoder(false) } while (ii < 0)
            enc.queueInputBuffer(ii, 0, 0, lastPts - wanted.first() + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(true)
            if (!muxing) throw ReverseException("Encoder produced no output")
            muxer!!.stop()
            out
        } catch (e: Exception) {
            out.delete(); throw e
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { ex.release() }
            tmp.delete()
        }
    }
}

/** Plane copies between android.media.Image (any strides) and packed I420. */
object YuvCopy {
    fun imageToI420(img: Image, w: Int, h: Int, dst: ByteArray) {
        require(img.format == ImageFormat.YUV_420_888 || img.planes.size == 3) { "unexpected image format ${img.format}" }
        val cr: Rect = img.cropRect
        var o = 0
        for (p in 0 until 3) {
            val plane = img.planes[p]
            val buf = plane.buffer
            val sub = if (p == 0) 1 else 2
            val pw = w / sub; val ph = h / sub
            val rs = plane.rowStride; val ps = plane.pixelStride
            val x0 = cr.left / sub; val y0 = cr.top / sub
            val row = ByteArray(rs.coerceAtLeast(pw * ps))
            for (y in 0 until ph) {
                val start = (y0 + y) * rs + x0 * ps
                buf.position(start)
                val len = minOf((pw - 1) * ps + 1, buf.remaining())
                buf.get(row, 0, len)
                if (ps == 1) { System.arraycopy(row, 0, dst, o, pw); o += pw }
                else for (x in 0 until pw) dst[o++] = row[x * ps]
            }
        }
    }

    fun i420ToImage(src: ByteArray, w: Int, h: Int, img: Image) {
        var o = 0
        for (p in 0 until 3) {
            val plane = img.planes[p]
            val buf = plane.buffer
            val sub = if (p == 0) 1 else 2
            val pw = w / sub; val ph = h / sub
            val rs = plane.rowStride; val ps = plane.pixelStride
            for (y in 0 until ph) {
                if (ps == 1) { buf.position(y * rs); buf.put(src, o, pw); o += pw }
                else for (x in 0 until pw) { buf.put(y * rs + x * ps, src[o++]) }
            }
        }
    }
}

/** Freeze frame: grab the frame at a source time and turn it into a short silent MP4 clip. */
object FreezeMaker {
    suspend fun make(ctx: Context, uri: Uri, srcMs: Long, lenMs: Long, out: File, onProgress: (Int) -> Unit = {}): File {
        val png = File(out.parentFile, out.nameWithoutExtension + ".png")
        val (w, h) = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val b: Bitmap = r.getFrameAtTime(srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IllegalStateException("Could not read the frame at the playhead")
                out.parentFile?.mkdirs()
                FileOutputStream(png).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                (b.width and 1.inv()) to (b.height and 1.inv())
            } finally { runCatching { r.release() } }
        }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(png)).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(lenMs).build()
                ).setDurationUs(lenMs * 1000).setFrameRate(30)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(max(2, w), max(2, h), Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                val comp = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item)))).build()
                val t = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(transformerListener(out, cont)).build()
                startWithProgress(t, comp, out, onProgress, cont)
            }
        }
        png.delete()
        return out
    }
}
