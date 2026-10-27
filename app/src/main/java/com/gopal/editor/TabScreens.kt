package com.gopal.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainTab(val en: String, val hi: String) { HOME("Home", "होम"), PROJECTS("Projects", "प्रोजेक्ट"), AI("AI", "AI"), UPDATES("Updates", "अपडेट"), SETTINGS("Settings", "सेटिंग्स") }

@Composable
fun MainBottomBar(tab: MainTab, hasUpdate: Boolean, onTab: (MainTab) -> Unit) {
    NavigationBar(containerColor = G.Surface, tonalElevation = 0.dp) {
        MainTab.entries.forEach { t ->
            val icon: ImageVector = when (t) {
                MainTab.HOME -> Icons.Default.Home; MainTab.PROJECTS -> GI.Folder; MainTab.AI -> GI.Sparkle
                MainTab.UPDATES -> GI.Update; MainTab.SETTINGS -> Icons.Default.Settings
            }
            NavigationBarItem(
                selected = t == tab, onClick = { onTab(t) },
                icon = {
                    Box {
                        Icon(icon, null)
                        if (t == MainTab.UPDATES && hasUpdate) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp)) { Dot() }
                    }
                },
                label = { Text(L.t(t.en, t.hi), fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = G.Accent, selectedTextColor = G.Accent,
                    indicatorColor = G.Accent.copy(alpha = 0.14f), unselectedIconColor = G.Sub, unselectedTextColor = G.Sub)
            )
        }
    }
}

@Composable
fun HomeScreen(
    projects: List<ProjectEntity>, keyCount: Int,
    onApi: () -> Unit, onNew: () -> Unit, onOpen: (Long) -> Unit, onAllProjects: () -> Unit,
    thumb: (String?) -> ImageBitmap? = { null }
) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gopal Editor", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = G.Text)
                Text("गोपाल एडिटर", fontSize = 13.sp, color = G.Sub)
            }
            Box(Modifier.size(46.dp).clip(CircleShape).background(G.Surface2).clickable(onClick = onApi), contentAlignment = Alignment.Center) {
                Icon(GI.Key, "API", tint = if (keyCount > 0) G.Accent else G.Warn)
                if (keyCount == 0) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Dot(G.Warn, 8.dp) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(172.dp).clip(RoundedCornerShape(22.dp)).background(G.Grad).clickable(onClick = onNew).padding(20.dp)) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(L.t("New project", "नया प्रोजेक्ट"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text(L.t("Pick videos · trim · captions · export", "वीडियो चुनें · ट्रिम · कैप्शन · एक्सपोर्ट"), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                    }
                }
            }
            if (keyCount == 0) item {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(G.Warn.copy(alpha = 0.12f)).clickable(onClick = onApi).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(GI.Key, null, tint = G.Warn); Spacer(Modifier.width(8.dp))
                    Text(L.t("Add your AI key to use Auto Captions", "Auto Captions के लिए अपनी AI key डालें"), color = G.Warn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Recent drafts", "हाल के ड्राफ्ट"), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = G.Text)
                    if (projects.isNotEmpty()) Text(L.t("See all", "सभी देखें"), Modifier.clickable(onClick = onAllProjects), color = G.Accent, fontWeight = FontWeight.Bold)
                }
            }
            if (projects.isEmpty()) item {
                Card2 {
                    Text(L.t("No projects yet. Tap “New project” to start.", "अभी कोई प्रोजेक्ट नहीं। “नया प्रोजेक्ट” दबाएँ।"), color = G.Sub)
                }
            } else item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(projects.take(8), key = { it.id }) { p -> DraftCard(p, thumb(p.thumbPath)) { onOpen(p.id) } }
                }
            }
            item {
                SectionTitle(L.t("Quick tools", "क्विक टूल"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(GI.CC, L.t("Auto captions", "ऑटो कैप्शन"), Modifier.weight(1f), onNew)
                    QuickTile(GI.Cut, L.t("Trim & split", "ट्रिम/स्प्लिट"), Modifier.weight(1f), onNew)
                    QuickTile(GI.Aspect, L.t("Resize 9:16", "साइज़ 9:16"), Modifier.weight(1f), onNew)
                }
            }
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(G.Surface).border(1.dp, G.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = G.Accent); Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = G.Text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DraftCard(p: ProjectEntity, img: ImageBitmap?, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(14.dp)).background(G.Surface2)) {
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(GI.Movie, null, tint = G.Sub, modifier = Modifier.align(Alignment.Center))
            Text(fmtTime(p.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 1.dp), color = Color.White, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = G.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun ProjectsScreen(projects: List<ProjectEntity>, onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, onNew: () -> Unit, thumb: (String?) -> ImageBitmap? = { null }) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("Projects", "प्रोजेक्ट"), L.t("Saved automatically", "अपने आप सेव होते हैं")) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onNew), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = G.Accent) }
        }
        if (projects.isEmpty()) Text(L.t("No projects yet.", "अभी कोई प्रोजेक्ट नहीं।"), Modifier.padding(18.dp), color = G.Sub)
        val df = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(G.Surface).clickable { onOpen(p.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(G.Surface2)) {
                        thumb(p.thumbPath)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = G.Text, fontWeight = FontWeight.Bold)
                        Text("${fmtTime(p.durationMs)} · ${df.format(Date(p.updatedAt))}", color = G.Sub, fontSize = 12.sp)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onDelete(p.id) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Delete, null, tint = G.Sub)
                    }
                }
            }
        }
    }
}

