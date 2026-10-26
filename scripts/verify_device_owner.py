# -*- coding: utf-8 -*-
"""
设备所有者（Device Owner）部署验收脚本。

## 为什么需要它
防卸载 / 静默安装 / 禁止恢复出厂等核心承诺**全部依赖**设备所有者(DO)：
未开 DO 时这些能力在系统层面不可用，"孩子可自卸"即成立。
因此发布包把 DO 预置设为装机强制首步，本脚本用于装机后**客观验收**是否就位——
不靠肉眼看界面，而是解析 `adb shell dumpsys device_policy` 的真实输出。

## 它查什么
1. adb 可用、目标设备已连接；
2. `dumpsys device_policy` 的 `Device Owner:` 段（找不到则看 `Profile Owner:`）中，
   admin 组件是否属于预期包名（默认 `com.padguard.child`）。

## 用法
    python scripts/verify_device_owner.py [expected_package] [adb_path] [-s SERIAL]

位置参数（均可选）：
    expected_package  预期成为 DO/PO 的包名，默认 com.padguard.child
    adb_path          自定义 adb 可执行文件路径

选项：
    -s SERIAL         指定 adb 设备序列号（多设备时必填）

环境变量（优先于位置参数）：
    DO_PACKAGE        预期包名
    ADB               adb 可执行文件
    ANDROID_SERIAL    目标设备序列号
"""

import os
import re
import subprocess
import sys

DEFAULT_PACKAGE = "com.padguard.child"


def find_adb(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get("ADB")
    if env:
        return env
    for cand in ("adb", "adb.exe"):
        # 仅靠 shutil.which 在 PATH 里找；找不到就返回候选名让 subprocess 报错
        from shutil import which
        hit = which(cand)
        if hit:
            return hit
    return "adb"


def run(cmd):
    """执行命令，返回 (returncode, stdout_text, stderr_text)。"""
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=30, text=True)
        return proc.returncode, proc.stdout, proc.stderr
    except FileNotFoundError:
        return 127, "", "command not found: %s" % cmd[0]
    except subprocess.TimeoutExpired:
        return 124, "", "timeout after 30s"
    except Exception as e:  # 其他异常（编码、权限等）
        return -1, "", str(e)


def parse_device_policy(text, expected_pkg):
    """
    从 dumpsys 输出中解析 DO / PO 的 admin 包名。

    典型输出：
        Device Owner:
          admin=ComponentInfo{com.padguard.child/com.padguard.child.receiver.PadGuardDeviceAdminReceiver}
          name=
        Profile Owner:
          admin=ComponentInfo{...}

    无 DO 时常见：
        Device Owner:
          admin=null

    返回 dict: {"device_owner": pkg_or_None, "profile_owner": pkg_or_None,
                "raw_owner_block": str, "raw_profile_block": str}
    """
    owner_re = re.compile(r"admin\s*=\s*(?:ComponentInfo\{([^/}\s]+)|null)", re.IGNORECASE)
    result = {"device_owner": None, "profile_owner": None,
              "raw_owner_block": "", "raw_profile_block": ""}
    current = None
    for line in text.splitlines():
        low = line.strip().lower()
        if low.startswith("device owner:"):
            current = "device_owner"
            continue
        if low.startswith("profile owner:"):
            current = "profile_owner"
            continue
        if low.startswith("active admins:") or low.startswith("enabled admins:"):
            # 进入其它段，停止捕获当前段
            current = None
            continue
        if current in ("device_owner", "profile_owner"):
            if current == "device_owner":
                result["raw_owner_block"] += line + "\n"
            else:
                result["raw_profile_block"] += line + "\n"
            m = owner_re.search(line)
            if m:
                # group(1) 有值为包名；为 null 时保持 None
                if m.group(1):
                    result[current] = m.group(1)
    return result


