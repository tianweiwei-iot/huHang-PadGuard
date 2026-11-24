#!/usr/bin/env bash
# PadGuard 上线前自检（launch preflight / gate）。
#
# 用途：在发布/交付前一键跑通三端测试 + lint，并探活服务端，输出 GO / NO-GO 结论。
# 不修改任何源码，只读取测试结果。可在本地或 CI 中调用。
#
# 用法：
#   bash scripts/launch-preflight.sh                 # 严格模式：任何失败 → 非 0 退出
#   bash scripts/launch-preflight.sh --no-strict     # 兼容旧行为：失败只打印 NO-GO，仍 exit 0
#   CI=true bash scripts/launch-preflight.sh         # CI 模式（同严格模式）
#
# 2026-11-18 加固：
#   1. 默认严格退出（旧版本地 FAIL>0 仍 exit 0，门禁可被当"跑过了"）；
#   2. 日志落到 mktemp 目录，并发 job 不再互相覆盖；
#   3. 解析 JUnit XML 校验"用例数下限"，--rerun-tasks 后仍 0 用例直接判失败（假绿灯）；
#   4. 孩子端补跑 core:* 单测（此前只跑 :app，core/data 的策略单测从未进门禁）。
#   5. 服务端测试补 --rerun-tasks：cleanTest 只删报告目录、不清 Gradle build cache，
#      并行改码时可能 FROM-CACHE 命中旧用例集假绿；与孩子端/家长端口径统一（全量门禁复核发现）。
set -uo pipefail

STRICT=true
for arg in "$@"; do
  case "$arg" in
    --no-strict) STRICT=false ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

# 始终以脚本自身所在目录为基准解析项目根，避免「从子目录调用」时路径错乱（曾导致三端套件被静默跳过、误报 GO）。
SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
ROOT="$(cd "$(dirname "$SCRIPT_PATH")/../.." && pwd)"
CHILD_DIR="$ROOT/护航成长"
PARENT_DIR="$ROOT/护航控制端"
SERVER_DIR="$ROOT/padguard-server"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/padguard-preflight.XXXXXX")"
SERVER_LOG="$LOG_DIR/server.log"
CHILD_LOG="$LOG_DIR/child.log"
PARENT_LOG="$LOG_DIR/parent.log"
ADMIN_LOG="$LOG_DIR/admin.log"

# 测试产物位置必须与两端根 build.gradle.kts 的重定向规则严格一致：
#   Windows + 中文路径 -> <盘符>:/builds/padguard(-parent)/
#   Linux/macOS/CI     -> 各模块默认 build/（Gradle 默认行为）
# 旧实现在 ubuntu-latest 上把 /home/runner/... 的第 2 个字符当盘符，拼出 /h/builds/...，
# 与真实产物路径永远对不上，用例数恒为 0 → CI 恒判 NO-GO（假红灯）。
case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=true ;;
  *) IS_WINDOWS=false ;;
esac
if [ "$IS_WINDOWS" = true ]; then
  # 兼容 Git Bash POSIX 形式（/d/...）与 Windows 风格形式（D:/...）两种 pwd。
  DRIVE_LOWER="$(printf '%s' "$ROOT" | cut -c1 | tr '[:upper:]' '[:lower:]')"
  if [ "$DRIVE_LOWER" = "/" ]; then
    DRIVE_LOWER="$(printf '%s' "$ROOT" | cut -c2 | tr '[:upper:]' '[:lower:]')"
  fi
  case "$DRIVE_LOWER" in
    [a-z]) ;;
    *) echo "无法从项目根推导盘符：$ROOT" >&2; exit 2 ;;
  esac
  CHILD_RESULTS="/$DRIVE_LOWER/builds/padguard"
  PARENT_RESULTS="/$DRIVE_LOWER/builds/padguard-parent"
  CHILD_RESULT_PATTERN='*/test-results/testDebugUnitTest/TEST-*.xml'
  PARENT_RESULT_PATTERN='*/test-results/testDebugUnitTest/TEST-*.xml'
else
  CHILD_RESULTS="$CHILD_DIR"
  PARENT_RESULTS="$PARENT_DIR"
  CHILD_RESULT_PATTERN='*/build/test-results/testDebugUnitTest/TEST-*.xml'
  PARENT_RESULT_PATTERN='*/build/test-results/testDebugUnitTest/TEST-*.xml'
fi

PASS=0
FAIL=0
WARN=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$1"; }
info()  { printf '%s\n' "$1"; }

section() { echo; info "=== $1 ==="; }

# 汇总 JUnit XML 的 tests 属性；找不到 XML 或解析失败一律返回 0（由调用方判失败）。
count_tests() {
  local root="$1" path_pattern="$2"
  local files
  files=$(find "$root" -type f -path "$path_pattern" 2>/dev/null || true)
  if [ -z "$files" ]; then echo 0; return; fi
  printf '%s\n' "$files" | while read -r f; do
    grep -o 'tests="[0-9][0-9]*"' "$f" 2>/dev/null | head -1 | cut -d'"' -f2
  done | awk '{s+=$1} END {print s+0}'
}

