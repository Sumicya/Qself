#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""拿真 QQ 的 dex 校验 Qself 依赖的每个名字。

    python3 tools/dexcheck.py --apk qq.apk              # 全绿 = 退出码 0
    python3 tools/dexcheck.py --dex /tmp/qq/classes*.dex

符号表 tools/symbols.txt 的语法(每行一条,# 起注释):
    class   <内部名>        类必须存在,嵌套用 $,例:X$Inner
    class~  <后缀>          至少一个类名以该后缀结尾(QQ 挪包/改名也能活)
    member  <类>#<前缀>     至少一个方法名以该前缀开头,打印真签名
    member! <类>#<全名>     方法名必须完全相等
    field   <类>#<全名>     字段必须存在
    res     <资源名>        资源名出现在 resources.arsc
    text    <字面量>        字面量出现在 resources.arsc

只用标准库,一次只读一个 dex(37 个 dex 全读进内存会 OOM)。
"""
import argparse
import glob
import hashlib
import re
import os
import struct
import sys
import zipfile

# ---------------------------------------------------------------- dex 解析


def uleb(buf, p):
    r = s = 0
    while True:
        x = buf[p]
        p += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, p
        s += 7


class Dex:
    """只解我们要的四张表: string / type / proto / class_def, 外加 field/method id。"""

    def __init__(self, buf, name):
        self.b = buf
        self.name = name
        (self.nstr, self.ostr) = struct.unpack_from("<II", buf, 0x38)
        (self.ntyp, self.otyp) = struct.unpack_from("<II", buf, 0x40)
        (self.npro, self.opro) = struct.unpack_from("<II", buf, 0x48)  # proto_ids
        (self.nfld, self.ofl) = struct.unpack_from("<II", buf, 0x50)  # field_ids
        (self.nmth, self.omth) = struct.unpack_from("<II", buf, 0x58)  # method_ids
        (self.ncls, self.ocl) = struct.unpack_from("<II", buf, 0x60)
        self._str = None
        self._typ = None

    def strings(self):
        if self._str is None:
            b, out = self.b, []
            for i in range(self.nstr):
                off = struct.unpack_from("<I", b, self.ostr + 4 * i)[0]
                _, p = uleb(b, off)  # utf16 字符数,不是字节数
                e = b.index(0, p)
                out.append(b[p:e].decode("utf-8", "replace"))
            self._str = out
            del b, out
        return self._str

    def types(self):
        if self._typ is None:
            s = self.strings()
            self._typ = [
                s[struct.unpack_from("<I", self.b, self.otyp + 4 * i)[0]]
                for i in range(self.ntyp)
            ]
        return self._typ

    def internal(self, descriptor):
        """Lcom/x/Y; -> com.x.Y （符号表一律写点号）"""
        if descriptor.startswith("L") and descriptor.endswith(";"):
            descriptor = descriptor[1:-1]
        return descriptor.replace("/", ".")

    def classes(self):
        t = self.types()
        return {
            self.internal(t[struct.unpack_from("<I", self.b, self.ocl + 32 * i)[0]])
            for i in range(self.ncls)
        }

    def proto(self, idx):
        _, ret, params = struct.unpack_from("<III", self.b, self.opro + 12 * idx)
        t = self.types()
        args = []
        if params:
            n = struct.unpack_from("<I", self.b, params)[0]
            args = [t[struct.unpack_from("<H", self.b, params + 4 + 2 * i)[0]] for i in range(n)]
        return "{} {}".format(self.internal(t[ret]), ",".join(self.internal(a) for a in args))

    def members(self, wanted):
        """wanted: 关心的类名集合 -> {类: [(kind, name, sig)]}"""
        out = {k: [] for k in wanted}
        t = self.types()
        s = self.strings()
        for i in range(self.nfld):
            c, ty, n = struct.unpack_from("<HHI", self.b, self.ofl + 8 * i)
            cls = self.internal(t[c])
            if cls in out:
                out[cls].append(("field", s[n], self.internal(t[ty])))
        for i in range(self.nmth):
            c, pr, n = struct.unpack_from("<HHI", self.b, self.omth + 8 * i)
            cls = self.internal(t[c])
            if cls in out:
                out[cls].append(("method", s[n], self.proto(pr)))
        return out


# ---------------------------------------------------------------- 符号表


def load_symbols(path):
    syms = []
    with open(path, encoding="utf-8") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip("\n")
            i = line.find("#")  # 行首或空格后的 # 才是注释: member X#y 里的 # 是语法
            if i >= 0 and (i == 0 or line[i - 1].isspace()):
                line = line[:i]
            line = line.strip()
            if not line:
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                sys.exit("symbols.txt:%d 看不懂: %s" % (lineno, raw.rstrip()))
            syms.append((parts[0], parts[1].strip(), lineno))
    return syms


KINDS = ("class", "class~", "member", "member!", "field", "res", "text", "lit")


# ---------------------------------------------------------------- 主流程


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="QQ 安装包(可以是 qq.aa..qq.ae 拼起来的)")
    ap.add_argument("--dex", help="dex 目录或 glob,和 --apk 二选一")
    ap.add_argument("--symbols", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "symbols.txt"))
    ap.add_argument("--quiet", action="store_true", help="只打印 FAIL 和总结")
    ap.add_argument("--lint", help="顺带检查这些 .kt 源码里的反射名字都登记在表里了")
    a = ap.parse_args()

    if a.apk:
        h = hashlib.sha256()
        with open(a.apk, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        print("apk   %s  %.1f MB  sha256:%s" % (a.apk, os.path.getsize(a.apk) / 1e6, h.hexdigest()[:12]))
        zf = zipfile.ZipFile(a.apk)
        dex_names = sorted((n for n in zf.namelist() if n.endswith(".dex")), key=lambda n: (len(n), n))
        reader = lambda n: zf.read(n)  # noqa: E731
        arsc = zf.read("resources.arsc") if "resources.arsc" in zf.namelist() else b""
    else:
        paths = sorted(glob.glob(a.dex))
        if not paths:
            sys.exit("没有 dex: %s" % a.dex)
        reader = lambda n: open(n, "rb").read()  # noqa: E731
        dex_names = paths
        arsc = b""

    print("dex   %d 个" % len(dex_names))
    print("表    %s\n" % a.symbols)

    syms = load_symbols(a.symbols)
    need_members = {s.split("#", 1)[0] for k, s, _ in syms if k in ("member", "member!", "field")}
    hit_class = {}     # 内部名 -> dex
    hit_suffix = {}    # 后缀 -> [内部名]
    hit_member = {}    # 类 -> {名字: (kind, sig, dex)}
    text_hits = {s: 0 for k, s, _ in syms if k == "text"}
    needles = [(s, s.encode("utf-8"), s.encode("utf-16-le")) for s in text_hits]
    for name in dex_names:
        d = Dex(reader(name), name)
        for cls in d.classes():
            hit_class.setdefault(cls, d.name)
            for suffix in (s for k, s, _ in syms if k == "class~"):
                if cls.endswith(suffix) and len(hit_suffix.setdefault(suffix, [])) < 4:
                    hit_suffix[suffix].append(cls)
        for cls, members in d.members(need_members).items():
            got = hit_member.setdefault(cls, {})
            for kind, mname, sig in members:
                got.setdefault((kind, mname), (sig, d.name))  # 同名的字段和方法是两条
        for s, u8, u16 in needles:  # 文案可能硬编码在 dex 里,也可能在资源表里
            text_hits[s] += d.b.count(u8) + d.b.count(u16)
        del d

    fails = 0
    for kind, arg, lineno in syms:
        if kind not in KINDS:
            sys.exit("symbols.txt:%d 不认识的关键字 %s" % (lineno, kind))
        ok, detail = True, ""
        if kind == "class":
            detail = "in " + hit_class[arg] if arg in hit_class else "dex 里没有"
            ok = arg in hit_class
        elif kind == "class~":
            got = hit_suffix.get(arg, [])
            ok = bool(got)
            detail = "%d 个: %s" % (len(got), ", ".join(got[:3])) if ok else "没有任何类名以它结尾"
        elif kind in ("member", "member!", "field"):
            cls, name = arg.split("#", 1)
            want = "<init>" if name in ("init", "<init>") else name
            kindw = "field" if kind == "field" else "method"
            hits = [(n, k, sig, dx) for (k, n), (sig, dx) in hit_member.get(cls, {}).items()
                    if k == kindw and (n == want if kind != "member" or want == "<init>" else n.startswith(want))]
            ok = bool(hits)
            detail = " · ".join("%s %s [%s]" % (h[1], h[2], h[3]) for h in hits[:2]) if ok \
                else "没有（%s里%s）" % (cls.split(".")[-1], "类不存在" if cls not in hit_class else "无此成员")
        elif kind == "lit":
            ok, detail = True, "非 dex 字面量（给源码检查用的声明）"
        elif kind in ("res", "text"):
            n = arsc.count(arg.encode("utf-8")) if arsc else 0
            where = "resources.arsc"
            if kind == "text":
                n += text_hits.get(arg, 0) + (arsc.count(arg.encode("utf-16-le")) if arsc else 0)
                where = "dex+资源表"
            ok = n > 0
            detail = "%s 命中 %d 次%s" % (where, n, "（太常见，只能当参考）" if n > 20 else "") if ok else "%s 里没有" % where
        if ok and not a.quiet:
            print("  ok    %-9s %-72s %s" % (kind, arg, detail))
        if not ok:
            fails += 1
            print("  FAIL  %-9s %-72s %s" % (kind, arg, detail))

    if a.lint:
        bad = lint_sources(a.lint, syms)
        if bad:
            print("\n源码里有 %d 个名字没登记在 symbols.txt" % bad)
            fails += bad

    print("\n%d 条符号，%d 条对不上" % (len(syms), fails))
    return 1 if fails else 0


FQCN = re.compile(r"^[a-z][A-Za-z0-9_$]*(\.[A-Za-z0-9_$]+){2,}$")


def literals(line):
    """把一行 Kotlin 里的字符串字面量抽出来 —— 要认 ${...} 里的嵌套引号和三引号。"""
    out, i, n = [], 0, len(line)
    while i < n:
        if line[i] != '"':
            i += 1
            continue
        if line.startswith('"""', i):
            j = line.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        i += 1
        buf = []
        while i < n:
            c = line[i]
            if c == "\\":
                buf.append(line[i:i + 2])
                i += 2
            elif c == "$" and i + 1 < n and line[i + 1] == "{":
                depth, j = 0, i + 1
                while j < n:
                    if line[j] == "{":
                        depth += 1
                    elif line[j] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                buf.append("$X")  # 占位, 后面只看剩下的部分
                i = j + 1
            elif c == '"':
                i += 1
                break
            else:
                buf.append(c)
                i += 1
        out.append("".join(buf))
    return out


def lint_sources(root, syms):
    """代码里出现的反射名字必须都登记在表里 —— 不然这张表就是摆设。"""
    declared = {arg for _, arg, _ in syms}
    declared |= {arg.split("#", 1)[0] for arg in declared if "#" in arg}
    bad = 0
    for path in sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True)):
        with open(path, encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                for value in literals(line):
                    if "$X" in value:  # 拼出来的名字核不了, 一律不许
                        if "." in value and any(c.isupper() for c in value):
                            print("  FAIL  lint %s:%d  别拼名字: %s" % (path, lineno, value))
                            bad += 1
                        continue
                    if not FQCN.match(value):
                        continue
                    if value in declared or any(value in d for d in declared):
                        continue
                    print("  FAIL  lint %s:%d  %s 没登记" % (path, lineno, value))
                    bad += 1
    return bad


if __name__ == "__main__":
    sys.exit(main())
