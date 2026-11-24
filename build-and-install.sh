#!/usr/bin/env bash
# =============================================================================
# PadGuard 发布包构建 + 安装助手（分发脚本切到 release）
# 从当前源码干净重建孩子端 + 家长端 RELEASE 包，刷新发布文件夹，并通过 adb 安装到已连接设备。
# 运行：  bash build-and-install.sh
#
# 前置（release 必须，否则 assembleRelease 会明确报错，见各 app/build.gradle.kts 末尾守卫）：
#   1) 两端均需在 local.properties（不入库）配置正式 keystore：
#        RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
#   2) 出厂接入点（本脚本会在 1 秒内前置检查，缺了不会白等 5 分钟构建）：
#        BASE_URL=https://<域名>/api/v1/     # 孩子端接口前缀是 /api/v1/
#        MQTT_BROKER=ssl://<域名>:8883
#      写进 护航成长/local.properties，或用环境变量 PADGUARD_BASE_URL / PADGUARD_MQTT_BROKER。
#      家长端地址由孩子端自动推导（/api/v1/ → /v1/），特殊部署才需 PARENT_BASE_URL 覆盖。
#      只想确认参数对不对、不打包：DRY_RUN=1 bash build-and-install.sh
#   3) 家长端还需高德 Key：用 -PAMAP_KEY=xxx 注入，或 gradle.properties 写 amapKey=xxx；
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
# 盘符推导必须兼容两种 pwd 形态（与 padguard-server/scripts/launch-preflight.sh 同款）：
#   Git Bash POSIX 形式  -> /d/Projects/...  （第 2 个字符才是盘符）
#   Windows 风格形式     -> D:/Projects/...  （第 1 个字符是盘符）
# 曾因直接 cut -c1 把 `/` 当盘符拼出 /:/builds/...，构建末尾 cp 找不到源包而静默失败。
DRIVE_LOWER="$(printf '%s' "$ROOT" | cut -c1 | tr '[:upper:]' '[:lower:]')"
if [ "$DRIVE_LOWER" = "/" ]; then
  DRIVE_LOWER="$(printf '%s' "$ROOT" | cut -c2 | tr '[:upper:]' '[:lower:]')"
fi
case "$DRIVE_LOWER" in
  [a-z]) ;;
  *) echo "无法从项目根推导盘符：$ROOT" >&2; exit 2 ;;
esac

# 两端根 build.gradle.kts 都把「含中文路径」的构建产物重定向（详见各自 build.gradle.kts）：
#   孩子端 -> <盘符>:/builds/padguard/app
#   家长端 -> <盘符>:/builds/padguard-parent/app
# 早期"家长端未重定向、沿用 PARENT_DIR/app/build"的假设早已过时，按它取包会拿到旧包或取不到。
CHILD_APK="/$DRIVE_LOWER/builds/padguard/app/outputs/apk/release/app-release.apk"
PARENT_APK="/$DRIVE_LOWER/builds/padguard-parent/app/outputs/apk/release/app-release.apk"

DIST="$ROOT/发布包/安装包-最新"
STEP3="$ROOT/发布包/第3步-孩子平板-护航成长.apk"
STEP2="$ROOT/发布包/第2步-家长手机-护航控制端.apk"

# 若环境变量 AMAP_KEY 已就绪，注入家长端 release 构建（避免定位页白屏/无底图）。
AMAP_ARG=""
if [ -n "${AMAP_KEY:-}" ]; then
  AMAP_ARG="-PAMAP_KEY=$AMAP_KEY"
fi

# ---------------------------------------------------------------------------
# 出厂接入点前置检查（两个 app 的 verifyReleaseEndpoint 都会拦，但那是 3 分钟的
# gradle 启动之后才报错；这里 1 秒内说清楚"缺什么、怎么补"）。
#
# 为什么必须前置：孩子端一旦带着占位域名出厂，平板上没有键盘、孩子也不会去改高级设置，
# 结果就是整批设备永久离线 —— 这类事故在构建阶段拦一次，成本最低。
# 解析顺序与 build.gradle.kts 完全一致：环境变量 > local.properties（不入库）。
# ---------------------------------------------------------------------------
pick() { # pick <envName> <propKey> <file>
  local env_val="${!1:-}"
  if [ -n "$env_val" ]; then printf '%s' "$env_val"; return; fi
  [ -f "$3" ] || return 0
  grep -E "^${2}=" "$3" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'
}

BASE_URL_RESOLVED="$(pick PADGUARD_BASE_URL BASE_URL "$CHILD_DIR/local.properties")"
MQTT_RESOLVED="$(pick PADGUARD_MQTT_BROKER MQTT_BROKER "$CHILD_DIR/local.properties")"

MISSING=""
[ -z "$BASE_URL_RESOLVED" ] && MISSING="${MISSING}  BASE_URL（服务端地址，形如 https://your.domain/api/v1/ 或 http://192.168.1.11:8090/api/v1/）\n"
[ -z "$MQTT_RESOLVED" ]     && MISSING="${MISSING}  MQTT_BROKER（指令通道，形如 ssl://your.domain:8883 或 tcp://192.168.1.11:1883）\n"
if [ -n "$MISSING" ]; then
  echo "❌ release 接入点未配置，已中止（未开始构建）：" >&2
  printf "%b" "$MISSING" >&2
  echo "配置方式（任选其一）：" >&2
  echo "  a) 写进 $CHILD_DIR/local.properties（不入库，推荐）：" >&2
  echo "       BASE_URL=https://your.domain/api/v1/" >&2
  echo "       MQTT_BROKER=ssl://your.domain:8883" >&2
  echo "  b) 环境变量：export PADGUARD_BASE_URL=... PADGUARD_MQTT_BROKER=..." >&2
  echo "  c) 家长端地址由孩子端自动推导（/api/v1/ → /v1/）；特殊部署可用 PARENT_BASE_URL 覆盖。" >&2
  exit 1
