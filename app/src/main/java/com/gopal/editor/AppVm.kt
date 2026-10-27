package com.gopal.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Panel { NONE, CAPTIONS, STYLE, ASPECT, VOLUME }

/** Everything the editor screen shows. Compose state, so the UI (and Paparazzi renders) read it directly. */
class EditorState(val projectId: Long, name: String, tl: Timeline) {
    var name by mutableStateOf(name)
    var tl by mutableStateOf(tl)
        private set
    var selected by mutableStateOf<String?>(null)
    var playhead by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var pxPerS by mutableFloatStateOf(Zoom.DEFAULT_PX_PER_S)
    var panel by mutableStateOf(Panel.NONE)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f)
    var note by mutableStateOf<String?>(null)
    /** Bumped whenever the clip list changes so the preview player rebuilds its playlist. */
    var mediaVersion by mutableIntStateOf(0)
    private val undoStack = ArrayDeque<Timeline>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun edit(new: Timeline) {
        if (new == tl) return
        undoStack.addLast(tl); if (undoStack.size > 50) undoStack.removeFirst()
        val mediaChanged = new.video != tl.video || new.audio != tl.audio
        tl = new
        if (mediaChanged) mediaVersion++
        playhead = playhead.coerceIn(0, tl.durationMs)
    }
    /** Live update while a finger drags a trim handle / clip (no undo entry, no player rebuild yet). */
    fun setLive(new: Timeline) { tl = new }
    /** End of a drag: one undo step back to [base]; rebuild the preview if media changed. */
    fun commit(base: Timeline) {
        if (base == tl) return
        undoStack.addLast(base); if (undoStack.size > 50) undoStack.removeFirst()
        if (base.video != tl.video || base.audio != tl.audio) mediaVersion++
        playhead = playhead.coerceIn(0, tl.durationMs)
    }
    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val mediaChanged = prev.video != tl.video || prev.audio != tl.audio
        tl = prev
        if (mediaChanged) mediaVersion++
        if (selected != null && tl.trackOf(selected!!) == null) selected = null
    }
}

data class UpdateUi(
    val checking: Boolean = false,
    val info: VersionInfo? = null,
    val error: String? = null,
    val downloading: Float = -1f,
    val apk: File? = null,
    val checkedAt: Long = 0
)

data class ExportUi(val running: Boolean = false, val progress: Int = 0, val file: File? = null, val savedToGallery: Boolean? = null, val error: String? = null)