def main():
    args = sys.argv[1:]
    serial = None
    positional = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "-s":
            i += 1
            serial = args[i] if i < len(args) else None
        else:
            positional.append(a)
        i += 1

    expected = (os.environ.get("DO_PACKAGE")
                or (positional[0] if len(positional) > 0 else None)
                or DEFAULT_PACKAGE)
    adb = find_adb(positional[1] if len(positional) > 1 else None)
    if not serial:
        serial = os.environ.get("ANDROID_SERIAL")

    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        mark = "PASS" if ok else "FAIL"
        print("[%s] %s%s" % (mark, name, ("  -- " + detail) if detail else ""))

    print("=" * 72)
    print("PadGuard 设备所有者(DO)部署验收  expected=%s" % expected)
    print("=" * 72)

    # 1. adb 可用
    rc, out, err = run([adb, "version"])
    check("adb 可执行", rc == 0, (out.splitlines()[0] if out else err[:120]))

    # 2. 设备已连接
    rc, out, err = run([adb, "devices"])
    if rc != 0:
        check("设备已连接", False, err[:160])
        return finish(results)
    devices = [l.split("\t")[0] for l in out.splitlines()
               if l.strip() and not l.startswith("List") and "\t" in l]
    if not devices:
        check("设备已连接", False, "未检测到任何设备（adb devices 为空），请先连接并授权 USB 调试")
        return finish(results)
    if serial and serial not in devices:
        check("指定序列号在线", False, "序列号 %s 不在已连接设备列表：%s" % (serial, devices))
        return finish(results)
    check("设备已连接", True, "在线设备：%s" % (serial or devices[0]))

    target = serial or devices[0]
    # 3. 拉取 device_policy 状态
    rc, out, err = run([adb, "-s", target, "shell", "dumpsys", "device_policy"])
    if rc != 0:
        check("dumpsys device_policy 可读", False, err[:160])
        return finish(results)
    check("dumpsys device_policy 可读", True, "已读取 %d 行" % len(out.splitlines()))

    parsed = parse_device_policy(out, expected)

    # 4. 核心断言：预期包是 Device Owner（或 Profile Owner）
    do_pkg = parsed["device_owner"]
    po_pkg = parsed["profile_owner"]
    is_do = (do_pkg == expected)
    is_po = (po_pkg == expected)
    if is_do:
        check("设备所有者已就位（核心承诺可用）", True,
              "Device Owner = %s" % expected)
    elif is_po:
        check("设备所有者(Device Owner)未就位，但工作资料所有者(Profile Owner)可用", True,
              "Profile Owner = %s（部分 DO 专属能力受限，请确认部署目标）" % expected)
    else:
        detail = "实际 Device Owner=%s，Profile Owner=%s" % (do_pkg or "null", po_pkg or "null")
        check("设备所有者已就位（核心承诺可用）", False, detail)
        if do_pkg is None and po_pkg is None:
            print("    → 该设备当前没有任何 DO/PO。请按装机指引执行 "
                  "`adb shell dpm set-device-owner --user current <组件>` 或扫码预置。")
        elif do_pkg != expected:
            print("    → DO/PO 属于其它包（%s），不是本应用。请确认安装的是正确的 PadGuard 发布包。"
                  % (do_pkg or po_pkg))

    # 5. 辅助信息：admin 组件是否存活（DO 段里能看到具体组件即代表激活）
    if do_pkg == expected:
        check("DO admin 组件已激活", "ComponentInfo{" in parsed["raw_owner_block"],
              parsed["raw_owner_block"].strip().splitlines()[0] if parsed["raw_owner_block"].strip() else "")

    return finish(results)


def finish(results):
    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    print("-" * 72)
    print("合计 %d 项，通过 %d 项，失败 %d 项" % (total, passed, total - passed))
    if passed != total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  %s" % (name, detail))
    # 核心项未通过时.exit(1)，便于 CI / 发布流水线拦截
    core_ok = any(name.startswith("设备所有者已就位") and ok for name, ok, _ in results)
    return 0 if core_ok else 1


if __name__ == "__main__":
    sys.exit(main())
