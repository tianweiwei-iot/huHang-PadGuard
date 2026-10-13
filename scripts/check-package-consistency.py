#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
W61 防复发闸门：包名一致性检查。

背景（审查 W61）：家长端曾有四套包名并存（parent/ 目录下声明
com.padguard.presentation.* / com.padguard.data.* / com.padguard.domain.* /
com.padguard.parent.*），编译正常但 IDE 导航/重构/按包检索全部失灵。
批次 K 已一次性对齐为「package 声明 == 物理目录」，本脚本防止回归。

判定规则：每个 .kt 文件的 package 声明必须等于其所在目录相对
src/main/java 的路径（点分）。这是 Android/Kotlin 的标准约定，
不一致即视为回归。

用法：
    python scripts/check-package-consistency.py [模块目录 ...]

    不带参数时检查仓库内两个 Android app；也可传任意模块目录
    （目录下需存在 app/src/main/java）。

退出码：0 = 全部一致；1 = 存在不一致（逐条打印路径与声明）。
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_MODULES = [
    os.path.join(REPO, "护航控制端"),
    os.path.join(REPO, "护航成长"),
]

PKG_RE = re.compile(r"(?m)^package\s+([\w.]+)")


def check_module(module_dir: str) -> int:
    # Android 模块的源码根可能是 java（老惯例）或 kotlin（纯 Kotlin 工程），两者都查
    roots = [
        os.path.join(module_dir, "app", "src", "main", "java"),
        os.path.join(module_dir, "app", "src", "main", "kotlin"),
    ]
    roots = [r for r in roots if os.path.isdir(r)]
    if not roots:
        print(f"[skip] {module_dir}: 无 app/src/main/{java|kotlin}")
        return 0
    bad = 0
    for java_root in roots:
        bad += check_root(java_root, module_dir)
    label = os.path.basename(module_dir)
    print(f"[{'OK' if bad == 0 else 'FAIL'}] {label}: {'包名与目录全部一致' if bad == 0 else f'{bad} 个文件不一致'}")
    return bad


def check_root(java_root: str, module_dir: str) -> int:
    bad = 0
    for dirpath, _dirnames, filenames in os.walk(java_root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            with open(p, "rb") as f:
                text = f.read().decode("utf-8", "replace")
            m = PKG_RE.search(text)
            physical = os.path.relpath(dirpath, java_root).replace("\\", ".")
            if not m:
                print(f"[FAIL] 无 package 声明: {os.path.relpath(p, REPO)}")
                bad += 1
            elif m.group(1) != physical:
                print(
                    f"[FAIL] {os.path.relpath(p, REPO)}\n"
                    f"       声明={m.group(1)}  物理={physical}"
                )
                bad += 1
    return bad


def main() -> int:
    modules = sys.argv[1:] or DEFAULT_MODULES
    total = sum(check_module(m) for m in modules)
    if total:
        print(f"\n合计 {total} 个文件包名不一致——请把 package 声明改为与目录一致（W61 对齐口径）。")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
