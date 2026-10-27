package com.gopal.editor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

enum class Route { MAIN, API, EDITOR, EXPORT, SCRIPT, IDEAS }

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GopalTheme { Surface(Modifier.fillMaxSize(), color = G.Bg) { App(vm) } } }
    }
}

@Composable
fun App(vm: AppVm) {
    val ctx = LocalContext.current
    var route by remember { mutableStateOf(Route.MAIN) }
    var tab by remember { mutableStateOf(MainTab.HOME) }
    val projects by vm.projects.collectAsState()
    val thumbCache = remember { HashMap<String, ImageBitmap?>() }
    val thumb: (String?) -> ImageBitmap? = { p -> if (p == null) null else thumbCache.getOrPut(p) { runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }

    val pickNew = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.newProject(uris) }
    val pickClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addClips(uris) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.addAudio(u) }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.importLut(u) }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.saveExport(ok) }

    // open the editor as soon as a project is loaded
    LaunchedEffect(vm.editor) { if (vm.editor != null && route == Route.MAIN) route = Route.EDITOR }

    val safe = Modifier.fillMaxSize().safeDrawingPadding()
    if (!vm.setupDone) {
        Box(safe) {
            ApiSettingsScreen(vm.keys, vm.keyTest, vm.keyTesting, firstLaunch = true, encrypted = vm.vaultEncrypted,
                onSave = vm::saveKey, onDelete = vm::deleteKey, onMove = vm::moveKey, onTest = { vm.testKey(it) },
                onLang = vm::setLang, onDone = vm::finishSetup, onBack = null,
                free = vm.free, onFreeMode = vm::setFreeMode, onProvidersUrl = vm::setProvidersUrl, onReloadProviders = { vm.reloadProviders() })
        }
        return
    }

    when (route) {
        Route.API -> {
            BackHandler { route = if (vm.editor != null) Route.EDITOR else Route.MAIN }
            Box(safe) {
                ApiSettingsScreen(vm.keys, vm.keyTest, vm.keyTesting, firstLaunch = false, encrypted = vm.vaultEncrypted,
                    onSave = vm::saveKey, onDelete = vm::deleteKey, onMove = vm::moveKey, onTest = { vm.testKey(it) },
                    onLang = vm::setLang, onDone = {}, onBack = { route = if (vm.editor != null) Route.EDITOR else Route.MAIN },
                    free = vm.free, onFreeMode = vm::setFreeMode, onProvidersUrl = vm::setProvidersUrl, onReloadProviders = { vm.reloadProviders() })
            }
        }
        Route.SCRIPT -> {
            BackHandler { route = Route.MAIN }
            Box(safe) { ScriptWriterScreen(vm.tools, vm.free.freeMode, onGenerate = { vm.writeScript() }, onApi = { route = Route.API }, onBack = { route = Route.MAIN }) }
        }
        Route.IDEAS -> {
            BackHandler { route = Route.MAIN }
            Box(safe) { IdeasScreen(vm.tools, vm.editor?.tl?.captions?.isNotEmpty() == true, onGenerate = { vm.suggestIdeas() }, onApi = { route = Route.API }, onBack = { route = Route.MAIN }) }
        }
        Route.EDITOR -> {
            val ed = vm.editor
            if (ed == null) { route = Route.MAIN; return }
            BackHandler { if (ed.panel != Panel.NONE) ed.panel = Panel.NONE else { vm.closeEditor(); route = Route.MAIN } }
            EditorRoute(ed, vm, onExport = { route = Route.EXPORT }, onApi = { route = Route.API },
                onAddClip = { pickClip.launch(arrayOf("video/*")) }, onAddAudio = { pickAudio.launch(arrayOf("audio/*")) },
                onImportLut = { pickLut.launch(arrayOf("*/*")) },
                onClose = { vm.closeEditor(); route = Route.MAIN }, modifier = safe)
        }
        Route.EXPORT -> {
            val ed = vm.editor
            if (ed == null) { route = Route.MAIN; return }
            var settings by remember { mutableStateOf(ExportSettings()) }
            var support by remember { mutableStateOf(EncoderSupport()) }
            LaunchedEffect(ed.tl.aspect, settings.fps) {
                support = withContext(Dispatchers.Default) {
                    fun ok(p: Int) = ExportMath.outputSize(ed.tl.aspect, p).let { (w, h) -> EncoderCaps.pick(w, h, settings.fps) }
                    val k4 = ok(2160)
                    EncoderSupport(ok(720) != null, ok(1080) != null, k4 != null, k4?.label)
                }
                if (settings.shortSide == 2160 && !support.ok4k) settings = settings.copy(shortSide = 1080)
            }
            BackHandler(enabled = !vm.export.running) { vm.export = ExportUi(); route = Route.EDITOR }
            Box(safe) {
                ExportScreen(ed.tl, settings, support, vm.export, onSettings = { settings = it }, onStart = { vm.startExport(settings) },
                    onCancel = vm::cancelExport,
                    onSave = {
                        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else vm.saveExport(true)
                    },
                    onShare = { vm.shareIntent()?.let { ctx.startActivity(it) } },
                    onBack = { vm.export = ExportUi(); route = Route.EDITOR })
            }
        }
        Route.MAIN -> Column(safe) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    MainTab.HOME -> HomeScreen(projects, vm.keys.size, onApi = { route = Route.API }, onNew = { pickNew.launch(arrayOf("video/*")) },
                        onOpen = { vm.open(it) }, onAllProjects = { tab = MainTab.PROJECTS }, thumb = thumb)
                    MainTab.PROJECTS -> ProjectsScreen(projects, onOpen = { vm.open(it) }, onDelete = vm::deleteProject, onNew = { pickNew.launch(arrayOf("video/*")) }, thumb = thumb)
                    MainTab.AI -> AiScreen(vm.keys.size, onApi = { route = Route.API }, onNew = { pickNew.launch(arrayOf("video/*")) },
                        freeMode = vm.free.freeMode, onScript = { route = Route.SCRIPT }, onIdeas = { route = Route.IDEAS })
                    MainTab.UPDATES -> UpdatesScreen(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, UpdateConfig.REPO, vm.update, vm.hasUpdate,
                        onCheck = { vm.checkUpdate() }, onUpdate = { vm.downloadUpdate() }, onInstall = vm::installUpdate)
                    MainTab.SETTINGS -> SettingsScreen(vm.keys.size, BuildConfig.VERSION_NAME, onApi = { route = Route.API }, onLang = vm::setLang)
                }
            }
            MainBottomBar(tab, vm.hasUpdate) { tab = it }
        }
    }
}

