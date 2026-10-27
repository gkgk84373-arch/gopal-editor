package com.gopal.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun aiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = G.Accent, unfocusedBorderColor = G.Line, focusedContainerColor = G.Surface2, unfocusedContainerColor = G.Surface2,
    focusedLabelColor = G.Accent, unfocusedLabelColor = G.Sub, cursorColor = G.Accent, focusedTextColor = G.Text, unfocusedTextColor = G.Text
)

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Gopal Editor", text))
}

@Composable
private fun ErrorBox(msg: String) {
    Text(msg, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(G.Red.copy(alpha = 0.12f)).padding(10.dp),
        color = Color(0xFFFF8A9E), fontSize = 13.sp)
}

@Composable
private fun ViaLine(via: String) {
    if (via.isNotBlank()) Text(L.t("via ", "चला: ") + via, color = G.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

// ---------------- Script Writer ----------------

@Composable
fun ScriptWriterScreen(st: AiToolState, freeMode: Boolean, onGenerate: () -> Unit, onApi: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("AI Script Writer", "AI स्क्रिप्ट राइटर"), if (freeMode) L.t("Free mode on · no key needed", "Free mode चालू · key ज़रूरी नहीं") else L.t("Runs on your key", "आपकी key से"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(st.topic, { st.topic = it }, Modifier.fillMaxWidth(), label = { Text(L.t("Topic", "टॉपिक")) },
                placeholder = { Text(L.t("e.g. 5 benefits of waking up early", "जैसे: सुबह जल्दी उठने के 5 फ़ायदे")) }, minLines = 2, colors = aiFieldColors())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(st.tone, { st.tone = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(L.t("Tone (optional)", "अंदाज़ (वैकल्पिक)")) },
                placeholder = { Text(L.t("funny, emotional, motivational…", "मज़ेदार, भावुक, मोटिवेशनल…")) }, colors = aiFieldColors())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ScriptWriter.LANGS.forEach { (id, n) -> Chip2(n, st.lang == id) { st.lang = id } } }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ScriptWriter.LENGTHS.forEach { s -> Chip2(L.t("${s}s", "${s} सेकंड"), st.seconds == s) { st.seconds = s } } }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (st.busy) L.t("Writing…", "लिख रहे हैं…") else L.t("Write script", "स्क्रिप्ट लिखें"), Modifier.fillMaxWidth(),
                enabled = !st.busy && st.topic.isNotBlank(), icon = GI.Sparkle, onClick = onGenerate)
            if (st.busy) { Spacer(Modifier.height(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), color = G.Accent, strokeWidth = 2.dp) }
            st.error?.let { Spacer(Modifier.height(8.dp)); ErrorBox(it); Spacer(Modifier.height(6.dp)); GhostButton(L.t("Open API Settings", "API Settings खोलें"), Modifier.fillMaxWidth(), onClick = onApi) }
            if (st.script.isNotBlank()) {
                SectionTitle(L.t("Your script", "आपकी स्क्रिप्ट"))
                Card2 { SelectionContainer { Text(st.script, color = G.Text, fontSize = 16.sp, lineHeight = 24.sp) }; ViaLine(st.via) }
                Spacer(Modifier.height(8.dp))
                GhostButton(L.t("Copy", "कॉपी करें"), Modifier.fillMaxWidth(), icon = GI.Copy) { copy(ctx, st.script) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------- Hook / Title / Hashtags ----------------

@Composable
fun IdeasScreen(st: AiToolState, hasProjectCaptions: Boolean, onGenerate: () -> Unit, onApi: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("Hook · Title · Hashtags", "हुक · टाइटल · हैशटैग"), L.t("Ideas for your Reel", "आपकी रील के लिए सुझाव"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(st.topic, { st.topic = it }, Modifier.fillMaxWidth(), label = { Text(L.t("Topic / what the video is about", "टॉपिक / वीडियो किस बारे में है")) },
                minLines = 2, colors = aiFieldColors())
            if (hasProjectCaptions) Text(L.t("The open project's captions are used too.", "खुले प्रोजेक्ट के कैप्शन भी इस्तेमाल होंगे।"), color = G.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chip2("हिंदी", st.lang != "en") { st.lang = "hi" }; Chip2("English", st.lang == "en") { st.lang = "en" } }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (st.busy) L.t("Thinking…", "सोच रहे हैं…") else L.t("Get ideas", "सुझाव पाएँ"), Modifier.fillMaxWidth(),
                enabled = !st.busy && (st.topic.isNotBlank() || hasProjectCaptions), icon = GI.Sparkle, onClick = onGenerate)
            if (st.busy) { Spacer(Modifier.height(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), color = G.Accent, strokeWidth = 2.dp) }
            st.error?.let { Spacer(Modifier.height(8.dp)); ErrorBox(it); Spacer(Modifier.height(6.dp)); GhostButton(L.t("Open API Settings", "API Settings खोलें"), Modifier.fillMaxWidth(), onClick = onApi) }
            st.ideas?.let { ideas ->
                IdeaGroup(L.t("Hooks (first line)", "हुक (पहली लाइन)"), ideas.hooks) { copy(ctx, it) }
                IdeaGroup(L.t("Titles", "टाइटल"), ideas.titles) { copy(ctx, it) }
                if (ideas.hashtags.isNotEmpty()) {
                    SectionTitle(L.t("Hashtags", "हैशटैग"))
                    Card2 { Text(ideas.hashtags.joinToString(" "), color = G.Accent, fontSize = 14.sp) }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(L.t("Copy all hashtags", "सारे हैशटैग कॉपी करें"), Modifier.fillMaxWidth(), icon = GI.Copy) { copy(ctx, ideas.hashtags.joinToString(" ")) }
                }
                ViaLine(st.via)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdeaGroup(title: String, items: List<String>, onCopy: (String) -> Unit) {
    if (items.isEmpty()) return
    SectionTitle(title)
    items.forEach { t ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(G.Surface).clickable { onCopy(t) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t, Modifier.weight(1f), color = G.Text, fontSize = 14.sp)
            androidx.compose.material3.Icon(GI.Copy, L.t("copy", "कॉपी"), tint = G.Sub, modifier = Modifier.size(18.dp))
        }
    }
}

// ---------------- captions: translate + language fix ----------------

@Composable
fun CaptionAiRow(ed: EditorState, onTranslate: (String) -> Unit, onFix: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val enabled = ed.busy == null && ed.tl.captions.isNotEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            GhostButton(L.t("Translate ▾", "ट्रांसलेट ▾"), Modifier.fillMaxWidth(), enabled = enabled, tint = G.Accent) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                CaptionAi.TARGETS.forEach { (prompt, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onTranslate(prompt) }) }
            }
        }
        GhostButton(L.t("Fix language", "भाषा सुधारें"), Modifier.weight(1f), enabled = enabled, tint = G.Accent, onClick = onFix)
    }
}

