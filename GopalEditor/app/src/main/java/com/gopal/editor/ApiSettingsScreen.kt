package com.gopal.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Draft of the key being added/edited in the form. */
class KeyDraft(k: ApiKey? = null) {
    var id by mutableStateOf(k?.id ?: TimelineOps.idGen())
    var type by mutableStateOf(k?.type ?: ProviderType.GROQ)
    var key by mutableStateOf(k?.key ?: "")
    var model by mutableStateOf(k?.model ?: "")
    var stt by mutableStateOf(k?.sttModel ?: "")
    var base by mutableStateOf(k?.baseUrl ?: "")
    val enabled = k?.enabled ?: true
    fun toKey() = ApiKey(id, type, key.trim(), model.trim(), stt.trim(), base.trim(), enabled)
    val valid: Boolean get() = key.isNotBlank() && (type != ProviderType.CUSTOM || (base.startsWith("https://") || base.startsWith("http://")) && model.isNotBlank())
}

@Composable
fun ApiSettingsScreen(
    keys: List<ApiKey>,
    tests: Map<String, String>,
    testing: Map<String, Boolean>,
    firstLaunch: Boolean,
    encrypted: Boolean,
    onSave: (ApiKey) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onTest: (ApiKey) -> Unit,
    onLang: (String) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    initialDraft: KeyDraft? = null,
    initialShowKey: Boolean = false
) {
    var draft by remember { mutableStateOf(initialDraft ?: KeyDraft()) }
    Column(Modifier.fillMaxSize().background(G.Bg)) {
        TopBar(L.t("API Settings", "API सेटिंग्स"), L.t("Your own AI keys · no server", "आपकी अपनी AI keys · कोई सर्वर नहीं"), onBack) { LangToggle(onLang); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (firstLaunch) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(G.Grad).padding(16.dp)) {
                    Column {
                        Text(L.t("Welcome to Gopal Editor", "गोपाल एडिटर में स्वागत है"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(L.t("Add at least one AI key. Captions and AI tools run on YOUR key, straight from this phone to the provider.",
                            "कम से कम एक AI key डालें। कैप्शन और AI टूल आपकी अपनी key से चलते हैं — सीधे इस फ़ोन से provider तक।"),
                            color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = if (encrypted) G.Ok else G.Warn, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (encrypted) L.t("Keys are stored encrypted on this phone (Android Keystore)", "Keys इस फ़ोन पर encrypted सेव होती हैं (Android Keystore)")
                     else L.t("Secure storage unavailable on this phone: keys kept only until the app closes", "इस फ़ोन पर secure storage नहीं मिला: keys सिर्फ़ ऐप बंद होने तक रहेंगी"),
                    fontSize = 12.sp, color = G.Sub)
            }

            SectionTitle(if (keys.any { it.id == draft.id }) L.t("Edit key", "Key बदलें") else L.t("Add a key", "नई key जोड़ें"))
            KeyForm(draft, tests[draft.id], testing[draft.id] == true, initialShowKey,
                onTest = { onTest(draft.toKey()) },
                onSave = { onSave(draft.toKey()); draft = KeyDraft() })

            SectionTitle(L.t("Fallback order (top is tried first)", "Fallback क्रम (ऊपर वाली पहले चलेगी)"))
            if (keys.isEmpty()) {
                Text(L.t("No keys saved yet.", "अभी कोई key सेव नहीं है।"), color = G.Sub, fontSize = 14.sp)
            }
            keys.forEachIndexed { i, k ->
                SavedKeyRow(i, k, tests[k.id], testing[k.id] == true, i > 0, i < keys.lastIndex,
                    onEdit = { draft = KeyDraft(k) }, onDelete = { onDelete(k.id) }, onMove = { d -> onMove(k.id, d) },
                    onTest = { onTest(k) }, onToggle = { on -> onSave(k.copy(enabled = on)) })
                Spacer(Modifier.height(8.dp))
            }
            Text(L.t("If a provider fails (bad key, no credit, limit, network), the app moves to the next key automatically. Captions need OpenAI, Groq, Gemini, Hugging Face or a Whisper-compatible custom URL.",
                "अगर कोई provider फेल हो (गलत key, क्रेडिट ख़त्म, लिमिट, नेटवर्क), ऐप अपने आप अगली key पर चला जाता है। कैप्शन के लिए OpenAI, Groq, Gemini, Hugging Face या Whisper वाला custom URL चाहिए।"),
                color = G.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        }
        if (firstLaunch) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(L.t("Skip for now", "अभी नहीं"), Modifier.weight(1f), onClick = onDone)
                PrimaryButton(L.t("Continue", "आगे बढ़ें"), Modifier.weight(1f), enabled = keys.isNotEmpty(), onClick = onDone)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = G.Accent, unfocusedBorderColor = G.Line, focusedContainerColor = G.Surface2, unfocusedContainerColor = G.Surface2,
    focusedLabelColor = G.Accent, unfocusedLabelColor = G.Sub, cursorColor = G.Accent
)

@Composable
private fun KeyForm(d: KeyDraft, result: String?, busy: Boolean, initialShow: Boolean, onTest: () -> Unit, onSave: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(initialShow) }
    Card2 {
        Text(L.t("Provider", "Provider"), fontSize = 12.sp, color = G.Sub)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Surface2).border(1.dp, G.Line, RoundedCornerShape(12.dp))
                .clickable { menu = true }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(d.type)
                Spacer(Modifier.width(10.dp))
                Text(d.type.label, color = G.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (d.type.supports(Capability.STT)) Pill(L.t("captions ✓", "कैप्शन ✓"), G.Ok) else Pill(L.t("chat only", "सिर्फ़ चैट"), G.Warn)
                Icon(Icons.Default.ArrowDropDown, null, tint = G.Sub)
            }
            DropdownMenu(menu, { menu = false }) {
                ProviderType.entries.forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, leadingIcon = { ProviderBadge(p) }, onClick = { d.type = p; menu = false })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(d.key, { d.key = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(L.t("API key", "API key")) }, placeholder = { Text(d.type.keyPrefix + "…") },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { Icon(GI.Eye, "show", tint = if (show) G.Accent else G.Sub, modifier = Modifier.clickable { show = !show }) },
            colors = fieldColors())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(d.model, { d.model = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(L.t("Model name", "Model का नाम")) },
            placeholder = { Text(d.type.defaultModel.ifBlank { "model-id" }) }, colors = fieldColors())
        if (d.type.supports(Capability.STT)) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(d.stt, { d.stt = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(L.t("Speech-to-text model (optional)", "Speech-to-text model (वैकल्पिक)")) },
                placeholder = { Text(d.type.defaultSttModel ?: "") }, colors = fieldColors())
        }
        if (d.type == ProviderType.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(d.base, { d.base = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(L.t("Base URL (OpenAI-compatible)", "Base URL (OpenAI-compatible)")) },
                placeholder = { Text("https://api.example.com/v1") }, colors = fieldColors())
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (busy) L.t("Testing…", "जाँच हो रही है…") else L.t("Test Key", "Key जाँचें"), Modifier.weight(1f), enabled = d.valid && !busy, tint = G.Accent, onClick = onTest)
            PrimaryButton(L.t("Save", "सेव करें"), Modifier.weight(1f), enabled = d.valid, onClick = onSave)
        }
        if (busy) { Spacer(Modifier.height(8.dp)); CircularProgressIndicator(Modifier.size(18.dp), color = G.Accent, strokeWidth = 2.dp) }
        if (result != null) { Spacer(Modifier.height(8.dp)); TestResult(result) }
    }
}

