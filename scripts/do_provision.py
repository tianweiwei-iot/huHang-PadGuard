# -*- coding: utf-8 -*-
"""
孩子端 Device Owner (DO) 一键部署 + 验收（跨平台，自动识别包名/组件）。

## 为什么另写一个（与 do-provisioning.ps1 / verify_device_owner.py 的关系）
- `do-provisioning.ps1` 把包名/接收器**写死**成 `com.padguard.child.debug`，
  对正式发布包（AndroidManifest 用 `BuildConfig.APPLICATION_ID`，debug 才带 `.debug`）
  会变成「命令执行成功但 DO 落在错误包上」，家长端看到的仍是「非 DO 软管控」——
  这正是三大保底功能（不可卸载 / 物理锁死 / 改时间绕不过）全部失效的根因。
- `verify_device_owner.py` 只验收、不下发。
- 本脚本把两者合一，且**从已装 APK 实时推断真实包名与 DeviceAdmin 组件**，
  不再依赖手填的常量；下发出错时明确告诉你是「已设锁屏密码」还是「已登录非出厂账号」。

## 它做了什么（按顺序，任一步 FAIL 即退出非零码，便于流水线拦截）
1. 找 adb（PATH / ANDROID_HOME / 常见 SDK 路径）；
2. 列设备，强制只连一台；
3. 从设备已装包里推断 PadGuard 孩子端真实包名（可 --package 覆盖）；
4. 从 `dumpsys package` 实时解析 DeviceAdmin 组件（可 --receiver 覆盖，否则按约定兜底）；
5. 前置体检：是否已锁屏密码 / 已登录非出厂账号（仅告警，不阻断，因为部分 ROM 仍允许设 DO）；
6. 幂等下发 `dpm set-device-owner --user current <pkg>/<receiver>`：
   - 已是 DO 且是本包 → 直接判 ALREADY_ACTIVE，不重复下发；
7. 验收：解析 `dumpsys device_policy` 的 Device Owner / Profile Owner 段；
8. 打印 PASS/FAIL 汇总并以正确退出码结束。

## 用法
    python scripts/do_provision.py
    python scripts/do_provision.py --package com.padguard.child
    python scripts/do_provision.py -s 3ZPBB24B20206385
    python scripts/do_provision.py --revert          # 仅调试：remove-active-admin

环境变量（优先于位置/选项）：ADB / ANDROID_SERIAL / DO_PACKAGE。
"""
import argparse
import os
import re
import subprocess
import sys

# 兜底约定的接收器类名（与 AndroidManifest 中 android:name 对齐；
# 真正以设备 dumpsys 解析结果为准，这里只是解析失败时的最后兜底）。
FALLBACK_RECEIVER_SIMPLE = "receiver.PadGuardDeviceAdminReceiver"


def find_adb(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get("ADB")
    if env:
        return env
    for cand in ("adb", "adb.exe"):
        from shutil import which
        hit = which(cand)
        if hit:
            return hit
    return "adb"


def run(cmd, timeout=60):
    """执行命令，返回 (returncode, stdout, stderr)。超时/异常均安全返回。"""
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=timeout, text=True)
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError:
        return 127, "", "command not found: %s" % cmd[0]
    except subprocess.TimeoutExpired:
        return 124, "", "timeout after %ds" % timeout
    except Exception as e:  # 编码/权限等
        return -1, "", str(e)


def detect_package(adb, serial):
    """从已装包里推断 PadGuard 孩子端真实包名。优先精确匹配 com.padguard.child*。"""
    rc, out, err = run([adb, "-s", serial, "shell", "pm", "list", "packages"])
    if rc != 0:
        return None, "pm list packages failed: " + err[:160]
    pkgs = [l.split(":")[1].strip() for l in out.splitlines()
            if l.startswith("package:") and "padguard.child" in l]
    if not pkgs:
        return None, "设备上未安装任何 com.padguard.child* 包，请先安装孩子端 APK"
    # 精确优先：最先匹配不带 .debug 的正式包
    exact = [p for p in pkgs if p == "com.padguard.child"]
    if exact:
        return exact[0], ""
    # 其次：带 flavor 的（如 com.padguard.child.debug）
    return pkgs[0], ""


