#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 docs/preview-log-merge-and-cleanup.png：反复导出只留一个文件的合并去重 + 导出前清理。

    python3 tools/make_log_merge_preview.py

数据来自真机回放：把用户回传的 10 份 26.10.24.3.3165 日志按时间顺序
（03:00:07.750 → 03:00:57.427）依次喂给 mergeDiagnosticReports 得到的真实数字。
只用 Pillow + mplfonts 自带 Noto Sans CJK，不依赖系统字体。
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1560, 1100
BG = (15, 17, 19)
PANEL = (27, 30, 34)
LINE = (58, 64, 72)
INK = (240, 244, 250)
SUB = (176, 188, 204)
MONO = (150, 205, 255)
BAD = (255, 92, 84)
GOOD = (86, 214, 140)
WARN = (255, 189, 84)

FONT = "/usr/local/lib/python3.11/dist-packages/mplfonts/fonts/NotoSansCJKsc-Regular.otf"
MONOF = "/usr/local/lib/python3.11/dist-packages/mplfonts/fonts/NotoSansMonoCJKsc-Regular.otf"


def f(size, mono=False):
    return ImageFont.truetype(MONOF if mono else FONT, size)


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def text(x, y, s, font, color=INK, anchor="la"):
    d.text((x, y), s, font=font, fill=color, anchor=anchor)


def panel(x, y, w, h, title, color=SUB):
    d.rounded_rectangle([x, y, x + w, y + h], radius=18, fill=PANEL, outline=LINE, width=2)
    text(x + 24, y + 18, title, f(23), color)


# ---- 标题 ----
text(48, 40, "「清理和 log 合并」之前确实没做 —— 现已实装：一次导出 = 一个累计文件", f(34), INK)
text(48, 90, "Qself 26.10.24.4 · 真机 OnePlus PLC110 / Android 16 / 1272×2800 · 用你回传的 10 份日志（03:00:07.750 → 03:00:57.427）逐份回放验证",
     f(17), SUB)

# ---- ① 之前：10 个编号副本 ----
panel(48, 140, 470, 470, "① 之前：手机 Download 里堆了 10 个副本", BAD)
before = [
    ("qself-full-log.txt        2.0 MB", MONO),
    ("qself-full-log (1).txt    1.5 MB", MONO),
    ("qself-full-log (2).txt    1.4 MB", MONO),
    ("qself-full-log (3).txt    1.4 MB", MONO),
    ("qself-full-log (4).txt    1.5 MB", MONO),
    ("qself-full-log (5).txt    1.8 MB", MONO),
    ("qself-full-log (6).txt    1.9 MB", MONO),
    ("qself-full-log (7).txt    1.9 MB", MONO),
    ("qself-full-log (8).txt    1.9 MB", MONO),
    ("qself-full-log (9).txt    2.0 MB", MONO),
]
y = 190
for s, c in before:
    text(76, y, s, f(18, mono=True), c)
    y += 30
y += 14
text(76, y, "根因：每次导出都往 MediaStore 插一条 DISPLAY_NAME", f(16), WARN); y += 26
text(76, y, "= qself-full-log.txt 的新记录，系统自动改名为 (1)(2)…", f(16), WARN); y += 30
text(76, y, "→ 不是你手抖，是导出逻辑自己生的。", f(17), BAD); y += 30
text(76, y, "（之前只有 CI 侧的 cleanup-artifacts 在删构建产物，", f(15), SUB); y += 24
text(76, y, "  手机端一行清理代码都没有。）", f(15), SUB)

# ---- ② 现在：三步 ----
panel(546, 140, 470, 470, "② 现在：导出前先合并、再清理、只写一个", GOOD)
steps = [
    ("1. 读回上一次导出", "filesDir/qself-full-log.txt（私有目录，无需权限）", MONO),
    ("2. 按小节合并去重", "[3] 快照并集 / [4][5][6] 记录去重后按时间戳排序", GOOD),
    ("3. 清理历史副本", "MediaStore LIKE 'qself-full-log%' + Download + 应用目录", WARN),
    ("4. 写回唯一文件", "只留 qself-full-log.txt，被挤掉的老行全捞回来", MONO),
]
y = 192
for i, (t1, t2, c) in enumerate(steps):
    d.ellipse([572, y + 4, 596, y + 28], fill=c)
    text(584, y + 6, str(i + 1), f(15), BG, anchor="mm")
    text(608, y, t1, f(19), c)
    y += 30
    text(604, y, t2, f(13, mono=True), SUB)
    y += 42