/** Preview before applying: every changed line shows old → new with a tick; nothing changes until Apply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionProposalDialog(p: CaptionProposal, onApply: (Set<Int>) -> Unit, onCancel: () -> Unit) {
    val changed = p.changes.filter { it.changed }
    var picked by remember(p) { mutableStateOf(changed.map { it.index }.toSet()) }
    BasicAlertDialog(onDismissRequest = onCancel) {
        Card2 {
            Text(p.title, color = G.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(L.t("${changed.size} of ${p.changes.size} lines change. Untick any you want to keep.", "${p.changes.size} में से ${changed.size} लाइनें बदलेंगी। जो न बदलनी हों, उनका टिक हटाएँ।"),
                color = G.Sub, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (changed.isEmpty()) Text(L.t("Nothing to change: the captions already look right.", "कुछ बदलना नहीं है: कैप्शन पहले से सही हैं।"), color = G.Ok, fontSize = 14.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(changed, key = { it.index }) { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(G.Surface2).padding(8.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(c.index in picked, { on -> picked = if (on) picked + c.index else picked - c.index },
                            colors = CheckboxDefaults.colors(checkedColor = G.Accent))
                        Column(Modifier.weight(1f)) {
                            Text(c.old, color = G.Sub, fontSize = 13.sp)
                            Text("→ " + c.new, color = G.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            ViaLine(p.via)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(L.t("Cancel", "रद्द"), Modifier.weight(1f), onClick = onCancel)
                PrimaryButton(L.t("Apply", "लागू करें") + " (${picked.size})", Modifier.weight(1f), enabled = picked.isNotEmpty()) { onApply(picked) }
            }
        }
    }
}

// ---------------- AI image (background / sticker) ----------------

@Composable
fun AiImagePanel(ed: EditorState, onGenerate: (String, Boolean) -> Unit, onAdd: (Long) -> Unit) {
    var prompt by remember { mutableStateOf(ed.aiImage.prompt) }
    var sticker by remember { mutableStateOf(ed.aiImage.sticker) }
    var secs by remember { mutableStateOf(3) }
    val ui = ed.aiImage
    PanelFrame(L.t("AI image", "AI इमेज"), ed, 330) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip2(L.t("Background", "बैकग्राउंड"), !sticker) { sticker = false }
                Chip2(L.t("Sticker", "स्टिकर"), sticker) { sticker = true }
            }
            if (sticker) Text(L.t("No sticker/overlay track yet: it is added as a short image clip.", "स्टिकर/ओवरले ट्रैक अभी नहीं है: यह छोटी इमेज क्लिप की तरह जुड़ेगा।"),
                color = G.Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(L.t("Describe the picture", "तस्वीर के बारे में लिखें")) },
                placeholder = { Text(L.t("e.g. golden temple at sunrise", "जैसे: सूर्योदय में स्वर्ण मंदिर")) }, maxLines = 3, colors = aiFieldColors())
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (ui.busy) L.t("Making the picture… (up to a minute)", "तस्वीर बन रही है… (एक मिनट तक)") else L.t("Generate", "बनाएँ"), Modifier.fillMaxWidth(),
                enabled = !ui.busy && prompt.isNotBlank() && ed.busy == null, icon = GI.Sparkle) { onGenerate(prompt, sticker) }
            ui.error?.let { Spacer(Modifier.height(6.dp)); ErrorBox(it) }
            ui.file?.let { f ->
                val bmp = remember(f) { runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bmp != null) Image(bmp, null, Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, G.Line, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(2, 3, 5).forEach { s -> Chip2(L.t("${s}s", "${s} से"), secs == s) { secs = s } }
                        }
                        Spacer(Modifier.height(6.dp))
                        PrimaryButton(L.t("Add at playhead", "प्लेहेड पर जोड़ें"), Modifier.fillMaxWidth(), enabled = ed.busy == null) { onAdd(secs * 1000L) }
                    }
                }
                ViaLine(ui.via)
            }
        }
    }
}

// ---------------- API Settings: Free mode card ----------------

@Composable
fun FreeModeCard(f: FreeAiUi, userKeyTypes: Set<ProviderType>, onFreeMode: (Boolean) -> Unit, onUrl: (String) -> Unit, onReload: () -> Unit, onAddKeyFor: (FreeProvider) -> Unit) {
    var url by remember(f.url) { mutableStateOf(f.url) }
    var showList by remember { mutableStateOf(false) }
    Card2 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Free mode (बिना key)", color = G.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(if (f.freeMode) L.t("ON: free AI with no key. Your own keys still go first.", "चालू: बिना key के मुफ़्त AI। आपकी अपनी key हो तो वही पहले चलेगी।")
                     else L.t("OFF: only your own keys are used.", "बंद: सिर्फ़ आपकी अपनी keys चलेंगी।"), color = G.Sub, fontSize = 12.sp)
            }
            Switch(f.freeMode, onFreeMode, colors = SwitchDefaults.colors(checkedTrackColor = G.Accent))
        }
        Spacer(Modifier.height(8.dp))
        val src = when (f.source) {
            ProviderListChooser.Source.ONLINE -> L.t("downloaded now", "अभी डाउनलोड हुई")
            ProviderListChooser.Source.CACHE -> L.t("last saved copy", "आख़िरी सेव कॉपी")
            ProviderListChooser.Source.BUNDLED -> L.t("built-in list", "ऐप की अपनी लिस्ट")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.t("Providers: ${f.providers.size} · $src", "Providers: ${f.providers.size} · $src"), color = G.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (f.loading) CircularProgressIndicator(Modifier.size(16.dp), color = G.Accent, strokeWidth = 2.dp)
            Text(if (showList) L.t("Hide", "छुपाएँ") else L.t("Show", "देखें"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { showList = !showList }.padding(6.dp),
                color = G.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        f.fetchError?.let { Text(L.t("Online list not loaded: $it", "ऑनलाइन लिस्ट नहीं आई: $it"), color = G.Warn, fontSize = 11.sp) }
        if (showList) {
            Spacer(Modifier.height(6.dp))
            f.providers.sortedWith(compareBy({ it.needsKey }, { it.priority })).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(G.Surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = G.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(p.caps.joinToString(" + ") { if (it == FreeCap.IMAGE) L.t("image", "इमेज") else L.t("text", "टेक्स्ट") } +
                            (if (p.model.isNotBlank()) " · " + p.model else ""), color = G.Sub, fontSize = 11.sp, maxLines = 1)
                    }
                    when {
                        !p.needsKey -> Pill(L.t("no key", "बिना key"), G.Ok)
                        p.keyType != null && p.keyType in userKeyTypes -> Pill(L.t("your key ✓", "आपकी key ✓"), G.Accent)
                        else -> Text(L.t("Add key", "Key डालें"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAddKeyFor(p) }.padding(6.dp),
                            color = G.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(L.t("providers.json URL", "providers.json का URL")) },
                placeholder = { Text(f.defaultUrl.ifBlank { "https://raw.githubusercontent.com/USER/REPO/main/providers.json" }, maxLines = 1) }, colors = aiFieldColors())
            Text(L.t("Must be a PUBLIC repo or a gist (raw link). A private repo can't be read by the app; then the saved/built-in list is used. Empty = default from the Updates repo.",
                "यह PUBLIC repo या gist (raw लिंक) में होना चाहिए। प्राइवेट repo ऐप नहीं पढ़ सकता; तब सेव की हुई/ऐप की अपनी लिस्ट चलेगी। खाली = Updates repo वाला डिफ़ॉल्ट।"),
                color = G.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(L.t("Reload", "फिर लोड करें"), Modifier.weight(1f), enabled = !f.loading, onClick = onReload)
                PrimaryButton(L.t("Save URL", "URL सेव करें"), Modifier.weight(1f), enabled = url.isBlank() || url.trim().startsWith("https://")) { onUrl(url) }
            }
        }
    }
}
