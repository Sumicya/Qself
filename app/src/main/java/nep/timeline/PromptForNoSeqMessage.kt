/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2025 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package nep.timeline

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.ntapi.MsgConstants
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import me.ketal.dispacher.BaseBubbleBuilderHook

/** Config compatibility for the old switch; rendering is owned by ChatItemShowQQUin's shared tail. */
@UiItemAgentEntry
object PromptForNoSeqMessage : CommonSwitchFunctionHook() {
    override val name = "提示 NoSeq 消息"
    override val description = "未成功发送提示与消息 ID/时间共用尾注；有发送异常提示时优先显示提示"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.MESSAGE_CATEGORY
    override fun initOnce(): Boolean = isAvailable && BaseBubbleBuilderHook.initialize()

    fun shouldShowTailMsgForMsgRecord(chatMessage: MsgRecord): Boolean =
        chatMessage.msgType != MsgConstants.MSG_TYPE_GRAY_TIPS && MessageUtils.isNoSeqMessage(chatMessage.sendStatus)
}
