#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最小 dex 反汇编器：认落点用。

    python3 tools/dexq.py qq.apk com.tencent.mobileqq.aio.input.reply.i
    python3 tools/dexq.py qq.apk 类名...      # 一次可以查多个

打印每个方法用到的字符串、读写的字段、调用的方法。换 QQ 版本时靠它按签名找新名字：
上游 QAuxiliary 用 DexKit 在运行时搜（「哪个方法参数是 AIOMsgItem、用到 mContext 和 senderUid」），
这里离线搜同一件事，搜到就把名字写进 tools/symbols.txt 和代码。

只用标准库，复用 dexcheck.py 的 dex 解析。一次只读一个 dex（37 个全读进内存会 OOM）。
"""
import struct
import sys
import zipfile

from dexcheck import Dex, uleb

# 每条指令占几个 16-bit 码元，按 opcode 查。
W = [1] * 256
for lo, hi, w in (
    (0x02, 0x02, 2), (0x03, 0x03, 3), (0x05, 0x05, 2), (0x06, 0x06, 3),
    (0x08, 0x08, 2), (0x09, 0x09, 3), (0x13, 0x13, 2), (0x14, 0x14, 3),
    (0x15, 0x16, 2), (0x17, 0x17, 3), (0x18, 0x18, 5), (0x19, 0x19, 2),
    (0x1a, 0x1a, 2), (0x1b, 0x1b, 3), (0x1c, 0x1c, 2), (0x1f, 0x20, 2),
    (0x22, 0x23, 2), (0x24, 0x26, 3), (0x29, 0x29, 2), (0x2a, 0x2c, 3),
    (0x2d, 0x3d, 2), (0x44, 0x6d, 2), (0x6e, 0x72, 3), (0x74, 0x78, 3),
    (0xd0, 0xe2, 2),
):
    for op in range(lo, hi + 1):
        W[op] = w


def disasm(d, code_off):
    """线性扫一遍 code_item -> (用到的字符串, 读写的字段, 调用的方法)。

    只看 const-string / 字段读写 / invoke 三类，其余按宽度跳过。
    撞上 switch / fill-array-data 载荷就停（那东西只出现在指令流末尾）。
    """
    insns_size = struct.unpack_from("<I", d.b, code_off + 12)[0]
    p, end = code_off + 16, code_off + 16 + 2 * insns_size
    t, s = d.types(), d.strings()
    strs, flds, calls = set(), set(), set()
    while p + 1 < end:
        u = struct.unpack_from("<H", d.b, p)[0]
        op = u & 0xFF
        if op == 0x00 and (u >> 8) & 0xFF != 0:
            break
        if op == 0x1A:
            strs.add(s[struct.unpack_from("<H", d.b, p + 2)[0]])
        elif op == 0x1B:
            strs.add(s[struct.unpack_from("<I", d.b, p + 2)[0]])
        elif 0x52 <= op <= 0x6D:
            c, ty, n = struct.unpack_from("<HHI", d.b, d.ofl + 8 * struct.unpack_from("<H", d.b, p + 2)[0])
            flds.add("%s#%s:%s" % (d.internal(t[c]), s[n], d.internal(t[ty])))
        elif 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:
            c, _, n = struct.unpack_from("<HHI", d.b, d.omth + 8 * struct.unpack_from("<H", d.b, p + 2)[0])
            calls.add("%s#%s" % (d.internal(t[c]), s[n]))
        p += 2 * W[op]
    return strs, flds, calls


def methods_of(d, cls_name):
    """-> [(方法名, 签名, code_off)]；code_off 为 0 表示 abstract / native。"""
    t, s = d.types(), d.strings()
    for i in range(d.ncls):
        cidx, _, _, _, _, _, cdoff, _ = struct.unpack_from("<IIIIIIII", d.b, d.ocl + 32 * i)
        if d.internal(t[cidx]) != cls_name or not cdoff:
            continue
        p = cdoff
        nsf, p = uleb(d.b, p)
        nif, p = uleb(d.b, p)
        ndm, p = uleb(d.b, p)
        nvm, p = uleb(d.b, p)
        for _ in range(nsf + nif):
            _, p = uleb(d.b, p)
            _, p = uleb(d.b, p)
        out = []
        # DEX 的 direct_methods 与 virtual_methods 各有自己的 delta 编码基点。
        for count in (ndm, nvm):
            idx = 0
            for _ in range(count):
                di, p = uleb(d.b, p)
                idx += di
                _, p = uleb(d.b, p)
                co, p = uleb(d.b, p)
                if idx >= d.nmth:
                    break
                c, pr, n = struct.unpack_from("<HHI", d.b, d.omth + 8 * idx)
                if n >= len(s) or pr >= d.npro:
                    continue
                out.append((s[n], d.proto(pr), co))
        return out
    return None


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    apk, targets = sys.argv[1], sys.argv[2:]
    zf = zipfile.ZipFile(apk)
    dex_names = sorted((n for n in zf.namelist() if n.endswith(".dex")), key=lambda n: (len(n), n))
    todo = set(targets)
    for name in dex_names:
        d = Dex(zf.read(name), name)
        for cls in sorted(todo):
            try:
                ms = methods_of(d, cls)
            except Exception:      # 单个类解析炸了不连累其余的
                continue
            if ms is None:
                continue
            todo.discard(cls)
            print("===== %s  [%s]" % (cls, name))
            for mname, sig, co in sorted(ms):
                if not co:
                    print("  %-10s %-40s (abstract/native)" % (mname, sig))
                    continue
                strs, flds, calls = disasm(d, co)
                print("  %-10s %s" % (mname, sig))
                if strs:
                    print("        str:  %s" % ", ".join(sorted(strs)[:12]))
                if flds:
                    print("        fld:  %s" % ", ".join(sorted(flds)[:8]))
                if calls:
                    print("        call: %s" % ", ".join(sorted(c.split("#")[-1] for c in calls)[:14]))
        del d
        if not todo:
            break
    if todo:
        print("没找到: %s" % ", ".join(sorted(todo)))


if __name__ == "__main__":
    main()
