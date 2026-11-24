#!/usr/bin/env bash
# PadGuard 真机回归门禁（P0-2 / R-00 ~ R-13）。
#
# 用途：连接一台真机（孩子端已绑定），用 adb 驱动执行回归清单中可机械化的部分
#       （版本守卫 / 权限与组件探测 / 无障碍静默自愈 / 强杀 / 飞行模式 / 服务存活），
#       对其余依赖「跨零点 / 锁屏 UI / 远程指令 / root 改时间」的项给出明确人工步骤。
#       输出 PASS / WARN / SKIP / MANUAL / FAIL 汇总，并落 Markdown 报告 + logcat 证据。
#
# 用法：
#   bash scripts/device-regression.sh                    # 全量（自动项跑、人工项出步骤）
#   bash scripts/device-regression.sh R-00 R-10A         # 只跑指定编号
#   PKG=com.padguard.child.debug bash scripts/device-regression.sh
#   EXPECT_VERSION_CODE=6 STRICT=1 bash scripts/device-regression.sh
#   REPORT_DIR=/d/builds bash scripts/device-regression.sh
#
# 前置：已安装 adb 且设备通过 USB 调试连接；孩子端已安装并绑定。
# 版本守卫：默认从「护航成长/gradle.properties」读取 VERSION_CODE / VERSION_NAME；
#           物理机上若确实要验旧包，可用 EXPECT_VERSION_CODE / EXPECT_VERSION_NAME 覆盖。
# 注意：本脚本**不改写任何生产代码**，只读设备状态 / 驱动系统动作 / 采集日志。
#       R-10A 会主动清空 enabled_accessibility_services 再等待自愈，属破坏性回归，
#       仅在确认 WRITE_SECURE_SETTINGS 已 pm grant 后执行；未授权则 SKIP。
set -uo pipefail

PKG="${PKG:-com.padguard.child}"
ADB="${ADB:-adb}"
WANT="${*:-ALL}"
STRICT="${STRICT:-0}"
REPORT_DIR="${REPORT_DIR:-/tmp}"
CHILD_PROPS="$(cd "$(dirname "$0")/../../护航成长" 2>/dev/null && pwd)/gradle.properties"

EXPECT_VERSION_CODE="${EXPECT_VERSION_CODE:-}"
EXPECT_VERSION_NAME="${EXPECT_VERSION_NAME:-}"
if [ -z "$EXPECT_VERSION_CODE" ] && [ -f "$CHILD_PROPS" ]; then
  EXPECT_VERSION_CODE="$(grep -m1 '^VERSION_CODE=' "$CHILD_PROPS" 2>/dev/null | cut -d= -f2 | tr -d '\r')"
fi
if [ -z "$EXPECT_VERSION_NAME" ] && [ -f "$CHILD_PROPS" ]; then
  EXPECT_VERSION_NAME="$(grep -m1 '^VERSION_NAME=' "$CHILD_PROPS" 2>/dev/null | cut -d= -f2 | tr -d '\r')"
fi

PASS=0; WARN=0; SKIP=0; MANUAL=0; FAIL=0
REPORT_LINES=()
REPORT_FILE="$REPORT_DIR/device-regression-$(date +%Y%m%d-%H%M%S).md"
LOGCAT_FILE="$REPORT_DIR/device-regression-logcat-$(date +%Y%m%d-%H%M%S).txt"
mkdir -p "$REPORT_DIR" 2>/dev/null || true

green(){ printf '\033[32m%s\033[0m\n' "$1"; }
red(){ printf '\033[31m%s\033[0m\n' "$1"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$1"; }
info(){ printf '%s\n' "$1"; }
section(){ echo; info "=== $1 ==="; record ""; record "### $1"; }
record(){ REPORT_LINES+=("$1"); }

wanted(){
  [ "$WANT" = "ALL" ] && return 0
  case " $WANT " in *" $1 "*) return 0;; *) return 1;; esac
}

run(){
  local id="$1"; local title="$2"; local mode="$3"
  wanted "$id" || return 1
  section "$id $title"
  if [ "$mode" = "MANUAL" ]; then
    MANUAL=$((MANUAL+1))
    yellow "MANUAL：见下步骤（脚本无法自动判定）"
    record "- MANUAL：见脚本同编号步骤（跨进程/跨 ROM/需人工交互）"
  fi
  return 0
}

