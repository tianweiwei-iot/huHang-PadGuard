# -*- coding: utf-8 -*-
"""精确校验 APK 是否满足 16 KB 页大小要求（两个维度）。

维度一 ELF：每个 64 位 .so 的 PT_LOAD 段 p_align >= 0x4000 且 p_vaddr ≡ p_offset (mod 0x4000)。
维度二 ZIP：未压缩（STORED）的 .so 其「数据起始偏移」必须 16 KB 对齐。
            数据偏移 = 本地文件头偏移 + 30 + 文件名长度 + 本地 extra 长度
            （注意：不能直接用 header_offset，必须补上头部与 extra 长度）

用法: python check_elf_16k.py <apk 路径>
"""
import os
import struct
import sys
import zipfile

PT_LOAD = 1
PAGE = 16384


def parse_elf_loads(data):
    """返回 [(p_offset, p_vaddr, p_align), ...]（仅 PT_LOAD）；非 ELF 返回 None。"""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
        phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
        phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
    else:
        phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
        phentsize = struct.unpack_from(endian + "H", data, 0x2A)[0]
        phnum = struct.unpack_from(endian + "H", data, 0x2C)[0]
    segs = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + phentsize > len(data):
            break
        if struct.unpack_from(endian + "I", data, off)[0] != PT_LOAD:
            continue
        if is64:
            p_offset = struct.unpack_from(endian + "Q", data, off + 0x08)[0]
            p_vaddr = struct.unpack_from(endian + "Q", data, off + 0x10)[0]
            p_align = struct.unpack_from(endian + "Q", data, off + 0x30)[0]
        else:
            p_offset = struct.unpack_from(endian + "I", data, off + 0x04)[0]
            p_vaddr = struct.unpack_from(endian + "I", data, off + 0x08)[0]
            p_align = struct.unpack_from(endian + "I", data, off + 0x1C)[0]
        segs.append((p_offset, p_vaddr, p_align))
    return segs


def local_data_offset(f, info):
    """读本地文件头，返回该条目的数据起始偏移。"""
    f.seek(info.header_offset)
    sig = f.read(4)
    if sig != b"PK\x03\x04":
        return None
    hdr = f.read(26)
    name_len = struct.unpack_from("<H", hdr, 22)[0]
    extra_len = struct.unpack_from("<H", hdr, 24)[0]
    return info.header_offset + 30 + name_len + extra_len


def main():
    apk = sys.argv[1]
    print("APK : %s" % apk)
    print("大小: %.2f MB" % (os.path.getsize(apk) / 1024.0 / 1024.0))
    print()

    zf = zipfile.ZipFile(apk)
    sos = sorted(
        (i for i in zf.infolist() if i.filename.endswith(".so")),
        key=lambda x: x.filename,
    )
    print("APK 内 native 库数量: %d" % len(sos))
    print("覆盖 ABI: %s" % sorted({n.split("/")[1] for n in (i.filename for i in sos)}))
    print()

    print("%-44s %-8s %-12s %-8s %-12s %s" % (
        "lib 路径", "压缩", "数据偏移", "16K?", "p_align", "结论"))
    print("-" * 108)

    bad = []
    with open(apk, "rb") as f:
        for info in sos:
            data = zf.read(info.filename)
            segs = parse_elf_loads(data)
            if segs is None:
                print("%-44s 非 ELF（跳过）" % info.filename)
                continue
            stored = info.compress_type == 0
            doff = local_data_offset(f, info) if stored else None
            zip_ok = (doff % PAGE == 0) if doff is not None else None

            min_align = min(a for _, _, a in segs)
            elf_ok = min_align >= PAGE and all(
                (v - o) % PAGE == 0 for o, v, _ in segs)
            ok = elf_ok and (zip_ok is not False)

            reasons = []
            if not elf_ok:
                reasons.append("ELF PT_LOAD p_align=%d(%s) 或 vaddr/offset 不同余"
                               % (min_align, hex(min_align)))
            if zip_ok is False:
                reasons.append("zip 数据偏移 0x%x 未 16 KB 对齐" % doff)
            if not ok:
                bad.append((info.filename, reasons))

            print("%-44s %-8s %-12s %-8s %-12s %s" % (
                info.filename,
                "STORED" if stored else "DEFLATE",
                ("0x%x" % doff) if doff is not None else "-",
                ("是" if zip_ok else "否") if zip_ok is not None else "n/a",
                "0x%x" % min_align,
                "OK" if ok else "*** 不兼容 ***",
            ))
            for r in reasons:
                print("       └─ %s" % r)

    print()
    print("=" * 108)
    print("16 位/64 位 ABI 说明：16 KB 页大小要求仅约束 64 位 ABI（arm64-v8a / x86_64）。")
    print()
    if bad:
        print("结论：存在 %d 个不满足要求的 native 库：" % len(bad))
        for n, r in bad:
            print("  - %s：%s" % (n, "；".join(r)))
    else:
        print("结论：APK 内全部 native 库均满足 ELF 16 KB 对齐 + ZIP 16 KB 对齐，")
        print("      符合 Android 15+ 16 KB 内存页设备要求。")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