y += 6
text(572, y, "别的应用写进 Download 的副本删不掉时，系统抛", f(15), SUB); y += 24
text(572, y, "RecoverableSecurityException → 弹一次系统授权框，", f(15), SUB); y += 24
text(572, y, "你点允许之后就真的只剩一个文件。", f(15), GOOD)

# ---- ③ 真机回放 ----
panel(1044, 140, 468, 470, "③ 用你那 10 份日志逐份回放", MONO)
rows = [
    ("(1)", "1.60 MB", "→ 1.60 MB", "首次", SUB),
    ("(2)", "1.42 MB", "→ 2.17 MB", "+2558", GOOD),
    ("(3)", "1.48 MB", "→ 2.56 MB", "+1550", GOOD),
    ("(4)", "1.54 MB", "→ 2.75 MB", "+346", GOOD),
    ("(5)", "1.93 MB", "→ 3.44 MB", "+2089", GOOD),
    ("(6)", "1.95 MB", "→ 3.71 MB", "+574", GOOD),
    ("(7)", "1.99 MB", "→ 4.01 MB", "+648", GOOD),
    ("(8)", "2.02 MB", "→ 4.19 MB", "+403", GOOD),
    ("(9)", "2.09 MB", "→ 4.36 MB", "+183", GOOD),
]
text(1070, 186, "输入", f(16), SUB)
text(1220, 186, "累计合并后", f(16), SUB)
text(1420, 186, "新增", f(16), SUB)
y = 216
for a, b, c, dd, col in rows:
    text(1070, y, a, f(17, mono=True), MONO)
    text(1130, y, b, f(17, mono=True), SUB)
    text(1220, y, c, f(17, mono=True), INK)
    text(1420, y, dd, f(17, mono=True), col)
    y += 32
y += 12
text(1070, y, "9 次再导出累计新增 8340 条；单文件 4.36 MB / 28272 行", f(15), GOOD); y += 26
text(1070, y, "再点一次同样的内容：新增 0 条，长度不变（幂等）", f(15), SUB)

# ---- ④ 合并后单文件的账 ----
panel(48, 636, 1464, 300, "④ 合并后的那一个文件里都有什么（小节口径）", MONO)
cols = [
    ("[2] 钩子调用次数", "取最新一份（次数本就累计）", "28791 × ViewGroup#dispatchDraw", MONO),
    ("[3] 界面树快照", "按标题取并集，最新 16 块", "15 块：HomeTopBar / AIO-Header / AIO-Header-LaidOut", GOOD),
    ("[4] Qself 事件", "整条去重 + 按时间戳排序", "816 条", INK),
    ("[5] UI/AIO/错误 QLog", "整条去重 + 按时间戳排序", "2050 条（原 2099 行里有 49 行完全重复）", INK),
    ("[6] 全量 QQ QLog", "整条去重，上限 12000 条", "9367 条 ← 单份最多只留 3000，被挤掉的全捞回来了", WARN),
]
y = 690
for name, rule, val, col in cols:
    text(76, y, name, f(19), col)
    text(340, y, rule, f(16, mono=True), SUB)
    text(700, y, val, f(16, mono=True), INK)
    y += 40
y += 12
text(76, y, "报告头多了一行「合并 ：已与上一次导出按小节合并去重（上次 N 字节）；导出前清理历史副本 M 个，同名文件只保留 qself-full-log.txt」", f(16), GOOD)
y += 30
text(76, y, "LSPosed 日志与 logcat -s QselfDiag 只吐增量（reportDeltaRecords），重复导出不再刷屏；剪贴板仍是 320KB 头尾截取，完整内容在文件里。", f(16), SUB)

# ---- 底部 ----
text(48, 966, "合并/去重逻辑放在 LogMerge.kt，零 Android 依赖，可用真机日志在 JVM 上直接跑（ProtoTest 3 条新用例 + 真机 10 份回放）", f(17), INK)
text(48, 998, "按钮改名「导出全量日志（合并去重）」，下方加了一行说明；同名文件只保留 qself-full-log.txt。", f(17), SUB)
text(48, 1042, "建议：你连点两下导出（中间隔几秒），确认 Download 里始终只有一个文件，且报告头的「合并」一行显示上次字节数与清理个数。", f(16), WARN)

img.save("docs/preview-log-merge-and-cleanup.png")
print("wrote docs/preview-log-merge-and-cleanup.png", img.size)
