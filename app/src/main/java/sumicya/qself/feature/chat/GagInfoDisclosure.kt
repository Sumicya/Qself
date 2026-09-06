/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.feature.chat

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.bridge.ContactUtils
import io.github.qauxv.bridge.kernelcompat.ContactCompat
import io.github.qauxv.bridge.ntapi.ChatTypeConstants
import io.github.qauxv.bridge.ntapi.NtGrayTipHelper
import io.github.qauxv.bridge.ntapi.RelationNTUinAndUidApi
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.dexkit.CMessageRecordFactory
import io.github.qauxv.util.dexkit.Hd_GagInfoDisclosure_Method
import io.github.qauxv.util.dexkit.NContactUtils_getBuddyName
import io.github.qauxv.util.dexkit.NContactUtils_getDiscussionMemberShowName
import sumicya.qself.adapter.HostEnvironmentAdapter
import sumicya.qself.adapter.chat.GagNoticeAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.HostEnvironmentApi
import sumicya.qself.hostapi.chat.GagNoticeApi
import sumicya.qself.hostapi.chat.GagNoticeApi.GagEvent

/**
 * 显示设置禁言的管理 — RFC-03 §10, the domain-event terminal form.
 *
 * The feature is pure policy: it pattern-matches normalized gag events,
 * words them in Chinese and inserts an NT gray-tip message. Byte-level
 * vMsg parsing, generation branching and legacy push extraction are all
 * adapter knowledge (GagNoticeAdapter).
 */
@FunctionHookEntry
@UiItemAgentEntry
object GagInfoDisclosure : CommonSwitchFunctionHook(
    hookKey = "GagInfoDisclosure",
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF,
    targets = arrayOf(
        CMessageRecordFactory,
        NContactUtils_getDiscussionMemberShowName,
        NContactUtils_getBuddyName,
        Hd_GagInfoDisclosure_Method,
    ),
) {

    private const val CAPABILITY_KEY = "chat.gag_notice"

    private val api: GagNoticeApi = GagNoticeAdapter
    private val env: HostEnvironmentApi = HostEnvironmentAdapter

    override val name = "显示设置禁言的管理"

    override val description = "总是显示哪个管理员设置了禁言"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY

    override val isAvailable: Boolean
        get() = env.isNtKernel()

    override fun initOnce(): Boolean {
        val installed = api.installGagNotice(
            Initiator.getHostClassLoader(),
            onEvent = ::onGagEvent,
            isEnabled = { isEnabled },
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.ABSENT,
        )
        if (!installed) {
            Log.e("$CAPABILITY_KEY: gag notice source not installable")
        }
        return installed
    }

    private fun onGagEvent(event: GagEvent) {
        val selfUin = AppRuntimeHelper.getAccount()
        when (event) {
            is GagNoticeApi.AllGag -> addGagTipMsg(
                selfUin, event.troopUin, event.opUin, "0",
                if (event.enabled) 1L else 0L,
            )
            is GagNoticeApi.MemberGag -> addGagTipMsg(
                selfUin, event.troopUin, event.opUin, event.victimUin, event.seconds,
            )
        }
    }

    private fun getSecStr(sec: Long): String {
        val (min, hour, day) = Triple("分", "时", "天")
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = ((sec % 86400) % 3600) / 60
        val ret = StringBuilder()
        if (d > 0) ret.append(d).append(day)
        if (h > 0) ret.append(h).append(hour)
        if (m > 0) ret.append(m).append(min)
        return ret.toString()
    }

    private fun NtGrayTipHelper.NtGrayTipJsonBuilder.appendUserItem(uin: String, name: String) {
        val uid = RelationNTUinAndUidApi.getUidFromUin(uin).takeIf { it.isNullOrEmpty().not() }
            ?: "u_0000000000000000000000"
        this.append(NtGrayTipHelper.NtGrayTipJsonBuilder.UserItem(uin, uid, name))
    }

    private fun addGagTipMsg(
        selfUin: String?,
        troopUin: String,
        opUin: String,
        victimUin: String,
        victimTime: Long,
    ) {
        val opName = ContactUtils.getTroopMemberNick(troopUin, opUin)
        val victimName = ContactUtils.getTroopMemberNick(troopUin, victimUin)
        val builder = NtGrayTipHelper.NtGrayTipJsonBuilder()
        when (victimUin) {
            "0" -> {
                if (opUin == selfUin) {
                    builder.appendUserItem(selfUin!!, "你")
                } else {
                    builder.appendUserItem(opUin, opName)
                }
                builder.appendText(if (victimTime == 0L) "关闭了全员禁言" else "开启了全员禁言")
            }

            selfUin -> {
                builder.appendUserItem(selfUin!!, "你")
                builder.appendText("被")
                builder.appendUserItem(opUin, opName)
                builder.appendText(if (victimTime == 0L) "解除禁言" else "禁言${getSecStr(victimTime)}")
            }

            else -> {
                builder.appendUserItem(victimUin, victimName)
                builder.appendText("被")
                if (opUin == selfUin) {
                    builder.appendUserItem(selfUin!!, "你")
                } else {
                    builder.appendUserItem(opUin, opName)
                }
                builder.appendText(if (victimTime == 0L) "解除禁言" else "禁言${getSecStr(victimTime)}")
            }
        }
        NtGrayTipHelper.addLocalJsonGrayTipMsg(
            AppRuntimeHelper.getAppRuntime()!!,
            ContactCompat(ChatTypeConstants.GROUP, troopUin, ""),
            NtGrayTipHelper.createLocalJsonElement(
                NtGrayTipHelper.AIO_AV_GROUP_NOTICE.toLong(), builder.build().toString(), ""),
            true,
            true,
        ) { result, uin ->
            if (result != 0) {
                Log.e("GagInfoDisclosure error: addLocalJsonGrayTipMsg failed, result=$result, uin=$uin")
            }
        }
    }
}
