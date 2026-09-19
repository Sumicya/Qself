#!/usr/bin/env python3
# Qself — NT QQ hook-target scanner.
#
# Reads the already-extracted classes*.dex and prints, for chosen class-name
# prefixes, the exact methods (with parameter types) and fields. That is what
# a hook needs; class names alone are not enough.
#
# Usage:  python3 qself-scan.py [dex-dir] [prefix1,prefix2,...]
# Output: filtered candidate list on stdout + full dump in /sdcard/qself-methods.txt
#
# No dependencies. Nothing is uploaded anywhere.

import glob, os, struct, sys

DEFAULT_PREFIXES = [
    "Lcom/tencent/mobileqq/qfix/",
    "Lcom/tencent/feedback/eup/",
    "Lcom/tencent/bugly/crashreport/",
    "Lcom/tencent/bugly/library/",
    "Lcom/tencent/qqnt/startup/",
]

# Methods worth seeing first (plus every constructor).
INTERESTING = (
    "install", "load", "apply", "patch", "relax", "init", "start", "run",
    "hook", "replace", "check", "enable", "disable", "update", "crash",
    "report", "upload", "send", "handle", "onCreate", "attach", "doPatch",
)

FLAG_M = [(1, "public"), (2, "private"), (4, "protected"), (8, "static"),
          (16, "final"), (32, "synchronized"), (256, "native"), (1024, "abstract")]
FLAG_F = [(1, "public"), (2, "private"), (4, "protected"), (8, "static"),
          (16, "final"), (64, "volatile"), (128, "transient")]


def uleb(data, off):
    result, shift = 0, 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def flags(v, table):
    return " ".join(n for bit, n in table if v & bit)


class Dex:
    def __init__(self, data):
        self.d = data
        (self.n_str, self.o_str) = struct.unpack_from("<II", data, 0x38)
        (self.n_type, self.o_type) = struct.unpack_from("<II", data, 0x40)
        (self.n_proto, self.o_proto) = struct.unpack_from("<II", data, 0x48)
        (self.n_field, self.o_field) = struct.unpack_from("<II", data, 0x50)
        (self.n_method, self.o_method) = struct.unpack_from("<II", data, 0x58)
        (self.n_class, self.o_class) = struct.unpack_from("<II", data, 0x60)
        self._str = {}

    def string(self, i):
        if i not in self._str:
            off = struct.unpack_from("<I", self.d, self.o_str + i * 4)[0]
            _, off = uleb(self.d, off)
            end = self.d.index(b"\x00", off)
            self._str[i] = self.d[off:end].decode("utf-8", "replace")
        return self._str[i]

    def type_name(self, i):
        desc = self.string(struct.unpack_from("<I", self.d, self.o_type + i * 4)[0])
        n = 0
        while desc[n] == "[":
            n += 1
        body = desc[n:]
        simple = dict(V="void", Z="boolean", B="byte", S="short", C="char",
                      I="int", J="long", F="float", D="double")
        if body and body[0] == "L":
            body = body[1:-1].replace("/", ".")
        else:
            body = simple.get(body, body)
        return body + "[]" * n

    def proto(self, i):
        _, ret, params_off = struct.unpack_from("<III", self.d, self.o_proto + i * 12)
        params = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(n):
                params.append(self.type_name(
                    struct.unpack_from("<H", self.d, params_off + 4 + k * 2)[0]))
        return self.type_name(ret), params

    def method(self, i):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", self.d, self.o_method + i * 8)
        ret, params = self.proto(proto_idx)
        return self.string(name_idx), ret, params

    def field(self, i):
        _, _, name_idx = struct.unpack_from("<HHI", self.d, self.o_field + i * 8)
        return self.string(name_idx)

    def classes(self):
        for i in range(self.n_class):
            base = self.o_class + i * 32
            cls_idx = struct.unpack_from("<I", self.d, base)[0]
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            yield self.string(cls_idx), data_off

    def members(self, data_off):
        if not data_off:
            return [], []
        p = data_off
        sf, p = uleb(self.d, p)
        inf, p = uleb(self.d, p)
        dm, p = uleb(self.d, p)
        vm, p = uleb(self.d, p)
        fields, methods = [], []
        idx = 0
        for k in range(sf + inf):
            diff, p = uleb(self.d, p)
            fl, p = uleb(self.d, p)
            idx = diff if k == 0 else idx + diff
            fields.append((self.field(idx), flags(fl, FLAG_F)))
        idx = 0
        for k in range(dm + vm):
            diff, p = uleb(self.d, p)
            fl, p = uleb(self.d, p)
            _, p = uleb(self.d, p)  # code_off — not needed
            idx = diff if k == 0 else idx + diff
            name, ret, params = self.method(idx)
            methods.append((name, ret, params, fl))
        return fields, methods


def main():
    dex_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    if len(sys.argv) > 2:
        prefixes = [p.strip() if p.strip().startswith("L") else "L" + p.strip().replace(".", "/")
                    for p in sys.argv[2].split(",") if p.strip()]
    else:
        prefixes = DEFAULT_PREFIXES

    files = sorted(glob.glob(os.path.join(dex_dir, "classes*.dex")))
    if not files:
        print("no classes*.dex under", dex_dir)
        return 1

    full = []
    interesting = []
    matched = 0
    for index, path in enumerate(files, start=1):
        print("  [%d/%d] %s" % (index, len(files), os.path.basename(path)), file=sys.stderr)
        with open(path, "rb") as handle:
            data = handle.read()
        if data[:4] != b"dex\n":
            print("    (not a dex, skipped)", file=sys.stderr)
            continue
        try:
            dex = Dex(data)
        except Exception as error:  # a malformed image must not kill the scan
            print("    (parse failed: %s, skipped)" % error, file=sys.stderr)
            continue
        for descriptor, data_off in dex.classes():
            if not any(descriptor.startswith(p) for p in prefixes):
                continue
            matched += 1
            fields, methods = dex.members(data_off)
            java = descriptor[1:-1].replace("/", ".")
            full.append("class " + java)
            for name, ffl in fields:
                full.append("  f %s %s" % (ffl, name))
            for name, ret, params, mfl in methods:
                full.append("  m %s %s(%s): %s" % (flags(mfl, FLAG_M), name, ", ".join(params), ret))
                low = name.lower()
                if name == "<init>" or any(k.lower() in low for k in INTERESTING):
                    interesting.append("%s\n    %s %s(%s): %s" % (
                        java, flags(mfl, FLAG_M), name, ", ".join(params), ret))

    text = "# Qself method dump\n# files: %d\n# matched classes: %d\n\n" % (len(files), matched) \
        + "\n".join(full) + "\n"
    out = "/sdcard/qself-methods.txt"
    try:
        with open(out, "w") as handle:
            handle.write(text)
        written = out
    except OSError:
        written = os.path.abspath("qself-methods.txt")
        with open(written, "w") as handle:
            handle.write(text)

    print("matched classes: %d   dex files: %d" % (matched, len(files)))
    print("full dump -> %s (%d lines)" % (written, len(full)))
    print("")
    print("=== 候选入口方法（把这一段贴回来即可）===")
    print("\n".join(dict.fromkeys(interesting)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
