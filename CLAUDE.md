# Chot

Hardware DSP hotword bridge: intercepts "Hey Google" on a stock Pixel 8 and hands off to Claude Voice Mode. Pronounced "c-hot".

## Known issues (ultrareview, 2026-10-20)

Open: 6, 8 and 10–14, all rated normal severity. 13 has already cost one voice-mode switch on the phone. Fixed issues keep their numbers and are listed separately below.

### Setup and edge cases
- **6. Locked phone drops the request.** `requestDismissKeyguard(this, null)` doesn't wait for the unlock, so Claude launches behind the keyguard and the auto-tap times out. Launch from `onDismissSucceeded`. `ChotTrampolineActivity.kt:36`.
  - **Seen on the Pixel 8 (2026-10-21):** "Hey Google" on the lock screen shows the unlock prompt, but after unlocking nothing happens.
- **8. The daemon taps without checking, at a hardcoded spot.**
  - `pixel8-instant-adb-daemon.sh:78-88` taps 0.75s after every trigger without checking that `am start` worked or that Claude has focus.
  - It taps (955, 2195), not the APK's default (955, 2234) from `ChotPrefs.kt:73`. On the Pixel 8 the settled Voice button was at [907,2164][1033,2290] and [932,2164][1058,2290], so both points currently land on it. The risk is the button moving (its x shifted 25px between intercepts), not the y value.
  - `MainActivity.kt:245-246` falls back to 88.5%/91.5% where the default is 88.4%/93.1%.
- **10. The daemon force-stops the Google app on every trigger.** `am force-stop com.google.android.googlequicksearchbox` tears down the VoiceInteractionService and hotword detector the bridge depends on, so the next "Hey Google" may not be detected. It also cancels Google's alarms and jobs. Drop it and rely on BACK, as the APK does. `pixel8-instant-adb-daemon.sh:72`.
- **11. The daemon's ASSIST fallback never runs.** `tr -d " \r"` strips the spaces from "No activities found" before the `*"No activities"*` check, so that check can't match. Without a VOICE_ASSIST activity, the daemon opens Claude's main activity instead of the assistant overlay. Compare before stripping, or check for `/`. `pixel8-instant-adb-daemon.sh:28-29`.

### Found while fixing 1–5
- **12. The 85ms follow-up BACK can double up.** `performGlobalAction(BACK)` on Pixel goes through SystemUI, so the first BACK may still be in flight at 85ms. The follow-up then sends a second one, which lands in the trampoline or the user's app. This is older than the fix for 1–5. Re-send only while the overlay window is still focused, after about 300ms, once timing has been measured on the device. `ChotAccessibilityService.kt` `triggerIntercept`.
- **13. Auto-tap can tap where the Voice button used to be, off screen.** Chot can find the Voice button while Claude's window is still sliding in from the right, so its bounds lie partly or wholly off the 1080-wide screen. ACTION_CLICK usually works anyway, but neither fallback tap would:
  - The 380ms follow-up taps the bounds captured before, even though it has just looked the button up again.
  - If ACTION_CLICK fails, Chot taps those bounds right away, with auto-tap already disarmed, so off-screen bounds mean no retry and no 1.6s coordinate fallback.
  - Either way, Claude opens without entering voice mode. Tap the freshly found button's current bounds, never tap bounds outside the screen, and stay armed so the 120ms poll retries. `ChotAccessibilityService.kt:267`, `:285`.
  - **Seen on the Pixel 8 (2026-10-21):** the settled button's left edge is at x=907–932. At tap time, Chot logged a left edge of:
    - 1573 once in the first test.
    - 954, 1109 and 1137 in the re-test when Claude started a fresh `AppOnlyMainActivity` (`START … result=0`).
    - 1999, 2030 and 2030 each time Claude's existing task was brought to the front instead (`result=2`). That is about one screen width right of the settled spot.
  - In one of the `result=2` cases ACTION_CLICK failed (`action=false, gesture=true`), the gesture tap went off screen, and Claude never entered voice mode. The owner then found the launcher's app-drawer search open with "sa" typed; nothing in the logs explains that.
