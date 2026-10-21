#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="${1:-$PROJECT_DIR/chot-pixel8.apk}"

if ! command -v adb >/dev/null 2>&1; then
    if [ -x "$PROJECT_DIR/.toolchain/android-sdk/platform-tools/adb" ]; then
        export PATH="$PROJECT_DIR/.toolchain/android-sdk/platform-tools:$PATH"
    elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
    else
        echo "Error: 'adb' not found in PATH." >&2
        exit 1
    fi
fi

if ! mkdir -p "$HOME/.android" 2>/dev/null; then
    mkdir -p "$PROJECT_DIR/.toolchain/android-home/.android"
    export HOME="$PROJECT_DIR/.toolchain/android-home"
fi

echo "==> Checking for connected Pixel 8 via ADB (waiting up to 30s — tap 'Allow USB debugging' on phone if prompted)..."
adb start-server >/dev/null 2>&1 </dev/null || true
if ! timeout 30s adb wait-for-device </dev/null; then
    echo "Error: No authorized ADB device detected after 30s." >&2
    echo "Current ADB devices:" >&2
    adb devices -l >&2 || true
    echo "Make sure your Pixel 8 screen is unlocked and you tapped 'Allow' on the 'Allow USB debugging?' prompt." >&2
    exit 1
fi
DEVICE_MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
echo "    Connected device: $DEVICE_MODEL"

if [ ! -f "$APK_PATH" ]; then
    ALT_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$ALT_APK" ]; then
        APK_PATH="$ALT_APK"
    else
        echo "Error: APK not found at $APK_PATH. Run ./scripts/build-apk.sh first." >&2
        exit 1
    fi
fi

echo "==> 1/6 Installing chot APK ($APK_PATH)..."
adb install -r "$APK_PATH"

PKG="dev.chot.pixel8"
SVC="$PKG/$PKG.ChotAccessibilityService"

echo "==> 2/6 Granting privileged ADB permissions (WRITE_SECURE_SETTINGS, SYSTEM_ALERT_WINDOW, Claude RECORD_AUDIO)..."
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb shell pm grant com.anthropic.claude android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant com.anthropic.claude android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "==> 3/6 Whitelisting $PKG from Doze / battery restrictions..."
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
adb shell cmd appops set "$PKG" RUN_IN_BACKGROUND allow || true
adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true

# Only checks: the default assistant is the user's choice, so the script never changes it
echo "==> 4/6 Checking that Google is the Default Digital Assistant so Pixel 8 Tensor G3 'Hey Google' DSP stays active..."
CURRENT_VIS="$(adb shell settings get secure voice_interaction_service | tr -d '\r')"
if [[ "$CURRENT_VIS" == *"googlequicksearchbox"* ]]; then
    echo "    Google is the default assistant ($CURRENT_VIS)."
else
    echo "    WARNING: Google is not the default assistant (voice_interaction_service: ${CURRENT_VIS:-none})."
    echo "    \"Hey Google\" only reaches chot while Google (Gemini) is the default digital assistant. Set it on the phone:"
    echo "    Settings > Apps > Default apps > Digital assistant app > Google (Gemini)"
fi

echo "==> 5/6 Enabling ChotAccessibilityService automatically..."
CURRENT_ACC="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [ "$CURRENT_ACC" = "null" ] || [ -z "$CURRENT_ACC" ]; then
    NEW_ACC="$SVC"
elif [[ "$CURRENT_ACC" != *"$SVC"* ]]; then
    NEW_ACC="${CURRENT_ACC}:${SVC}"
else
    NEW_ACC="$CURRENT_ACC"
fi
adb shell settings put secure enabled_accessibility_services "$NEW_ACC"
adb shell settings put secure accessibility_enabled 1

echo "==> 6/6 Launching chot status dashboard on Pixel 8..."
adb shell am start -n "$PKG/$PKG.MainActivity"

echo ""
echo "=================================================================="
echo " SUCCESS! chot is active on your $DEVICE_MODEL."
echo " Say 'Hey Google' — it will immediately dismiss Google Assistant"
echo " and launch Claude into Voice Mode!"
echo "=================================================================="