fi
echo "接入点：BASE_URL=$BASE_URL_RESOLVED  MQTT_BROKER=$MQTT_RESOLVED"

# 两端 REST 前缀不同，同一个主机不能直接把同一个字符串塞给两边：
#   孩子端   DEFAULT_BASE_URL = https://host/api/v1/   （孩子端接口统一 /api/v1 前缀）
#   家长端   BASE_URL         = https://host/v1/       （Retrofit 基址直接拼 /v1/...）
# 误把 /api/v1/ 传给家长端 → 所有请求 404，且表现为"登录失败"而不是"地址错"，极难排查。
# 所以家长端地址由孩子端地址推导；特殊部署可用 PARENT_BASE_URL 显式覆盖。
derive_parent_base() {
  case "$1" in
    */api/v1/) printf '%s' "${1%/api/v1/}/v1/" ;;
    */api/v1)  printf '%s' "${1%/api/v1}/v1/" ;;
    *)         printf '%s' "$1" ;;
  esac
}
PARENT_BASE="${PADGUARD_PARENT_BASE_URL:-$(pick PADGUARD_PARENT_BASE_URL PARENT_BASE_URL "$PARENT_DIR/local.properties")}"
[ -z "$PARENT_BASE" ] && PARENT_BASE="$(derive_parent_base "$BASE_URL_RESOLVED")"
echo "家长端 BASE_URL=$PARENT_BASE"

# DRY_RUN=1：只打印解析出的接入点、不真的打包（5 分钟构建之前先确认参数对不对）。
if [ "${DRY_RUN:-}" = "1" ]; then
  echo "DRY_RUN=1，接入点校验通过，未开始构建。"
  exit 0
fi

# 强制质量门禁（2026-11-18）：发布脚本此前 clean assembleRelease → cp → adb install，
# 全程不跑测试/lint，与《上线前最终验收检查表》的"三端门禁"承诺脱节。
# 现在默认必须先过 launch-preflight.sh（真实重跑 + 用例数下限 + lint），
# 任何失败立即 NO-GO，不进入耗时构建。极端排障场景可用 SKIP_GATE=1 显式跳过（会打印告警）。
if [ "${SKIP_GATE:-0}" = "1" ]; then
  echo "⚠ SKIP_GATE=1：跳过强制测试/lint 门禁，产出的包不得视为已验发布包。"
else
  echo "[0/4] Running mandatory preflight gate (tests + lint) ..."
  if ! bash "$ROOT/padguard-server/scripts/launch-preflight.sh"; then
    echo "NO-GO：质量门禁失败，未开始打包。" >&2
    exit 1
  fi
fi

echo "[1/4] Building child app from source (clean assembleRelease) ..."
( cd "$CHILD_DIR" && ./gradlew clean assembleRelease -PBASE_URL="$BASE_URL_RESOLVED" -PMQTT_BROKER="$MQTT_RESOLVED" )
echo "[2/4] Building parent app from source (clean assembleRelease ${AMAP_ARG}) ..."
( cd "$PARENT_DIR" && ./gradlew clean assembleRelease $AMAP_ARG -PBASE_URL="$PARENT_BASE" )

echo "[3/4] Refreshing distribution copies with the fresh RELEASE builds ..."
# 构建成功但产物不在预期位置时，立即报出实际解析路径；否则 cp 在 set -e 下直接退出，
# 家长排查不到"到底找的是哪个文件"（历史事故：盘符推导错、家长端重定向改路径）。
for APK in "$CHILD_APK" "$PARENT_APK"; do
  if [ ! -f "$APK" ]; then
    echo "构建完成但找不到产物：$APK" >&2
    echo "请核对各工程 build.gradle.kts 的 buildRoot 重定向配置。" >&2
    exit 1
  fi
done
mkdir -p "$DIST"
cp -f "$CHILD_APK"  "$DIST/孩子端-app-release.apk"
cp -f "$CHILD_APK"  "$STEP3"
cp -f "$PARENT_APK" "$DIST/家长端-app-release.apk"
cp -f "$PARENT_APK" "$STEP2"

echo "[4/4] Installing to connected devices via adb (best effort) ..."
adb install -r "$CHILD_APK"  || echo "  (child: no device / adb not found — install 安装包-最新/孩子端-app-release.apk manually)"
adb install -r "$PARENT_APK" || echo "  (parent: no device / adb not found — install 安装包-最新/家长端-app-release.apk manually)"

# WRITE_SECURE_SETTINGS：装机时一次性授予孩子端「无障碍静默自愈」能力（signature|privileged，
# 系统不会自动授予）。授予后 EMUI「一键清理」停用无障碍，孩子端可自己把开关写回
# ENABLED_ACCESSIBILITY_SERVICES（AccessibilityCapabilityHealer），无需家长到场。
# release 包名 com.padguard.child；debug 包（adb 安装 debug 构建时）为 com.padguard.child.debug。
# best effort：无设备/未授权不影响安装结果；卸载重装后需重新执行本步骤。
for PKG in com.padguard.child com.padguard.child.debug; do
  adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null \
    && echo "  (granted WRITE_SECURE_SETTINGS to $PKG)" \
    || true
done

echo "Done. Install sources now reflect the latest source code (RELEASE builds)."