def detect_receiver(adb, serial, package):
    """从 dumpsys package 解析真实的 DeviceAdmin 组件（完整 <pkg>/<cls> 形式）。"""
    rc, out, err = run([adb, "-s", serial, "shell", "dumpsys", "package", package])
    if rc != 0:
        return None, "dumpsys package failed: " + err[:160]
    # 形如：com.padguard.child/.receiver.PadGuardDeviceAdminReceiver
    # 或完整：com.padguard.child.receiver.PadGuardDeviceAdminReceiver
    m = re.search(
        r"(com\.padguard\.child[\w.]*)/(?:\.?[\w.]*)?PadGuardDeviceAdminReceiver", out)
    if m:
        return m.group(0), ""  # group(0) 已是完整组件串，可直接用于 dpm
    # 兜底：按约定拼（debug 包名自带 .debug，直接拼类名段即可）
    return "%s/%s" % (package, FALLBACK_RECEIVER_SIMPLE), ""


def parse_dpm_owners(text, expected_pkg):
    """解析 dumpsys device_policy 的 DO/PO 段，返回 (device_owner_pkg, profile_owner_pkg, raw_do)。"""
    owner_re = re.compile(r"admin\s*=\s*(?:ComponentInfo\{([^/}\s]+)|null)", re.IGNORECASE)
    do_pkg = None
    po_pkg = None
    raw_do = ""
    current = None
    for line in text.splitlines():
        low = line.strip().lower()
        if low.startswith("device owner:"):
            current = "do"
            continue
        if low.startswith("profile owner:"):
            current = "po"
            continue
        if low.startswith("active admins:") or low.startswith("enabled admins:"):
            current = None
            continue
        if current in ("do", "po"):
            if current == "do":
                raw_do += line + "\n"
            m = owner_re.search(line)
            if m and m.group(1):
                if current == "do":
                    do_pkg = m.group(1)
                else:
                    po_pkg = m.group(1)
    return do_pkg, po_pkg, raw_do


def preflight(adb, serial):
    """前置体检：锁屏密码 / 非出厂账号。仅告警，不阻断（部分 ROM 仍允许设 DO）。"""
    notes = []
    rc, out, _ = run([adb, "-s", serial, "shell", "locksettings", "get-password-type"])
    if rc == 0:
        t = out.strip()
        if t and t not in ("none", "NONE", ""):
            notes.append("已设锁屏密码（类型=%s）；部分 ROM 会阻止 DO，请先移除再重试" % t)
    # 是否有非出厂账号（粗判：accounts 数 > 0 且非我们自建）
    rc, out, _ = run([adb, "-s", serial, "shell", "dumpsys", "account"])
    if rc == 0 and "Account {" in out:
        notes.append("设备上检测到已登录账号；若为非厂商/非出厂账号，DO 可能被拒")
    return notes


