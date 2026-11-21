#!/usr/bin/env python3
"""静态闸门：残留代码 + 宿主可见指纹，一个入口一次跑完。

四条判据：
  ① 残留——标识符在**主码里自己**出现的次数。主码与测试分开数；只被测试调用的主码函数也算残留
     （测试是调用方，不是用户）。计数前先剥注释：死代码只要在注释里被提一次就会凑够两次。
  ② 指纹——会被宿主拿到的字符串字面量里不许出现本模块的名字。标识符（包名、类名）不算：
     包名是安装包的身份证，宿主查已安装应用列表本来就看得到。
  ③ 禁查——主码不许枚举这台机器上还有谁（应用列表、运行中进程、最近任务、使用统计、剪贴板）。
  ④ 盲区清单——三类键不全为空、每条三栏齐全、路径真实存在。

结构上有两处是刻意的：
  · **源码只有一种读法**。剥注释、抹字符串是同一台状态机（`render`），四种视图由它一个函数产出；
    以前剥注释与抹字符串各写一份，两份的引号规则不一样，改一处忘一处就是下一次漏检。
  · **指纹面只有一张表**（`FACES`／`BINARY`）。扫描、白名单过期检查、"产物里有没有东西不在表里"
    三处读同一份表。表里没有的后缀要报，登记成文本却解不成 UTF-8 的也要报——以前后者被
    `continue` 吃掉，文件既不进视野也不进总数，于是「产物 N 个、扫了 N 个」这个等式从来没被验过。

固有盲区（补不掉，只能列进 tools/blindspots.json）：
  ① 反射按字符串调用的方法：`Class.forName("...").getMethod("x")` 里的 "x" 只是个字符串。
  ② 只在构建脚本里被引用的符号：Kotlin 源码里看不到 gradle 那边的使用。
  ③ 跨文件动态导入：`importlib.import_module(name)` 这类，名字在运行期才定。
  ④ XML 只抽属性值，不抽元素文本（`<string>…</string>` 中间那段）。

有发现就退出码 1，接在 tools/check.sh 与 .github/workflows/build.yml 上，非 0 不出包。
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src'
MAIN = SRC / 'main'
TEST = SRC / 'test'
MAIN_KT = MAIN / 'kotlin'
PY_DIRS = (ROOT / 'tools', SRC / 'test/python')      # 规范 §11：脚本只进 tools/

NAME = 'qself'
SKIP = {'main', '__all__'}


# ── 源码读法：一台状态机，四种视图 ────────────────────────────────────────────
def render(src: str, py: bool, strings: bool) -> str:
    """剥注释；`strings=True` 保留字符串内容，`False` 抹成空格。行列数不变。

    为什么抹：写在字符串里的 `def foo` 不是声明，写在字符串里的名字也不是引用。
    为什么又要留：Kotlin 的 `"$name"` 模板是真引用，全抹掉会造出一堆假残留。
    """
    out, i, n = [], 0, len(src)
    quotes = ('"""', "'''") if py else ('"""',)
    while i < n:
        head = next((q for q in quotes if src.startswith(q, i)), None)
        if head:
            j = src.find(head, i + 3)
            j = n if j < 0 else j
            body = src[i + 3:j] if strings else re.sub(r'[^\n]', ' ', src[i + 3:j])
            out.append(head + body + head)
            i = j + 3
            continue
        c = src[i]
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            body = src[i + 1:j] if strings else re.sub(r'[^\n]', ' ', src[i + 1:j])
            out.append(c + body + c)
            i = j + 1
            continue
        if (py and c == '#') or (not py and src.startswith('//', i)):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if not py and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def literals(src: str) -> list[tuple[int, str]]:
    """按行号取字符串字面量（入参应是 `render(..., strings=True)` 的结果）。"""
    found, i, n, line = [], 0, len(src), 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            found.append((line, src[i + 3:j]))
            line += src.count('\n', i, j)
            i = j + 3
            continue
        if c == '"':
            j, buf = i + 1, []
            while j < n and src[j] != '"' and src[j] != '\n':
                if src[j] == '\\':
                    j += 1
                buf.append(src[j])
                j += 1
            found.append((line, ''.join(buf)))
            i = j + 1
            continue
        i += 1
    return found