pass(){ PASS=$((PASS+1)); green "PASS: $1"; record "- PASS：$1"; }
warn(){ WARN=$((WARN+1)); yellow "WARN: $1"; record "- WARN：$1"; }
skip(){ SKIP=$((SKIP+1)); yellow "SKIP: $1"; record "- SKIP：$1"; }
fail(){ FAIL=$((FAIL+1)); red "FAIL: $1"; record "- FAIL：$1"; }

# ---- 精确判据（旧脚本用 grep -c "com.padguard.child" 会把 Receiver / Activity 也算成服务存活） ----
svc_running(){
  local n
  n="$($ADB shell "dumpsys activity services $PKG 2>/dev/null" | grep -c "/.service.GuardService")"
  [ "$n" -gt 0 ] 2>/dev/null
}

perm_granted(){
  local out
  out="$($ADB shell pm check "$1" -p "$PKG" 2>/dev/null | tr -d '\r')"
  echo "$out" | grep -q ':granted'
}

a11y_enabled(){
  local v
  v="$($ADB shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
  echo "$v" | grep -q "$PKG/.guard.OperationGuardAccessibilityService"
}

set_airplane(){
  local state="$1"   # 1 = on, 0 = off
  local broadcast
  if [ "$state" = "1" ]; then broadcast=true; else broadcast=false; fi
  $ADB shell settings put global airplane_mode_on "$state" >/dev/null 2>&1
  $ADB shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "$broadcast" >/dev/null 2>&1
}

detect_pkg(){
  local found
  found="$($ADB shell pm list packages 2>/dev/null | grep -oE 'com\.padguard\.child(\.debug)?' | head -1 | tr -d '\r')"
  if [ -n "$found" ]; then PKG="$found"; fi
}

wait_device(){
  $ADB wait-for-device >/dev/null 2>&1
  local t=0
  until $ADB shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do
    sleep 2; t=$((t+2)); [ "$t" -ge 120 ] && break
  done
}

flush_logcat(){
  if $ADB get-state >/dev/null 2>&1; then
    $ADB logcat -d -t 3000 > "$LOGCAT_FILE" 2>/dev/null || true
    record ""
    record "logcat 证据：$LOGCAT_FILE"
  fi
}

# ============================================================
# 版本守卫与权限探测（新增 R-00）
# ============================================================
R00_environment(){
  run "R-00" "版本守卫 / 权限与组件探测" "AUTO" || return 0
  local dump code name
  dump="$($ADB shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
  code="$(echo "$dump" | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2)"
  name="$(echo "$dump" | grep -m1 -oE 'versionName=[^[:space:]]+' | cut -d= -f2)"
  if [ -z "$code" ]; then
    fail "读不到 $PKG 的 versionCode（包未安装或 adb 无权限）"
    return
  fi
  info "设备版本：versionCode=$code versionName=$name（期望 ${EXPECT_VERSION_CODE:-任意} / ${EXPECT_VERSION_NAME:-任意}）"
  record "- 设备版本：versionCode=$code versionName=$name"
  if [ -n "$EXPECT_VERSION_CODE" ] && [ "$code" != "$EXPECT_VERSION_CODE" ]; then
    fail "装包落后：设备 versionCode=$code，源码期望 $EXPECT_VERSION_CODE。历史多次栽在「重新 Run 了却还是旧行为」，所有回归结果无效，先装最新包。"
  else
    pass "versionCode 与源码一致"
  fi
  if a11y_enabled; then pass "无障碍服务已启用"; else fail "无障碍服务未启用（守卫无法接管）"; fi
  if svc_running; then pass "GuardService 存活"; else warn "GuardService 当前不在运行（R-03 会拉起验证）"; fi
  if perm_granted android.permission.WRITE_SECURE_SETTINGS; then
    pass "WRITE_SECURE_SETTINGS 已授予（一键清理可静默自愈）"
  else
    warn "WRITE_SECURE_SETTINGS 未授予：一键清理后只能检测+告警，需 adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
  fi
  if perm_granted android.permission.SYSTEM_ALERT_WINDOW; then
    pass "SYSTEM_ALERT_WINDOW 已授予（确认浮层可盖锁屏）"
  else
    warn "SYSTEM_ALERT_WINDOW 未授予：R-05 浮层项必然失败，先手动授权"
  fi
  local da
  da="$($ADB shell dumpsys device_policy 2>/dev/null | grep -c "$PKG")"
  if [ "$da" -gt 0 ] 2>/dev/null; then
    info "设备管理员/DO 已激活（硬管控可用）"; record "- 设备管理员/DO 已激活"
  else
    warn "未激活 DO：软管控下强停/安全模式/改时间可绕过（已如实告知家长）"
  fi
}

