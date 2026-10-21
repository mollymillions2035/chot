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
logcat -v brief VoiceInteractionManagerService:I SoundTriggerMiddlewareLogging:I HotwordDetectionService:I ActivityTaskManager:I *:S | while IFS= read -r line; do
    case "$line" in
        *"VoiceInteractionManagerService"*"showSession"*|*"SoundTriggerMiddlewareLogging"*"onRecognition"*|*"VoicePlateActivity"*|*"RobinVoicePlateActivity"*)
            NOW=$(date +%s)
            if [ $((NOW - LAST_TRIGGER)) -lt 3 ]; then
                continue
            fi
            LAST_TRIGGER=$NOW
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
