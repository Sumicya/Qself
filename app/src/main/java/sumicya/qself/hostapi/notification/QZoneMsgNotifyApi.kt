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
package sumicya.qself.hostapi.notification

import java.lang.reflect.Method

/**
 * Port: the host's QZone push notification entry (被赞说说通知).
 *
 * Second pilot (RFC-03 §5), the advanced form: the port hands back a
 * **domain handle** instead of a raw [Method] — the volatile analysis
 * ("which of the widest void method's parameters carries the notification
 * description") happens inside the adapter and is summarized as
 * [NotifierHandle.descArgIndex]. Features reason about notification text,
 * not reflection details.
 */
interface QZoneMsgNotifyApi {

    /**
     * Resolved host knowledge: the notification entry method plus the
     * parameter index carrying the human-readable description.
     */
    class NotifierHandle(val method: Method, val descArgIndex: Int) {
        override fun toString(): String =
            "NotifierHandle(${method.declaringClass.name}#${method.name}, desc@$descArgIndex)"
    }

    /**
     * Pure resolution against the given classloader (testable seam).
     *
     * @return the handle, or null when the host provides no such
     *         capability (class renamed/removed, or the widest void method
     *         carries fewer than two String parameters)
     */
    fun resolveNotifier(classLoader: ClassLoader): NotifierHandle?

    /**
     * Install the mute hook: when [isEnabled] and [shouldMute] agree on the
     * notification description, the notification is suppressed.
     *
     * @param onError exception fence for interception logic failures
     * @return true when installed
     */
    fun installMute(
        handle: NotifierHandle,
        isEnabled: () -> Boolean,
        shouldMute: (String?) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