# ── 指纹面：一张表，三处共用 ──────────────────────────────────────────────────
def _attrs(src: str) -> list[tuple[int, str]]:
    return [(i, v) for i, line in enumerate(src.split('\n'), 1)
            for v in re.findall(r'="([^"]*)"', line)]


def _lines(src: str) -> list[tuple[int, str]]:
    return [(i, line.strip()) for i, line in enumerate(src.split('\n'), 1) if line.strip()]


# 后缀 → 取字面量的办法。加一种后缀就在这里加一行，别的什么都不用改。
FACES: dict[str, object] = {'.kt': literals, '.xml': _attrs, '.list': _lines,
                            '.prop': _lines, '.txt': _lines}
# 二进制面：登记了就不打开，但必须登记——不登记的文件会被 face 判据报出来。
BINARY = {'.png', '.jpg', '.jpeg', '.webp', '.jar', '.zip', '.arsc'}

# (文件, 字面量, 为什么还留着)。删掉条目对应的字面量时，过期检查会自己报错。
ALLOW = [
    ('AndroidManifest.xml', 'Qself', '安装包自身的名字，与包名同级；宿主 getInstalledPackages() 本来就看得到'),
    ('java_init.list', 'sumicya.qself.Qself', 'Xposed 按这个名字反射加载入口类，改名等于换模块'),
]

# 宿主进程里不许出现的查询。本仓一条都没有，也不该有：空表本身就是判据，
# 要开例外得先在这里写一行理由，写不出理由就说明它不该加进来。
FORBIDDEN = ('getInstalledPackages', 'getInstalledApplications', 'getRunningAppProcesses',
             'getRunningTasks', 'getRecentTasks', 'queryUsageStats', 'getPrimaryClip')
FORBIDDEN_ALLOW: list[tuple[str, str, str]] = []


def face_scan() -> tuple[list[str], dict[str, list[tuple[int, str]]], dict[str, int]]:
    """走一遍产物目录：报没登记的文件、报解不开的文本、返回每个文件的字面量。

    返回 (problems, 文件名字面量表, 计数)。计数里有三个数：总文件、文本面、二进制面，
    前两者之和必须等于第一个——这条等式就是「扫了 N 个」与「产物有 M 个」不再打架的地方。
    """
    problems: list[str] = []
    hits: dict[str, list[tuple[int, str]]] = {}
    tally = {'files': 0, 'text': 0, 'binary': 0}
    for path in sorted(MAIN.rglob('*')):
        if not path.is_file():
            continue
        tally['files'] += 1
        reader = FACES.get(path.suffix)
        if reader is None:
            if path.suffix in BINARY:
                tally['binary'] += 1
                continue
            problems.append(f'产物里的 {path.relative_to(MAIN)} 没被指纹面覆盖（后缀 {path.suffix!r}）：'
                            f'要么加进 FACES，要么加进 BINARY 并说明为什么不解文本')
            continue
        try:
            src = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            problems.append(f'产物里的 {path.relative_to(MAIN)} 登记为文本面却解不成 UTF-8：'
                            f'解不开就是没看，不能算进「已覆盖」')
            continue
        tally['text'] += 1
        body = render(src, False, True) if reader is literals else src
        hits[path.name] = reader(body)
    return problems, hits, tally


def fingerprints(hits: dict[str, list[tuple[int, str]]]) -> list[str]:
    """宿主可见字面量里出现模块名就报；白名单条目消失也要报。"""
    bad: list[str] = []
    for fname, rows in hits.items():
        for lineno, lit in rows:
            if NAME not in lit.lower():
                continue
            if any(fname == f and lit == v for f, v, _ in ALLOW):
                continue
            bad.append(f'{fname}:{lineno} 宿主可见字面量 {lit!r}')
    for fname, want, _ in ALLOW:
        if not any(lit == want for _, lit in hits.get(fname, [])):
            bad.append(f'白名单条目已不在产物里，请同步删掉  {fname}:{want!r}')
    return bad


def forbidden_calls() -> list[str]:
    bad: list[str] = []
    sources = {p: render(p.read_text(encoding='utf-8'), False, True)
               for p in sorted(MAIN_KT.rglob('*.kt'))}
    for path, src in sources.items():
        for lineno, line in enumerate(src.split('\n'), 1):
            for name in FORBIDDEN:
                if name in line and not any(path.name == f and name == s for f, s, _ in FORBIDDEN_ALLOW):
                    bad.append(f'{path.name}:{lineno} 主码调用了禁查 API {name}：'
                               f'本模块只改 QQ 界面，不该枚举这台机器上的其他应用')
    for fname, sym, _ in FORBIDDEN_ALLOW:
        if not any(sym in src for p, src in sources.items() if p.name == fname):
            bad.append(f'禁查白名单条目已不在源码中，请同步删掉  {fname}:{sym}')
    return bad