# ============================================================
# 自动项
# ============================================================
R02_airplane(){
  run "R-02" "飞行模式整天" "AUTO" || return 0
  set_airplane 1
  sleep 3
  if svc_running; then pass "飞行模式下 GuardService 仍存活"; else fail "飞行模式下 GuardService 未存活"; fi
  $ADB shell am broadcast -a android.intent.action.TIME_SET >/dev/null 2>&1
  sleep 2
  if svc_running; then pass "TIME_SET 广播后未崩溃"; else fail "TIME_SET 广播后服务掉线"; fi
  # 旧脚本漏了恢复飞行模式，后续 R-06/R-11 需要网络时会假失败
  set_airplane 0
  sleep 3
  if $ADB shell settings get global airplane_mode_on 2>/dev/null | grep -q 0; then
    pass "飞行模式已恢复关闭"
  else
    fail "飞行模式未恢复：后续联网用例会假失败"
  fi
}

R03_forcekill(){
  run "R-03" "杀进程重启" "AUTO" || return 0
  $ADB shell am force-stop "$PKG"
  sleep 2
  $ADB shell am kill "$PKG" 2>/dev/null
  sleep 1
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 4
  if svc_running; then pass "强杀后守护服务已恢复"; else fail "强杀后守护服务未恢复（看门狗/JobScheduler 未拉起）"; fi
}

R10_nonDO(){
  run "R-10" "非DO 强停/安全模式（服务自愈）" "AUTO" || return 0
  $ADB shell am force-stop "$PKG"
  sleep 2
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 4
  if svc_running; then pass "强停后服务自愈（软管控下拉活链路可用）"; else fail "强停后服务未自愈"; fi
  local da
  da="$($ADB shell dumpsys device_policy 2>/dev/null | grep -c "$PKG")"
  if [ "$da" -gt 0 ] 2>/dev/null; then
    info "设备管理员/DO 已激活（加固态）"; record "- 设备管理员/DO 已激活"
  else
    warn "未激活 DO：软管控下强停/安全模式/改时间可绕过（已如实告知家长）"
  fi
}

R10A_a11y_heal(){
  run "R-10A" "无障碍一键清理静默自愈（EMUI）" "AUTO" || return 0
  if ! perm_granted android.permission.WRITE_SECURE_SETTINGS; then
    skip "未授予 WRITE_SECURE_SETTINGS，无法静默自愈。先执行：adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
    return
  fi
  $ADB shell settings put secure enabled_accessibility_services '' >/dev/null 2>&1
  local waited=0 ok=0
  while [ "$waited" -lt 180 ]; do
    sleep 5; waited=$((waited+5))
    if a11y_enabled; then ok=1; break; fi
  done
  if [ "$ok" = 1 ]; then
    pass "无障碍在 ${waited}s 内静默恢复（WRITE_SECURE_SETTINGS 直写，上限 180s）"
  else
    fail "180s 内未恢复：检查 A11yRebindNotifier / GuardHeartbeat 自愈链"
  fi
  if svc_running; then pass "自愈后 GuardService 仍存活"; else fail "自愈后 GuardService 掉线"; fi
}

