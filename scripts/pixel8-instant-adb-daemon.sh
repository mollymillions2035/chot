#!/usr/bin/env bash
# Zero-compile ADB / Shizuku shell daemon for Pixel 8:
# Watches the Tensor G3 hardware DSP hotword trigger in logcat, immediately kills
# Google's VoiceInteractionSession so the mic is freed, and launches Claude into Voice Mode.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if ! command -v adb >/dev/null 2>&1; then
    if [ -x "$PROJECT_DIR/.toolchain/android-sdk/platform-tools/adb" ]; then
        export PATH="$PROJECT_DIR/.toolchain/android-sdk/platform-tools:$PATH"
    elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
    fi
fi

echo "==> Starting Pixel 8 Hardware DSP 'Hey Google' -> Claude Voice Mode Interceptor..."
adb wait-for-device

adb shell '
CLAUDE_PKG="com.anthropic.claude"
GOOGLE_PKG="com.google.android.googlequicksearchbox"
# Focused window while the "Hey Google" overlay is up: Gemini FloatyActivity, or classic Assistant VoiceInteractionSession
OVERLAY_FOCUS_RE="mCurrentFocus=Window\{[^ ]+ u[0-9]+ ($GOOGLE_PKG/com\.google\.android\.apps\.search\.assistant\.surfaces\.voice\.robin\.ui\.floaty\.activity\.FloatyActivity|VoiceInteractionSession)\}"

# Discover Claude ASSIST or Main activity dynamically on device
ASSIST_COMP=$(cmd package query-activities --brief -a android.intent.action.VOICE_ASSIST "$CLAUDE_PKG" 2>/dev/null | tail -n 1 | tr -d " \r")
if [ -z "$ASSIST_COMP" ] || [[ "$ASSIST_COMP" == *"No activities"* ]]; then
    ASSIST_COMP=$(cmd package query-activities --brief -a android.intent.action.ASSIST "$CLAUDE_PKG" 2>/dev/null | tail -n 1 | tr -d " \r")
fi
MAIN_COMP=$(cmd package resolve-activity --brief "$CLAUDE_PKG" 2>/dev/null | tail -n 1 | tr -d " \r")

echo "[chot-daemon] Discovered Claude ASSIST component: ${ASSIST_COMP:-none}"
echo "[chot-daemon] Discovered Claude MAIN component:   ${MAIN_COMP:-none}"
echo "[chot-daemon] Listening for Pixel 8 Tensor G3 DSP hotword events..."

LAST_TRIGGER=0
logcat -c
# "onDetected" comes from the hotword detector of the default assistant itself, so generic sound models such as
# Now Playing never produce it, unlike SoundTriggerMiddlewareLogging recognitions. It is still only a hint (Quick
# Phrases may share the detector), so nothing happens unless the overlay shows up.
logcat -v brief AlwaysOnHotwordDetector:I ActivityTaskManager:I *:S | while IFS= read -r line; do
    case "$line" in
        "I/AlwaysOnHotwordDetector("*"): onDetected"|*"VoicePlateActivity"*|*"RobinVoicePlateActivity"*)
            NOW=$(date +%s)
            if [ $((NOW - LAST_TRIGGER)) -lt 3 ]; then
                continue
            fi
            LAST_TRIGGER=$NOW

            # The hint is logged before the overlay exists, and BACK right away would land in the app the user
            # is in. Wait up to a couple of seconds for the overlay to have focus.
            OVERLAY_UP=0
            i=0
            while [ $i -lt 15 ]; do
                if dumpsys window displays 2>/dev/null | grep -qE "$OVERLAY_FOCUS_RE"; then
                    OVERLAY_UP=1
                    break
                fi
                sleep 0.1
                i=$((i + 1))
            done
            if [ "$OVERLAY_UP" != 1 ]; then
                echo "[chot-daemon] Hotword hint, but no Assistant overlay took focus. Ignoring."
                continue
            fi
            echo "[chot-daemon] Hotword detected! Dismissing Google Assistant & freeing mic..."

            # 1. Dismiss Google Assistant overlay & force-release microphone
            input keyevent KEYCODE_BACK
            am force-stop "$GOOGLE_PKG" >/dev/null 2>&1 || true

            # 2. Wake screen if asleep
            input keyevent KEYCODE_WAKEUP

            # 3. Launch Claude Assistant / Voice Mode
            if [ -n "$ASSIST_COMP" ] && [[ "$ASSIST_COMP" == *"/"* ]]; then
                am start -a android.intent.action.VOICE_ASSIST -n "$ASSIST_COMP" --ez voice_mode true -f 0x14000000 >/dev/null 2>&1
            else
                am start -n "$MAIN_COMP" --ez voice_mode true -f 0x14000000 >/dev/null 2>&1
            fi

            # 4. Tap the Voice Mode waveform button on Pixel 8 (1080x2400 -> x=955, y=2195) after UI settles
            (
                sleep 0.75
                input tap 955 2195
            ) &
            ;;
    esac
done
'
