#!/usr/bin/env python3
"""静态闸门：残留代码 + 宿主可见指纹，一个入口一次跑完。

判据一（残留）：标识符在**主码里自己**出现的次数。主码与测试分开数，
只被测试调用的主码函数也算残留——测试是调用方，不是用户。出现 1 次就是只有定义，
没有任何主码调用点。计数前先把注释剥掉：死代码只要在注释里被提一次就会凑够两次。
判据二（指纹）：会被宿主进程拿到的**字符串字面量**里不许出现本模块的名字。
标识符（包名、类名）不算：包名是安装包的身份证，宿主查已安装应用列表就看得到。

范围：app/src 的 .kt（含缩进成员与三引号原始字符串），tools/、.github/scripts/、
app/src/test/python 的 .py（模块级声明、局部只写不读、未使用 import）。

固有盲区（补不掉，只能列出来）：
  ① 反射按字符串调用的方法：`Class.forName("...").getMethod("x")` 里的 "x" 只是个字符串。
  ② 只在构建脚本里被引用的符号：Kotlin 源码里看不到 gradle 那边的使用。
  ③ 跨文件动态导入：`importlib.import_module(name)` 这类，名字在运行期才定。
  ④ 资源文件里的字符串：.xml/.json 里写的东西不在本脚本的抽取范围内。

有发现就退出码 1，接在 tools/check.sh 与 .github/workflows/build.yml 上，非 0 不出包。
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src'
MAIN = SRC / 'main'
TEST = SRC / 'test'
PY_DIRS = (ROOT / 'tools', ROOT / '.github/scripts', SRC / 'test/python')

DECL = re.compile(
    r'^[ \t]*(?:@\w+[ \t]*)?(?:internal[ \t]+|private[ \t]+|public[ \t]+)?(?:const[ \t]+|inline[ \t]+|suspend[ \t]+)?'
    r'(fun|val|var|class|object|interface)[ \t]+(?:<[^>]+>[ \t]*)?(?:[\w.]+\.)?(\w+)', re.M)
IMPORT = re.compile(r'^import ([\w.]+)(?: as (\w+))?$', re.M)
PY_IMPORT = re.compile(r'^(?:import ([\w.]+)(?: as (\w+))?|from ([\w.]+) import ([^#\n]+))$', re.M)
KEY = re.compile(r'(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([a-z0-9_]{3,})"')
PY_DEF = re.compile(r'^def\s+(\w+)', re.M)
PY_CLASS = re.compile(r'^class\s+(\w+)', re.M)
PY_CONST = re.compile(r'^([A-Z][A-Z0-9_]{2,})\s*=', re.M)
SKIP = {'main', '__all__'}

NAME = 'qself'
# (文件, 字面量, 为什么还留着)
ALLOW = [
    ('AndroidManifest.xml', 'Qself', '安装包自身的名字，与包名同级；宿主 getInstalledPackages() 本来就看得到'),
]


def strip_comments(src: str, py: bool) -> str:
    """把注释替换成空格，保留换行与字符串，行号不变。"""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if not py and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if py and src.startswith('"""', i) or py and src.startswith("'''", i):
            q = src[i:i + 3]; j = src.find(q, i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1; continue
        if (py and c == '#') or (not py and src.startswith('//', i)):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j; continue
        if not py and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j])); i = j; continue
        out.append(c); i += 1
    return ''.join(out)


