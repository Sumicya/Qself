#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 docs/preview-safe-top-slider-and-aio-diagnosis.png：顶栏安全区真机日志定位 + 滑条实装预览。

    python3 tools/make_safe_top_preview.py

只用 Pillow + mplfonts 自带 Noto Sans CJK，不依赖系统字体。
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1480, 1040
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
    text(x + 24, y + 20, title, f(24), color)


# ---- 标题 ----
text(48, 42, "顶栏安全区：真机日志定位 → 「顶栏安全区」滑条实装", f(34), INK)
text(48, 92, "Qself 26.10.24.3 · 真机 OnePlus PLC110 / Android 16 / 1272×2800 / density 1.976 / statusBars=[0,141,0,0]",
     f(18), SUB)

# ---- 左：真机日志证据 ----
panel(48, 136, 620, 560, "① 真机全量日志证据（qself-full-log.txt）")
log = [
    ("insets: statusBars=[0,141,0,0] navBars=[0,0,0,0]", MONO),
    ("reducedSafeTop=109        ← 141px 只压掉 32px", BAD),
    ("TitleAreaHeadLayout 0x7f0a3245(e3u) mar=[0,68,0,0]", MONO),
    ("ClipSkinnableRecycleView 0x7f0a0e96 pad=[0,167,0,107]", MONO),
    ("FrameLayout 0x7f0a6cfa(5un) b=[0,0,1272,167]", MONO),
    ("MainResourceConfig#n -> 68   （未被压缩）", WARN),
    ("TitleAreaRightLayout 0x7f0a120a b=[0,68,1272,167]", MONO),
    ("AIOTitleRelativeLayout bg=sumicya.qself.HeaderPillsDrawable", MONO),
    ("QQBlurViewWrapper 0x7f0a0957 vis=G alpha=0.0", GOOD),
    ("View 0x7f0a3715(z5u) vis=G alpha=0.0", GOOD),
]
y = 196
for line, color in log:
    text(72, y, line, f(18, mono=True), color)
    y += 34

y += 18
text(72, y, "真机截图 0~68px 全为背景色、无任何状态栏像素，", f(18), SUB)
y += 28
text(72, y, "说明状态栏实际不绘制；QQ 预留的 141px 顶部", f(18), SUB)
y += 28
text(72, y, "安全区是纯浪费，应当整段去掉。", f(18), SUB)

# ---- 右上：根因 ----
panel(700, 136, 732, 268, "② 根因：reducedSafeTop 的「至少保留七成」下限")
code = [
    ("-  val minTop = (origTop * 0.72f).roundToInt()          // 141 → 101", BAD),
    ("-  val baseTop = (origTop - 16dp).coerceAtLeast(minTop) // 141 → 109", BAD),
    ("+  val keep = (origTop * safeTopPercent() / 100f).roundToInt()", GOOD),
    ("+              + (shiftDp * dp).roundToInt()             // 141 → 0", GOOD),
]
y = 196
for line, color in code:
    text(724, y, line, f(18, mono=True), color)
    y += 34
y += 12
text(724, y, "ProtoTest 断言：reducedSafeTop(141, 1.9761429f) == 0", f(19), GOOD)

# ---- 右下：修复前后对比 ----
panel(700, 428, 732, 268, "③ 修复前后（1272px 宽，纵向 0~280px）")


def bar(bx, by, label, safe_px, color, note):
    d.rectangle([bx, by, bx + 300, by + 200], fill=(32, 36, 41), outline=LINE, width=2)
    # 安全区
    if safe_px > 0:
        d.rectangle([bx, by, bx + 300, by + safe_px], fill=(70, 78, 88))
    # 顶栏三胶囊
    d.rounded_rectangle([bx + 8, by + safe_px + 6, bx + 78, by + safe_px + 52], radius=23, fill=(58, 66, 76), outline=LINE)
    d.rounded_rectangle([bx + 90, by + safe_px + 6, bx + 210, by + safe_px + 52], radius=23, fill=(58, 66, 76), outline=LINE)
    d.rounded_rectangle([bx + 222, by + safe_px + 6, bx + 292, by + safe_px + 52], radius=23, fill=(58, 66, 76), outline=LINE)
    # 消息
    d.rounded_rectangle([bx + 8, by + safe_px + 66, bx + 190, by + safe_px + 120], radius=16, fill=(44, 50, 58))
    d.rounded_rectangle([bx + 110, by + safe_px + 130, bx + 292, by + safe_px + 184], radius=16, fill=(44, 50, 58))
    d.text((bx, by + safe_px / 2 - 10), f"{safe_px}px", font=f(18), fill=(220, 226, 236))
    text(bx, by + 210, label, f(19), color)
    text(bx, by + 236, note, f(16), SUB)


bar(728, 500, "修复前：141px（压到 109px）", 109, BAD, "顶栏下方仍留一大段空档")
bar(1090, 500, "修复后：0%（滑条默认）", 0, GOOD, "顶栏与消息顶到窗口最上沿")

# ---- 底部：滑条与落地路径 ----
panel(48, 720, 1384, 268, "④ 落地：面板两条滑条 + 三处同步上移")
rows = [
    ("顶栏安全区 0~100%（默认 0% 完全去掉） · 顶栏上下微调 -24~+48dp",
     "safeTopPercent() / safeTopShiftDp() → reducedSafeTop()，删掉 0.72f 下限，不再加任何人为夹紧上限"),
    ("AIO：sm1.u#k / sm1.u#a 顶部内边距按百分比压缩",
     "141px → 0px，HeaderPillsDrawable 三胶囊贴到窗口最上沿（46dp 胶囊、6dp 边距）"),
    ("AIO 消息列表：paddingTop 经 aioSafeTopRemoved() 同步相减",
     "真包 238 = 141（状态栏）+ 97（标题栏），顶栏上移多少消息列表就减多少，标题栏与首条消息之间不留空档"),
    ("主页 Conversation：HomeSafeArea 三件套同 delta 上移",
     "0x7f0a3245 头像 topMargin 68px、0x7f0a6cfa 顶栏容器 translationY、0x7f0a0e96 列表 paddingTop 167px"),
    ("二级页 QUISecNavBar / MainResourceConfig#n",
     "同一 reducedSafeTop 策略；每次换算写入全量日志，AIO 顶栏 onLayout 后 3 秒节流抓一次带真实尺寸的视图树"),
]
y = 776
for head, sub in rows:
    d.ellipse([72, y + 8, 84, y + 20], fill=GOOD)
    text(100, y, head, f(19), INK)
    text(100, y + 26, sub, f(16), SUB)
    y += 42

img.save("docs/preview-safe-top-slider-and-aio-diagnosis.png")
print("ok")
