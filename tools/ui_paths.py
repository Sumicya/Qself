#!/usr/bin/env python3
"""QQ 9.2.10 UI 的静态绑定证据。核完整签名/读写方向/调用边，不冒充设备命中或视觉验证。"""
import hashlib
import json
import struct
import sys
import zipfile

from dexcheck import Dex
from dexq import W, disasm, methods_of

FRAME = 'com.tencent.mobileqq.tab.TabFrameLayout'
DELEGATE = 'com.tencent.mobileqq.aio.input.edit.delegate.InputEditTextVBDelegate'
BADGE = 'com.tencent.mobileqq.quibadge.QUIBadge'
INJECT = 'com.tencent.mobileqq.activity.framebusiness.controllerinject.FrameControllerInjectImpl'
CONTROLLER = 'com.tencent.mobileqq.activity.home.impl.TabFrameControllerImpl'

VB = 'com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB'
COMPONENT = 'com.tencent.mobileqq.aio.msglist.holder.component.c'
NICK = 'com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2'
DIFFER = 'com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.differ.AutoCancelAsyncListDiffer'
CALLBACK = 'com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.differ.e'
CONSTRAINT = 'androidx.constraintlayout.widget.ConstraintLayout'
WIDGET = 'androidx.constraintlayout.solver.widgets.ConstraintWidget'

# 完整签名 -> 必须出现的字段/调用。与运行时代码使用的角色一致，不只核方法名存在。
RULES = {
    (FRAME, 'a', 'android.view.View '): ({f'{FRAME}#d:androidx.viewpager2.widget.ViewPager2'}, set()),
    (FRAME, 'onFinishInflate', 'V '): (
        {f'{FRAME}#d:androidx.viewpager2.widget.ViewPager2', f'{FRAME}#e:com.tencent.mobileqq.widget.QQTabLayout'}, set()),
    (DELEGATE, 'U', 'V com.tencent.mobileqq.aio.input.adorn.e'): (
        {f'{DELEGATE}#e:android.widget.EditText'}, {'com.tencent.mobileqq.aio.input.adorn.e#a'}),
    (CONTROLLER, 'updateFrames', 'V com.tencent.mobileqq.app.FrameFragment,I,I,java.lang.Object'): (
        set(), {'com.tencent.mobileqq.activity.home.x#b'}),
    (INJECT, 'b', f'V {BADGE},I,java.lang.String,I,I'): (set(), {'com.tencent.widget.d#a'}),
    ('com.tencent.widget.d', 'a', f'V {BADGE},I,I,I,java.lang.String'): (
        set(), {f'{BADGE}#setRedNum', f'{BADGE}#setRedText'}),
    (BADGE, 'setRedText', 'V java.lang.String'): (set(), {f'{BADGE}#resetValue'}),
    (BADGE, 'resetValue', 'V '): ({f'{BADGE}#mNum:I'}, set()),
    (VB, 'H1', 'V com.tencent.mobileqq.aio.msg.AIOMsgItem'): (
        {'android.view.ViewGroup$MarginLayoutParams#topMargin:I'}, {f'{COMPONENT}#i1'}),
    (VB, 'j1', 'V I'): ({f'{VB}#G:android.util.SparseArray'},
        {'com.tencent.mobileqq.aio.msglist.holder.g#a', f'{COMPONENT}#i1', f'{COMPONENT}#f1'}),
    (NICK, 'd1', 'V I,com.tencent.aio.data.msglist.a,java.util.List'): (
        set(), {f'{COMPONENT}#i1', 'android.view.View#setVisibility'}),
    (NICK, 'f1', 'android.view.View '): (set(), {f'{NICK}#v1'}),
    (COMPONENT, 'i1', 'android.view.View '): ({f'{COMPONENT}#d:android.view.View'}, set()),
    (DIFFER, 'j', f'V java.util.List,{CALLBACK}'): (set(), {f'{CALLBACK}#a'}),
    (DIFFER, 'h', f'V java.util.List,androidx.recyclerview.widget.DiffUtil$DiffResult,{CALLBACK}'): (
        {f'{DIFFER}#e:java.util.List'}, {'androidx.recyclerview.widget.DiffUtil$DiffResult#dispatchUpdatesTo', f'{DIFFER}#j'}),
    (CONSTRAINT, 'onLayout', 'V Z,I,I,I,I'): (
        set(), {f'{WIDGET}#getY', f'{WIDGET}#getHeight', 'android.view.View#layout'}),
}


def field_accesses(dex, offset):
    """记录实际字段读写方向，避免把引用字段当成已证明字段赋值。"""
    p = offset + 16
    end = p + 2 * struct.unpack_from('<I', dex.b, offset + 12)[0]
    types, strings = dex.types(), dex.strings()
    accesses = []
    while p < end:
        word = struct.unpack_from('<H', dex.b, p)[0]
        op = word & 255
        if op == 0 and word >> 8:
            break
        if 0x52 <= op <= 0x6D:
            index = struct.unpack_from('<H', dex.b, p + 2)[0]
            owner, typ, name = struct.unpack_from('<HHI', dex.b, dex.ofl + 8 * index)
            mode = 'write' if 0x59 <= op <= 0x5F or 0x67 <= op <= 0x6D else 'read'
            accesses.append((mode, f'{dex.internal(types[owner])}#{strings[name]}:{dex.internal(types[typ])}'))
        p += 2 * W[op]
    return accesses


def verify(apk):
    pending = dict(RULES)
    proof = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('.dex'):
                continue
            dex = Dex(archive.read(name), name)
            for owner in sorted({key[0] for key in pending}):
                for method, signature, offset in methods_of(dex, owner) or []:
                    key = owner, method, signature
                    if key not in pending:
                        continue
                    assert offset, f'目标不能是 abstract/native: {key}'
                    _, fields, calls = disasm(dex, offset)
                    wanted_fields, wanted_calls = pending.pop(key)
                    assert wanted_fields <= fields, (key, wanted_fields - fields)
                    assert wanted_calls <= calls, (key, wanted_calls - calls)
                    access = field_accesses(dex, offset)
                    if owner == FRAME:
                        mode = 'read' if method == 'a' else 'write'
                        assert all((mode, field) in access for field in wanted_fields), (key, access)
                    elif owner == DELEGATE or method == 'resetValue' or (owner == DIFFER and method == 'h'):
                        assert all(('write', field) in access for field in wanted_fields), (key, access)
                    proof.append({'dex': name, 'method': f'{owner}#{method} {signature}',
                                  'fields': sorted(wanted_fields), 'calls': sorted(wanted_calls),
                                  'field_access': access})
    assert not pending, f'完整签名没有匹配: {list(pending)}'
    return proof


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('用法: python3 tools/ui_paths.py qq.apk')
    with open(sys.argv[1], 'rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    print(json.dumps({'apk_sha256': digest, 'static_paths': verify(sys.argv[1]),
                      'device_execution_verified': False}, ensure_ascii=False, indent=2))