@Composable
private fun EditorRoute(
    ed: EditorState, vm: AppVm, onExport: () -> Unit, onApi: () -> Unit, onAddClip: () -> Unit, onAddAudio: () -> Unit,
    onImportLut: () -> Unit, onClose: () -> Unit, modifier: Modifier
) {
    val luts by vm.luts.collectAsState()
    val ctx = LocalContext.current
    val pc = remember(ed) { PreviewController(ctx, ed) }
    DisposableEffect(pc) { onDispose { pc.release() } }
    LaunchedEffect(ed.mediaVersion) { pc.rebuild() }
    LaunchedEffect(pc) { while (true) { pc.tick(); delay(33) } }
    LaunchedEffect(ed.tl) { pc.refreshLook() }   // a filter/adjust change shows at once, even while paused
    // filmstrip thumbnails for the timeline (one frame per source video)
    val thumbs = remember(ed) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(ed.mediaVersion) {
        ed.tl.video.map { it.uri }.distinct().filter { it !in thumbs }.forEach { u ->
            val bmp = withContext(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "strip/${u.hashCode()}.jpg")
                (if (f.exists()) f else MediaProbe.thumbnail(ctx, Uri.parse(u), f))?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
            if (bmp != null) thumbs[u] = bmp
        }
    }
    Box(modifier) {
        EditorScreen(ed, EditorActions(
            onBack = onClose, onExport = { pc.player.pause(); onExport() }, onAddClip = onAddClip, onAddAudio = onAddAudio,
            onAutoCaptions = { vm.autoCaptions(it) }, onPlayPause = pc::playPause, onSeek = pc::seekTo, onApi = onApi,
            luts = luts, onImportLut = onImportLut,
            onFreeze = { pc.player.pause(); vm.freezeFrame(it) },
            onReverse = { pc.player.pause(); vm.reverseClip(it) },
            ai = EditorAiActions(
                onTranslate = { vm.captionAi("translate", it) }, onFixCaptions = { vm.captionAi("fix") },
                onApplyProposal = { vm.applyProposal(it) }, onDiscardProposal = vm::discardProposal,
                onGenImage = { p, s -> vm.generateImage(p, s) }, onAddImage = { pc.player.pause(); vm.addAiImage(it) }
            )
        ), thumbs) { PlayerSurface(pc.player, ed.tl.fill) }
    }
}
