"""开关分组与命名契约；菜单观感仍需设备验收。

30 个开关平铺一列就是「开关混乱」：找不着、记不住、同类开关的动词还各说各话。
这里把「每个开关都恰好进一个组」和「同一个意思只用同一个动词」钉成契约，
改名改漏了、新加开关忘分组了，都会在这里红。
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
QSELF = (ROOT / 'app/src/main/kotlin/sumicya/qself/Qself.kt').read_text()
KNOB = (ROOT / 'app/src/main/kotlin/sumicya/qself/Knob.kt').read_text()
DIAG = (ROOT / 'app/src/main/kotlin/sumicya/qself/DiagLog.kt').read_text()


def feature_names():
    """`Feature("名字", ...)` 里的名字，按声明顺序。"""
    return re.findall(r'^\s*Feature\(\s*"([^"]+)"', QSELF, re.M)


def group_table():
    """`val featureGroups` 里的 (组名, [开关...])；组可以跨行写。"""
    block = QSELF.split('val featureGroups', 1)[1].split('listOf(', 1)[1]
    depth, end = 1, len(block)
    for i, ch in enumerate(block):
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                end = i
                break
    block = block[:end]
    groups = []
    for m in re.finditer(r'"([^"]+)"\s*to\s*listOf\(([^()]*)\)', block, re.S):
        groups.append((m.group(1), re.findall(r'"([^"]+)"', m.group(2))))
    return groups


def is_cjk(ch):
    return '一' <= ch <= '鿿'


class SwitchGroupContractTest(unittest.TestCase):
    def test_every_feature_is_grouped_exactly_once(self):
        names = feature_names()
        self.assertTrue(names, '没解析到任何开关')
        grouped = [n for _, ns in group_table() for n in ns]
        self.assertEqual(sorted(names), sorted(set(grouped)),
                         '开关与分组表必须一一对应：多出来的、漏掉的都列在这儿')
        dupes = sorted({n for n in grouped if grouped.count(n) > 1})
        self.assertEqual([], dupes, f'这些开关被分进了多个组：{dupes}')

    def test_groups_are_non_empty_and_in_menu(self):
        for group, names in group_table():
            self.assertTrue(names, f'分组「{group}」是空的')
            self.assertIn(f'"{group}"', QSELF)
        self.assertEqual(len(group_table()), len({g for g, _ in group_table()}), '组名不能重复')

    def test_menu_renders_group_headings_not_a_flat_list(self):
        # 菜单必须按分组摆，不能再回到 features.forEach 平铺
        self.assertIn('featuresByGroup()', KNOB)
        self.assertIn('for ((group, list) in featuresByGroup())', KNOB)
        self.assertNotIn('features.forEach { grid.addView(cell(it)', KNOB)
        self.assertIn('featuresByGroup()', DIAG)  # 日志里的 Switches 也要分组，不能是一长条

    def test_name_verbs_are_consistent(self):
        # 藏/去/屏蔽 三个动词都在说「关掉一样东西」，一律收口成 隐藏 或 停止。
        banned = ('屏蔽', '藏', '去')
        for name in feature_names():
            self.assertFalse(name.startswith(banned),
                             f'「{name}」用了被废弃的动词；藏东西一律叫「隐藏…」，停行为一律叫「停止…」')

    def test_names_stay_short(self):
        for name in feature_names():
            cjk = sum(1 for ch in name if is_cjk(ch))
            self.assertLessEqual(cjk, 6, f'「{name}」太长，菜单一行放不下')


if __name__ == '__main__':
    unittest.main()
