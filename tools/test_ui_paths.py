"""UI 证据检查器的字段读写解码回归；真包完整签名/调用边由 CI 直接检查。"""
import struct
from types import SimpleNamespace
import unittest

from ui_paths import field_accesses
from dexq import disasm, payload_end


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

    def test_switch_payload_in_the_middle_does_not_hide_following_instructions(self):
        dex = sample([0x0100, 1, 0, 0, 0, 0, 0x1054, 0, 0x0e])
        self.assertEqual([('read', 'Owner#content:Target')], field_accesses(dex, 0))
        self.assertEqual({'Owner#content:Target'}, disasm(dex, 0)[1])

    def test_arithmetic_operand_is_not_a_payload_header(self):
        dex = sample([0x0090, 0x0300, 0x1054, 0, 0x0e])
        self.assertEqual([('read', 'Owner#content:Target')], field_accesses(dex, 0))
        self.assertEqual({'Owner#content:Target'}, disasm(dex, 0)[1])

    def test_sparse_and_array_payload_lengths(self):
        sparse = struct.pack('<HHII', 0x0200, 1, 0, 0)
        self.assertEqual(12, payload_end(sparse, 0, len(sparse)))
        array = struct.pack('<HHI', 0x0300, 1, 3) + b'abc\x00'
        self.assertEqual(12, payload_end(array, 0, len(array)))
        self.assertIsNone(payload_end(array, 0, 10))

    def test_switch_payload_is_not_field_access(self):
        dex = sample([0x0100, 0x105b, 0])
        self.assertEqual([], field_accesses(dex, 0))


if __name__ == '__main__':
    unittest.main()
