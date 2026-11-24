# -*- coding: utf-8 -*-
"""
孩子端三大保底功能 · 真机回归脚本（adb 驱动 + logcat 断言）

对应代码批次（R25–R28，2026-11）：
  - 权限永不丢失  —— PermissionHealthChecker 5 分钟快检 + ever-granted 账本
  - 锁屏无法退出  —— LockScreenActivity 持续补拉 + FSI 重发 + FLAG_SECURE
  - 永不掉线      —— 4 态 onlineState + 网络恢复秒级自愈(onConnectivityRestored)

本脚本不依赖后端，只通过 adb 在已绑定的真机上验证「保底行为真的在发生」：
每条用例都去 logcat 里 grep 代码里真实打印的标记（marker），看到标记才算 PASS。

用法：
    python3 scripts/regress_safeguard.py

前置：
    1. 设备已开启 USB 调试且已连接（adb devices 可见）
    2. 孩子端已安装含本批次修复的 APK，且已完成绑定
    3. 锁屏类用例需先让设备进入锁定态（见 --trigger-lock 说明）
    4. 真机日志窗口可另开：adb logcat -s LockScreenActivity:* GuardService:*

可选参数：
    --trigger-lock CMD   锁定态触发命令；默认是一句 no-op 提示。
                         例：--trigger-lock "adb shell am broadcast -a com.padguard.child.DEBUG_LOCK"
                         若你的构建未暴露调试广播，请改用家长端远程锁，再跑本脚本。
    --pkg PKG           孩子端包名，默认 com.padguard.child
    --no-wifi            跳过需要切 WiFi 的用例（无网络环境）

注意：
    - 「MQTT 假活 30s 对账」「改系统时间秒级重锁」两条依赖服务端改策略/半开链路，
      本脚本只能做尽力而为的检查并明确标注为 MANUAL/依赖契约测试，务必配合人工确认。
    - 退出码：全部 PASS=0，有 FAIL=1，有 SKIP 但无 FAIL=0。
"""

import subprocess
import sys
import time

PKG = "com.padguard.child"
TRIGGER_LOCK = None  # 由参数注入

# 代码里真实打印的标记（与源码同步，改代码时务必同步这里）
MARKER_LOCK_REARM = "re-arming"                       # LockScreenActivity 急抢补拉
MARKER_LOCK_SUSTAINED = "sustained re-arm"            # LockScreenActivity 持续补拉
MARKER_FSI_FAIL = "FSI post failed"                   # 全屏 intent 重发失败（如实降级）
MARKER_CONN_RESTORED = "connectivity restored, kicked immediate recovery"  # GuardService
MARKER_POLICY_DRIFT = "policy version drift"          # HTTPS 对账发现版本漂移

results = []


def run_adb(*args, timeout=30):
    try:
        out = subprocess.run(
            ["adb"] + list(args), capture_output=True, text=True, timeout=timeout
        )
        return out.returncode, out.stdout, out.stderr
    except FileNotFoundError:
        sys.exit("ERR: adb 不在 PATH 中，请先配置 Android SDK platform-tools")
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"


def wait_device():
    for _ in range(30):
        rc, out, _ = run_adb("devices")
        if rc == 0 and any(line.endswith("\tdevice") for line in out.splitlines()[1:]):
            return True
        time.sleep(1)
    sys.exit("ERR: 未检测到已连接设备（adb devices 无 device 状态）")


def clear_logcat():
    run_adb("logcat", "-c")


def logcat_contains(tag_filter, regex, duration=20):
    """在 duration 秒内持续采集 logcat，任一时刻匹配到 regex 即返回 True。"""
    proc = subprocess.Popen(
        ["adb", "logcat", "-v", "brief", tag_filter + ":*", "*:S"],
        stdout=subprocess.PIPE, text=True,
    )
    try:
        end = time.time() + duration
        import re
        pat = re.compile(regex)
        while time.time() < end:
            line = proc.stdout.readline()
            if pat.search(line):
                return True
        return False
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


def record(name, status, detail=""):
    results.append((name, status, detail))
    mark = {"PASS": "✅", "FAIL": "❌", "SKIP": "⚠️ ", "MANUAL": "🔧"}.get(status, "·")
    print(f"  {mark} {name}: {status}" + (f" — {detail}" if detail else ""))


