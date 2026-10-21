# Chot

Hardware DSP hotword bridge: intercepts "Hey Google" on a stock Pixel 8 and hands off to Claude Voice Mode.

## Known issues (ultrareview, 2026-10-20)

Not yet checked against the code or fixed. All rated normal severity.

### Intercepting things the user didn't ask for
1. **Opening the Gemini app by hand gets intercepted.** `ASSISTANT_OVERLAY_CLASSES` matches the whole `com.google.android.apps.bard` package, and `hasActiveVoiceInteractionWindow()` matches any window titled "Gemini". Only the voice overlay should match. `ChotAccessibilityService.kt:132`, `:581`.
2. **Manual Google voice search gets intercepted.** `hasActiveVoiceInteractionWindow()` (step 2) runs before the `NORMAL_SEARCH_CLASSES` exclusion (step 3), so the search-bar mic gets hijacked. `ChotAccessibilityService.kt:89`.
3. **Other on-device sound models count as "Hey Google".** Any `SoundTriggerMiddlewareLogging … onRecognition` line counts, including Now Playing and Quick Phrases. Filter on the hotword model or VIMS instead. The same match is in `scripts/pixel8-instant-adb-daemon.sh`. `ChotAccessibilityService.kt:277`.
4. **The logcat fast path presses BACK in the wrong app.** It sends BACK before the Assistant overlay exists, so the user's current app gets BACK. The 2.5s cooldown then stops the overlay from being dismissed. Send BACK only once the Assistant window is confirmed. `ChotAccessibilityService.kt:204`.
5. **The pause switch doesn't stop the mic callback path.** That path skips the `ChotPrefs.isEnabled` check. Move the check into `triggerIntercept`. `ChotAccessibilityService.kt:37-45`.

### Setup and edge cases
6. **Locked phone drops the request.** `requestDismissKeyguard(this, null)` doesn't wait for the unlock, so Claude launches behind the keyguard and the auto-tap times out. Launch from `onDismissSucceeded`. `ChotTrampolineActivity.kt:36`.
7. **The logcat fast path may never work on Android 13+.** The watcher starts from `onServiceConnected` while Chot is in the background, so logd's per-session consent is denied without any error. The `logcatThread?.isAlive` guard then stops `MainActivity.onResume` from restarting it, yet the checklist still shows it as active. This depends on OS behaviour that hasn't been verified. `ChotAccessibilityService.kt:247`.
8. **The daemon taps without checking, at the wrong spot.**
   - `pixel8-instant-adb-daemon.sh:56-66` taps 0.75s after every trigger without checking that `am start` worked or that Claude has focus.
   - It taps at y=2195, but the measured button center is (955, 2234), per `ChotPrefs.kt:73`.
   - `MainActivity.kt:247` falls back to 88.5%/91.5% where the default is 88.4%/93.1%.
9. **Assistant settings don't match.** `pixel8-adb-setup.sh:66-69` sets `assistant` to `OpaActivity` and `voice_interaction_service` to `GsaVoiceInteractionService`. It never changes the ASSISTANT role, so a role sync can revert both settings. The suggested fix is `cmd role add-role-holder android.app.role.ASSISTANT com.google.android.googlequicksearchbox`.
   - **Observed on device:** long-pressing the power button currently opens a Claude voice chat successfully. The owner hasn't decided whether that's desirable, so confirm with them before "fixing" this.
