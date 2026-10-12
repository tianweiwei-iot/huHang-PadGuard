#!/usr/bin/env bash
# =============================================================================
# PadGuard build + install helper
# Rebuilds child + parent apps from CURRENT source (clean), refreshes the
# distribution folder, and installs to any connected tablet/phone via adb.
# Run from git bash:   bash build-and-install.sh
# Use this instead of installing stale copied APKs.
# =============================================================================
set -e

CHILD_DIR="E:/PCS/V1.0.261012/护航成长"
PARENT_DIR="E:/PCS/V1.0.261012/护航控制端"

# 注意：孩子端根 build.gradle.kts 把 buildDir 绝对重定向到 E:/builds/padguard，
#       与源码所在目录无关，所以 CHILD_APK 固定在此路径；家长端用默认 build/，
#       其 APK 跟随 PARENT_DIR。两端产物路径不同，不存在互相覆盖。
CHILD_APK="E:/builds/padguard/app/outputs/apk/debug/app-debug.apk"
PARENT_APK="E:/PCS/V1.0.261012/护航控制端/app/build/outputs/apk/debug/app-debug.apk"

DIST="E:/PCS/V1.0.261012/发布包/安装包-最新"
STEP3="E:/PCS/V1.0.261012/发布包/第3步-孩子平板-护航成长.apk"
STEP2="E:/PCS/V1.0.261012/发布包/第2步-家长手机-护航控制端.apk"

echo "[1/4] Building child app from source (clean assembleDebug) ..."
( cd "$CHILD_DIR" && ./gradlew clean assembleDebug )
echo "[2/4] Building parent app from source (clean assembleDebug) ..."
( cd "$PARENT_DIR" && ./gradlew clean assembleDebug )

echo "[3/4] Refreshing distribution copies with the fresh builds ..."
mkdir -p "$DIST"
cp -f "$CHILD_APK"  "$DIST/孩子端-app-debug.apk"
cp -f "$CHILD_APK"  "$STEP3"
cp -f "$PARENT_APK" "$DIST/家长端-app-debug.apk"
cp -f "$PARENT_APK" "$STEP2"

echo "[4/4] Installing to connected devices via adb (best effort) ..."
adb install -r "$CHILD_APK"  || echo "  (child: no device / adb not found — install 安装包-最新/孩子端-app-debug.apk manually)"
adb install -r "$PARENT_APK" || echo "  (parent: no device / adb not found — install 安装包-最新/家长端-app-debug.apk manually)"

echo "Done. Install sources now reflect the latest source code."
