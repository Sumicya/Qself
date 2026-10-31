"""DEX class_data 的 direct/virtual 差分索引必须分别从 0 开始。"""
import sys
from pathlib import Path

# 工具在 tools/，测试进标准测试目录；把工具目录挂上 import 路径再导入被测纯函数。
sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))

import struct
import unittest
from types import SimpleNamespace
from dexq import methods_of


class DexMethodsTest(unittest.TestCase):
    def fixture(self, wrong_owner=False):
        data = bytearray(192)
        struct.pack_into('<IIIIIIII', data, 16, 0, 0, 1, 0, 0, 0, 64, 0)
        # direct method_idx=2，virtual method_idx=1；virtual 不能接着 direct 的 2 累加。
        data[64:74] = bytes([0, 0, 1, 1, 2, 0, 0, 1, 0, 0])
        for i in range(4):
            owner = 1 if i == 3 or (wrong_owner and i == 1) else 0
            struct.pack_into('<HHI', data, 128 + 8 * i, owner, 0, i)
        return SimpleNamespace(b=data, ncls=1, ocl=16, nmth=4, omth=128,
                               types=lambda: ['LTarget;', 'LOther;'],
                               strings=lambda: ['unused', 'virtualMethod', 'directMethod', 'wrongMethod'],
                               internal=lambda s: s[1:-1], proto=lambda i: 'V ')

    def test_reset_index_between_direct_and_virtual(self):
        self.assertEqual(methods_of(self.fixture(), 'Target'),
                         [('directMethod', 'V ', 0), ('virtualMethod', 'V ', 0)])

    def test_access_flags_distinguish_private_helpers_from_public_methods(self):
        fixture = self.fixture()
        fixture.b[69] = 2
        fixture.b[72] = 1
        self.assertEqual([('directMethod', 'V ', 0, 2), ('virtualMethod', 'V ', 0, 1)],
                         methods_of(fixture, 'Target', with_access=True))

    def test_reject_method_owned_by_another_class(self):
        with self.assertRaisesRegex(ValueError, '所属类'):
            methods_of(self.fixture(wrong_owner=True), 'Target')


if __name__ == '__main__':
    unittest.main()
