#!/usr/bin/env python3
"""数字来源检查：报告里每个数，同一句里得有一句话说明它是怎么来的。

判据：一句话里出现「读数」，而整句没有一个来源词，就打印一行。三类不算读数——
① 换了环境就变的：代码块、行内代码、HTML 标签与属性、链接、有序列表序号、百字标；
② 名字而不是量：平台版本号（`Android 11`）、同一个字重复（「唯一一条」里的「一一」）；
③ 紧跟在「第」「§」「#」后面的序数。

用法：
    python3 tools/number_source.py 报告.md …     # 打印清单；有缺来源就退出 1
    python3 tools/number_source.py --self-test   # 跑造反例，证明它在两种情况下都会红

盲区（补不掉，只能列进 tools/blindspots.json）：
  ① 跨句指代：上一句写「这次实测」、下一句只给数，会按没有来源报。方向是多报，不少报。
  ② 单字中文数（一、两、三…）一律不查：跟「这一篇」「第三种」撞得太狠，查进来只会把报告淹掉。
     「三次」「五秒」这类短刻度扫不到，要人眼补。
  ③ 一句里任何一个来源词都会救活这句里全部数，不做逐个绑定。
  ④ 单位写错（数对了、单位错了）看不出来。
  ⑤ 链接文本与粗体里的编号（`[10 · Machine]`、`**Option 1**`）与真读数同形，机器分不开；
     实测 30 条抽样里 16 条属于这一类，只能人读。

退出码：0 全部有来源；1 有缺来源的数，或文件读不到（打印的是另一句话，别把没跑成当没问题）。
"""
import re
import sys
from pathlib import Path

# 不算读数的三类，按「抹成空格」处理。抹的顺序有讲究：先标签后链接——
# `<https://…>` 先被标签吃掉，裸链接才轮到 URL 那一条；两条都留着，各配一条造反例。
NOT_A_NUMBER = (
    (re.compile(r'```.*?```', re.S), lambda m: '\n' * m.group(0).count('\n')),   # 代码块，命令本身就是来源
    (re.compile(r'`[^`\n]*`'), ' '),                                            # 行内代码
    (re.compile(r'<[^>\n]{0,400}>'), ' '),                                      # HTML 标签与属性
    (re.compile(r'(?:https?|ftp)://[^\s)<>\]"\']+'), ' '),                      # 链接
)
NOT_A_NUMBER_LINE = (
    (re.compile(r'^\s{0,3}\d{1,3}[.)]\s'), ' '),                                # 有序列表序号
)

# 百字标两种写法：`〔100〕`（本会话旧习惯）与 `（100）`（规范 §22 的格式）。只认整百位，
# 所以「（3 处）」这类括号里的真读数照旧要来源；代价是「（2026）」这种整百位年份也会被抹掉。
MARK = re.compile(r'〔\d+〕|（\d+00）')
ARABIC = re.compile(r'\d+(?:,\d{3})*(?:\.\d+)?')
CHINESE = re.compile(r'[一二三四五六七八九十百千万零两]{2,8}(?=[个条次秒天篇行处枚份项步轮层种类])')
PLATFORM = re.compile(r'Android\s+\d+(?:\.\d+)*')   # 平台版本号是名字，不是这一轮量出来的数
VERSION = re.compile(r'\b\d{2}(?:\.\d{1,2}){2,3}\b')  # 26.11.24 这类日期／版本号：名字，不是读数
OPEN_BRACKET, CLOSE_BRACKET = '（(「[', '）)」]'      # 括号里的分句不切开：切开会把来源与数分家
ORDINAL_BEFORE = ('第', '§', '#')
SOURCES = ('实测', '读数', '读出来', '读出', '跑出', '跑一遍', '打印', '命令', '脚本',
           '数出', '算出', '统计', '量出', '上限', '基线', '验收线', '余量', '目标',
           '预估', '估', '约', '未验证', '阈值', '刻度', '篇号', '行号', '条号', '版本', '依据')


def scrub(text: str) -> str:
    """把「不算读数」的东西抹成空格，行数不变。百字标抹成空串——抹空格会把「量〔100〕出」拆开。"""
    for pattern, repl in NOT_A_NUMBER:
        text = pattern.sub(repl, text)
    lines = []
    for line in text.split('\n'):
        for pattern, repl in NOT_A_NUMBER_LINE:
            line = pattern.sub(repl, line, count=1)
        lines.append(line)
    return MARK.sub('', '\n'.join(lines))