@Composable
fun AiScreen(keyCount: Int, onApi: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("AI tools", "AI टूल"), L.t("All run on your own API key", "सब आपकी अपनी API key से")) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onApi), contentAlignment = Alignment.Center) { Icon(GI.Key, null, tint = G.Accent) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            AiRow(GI.CC, L.t("Auto Captions", "ऑटो कैप्शन"), L.t("Hindi · English · Hinglish, word-by-word highlight. Open a project → Captions.",
                "हिंदी · इंग्लिश · Hinglish, शब्द-दर-शब्द हाइलाइट। प्रोजेक्ट खोलें → कैप्शन।"), live = true, onClick = onNew)
            SectionTitle(L.t("Coming next", "जल्द आ रहा है"))
            listOf(
                L.t("AI Script Writer", "AI स्क्रिप्ट राइटर") to L.t("Topic in, script out", "टॉपिक डालो, स्क्रिप्ट पाओ"),
                L.t("Text-to-Speech voiceover", "टेक्स्ट से आवाज़ (TTS)") to L.t("Voice for your script", "स्क्रिप्ट के लिए आवाज़"),
                L.t("AI Translate captions", "AI कैप्शन ट्रांसलेट") to L.t("Any language", "किसी भी भाषा में"),
                L.t("Smart Cut", "स्मार्ट कट") to L.t("Remove silent parts automatically", "चुप वाले हिस्से अपने आप हटें"),
                L.t("Hook / Title / Hashtags", "हुक / टाइटल / हैशटैग") to L.t("Ideas for your Reel", "रील के लिए सुझाव"),
                L.t("AI Chat assistant", "AI चैट असिस्टेंट") to L.t("“Make this a Reel” → edits the timeline", "“इसे रील बना दो” → टाइमलाइन बदले"),
                L.t("AI image / background", "AI इमेज / बैकग्राउंड") to L.t("If your provider supports it", "अगर provider सपोर्ट करे"),
            ).forEach { (t, s) -> AiRow(GI.Sparkle, t, s, live = false) {} }
            Spacer(Modifier.height(16.dp))
            Text(L.t("$keyCount key(s) saved", "$keyCount key सेव हैं"), color = G.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AiRow(icon: ImageVector, title: String, sub: String, live: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(G.Surface).clickable(enabled = live, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (live) G.Accent.copy(alpha = 0.15f) else G.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (live) G.Accent else G.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (live) G.Text else G.Text.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            Text(sub, color = G.Sub, fontSize = 12.sp)
        }
        if (live) Pill(L.t("live", "चालू"), G.Ok) else Pill(L.t("coming next", "जल्द"), G.Sub)
    }
}

@Composable
fun UpdatesScreen(
    versionName: String, versionCode: Int, repo: String, ui: UpdateUi, hasUpdate: Boolean,
    onCheck: () -> Unit, onUpdate: () -> Unit, onInstall: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("Updates", "अपडेट"), "github.com/$repo")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(G.Grad), contentAlignment = Alignment.Center) {
                        Icon(GI.Movie, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Gopal Editor", color = G.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text(L.t("Installed: v$versionName (build $versionCode)", "इंस्टॉल: v$versionName (build $versionCode)"), color = G.Sub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                GhostButton(if (ui.checking) L.t("Checking…", "जाँच हो रही है…") else L.t("Check for update", "अपडेट चेक करें"),
                    Modifier.fillMaxWidth(), enabled = !ui.checking, icon = Icons.Default.Info, onClick = onCheck)
                if (ui.checking) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = G.Accent, trackColor = G.Surface2) }
            }
            val info = ui.info
            if (hasUpdate && info != null) {
                Spacer(Modifier.height(14.dp))
                Card2 {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(); Spacer(Modifier.width(8.dp))
                        Text(L.t("New version v${info.versionName}", "नया वर्ज़न v${info.versionName}"), color = G.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f)); Pill("build ${info.versionCode}", G.Accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(L.t("What's new", "क्या नया है"), color = G.Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(info.changelog.ifBlank { "—" }, color = G.Text, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    when {
                        ui.downloading >= 0f -> {
                            Text(L.t("Downloading… ${(ui.downloading * 100).toInt()}%", "डाउनलोड हो रहा है… ${(ui.downloading * 100).toInt()}%"), color = G.Text, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator({ ui.downloading }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = G.Accent, trackColor = G.Surface2)
                        }
                        ui.apk != null -> PrimaryButton(L.t("Install now", "अभी इंस्टॉल करें"), Modifier.fillMaxWidth(), icon = GI.Download, onClick = onInstall)
                        else -> PrimaryButton(L.t("Update Now", "अभी अपडेट करें"), Modifier.fillMaxWidth(), icon = GI.Download, onClick = onUpdate)
                    }
                }
            } else if (info != null && !ui.checking) {
                Spacer(Modifier.height(14.dp))
                Text(L.t("✓ You have the latest version (v${info.versionName})", "✓ आपके पास नया वर्ज़न है (v${info.versionName})"), color = G.Ok, fontWeight = FontWeight.Bold)
            }
            if (ui.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(ui.error, color = Color(0xFFFF8A9E), fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(L.t("Updates come from the newest GitHub Release (version.json). Android will ask once to allow installs from this app.",
                "अपडेट GitHub की सबसे नई Release (version.json) से आते हैं। Android एक बार इस ऐप से इंस्टॉल की इजाज़त माँगेगा।"), color = G.Sub, fontSize = 12.sp)
        }
    }
}

@Composable
fun SettingsScreen(keyCount: Int, versionName: String, onApi: () -> Unit, onLang: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("Settings", "सेटिंग्स"))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("Language", "भाषा"), Modifier.weight(1f), color = G.Text, fontWeight = FontWeight.Bold)
                    LangToggle(onLang)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.Surface).clickable(onClick = onApi).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(GI.Key, null, tint = G.Accent); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.t("API Settings", "API सेटिंग्स"), color = G.Text, fontWeight = FontWeight.Bold)
                    Text(L.t("$keyCount key(s) · fallback order", "$keyCount key · fallback क्रम"), color = G.Sub, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Card2 {
                Text(L.t("About", "जानकारी"), color = G.Text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Gopal Editor v$versionName · com.gopal.editor", color = G.Sub, fontSize = 13.sp)
                Text(L.t("No backend: your keys and videos never pass through our server. Fonts: Mukta, Poppins, Baloo 2 (SIL OFL).",
                    "कोई बैकएंड नहीं: आपकी keys और वीडियो हमारे सर्वर से नहीं गुज़रते। फ़ॉन्ट: Mukta, Poppins, Baloo 2 (SIL OFL)।"), color = G.Sub, fontSize = 13.sp)
            }
        }
    }
}
