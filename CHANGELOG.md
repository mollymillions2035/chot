# Changelog

All notable changes to chot are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Numbers in parentheses are issue numbers from the known-issues list in `CLAUDE.md`.

## [Unreleased]

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

[Unreleased]: https://github.com/mollymillions2035/chot/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/mollymillions2035/chot/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/mollymillions2035/chot/releases/tag/v0.1.0