def split_sentences(line: str) -> list[str]:
    """按句读切句，但不切括号里面：`（+1,742；命令：git ls-files）` 切开就没有来源词了。"""
    parts, buf, depth = [], [], 0
    for ch in line:
        if ch in OPEN_BRACKET:
            depth += 1
        elif ch in CLOSE_BRACKET and depth:
            depth -= 1
        if ch in '。！？；' and depth == 0:
            parts.append(''.join(buf))
            buf = []
            continue
        buf.append(ch)
    parts.append(''.join(buf))
    return parts


def numbers_in(sentence: str) -> list[str]:
    """一句里算数的那些：阿拉伯数字与两字以上带量词的中文数，都排除序数与前缀。"""
    body = VERSION.sub(' v ', PLATFORM.sub(' Android ', sentence))
    found = []
    for match in ARABIC.finditer(body):
        if body[:match.start()].rstrip()[-1:] not in ORDINAL_BEFORE:
            found.append(match.group(0))
    for match in CHINESE.finditer(body):
        token = match.group(0)
        if len(set(token)) > 1 and body[:match.start()].rstrip()[-1:] not in ORDINAL_BEFORE:
            found.append(token)
    return found


def scan(text: str) -> list[tuple[int, str, list[str]]]:
    """逐行逐句找「有数、没来源词」的句子，返回 (行号, 句子, 抓到的数)。"""
    findings = []
    for lineno, line in enumerate(scrub(text).split('\n'), 1):
        for sentence in split_sentences(line):
            found = numbers_in(sentence)
            if found and not any(source in sentence for source in SOURCES):
                findings.append((lineno, sentence.strip(), found))
    return findings


# (说明, 输入, 期望命中数)。期望值是手写常量，不取自被检函数。
FIXTURES = (
    ('阿拉伯数字、整句没有来源词', '本轮共 5123 字节。', 1),
    ('中文数字带量词、整句没有来源词', '一共三十个文件。', 1),
    ('一句有来源、下一句没有', '实测 5123 字节。另有 77 处。', 1),
    ('都有来源', '实测 5123 字节，命令是 wc -c。', 0),
    ('序数不算数', '第 12 篇、第三种写法、第十二条。', 0),
    ('只有百字标、没有别的数', '这一篇到此〔100〕。', 0),
    ('全角括号的百字标', '这一篇（100）到此（200）。', 0),
    ('括号里的真读数仍要来源', '这一句（3 处）没有来源。', 1),
    ('分号在括号里不切句', '读数 1,742（+1,742；命令：git ls-files）。', 0),
    ('日期版本号不算读数', '## 26.11.24 这一条。', 0),
    ('百字标插在来源词中间', '量〔100〕出一万字节。', 0),
    ('同一个字重复不算数目', '这是唯一一条他要求必须留的。', 0),
    ('平台版本号不算读数', '接口在 Android 5.1 之后返回的就是自己的进程。', 0),
    ('HTML 标签属性不算读数', '<img width="240" src="https://x/y.png" />', 0),
    ('尖括号里的链接不算读数', '见 <https://github.com/o/r/issues/13118>。', 0),
    ('裸链接里的编号不算读数', '见 https://github.com/o/r/issues/13118 这一条。', 0),
    ('有序列表序号不算读数', '1. 先看结构。\n2. 再看复用。', 0),
    ('代码块里的数不算读数', '跑这条：\n```\nwc -c README.md  # 358 字节\n```\n完。', 0),
    ('正文里的真读数仍然要来源', 'README 一共 1648 行。', 1),
)


def self_test() -> int:
    bad = 0
    for name, text, expect in FIXTURES:
        got = len(scan(text))
        print(f'{name}：期望命中 {expect}，实得 {got} —— {"红 ✓" if got == expect else "✗ 与手写常量不符"}')
        bad += got != expect
    print(f'自检：{len(FIXTURES)} 组，{len(FIXTURES) - bad} 组符合，{bad} 组不符')
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    if '--self-test' in argv:
        return self_test()
    files = [a for a in argv if not a.startswith('-')]
    if not files:
        print('用法：python3 tools/number_source.py <文件>… 或 --self-test')
        return 1
    total = 0
    for name in files:
        path = Path(name)
        if not path.is_file():
            print(f'读不到文件：{name}（这不是「没有问题」，是没跑成）')
            return 1
        for lineno, sentence, found in scan(path.read_text(encoding='utf-8')):
            print(f'{name}:{lineno} 缺来源的数 {"、".join(found)}  ← {sentence[:60]}')
            total += 1
    print(f'数字来源：{len(files)} 个文件，缺来源 {total} 处')
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
