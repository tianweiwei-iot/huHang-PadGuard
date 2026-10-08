#!/usr/bin/env bash
# =============================================================================
# PadGuard build + install helper
# Rebuilds child + parent apps from CURRENT source (clean), refreshes the
# distribution folder, and installs to any connected tablet/phone via adb.
# Run from git bash:   bash build-and-install.sh
# Use this instead of installing stale copied APKs.
# =============================================================================
set -e

CHILD_DIR="E:/项目/护航成长"
PARENT_DIR="E:/项目/护航控制端"

CHILD_APK="E:/builds/padguard/app/outputs/apk/debug/app-debug.apk"
PARENT_APK="E:/项目/护航控制端/app/build/outputs/apk/debug/app-debug.apk"

DIST="e:/项目/发布包/安装包-最新"
STEP3="e:/项目/发布包/第3步-孩子平板-护航成长.apk"
STEP2="e:/项目/发布包/第2步-家长手机-护航控制端.apk"

echo "[1/4] Building child app from source (clean assembleDebug) ..."
( cd "$CHILD_DIR" && ./gradlew clean assembleDebug )
echo "[2/4] Building parent app from source (clean assembleDebug) ..."
( cd "$PARENT_DIR" && ./gradlew clean assembleDebug )

echo "[3/4] Refreshing distribution copies with the fresh builds ..."
cp -f "$CHILD_APK"  "$DIST/孩子端-app-debug.apk"
cp -f "$CHILD_APK"  "$STEP3"
cp -f "$PARENT_APK" "$DIST/家长端-app-debug.apk"
cp -f "$PARENT_APK" "$STEP2"

echo "[4/4] Installing to connected devices via adb (best effort) ..."
adb install -r "$CHILD_APK"  || echo "  (child: no device / adb not found — install 安装包-最新/孩子端-app-debug.apk manually)"
adb install -r "$PARENT_APK" || echo "  (parent: no device / adb not found — install 安装包-最新/家长端-app-debug.apk manually)"

echo "Done. Install sources now reflect the latest source code."
