"""UI 证据检查器的字段读写解码回归；真包完整签名/调用边由 CI 直接检查。"""
import struct
from types import SimpleNamespace
import unittest

from ui_paths import field_accesses


def sample(words):
    code = struct.pack('<HHHHII', 2, 1, 0, 0, 0, len(words)) + struct.pack('<' + 'H' * len(words), *words)
    return SimpleNamespace(b=code + struct.pack('<HHI', 0, 1, 0), ofl=len(code),
                           types=lambda: ['LOwner;', 'LTarget;'], strings=lambda: ['content'],
                           internal=lambda name: name[1:-1])


class UIPathsTest(unittest.TestCase):
    def test_reads_do_not_prove_assignment(self):
        dex = sample([0x1054, 0, 0x105b, 0, 0x0e])
        self.assertEqual([('read', 'Owner#content:Target'), ('write', 'Owner#content:Target')],
                         field_accesses(dex, 0))

    def test_switch_payload_is_not_field_access(self):
        dex = sample([0x0100, 0x105b, 0])
        self.assertEqual([], field_accesses(dex, 0))


if __name__ == '__main__':
    unittest.main()