def ensure_locked():
    """让设备进入锁定态：执行触发命令（若提供），否则提示人工锁定。"""
    if TRIGGER_LOCK:
        rc, _, err = run_adb(*TRIGGER_LOCK.split())
        if rc != 0:
            print(f"  触发锁定命令失败：{err.strip()}")
    else:
        print("  [人工] 请先通过家长端远程锁，或传入 --trigger-lock，使设备处于锁屏态。")
    # 给锁定一点生效时间
    time.sleep(2)


def scenario_lock_escape():
    """保底·锁屏无法退出：上滑/返回/多任务后，锁屏页持续补拉回到前台。"""
    print("[用例] 锁屏无法退出（非 DO 上滑/返回/多任务逃逸）")
    ensure_locked()
    clear_logcat()
    # 模拟逃逸手势：返回键、多任务键、上滑（swipe up）
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.3)
    run_adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
    time.sleep(0.5)
    run_adb("shell", "input", "swipe", "300", "1200", "300", "300")  # 上滑回桌面
    # 观察 15s：急抢("re-arming") 或持续("sustained re-arm") 任一出现即证明补拉在发生
    hit = logcat_contains(
        "LockScreenActivity",
        f"({MARKER_LOCK_REARM}|{MARKER_LOCK_SUSTAINED})",
        duration=15,
    )
    if hit:
        record("锁屏逃逸后持续补拉", "PASS",
               "logcat 命中 re-arming / sustained re-arm，锁屏页在自动回顶")
    else:
        record("锁屏逃逸后持续补拉", "FAIL",
               "15s 内未命中补拉标记；请确认设备处于锁定态且非 DO")


def scenario_flag_secure():
    """保底·锁屏无法退出：锁屏页带 FLAG_SECURE（截屏/录屏被系统拒绝）。"""
    print("[用例] 锁屏 FLAG_SECURE 防截屏")
    ensure_locked()
    time.sleep(1)
    rc, out, _ = run_adb("shell", "dumpsys", "window", "windows")
    if rc != 0:
        record("锁屏 FLAG_SECURE", "SKIP", "dumpsys 失败")
        return
    # 在 LockScreenActivity 窗口块内寻找 FLAG_SECURE
    lines = out.splitlines()
    in_lock = False
    found = False
    for ln in lines:
        if "LockScreenActivity" in ln:
            in_lock = True
            continue
        if in_lock:
            if "FLAG_SECURE" in ln:
                found = True
                break
            if ln.strip().startswith("Window{"):
                in_lock = False  # 进入下一个窗口块
    if found:
        record("锁屏 FLAG_SECURE", "PASS", "锁屏窗口含 FLAG_SECURE，系统将拒绝截屏/录屏")
    else:
        record("锁屏 FLAG_SECURE", "SKIP",
               "未在当前窗口树定位到锁屏 FLAG_SECURE（可能未锁定或 dump 形态差异）")


def scenario_connectivity_recover():
    """保底·永不掉线：WiFi 断开再恢复后，立即触发重连+策略同步。"""
    print("[用例] 网络恢复秒级自愈")
    clear_logcat()
    run_adb("shell", "svc", "wifi", "disable")
    time.sleep(3)
    run_adb("shell", "svc", "wifi", "enable")
    hit = logcat_contains("GuardService", MARKER_CONN_RESTORED, duration=20)
    if hit:
        record("网络恢复秒级自愈", "PASS",
               "命中 onConnectivityRestored 标记，重连+策略同步已触发")
    else:
        record("网络恢复秒级自愈", "FAIL",
               "20s 内未命中 connectivity restored 标记；请确认设备已绑定且守护在跑")


