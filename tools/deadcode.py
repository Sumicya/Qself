#!/usr/bin/env python3
"""残留代码扫描：声明了却没人用、import 了却没人提、prefs 键只有写或只有读。

判据只有一条——标识符在整个 app/src 里出现的次数。出现 1 次就是只有定义本身，
没有任何调用点；这不是「可能没用」，是「一定没用」。有发现就退出码 1，
可以直接接进本地回路与 CI，不靠自觉。
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'app/src'
DECL = re.compile(
    r'^(?:@\w+\s*)?(?:internal\s+|private\s+|public\s+)?(?:const\s+|inline\s+|suspend\s+)?'
    r'(fun|val|var|class|object|interface)\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)', re.M)
IMPORT = re.compile(r'^import ([\w.]+)(?: as (\w+))?$', re.M)
KEY = re.compile(r'(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([a-z0-9_]{3,})"')


def main() -> int:
    files = sorted(p for p in ROOT.rglob('*.kt'))
    text = {p: p.read_text(encoding='utf-8') for p in files}
    whole = '\n'.join(text.values())
    count = Counter(re.findall(r'[A-Za-z_]\w*', whole))
    problems: list[str] = []

    for path, body in text.items():
        name = path.name
        lines = body.split('\n')
        depth, depth_at = 0, []
        for line in lines:                       # 记每行所在的花括号深度，用来区分顶层与成员
            depth_at.append(depth)
            depth += line.count('{') - line.count('}')
        for m in DECL.finditer(body):
            ln = body[:m.start()].count('\n')
            decl = lines[ln].strip()
            ident = m.group(2)
            # override 由框架调用、顶层 main 由入口调用，都不计
            if 'override' in decl or ident == 'main':
                continue
            if count[ident] <= 1:
                problems.append(f'{name}:{ln + 1} 声明无人引用  {decl[:88]}')
        imports = [m for m in IMPORT.finditer(body)]
        if imports:
            stripped = IMPORT.sub('', body)
            body_words = set(re.findall(r'[A-Za-z_]\w*', stripped))
            for m in imports:
                used = m.group(2) or m.group(1).split('.')[-1]
                if used not in body_words:
                    problems.append(f'{name}:{body[:m.start()].count(chr(10)) + 1} import 无人使用  {used}')

    keys = {}
    for name, value in KEY.findall(whole):
        keys.setdefault(value, set()).add(name)
    for value, names in sorted(keys.items()):
        reads = len(re.findall(r'\.get\w+\(\s*(?:' + '|'.join(names) + r')\b', whole))
        writes = len(re.findall(r'\.put\w+\(\s*(?:' + '|'.join(names) + r')\b', whole))
        if reads == 0 or writes == 0:
            problems.append(f'prefs 键只有{"写" if reads == 0 else "读"}没有{"读" if reads == 0 else "写"}  {value}')

    for line in problems:
        print(line)
    print(f'扫描 {len(files)} 个文件，{count.total()} 个标识符，残留 {len(problems)} 处')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
