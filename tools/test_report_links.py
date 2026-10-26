"""汇报链接块：每条都得是点得开的完整 URL，缺项就不出现（不印空链接）。"""
import unittest

import report_links


class ReportLinksTest(unittest.TestCase):
    def test_every_line_is_a_full_clickable_url(self):
        lines = report_links.links(
            'Sumicya/Qself', 'abc1234', run=40150144442,
            artifacts=[(12581537741, 'Qself-26.10.25.4.3181', 422817)],
            base='f9c1fc0', spec='6dbf46c1', upstream=[('nagi-studio/skills', 'ec9db18b', 'skills/pondering/grill/SKILL.md')])
        self.assertEqual([
            '提交：https://github.com/Sumicya/Qself/commit/abc1234',
            'CI：https://github.com/Sumicya/Qself/actions/runs/40150144442',
            '产物：https://github.com/Sumicya/Qself/actions/runs/40150144442/artifacts/12581537741（Qself-26.10.25.4.3181，422817 字节）',
            '对比：https://github.com/Sumicya/Qself/compare/f9c1fc0...abc1234',
            '规范：https://github.com/Sumicya/selfs/blob/6dbf46c1/GLOBAL.md',
            '上游：https://github.com/nagi-studio/skills/blob/ec9db18b/skills/pondering/grill/SKILL.md',
        ], lines)
        for line in lines:
            self.assertIn('https://github.com/', line)

    def test_missing_items_are_omitted_not_blank_links(self):
        self.assertEqual(['提交：https://github.com/Sumicya/Qself/commit/abc1234'],
                         report_links.links('Sumicya/Qself', 'abc1234'))


if __name__ == '__main__':
    unittest.main()
