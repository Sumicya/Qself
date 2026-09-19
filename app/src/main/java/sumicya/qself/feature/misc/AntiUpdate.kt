/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.misc

import android.os.Message
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "misc.anti_update",
    name = "屏蔽更新",
    summary = "屏蔽 QQ 的更新弹窗、横幅与升级检查",
    category = "misc",
)
object AntiUpdate : SwitchFeature() {

    override val id: String = "misc.anti_update"
    override val name: String = "屏蔽更新"
    override val summary: String = "屏蔽 QQ 的更新弹窗、横幅与升级检查"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host

        val upgradeController = host.require(
            "com.tencent.mobileqq.upgrade.UpgradeController",
            "com.tencent.mobileqq.app.upgrade.UpgradeController",
        )
        val upgradeDetailWrapper = host.require(
            "com.tencent.mobileqq.upgrade.UpgradeDetailWrapper",
            "com.tencent.mobileqq.app.upgrade.UpgradeDetailWrapper",
        )

        // dialog: ConfigHandler.showUpgradeIfNecessary(UpgradeDetailWrapper)
        val configHandler = host.resolveSynthetic(
            "com.tencent.mobileqq.app.ConfigHandler",
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
        ) ?: throw ClassNotFoundException("ConfigHandler not found")
        val candidates = configHandler.declaredMethods.filter {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == upgradeDetailWrapper
        }
        if (candidates.isEmpty() || candidates.size > 3) {
            throw IllegalStateException("ConfigHandler candidates: ${candidates.size}")
        }
        candidates.forEach { m ->
            m.isAccessible = true
            Hooks.beforeIfEnabled(this, m) { it.skip() }
        }

        // yellow banner (newer versions)
        val banner = host.resolve(
            "com.tencent.mobileqq.activity.recent.bannerprocessor.UpgradeBannerProcessor",
        )
        if (banner != null) {
            val method = banner.declaredMethods.singleOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Message::class.java &&
                    it.parameterTypes[1] == java.lang.Long::class.java &&
                    it.parameterTypes[2] == java.lang.Boolean::class.java
            }
            method?.let {
                it.isAccessible = true
                Hooks.beforeIfEnabled(this, it) { it.skip() }
            }
        } else {
            // older versions: single-char factory methods on UpgradeController
            upgradeController.declaredMethods.filter {
                it.returnType == upgradeDetailWrapper &&
                    it.parameterTypes.isEmpty() &&
                    it.name.length == 1
            }.forEach { m ->
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip() }
            }
        }

        // silence the rest of UpgradeController
        upgradeController.declaredMethods.forEach { m ->
            if (m.returnType == Void.TYPE) {
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip() }
            } else if (m.returnType == java.lang.Boolean.TYPE) {
                m.isAccessible = true
                Hooks.beforeIfEnabled(this, m) { it.skip(false) }
            }
        }
        return true
    }
}