- **14. Claude stays on its last screen when that was a Claude Code session.** Seen on the Pixel 8 (2026-10-21): if the Claude app last showed a Claude Code session, "Hey Google" opens Claude straight back into that session, and nothing switches to voice mode.
  - The launch goes `VOICE_ASSIST` → `AssistantOverlayActivity` → `AppOnlyMainActivity`, which restores the last screen.
  - That screen has no Voice button, so the auto-tap finds nothing. After 1.6s the coordinate fallback taps (88.4%, 93.1%) anyway, on whatever sits there in Claude Code; check what that hits.
  - Needs a launch that always lands on a chat, or a fallback that only taps when Claude's chat screen is showing. `ClaudeLauncher.kt:84`, `ChotAccessibilityService.kt:311`.

### Fixed
Issues 1–5 (intercepting things the user didn't ask for), 7 and 9 were fixed on 2026-10-21. On the Pixel 8 the fixes for 1, 2, 5 and 7 held; 9's new setup step hasn't been run on the phone yet. The fix for 7 removed the APK's logcat fast path, so 3 and 4 now only concern the daemon, which wasn't run on the phone, though the capture confirmed the log lines it keys on.

- **1. Opening the Gemini app by hand gets intercepted.** Fixed. The overlay checks now match only the overlay: Gemini's `…robin.ui.floaty.activity.FloatyActivity` (by class, or its `assistant_robin_floaty_root_container` root view), or classic Assistant's `VoiceInteractionSession` window, which accessibility reports as window type -1 (never `TYPE_SYSTEM`). The full Gemini app is `…robin.main.MainActivity` in the Google app, which the old `com.google.android.apps.search.assistant` token and `:id/assistant_` view ids also matched; both are gone, along with the bard package token and the "Gemini" title match. The older class-name tokens (`VoicePlate`, `RobinVoicePlate`, `RobinActivity`, `AssistantActivity`, `OpaActivity`, `ZeroState`, `LockscreenEntry`) are still there. Not all of them are classic-Assistant names: "Robin" is Gemini's codename. On the Pixel 8 none of them matched any Google activity seen. Anything else they might match is unverified.
- **2. Manual Google voice search gets intercepted.** Fixed. Window-state events from `NORMAL_SEARCH_CLASSES` are excluded before any overlay check. What actually caught voice search was the "Listening…" text heuristic, which content events and the mic path reached without that exclusion; it is gone, along with "Ask Gemini", which matched the Gemini app.
- **3. Other on-device sound models count as "Hey Google".** Fixed. The APK no longer reads logcat (see 7). The daemon keys on `I/AlwaysOnHotwordDetector: onDetected`, which only the default assistant's hotword detector logs, so generic models such as Now Playing can't produce it. Quick Phrases might, if the Google app runs them through the same detector (unverified), so the daemon treats the line as a hint and acts only once the overlay is up. The old matches were dead on Android 14+ anyway: VIMS logs under `VoiceInteractionManager` and nothing at INFO, and SoundTrigger prints `RECOGNITION (`, not `onRecognition`.
- **4. The logcat fast path presses BACK in the wrong app.** Fixed. The APK's fast path first became a hint that polled for the overlay, then was removed (see 7). The daemon, whose old patterns never matched on Android 14+ (none of them appear in the Pixel 8's log) and which this fix brought back to life, now waits for `mCurrentFocus` to be the overlay (`dumpsys window displays`) before it presses BACK or launches Claude. It starts its 3s cooldown on the hint itself (`pixel8-instant-adb-daemon.sh:46-50`). So a hint that never brings up the overlay makes it ignore further hints for 3s.
- **5. The pause switch doesn't stop the mic callback path.** Fixed. `triggerIntercept` checks `ChotPrefs.isEnabled`. The Test button (`manual = true`) still runs while paused and sends no BACK.
- **7. The logcat fast path never worked on Android 13+.** Fixed by removing it from the APK, along with `READ_LOGS`.
  - Since Android 13, an app reading other apps' logs needs consent for each logcat process. logd asks only while the app is the top activity; otherwise the reader silently sees only the app's own logs (`LogcatManagerService`, AOSP 14–16). `adb shell` is exempt, so the daemon is unaffected.
  - The watcher started from `onServiceConnected`, and after a refusal its `logcatThread?.isAlive` guard kept it running blind while the checklist said it was active.
  - Making it work would have meant an Allow tap after every reboot, app update or accessibility rebind. The gain was at most one poll interval: since the fix for 4, a hint only polled for the overlay that the accessibility path already reacts to.
  - **Seen on the Pixel 8 (Android 17):** the setup script opens Chot, so the consent dialog (`LogAccessDialogActivity`) did appear right after install. It was destroyed when the screen went to sleep, apparently unanswered. Chot then logged no `DSP-Logcat` intercept or expired hint for the 3 hotwords said while it was on.
  - **Re-checked on the Pixel 8 (`a568a5a`):** `READ_LOGS` is gone from `dumpsys package`, no `LogAccessDialogActivity` appeared after install, and the status screen has no logcat row.
