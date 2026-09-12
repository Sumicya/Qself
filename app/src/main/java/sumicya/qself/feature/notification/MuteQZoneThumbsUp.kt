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
package sumicya.qself.feature.notification

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Auxiliary
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.dexkit.CQzoneMsgNotify
import sumicya.qself.adapter.qzone.QZoneMsgNotifyAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.notification.QZoneMsgNotifyApi

/**
 * 被赞说说不提醒 — RFC-03 §5 second pilot (advanced form).
 *
 * The feature holds only policy: which notification descriptions to mute.
 * Everything volatile (which method, which argument carries the text) is
 * the adapter's [NotifierHandle]. Config compatibility: the hook key is
 * passed explicitly and equals the legacy class simple name.
 */
@FunctionHookEntry
@UiItemAgentEntry
object MuteQZoneThumbsUp : CommonSwitchFunctionHook(
    hookKey = "MuteQZoneThumbsUp",
    targets = arrayOf(CQzoneMsgNotify),
) {

    private const val CAPABILITY_KEY = "notification.qzone_thumbs_up"

    private val api: QZoneMsgNotifyApi = QZoneMsgNotifyAdapter

    override val name: String = "被赞说说不提醒"

    override val description: String = "不影响评论,转发或击掌的通知"

    override val uiItemLocation: Array<String> = Auxiliary.NOTIFICATION_CATEGORY

    override fun initOnce(): Boolean {
        val handle = api.resolveNotifier(Initiator.getHostClassLoader())
        if (handle == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("qzone msg notification entry not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val installed = api.installMute(
            handle,
            isEnabled = { isEnabled },
            shouldMute = ::shouldMute,
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
        )
        return installed
    }

    /** policy: mute like-notifications only, keep comments/reposts/high-fives. */
    private fun shouldMute(desc: String?): Boolean = desc != null
        && (desc.endsWith("赞了你的说说") || desc.endsWith("赞了你的分享") || desc.endsWith("赞了你的照片"))
}
