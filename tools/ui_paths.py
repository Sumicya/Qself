#!/usr/bin/env python3
"""QQ 9.2.10 UI 的静态绑定证据。核完整签名/读写方向/调用边，不冒充设备命中或视觉验证。"""
import hashlib
import json
import struct
import sys
import zipfile

from dexcheck import Dex
from dexq import W, disasm, methods_of, payload_end

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
    ('com.tencent.mobileqq.aio.msglist.holder.component.nick.slot.AIONickComponentV4', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (
        set(), {f'{COMPONENT}#i1', 'android.view.View#setVisibility'}),
    (BADGE, 'onLayout', 'V Z,I,I,I,I'): (
        {f'{BADGE}#mDelegateRect:android.graphics.Rect'},
        {f'{BADGE}#getExtendHitRect', 'android.view.View#onLayout'}),
    (BADGE, 'initPaint', 'V '): (
        {'android.graphics.Paint$Align#CENTER:android.graphics.Paint$Align', f'{BADGE}#mTextPaint:android.graphics.Paint'},
        {'android.graphics.Paint#setTextAlign'}),
    (BADGE, 'drawText', 'V android.graphics.Canvas'): (
        {f'{BADGE}#mTextPaint:android.graphics.Paint'}, {f'{BADGE}#drawBackground', f'{BADGE}#drawBorder', 'android.graphics.Canvas#drawText'}),
}