check_count() {
  local label="$1" count="$2" min="$3"
  if [ "$count" -lt "$min" ]; then
    red "$label 真实执行用例数=$count < 下限=$min（疑似 UP-TO-DATE / 构建缓存假绿灯）"
    FAIL=$((FAIL+1))
  else
    green "$label 真实执行用例数=$count"
    PASS=$((PASS+1))
  fi
}

# ---- 1. 服务端测试 ----
section "服务端测试 (padguard-server ./gradlew cleanTest test --rerun-tasks)"
if [ -d "$SERVER_DIR" ]; then
  if ( cd "$SERVER_DIR" && ./gradlew cleanTest test --rerun-tasks --no-daemon > "$SERVER_LOG" 2>&1 ); then
    green "服务端测试通过"
    PASS=$((PASS+1))
    check_count "服务端" "$(count_tests "$SERVER_DIR/build/test-results/test" '*/TEST-*.xml')" 1100
  else
    red "服务端测试失败（详见 $SERVER_LOG）"
    FAIL=$((FAIL+1))
  fi
else
  yellow "跳过：未找到 $SERVER_DIR"
  WARN=$((WARN+1))
fi

# ---- 2. 孩子端测试（含 core:*）+ lint ----
section "孩子端测试 + lint (护航成长 ./gradlew <all unit tests> :app:lintDebug)"
if [ -d "$CHILD_DIR" ]; then
  CHILD_TASKS=":app:testDebugUnitTest :core:common:testDebugUnitTest :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :core:transport:testDebugUnitTest :app:lintDebug"
  if ( cd "$CHILD_DIR" && ./gradlew $CHILD_TASKS --rerun-tasks > "$CHILD_LOG" 2>&1 ); then
    green "孩子端测试 + lint 通过"
    PASS=$((PASS+1))
    check_count "孩子端" "$(count_tests "$CHILD_RESULTS" "$CHILD_RESULT_PATTERN")" 600
  else
    red "孩子端测试/lint 失败（详见 $CHILD_LOG）"
    FAIL=$((FAIL+1))
  fi
else
  yellow "跳过：未找到 $CHILD_DIR"
  WARN=$((WARN+1))
fi

# ---- 3. 家长端测试（含 core:*）+ lint ----
# 2026-11-18：core:network/database/ui 空壳模块已删除，任务列表只剩 app + core:common。
section "家长端测试 + lint (护航控制端 ./gradlew <all unit tests> :app:lintDebug)"
if [ -d "$PARENT_DIR" ]; then
  PARENT_TASKS=":app:testDebugUnitTest :core:common:testDebugUnitTest :app:lintDebug"
  if ( cd "$PARENT_DIR" && ./gradlew $PARENT_TASKS --rerun-tasks > "$PARENT_LOG" 2>&1 ); then
    green "家长端测试 + lint 通过"
    PASS=$((PASS+1))
    check_count "家长端" "$(count_tests "$PARENT_RESULTS" "$PARENT_RESULT_PATTERN")" 140
  else
    red "家长端测试/lint 失败（详见 $PARENT_LOG）"
    FAIL=$((FAIL+1))
  fi
else
  yellow "跳过：未找到 $PARENT_DIR"
  WARN=$((WARN+1))
fi

# ---- 4. admin 契约守卫（与 CI 共用同一条命令，避免本地门禁漏掉 XSS/契约断言） ----
section "admin 契约守卫 (node padguard-admin/scripts/guard.js)"
if command -v node >/dev/null 2>&1; then
  if node "$ROOT/padguard-admin/scripts/guard.js" > "$ADMIN_LOG" 2>&1; then
    green "admin 契约守卫通过"
    PASS=$((PASS+1))
  else
    red "admin 契约守卫失败（详见 $ADMIN_LOG）"
    FAIL=$((FAIL+1))
  fi
else
  red "未找到 node：admin 守卫无法执行，本地与 CI 门禁口径不允许漂移"
  FAIL=$((FAIL+1))
fi

# ---- 5. 服务端探活（仅当本地已在运行） ----
section "服务端探活 (http://127.0.0.1:8090/health)"
if command -v curl >/dev/null 2>&1; then
  if curl -s --max-time 3 http://127.0.0.1:8090/health 2>/dev/null | grep -q "ready=true"; then
    green "本地服务端健康检查通过 (ready=true)"
    PASS=$((PASS+1))
  else
    yellow "本地服务端未运行或 /health 不可达（部署阶段再验，非阻断）"
    WARN=$((WARN+1))
  fi
else
  yellow "跳过：未安装 curl"
  WARN=$((WARN+1))
fi

# ---- 结论 ----
section "结论"
info "通过=$PASS  失败=$FAIL  警告(非阻断)=$WARN"
info "日志目录：$LOG_DIR"
if [ "$FAIL" -gt 0 ]; then
  red "NO-GO：存在失败项，请先修复后再发布。"
  if [ "$STRICT" = true ] || [ "${CI:-false}" = "true" ]; then exit 1; fi
  exit 0
fi
green "GO（代码门禁通过）。其余上线项见 docs/上线推进清单-2026-11-11.md 的 P0-2~P0-7（真机回归/运维/合规/发布）。"
