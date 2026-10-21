# Chot (`dev.chot.pixel8`) — Hardware DSP Hotword Bridge for Claude on Stock Pixel 8

Makes **"Hey Google"** launch **Claude Voice Mode / Assistant Overlay** (`com.anthropic.claude`) hands-free on an **unrooted, stock Pixel 8** while retaining the Tensor G3's zero-battery hardware audio DSP.

---

## Why Stock Pixel 8 Needs This Architecture

On stock Android (Pixel 8 `user` builds):
1. Low-power hardware DSP hotword detection (`AlwaysOnHotwordDetector` / `SoundTrigger`) requires `CAPTURE_AUDIO_HOTWORD` and `MANAGE_HOTWORD_DETECTION`, which are `signature|privileged` permissions locked to system-signed Google packages (`com.google.android.as` + `com.google.android.googlequicksearchbox`).
2. If you change Android's **Default Digital Assistant** setting from Google to Claude, `VoiceInteractionManagerService` unbinds Google's `HotwordDetectionService` and **turns off the Tensor G3 hardware DSP**.
3. If you keep Google as the Default Digital Assistant so the hardware DSP stays active, saying `"Hey Google"` opens Google's `VoiceInteractionSession` overlay (`TYPE_VOICE_INTERACTION`), which locks the microphone (`AudioRecord`) and prevents Claude from recording your voice.

### How `Chot` Solves All Three Problems

1. **Zero-Battery Tensor G3 DSP Remains Active:** Google stays registered as the system `voice_interaction_service` so hardware `"Hey Google"` works everywhere (including lock screen / AOD).
2. **Triple-Sensor Hotword Interception (Zero False Positives on Normal Google Search):**
   - **Sensor 1 — Hardware DSP Logcat Fast-Path (when `READ_LOGS` is granted via ADB):** Tails filtered system logcat (`AlwaysOnHotwordDetector`, `ActivityTaskManager`) for the default assistant's own hotword detection (`onDetected`, which generic sound models such as Now Playing can't log) and the start of Gemini's overlay activity. Both are logged before the Assistant UI finishes inflating, so a hit only starts a short poll; Chot intercepts once the overlay is actually on screen.
   - **Sensor 2 — Assistant/Gemini Overlay Window Inspector (Works 100% standalone without ADB):** Matches only the voice overlay itself: Gemini's `FloatyActivity` (by class, or by its `assistant_robin_floaty_root_container` root view) and classic Assistant's `VoiceInteractionSession` window (`TYPE_VOICE_INTERACTION`, which accessibility reports as window type `-1`). Normal Google Search (`SearchActivity`, `GoogleAppActivity`, including the search-bar mic's voice search) and the full Gemini app (`robin.main.MainActivity`) never match: window-state events from normal Search are excluded up front, and the overlay check itself uses no text or generic view-id heuristics.
   - **Sensor 3 — `AudioManager.AudioRecordingCallback`:** Monitors live microphone recording states in real time.
3. **Deterministic Microphone Handover (`ChotTrampolineActivity`):**
   - Sends `GLOBAL_ACTION_BACK` to dismiss Google's Assistant / Gemini overlay (only once it is confirmed on screen, not as soon as a logcat line hints at it).
   - Requests `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` to force Google's `SpeechRecognizer` / `AudioRecord` to immediately abort.
   - Polls `AudioManager.getActiveRecordingConfigurations()` (every 20ms, up to 340ms) until the microphone is verified free, releases transient audio focus, wakes/unlocks the screen if triggered from AOD/lock screen, and launches Claude.
4. **Cascading Claude Voice Mode Launch + Auto-Tap:**
   - Queries `PackageManager` for `com.anthropic.claude`'s exported `ACTION_VOICE_ASSIST` and `ACTION_ASSIST` activities (`AssistantOverlayActivity` / `MainActivity`) and launches them directly into a new chat.
   - Actively polls `com.anthropic.claude`'s Jetpack Compose accessibility semantics tree (`depth <= 40`, every `120ms`) and clicks the **Voice Mode** (`contentDescription = "Voice Mode"` / `text = "Talk"`) button at `[943,2222][967,2247]` (`88.4% X, 93.1% Y` on Pixel 8 `1080x2400`).

---

## Quick Start (One-Command Build + Auto-Setup over ADB)

1. Make sure the official **Claude** app (`com.anthropic.claude`) is installed on your Pixel 8 and **USB Debugging** (or Wireless Debugging) is enabled.
2. Build the signed APK (automatically downloads portable JDK 17, Android SDK 34, and Gradle 8.7 into `.toolchain/` if not installed on your machine):
   ```bash
   ./scripts/build-apk.sh
   ```
3. Plug in your Pixel 8 and run the automated setup script (installs `chot-pixel8.apk`, grants `READ_LOGS`, `WRITE_SECURE_SETTINGS`, and `SYSTEM_ALERT_WINDOW`, ensures Google's hardware DSP hotword service is active, and enables `ChotAccessibilityService` automatically):
   ```bash
   ./scripts/pixel8-adb-setup.sh
   ```
4. Say **"Hey Google"** to your Pixel 8 — Google Assistant is instantly dismissed, the mic is handed over, and Claude opens directly into Voice Mode!

---

## Alternative: Instant Zero-Compile ADB / Shizuku Shell Daemon

Want to test the hardware DSP hijack on your connected Pixel 8 immediately without building/installing an APK? Run:

```bash
./scripts/pixel8-instant-adb-daemon.sh
```