# ── 残留：Kotlin 与 Python 各走一遍，判定口径相同 ─────────────────────────────
DECL = re.compile(
    r'^[ \t]*(?:@\w+[ \t]*)?(?:internal[ \t]+|private[ \t]+|public[ \t]+)?(?:const[ \t]+|inline[ \t]+|suspend[ \t]+)?'
    r'(fun|val|var|class|object|interface)[ \t]+(?:<[^>]+>[ \t]*)?(?:[\w.]+\.)?(\w+)', re.M)
IMPORT = re.compile(r'^import ([\w.]+)(?: as (\w+))?$', re.M)
PREF_KEY = re.compile(r'(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([a-z0-9_]{3,})"')
PY_IMPORT = re.compile(r'^(?:import ([\w.]+)(?: as (\w+))?|from ([\w.]+) import ([^#\n]+))$', re.M)
PY_DECL = re.compile(r'^(def|class)\s+(\w+)', re.M)
PY_CONST = re.compile(r'^([A-Z][A-Z0-9_]{2,})\s*=', re.M)
PY_LOCAL = re.compile(r'^\s+([a-z_]\w*) = ', re.M)


def words(text: str) -> Counter:
    return Counter(re.findall(r'[A-Za-z_]\w*', text))


def kotlin_problems() -> tuple[list[str], Counter, Counter, int]:
    """主码与测试分开数；返回问题、主码计数、测试计数、Kotlin 文件数。"""
    files = sorted(SRC.rglob('*.kt'))
    raw = {p: p.read_text(encoding='utf-8') for p in files}
    decl = {p: render(v, False, False) for p, v in raw.items()}     # 抹字符串：字符串里的 fun 不是声明
    whole = {p: render(v, False, True) for p, v in raw.items()}      # 留字符串：模板是真引用
    main_cnt = words('\n'.join(v for k, v in whole.items() if MAIN in k.parents))
    test_cnt = words('\n'.join(v for k, v in whole.items() if TEST in k.parents))
    bad: list[str] = []
    for path, body in decl.items():
        in_main = MAIN in path.parents
        lines = body.split('\n')
        for m in DECL.finditer(body):
            lineno = body[:m.start()].count('\n')
            sig, ident = lines[lineno].strip(), m.group(2)
            # 框架按名字调用的都不计：override、@Test、JUnit 测试类、XposedModule 入口
            if 'override' in sig or sig.startswith('@Test') or ident in SKIP or 'XposedModule' in sig:
                continue
            if not in_main and m.group(1) == 'class':
                continue
            if in_main and main_cnt[ident] <= 1:
                only = '，只被测试调用' if test_cnt[ident] else ''
                bad.append(f'{path.name}:{lineno + 1} 主码无人引用{only}  {sig[:88]}')
            elif not in_main and test_cnt[ident] <= 1:
                bad.append(f'{path.name}:{lineno + 1} 测试内无人引用  {sig[:88]}')
        for m in IMPORT.finditer(body):
            used = m.group(2) or m.group(1).split('.')[-1]
            if used not in set(re.findall(r'[A-Za-z_]\w*', IMPORT.sub('', whole[path]))):
                bad.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} import 无人使用  {used}')

    # prefs 键：只写不读或只读不写，都是死配置
    joined = '\n'.join(whole.values())
    stores = set(re.findall(r'getSharedPreferences\(\s*(\w+)', joined))
    keys: dict[str, set] = {}
    for const, value in (kv for kv in PREF_KEY.findall(joined) if kv[0] not in stores):
        keys.setdefault(value, set()).add(const)
    for value, consts in sorted(keys.items()):
        alt = '|'.join(consts)
        reads = len(re.findall(r'\.get\w+\(\s*(?:' + alt + r')\b', joined))
        writes = len(re.findall(r'\.put\w+\(\s*(?:' + alt + r')\b', joined))
        if reads == 0 or writes == 0:
            bad.append(f'prefs 键只有{"写" if reads == 0 else "读"}没有{"读" if reads == 0 else "写"}  {value}')
    return bad, main_cnt, test_cnt, len(files)


