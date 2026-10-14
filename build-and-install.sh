#!/usr/bin/env bash
# =============================================================================
# PadGuard 发布包构建 + 安装助手（分发脚本切到 release）
# 从当前源码干净重建孩子端 + 家长端 RELEASE 包，刷新发布文件夹，并通过 adb 安装到已连接设备。
# 运行：  bash build-and-install.sh
#
# 前置（release 必须，否则 assembleRelease 会明确报错，见各 app/build.gradle.kts 末尾守卫）：
#   1) 两端均需在 local.properties（不入库）配置正式 keystore：
#        RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
#   2) 家长端还需高德 Key：用 -PAMAP_KEY=xxx 注入，或 gradle.properties 写 amapKey=xxx；
#      并在高德开放平台控制台把「release keystore 的 SHA1」加入该 Key 的签名绑定
#      （否则定位页白屏/无底图，属上线事故）。debug 构建允许空 Key（回退示意占位图）。
#   生成 keystore（一次性，妥善保管）：
#     keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias padguard
# =============================================================================
set -e

# 项目根：由脚本自身位置推导（本脚本与两个 Android 工程同级）。
# 不写死盘符与绝对路径——历史上这里写死过 D:/Projects/PCS/...，项目迁到 E: 盘后
# 整个脚本静默失效（引用路径全部不存在），排查成本很高。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHILD_DIR="$ROOT/护航成长"
PARENT_DIR="$ROOT/护航控制端"
PROJECT_DRIVE="$(printf '%s' "$ROOT" | cut -c1)"

# 孩子端根 build.gradle.kts 把「含中文路径」的构建产物重定向到 <盘符>:/builds/padguard，
# :app 模块的 moduleKey 为 "app"，故产物固定在 <盘符>:/builds/padguard/app。
# 家长端未重定向，沿用默认 build/ 目录（跟随 PARENT_DIR）。
CHILD_APK="$PROJECT_DRIVE:/builds/padguard/app/outputs/apk/release/app-release.apk"
PARENT_APK="$PARENT_DIR/app/build/outputs/apk/release/app-release.apk"

DIST="$ROOT/发布包/安装包-最新"
STEP3="$ROOT/发布包/第3步-孩子平板-护航成长.apk"
STEP2="$ROOT/发布包/第2步-家长手机-护航控制端.apk"

# 若环境变量 AMAP_KEY 已就绪，注入家长端 release 构建（避免定位页白屏/无底图）。
AMAP_ARG=""
if [ -n "${AMAP_KEY:-}" ]; then
  AMAP_ARG="-PAMAP_KEY=$AMAP_KEY"
fi

echo "[1/4] Building child app from source (clean assembleRelease) ..."
( cd "$CHILD_DIR" && ./gradlew clean assembleRelease )
echo "[2/4] Building parent app from source (clean assembleRelease ${AMAP_ARG}) ..."
( cd "$PARENT_DIR" && ./gradlew clean assembleRelease $AMAP_ARG )

echo "[3/4] Refreshing distribution copies with the fresh RELEASE builds ..."
mkdir -p "$DIST"
cp -f "$CHILD_APK"  "$DIST/孩子端-app-release.apk"
cp -f "$CHILD_APK"  "$STEP3"
cp -f "$PARENT_APK" "$DIST/家长端-app-release.apk"
cp -f "$PARENT_APK" "$STEP2"

echo "[4/4] Installing to connected devices via adb (best effort) ..."
adb install -r "$CHILD_APK"  || echo "  (child: no device / adb not found — install 安装包-最新/孩子端-app-release.apk manually)"
adb install -r "$PARENT_APK" || echo "  (parent: no device / adb not found — install 安装包-最新/家长端-app-release.apk manually)"

echo "Done. Install sources now reflect the latest source code (RELEASE builds)."
