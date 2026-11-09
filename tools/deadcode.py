#!/usr/bin/env python3
"""残留代码扫描：声明了却没人用、import 了却没人提、prefs 键只有写或只有读。

判据只有一条——标识符在**主码里自己**出现的次数。主码与测试分开数：
只被测试调用的主码函数也算残留（测试是调用方，不是用户）。出现 1 次就是只有定义本身，
没有任何主码调用点；这不是「可能没用」，是「一定没用」。

范围：app/src 的 .kt，加上 tools/*.py 与 .github/scripts/*.py 的模块级 def/class/常量。
有发现就退出码 1，直接接进本地回路与 CI，不靠自觉。
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src'
MAIN = SRC / 'main'
TEST = SRC / 'test'
PY_DIRS = (ROOT / 'tools', ROOT / '.github/scripts')

DECL = re.compile(
    r'^[ \t]*(?:@\w+[ \t]*)?(?:internal[ \t]+|private[ \t]+|public[ \t]+)?(?:const[ \t]+|inline[ \t]+|suspend[ \t]+)?'
    r'(fun|val|var|class|object|interface)[ \t]+(?:<[^>]+>[ \t]*)?(?:[\w.]+\.)?(\w+)', re.M)
IMPORT = re.compile(r'^import ([\w.]+)(?: as (\w+))?$', re.M)
KEY = re.compile(r'(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([a-z0-9_]{3,})"')
PY_DECL = re.compile(r'^(?:def|class)\s+(\w+)|^([A-Z][A-Z0-9_]{2,})\s*=', re.M)
PY_SKIP = {'main', '__all__', 'if'}


def words(text):
    return Counter(re.findall(r'[A-Za-z_]\w*', text))


def main() -> int:
    files = sorted(SRC.rglob('*.kt'))
    text = {p: p.read_text(encoding='utf-8') for p in files}
    main_cnt = words('\n'.join(v for k, v in text.items() if MAIN in k.parents))
    test_cnt = words('\n'.join(v for k, v in text.items() if TEST in k.parents))
    problems: list[str] = []

    for path, body in text.items():
        name = path.name
        in_main = MAIN in path.parents
        lines = body.split('\n')
        for m in DECL.finditer(body):
            ln = body[:m.start()].count('\n')
            decl = lines[ln].strip()
            ident = m.group(2)
            # override 与 @Test 由框架调用、main 由入口调用、测试类由 JUnit 加载，都不计
            if 'override' in decl or decl.startswith('@Test') or ident in PY_SKIP:
                continue
            if not in_main and m.group(1) == 'class':
                continue
            if in_main and main_cnt[ident] <= 1:
                only = '，只被测试调用' if test_cnt[ident] else ''
                problems.append(f'{name}:{ln + 1} 主码无人引用{only}  {decl[:88]}')
            elif not in_main and test_cnt[ident] <= 1:
                problems.append(f'{name}:{ln + 1} 测试内无人引用  {decl[:88]}')
        imports = [m for m in IMPORT.finditer(body)]
        if imports:
            body_words = set(re.findall(r'[A-Za-z_]\w*', IMPORT.sub('', body)))
            for m in imports:
                used = m.group(2) or m.group(1).split('.')[-1]
                if used not in body_words:
                    problems.append(f'{name}:{body[:m.start()].count(chr(10)) + 1} import 无人使用  {used}')

    keys = {}
    whole = '\n'.join(text.values())
    names_of_files = {a or b for a, b in re.findall(r'getSharedPreferences\(\s*(\w+)|deleteSharedPreferences\(\s*(\w+)', whole)}
    for name, value in (kv for kv in KEY.findall(whole) if kv[0] not in names_of_files):
        keys.setdefault(value, set()).add(name)
    for value, names in sorted(keys.items()):
        alt = '|'.join(names)
        reads = len(re.findall(r'\.get\w+\(\s*(?:' + alt + r')\b', whole))
        writes = len(re.findall(r'\.put\w+\(\s*(?:' + alt + r')\b', whole))
        if reads == 0 or writes == 0:
            problems.append(f'prefs 键只有{"写" if reads == 0 else "读"}没有{"读" if reads == 0 else "写"}  {value}')

    py_files = sorted(p for d in PY_DIRS if d.exists() for p in d.glob('*.py'))
    py_text = {p: p.read_text(encoding='utf-8') for p in py_files}
    py_cnt = words('\n'.join(py_text.values()))
    for path, body in py_text.items():
        for m in re.finditer(r'^\s+([a-z_]\w*) = ', body, re.M):
            if py_cnt[m.group(1)] <= 1:
                problems.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} 局部变量只写不读  {m.group(1)}')
        for m in PY_DECL.finditer(body):
            ident = m.group(1) or m.group(2)
            if ident in PY_SKIP:
                continue
            if py_cnt[ident] <= 1:
                problems.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} 模块级无人引用  {ident}')

    for line in problems:
        print(line)
    print(f'扫描 {len(files)} 个 Kotlin 文件、{len(py_files)} 个 Python 文件，'
          f'{main_cnt.total()} 个主码标识符（测试另计 {test_cnt.total()}），残留 {len(problems)} 处')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
