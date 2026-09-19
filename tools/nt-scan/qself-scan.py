#!/usr/bin/env python3
# Qself — NT QQ hook-target scanner.
#
# Reads the already-extracted classes*.dex and prints, for chosen class-name
# prefixes, the exact methods (with parameter types) and fields. That is what
# a hook needs; class names alone are not enough.
#
# Usage:
#   python3 qself-scan.py [dex-dir] [prefix1,prefix2,...]   # default prefixes
#   python3 qself-scan.py --diag FILE.dex                  # structural report
#   python3 qself-scan.py --all  [dex-dir]                 # every class
#
# Output: candidate entry points on stdout, full dump in /sdcard/qself-methods.txt
# No dependencies. Nothing is uploaded anywhere.

import glob
import os
import struct
import sys

DEFAULT_PREFIXES = [
    "Lcom/tencent/mobileqq/qfix/",
    "Lcom/tencent/feedback/eup/",
    "Lcom/tencent/bugly/crashreport/",
    "Lcom/tencent/bugly/library/",
    "Lcom/tencent/qqnt/startup/",
]

# Methods worth listing first (plus every constructor).
INTERESTING = (
    "install", "load", "apply", "patch", "relax", "init", "start", "run",
    "hook", "replace", "check", "enable", "disable", "update", "crash",
    "report", "upload", "send", "handle", "oncreate", "attach", "dopatch",
)

FLAG_M = [(1, "public"), (2, "private"), (4, "protected"), (8, "static"),
          (16, "final"), (32, "synchronized"), (256, "native"), (1024, "abstract")]
FLAG_F = [(1, "public"), (2, "private"), (4, "protected"), (8, "static"),
          (16, "final"), (64, "volatile"), (128, "transient")]

MAP_TYPES = {
    0x0000: "HEADER_ITEM", 0x0001: "STRING_ID_ITEM", 0x0002: "TYPE_ID_ITEM",
    0x0003: "PROTO_ID_ITEM", 0x0004: "FIELD_ID_ITEM", 0x0005: "METHOD_ID_ITEM",
    0x0006: "CLASS_DEF_ITEM", 0x1000: "MAP_LIST", 0x1001: "TYPE_LIST",
    0x1002: "ANNOTATION_SET_REF_LIST", 0x1003: "ANNOTATION_SET_ITEM",
    0x2000: "CLASS_DATA_ITEM", 0x2001: "CODE_ITEM", 0x2002: "STRING_DATA_ITEM",
    0x2003: "DEBUG_INFO_ITEM", 0x2004: "ANNOTATION_ITEM",
    0x2005: "ENCODED_ARRAY_ITEM", 0x2006: "ANNOTATIONS_DIRECTORY_ITEM",
}

MAX_CLASSES_REPORTED = 80
MAX_METHODS_REPORTED = 12

PRIMITIVES = dict(V="void", Z="boolean", B="byte", S="short", C="char",
                  I="int", J="long", F="float", D="double")


class Anomaly(Exception):
    """A class whose member table could not be decoded; carries the context."""

    def __init__(self, message, **context):
        super().__init__(message)
        self.context = context


