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

import cc.ioctl.util.ExfriendManager
import io.github.qauxv.config.ConfigItems
import io.github.qauxv.hook.BasePersistBackgroundHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import sumicya.qself.adapter.chat.TroopMuteAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.chat.TroopMuteApi

/**
 * 屏蔽指定群的 @全体成员 和红包 — RFC-03 §11, the persist-background form.
 *
 * Faithful shape notes:
 *  - base class stays BasePersistBackgroundHook: an always-on background hook
 *    with no UI entry and no own switch (isEnabled is hard-wired true there);
 *  - the two mute lists live in the *legacy shared config* (ExfriendManager,
 *    keys qn_muted_at_all / qn_muted_red_packet). No code in this repository
 *    writes those keys anymore — they can only be populated from old backups
 *    or manual config edits; consumption is preserved bug-for-bug;
 *  - the original initOnce() returned true unconditionally (only an exception
 *    could fail it), so this port reports absent hook points through the
 *    CapabilityRegistry but still returns true.
 */
object MuteAtAllAndRedPacket : BasePersistBackgroundHook() {

    private const val CAPABILITY_AT_ALL = "chat.mute_at_all"
    private const val CAPABILITY_RED_PACKET = "chat.mute_red_packet"

    private val api: TroopMuteApi = TroopMuteAdapter

    override fun initOnce(): Boolean {
        val classLoader = Initiator.getHostClassLoader()
        val atAll = api.installAtAllMute(
            classLoader,
            isEnabled = { isEnabled },
            isMuted = { troopUin -> isTroopInList(ConfigItems.qn_muted_at_all, troopUin) },
            onError = { traceError(it) },
        )
        val redPacket = api.installRedPacketMute(
            classLoader,
            isMuted = { troopUin -> isTroopInList(ConfigItems.qn_muted_red_packet, troopUin) },
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_AT_ALL,
            if (atAll) CapabilityState.AVAILABLE else CapabilityState.ABSENT,
        )
        CapabilityRegistry.report(
            CAPABILITY_RED_PACKET,
            if (redPacket) CapabilityState.AVAILABLE else CapabilityState.ABSENT,
        )
        if (!atAll) {
            Log.e("$CAPABILITY_AT_ALL: at-all classifier hook point not found")
        }
        if (!redPacket) {
            Log.e("$CAPABILITY_RED_PACKET: MessageForQQWalletMsg.doParse hook point not found")
        }
        return true
    }

    /** Fresh read on every event — no caching, as the original did. */
    private fun isTroopInList(key: String, troopUin: String): Boolean {
        val rawList = ExfriendManager.getCurrent().getConfig().getString(key)
        return TroopMuteAdapter.isTroopInMutedList(rawList, troopUin)
    }

    override val targetProcesses = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF
}