def main():
    ap = argparse.ArgumentParser(description="PadGuard 孩子端 Device Owner 部署+验收")
    ap.add_argument("--package", help="显式指定孩子端包名（默认从设备自动推断）")
    ap.add_argument("--receiver", help="显式指定 DeviceAdmin 组件（默认从 dumpsys 解析）")
    ap.add_argument("--adb", help="adb 路径")
    ap.add_argument("-s", "--serial", help="设备序列号（多设备时必填）")
    ap.add_argument("--revert", action="store_true", help="仅调试：remove-active-admin")
    args = ap.parse_args()

    adb = find_adb(args.adb)
    serial = args.serial or os.environ.get("ANDROID_SERIAL")

    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print("[%s] %s%s" % ("PASS" if ok else "FAIL", name,
                              ("  -- " + detail) if detail else ""))
        return ok

    print("=" * 72)
    print("PadGuard 孩子端 Device Owner 部署")
    print("=" * 72)

    rc, out, err = run([adb, "version"])
    if not check("adb 可执行", rc == 0, (out.splitlines()[0] if out else err[:120])):
        return finish(results)

    rc, out, err = run([adb, "devices"])
    if rc != 0:
        check("设备已连接", False, err[:160])
        return finish(results)
    devices = [l.split("\t")[0] for l in out.splitlines()
               if l.strip() and not l.startswith("List") and "\t" in l]
    if not devices:
        check("设备已连接", False, "adb devices 为空，请连接并授权 USB 调试")
        return finish(results)
    if serial and serial not in devices:
        check("指定序列号在线", False, "序列号 %s 不在：%s" % (serial, devices))
        return finish(results)
    if not serial:
        if len(devices) > 1:
            check("仅连一台设备", False, "检测到多台：%s，请用 -s 指定" % devices)
            return finish(results)
        serial = devices[0]
    check("目标设备在线", True, serial)

    # 推断包名
    package = args.package or os.environ.get("DO_PACKAGE")
    if not package:
        package, derr = detect_package(adb, serial)
        if not package:
            check("推断孩子端包名", False, derr)
            return finish(results)
    else:
        # 覆盖模式下仍确认已安装
        rc, out, _ = run([adb, "-s", serial, "shell", "pm", "list", "packages", package])
        if rc != 0 or package not in out:
            check("包已安装(%s)" % package, False, "设备上未安装该包，请先安装 APK")
            return finish(results)
    check("孩子端包名", True, package)

    # 推断/校验接收器
    if args.receiver:
        component = args.receiver if "/" in args.receiver else "%s/%s" % (package, args.receiver)
    else:
        component, rerr = detect_receiver(adb, serial, package)
        if not component:
            check("DeviceAdmin 组件", False, rerr)
            return finish(results)
    check("DeviceAdmin 组件", True, component)

    # 前置体检（仅告警）
    for n in preflight(adb, serial):
        print("  [提醒] %s" % n)

    if args.revert:
        print(">> 撤销设备管理员（调试用，生产机请用恢复出厂彻底移除 DO）...")
        rc, out, err = run([adb, "-s", serial, "shell", "dpm", "remove-active-admin",
                            component, "-n"])
        ok = rc == 0
        check("remove-active-admin", ok, (out + err)[:200] if not ok else "")
        return finish(results)

    # 幂等：先查是否已是本包 DO
    rc, out, err = run([adb, "-s", serial, "shell", "dumpsys", "device_policy"])
    if rc == 0:
        do_pkg, po_pkg, _ = parse_dpm_owners(out, package)
        if do_pkg == package:
            check("Device Owner 已就位", True, "%s 已是 DO（幂等跳过下发）" % package)
            return finish(results)
        if do_pkg and do_pkg != package:
            check("Device Owner 归属", False,
                  "已有其它 DO=%s，非本包；请先 remove-active-admin 或恢复出厂" % do_pkg)
            return finish(results)

    # 下发
    print(">> 执行 dpm set-device-owner --user current %s ..." % component)
    rc, out, err = run([adb, "-s", serial, "shell", "dpm", "set-device-owner",
                        "--user", "current", component])
    if rc == 0 and "error" not in (out + err).lower():
        check("set-device-owner 下发", True, "命令返回成功")
    else:
        detail = (out + err).strip()[:300] or "未知错误"
        check("set-device-owner 下发", False, detail)
        print("    常见原因：已设锁屏密码 / 已登录非出厂账号 / 已是非 DO 设备管理员。")
        print("    对策：移除锁屏密码与多余账号后重试；或先 remove-active-admin。")
        return finish(results)

    # 验收
    rc, out, err = run([adb, "-s", serial, "shell", "dumpsys", "device_policy"])
    if rc != 0:
        check("device_policy 可读", False, err[:160])
        return finish(results)
    do_pkg, po_pkg, raw_do = parse_dpm_owners(out, package)
    if do_pkg == package:
        check("设备所有者已就位（核心承诺可用）", True, "Device Owner = %s" % package)
        check("DO admin 组件已激活", "ComponentInfo{" in raw_do,
              raw_do.strip().splitlines()[0] if raw_do.strip() else "")
        print("    后续：打开孩子端完成绑定；非 DO 下无法物理锁死的能力现已成立。")
    elif po_pkg == package:
        check("设备所有者未就位，但 Profile Owner 可用", True,
              "Profile Owner = %s（部分 DO 专属能力受限）" % package)
    else:
        detail = "实际 DO=%s，PO=%s" % (do_pkg or "null", po_pkg or "null")
        check("设备所有者已就位（核心承诺可用）", False, detail)
        print("    → DO 未生效。请确认下发命令是否真成功，或设备是否有其它限制。")
        return finish(results)

    return finish(results)


def finish(results):
    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    print("-" * 72)
    print("合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    if passed != total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  %s" % (name, detail))
    core_ok = any(name.startswith("设备所有者已就位") and ok for name, ok, _ in results)
    if args_core_override(results):
        return 0
    return 0 if core_ok else 1


def args_core_override(results):
    """幂等场景：已是本包 DO 也算成功（core 项名含「已就位」或「幂等」）。"""
    for name, ok, _ in results:
        if ok and ("已就位" in name or "幂等" in name):
            return True
    return False


if __name__ == "__main__":
    sys.exit(main())
