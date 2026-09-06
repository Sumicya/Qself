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
 * RFC-03 §11 batch-5 port: suppressing at-all notifications and red-packet
 * messages for user-configured troops.
 *
 * Unlike the batch-4 event port, this is a *query-style interception* port:
 * the hook sites need a synchronous boolean decision inside the hook body,
 * so the adapter calls back into the feature instead of delivering events.
 *
 * Semantics contract (bug-for-bug vs cc.ioctl.hook.bak.MuteAtAllAndRedPacket):
 *  - at-all site: hooks the unique `int (QQAppInterface, boolean, String)`
 *    method of MessageInfo; when the return value equals the at-all message
 *    type, a muted troop gets the return value rewritten to 0;
 *  - red-packet site: after `MessageForQQWalletMsg.doParse`, a muted *group*
 *    (istroop == 1) gets its message marked as already read.
 */
interface TroopMuteApi {

    /**
     * Installs the at-all classifier interception.
     *
     * @param classLoader host class loader
     * @param isEnabled guard evaluated on every invocation (original semantics:
     *        the hook body only runs when enabled and common hooks are licensed)
     * @param isMuted synchronous decision: is this troop in the at-all mute list
     * @param onError error sink; the adapter rethrows after reporting
     * @return true if the hook point was found and hooked
     */
    fun installAtAllMute(
        classLoader: ClassLoader,
        isEnabled: () -> Boolean,
        isMuted: (troopUin: String) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean

    /**
     * Installs the red-packet mark-read interception.
     *
     * @param classLoader host class loader
     * @param isMuted synchronous decision: is this troop in the red-packet mute list
     * @param onError error sink; the adapter rethrows after reporting
     * @return true if the hook point was found and hooked
     */
    fun installRedPacketMute(
        classLoader: ClassLoader,
        isMuted: (troopUin: String) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
