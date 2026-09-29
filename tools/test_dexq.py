#!/usr/bin/env python3
"""回归：DEX direct_methods / virtual_methods 的 method_idx_diff 分别从 0 起算。"""
import struct
import unittest

from dexq import methods_of


class FakeDex:
    def __init__(self):
        self.b = bytearray(128)
        self.ncls, self.ocl, self.nmth, self.omth, self.npro = 1, 0, 2, 96, 1
        struct.pack_into('<IIIIIIII', self.b, 0, 0, 0, 0, 0, 0, 0, 40, 0)
        # 0 static/instance fields; 1 direct / 1 virtual method, index 0 and 1.
        self.b[40:50] = bytes([0, 0, 1, 1, 0, 1, 0, 1, 1, 0])
        struct.pack_into('<HHI', self.b, 96, 0, 0, 0)
        struct.pack_into('<HHI', self.b, 104, 0, 0, 1)

    def types(self):
        return ['Lqq/Tab;']

    def strings(self):
        return ['<init>', 'onInterceptTouchEvent']

    def internal(self, desc):
        return 'qq.Tab'

    def proto(self, idx):
        return 'V '


class DexClassDataTest(unittest.TestCase):
    def test_virtual_method_index_resets(self):
        self.assertEqual([name for name, _, _ in methods_of(FakeDex(), 'qq.Tab')],
                         ['<init>', 'onInterceptTouchEvent'])


if __name__ == '__main__':
    unittest.main()
