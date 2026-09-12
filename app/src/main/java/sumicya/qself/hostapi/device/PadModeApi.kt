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
package sumicya.qself.hostapi.device

/**
 * RFC-03 §12 batch-6 port: forcing the tablet (pad) host mode.
 *
 * The spoof point is the AppSetting appId reader: its (obfuscated,
 * version-dependent) int-returning method is hooked after, and the result
 * is overwritten with the value of the (equally obfuscated) TABLET-side
 * static field of the same class.
 *
 * Failure contract — faithful to the original ezxhelper-based code: an
 * absent hook point (AppSetting class, reader method, or TABLET field)
 * THROWS instead of returning false. The original findMethod failed by
 * exception and the hook framework turned that into a traced init failure;
 * this port keeps that behavior.
 *
 * Environment facts (host identity, version) are injected by the caller —
 * the adapter never reaches for global host state itself.
 */
interface PadModeApi {

    /**
     * Installs the appId spoof for the running host generation.
     *
     * @param classLoader host class loader
     * @param hostIsTim true when running inside TIM (its table row differs)
     * @param hostVersionCode the host's numeric version code
     * @return true once installed; absence of the hook point throws
     */
    fun installForcePadAppId(
        classLoader: ClassLoader,
        hostIsTim: Boolean,
        hostVersionCode: Long,
    ): Boolean
}
