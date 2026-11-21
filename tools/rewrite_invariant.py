#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重写不变量：整份重写前抽一次硬事实，重写完再比一次，少了就红。

为什么需要：这个仓的主码是对真 QQ 逆向出来的，里面散落着几百个
「类名 / 字段名 / 方法名」硬编码。整份重写时写错一个字符（`e` 写成 `f`、
`isNowThemeIsNight` 少一个 s），Kotlin 编译器不会报——它们是字符串。
后果是编译能过、单测全绿、真机整块功能静默失效。

所以重写前先跑：

    python3 tools/rewrite_invariant.py --save /tmp/before.json

重写完再跑：

    python3 tools/rewrite_invariant.py --check /tmp/before.json

四类硬事实，一条都不许少：
    fqcn    QQ / 框架的全限定类名（"com.tencent..."）
    method  getDeclaredMethod 取的方法名
    field   getDeclaredField 取的字段名
    prefs   SharedPreferences 的键

允许「变多」——重写时发现新的名字、补全一个漏掉的分支，都是好事。
只报「变少」和「换成了什么」，因为那才是静默失效的来源。

退出码：0 没少；1 有缺失或文件消失。
"""
import argparse
import glob
import json
import os
import re
import sys

# 三类「单个短名字」的判据：光比名字集合不够——同一个文件里既有 getDeclaredField("e")
# 也有 getDeclaredField("f")，把其中一个改成另一个，集合不变、功能已经错了。
# 所以这三类连「是谁的」一起记：接收者表达式 + 名字。接收者换了也算变。
PATTERNS = {
    'fqcn': re.compile(r'"((?:com|androidx?|io|kotlin)\.[A-Za-z0-9_.$]+)"'),
    'method': re.compile(r'([A-Za-z0-9_$.]+)\.getDeclaredMethod\("([A-Za-z0-9_$]+)"'),
    'field': re.compile(r'([A-Za-z0-9_$.]+)\.getDeclaredField\("([A-Za-z0-9_$]+)"'),
    # prefs 的键多数写成常量（store()?.getBoolean(KEY, …)），字面量只有少数几处。
    # 这里既收字面量键，也收「常量键」的标识符——常量名改了同样是静默失效。
    'prefs': re.compile(
        r'(?:getBoolean|getInt|getString|getFloat|putBoolean|putInt|putString|putFloat)'
        r'\(\s*("(?:[^"]+)"|[A-Za-z0-9_.]+)'),
    'prefsconst': re.compile(
        r'(?:private |internal )?const val ([A-Z][A-Z0-9_]{2,})\s*=\s*"'),
}

LABELS = {
    'fqcn': '全限定类名',
    'method': '方法名',
    'field': '字段名',
    'prefs': 'prefs 键',
    'prefsconst': 'prefs 常量名',
}


def scan(root: str) -> dict:
    """按文件收集四类事实。文件消失也要能被发现，所以键是文件名。"""
    facts = {}
    for path in sorted(glob.glob(os.path.join(root, '**', '*.kt'), recursive=True)):
        text = open(path, encoding='utf-8').read()
        found = {}
        for kind, pattern in PATTERNS.items():
            values = set()
            for hit in pattern.findall(text):
                # fqcn 只有一个捕获组；其余两个是 (接收者, 名字)
                values.add(hit if isinstance(hit, str) else '%s#%s' % (hit[0], hit[1]))
            found[kind] = sorted(values)
        facts[os.path.basename(path)] = found
    return facts


def save(root: str, target: str) -> int:
    facts = scan(root)
    with open(target, 'w', encoding='utf-8') as fh:
        json.dump(facts, fh, ensure_ascii=False, indent=1, sort_keys=True)
    total = {kind: sum(len(v[kind]) for v in facts.values()) for kind in PATTERNS}
    print('已存 %s：%d 个文件' % (target, len(facts)))
    for kind in PATTERNS:
        print('  %-8s %-10s %d 条' % (kind, LABELS[kind], total[kind]))
    return 0


def check(root: str, baseline: str) -> int:
    before = json.load(open(baseline, encoding='utf-8'))
    after = scan(root)
    bad = 0

    for name in sorted(set(before) - set(after)):
        print('文件消失：%s（整份重写不该删文件）' % name)
        bad += 1

    for name in sorted(set(before) & set(after)):
        for kind in PATTERNS:
            lost = sorted(set(before[name][kind]) - set(after[name][kind]))
            if not lost:
                continue
            print('%s：少了 %d 个%s' % (name, len(lost), LABELS[kind]))
            for value in lost:
                print('    %s' % value)
            bad += len(lost)

    gained = 0
    for name in sorted(set(before) & set(after)):
        for kind in PATTERNS:
            gained += len(set(after[name][kind]) - set(before[name][kind]))

    print()
    if bad:
        print('不变量破了 %d 处；名字少一个，真机上就是一块功能静默失效。' % bad)
        return 1
    print('不变量完好；顺带新增 %d 条（重写时补全的，不算坏）' % gained)
    return 0


# (说明, 源码, 基准事实, 期望是否报红)。期望值是手写常量，不取自被检函数。
FIXTURES = (
    ('字段换了', 'x.getDeclaredField("f")',
     {'a.kt': {'field': ['x#e']}}, True),
    ('字段没换', 'x.getDeclaredField("e")',
     {'a.kt': {'field': ['x#e']}}, False),
    ('类名少一个字符', 'cls("com.tencent.mobileqq.tab.TabFrameLayou")',
     {'a.kt': {'fqcn': ['com.tencent.mobileqq.tab.TabFrameLayout']}}, True),
    ('方法名换了接收者', 'other.getDeclaredMethod("a")',
     {'a.kt': {'method': ['frame#a']}}, True),
    ('常量名改了', 'const val NEW_KEY = "k"',
     {'a.kt': {'prefsconst': ['OLD_KEY']}}, True),
    ('多出来不算坏', 'x.getDeclaredField("e"); y.getDeclaredField("z")',
     {'a.kt': {'field': ['x#e']}}, False),
    ('文件整个没了', '',
     {'gone.kt': {'field': ['x#e']}}, True),
)


def self_test(tmpdir: str) -> int:
    """反例：每一条都独立造一个迷你源码目录，跑真判据，比对期望的红绿。"""
    bad = 0
    for index, (name, source, baseline, expect_red) in enumerate(FIXTURES):
        case = os.path.join(tmpdir, 'case%d' % index)
        os.makedirs(case, exist_ok=True)
        if source:
            open(os.path.join(case, 'a.kt'), 'w', encoding='utf-8').write(source)
        base = os.path.join(tmpdir, 'base%d.json' % index)
        with open(base, 'w', encoding='utf-8') as fh:
            json.dump(baseline, fh)
        got_red = check_quiet(case, base)
        ok = got_red == expect_red
        print('%s：期望%s，实得%s —— %s' % (
            name, '红' if expect_red else '绿', '红' if got_red else '绿', '✓' if ok else '✗ 与手写常量不符'))
        bad += 0 if ok else 1
    print('自检：%d 组，%d 组符合，%d 组不符' % (len(FIXTURES), len(FIXTURES) - bad, bad))
    return 1 if bad else 0


def check_quiet(root: str, baseline: str) -> bool:
    """同 check()，但不打印、只回答「破了没有」。自检用。"""
    before = json.load(open(baseline, encoding='utf-8'))
    after = scan(root)
    if set(before) - set(after):
        return True
    return any(set(before[n][k]) - set(after[n][k])
               for n in set(before) & set(after) for k in before[n])


def main(argv) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='app/src/main/kotlin', help='扫描的源码根目录')
    ap.add_argument('--save', help='把当前事实存到这个 json')
    ap.add_argument('--check', help='拿这个 json 当初次基准来比')
    ap.add_argument('--self-test', action='store_true', help='跑造反例，证明它真会红')
    a = ap.parse_args(argv)
    if a.self_test:
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            return self_test(tmp)
    if a.save:
        return save(a.root, a.save)
    if a.check:
        return check(a.root, a.check)
    ap.error('要么 --save 要么 --check')
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