def blank_strings(src: str, py: bool) -> str:
    """把字符串内容抹成空格，保留引号与换行，长度与行号不变。

    为什么计数时要抹：写在字符串里的 `def foo` 不是声明，写在字符串里的名字也不是引用。
    Kotlin 那边唯一靠字符串引用的自己人只有 XposedModule 入口类，已显式豁免。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        if not py and src.startswith('"""', i) or py and (src.startswith('"""', i) or src.startswith("'''", i)):
            q = '"""' if src.startswith('"""', i) else "'''"
            j = src.find(q, i + 3)
            j = n if j < 0 else j
            body = src[i + 3:j]
            out.append(q + re.sub(r'[^\n]', ' ', body) + q); i = j + 3; continue
        c = src[i]
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(c + re.sub(r'[^\n]', ' ', src[i + 1:j]) + c); i = j + 1; continue
        out.append(c); i += 1
    return ''.join(out)


def literals(src: str) -> list[tuple[int, str]]:
    """按行号取字符串字面量，含 Kotlin 三引号原始字符串。"""
    found, i, n, line = [], 0, len(src), 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1; i += 1; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            found.append((line, src[i + 3:j])); line += src.count('\n', i, j); i = j + 3; continue
        if c == '"':
            j, buf = i + 1, []
            while j < n and src[j] != '"' and src[j] != '\n':
                if src[j] == '\\':
                    j += 1
                buf.append(src[j]); j += 1
            found.append((line, ''.join(buf))); i = j + 1; continue
        i += 1
    return found


def words(text: str) -> Counter:
    return Counter(re.findall(r'[A-Za-z_]\w*', text))


def main() -> int:
    files = sorted(SRC.rglob('*.kt'))
    raw = {p: p.read_text(encoding='utf-8') for p in files}
    # decl 视图抹掉字符串（写在字符串里的 `fun foo` 不是声明）；计数视图保留字符串
    # （Kotlin 的 `"$name"` 模板是真引用，抹掉会造出一堆假残留）
    code = {p: blank_strings(strip_comments(v, False), False) for p, v in raw.items()}
    counted = {p: strip_comments(v, False) for p, v in raw.items()}
    main_cnt = words('\n'.join(v for k, v in counted.items() if MAIN in k.parents))
    test_cnt = words('\n'.join(v for k, v in counted.items() if TEST in k.parents))
    problems: list[str] = []

    for path, body in code.items():
        name, in_main = path.name, MAIN in path.parents
        lines = body.split('\n')
        for m in DECL.finditer(body):
            ln = body[:m.start()].count('\n')
            decl, ident = lines[ln].strip(), m.group(2)
            # override 与 @Test 由框架调用、main 由入口调用、测试类由 JUnit 加载、
            # XposedModule 入口类由框架按名字加载，都不计
            if 'override' in decl or decl.startswith('@Test') or ident in SKIP or 'XposedModule' in decl:
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
            body_words = set(re.findall(r'[A-Za-z_]\w*', IMPORT.sub('', counted[path])))
            for m in imports:
                used = m.group(2) or m.group(1).split('.')[-1]
                if used not in body_words:
                    problems.append(f'{name}:{body[:m.start()].count(chr(10)) + 1} import 无人使用  {used}')

    whole = '\n'.join(counted.values())
    names_of_files = {a for a in re.findall(r'getSharedPreferences\(\s*(\w+)', whole)}
    keys: dict[str, set] = {}
    for name, value in (kv for kv in KEY.findall(whole) if kv[0] not in names_of_files):
        keys.setdefault(value, set()).add(name)
    for value, names in sorted(keys.items()):
        alt = '|'.join(names)
        reads = len(re.findall(r'\.get\w+\(\s*(?:' + alt + r')\b', whole))
        writes = len(re.findall(r'\.put\w+\(\s*(?:' + alt + r')\b', whole))
        if reads == 0 or writes == 0:
            problems.append(f'prefs 键只有{"写" if reads == 0 else "读"}没有{"读" if reads == 0 else "写"}  {value}')

    py_files = sorted({p for d in PY_DIRS if d.exists() for p in d.glob('*.py')} - {Path(__file__)})
    py_raw = {p: p.read_text(encoding='utf-8') for p in py_files}
    py_text = {p: blank_strings(strip_comments(v, True), True) for p, v in py_raw.items()}
    py_cnt = words('\n'.join(strip_comments(v, True) for v in py_raw.values()))
    for path, body in py_text.items():
        is_test = path.name.startswith('test_')
        decls = [(m.group(1), PY_CLASS) for m in PY_CLASS.finditer(body)]
        decls += [(m.group(1), PY_DEF) for m in PY_DEF.finditer(body)]
        decls += [(m.group(1), PY_CONST) for m in PY_CONST.finditer(body)]
        for ident, kind in decls:
            # 测试文件里的类由 unittest 按 TestCase 发现、test_ 前缀的函数由 unittest 调用，都不计
            if ident in SKIP or (is_test and (kind is PY_CLASS or ident.startswith('test'))):
                continue
            if py_cnt[ident] <= 1:
                problems.append(f'{path.name}: 模块级无人引用  {ident}')
        for m in re.finditer(r'^\s+([a-z_]\w*) = ', body, re.M):
            if py_cnt[m.group(1)] <= 1:
                problems.append(f'{path.name}:{body[:m.start()].count(chr(10)) + 1} 局部变量只写不读  {m.group(1)}')
        for m in PY_IMPORT.finditer(body):
            if m.group(1):
                used = m.group(2) or m.group(1).split('.')[0]
            else:
                used = None
                tail = [t.strip().split(' as ')[-1].strip() for t in m.group(4).split(',')]
            names = [used] if used else tail
            for u in names:
                # 用含字符串的计数视图判「有没有人提过」：f-string 里的表达式也是真引用
                if u and u != '*' and py_cnt[u] <= 1:
                    problems.append(f'{path.name}: import 无人使用  {u}')

    fp: list[tuple[str, str, int]] = []
    scanned = 0
    for path in sorted(MAIN.rglob('*')):
        if path.suffix not in ('.kt', '.xml') or not path.is_file():
            continue
        scanned += 1
        src = path.read_text(encoding='utf-8')
        if path.suffix == '.xml':
            hits = [(i, v) for i, line in enumerate(src.split('\n'), 1) for v in re.findall(r'="([^"]*)"', line)]
        else:
            hits = literals(strip_comments(src, False))
        for ln, lit in hits:
            if NAME in lit.lower() and not any(path.name == f and lit == v for f, v, _ in ALLOW):
                fp.append((path.name, lit, ln))

    for f, lit, ln in fp:
        problems.append(f'{f}:{ln} 宿主可见字面量 {lit!r}')
    for f, v, _ in ALLOW:
        if not any(f2 == f and lit == v for f2, lit, _ in
                   [(p.name, l, 0) for p in MAIN.rglob('*') if p.suffix in ('.kt', '.xml')
                    for l in ([x for _, x in literals(p.read_text(encoding='utf-8'))] if p.suffix == '.kt'
                              else re.findall(r'="([^"]*)"', p.read_text(encoding='utf-8')))]):
            problems.append(f'白名单条目已不在源码中，请同步删掉  {f}:{v!r}')

    for line in problems:
        print(line)
    print(f'残留/指纹：Kotlin {len(files)} 个、Python {len(py_files)} 个、指纹面 {scanned} 个文件；'
          f'主码标识符 {main_cnt.total()}（测试另计 {test_cnt.total()}），白名单 {len(ALLOW)} 条，问题 {len(problems)} 处')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
