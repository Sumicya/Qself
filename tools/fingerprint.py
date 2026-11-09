#!/usr/bin/env python3
"""宿主可见指纹扫描：任何会被 QQ 进程拿到的字符串里都不许出现本模块的名字。

判据不是「全文 grep」。只有**字符串字面量**会被交给宿主：Log/xposed.log 的 tag、
RenderNode 的名字、ClipData 的 label、SharedPreferences 的文件名、View.tag、
contentDescription、导出文件名、BeaconReport 返回值里的字符串、剪贴板与分享的标题。
标识符（包名、类名、方法名）不算：包名是安装包的身份证，宿主 getInstalledPackages()
就能看见，改它只是换个名字被看见。

白名单逐条列出并打印，且要逐字匹配：字面量被改一个字就失效，逼人重看这一处为什么还留着。
有白名单外的命中就退出码 1。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main'
STR = re.compile(r'"((?:[^"\\]|\\.)*)"')
XML_ATTR = re.compile(r'="([^"]*)"')
NAME = 'qself'

# (文件, 字面量, 为什么还留着)
ALLOW = [
    ('AndroidManifest.xml', 'Qself', '安装包自身的名字，与包名同级；宿主 getInstalledPackages() 本来就看得到，改它只是换个名字被看见'),
    ('Hook.kt', 'qself', '一次性搬家常量：把上一版 qself.xml 里的开关搬进 app_cfg 后立即删掉旧文件，之后不再出现'),
]


def main() -> int:
    hits, leftovers = [], []
    scanned = 0
    for path in sorted(MAIN.rglob('*')):
        if path.suffix not in ('.kt', '.xml') or not path.is_file():
            continue
        scanned += 1
        for i, line in enumerate(path.read_text(encoding='utf-8').split('\n'), 1):
            s = line.lstrip()
            if s.startswith('//') or s.startswith('*') or s.startswith('<!--'):
                continue
            lits = (XML_ATTR if path.suffix == '.xml' else STR).findall(line)
            for lit in lits:
                if NAME not in lit.lower():
                    continue
                row = (path.name, lit, i)
                if any(path.name == f and lit == v for f, v, _ in ALLOW):
                    leftovers.append(row)
                else:
                    hits.append(row)

    for f, lit, ln in hits:
        print(f'残留 {f}:{ln}  字面量 {lit!r} 会被宿主进程拿到')
    for f, v, why in ALLOW:
        for f2, lit, ln in leftovers:
            if (f2, lit) == (f, v):
                print(f'白名单 {f}:{ln}  {lit!r} —— {why}')
    print(f'扫描 {scanned} 个文件：宿主可见残留 {len(hits)} 处，白名单 {len(leftovers)} 处')
    missing = len(ALLOW) - len(leftovers)
    if missing:
        print(f'注意：白名单里有 {missing} 条已经不在源码中（已删或改字），请同步删掉白名单条目')
        return 1 if hits else 0
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
