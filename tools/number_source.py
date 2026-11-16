#!/usr/bin/env python3
"""数字来源检查：报告里的数，旁边得有说明它是怎么来的字。

判据：同一个句子里出现一个数（阿拉伯数字串，或跟着量词的中文数字），而整句没有任何
一个「来源词」，就打印一行。数在代码块或行内代码里的跳过——一条命令本身就是它的来源。

用法：
    python3 tools/number_source.py 报告.md …     # 打印清单；有缺来源就退出 1
    python3 tools/number_source.py --self-test   # 跑四组造反例，证明它会红

盲区（补不掉，只能列出来）：
  ① 跨句指代：上一句写「这次实测」，下一句只给数字，会按「没有来源」报出来。方向是多报，不少报。
  ② 单字中文数字（一、两、三…）一律不查：它跟「这一篇」「第三种」这类序数、量词撞得太狠，
     查进来只会把报告淹掉。所以「三次」「五秒」这种短刻度扫不到，要人眼补。
  ③ 只按句子看，不做语义绑定：一句里任何一个来源词，都会救活这句里全部数字。
  ④ 单位写错（数对了、单位错了）看不出来。

退出码：0 全部有来源；1 有缺来源的数，或传进来的文件读不到（打印的是另一句话，别把没跑成当没问题）。
"""
import re
import sys
from pathlib import Path

FENCE = re.compile(r"```.*?```", re.S)
INLINE = re.compile(r"`[^`\n]*`")
MARKS = re.compile(r"〔\d+〕")                       # 检讨的百字标，不是读数
ARABIC = re.compile(r"\d+(?:,\d{3})*(?:\.\d+)?")
CHINESE = re.compile(r"[一二三四五六七八九十百千万零两]{2,8}(?=[个条次秒天篇行处枚份项步轮层种类])")
ORDINAL = ("第", "§", "#")                            # 紧挨在这些字后面的数当序数，不查
MARKERS = ("实测", "读数", "读出来", "读出", "跑出", "跑一遍", "打印", "命令", "脚本",
           "数出", "算出", "统计", "量出", "上限", "基线", "验收线", "余量", "目标",
           "预估", "估", "约", "未验证", "阈值", "刻度", "篇号", "行号", "条号", "版本", "依据")
FIXTURES = (
    ("阿拉伯数字、整句没有来源词", "本轮共 5123 字节。", 1),
    ("中文数字带量词、整句没有来源词", "一共三十个文件。", 1),
    ("一句有来源、下一句没有", "实测 5123 字节。另有 77 处。", 1),
    ("都有来源", "实测 5123 字节，命令是 wc -c。", 0),
    ("序数不算数", "第 12 篇、第三种写法、第十二条。", 0),
    ("只有百字标、没有别的数", "这一篇到此〔100〕。", 0),
)


def scrub(text: str) -> str:
    """抹掉代码块与行内代码，保留行数：命令本身就是数字的来源，不该被当成缺来源。"""
    text = FENCE.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    return MARKS.sub(" ", INLINE.sub(" ", text))


def scan(text: str) -> list[tuple[int, str, list[str]]]:
    """逐行逐句找「有数、无来源词」的句子，返回 (行号, 句子, 抓到的数)。"""
    findings = []
    for lineno, line in enumerate(scrub(text).split("\n"), 1):
        for sentence in re.split(r"[。！？；]", line):
            numbers = [m.group(0) for m in ARABIC.finditer(sentence)
                       if sentence[:m.start()].rstrip()[-1:] not in ORDINAL]
            numbers += [m.group(0) for m in CHINESE.finditer(sentence)
                        if sentence[:m.start()].rstrip()[-1:] not in ORDINAL]
            if not numbers or any(marker in sentence for marker in MARKERS):
                continue
            findings.append((lineno, sentence.strip(), numbers))
    return findings


def self_test() -> int:
    """四组造反例：期望值是手写常量，不来自被检函数。"""
    bad = 0
    for name, text, expect in FIXTURES:
        got = len(scan(text))
        flag = "红 ✓" if got == expect else "✗ 与手写常量不符"
        print(f"{name}：期望命中 {expect}，实得 {got} —— {flag}")
        bad += got != expect
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    files = [a for a in argv if not a.startswith("-")]
    if not files:
        print("用法：python3 tools/number_source.py <文件>… 或 --self-test")
        return 1
    total = 0
    for name in files:
        path = Path(name)
        if not path.is_file():
            print(f"读不到文件：{name}（这不是「没有问题」，是没跑成）")
            return 1
        for lineno, sentence, numbers in scan(path.read_text(encoding="utf-8")):
            print(f"{name}:{lineno} 缺来源的数 {'、'.join(numbers)}  ← {sentence[:60]}")
            total += 1
    print(f"数字来源：{len(files)} 个文件，缺来源 {total} 处")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
