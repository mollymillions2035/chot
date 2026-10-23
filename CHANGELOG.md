# Changelog

All notable changes to chot are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Numbers in parentheses are issue numbers from the known-issues list in `CLAUDE.md`.

## [Unreleased]

## [1.1.0] - 2026-10-23

### Added
- chot has an app icon: a lowercase c with a spark in its opening, on vermilion. On Android 13 and later it follows your themed-icon colors too.

### Changed
- The app is called just "chot" on the home screen and in the app list.
- `scripts/build-apk.sh` checks the system JDK (17 to 21) and Gradle (8.x) versions and falls back to its portable downloads in `.toolchain/` when they don't match, saying why.

### Removed
- The `KILL_BACKGROUND_PROCESSES` and `WAKE_LOCK` permissions. chot asked Android to kill Google's background processes after each intercept, which has done nothing since Android 14, and never held a wake lock.

### Fixed
- On a locked phone, a "Hey Google" you don't answer no longer keeps the screen on with the unlock prompt up. chot gives up 30s after the hotword if the phone is still locked (18).
- Unticking "Auto-tap Claude Voice Mode waveform button" no longer stops chot from opening Claude when the widget press fails: the checkbox now only turns off the taps (19).
- chot's timers no longer depend on the wall clock, so a time change after a reboot can't make the auto-tap give up at once or never (20).
- Saving the waveform coordinates with an empty or unreadable field keeps the current value instead of a hard-coded one 38px off the Voice button (21).
- With no default digital assistant set, chot's status now says "Hey Google" is off and where to choose Google, instead of showing READY (22).
- chot's accessibility service now receives screen events only from Google, Gemini and Claude, instead of inspecting every app's screen changes (23).
- The diagnostic dump of Claude's screen, taken when the Voice button isn't found, no longer records chat titles or message text in chot's log, only the labels of controls and the length of other text (24).

## [1.0.1] - 2026-10-22

### Fixed
- Saying "Stop" to a ringing timer no longer opens Claude. As soon as a timer rings, Google shows a "Say “Stop”" pill, and chot took it for "Hey Google": it asked you to unlock and then opened Claude. chot now ignores that pill (16).
- After Claude was force-stopped, every "Hey Google" opened voice mode the slower way, because chot's hidden copy of Claude's widget had lost its Voice chat button. chot now asks Claude to redraw it, so only the first "Hey Google" after a force-stop takes the slower way (17).

## [1.0.0] - 2026-10-21

### Added
- Voice mode through Claude's own home-screen widget. chot presses the Voice chat button on a hidden copy of the widget, so Claude starts voice mode by itself, about 0.75s after "Hey Google". It works with a draft in the text box, after a Claude Code session and from a cold start. Set it up once with **4. Claude Widget (Voice chat button)** in chot (14, 15).
- On a locked phone, chot asks you to unlock and goes into voice mode once you have. From screen off, it waits until the screen has been on for 0.8s, so the prompt offers your fingerprint (6).
- chot's log records each BACK with the window that had focus, when Claude's window appeared and voice mode started, and why a fallback tap was skipped.

### Changed
- Long-press power opens Claude in voice mode too, through the same Gemini overlay as "Hey Google". Google has to be the default assistant.
- chot sends one BACK per intercept. The old follow-up BACK could arrive late and close Claude (12).
- The auto-tap taps only buttons fully on screen, and retries while Claude's window is still sliding in (13).
- The coordinate fallback never taps Send, Stop, the text box or the keyboard, so it can't send a draft (15).
- If the widget press doesn't bring Claude up within 1s, chot launches Claude the old way; if voice mode hasn't started 0.7s after Claude appears, it taps the Voice button.
- The setup script checks that Google is the default assistant instead of rewriting the assistant settings (9).
- chot's log keeps the last 100 lines instead of 40.

### Removed
- The logcat fast path and the `READ_LOGS` permission. Since Android 13 an app has to ask for consent for every logcat session, so it never worked (3, 4, 7).
- The adb shell daemon, `scripts/pixel8-instant-adb-daemon.sh`, and with it its bugs: a blind tap that could send a draft, force-stopping the Google app, and a dead fallback (8, 10, 11).

### Fixed
- Opening the Gemini app by hand no longer gets intercepted (1).
- The search-bar mic's voice search no longer gets intercepted (2).
- Other on-device sound models, such as Now Playing, no longer count as "Hey Google" (3).
- The pause switch stops every path, including the microphone callback (5).
- "Hey Google" on a locked phone no longer gets lost after the unlock (6).
- Claude no longer reopens a Claude Code session instead of entering voice mode (14).
- A draft in Claude's text box no longer keeps voice mode from starting (15).

## [0.1.0] - 2026-10-21

### Added
- First release: a hardware DSP hotword bridge that hands "Hey Google" off to Claude voice mode on a stock Pixel 8. The APK is debug-signed; set up the phone with `scripts/pixel8-adb-setup.sh`.

[Unreleased]: https://github.com/mollymillions2035/chot/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/mollymillions2035/chot/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/mollymillions2035/chot/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/mollymillions2035/chot/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/mollymillions2035/chot/releases/tag/v0.1.0
