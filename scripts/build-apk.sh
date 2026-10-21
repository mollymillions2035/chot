#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLCHAIN_DIR="$PROJECT_DIR/.toolchain"

mkdir -p "$TOOLCHAIN_DIR"

# 1. Ensure JDK 17 is available
if command -v javac >/dev/null 2>&1; then
    echo "==> Using system JDK: $(javac -version 2>&1)"
else
    JDK_DIR="$TOOLCHAIN_DIR/jdk-17"
    if [ ! -x "$JDK_DIR/bin/javac" ]; then
        echo "==> Downloading portable Eclipse Temurin JDK 17 (Linux x64)..."
        mkdir -p "$JDK_DIR"
        curl -fL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
            -o "$TOOLCHAIN_DIR/jdk17.tar.gz"
        tar -xzf "$TOOLCHAIN_DIR/jdk17.tar.gz" -C "$JDK_DIR" --strip-components=1
        rm -f "$TOOLCHAIN_DIR/jdk17.tar.gz"
    fi
    export JAVA_HOME="$JDK_DIR"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "==> Using portable JDK: $(javac -version 2>&1)"
fi

# 2. Ensure Android SDK (platforms;android-34 + build-tools;34.0.0 + platform-tools) is available
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    SDK_DIR="$ANDROID_HOME"
elif [ -d "$HOME/Android/Sdk" ]; then
    SDK_DIR="$HOME/Android/Sdk"
else
    SDK_DIR="$TOOLCHAIN_DIR/android-sdk"
fi

CMDLINE_TOOLS_BIN="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$CMDLINE_TOOLS_BIN" ]; then
    echo "==> Downloading Android SDK Command-Line Tools..."
    mkdir -p "$SDK_DIR/cmdline-tools"
    curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        -o "$TOOLCHAIN_DIR/cmdline-tools.zip"
    python3 -m zipfile -e "$TOOLCHAIN_DIR/cmdline-tools.zip" "$SDK_DIR/cmdline-tools"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    chmod +x "$SDK_DIR/cmdline-tools/latest/bin/"*
    rm -f "$TOOLCHAIN_DIR/cmdline-tools.zip"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

if [ ! -d "$SDK_DIR/platforms/android-34" ] || [ ! -d "$SDK_DIR/build-tools/34.0.0" ]; then
    echo "==> Accepting Android SDK licenses & installing android-34 + build-tools;34.0.0..."
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

# 3. Ensure Gradle 8.7 is available
if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="gradle"
else
    GRADLE_DIR="$TOOLCHAIN_DIR/gradle-8.7"
    if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
        echo "==> Downloading Gradle 8.7..."
        curl -fL "https://services.gradle.org/distributions/gradle-8.7-bin.zip" \
            -o "$TOOLCHAIN_DIR/gradle.zip"
        python3 -m zipfile -e "$TOOLCHAIN_DIR/gradle.zip" "$TOOLCHAIN_DIR"
        chmod +x "$GRADLE_DIR/bin/gradle"
        rm -f "$TOOLCHAIN_DIR/gradle.zip"
    fi
    GRADLE_BIN="$GRADLE_DIR/bin/gradle"
fi

# 4. Write local.properties and build the APK
echo "sdk.dir=$SDK_DIR" > "$PROJECT_DIR/local.properties"

echo "==> Building chot APK..."
cd "$PROJECT_DIR"
"$GRADLE_BIN" assembleDebug --no-daemon

BUILT_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
cp -f "$BUILT_APK" "$PROJECT_DIR/chot-pixel8.apk"

echo ""
echo "=================================================================="
echo " APK built successfully:"
echo "   $PROJECT_DIR/chot-pixel8.apk ($(du -h "$PROJECT_DIR/chot-pixel8.apk" | cut -f1))"
echo " To install and auto-configure your Pixel 8 over USB/Wireless ADB:"
echo "   ./scripts/pixel8-adb-setup.sh"
echo "=================================================================="
