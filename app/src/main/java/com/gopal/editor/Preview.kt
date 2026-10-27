@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gopal.editor

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Drives the preview: the main ExoPlayer plays the video track as a playlist of clipped items,
 * a second ExoPlayer follows the audio track. Timeline time = start of current clip + position in it.
 */
class PreviewController(ctx: Context, private val ed: EditorState) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private val music: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private var musicClipId: String? = null
    private var built: List<Triple<String, Long, Long>> = emptyList()

    /** Look of the clip under the playhead; the shader reads it on every frame. */
    @Volatile private var currentFx: FxParams = FxParams.NEUTRAL
    private var fxKey: Any? = null
    private var speedKey = 1f

    init {
        // Stage 2A: one GL effect for the whole player (filters + LUT + adjust); it reads currentFx per frame.
        runCatching { player.setVideoEffects(listOf<androidx.media3.common.Effect>(GopalFxEffect { currentFx })) }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ed.playing = isPlaying
                if (!isPlaying) music.pause()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.playWhenReady = false; music.pause() }
            }
        })
    }

    private fun item(uri: String, inMs: Long, outMs: Long) = MediaItem.Builder().setUri(Uri.parse(uri))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(inMs).setEndPositionMs(outMs).build()).build()

    /** Rebuild the playlist after the clip list changed, keeping the playhead. */
    fun rebuild() {
        val v = ed.tl.video
        val ids = v.map { Triple(it.uri, it.inMs, it.outMs) }   // a look/speed change must not reload the playlist
        if (ids != built) {
            built = ids
            player.setMediaItems(v.map { item(it.uri, it.inMs, it.outMs) })
            player.prepare()
        }
        musicClipId = null
        seekTo(ed.playhead)
    }

    fun seekTo(t: Long) {
        TimelineOps.locate(ed.tl, t)?.let { loc ->
            val c = ed.tl.video[loc.index]
            if (loc.index < player.mediaItemCount) player.seekTo(loc.index, c.outToSrc(loc.offsetMs.coerceAtMost(c.durMs - 1)).coerceAtMost(c.srcLenMs - 1))
            applyClip(loc.index, c.outToSrc(loc.offsetMs))
        }
        syncMusic(t, force = true)
    }

    fun playPause() {
        if (player.isPlaying) { player.pause(); return }
        if (ed.playhead >= ed.tl.durationMs - 50) { ed.playhead = 0; seekTo(0) }
        player.play()
    }

    /** Called ~30x per second from the UI. */
    fun tick() {
        if (!player.isPlaying) return
        val i = player.currentMediaItemIndex
        if (i !in ed.tl.video.indices) return
        val c = ed.tl.video[i]
        val t = ed.tl.videoStart(i) + c.srcToOut(player.currentPosition)
        ed.playhead = t.coerceIn(0, ed.tl.durationMs)
        applyClip(i, player.currentPosition)
        syncMusic(t, force = false)
    }

    private fun syncMusic(t: Long, force: Boolean) {
        val c = ed.tl.audio.lastOrNull { t >= it.startMs && t < it.endMs }
        if (c == null) { if (music.isPlaying) music.pause(); musicClipId = null; return }
        val want = t - c.startMs
        if (c.id != musicClipId) {
            musicClipId = c.id
            music.setMediaItem(item(c.uri, c.inMs, c.outMs))
            music.prepare()
            music.seekTo(want)
        } else if (force || abs(music.currentPosition - want) > 250) music.seekTo(want)
        music.volume = c.volume.coerceAtMost(1f)
        if (player.isPlaying && !music.isPlaying) music.play()
    }

    /**
     * Speed, sound and look of clip [i] at source offset [srcPos].
     * Speed: ExoPlayer's playback speed (Sonic, pitch kept), capped at PREVIEW_MAX_SPEED; curves are
     * followed ~30x a second. Export uses the exact speed. Sound is muted where the export mutes it.
     */
    private fun applyClip(i: Int, srcPos: Long) {
        val c = ed.tl.video.getOrNull(i) ?: return
        val sp = when {
            c.curve != null -> SpeedMath.stepSpeed(c.curve, c.srcLenMs, (srcPos.coerceIn(0, c.srcLenMs) / CURVE_STEP_MS).toInt())
            else -> SpeedMath.clampSpeed(c.speed)
        }.coerceIn(MIN_SPEED, PREVIEW_MAX_SPEED)
        if (sp != speedKey) { speedKey = sp; player.playbackParameters = PlaybackParameters(sp, 1f) }
        player.volume = if (c.still || c.reversed || (c.hasSpeed && !c.audioKept)) 0f else c.volume.coerceAtMost(1f)
        if (c.fx != fxKey) {
            fxKey = c.fx
            currentFx = FxParams.of(c.fx, LutCache.get(c.fx.lutPath))
        }
    }

    /** After a look change while paused: redraw the current frame with the new look. */
    fun refreshLook() {
        val loc = TimelineOps.locate(ed.tl, ed.playhead) ?: return
        val c = ed.tl.video[loc.index]
        if (c.fx == fxKey) return
        applyClip(loc.index, c.outToSrc(loc.offsetMs))
        if (!player.isPlaying && loc.index < player.mediaItemCount) player.seekTo(loc.index, player.currentPosition)
    }

    fun release() { player.release(); music.release() }
}

@Composable
fun PlayerSurface(player: Player, fill: Boolean) {
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        update = { it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
        modifier = Modifier.fillMaxSize()
    )
}