- **9. Assistant settings don't match.** Fixed by no longer changing them. When Google wasn't the default assistant, step 4 of `pixel8-adb-setup.sh` wrote `voice_interaction_service` and `assistant` itself. It set `assistant` to `OpaActivity`, where Android writes `GsaVoiceInteractionService`, and it left the ASSISTANT role alone, so a role sync could revert both. The step now only checks and warns, like Chot's status screen, and the README lists Google (Gemini) as the default digital assistant as a setup requirement.
  - **Seen on the Pixel 8 (2026-10-21):** `assistant` is set to `…/com.google.android.voiceinteraction.GsaVoiceInteractionService`, the same component as `voice_interaction_service`.
  - **Owner's decision (2026-10-21):** long-pressing the power button should open a Claude voice chat, as it does now. Don't "fix" that. It most likely works because Google is the default assistant: the long press opens Gemini's overlay, which Chot intercepts as it does for "Hey Google". No capture has confirmed that yet.

Checked on the owner's Pixel 8 (Android 17, Google app 17.66.20) on 2026-10-21:
- "Hey Google" from Settings opened Claude in voice mode 3 times out of 3, with no stray BACK (Settings stayed on the same screen).
- Not intercepted: opening Gemini by hand, the search-bar mic, Now Playing firing, and "Hey Google" while paused.
- Every intercept came through the accessibility path. The logcat fast path (see 7) and the daemon were not exercised.
- Re-checked after the fix for 7 (`a568a5a`): Chot caught 6 "Hey Google"s out of 6, all through the accessibility path (`AssistantActivity (FloatyActivity)` or `AssistantOverlayWindow`). Of the 3 tries watched closely, 2 entered voice mode and left Settings where it was; the third didn't enter voice mode (see 13).

## What the Pixel 8 does (Android 17, Google app 17.66.20)
For future matching work:
- **"Hey Google":** logs `I/SoundTriggerMiddlewareLogging: RECOGNITION (<handle>, PhraseRecognitionEventSys{… type: 0 … PhraseRecognitionExtra{id: 101 …`, then `I/AlwaysOnHotwordDetector: onDetected`, then `D/VoiceInteractionManager: Self-trigger check: VIS triggering from recent hotword`.
  - It then starts `FloatyActivity` (LAUNCH_SINGLE_TASK) within about a second. While it is up, `mCurrentFocus=Window{… u0 com.google.android.googlequicksearchbox/…robin.ui.floaty.activity.FloatyActivity}`. Most of its view ids are `assistant_robin_floaty_*`, including `assistant_robin_floaty_root_container`. Some are plain `assistant_robin_*`, and the Gemini app shares a few of those, so only the `floaty` ids identify the overlay.
- **Now Playing:** logs `FORCE_RECOGNITION (<handle>)`, then `RECOGNITION (<handle>, RecognitionEventSys{… type: 1 … data: [112, 111, 115, 95, 109, 117, 115, 105, 99, …]`. Those bytes are ASCII for `pos_music,val:…`; the text itself never appears in logcat.
- **Gemini opened by hand:** bard `BardEntryPointActivity`, then GSA `MainAssistantDeeplinkAnimated`, then `…robin.main.MainActivity`, with `assistant_robin_*` ids and no `floaty` ids.
- **Search-bar mic:** GSA `.InternalGoogleAppActivityEntrypoint`, with `googleapp_voice_search_*` ids.
- **Testing tips:**
  - `adb shell uiautomator dump` rebinds accessibility services, so Chot logs "connected & armed" after each dump.
  - Chot's own log (intercepts, auto-taps) is in its prefs: `adb shell run-as dev.chot.pixel8 cat shared_prefs/chot_pixel8_prefs.xml`. `run-as` works because `build-apk.sh` builds the debug APK.
  - `adb logcat -v time ActivityTaskManager:I AlwaysOnHotwordDetector:I *:S` shows each hotword, the overlay, the trampoline and Claude's activities with timestamps.
  - On the owner's machine adb isn't on PATH; use `.toolchain/android-sdk/platform-tools/adb` in the clone.
