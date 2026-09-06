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
package sumicya.qself.hostapi

/**
 * Port: stable queries about the host environment (RFC-02 §6.2 finally
 * cashed in). Born pull-based with exactly one member — the first feature
 * that needed it (GagInfoDisclosure) asked only "is this the NT kernel?".
 * Do not speculatively extend; grow it when a migrated feature asks.
 */
interface HostEnvironmentApi {

    /** True when the host runs the NT kernel (QQNT base activity present). */
    fun isNtKernel(): Boolean
}
