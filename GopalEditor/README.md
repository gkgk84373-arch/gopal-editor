# Gopal Editor · गोपाल एडिटर

A CapCut-style video editor for Android that runs entirely on **your own AI API keys**.
There is no backend server: the app talks straight to OpenAI / Gemini / Claude / Groq / OpenRouter / Hugging Face / your own OpenAI-compatible URL.

**Phase 1 (MVP):** API Settings (7 providers, Test Key, auto-fallback, encrypted keys) · multi-track timeline (video, audio, text) with pinch-zoom, drag, trim handles, split, delete, duplicate, undo · 9:16 / 16:9 / 1:1 / 4:5 · Auto Captions (Hindi, English, Hinglish) with 8 caption styles incl. word-by-word highlight · export 720p / 1080p / 4K (only if the phone's encoder supports it), 24/30/60 fps, bitrate choice, captions burned in · save to gallery and share · auto-saved projects (Room) · Updates tab that installs new versions from your GitHub Releases · Hindi / English UI.

Phases 2–4 are listed in [PHASES.md](PHASES.md).

- Kotlin, Jetpack Compose (Material 3), Media3 1.4.1 (ExoPlayer + Transformer), Room, OkHttp, Coroutines
- minSdk 24 · targetSdk 34 · compileSdk 35 · Gradle 8.9 · JDK 17 · package `com.gopal.editor`
- **No FFmpegKit:** that project was retired and its binaries were removed from Maven in 2025, so a fresh build would fail to download it. Everything here (trim, join, mixing, resize, captions burn-in, audio extraction) is done with Media3 Transformer and the phone's own MediaCodec hardware.

---

## हिंदी: Termux से GitHub पर पुश करके APK बनाना

नीचे की हर लाइन Termux में एक-एक करके चलाएँ।

### 1) Termux तैयार करें
```bash
pkg update -y && pkg upgrade -y
pkg install -y git unzip openjdk-17
termux-setup-storage          # "Allow" दबाएँ — इससे /sdcard दिखेगा
```

### 2) प्रोजेक्ट खोलें
`gopal_editor_v1_src.zip` को फ़ोन के Download फ़ोल्डर में रखें, फिर:
```bash
cd ~
unzip -o /sdcard/Download/gopal_editor_v1_src.zip -d ~/
cd ~/GopalEditor
ls                             # app/  .github/  README.md ... दिखने चाहिए
```

### 3) GitHub पर खाली repo बनाएँ
github.com → **New repository** → नाम `GopalEditor` → README मत जोड़ें → Create.
पासवर्ड की जगह **Personal Access Token** लगेगा: GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token → `repo` और `workflow` दोनों टिक करें → कॉपी करके रखें।

### 4) Release key (keystore) बनाएँ — सिर्फ़ एक बार
```bash
keytool -genkeypair -v -keystore ~/gopal-editor-release.jks -alias gopaleditor \
  -keyalg RSA -keysize 2048 -validity 10000
```
यह पासवर्ड और नाम पूछेगा। पासवर्ड याद रखें।
⚠️ यह `.jks` फ़ाइल और पासवर्ड संभालकर रखें (Google Drive पर भी कॉपी)। खो गई तो आगे के अपडेट पुराने ऐप के ऊपर इंस्टॉल नहीं होंगे।
```bash
cp ~/gopal-editor-release.jks /sdcard/Download/     # बैकअप
base64 -w 0 ~/gopal-editor-release.jks > ~/keystore_base64.txt
cat ~/keystore_base64.txt                           # पूरी एक लंबी लाइन कॉपी करें
```

### 5) GitHub Secrets डालें
Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| नाम | क्या डालना है |
|---|---|
| `KEYSTORE_BASE64` | `keystore_base64.txt` की पूरी लाइन |
| `KEYSTORE_PASSWORD` | keystore का पासवर्ड |
| `KEY_ALIAS` | `gopaleditor` |
| `KEY_PASSWORD` | वही पासवर्ड (keytool ने अलग नहीं पूछा तो वही) |

### 6) पुश करें
```bash
cd ~/GopalEditor
git config --global user.name "Gopal"
git config --global user.email "aapka@email.com"
git config --global --add safe.directory '*'
git init
git add .
git commit -m "Gopal Editor MVP"
git branch -M main
git remote add origin https://github.com/USERNAME/GopalEditor.git
git push -u origin main         # Username = GitHub नाम, Password = Token
```
अब repo → **Actions** टैब में "Release APK" चलता दिखेगा (लगभग 6–10 मिनट)। पूरा होने पर **Releases** में `gopal-editor.apk` और `version.json` होंगे।

### 7) आगे के बदलाव
```bash
cd ~/GopalEditor
git add .
git commit -m "क्या बदला वो लिखें"     # यही लाइन ऐप के Updates टैब में changelog बनकर दिखेगी
git push
```
हर push पर नया वर्ज़न (1.0.N) बनेगा, और फ़ोन में ऐप के **Updates** टैब पर लाल डॉट आएगा → **Update Now**।

**ध्यान दें:** Tab ने जो टेस्ट APK भेजा वह एक टेस्ट key से साइन है। GitHub से बना पहला APK इंस्टॉल करने से पहले टेस्ट वाला ऐप **Uninstall** करें (key अलग होने पर Android ऊपर से इंस्टॉल नहीं करता)। उसके बाद के सारे अपडेट अपने आप ऊपर से इंस्टॉल होंगे।

Updates वाला repo अपने आप सेट होता है (workflow `-PupdateRepo=${{ github.repository }}` भेजता है)। हाथ से बदलना हो तो `gradle.properties` में `updateRepo=USERNAME/GopalEditor`।

---

## English: build the APK by pushing from Termux

Run each line in Termux.

### 1) Prepare Termux
```bash
pkg update -y && pkg upgrade -y
pkg install -y git unzip openjdk-17
termux-setup-storage          # tap "Allow" so /sdcard is visible
```

### 2) Unpack the project
Put `gopal_editor_v1_src.zip` in your phone's Download folder, then:
```bash
cd ~
unzip -o /sdcard/Download/gopal_editor_v1_src.zip -d ~/
cd ~/GopalEditor
```

### 3) Create an empty GitHub repo
github.com → **New repository** → `GopalEditor` → no README → Create.
Git over HTTPS needs a **Personal Access Token** instead of your password: Settings → Developer settings → Personal access tokens → Tokens (classic) → tick **repo** and **workflow** (pushing a workflow file needs the `workflow` scope).

### 4) Create your release key (once)
```bash
keytool -genkeypair -v -keystore ~/gopal-editor-release.jks -alias gopaleditor \
  -keyalg RSA -keysize 2048 -validity 10000
cp ~/gopal-editor-release.jks /sdcard/Download/      # keep a backup — losing it breaks updates
base64 -w 0 ~/gopal-editor-release.jks > ~/keystore_base64.txt
cat ~/keystore_base64.txt                            # copy the whole single line
```

### 5) Add the GitHub Secrets
Repo → **Settings → Secrets and variables → Actions → New repository secret**:
`KEYSTORE_BASE64` (the line above), `KEYSTORE_PASSWORD`, `KEY_ALIAS` = `gopaleditor`, `KEY_PASSWORD` (same as the keystore password unless you set a different one).

### 6) Push
```bash
cd ~/GopalEditor
git config --global user.name "Gopal"
git config --global user.email "you@example.com"
git config --global --add safe.directory '*'
git init
git add .
git commit -m "Gopal Editor MVP"
git branch -M main
git remote add origin https://github.com/USERNAME/GopalEditor.git
git push -u origin main        # username = GitHub user, password = the token
```
**Actions → Release APK** runs (~6–10 min): it builds, runs the unit tests, signs with v1+v2+v3, creates release `v1.0.N` with `gopal-editor.apk` + `version.json` (versionCode = run number + 1, changelog = your commit message).

### 7) Later changes
`git add . && git commit -m "what changed" && git push` → a new release → the app's **Updates** tab shows a red dot → **Update Now** downloads with a progress bar and opens the Android installer (allow "Install unknown apps" for Gopal Editor once).

The test APK Tab sent is signed with a different test key: uninstall it once before installing the first APK from your GitHub; every later update then installs over the top.

### Troubleshooting
- `Permission denied (gradlew)` in Actions: the workflow already runs `chmod +x gradlew`.
- `Resource not accessible by integration` when creating the release: repo → Settings → Actions → General → Workflow permissions → **Read and write**.
- `refusing to allow a Personal Access Token to create or update workflow`: your token is missing the **workflow** scope.
- Updates tab says "No release with version.json yet": the first Action hasn't finished, or the repo is private (the app reads public release assets).

### Build on a computer (optional)
```bash
./gradlew assembleRelease testDebugUnitTest lintRelease   # unsigned APK in app/build/outputs/apk/release/
./gradlew recordPaparazziDebug                            # UI renders in app/src/test/snapshots/images/
```