# ============================================================
# 半自动 / 人工项
# ============================================================
R01_midnight(){
  run "R-01" "跨零点日切" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 孩子端用至额度耗尽 → 静置至系统跨 00:00（或 root 设备 adb shell date 改时间）；
  2. 静置 15s，验证进入等待页（AWAITING_START）并弹出「开始计时」确认；
  3. 断言：不自动起表；点确认后才起表。
EOF
}
R04_waitbtn(){
  run "R-04" "等待页开始计时按钮" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 跨日后到达等待页 → 点「开始计时」；
  2. 断言：在线经服务端 start() 起表 / 离线本地起表，倒计时开始。
EOF
}
R05_overlay(){
  run "R-05" "确认浮层盖锁屏可点" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 锁屏状态下使孩子端收到 needsStartConfirm；
  2. 断言：全屏系统浮层可点「开始计时」，不受锁屏阻挡；
  3. 若 Android 14 无 USE_FULL_SCREEN_INTENT，断言降级为普通高优可点通知而非静默消失。
EOF
}
R06_remote(){
  run "R-06" "远程解锁" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 家长端下「解除本次锁屏」→ 孩子端即时解除；
  2. 断言：等待页/锁屏解除，且 UNLOCK 不会把自检重挂的 AWAITING_START 压死。
EOF
}
R07_idempotent(){
  run "R-07" "家长重复点促办幂等" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 家长端连点多次「立即同步/促办」；
  2. 断言：指令幂等，不重复起表、不重复告警。
EOF
}
R08_suppress(){
  run "R-08" "暂不 10min 抑制窗" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 浮层点「暂不开始」→ 观察 10s 心跳；
  2. 断言：窗口内不再自动弹，窗口过后恢复；等待页按钮不受抑制。
EOF
}
R09_coldtime(){
  run "R-09" "冷启动改时间绕过（受信钟）" "MANUAL" || return 0
  cat <<EOF
  人工步骤（需 root 设备）：
  1. 计时中 → 关机 → adb shell date 改到允许时段 → 开机；
  2. 断言：受信钟钳制生效，时段判定不被伪造；篡改被检测并告警。
EOF
}
R11_capture(){
  run "R-11" "看屏授权（EMUI 10）" "MANUAL" || return 0
  cat <<EOF
  人工步骤：
  1. 触发看屏 → 孩子授权 MediaProjection；
  2. 断言：录屏正常；拒绝授权降级为不采集、不崩溃；授权态在家长端展示。
EOF
}
R12_consent(){
  run "R-12" "撤回同意后本地停采" "MANUAL" || return 0
  cat <<EOF
  人工步骤（服务端 + 孩子端联调）：
  1. 家长端数据权利页点「撤回同意」→ 确认策略包版本递增并下发；
  2. 孩子端等待心跳拉取新策略（或离线重启后从缓存读取）；
  3. 断言：截图/定位/应用/网址/硬件采集全部停止，但心跳仍在（否则收不到恢复同意）；
  4. 家长端下即时采集指令，断言被闸门拒绝且提示「已撤回同意」。
EOF
}
R13_mode_gate(){
  run "R-13" "管控模式双闸门" "MANUAL" || return 0
  cat <<EOF
  人工步骤（软/硬管控切换）：
  1. 家长端选 HARD 但设备未设 DO → 断言不进入硬管控、给出明确引导；
  2. 设备已设 DO、家长端选 HARD → 断言硬管控能力（setPackagesSuspended）生效；
  3. 切回 SOFT → 断言归还设备管理员权限且旧 ack 清理，不残留硬管控行为。
EOF
}

ALL_MANUAL_STEPS(){
  R01_midnight; R04_waitbtn; R05_overlay; R06_remote; R07_idempotent
  R08_suppress; R09_coldtime; R11_capture; R12_consent; R13_mode_gate
}

write_report(){
  {
    echo "# PadGuard 真机回归报告"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- 目标包：$PKG"
    echo "- 期望版本：${EXPECT_VERSION_CODE:-任意} / ${EXPECT_VERSION_NAME:-任意}"
    echo "- 命令：bash scripts/device-regression.sh $WANT"
    echo
    echo "## 结果"
    echo
    printf '%s\n' "${REPORT_LINES[@]+"${REPORT_LINES[@]}"}"
    echo
    echo "## 汇总"
    echo
    echo "PASS=$PASS WARN=$WARN SKIP=$SKIP MANUAL=$MANUAL FAIL=$FAIL"
  } > "$REPORT_FILE" 2>/dev/null || true
  info "报告：$REPORT_FILE"
}

# ============================================================
# 主流程
# ============================================================
section "设备探测"
if ! $ADB get-state >/dev/null 2>&1; then
  red "未检测到已连接设备（adb get-state 失败）。本次仅打印人工步骤。"
  ALL_MANUAL_STEPS
  skip "自动项跳过（无设备）"
  write_report
  exit 0
fi
detect_pkg
info "目标包：$PKG"
wait_device

R00_environment
R02_airplane
R03_forcekill
R10_nonDO
R10A_a11y_heal
ALL_MANUAL_STEPS
flush_logcat

section "结论"
info "PASS=$PASS  WARN=$WARN  SKIP=$SKIP  MANUAL=$MANUAL  FAIL=$FAIL"
write_report
if [ "$FAIL" -gt 0 ] && [ "$STRICT" = "1" ]; then
  red "存在 FAIL，STRICT 模式下以非 0 退出。"
  exit 1
fi
green "回归脚本执行完毕。自动项见 PASS/FAIL；MANUAL 项需按步骤人工点检后再发布。"
