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
package sumicya.qself.adapter

import io.github.qauxv.util.Initiator
import sumicya.qself.hostapi.HostEnvironmentApi

/**
 * Adapter for stable host-environment queries (RFC-03 §10). Replaces the
 * author-package QAppUtils.isQQnt() probe with a port-backed equivalent.
 */
object HostEnvironmentAdapter : HostEnvironmentApi {

    private const val NT_BASE_ACTIVITY = "com.tencent.qqnt.base.BaseActivity"

    override fun isNtKernel(): Boolean =
        runCatching { Initiator.load(NT_BASE_ACTIVITY) != null }.getOrDefault(false)
}