@Composable
private fun TestResult(r: String) {
    val ok = r.startsWith("✅")
    Text(r, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background((if (ok) G.Ok else G.Red).copy(alpha = 0.12f)).padding(10.dp),
        color = if (ok) G.Ok else Color(0xFFFF8A9E), fontSize = 13.sp)
}

@Composable
fun ProviderBadge(p: ProviderType, size: Int = 26) {
    val (c, t) = when (p) {
        ProviderType.OPENAI -> Color(0xFF10A37F) to "AI"
        ProviderType.GEMINI -> Color(0xFF4285F4) to "G"
        ProviderType.ANTHROPIC -> Color(0xFFD97757) to "A"
        ProviderType.GROQ -> Color(0xFFF55036) to "gq"
        ProviderType.OPENROUTER -> Color(0xFF6467F2) to "OR"
        ProviderType.HUGGINGFACE -> Color(0xFFFFB000) to "HF"
        ProviderType.CUSTOM -> Color(0xFF6B7280) to "{ }"
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(t, color = Color.White, fontSize = (size * 0.38f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SavedKeyRow(
    index: Int, k: ApiKey, result: String?, busy: Boolean, canUp: Boolean, canDown: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit, onTest: () -> Unit, onToggle: (Boolean) -> Unit
) {
    Card2(pad = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", color = G.Accent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.width(22.dp))
            ProviderBadge(k.type, 30)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(k.type.label, color = G.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${k.masked} · ${k.chatModel}", color = G.Sub, fontSize = 12.sp, maxLines = 1)
            }
            Switch(k.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = G.Accent))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(Icons.Default.KeyboardArrowUp, canUp) { onMove(-1) }
            SmallIcon(Icons.Default.KeyboardArrowDown, canDown) { onMove(1) }
            SmallIcon(Icons.Default.Edit, true, onEdit)
            SmallIcon(Icons.Default.Delete, true, onDelete)
            Spacer(Modifier.weight(1f))
            Text(if (busy) L.t("Testing…", "जाँच…") else L.t("Test", "जाँचें"), Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy, onClick = onTest)
                .padding(horizontal = 12.dp, vertical = 6.dp), color = G.Accent, fontWeight = FontWeight.Bold)
        }
        if (result != null) { Spacer(Modifier.height(4.dp)); TestResult(result) }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) G.Text else G.Sub.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}