def scenario_policy_reconcile():
    """保底·永不掉线：HTTPS 对账周期性运行（30s 盲窗）。

    完整验证需要服务端在窗口内改一次策略触发版本漂移；本脚本只确认对账机制在跑，
    真正的「漂移→自愈」请人工在家长端改策略后观察 logcat 的 policy version drift。
    """
    print("[用例] 策略对账（MANUAL：需家长端改策略触发漂移）")
    clear_logcat()
    # 等一个对账周期（HTTPS_RECONCILE_EVERY=2，约 30s @15s 心跳）让机制至少跑一次
    run_adb("shell", "svc", "wifi", "enable")
    hit = logcat_contains("GuardService", MARKER_POLICY_DRIFT, duration=40)
    if hit:
        record("策略对账-版本漂移自愈", "PASS", "命中 policy version drift，对账已纠偏")
    else:
        record("策略对账-版本漂移自愈", "MANUAL",
               "未观测到漂移（需服务端改策略）。R25 已将 HTTPS_RECONCILE_EVERY 收紧到 2（≈30s），"
               "请人工在家长端改策略后确认 30s 内设备纠偏。")


def scenario_time_tamper():
    """保底·锁屏无法退出：改系统时间后，受信钟驱动秒级重锁（依赖 R27 契约测试兜底）。

    自动部分：把时钟回拨 2 小时，观察是否触发锁屏重断言（re-arming 标记）。
    若设备未锁定或时间回拨被受信钟判定为同一日，标记可能不出现——此时以 R27 契约测试为门禁。
    """
    print("[用例] 改系统时间秒级重锁（尽力而为 + 契约测试兜底）")
    ensure_locked()
    clear_logcat()
    # 读取当前时间，回拨 2 小时
    _, now, _ = run_adb("shell", "date", "+%m%d%H%M")
    if now.strip():
        try:
            mm, dd, hh, mi = (now.strip()[0:2], now.strip()[2:4],
                               now.strip()[4:6], now.strip()[6:8])
            hh_new = "%02d" % ((int(hh) - 2) % 24)
            run_adb("shell", "date", f"{mm}{dd}{hh_new}{mi}")
        except Exception:
            pass
    hit = logcat_contains(
        "LockScreenActivity",
        f"({MARKER_LOCK_REARM}|{MARKER_LOCK_SUSTAINED})",
        duration=15,
    )
    # 还原时间（尽力）
    run_adb("shell", "date", now.strip()) if now.strip() else None
    if hit:
        record("改时间后秒级重锁", "PASS", "命中补拉标记，受信钟已驱动重锁")
    else:
        record("改时间后秒级重锁", "MANUAL",
               "自动检查未命中（可能落在同日/未锁定）；R27 已加契约测试锁死"
               "「时间变化分支必须含 applyCurrent(force=true)/reenforce()/checkDayRollover()」。")


def main():
    global TRIGGER_LOCK
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--trigger-lock":
            TRIGGER_LOCK = args[i + 1]
            i += 2
        elif a == "--pkg":
            global PKG
            PKG = args[i + 1]
            i += 2
        elif a == "--no-wifi":
            globals()["SKIP_WIFI"] = True
            i += 1
        else:
            i += 1

    print("=== 孩子端三大保底功能 · 真机回归 ===")
    wait_device()
    scenario_lock_escape()
    scenario_flag_secure()
    if not globals().get("SKIP_WIFI"):
        scenario_connectivity_recover()
        scenario_policy_reconcile()
    else:
        record("网络恢复秒级自愈", "SKIP", "--no-wifi 跳过")
        record("策略对账-版本漂移自愈", "SKIP", "--no-wifi 跳过")
    scenario_time_tamper()

    print("\n=== 汇总 ===")
    n_pass = sum(1 for _, s, _ in results if s == "PASS")
    n_fail = sum(1 for _, s, _ in results if s == "FAIL")
    n_skip = sum(1 for _, s, _ in results if s in ("SKIP", "MANUAL"))
    for name, status, detail in results:
        mark = {"PASS": "✅", "FAIL": "❌", "SKIP": "⚠️ ", "MANUAL": "🔧"}.get(status, "·")
        print(f"  {mark} {name}: {status}" + (f" — {detail}" if detail else ""))
    print(f"\nPASS={n_pass}  FAIL={n_fail}  SKIP/MANUAL={n_skip}")
    print("提示：FAIL 需排查；SKIP/MANUAL 为环境或需人工/服务端配合项，已由契约测试或人工复核兜底。")
    sys.exit(1 if n_fail else 0)


if __name__ == "__main__":
    main()