def python_problems() -> tuple[list[str], Counter, int]:
    files = sorted({p for d in PY_DIRS if d.exists() for p in d.glob('*.py')} - {Path(__file__)})
    raw = {p: p.read_text(encoding='utf-8') for p in files}
    decl = {p: render(v, True, False) for p, v in raw.items()}
    whole = {p: render(v, True, True) for p, v in raw.items()}
    cnt = words('\n'.join(whole.values()))
    bad: list[str] = []
    for path, body in decl.items():
        is_test = path.name.startswith('test_')
        decls = [(m.group(2), m.group(1)) for m in PY_DECL.finditer(body)]
        decls += [(m.group(1), 'const') for m in PY_CONST.finditer(body)]
        for ident, kind in decls:
            # 测试里的类由 unittest 发现、test_ 函数由 unittest 调用，都不计
            if ident in SKIP or (is_test and (kind == 'class' or ident.startswith('test'))):
                continue
            if cnt[ident] <= 1:
                bad.append(f'{path.name}: 模块级无人引用  {ident}')
        for m in PY_LOCAL.finditer(body):
            if cnt[m.group(1)] <= 1:
                bad.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} 局部变量只写不读  {m.group(1)}')
        for m in PY_IMPORT.finditer(body):
            if m.group(1):
                used = [m.group(2) or m.group(1).split('.')[0]]
            else:
                used = [t.strip().split(' as ')[-1].strip() for t in m.group(4).split(',')]
            for u in used:
                if u and u != '*' and cnt[u] <= 1:
                    bad.append(f'{path.name}: import 无人使用  {u}')
    return bad, cnt, len(files)


# ── 盲区清单 ────────────────────────────────────────────────────────────────
def blindspots() -> list[str]:
    path = ROOT / 'tools/blindspots.json'
    if not path.is_file():
        return ['tools/blindspots.json 不存在：盲区清单缺席']
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
    except json.JSONDecodeError as exc:
        return [f'tools/blindspots.json 不是合法 JSON：{exc}']
    want = ('excluded', 'deferred', 'open_questions')
    bad: list[str] = []
    if sorted(data) != sorted(want):
        bad.append(f'盲区清单的键必须正好是 {"/".join(want)}，实得 {"/".join(sorted(data))}')
    seen: set[str] = set()
    for key in want:
        rows = data.get(key)
        if not isinstance(rows, list) or not rows:
            bad.append(f'盲区清单 {key} 为空或不是数组：空的一类等于这一类没被交代')
            continue
        for index, row in enumerate(rows, 1):
            where = f'{key}[{index}]'
            if not isinstance(row, dict) or sorted(row) != ['limit', 'path', 'why']:
                bad.append(f'{where} 必须正好有 path/why/limit 三栏')
                continue
            for field in ('path', 'why', 'limit'):
                if not isinstance(row[field], str) or not row[field].strip():
                    bad.append(f'{where}.{field} 是空的')
            target = row['path']
            if target in seen:
                bad.append(f'{where}.path 重复：{target}')
            seen.add(target)
            if not (ROOT / target).exists():
                bad.append(f'{where}.path 在仓里不存在：{target}')
    return bad


def main() -> int:
    kt_bad, main_cnt, test_cnt, kt_files = kotlin_problems()
    py_bad, py_cnt, py_files = python_problems()
    fp_bad, hits, tally = face_scan()
    problems = (kt_bad + py_bad + fp_bad + fingerprints(hits) + forbidden_calls() + blindspots())

    for line in problems:
        print(line)
    blind = json.loads((ROOT / 'tools/blindspots.json').read_text(encoding='utf-8'))
    print(f'残留/指纹：Kotlin {kt_files} 个、Python {py_files} 个；'
          f'产物 {tally["files"]} 个 = 文本面 {tally["text"]} + 二进制面 {tally["binary"]}；'
          f'主码标识符 {main_cnt.total()}（Kotlin 测试另计 {test_cnt.total()}、Python {py_cnt.total()}），'
          f'白名单 {len(ALLOW)} 条，'
          f'盲区清单 {"/".join(f"{k}={len(v)}" for k, v in blind.items())}，问题 {len(problems)} 处')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
