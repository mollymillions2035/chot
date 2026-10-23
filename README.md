# chot (`dev.chot.pixel8`) — Hardware DSP Hotword Bridge for Claude on Stock Pixel 8

<p align="center">
  <i>Very silly and almost definitely very insecure</i>
  <br>
  <b>BUT</b>
  <br>
  <img alt="EXTREMELY BATTERY EFFICIENT" src="https://img.shields.io/badge/EXTREMELY-BATTERY_EFFICIENT-2ea44f?style=for-the-badge">
  <br>
  <i>and more importantly</i>
  <br>
  <img alt="ROOTLESS" src="https://img.shields.io/badge/ROOTLESS-8957e5?style=for-the-badge">
  <br>
  <i>implementation of hotword activation of Claude in voice conversation mode on a Pixel 8.</i>
</p>

<p align="center">
  <img alt="DOES NOT REQUIRE MODIFYING CLAUDE'S APP IN ANY WAY" src="https://img.shields.io/badge/DOES_NOT_REQUIRE-MODIFYING_CLAUDE%27S_APP_IN_ANY_WAY-D97757?style=for-the-badge">
</p>

<p align="center"><sub>Likely to not work on any phone but mine, but feel free to try.</sub></p>

Makes **"Hey Google"** launch **Claude Voice Mode / Assistant Overlay** (`com.anthropic.claude`) hands-free on an **unrooted, stock Pixel 8** while retaining the Tensor G3's zero-battery hardware audio DSP.

---

## Why Stock Pixel 8 Needs This Architecture

On stock Android (Pixel 8 `user` builds):
1. Low-power hardware DSP hotword detection (`AlwaysOnHotwordDetector` / `SoundTrigger`) requires `CAPTURE_AUDIO_HOTWORD` and `MANAGE_HOTWORD_DETECTION`, which are `signature|privileged` permissions locked to system-signed Google packages (`com.google.android.as` + `com.google.android.googlequicksearchbox`).
2. If you change Android's **Default Digital Assistant** setting from Google to Claude, `VoiceInteractionManagerService` unbinds Google's `HotwordDetectionService` and **turns off the Tensor G3 hardware DSP**.
3. If you keep Google as the Default Digital Assistant so the hardware DSP stays active, saying `"Hey Google"` opens Google's `VoiceInteractionSession` overlay (`TYPE_VOICE_INTERACTION`), which locks the microphone (`AudioRecord`) and prevents Claude from recording your voice.

### How `chot` Solves All Three Problems

1. **Zero-Battery Tensor G3 DSP Remains Active:** Google stays registered as the system `voice_interaction_service` so hardware `"Hey Google"` works everywhere (including lock screen / AOD).
2. **Dual-Sensor Hotword Interception (Zero False Positives on Normal Google Search):**
   - **Sensor 1 — Assistant/Gemini Overlay Window Inspector (Works 100% standalone without ADB):** Matches only the voice overlay itself: Gemini's `FloatyActivity` (by class, or by its `assistant_robin_floaty_root_container` root view) and classic Assistant's `VoiceInteractionSession` window (`TYPE_VOICE_INTERACTION`, which accessibility reports as window type `-1`). Normal Google Search (`SearchActivity`, `GoogleAppActivity`, including the search-bar mic's voice search) and the full Gemini app (`robin.main.MainActivity`) never match: window-state events from normal Search are excluded up front, and the overlay check itself uses no text or generic view-id heuristics.
   - **Sensor 2 — `AudioManager.AudioRecordingCallback`:** Monitors live microphone recording states in real time.
   - **No logcat sensor in the app:** since Android 13, an app reading other apps' logs needs the user's consent for every logcat session, and Android only asks while the app is on screen. A logcat hint also can't act before the overlay is on screen, which Sensor 1 already catches.
3. **Deterministic Microphone Handover (`ChotTrampolineActivity`):**
   - Sends `GLOBAL_ACTION_BACK` to dismiss Google's Assistant / Gemini overlay (only once it is confirmed on screen).
   - Requests `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` to force Google's `SpeechRecognizer` / `AudioRecord` to immediately abort.
   - Polls `AudioManager.getActiveRecordingConfigurations()` (every 20ms, up to 340ms) until the microphone is verified free, releases transient audio focus, wakes/unlocks the screen if triggered from AOD/lock screen, and launches Claude.
4. **Cascading Claude Voice Mode Launch + Auto-Tap:**
   - **Claude's widget first:** chot binds its own, never-shown copy of Claude's home-screen widget and presses its **Voice chat** button. That fires Claude's own launch into voice mode, which works whatever screen Claude is on, including with a draft in the text box or a Claude Code session open, and doesn't depend on reading Claude's screen. chot checks that voice mode started, and falls back to the steps below if the widget isn't set up or Claude doesn't open.
   - Queries `PackageManager` for `com.anthropic.claude`'s exported `ACTION_VOICE_ASSIST` and `ACTION_ASSIST` activities (`AssistantOverlayActivity` / `MainActivity`) and launches them directly into a new chat.
   - Actively polls `com.anthropic.claude`'s Jetpack Compose accessibility semantics tree (`depth <= 40`, every `120ms`) and clicks the **Voice Mode** (`contentDescription = "Voice Mode"` / `text = "Talk"`) button at `[943,2222][967,2247]` (`88.4% X, 93.1% Y` on Pixel 8 `1080x2400`).

---

## Quick Start (One-Command Build + Auto-Setup over ADB)

1. Make sure the official **Claude** app (`com.anthropic.claude`) is installed on your Pixel 8 and **USB Debugging** (or Wireless Debugging) is enabled.
   Google (Gemini) must be your **default digital assistant** (Settings → Apps → Default apps → Digital assistant app). chot needs Google's hotword detection running, and it doesn't change this setting for you.
2. Build the signed APK (automatically downloads portable JDK 17, Android SDK 34, and Gradle 8.7 into `.toolchain/` if not installed on your machine):
   ```bash
   ./scripts/build-apk.sh
   ```
3. Plug in your Pixel 8 and run the automated setup script (installs `chot-pixel8.apk`, grants `SYSTEM_ALERT_WINDOW` and chot's notification permission, checks that Google is still the default assistant, and enables `ChotAccessibilityService` over adb). Without the script, install the APK with `adb install -r` and tap **1. Enable Accessibility** and **5. Notifications** in chot:
   ```bash
   ./scripts/pixel8-adb-setup.sh
   ```
4. Open chot on the phone, tap **4. Claude Widget (Voice chat button)** and allow chot to create widgets. This one-time step lets chot start voice mode through Claude's own widget. Your home screen doesn't change.
5. Say **"Hey Google"** to your Pixel 8 — Google Assistant is instantly dismissed, the mic is handed over, and Claude opens directly into Voice Mode!
