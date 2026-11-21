"""造反例回归：把 deadcode.py 的任一条防线关掉，这里必须有一条变红。

每条用例都是一个**恰好命中 1 条**的夹具，期望问题数写死成常量 1：
① 注释里提到过的死函数 —— 不剥注释就会凑够两次、漏报；
② Python 未使用的导入；
③ 测试目录里的死函数 —— 不纳入 app/src/test/python 就看不见；
④ 只写在 Kotlin 三引号原文里的名字 —— 不抽原始字符串就漏掉指纹。
"""
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]   # app/src/test/python/ -> 仓根
DEADCODE = ROOT / "tools" / "deadcode.py"

HITS = 1

# 两条「干净夹具」：残留判据要闭合成环，否则基线自己就红，测不出新判据有没有抓手
KT_CLEAN = """\
package demo
fun alpha() = beta()
fun beta() = alpha()
"""

KT_CLEAN_B = """\
package demo
fun beta() = alpha()
"""

KT_DIRTY = """\
package demo
fun beta(ctx: Any) = if (ctx.getInstalledPackages(0).isEmpty()) alpha() else alpha()
"""

# 夹具里的盲区清单：形状与真仓一致，path 指一个夹具里真会建出来的文件
BLINDSPOTS = """\
{
  "excluded": [{"path": "app/src/main/AndroidManifest.xml", "why": "夹具", "limit": "夹具"}],
  "deferred": [{"path": "tools/deadcode.py", "why": "夹具", "limit": "夹具"}],
  "open_questions": [{"path": "app/src/test/python", "why": "夹具", "limit": "夹具"}]
}
"""

# ① 死函数 dead() 只被定义一次；两种注释里的提及都必须不算引用
KT_COMMENT = """\
// dead() 曾经在这里被调用过一次，现在没人用了
/* dead() 在块注释里也提过一次 */
fun dead() = 1
"""

# ② json 从没被提起；used 有定义有调用
PY_IMPORT = """\
import json
import os


def used():
    return os.sep


used()
"""

# ③ 模块级死函数；TestCase 与 test_ 前缀由 unittest 调用，不算残留
PY_TEST_DEAD = """\
import unittest


def helper_never_called():
    return 1


class DemoTest(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(1, 1)
"""

# ④ 跨行三引号：里面那行 'fun fake() = 1' 是原文不是代码，qself 是宿主可见名字
KT_RAW = '''\
fun dead() = """
    fun fake() = 1
    qself 只出现在这段三引号原文里
""".length
'''


