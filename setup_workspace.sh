#!/usr/bin/env bash
set -e

echo "=== KothaPad Workspace Setup ==="

# 1. Restore debug keystore if missing
if [ ! -f debug.keystore ] && [ -f debug.keystore.base64 ]; then
  echo "Restoring debug.keystore from debug.keystore.base64..."
  base64 -d debug.keystore.base64 > debug.keystore 2>/dev/null || base64 -D debug.keystore.base64 > debug.keystore
  echo "debug.keystore restored."
fi

# 2. Restore .env from .env.example if missing
if [ ! -f .env ] && [ -f .env.example ]; then
  echo "Creating .env from .env.example..."
  cp .env.example .env
  echo ".env created."
fi

# 3. Determine Gradle command
GRADLE_CMD="gradle"
if [ -x "./gradlew" ]; then
  GRADLE_CMD="./gradlew"
fi

echo "Building debug APK with $GRADLE_CMD..."
$GRADLE_CMD assembleDebug

# 4. Ensure KothaPad.apk is present at root
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
  cp app/build/outputs/apk/debug/app-debug.apk KothaPad.apk
  echo "KothaPad.apk successfully generated at workspace root."
fi

echo "=== Workspace ready! ==="