def uleb(data, off):
    """Decode an unsigned LEB128 at `off`; returns (value, next_offset)."""
    result, shift = 0, 0
    while True:
        if off >= len(data):
            raise Anomaly("uleb runs past end of file", at=off)
        byte = data[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, off
        shift += 7
        if shift > 35:
            raise Anomaly("uleb longer than 5 bytes", at=off)


def flags(value, table):
    return " ".join(name for bit, name in table if value & bit)


def pretty(descriptor):
    """`Ljava/lang/String;` -> `java.lang.String`, `[I` -> `int[]`."""
    dims = 0
    while dims < len(descriptor) and descriptor[dims] == "[":
        dims += 1
    body = descriptor[dims:]
    if body.startswith("L") and body.endswith(";"):
        body = body[1:-1].replace("/", ".")
    else:
        body = PRIMITIVES.get(body, body)
    return body + "[]" * dims


class Dex:
    def __init__(self, data, path="<memory>"):
        self.d = data
        self.path = path
        self._strings = {}
        self._types = {}
        if data[:4] != b"dex\n":
            raise Anomaly("not a dex image", magic=data[:8])
        self.version = data[4:7].decode("ascii", "replace")
        (self.n_str, self.o_str) = struct.unpack_from("<II", data, 0x38)
        (self.n_type, self.o_type) = struct.unpack_from("<II", data, 0x40)
        (self.n_proto, self.o_proto) = struct.unpack_from("<II", data, 0x48)
        (self.n_field, self.o_field) = struct.unpack_from("<II", data, 0x50)
        (self.n_method, self.o_method) = struct.unpack_from("<II", data, 0x58)
        (self.n_class, self.o_class) = struct.unpack_from("<II", data, 0x60)
        self.file_size = struct.unpack_from("<I", data, 0x20)[0]
        self.map_off = struct.unpack_from("<I", data, 0x34)[0]

    # --- tables ---------------------------------------------------------
    def string(self, index):
        if index >= self.n_str:
            raise Anomaly("string index out of range", index=index, limit=self.n_str)
        if index not in self._strings:
            off = struct.unpack_from("<I", self.d, self.o_str + index * 4)[0]
            if off >= len(self.d):
                raise Anomaly("string_data offset out of file", index=index, off=off)
            _, off = uleb(self.d, off)
            end = self.d.find(b"\x00", off)
            if end < 0:
                raise Anomaly("unterminated string", index=index)
            self._strings[index] = self.d[off:end].decode("utf-8", "replace")
        return self._strings[index]

    def type_descriptor(self, index):
        if index >= self.n_type:
            raise Anomaly("type index out of range", index=index, limit=self.n_type)
        if index not in self._types:
            self._types[index] = self.string(
                struct.unpack_from("<I", self.d, self.o_type + index * 4)[0])
        return self._types[index]

    def proto_descriptor(self, index):
        if index >= self.n_proto:
            raise Anomaly("proto index out of range", index=index, limit=self.n_proto)
        base = self.o_proto + index * 12
        _, ret_idx, params_off = struct.unpack_from("<III", self.d, base)
        ret = self.type_descriptor(ret_idx)
        params = []
        if params_off:
            if params_off + 4 > len(self.d):
                raise Anomaly("type_list offset out of file", off=params_off)
            count = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(count):
                at = params_off + 4 + k * 2
                if at + 2 > len(self.d):
                    raise Anomaly("type_list runs past end of file", off=at)
                params.append(self.type_descriptor(
                    struct.unpack_from("<H", self.d, at)[0]))
        return ret, params

    def method(self, index):
        if index >= self.n_method:
            raise Anomaly("method index out of range", index=index, limit=self.n_method)
        _, proto_idx, name_idx = struct.unpack_from("<HHI", self.d, self.o_method + index * 8)
        ret, params = self.proto_descriptor(proto_idx)
        return self.string(name_idx), ret, params

    def field(self, index):
        if index >= self.n_field:
            raise Anomaly("field index out of range", index=index, limit=self.n_field)
        _, type_idx, name_idx = struct.unpack_from("<HHI", self.d, self.o_field + index * 8)
        return self.string(name_idx), self.type_descriptor(type_idx)

    # --- structure ------------------------------------------------------
    def classes(self):
        for i in range(self.n_class):
            base = self.o_class + i * 32
            if base + 32 > len(self.d):
                raise Anomaly("class_defs run past end of file", index=i)
            # class_idx indexes type_ids (NOT string_ids) — getting this wrong
            # silently resolves every class name to an unrelated string.
            class_idx = struct.unpack_from("<I", self.d, base)[0]
            data_off = struct.unpack_from("<I", self.d, base + 24)[0]
            yield i, self.type_descriptor(class_idx), data_off

    def map_items(self):
        if not self.map_off or self.map_off + 4 > len(self.d):
            return []
        count = struct.unpack_from("<I", self.d, self.map_off)[0]
        items = []
        for k in range(count):
            at = self.map_off + 4 + k * 12
            if at + 12 > len(self.d):
                break
            kind, _, size, off = struct.unpack_from("<HHII", self.d, at)
            items.append((MAP_TYPES.get(kind, "0x%04x" % kind), size, off))
        return items

    def section_of(self, offset):
        """Which map section contains `offset` — for diagnosing stray pointers."""
        best = None
        for name, size, off in self.map_items():
            if off <= offset and (best is None or off > best[2]):
                best = (name, size, off)
        return best[0] if best else "?"

    def members(self, data_off):
        """Decode a class_data_item: (fields, methods), each a list of tuples."""
        if not data_off:
            return [], []
        if data_off >= len(self.d):
            raise Anomaly("class_data offset out of file", data_off=data_off)
        p = data_off
        try:
            sf, p = uleb(self.d, p)
            inf, p = uleb(self.d, p)
            dm, p = uleb(self.d, p)
            vm, p = uleb(self.d, p)
        except Anomaly as error:
            error.context.update(data_off=data_off)
            raise
        counts = dict(static_fields=sf, instance_fields=inf,
                      direct_methods=dm, virtual_methods=vm)
        fields, methods = [], []
        group, k = "?", 0
        try:
            # Each of the four arrays is diff-coded on its own: the first entry
            # is an absolute index and the chain restarts there. Carrying the
            # index across groups (as an earlier version did) walks straight out
            # of the method table on any class that has instance fields or
            # virtual methods.
            for group, count in (("static_fields", sf), ("instance_fields", inf)):
                index = 0
                for k in range(count):
                    diff, p = uleb(self.d, p)
                    access, p = uleb(self.d, p)
                    index = diff if k == 0 else index + diff
                    name, type_desc = self.field(index)
                    fields.append((name, type_desc, access))
            for group, count in (("direct_methods", dm), ("virtual_methods", vm)):
                index = 0
                for k in range(count):
                    diff, p = uleb(self.d, p)
                    access, p = uleb(self.d, p)
                    _, p = uleb(self.d, p)  # code_off — not needed here
                    index = diff if k == 0 else index + diff
                    name, ret, params = self.method(index)
                    methods.append((name, ret, params, access))
        except Anomaly as error:
            error.context.update(data_off=data_off, group=group, at_member=k,
                                 index=index, **counts)
            error.context["hex"] = self.d[data_off:data_off + 48].hex()
            raise
        return fields, methods


def normalise_prefix(text):
    """`com.tencent.qqnt.` / `Lcom/tencent/qqnt/` -> `Lcom/tencent/qqnt/`."""
    text = text.strip()
    if not text.startswith("L"):
        return "L" + text.replace(".", "/")
    return text


def scan(files, prefixes, verbose=True):
    full, per_class, anomalies = [], [], []
    matched = 0
    for position, path in enumerate(files, start=1):
        name = os.path.basename(path)
        with open(path, "rb") as handle:
            data = handle.read()
        if verbose:
            print("  [%d/%d] %s (%.1f MB)" % (position, len(files), name,
                                              len(data) / 1048576.0), file=sys.stderr)
        try:
            dex = Dex(data, path)
        except Anomaly as error:
            anomalies.append((name, "<image>", error, None))
            print("    ! %s" % error, file=sys.stderr)
            continue
        if dex.file_size != len(data):
            print("    ! header says %d bytes, file has %d" % (dex.file_size, len(data)),
                  file=sys.stderr)
        for index, descriptor, data_off in dex.classes():
            if prefixes and not any(descriptor.startswith(p) for p in prefixes):
                continue
            matched += 1
            java = descriptor[1:-1].replace("/", ".")
            try:
                fields, methods = dex.members(data_off)
            except Anomaly as error:
                anomalies.append((name, java, error, dex))
                full.append("class %s  (undecodable: %s)" % (java, error))
                continue
            full.append("class " + java)
            class_methods = []
            for field_name, field_type, access in fields:
                full.append("  f %s %s: %s" % (flags(access, FLAG_F), field_name,
                                               pretty(field_type)))
            for method_name, ret, params, access in methods:
                joined = ", ".join(pretty(p) for p in params)
                line = "m %s %s(%s): %s" % (flags(access, FLAG_M), method_name,
                                            joined, pretty(ret))
                full.append("  " + line)
                class_methods.append(line)
            per_class.append((java, class_methods))
    return full, per_class, anomalies, matched


def write_dump(full, matched, files):
    text = ("# Qself method dump\n# files: %d\n# matched classes: %d\n\n"
            % (len(files), matched) + "\n".join(full) + "\n")
    target = "/sdcard/qself-methods.txt"
    try:
        with open(target, "w") as handle:
            handle.write(text)
    except OSError:
        target = os.path.abspath("qself-methods.txt")
        with open(target, "w") as handle:
            handle.write(text)
    return target, text.count("\n")


def diagnose(path):
    with open(path, "rb") as handle:
        data = handle.read()
    dex = Dex(data, path)
    print("file            : %s (%.1f MB)" % (path, len(data) / 1048576.0))
    print("magic/version   : %s" % dex.version)
    print("header file_size: %d%s" % (dex.file_size,
                                     "" if dex.file_size == len(data) else "  <-- MISMATCH"))
    print("strings/types   : %d / %d" % (dex.n_str, dex.n_type))
    print("protos/fields   : %d / %d" % (dex.n_proto, dex.n_field))
    print("methods/classes : %d / %d" % (dex.n_method, dex.n_class))
    print("map_list        :")
    for name, size, off in dex.map_items():
        print("    %-26s size=%-8d off=0x%08x" % (name, size, off))
    bad = 0
    for index, descriptor, data_off in dex.classes():
        if not data_off:
            continue
        section = dex.section_of(data_off)
        if section != "CLASS_DATA_ITEM":
            print("  ! class %s class_data_off=0x%08x is inside %s" % (descriptor, data_off, section))
            bad += 1
            if bad > 10:
                break
        try:
            dex.members(data_off)
        except Anomaly as error:
            print("  ! %s: %s" % (descriptor, error))
            for key, value in error.context.items():
                print("        %-16s %s" % (key, value))
            bad += 1
            if bad > 10:
                break
    print("classes checked : %d, problems: %d" % (dex.n_class, bad))


def main(argv):
    if "--diag" in argv:
        return diagnose(argv[argv.index("--diag") + 1])
    everything = "--all" in argv
    args = [a for a in argv[1:] if not a.startswith("--")]
    dex_dir = args[0] if args else "."
    if len(args) > 1:
        prefixes = [normalise_prefix(p) for p in args[1].split(",") if p.strip()]
    elif everything:
        prefixes = []
    else:
        prefixes = DEFAULT_PREFIXES

    files = sorted(glob.glob(os.path.join(dex_dir, "classes*.dex")))
    if not files:
        print("no classes*.dex under", dex_dir)
        return 1

    sys.setrecursionlimit(10000)
    full, per_class, anomalies, matched = scan(files, prefixes)
    target, lines = write_dump(full, matched, files)

    print("matched classes: %d   dex files: %d" % (matched, len(files)))
    print("full dump -> %s (%d lines)" % (target, lines))
    if anomalies:
        print("undecodable classes: %d  (skipped, listed below)" % len(anomalies))
        for name, java, error, _dex in anomalies[:6]:
            print("  %s :: %s -> %s" % (name, java, error))
            print("      %s" % " ".join("%s=%s" % kv for kv in error.context.items()))
    else:
        print("undecodable classes: 0")
    print("")
    print("=== 匹配类的方法（把这一段贴回来即可）===")
    if not per_class:
        print("(no class matched — 试更宽的前缀，或先用 --all 看全部类名)")
    # Obfuscated hosts name their methods `a`, `b`, `c`: filtering by keyword
    # would hide exactly the method that has to be hooked, so every matched
    # class is listed. The full list stays in the dump file.
    for java, methods in per_class[:MAX_CLASSES_REPORTED]:
        print("class " + java)
        for line in methods[:MAX_METHODS_REPORTED]:
            print("    " + line)
        if len(methods) > MAX_METHODS_REPORTED:
            print("    # … %d more methods (见 dump 文件)" % (len(methods) - MAX_METHODS_REPORTED))
    if len(per_class) > MAX_CLASSES_REPORTED:
        print("# … %d more matched classes (见 dump 文件)" % (len(per_class) - MAX_CLASSES_REPORTED))
    print("")
    print("# 完整列表：%s" % target)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
