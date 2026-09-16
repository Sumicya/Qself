/* SPDX-License-Identifier: GPL-3.0-or-later */
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
