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

node scripts/verify-tv-focus.mjs
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

OUT="app/build/outputs/apk/debug"
UNIVERSAL="$OUT/app-universal-debug.apk"
ARM32="$OUT/app-armeabi-v7a-debug.apk"
ARM64="$OUT/app-arm64-v8a-debug.apk"
if [[ ! -f "$UNIVERSAL" || ! -f "$ARM32" || ! -f "$ARM64" ]]; then
  echo "Gradle finished without the universal and ARM APKs" >&2
  ls -lh "$OUT" || true
  exit 1
fi

mkdir -p /opt/cursor/artifacts
cp "$UNIVERSAL" /opt/cursor/artifacts/reelix-universal.apk
cp "$UNIVERSAL" /opt/cursor/artifacts/reelix-debug.apk
cp "$ARM32" /opt/cursor/artifacts/reelix-armeabi-v7a.apk
cp "$ARM64" /opt/cursor/artifacts/reelix-arm64-v8a.apk
python3 - << 'PY'
import os
limit = 24 * 1024 * 1024
root = "/opt/cursor/artifacts"
for name in ("reelix-armeabi-v7a.apk", "reelix-arm64-v8a.apk", "reelix-universal.apk"):
    size = os.path.getsize(os.path.join(root, name))
    print(f"{name} {size}")
    if name != "reelix-universal.apk" and size >= limit:
        raise SystemExit(f"{name} is {size} bytes, over 24 MB")
PY
ls -lh /opt/cursor/artifacts/reelix-universal.apk /opt/cursor/artifacts/reelix-armeabi-v7a.apk /opt/cursor/artifacts/reelix-arm64-v8a.apk