FORWARD = 'com.tencent.mobileqq.activity.ForwardRecentActivity'
MENU = 'com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout'
BASE_CONTENT = 'com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent'
RULES.update({
    (FORWARD, 'getForwardTargetKey', 'java.lang.String java.lang.String,I'): (set(), {'java.lang.StringBuilder#toString'}),
    (FORWARD, 'setSearchSelectedAndJoinedUins', 'V '): (set(), {f'{FORWARD}#getSelectedUins', f'{FORWARD}#getJoinUins'}),
    (FORWARD, 'refreshRightBtn', 'V '): ({f'{FORWARD}#mForwardTargetMap:java.util.Map'}, {'java.util.Map#size'}),
    ('com.tencent.qqnt.base.widget.RoundRectImageView', 'checkPathChanged', 'V '):
        ({'com.tencent.qqnt.base.widget.RoundRectImageView#mRoundMode:I'}, {'android.graphics.Path#addRoundRect'}),
    ('com.tencent.qqnt.base.widget.RoundRectImageView', 'setCornerRadiusAndMode', 'V I,I'):
        ({'com.tencent.qqnt.base.widget.RoundRectImageView#mRadius:I', 'com.tencent.qqnt.base.widget.RoundRectImageView#mRoundMode:I'}, set()),
    ('com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.file.AIOFileContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.marketface.AIOMarketFaceComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.video.AIOVideoContentComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (set(), {f'{BASE_CONTENT}#d1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.timestamp.AIOTimestampComponent', 'd1',
     'V I,com.tencent.aio.data.msglist.a,java.util.List'): (
        set(), {'com.tencent.mobileqq.aio.msglist.holder.component.timestamp.AIOTimestampComponent#u1'}),
    (MENU, 'p', 'android.view.View I,com.tencent.qqnt.aio.menu.ui.d,Z,[F'): (
        set(), {'com.tencent.qqnt.aio.menu.ui.d#f', 'android.view.View#setOnClickListener', 'android.widget.TextView#setTextColor'}),
    (MENU, 'l', 'android.widget.ImageView I'): (
        set(), {'android.view.View#setOnClickListener', f'{MENU}#B'}),
    (MENU, 'H', 'V '): (
        {f'{MENU}#Q:android.widget.ScrollView', f'{MENU}#R:android.widget.LinearLayout'},
        {f'{MENU}#removeAllViews'}),
    (MENU, 'G', 'V '): (set(), {f'{MENU}#H'}),
    ('com.tencent.qqnt.aio.widget.SelectableTextView', 'onTouchEvent', 'Z android.view.MotionEvent'): (set(), set()),
    ('com.tencent.mobileqq.aio.title.AIOTitleVB', 'onCreateView', 'android.view.View com.tencent.mvi.api.help.a'): (
        {'com.tencent.mobileqq.aio.title.AIOTitleVB#d:sm1.u'}, {'android.view.LayoutInflater#from'}),
    ('com.tencent.mobileqq.aio.title.AIOTitleVB', 'bindViewAndData', 'V '): (
        set(), {'com.tencent.mobileqq.aio.title.AIOTitleVB#r1', 'com.tencent.mobileqq.aio.title.AIOTitleVB#u1'}),
    ('com.tencent.mobileqq.aio.title.AIOTitleVB', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.title.AIOTitleVB#C1'}),
    ('com.tencent.mobileqq.aio.title.center.CenterVB', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.title.center.CenterVB#J1'}),
    ('com.tencent.mobileqq.aio.widget.AIOTitleRelativeLayout', 'onMeasure', 'V I,I'): (
        set(), {'android.view.View#setMeasuredDimension', 'com.tencent.mobileqq.aio.widget.g#v'}),
    ('com.tencent.mobileqq.aio.widget.AIOTitleRelativeLayout', 'onLayout', 'V Z,I,I,I,I'): (
        set(), {'com.tencent.mobileqq.aio.widget.g#u'}),
    ('com.tencent.mobileqq.aio.reserve1.blur.f', 'onCreateView', 'android.view.View com.tencent.mvi.api.help.a'): (
        {'com.tencent.mobileqq.aio.reserve1.blur.f#d:sm1.t'}, {'android.view.LayoutInflater#from'}),
    ('com.tencent.mobileqq.aio.reserve1.blur.f', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.reserve1.blur.f#f1'}),
    ('com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB', 'onCreateView', 'android.view.View com.tencent.mvi.api.help.a'): (
        set(), {'com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB#i1'}),
    ('com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB#k1'}),
    ('com.tencent.mobileqq.aio.input.AIOInputExpandableVB', 'onCreateView', 'android.view.View com.tencent.mvi.api.help.a'): (
        {'com.tencent.mobileqq.aio.input.AIOInputExpandableVB#e:android.widget.LinearLayout'}, {'android.view.View#setLayoutParams'}),
    ('com.tencent.mobileqq.aio.input.AIOInputExpandableVB', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.input.AIOInputExpandableVB#m1'}),
    ('com.tencent.mobileqq.aio.input.inputbar.compat.b', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.input.inputbar.compat.b#h'}),
    ('com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB', 'onCreateView', 'android.view.View com.tencent.mvi.api.help.a'): (
        set(), {'com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB#j1'}),
    ('com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB', 'handleUIState', 'V com.tencent.mvi.base.mvi.MviUIState'): (
        set(), {'com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB#o1'}),
    ('com.tencent.mobileqq.aio.msglist.holder.base.BaseMixContentComponent', 'C2', 'V com.tencent.mobileqq.aio.msglist.holder.skin.AIOBubbleSkinInfo'): (
        set(), {'com.tencent.mobileqq.aio.msglist.holder.base.BaseMixContentComponent#t2'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent', 'C2', 'V com.tencent.mobileqq.aio.msglist.holder.skin.AIOBubbleSkinInfo'): (
        {'android.widget.LinearLayout$LayoutParams#leftMargin:I', 'android.widget.LinearLayout$LayoutParams#topMargin:I'},
        {'android.graphics.drawable.Drawable#mutate'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent', 'K1', 'V android.content.Context,com.tencent.mobileqq.aio.msg.AIOMsgItem'): (
        {'com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent#m:java.lang.Object'},
        {'com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent#C1', 'com.tencent.qqnt.avatar.b$a#i'}),
    ('com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent', 'D1', 'android.graphics.drawable.Drawable '): (
        set(), {'com.tencent.mobileqq.utils.BaseImageUtil#getCircleFaceBitmap'}),
    ('com.tencent.qqnt.avatar.meta.b$a', 'm', 'com.tencent.qqnt.avatar.meta.b$a Z'): (
        {'com.tencent.qqnt.avatar.meta.b$a#g:Z'}, set()),
    ('com.tencent.qqnt.avatar.meta.b$a', 'b', 'com.tencent.qqnt.avatar.meta.b '): (
        set(), {'com.tencent.qqnt.avatar.meta.b#<init>'}),
    ('com.tencent.qqnt.avatar.b$a', 'i', 'V com.tencent.qqnt.avatar.i'): (
        {'com.tencent.qqnt.avatar.b$a#f:com.tencent.qqnt.avatar.i'}, set()),
    ('com.tencent.mobileqq.aio.msg.TextMsgContent', 'H', 'V com.tencent.mobileqq.aio.msg.ai'): (
        {'com.tencent.mobileqq.aio.msg.TextMsgContent#e:com.tencent.fastertextview.wrapper.a'},
        {'com.tencent.mobileqq.aio.msg.TextMsgContent#v', 'com.tencent.mobileqq.aio.msg.TextMsgContent#w'}),
    ('com.tencent.mobileqq.aio.msglist.holder.skin.padding.j', 'a', 'com.tencent.mobileqq.aio.msglist.holder.skin.padding.a com.tencent.mobileqq.aio.msg.AIOMsgItem'): (
        {'com.tencent.mobileqq.aio.msglist.holder.skin.padding.j#a:I', 'com.tencent.mobileqq.aio.msglist.holder.skin.padding.j#b:I'},
        {'com.tencent.mobileqq.aio.msg.AIOMsgItem#isSelf'}),
    ('com.tencent.mobileqq.aio.msglist.holder.template.g', 'q', 'V Z'): (
        {'com.tencent.mobileqq.aio.msglist.holder.template.g#a:sm1.h'},
        {'android.view.ViewGroup$MarginLayoutParams#setMarginStart', 'android.view.ViewGroup$MarginLayoutParams#setMarginEnd'}),
    ('com.tencent.mobileqq.aio.msglist.holder.template.l', 'q', 'V Z'): (
        {'com.tencent.mobileqq.aio.msglist.holder.template.l#a:sm1.r'},
        {'android.view.ViewGroup$MarginLayoutParams#setMarginEnd'}),
})


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
            after = payload_end(dex.b, p, end)
            if after is None:
                break
            p = after
            continue
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
                for method, signature, offset, flags in methods_of(dex, owner, with_access=True) or []:
                    key = owner, method, signature
                    if key not in pending:
                        continue
                    if owner == FORWARD and method != 'setSearchSelectedAndJoinedUins':
                        assert not flags & 1, (key, "非 public，运行时必须 declared + accessible", flags)
                    if owner == MENU and method in ('p', 'l', 'H'):
                        assert not flags & 1, (key, "非 public，运行时必须 declared + accessible", flags)
                    if owner == 'com.tencent.qqnt.base.widget.RoundRectImageView' and method == 'checkPathChanged':
                        # 固定 QQ 9.2.10：const/4 v3,1；if-eq v2,v3 跳向四角等半径分支，而非把 mode 当位掩码。
                        assert struct.unpack_from('<H', dex.b, offset + 0x50)[0] == 0x1312
                        opcode, delta = struct.unpack_from('<Hh', dex.b, offset + 0x54)
                        assert opcode == 0x3232
                        target = offset + 0x54 + 2 * delta
                        assert struct.unpack_from('<H', dex.b, target)[0] == 0xe254
                        method_index = struct.unpack_from('<H', dex.b, target + 0x20)[0]
                        _, prototype, _ = struct.unpack_from('<HHI', dex.b, dex.omth + 8 * method_index)
                        assert dex.proto(prototype) == 'V android.graphics.RectF,F,F,android.graphics.Path$Direction'
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
                                  'field_access': access, 'access_flags': flags})
    assert not pending, f'完整签名没有匹配: {list(pending)}'
    return proof


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('用法: python3 tools/ui_paths.py qq.apk')
    with open(sys.argv[1], 'rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    print(json.dumps({'apk_sha256': digest, 'static_paths': verify(sys.argv[1]),
                      'device_execution_verified': False}, ensure_ascii=False, indent=2))
