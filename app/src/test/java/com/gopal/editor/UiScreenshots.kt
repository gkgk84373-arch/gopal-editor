package com.gopal.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/**
 * Real renders of the MVP screens on the JVM (Paparazzi / layoutlib), Pixel 6 size.
 * Run: gradle recordPaparazziDebug -> app/src/test/snapshots/images/
 * The preview box shows a still frame (in the app, ExoPlayer plays the video in that box);
 * the captions on it are drawn by the same CaptionRenderer the export burns in.
 */
class UiScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false),
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.NORMAL,
        maxPercentDifference = 1.0,
        useDeviceResolution = true
    )

    private fun img(name: String): ImageBitmap? =
        javaClass.classLoader?.getResourceAsStream(name)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }

    @Composable private fun Frame(content: @Composable () -> Unit) {
        GopalTheme { Surface(Modifier.fillMaxSize(), color = G.Bg) { content() } }
    }

    private fun words(start: Long, text: String, msPerWord: Long = 380): List<Word> =
        text.split(" ").mapIndexed { i, w -> Word(start + i * msPerWord, start + (i + 1) * msPerWord, w) }

    private fun demoTimeline(style: String): Timeline {
        val caps = listOf(
            "मैं तो हर बात बिना पूछे मानता हूँ!" to 0L,
            "सड़क भी बिना देखे पार करोगे?" to 3_200L,
            "दावा बड़ा है तो सबूत भी दमदार हो" to 5_800L,
            "सवाल पूछना गलत नहीं है" to 9_000L,
            "राय बदलना हार नहीं, अपडेट है!" to 11_600L,
        ).mapIndexed { i, (t, s) -> Caption("c$i", s, s + 2_600, t, words(s, t)) }
        return Timeline(
            video = listOf(
                VideoClip("v1", "content://demo/a", "andhbhakt_1.mp4", 9_000, 0, 6_400),
                VideoClip("v2", "content://demo/a", "andhbhakt_1.mp4", 9_000, 6_400, 9_000),
                VideoClip("v3", "content://demo/b", "scene_7.mp4", 12_000, 1_000, 9_500),
            ),
            audio = listOf(AudioClip("a1", "content://demo/m", "background_beat.mp3", 60_000, 0, 14_000, 400, 0.35f)),
            captions = caps, aspect = Aspect.R9_16, styleId = style, captionLang = "hi"
        )
    }

    private fun editor(style: String, sel: String? = null, panel: Panel = Panel.NONE) =
        EditorState(1, "अंधभक्त vs देशभक्त", demoTimeline(style)).apply {
            playhead = 1_500; pxPerS = 62f; selected = sel; this.panel = panel
            note = "5 कैप्शन बने (Groq)"
        }

    @Test fun apiSettings() {
        val keys = listOf(
            ApiKey("k1", ProviderType.GROQ, "gsk_8fK2demoKeyNotReal93xQ", "llama-3.3-70b-versatile"),
            ApiKey("k2", ProviderType.OPENAI, "sk-proj-demoKeyNotReal7Lm2", "gpt-4o-mini"),
            ApiKey("k3", ProviderType.ANTHROPIC, "sk-ant-demoKeyNotRealZz81", "claude-haiku-4-5", enabled = false),
        )
        val draft = KeyDraft().apply { type = ProviderType.GEMINI; key = "AIzaSyDemoKeyNotReal_4fT"; model = "gemini-2.5-flash" }
        L.lang = "hi"
        paparazzi.snapshot("1_api_settings") {
            Frame {
                ApiSettingsScreen(keys, mapOf(draft.id to "✅ OK", "k1" to "✅ OK", "k2" to "❌ OpenAI: HTTP 429 – You exceeded your current quota"), emptyMap(),
                    firstLaunch = true, encrypted = true, onSave = {}, onDelete = {}, onMove = { _, _ -> }, onTest = {}, onLang = {}, onDone = {}, onBack = null,
                    initialDraft = draft)
            }
        }
    }

    @Test fun editorTimelineCaptions() {
        val frame = img("sample_frame.jpg")
        val thumbs = buildMap { frame?.let { put("content://demo/a", it) }; img("thumb2.jpg")?.let { put("content://demo/b", it) } }
        L.lang = "hi"
        paparazzi.snapshot("2_editor") {
            Frame {
                EditorScreen(editor("karaoke", sel = "c0"), EditorActions(), thumbs) {
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }

    @Test fun editorCaptionsPanel() {
        val frame = img("sample_frame.jpg")
        val thumbs = buildMap { frame?.let { put("content://demo/a", it) }; img("thumb2.jpg")?.let { put("content://demo/b", it) } }
        L.lang = "hi"
        paparazzi.snapshot("3_editor_captions") {
            Frame {
                EditorScreen(editor("highlight_box", panel = Panel.CAPTIONS).apply { note = null }, EditorActions(), thumbs) {
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }

    @Test fun editorStyles() {
        val frame = img("sample_frame.jpg")
        L.lang = "en"
        paparazzi.snapshot("4_editor_styles") {
            Frame {
                EditorScreen(editor("reels_bold", panel = Panel.STYLE).apply { note = null }, EditorActions(), emptyMap()) {
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }

    @Test fun export() {
        L.lang = "hi"
        paparazzi.snapshot("5_export") {
            Frame {
                ExportScreen(demoTimeline("karaoke"), ExportSettings(1080, 30, 0), EncoderSupport(ok4k = false), ExportUi(),
                    {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun exportProgress() {
        L.lang = "hi"
        paparazzi.snapshot("6_export_progress") {
            Frame { ExportScreen(demoTimeline("karaoke"), ExportSettings(), EncoderSupport(), ExportUi(running = true, progress = 63), {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun updates() {
        L.lang = "hi"
        val info = VersionInfo(14, "1.0.14", "https://github.com/gk/GopalEditor/releases/download/v1.0.14/gopal-editor.apk",
            "• Auto captions: Hinglish mode\n• Faster export\n• Timeline zoom fix")
        paparazzi.snapshot("7_updates") {
            Frame {
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            UpdatesScreen("1.0.0", 1, "gk/GopalEditor", UpdateUi(info = info, downloading = 0.42f), hasUpdate = true, onCheck = {}, onUpdate = {}, onInstall = {})
                        }
                        MainBottomBar(MainTab.UPDATES, hasUpdate = true) {}
                    }
                }
            }
        }
    }

    @Test fun home() {
        L.lang = "hi"
        val now = 1_761_550_000_000L
        val ps = listOf(
            ProjectEntity(1, "अंधभक्त vs देशभक्त", "", "sample_frame.jpg", 18_000, now, now),
            ProjectEntity(2, "Cosmic reel", "", "thumb1.jpg", 32_000, now, now - 3_600_000),
            ProjectEntity(3, "Mindset", "", "thumb2.jpg", 45_000, now, now - 86_400_000),
        )
        paparazzi.snapshot("8_home") {
            Frame {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { HomeScreen(ps, 2, {}, {}, {}, {}, thumb = { p -> p?.let { img(it) } }) }
                    MainBottomBar(MainTab.HOME, hasUpdate = true) {}
                }
            }
        }
    }
}
