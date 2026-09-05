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

import java.lang.reflect.Method

/**
 * Port: interception of the host's screenshot-share helper (截屏分享悬浮窗).
 *
 * Ports are framework-neutral by design (RFC-03 §2.2): only JDK types and
 * lambdas appear here. The volatile knowledge — where the class/method lives
 * in each host version — is hidden behind adapters; the stable knowledge —
 * "suppress the helper when the user enabled the feature" — is expressed by
 * the callback parameters.
 *
 * The [ClassLoader] parameter of [resolveShowMethod] is the testable seam:
 * contract tests inject a fake host classloader (see RFC-03 §2.6), while the
 * feature passes the real host classloader on device.
 */
interface ScreenshotHelperApi {

    /**
     * Locate the static `show(Context, String, Handler)`-shaped entry method
     * of the host screenshot helper. Pure resolution: no hook is installed,
     * no exception escapes (defensive boundary is the adapter's contract).
     *
     * @return the target method, or null when the host provides no such
     *         capability (class renamed/removed by a host update)
     */
    fun resolveShowMethod(classLoader: ClassLoader): Method?

    /**
     * Install the suppression hook on a resolved [method].
     *
     * @param isEnabled consulted on every invocation; when false the original
     *        method runs untouched (runtime toggle semantics)
     * @param onError receives any throwable thrown by the interception logic
     *        (exception fence; the host method must never see our errors)
     * @return true when the hook was installed
     */
    fun installSuppressor(
        method: Method,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
