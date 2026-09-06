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
package sumicya.qself.hostapi.chat

/**
 * Port: troop gag (禁言) notices as **domain events** — the terminal form
 * of the pilot series (RFC-03 §10). The adapter owns every volatile detail
 * (which method to hook per generation, raw byte-array offsets, signed
 * uin fixups, legacy push-parameter extraction) and emits semantic events;
 * features only decide wording and what to insert into the chat.
 */
interface GagNoticeApi {

    /** Normalized gag event; features pattern-match on the two shapes. */
    sealed interface GagEvent

    /** 全员禁言开关（opUin 操作者）。 */
    class AllGag(
        val troopUin: String,
        val opUin: String,
        val enabled: Boolean,
    ) : GagEvent

    /** 单成员禁言/解禁（seconds == 0 表示解禁）。 */
    class MemberGag(
        val troopUin: String,
        val opUin: String,
        val victimUin: String,
        val seconds: Long,
    ) : GagEvent

    /**
     * Resolve the host notice source for the running generation and start
     * delivering normalized events to [onEvent] (only while [isEnabled]
     * holds; interception errors go to [onError]).
     *
     * @return false when the host capability is absent — nothing installed
     */
    fun installGagNotice(
        classLoader: ClassLoader,
        onEvent: (GagEvent) -> Unit,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
