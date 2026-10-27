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
    private var built: List<VideoClip> = emptyList()

    init {
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
        if (v != built) {
            built = v
            player.setMediaItems(v.map { item(it.uri, it.inMs, it.outMs) })
            player.prepare()
        }
        musicClipId = null
        seekTo(ed.playhead)
    }

    fun seekTo(t: Long) {
        TimelineOps.locate(ed.tl, t)?.let { loc ->
            if (loc.index < player.mediaItemCount) player.seekTo(loc.index, loc.offsetMs.coerceAtMost(ed.tl.video[loc.index].durMs - 1))
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
        val t = ed.tl.videoStart(i) + player.currentPosition
        ed.playhead = t.coerceIn(0, ed.tl.durationMs)
        player.volume = ed.tl.video[i].volume.coerceAtMost(1f)
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
