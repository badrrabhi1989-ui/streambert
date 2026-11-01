#!/usr/bin/env bash
# Builds a debug APK. Installs nothing system-wide; expects Node, JDK 21, and
# an Android SDK in ANDROID_HOME (default: $HOME/android-sdk).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  fi
fi

if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
  echo "Android SDK platform 35 is missing under $ANDROID_HOME" >&2
  echo "See the Android section of the README." >&2
  exit 1
fi

if [[ ! -d node_modules/@capacitor/cli ]]; then
  npm install
fi

npx vite build

if [[ ! -f android/app/build.gradle ]]; then
  npx cap add android
fi

node scripts/patch-android-project.mjs
npx cap sync android
node scripts/prepare-android.mjs

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties

cd android
chmod +x ./gradlew
./gradlew assembleDebug testDebugUnitTest --stacktrace

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "Gradle finished without $APK" >&2
  exit 1
fi

mkdir -p /opt/cursor/artifacts
cp "$APK" /opt/cursor/artifacts/reelix-debug.apk
echo "APK: $ROOT/android/$APK"
echo "Artifact: /opt/cursor/artifacts/reelix-debug.apk"
ls -lh /opt/cursor/artifacts/reelix-debug.apk