class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val prefs = AppPrefs(app)
    private val vault = KeyVault(app)
    val ai = AiClient()
    private val updater = Updater(ai.http)
    private val db = EditorDb.get(app)

    val keys = mutableStateListOf<ApiKey>().apply { addAll(vault.load()) }
    val keyTest = mutableStateMapOf<String, String>()      // key id -> last test result
    val keyTesting = mutableStateMapOf<String, Boolean>()
    val vaultEncrypted = vault.isEncrypted
    var setupDone by mutableStateOf(prefs.setupDone)
    var update by mutableStateOf(UpdateUi())
    var export by mutableStateOf(ExportUi())
    var editor by mutableStateOf<EditorState?>(null)
    private var autosave: Job? = null
    private var exportJob: Job? = null

    val projects: StateFlow<List<ProjectEntity>> = db.projects().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val installedCode: Int = BuildConfig.VERSION_CODE
    val hasUpdate: Boolean get() = VersionCompare.isNewer(update.info, installedCode)

    init {
        L.lang = prefs.lang
        if (UpdateConfig.isConfigured) checkUpdate()
    }

    fun setLang(l: String) { L.lang = l; prefs.lang = l }
    fun finishSetup() { setupDone = true; prefs.setupDone = true }

    // ---------------- keys ----------------
    fun saveKey(k: ApiKey) {
        val i = keys.indexOfFirst { it.id == k.id }
        if (i >= 0) keys[i] = k else keys.add(k)
        vault.save(keys.toList())
    }
    fun deleteKey(id: String) { keys.removeAll { it.id == id }; keyTest.remove(id); vault.save(keys.toList()) }
    fun moveKey(id: String, delta: Int) {
        val i = keys.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in keys.indices) return
        val k = keys.removeAt(i); keys.add(j, k); vault.save(keys.toList())
    }
    fun testKey(k: ApiKey) = viewModelScope.launch {
        keyTesting[k.id] = true
        keyTest[k.id] = try { "✅ " + ai.testKey(k).take(120) } catch (e: Exception) { "❌ " + (e.message ?: e.javaClass.simpleName) }
        keyTesting[k.id] = false
    }

    // ---------------- projects ----------------
    private fun displayName(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "clip"

    private fun keep(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun newProject(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val tl = withContext(Dispatchers.IO) {
            var t = Timeline()
            uris.forEach { u -> keep(u); val d = MediaProbe.durationMs(ctx, u); if (d > 0) t = TimelineOps.addVideo(t, u.toString(), displayName(u), d) }
            t
        }
        if (tl.video.isEmpty()) return@launch
        val name = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
        val thumb = withContext(Dispatchers.IO) { MediaProbe.thumbnail(ctx, uris.first(), File(ctx.filesDir, "thumbs/${System.currentTimeMillis()}.jpg")) }
        val id = db.projects().upsert(ProjectEntity(name = name, timelineJson = TimelineJson.encode(tl), thumbPath = thumb?.path, durationMs = tl.durationMs))
        open(id)
    }

    fun open(id: Long) = viewModelScope.launch {
        val p = db.projects().get(id) ?: return@launch
        val ed = EditorState(p.id, p.name, TimelineJson.decode(p.timelineJson))
        editor = ed
        export = ExportUi()
        startAutosave(ed, p)
    }

    @OptIn(FlowPreview::class)
    private fun startAutosave(ed: EditorState, p: ProjectEntity) {
        autosave?.cancel()
        autosave = viewModelScope.launch {
            snapshotFlow { ed.tl to ed.name }.drop(1).debounce(700).collect { (tl, name) ->
                db.projects().upsert(p.copy(name = name, timelineJson = TimelineJson.encode(tl), durationMs = tl.durationMs, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun closeEditor() = viewModelScope.launch {
        val ed = editor ?: return@launch
        autosave?.cancel()
        db.projects().get(ed.projectId)?.let { p ->
            db.projects().upsert(p.copy(name = ed.name, timelineJson = TimelineJson.encode(ed.tl), durationMs = ed.tl.durationMs, updatedAt = System.currentTimeMillis()))
        }
        editor = null
    }

    fun deleteProject(id: Long) = viewModelScope.launch { db.projects().delete(id) }

    fun addClips(uris: List<Uri>) = viewModelScope.launch {
        val ed = editor ?: return@launch
        var t = ed.tl
        withContext(Dispatchers.IO) { uris.forEach { u -> keep(u); val d = MediaProbe.durationMs(ctx, u); if (d > 0) t = TimelineOps.addVideo(t, u.toString(), displayName(u), d) } }
        ed.edit(t)
    }

    fun addAudio(uri: Uri) = viewModelScope.launch {
        val ed = editor ?: return@launch
        keep(uri)
        val d = withContext(Dispatchers.IO) { MediaProbe.durationMs(ctx, uri) }
        if (d <= 0) { ed.note = L.t("Could not read that audio file", "यह ऑडियो फ़ाइल पढ़ी नहीं जा सकी"); return@launch }
        ed.edit(TimelineOps.addAudio(ed.tl, uri.toString(), displayName(uri), d, ed.playhead))
    }

    // ---------------- auto captions ----------------
    /** mode: "hi" | "en" | "hinglish" */
    fun autoCaptions(mode: String) = viewModelScope.launch {
        val ed = editor ?: return@launch
        if (ed.tl.video.isEmpty()) return@launch
        val chain = ProviderChain(keys.toList())
        if (chain.candidates(Capability.STT).isEmpty()) {
            ed.note = L.t("Add an OpenAI, Groq, Gemini or Hugging Face key for captions (API Settings).",
                "कैप्शन के लिए OpenAI, Groq, Gemini या Hugging Face की key डालें (API Settings)।")
            return@launch
        }
        try {
            ed.busy = L.t("Extracting audio…", "ऑडियो निकाला जा रहा है…"); ed.progress = 0f
            val audio = File(ctx.cacheDir, "captions/timeline.m4a").apply { parentFile?.mkdirs(); delete() }
            extractTimelineAudio(ctx, ed.tl, audio) { ed.progress = it / 100f }
            if (audio.length() > 24L * 1024 * 1024) ed.note = L.t("Audio is over 24 MB; some providers may refuse it.", "ऑडियो 24 MB से बड़ा है; कुछ provider मना कर सकते हैं।")
            ed.busy = L.t("Transcribing…", "आवाज़ से टेक्स्ट बन रहा है…"); ed.progress = -1f
            val r = chain.run(Capability.STT) { k ->
                ai.transcribe(k, audio, if (mode == "en") "en" else "hi") { Adts.m4aToAdts(audio, File(ctx.cacheDir, "captions/timeline.aac")) }
            }
            var caps = CaptionBuilder.build(r.value).filter { it.startMs < ed.tl.durationMs }
            var via = r.usedKey.type.label + fallbackNote(r.failures)
            if (mode == "hinglish" && caps.isNotEmpty()) {
                ed.busy = L.t("Converting to Hinglish…", "Hinglish में बदला जा रहा है…")
                try {
                    val lines = caps.map { it.text }
                    val rr = ProviderChain(keys.toList()).run(Capability.CHAT) { k -> ai.chat(k, Romanizer.prompt(lines), maxTokens = 4096) }
                    caps = Romanizer.apply(caps, Romanizer.parse(rr.value, lines))
                    via += " + " + rr.usedKey.type.label
                } catch (e: Exception) {
                    ed.note = L.t("Hinglish step failed, kept Hindi script: ", "Hinglish नहीं बन पाया, हिंदी लिपि रखी: ") + e.message
                }
            }
            ed.edit(ed.tl.copy(captions = caps, captionLang = mode))
            if (ed.note == null) ed.note = L.t("${caps.size} captions via $via", "${caps.size} कैप्शन बने ($via)")
        } catch (e: Exception) {
            ed.note = "❌ " + (e.message ?: e.javaClass.simpleName)
        } finally { ed.busy = null; ed.progress = -1f }
    }

    private fun fallbackNote(f: List<Pair<ApiKey, Throwable>>) =
        if (f.isEmpty()) "" else " (" + L.t("fallback after ", "fallback: ") + f.joinToString { it.first.type.label } + ")"

    // ---------------- export ----------------
    fun startExport(s: ExportSettings) {
        val ed = editor ?: return
        exportJob?.cancel()
        export = ExportUi(running = true)
        exportJob = viewModelScope.launch {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports/GopalEditor_$stamp.mp4").apply { parentFile?.mkdirs() }
            try {
                exportTimeline(ctx, ed.tl, s, out) { export = export.copy(progress = it) }
                export = export.copy(running = false, progress = 100, file = out)
            } catch (e: Exception) {
                export = ExportUi(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
    fun cancelExport() { exportJob?.cancel(); export = ExportUi() }

    fun saveExport(legacyGranted: Boolean) = viewModelScope.launch {
        val f = export.file ?: return@launch
        export = try { export.copy(savedToGallery = saveToGallery(ctx, f, legacyGranted)) } catch (e: Exception) { export.copy(error = e.message) }
    }

    fun shareIntent(): Intent? {
        val f = export.file ?: return null
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
    }

    // ---------------- updates ----------------
    fun checkUpdate() = viewModelScope.launch {
        if (!UpdateConfig.isConfigured) {
            update = update.copy(error = L.t("Update repo not set (updateRepo in gradle.properties)", "अपडेट repo सेट नहीं है (gradle.properties में updateRepo)"))
            return@launch
        }
        update = update.copy(checking = true, error = null)
        update = try {
            update.copy(checking = false, info = updater.fetch(), checkedAt = System.currentTimeMillis())
        } catch (e: Exception) { update.copy(checking = false, error = e.message, checkedAt = System.currentTimeMillis()) }
    }

    fun downloadUpdate() = viewModelScope.launch {
        val info = update.info ?: return@launch
        update = update.copy(downloading = 0f, error = null)
        try {
            val f = updater.download(info.apkUrl, File(ctx.cacheDir, "updates/gopal_editor_${info.versionCode}.apk")) { p -> update = update.copy(downloading = p) }
            update = update.copy(downloading = -1f, apk = f)
            installUpdate()
        } catch (e: Exception) { update = update.copy(downloading = -1f, error = e.message) }
    }

    fun installUpdate() {
        val f = update.apk ?: return
        if (!Updater.canInstall(ctx)) { Updater.openInstallPermission(ctx); return }
        Updater.install(ctx, f)
    }
}
