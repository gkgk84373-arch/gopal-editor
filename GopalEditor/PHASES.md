# Gopal Editor: phases · चरण

## Phase 1: MVP (this release) ✅
API Settings + fallback · timeline (video / audio / text) with trim / split / delete / duplicate / zoom / undo · aspect ratios · Auto Captions (Whisper / Gemini / HF, Hindi / English / Hinglish, 8 styles) · export 720p / 1080p / 4K, 24/30/60 fps, bitrate, burn-in · Room autosave · Updates tab + GitHub Actions.

## Phase 2: Editing power · एडिटिंग
- **Speed 0.1x–100x + curve speed**: Media3 1.4.1 already has `SpeedChangeEffect` + `SpeedChangingAudioProcessor` with a `SpeedProvider` (piecewise speed = curves). Pitch-kept audio via Sonic.
- **Freeze frame**: grab the frame (MediaMetadataRetriever / ImageReader) → still-image item with a duration in the sequence.
- **Reverse**: Media3 has no reverse. Plan: decode the clip GOP by GOP with MediaCodec, write the frames in reverse order to a new H.264 file (chunked, so memory stays small), then use that file as the clip.
- **Transitions** (fade, slide, zoom, wipe): a custom `GlShaderProgram` over the overlap between two clips.
- **Filters & effects**: `ColorLut` (.cube LUT presets), `RgbFilter`, `RgbMatrix`, blur/sharpen via `SeparableConvolution`.
- **Keyframe animation**: position / scale / rotation / opacity per clip via `MatrixTransformation` and `OverlaySettings` keyed by time.
- **Sticker & overlay tracks**: PNG/GIF/WebP stickers and text layers as time-ranged `BitmapOverlay`s.

## Phase 3: Colour, audio, compositing · कलर, ऑडियो, कम्पोज़िटिंग
- **Colour**: brightness / contrast / saturation (`Brightness`, `Contrast`, `HslAdjustment`), HSL per-hue and RGB curves (generated 1D/3D LUT).
- **Audio**: record voiceover (MediaRecorder → AAC) onto the audio track; per-clip volume (done) + fade in/out (a gain-ramp `AudioProcessor`); extract the audio of a video as its own clip; multiple music tracks.
- **Chroma key (green screen), mask, PiP, blend modes**: needs multi-layer video. Media3 1.4's `VideoCompositor` is experimental, so the plan is to move to Media3 1.6+ (`CompositionPlayer` + `VideoCompositorSettings`) and add custom shaders for chroma key (YCbCr distance), masks (alpha texture) and blend modes (multiply / screen / overlay).

## Phase 4: AI (all on the user's key) · AI फीचर
- **AI Script Writer**: topic → hook + script + scene list (chat API through the existing fallback chain).
- **Text-to-Speech voiceover**: OpenAI `audio/speech`, Gemini TTS, or ElevenLabs if he adds a key → audio clip on the timeline.
- **AI Translate captions**: any language, timings kept (same numbered-line method as Hinglish).
- **Smart Cut**: silence detection on decoded PCM (RMS threshold + min length), then auto-split and delete; optional filler-word removal from word timestamps.
- **Hook / Title / Hashtag suggestions** from the transcript.
- **AI Chat assistant that edits the timeline**: the model gets the timeline as JSON and replies with tool calls (`split`, `trim`, `delete`, `addCaption`, `setAspect`, `setStyle`…), the same `TimelineOps` the buttons use, applied with one-tap undo.
- **AI image / background generation**: OpenAI Images / Gemini image models, when the user's provider supports it → image clips or backgrounds.

### If a later feature truly needs ffmpeg
FFmpegKit is retired (binaries pulled from Maven in 2025). Options, in order of preference:
1. Keep doing it with Media3 / MediaCodec / OpenGL (covers nearly everything above).
2. Build ffmpeg ourselves for Android (NDK, LGPL config, only the needed filters) and ship it as `jniLibs`; this adds about 6–15 MB per ABI.
3. Community forks of FFmpegKit pinned to a local `.aar` in the repo (not pulled from Maven), after a licence and security check.