class DeadcodeRegressionTest(unittest.TestCase):
    def run_gate(self, files, blindspots=BLINDSPOTS, init_list="sumicya.qself.Qself\n"):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app/src/main/kotlin/demo").mkdir(parents=True)
            (root / "app/src/test/python").mkdir(parents=True)
            (root / "tools").mkdir()
            (root / "tools/deadcode.py").write_text(DEADCODE.read_text(encoding="utf-8"), encoding="utf-8")
            # 白名单要求的安装包标签：夹具里也要有，否则每条用例都会先被白名单缺席打红
            (root / "app/src/main/AndroidManifest.xml").write_text(
                '<manifest><application android:label="Qself" /></manifest>', encoding="utf-8")
            (root / "app/src/main/resources/META-INF/xposed").mkdir(parents=True, exist_ok=True)
            # init_list=None 表示这个文件不建：白名单里那条入口类清单会因此过期
            if init_list is not None:
                (root / "app/src/main/resources/META-INF/xposed/java_init.list").write_text(
                    init_list, encoding="utf-8")
            if blindspots is not None:
                (root / "tools/blindspots.json").write_text(textwrap.dedent(blindspots), encoding="utf-8")
            for rel, body in files.items():
                (root / rel).write_text(textwrap.dedent(body), encoding="utf-8")
            done = subprocess.run([sys.executable, str(root / "tools/deadcode.py")],
                                  capture_output=True, text=True, cwd=root)
            return done.returncode, done.stdout

    def test_comment_only_reference_does_not_hide_dead_code(self):
        code, out = self.run_gate({"app/src/main/kotlin/demo/A.kt": KT_COMMENT})
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("主码无人引用"), out)

    def test_unused_python_import_is_reported(self):
        code, out = self.run_gate({"tools/helper.py": PY_IMPORT})
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("import 无人使用"), out)
        self.assertIn("json", out)

    def test_dead_function_in_test_dir_is_reported(self):
        code, out = self.run_gate({"app/src/test/python/test_demo.py": PY_TEST_DEAD})
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("模块级无人引用"), out)
        self.assertIn("helper_never_called", out)

    def test_blindspot_with_unknown_key_is_reported(self):
        broken = BLINDSPOTS.replace('"excluded"', '"excludedd"')
        code, out = self.run_gate({"tools/helper.py": "x = 1\n"}, blindspots=broken)
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("盲区清单的键必须正好是"), out)

    def test_blindspot_pointing_at_missing_path_is_reported(self):
        broken = BLINDSPOTS.replace("app/src/main/AndroidManifest.xml", "app/src/main/nope.txt")
        code, out = self.run_gate({"tools/helper.py": "x = 1\n"}, blindspots=broken)
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("在仓里不存在"), out)
        self.assertIn("nope.txt", out)

    def test_empty_blindspot_class_is_reported(self):
        broken = BLINDSPOTS.replace(
            '"open_questions": [{"path": "app/src/test/python", "why": "夹具", "limit": "夹具"}]',
            '"open_questions": []')
        code, out = self.run_gate({"tools/helper.py": "x = 1\n"}, blindspots=broken)
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("open_questions 为空"), out)

    def test_blindspot_entry_missing_limit_is_reported(self):
        broken = BLINDSPOTS.replace(', "why": "夹具", "limit": "夹具"}', ', "why": "夹具"}', 1)
        code, out = self.run_gate({"tools/helper.py": "x = 1\n"}, blindspots=broken)
        self.assertEqual(1, code, out)
        self.assertIn("path/why/limit 三栏", out)

    def test_unregistered_suffix_in_product_face_is_reported(self):
        # 一个后缀没进 TEXT_FACES 也没进 BINARY_FACES 的文件：不报就等于它从没进过视野
        code, out = self.run_gate({
            "app/src/main/kotlin/demo/A.kt": KT_CLEAN,
            "app/src/main/kotlin/demo/B.kt": KT_CLEAN_B,
            "app/src/main/resources/unknown.bin": "x",
        })
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("没被指纹面覆盖"), out)
        self.assertIn("unknown.bin", out)

    def test_forbidden_api_call_in_main_code_is_reported(self):
        # 既没被残留判据抓（两个函数互引），也没被指纹抓（不含模块名），只剩禁查这一条能红
        code, out = self.run_gate({
            "app/src/main/kotlin/demo/A.kt": KT_CLEAN,
            "app/src/main/kotlin/demo/B.kt": KT_DIRTY,
        })
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("禁查 API"), out)
        self.assertIn("getInstalledPackages", out)

    def test_expired_allow_entry_is_reported(self):
        # 白名单里的东西从产物里消失时必须自己报错：否则「我知道它存在」变成「我以为它还在」
        code, out = self.run_gate({
            "app/src/main/kotlin/demo/A.kt": KT_CLEAN,
            "app/src/main/kotlin/demo/B.kt": KT_CLEAN_B,
        }, init_list=None)
        self.assertEqual(1, code, out)
        self.assertEqual(HITS, out.count("白名单条目已不在产物里"), out)
        self.assertIn("java_init.list", out)

    def test_clean_fixture_passes(self):
        # 对照：同样的夹具去掉那两样，必须绿。否则上面两条测试证明不了自己是抓手
        code, out = self.run_gate({
            "app/src/main/kotlin/demo/A.kt": KT_CLEAN,
            "app/src/main/kotlin/demo/B.kt": KT_CLEAN_B,
        })
        self.assertEqual(0, code, out)

    def test_raw_string_is_read_whole_not_line_by_line(self):
        code, out = self.run_gate({"app/src/main/kotlin/demo/B.kt": KT_RAW})
        self.assertEqual(1, code, out)
        # 只看单行的话，三引号第二行会被当成代码：fake 变成一条假残留、qself 反而不算字面量
        self.assertEqual(HITS, out.count("宿主可见字面量"), out)
        dead_lines = [line for line in out.splitlines() if "主码无人引用" in line]
        self.assertEqual(HITS, len(dead_lines), out)
        self.assertNotIn("fake", dead_lines[0])
        self.assertIn("qself", out)


if __name__ == "__main__":
    unittest.main()
