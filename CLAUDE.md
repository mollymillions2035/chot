# Chot

Hardware DSP hotword bridge: intercepts "Hey Google" on a stock Pixel 8 and hands off to Claude Voice Mode.

## Known issues (ultrareview, 2026-10-20)

Issues 1–5 are fixed (2026-10-21). Issues 6–12 are not yet checked against the code or fixed. All rated normal severity.

### Intercepting things the user didn't ask for (fixed, not yet verified on a device)
The fixes rest on AOSP 14–17 source and public captures of the Google app, not on a test on the Pixel 8.
1. **Opening the Gemini app by hand gets intercepted.** Fixed. The overlay checks now match only the overlay: Gemini's `…robin.ui.floaty.activity.FloatyActivity` (by class, or its `assistant_robin_floaty_root_container` root view), or classic Assistant's `VoiceInteractionSession` window, which accessibility reports as window type -1 (never `TYPE_SYSTEM`). The full Gemini app is `…robin.main.MainActivity` in the Google app, which the old `com.google.android.apps.search.assistant` token and `:id/assistant_` view ids also matched; both are gone, along with the bard package token and the "Gemini" title match. The legacy classic-Assistant class tokens (`OpaActivity`, `ZeroState`, `VoicePlate` …) are still there, unverified; on current builds they match nothing or only classic-Assistant screens.
2. **Manual Google voice search gets intercepted.** Fixed. Window-state events from `NORMAL_SEARCH_CLASSES` are excluded before any overlay check. What actually caught voice search was the "Listening…" text heuristic, which content events and the mic path reached without that exclusion; it is gone, along with "Ask Gemini", which matched the Gemini app.
3. **Other on-device sound models count as "Hey Google".** Fixed in the APK and the daemon. Both key on `I/AlwaysOnHotwordDetector: onDetected`, which only the default assistant's hotword detector logs, so generic models such as Now Playing can't produce it. Quick Phrases might, if the Google app runs them through the same detector (unverified), so both treat the line as a hint and act only once the overlay is up. The old matches were dead on Android 14+ anyway: VIMS logs under `VoiceInteractionManager` and nothing at INFO, and SoundTrigger prints `RECOGNITION (`, not `onRecognition`.
4. **The logcat fast path presses BACK in the wrong app.** Fixed. A logcat hit (`onDetected`, or the `FloatyActivity` START line) only starts a 2s poll; BACK and the cooldown happen once the overlay is on screen, and an expired hint is logged. The daemon, whose old patterns never matched on Android 14+ and which this fix brought back to life, now waits for `mCurrentFocus` to be the overlay (`dumpsys window displays`) before it does anything.
5. **The pause switch doesn't stop the mic callback path.** Fixed. `triggerIntercept` checks `ChotPrefs.isEnabled`. The Test button (`manual = true`) still runs while paused and sends no BACK.

To verify on the Pixel 8: `adb logcat -v brief -s AlwaysOnHotwordDetector ActivityTaskManager` while saying "Hey Google" should show `onDetected` and a `FloatyActivity` START, and `adb shell uiautomator dump` of the overlay should contain `assistant_robin_floaty_root_container`. Opening Gemini by hand, the search-bar mic, Now Playing and a Quick Phrase should log no intercept.

### Setup and edge cases
6. **Locked phone drops the request.** `requestDismissKeyguard(this, null)` doesn't wait for the unlock, so Claude launches behind the keyguard and the auto-tap times out. Launch from `onDismissSucceeded`. `ChotTrampolineActivity.kt:36`.
7. **The logcat fast path may never work on Android 13+.** The watcher starts from `onServiceConnected` while Chot is in the background, so logd's per-session consent is denied without any error. The `logcatThread?.isAlive` guard then stops `MainActivity.onResume` from restarting it, yet the checklist still shows it as active. This depends on OS behaviour that hasn't been verified. `ChotAccessibilityService.kt:212`.
8. **The daemon taps without checking, at the wrong spot.**
   - `pixel8-instant-adb-daemon.sh:78-88` taps 0.75s after every trigger without checking that `am start` worked or that Claude has focus.
   - It taps at y=2195, but the measured button center is (955, 2234), per `ChotPrefs.kt:73`.
   - `MainActivity.kt:247` falls back to 88.5%/91.5% where the default is 88.4%/93.1%.
9. **Assistant settings don't match.** `pixel8-adb-setup.sh:66-69` sets `assistant` to `OpaActivity` and `voice_interaction_service` to `GsaVoiceInteractionService`. It never changes the ASSISTANT role, so a role sync can revert both settings. The suggested fix is `cmd role add-role-holder android.app.role.ASSISTANT com.google.android.googlequicksearchbox`.
   - **Observed on device:** long-pressing the power button currently opens a Claude voice chat successfully. The owner hasn't decided whether that's desirable, so confirm with them before "fixing" this.
10. **The daemon force-stops the Google app on every trigger.** `am force-stop com.google.android.googlequicksearchbox` tears down the VoiceInteractionService and hotword detector the bridge depends on, so the next "Hey Google" may not be detected. It also cancels Google's alarms and jobs. Drop it and rely on BACK, as the APK does. `pixel8-instant-adb-daemon.sh:72`.
11. **The daemon's ASSIST fallback never runs.** `tr -d " \r"` strips the spaces from "No activities found" before the `*"No activities"*` check, so that check can't match. Without a VOICE_ASSIST activity, the daemon opens Claude's main activity instead of the assistant overlay. Compare before stripping, or check for `/`. `pixel8-instant-adb-daemon.sh:28-29`.

### Found while fixing 1–5
12. **The 85ms follow-up BACK can double up.** `performGlobalAction(BACK)` on Pixel goes through SystemUI, so the first BACK may still be in flight at 85ms. The follow-up then sends a second one, which lands in the trampoline or the user's app. This is older than the fix for 1–5. Re-send only while the overlay window is still focused, after about 300ms, once timing has been measured on the device. `ChotAccessibilityService.kt` `triggerIntercept`.
