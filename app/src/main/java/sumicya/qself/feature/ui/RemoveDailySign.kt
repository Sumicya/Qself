/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.ui

import android.widget.LinearLayout
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.QQVersion
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "ui.remove_daily_sign",
    name = "移除侧滑栏左上角打卡",
    summary = "移除 QQ 侧滑栏左上角的每日打卡入口",
    category = "ui",
)
object RemoveDailySign : SwitchFeature() {

    override val id: String = "ui.remove_daily_sign"
    override val name: String = "移除侧滑栏左上角打卡"
    override val summary: String = "移除 QQ 侧滑栏左上角的每日打卡入口"
    override val category: FeatureCategory = FeatureCategory.UI
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val hostInfo = ctx.hostInfo
        if (hostInfo.packageName != sumicya.qself.util.HostInfoProvider.PACKAGE_NAME_QQ) {
            return false
        }
        val host = ctx.host

        Hooks.allConstructorsIfEnabled(this, viewClass(host, hostInfo)) { param ->
            val signField = dailySignFieldName(hostInfo)
            val view = host.field(param.thisObject!!.javaClass, signField) ?: return@allConstructorsIfEnabled
            val layout = host.read(view, param.thisObject) as? LinearLayout ?: return@allConstructorsIfEnabled
            zeroSize(layout)
        }

        // NT variant (8.9.68+)
        val ntClass = host.resolve(
            if (hostInfo.isAtLeast(QQVersion.QQ_8_9_90)) {
                "com.tencent.mobileqq.QQSettingMeViewV9"
            } else {
                "com.tencent.mobileqq.activity.QQSettingMeViewV9"
            },
        )
        ntClass?.let { hookNtVariant(ctx, it) }
        return true
    }

    private fun viewClass(
        host: sumicya.qself.host.Host,
        hostInfo: sumicya.qself.util.HostInfo,
    ): Class<*> {
        val fqcn = when {
            hostInfo.isAtLeast(QQVersion.QQ_8_9_90) -> "com.tencent.mobileqq.QQSettingMeView"
            hostInfo.isAtLeast(QQVersion.QQ_8_9_25) -> "com.tencent.mobileqq.activity.QQSettingMeView"
            else -> "com.tencent.mobileqq.activity.QQSettingMe"
        }
        return host.require(fqcn)
    }

    private fun dailySignFieldName(hostInfo: sumicya.qself.util.HostInfo): String = when {
        hostInfo.isAtLeast(QQVersion.QQ_9_1_70) -> "k0"
        hostInfo.isAtLeast(QQVersion.QQ_9_1_50) -> "g0"
        hostInfo.isAtLeast(QQVersion.QQ_9_1_30) -> "c0"
        hostInfo.isAtLeast(QQVersion.QQ_9_0_90) -> "b0"
        hostInfo.isAtLeast(QQVersion.QQ_9_0_85) -> "d0"
        hostInfo.isAtLeast(QQVersion.QQ_9_0_35) -> "c0"
        hostInfo.isAtLeast(QQVersion.QQ_9_0_20) -> "a0"
        hostInfo.isAtLeast(QQVersion.QQ_9_0_0) -> "b0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_90) -> "e0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_88) -> "h0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_70) -> "h0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_68) -> "h0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_28) -> "i0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_25) -> "h0"
        hostInfo.isAtLeast(QQVersion.QQ_8_9_3) -> "d0"
        hostInfo.isAtLeast(QQVersion.QQ_8_8_93) -> "c0"
        hostInfo.isAtLeast(QQVersion.QQ_8_8_17) -> "O"
        hostInfo.isAtLeast(QQVersion.QQ_8_8_11) -> "N"
        hostInfo.versionCode == QQVersion.QQ_8_6_0 -> "b"
        else -> "a"
    }

    private fun hookNtVariant(ctx: FeatureContext, ntClass: Class<*>) {
        try {
            val host = ctx.host
            val bizPartsPrefix = when {
                ctx.hostInfo.isAtLeast(QQVersion.QQ_8_9_90) -> "com.tencent.mobileqq.bizParts"
                else -> "com.tencent.mobileqq.activity.qqsettingme.bizParts"
            }
            val partField = ntClass.declaredFields.firstOrNull { f ->
                f.type.name.contains(bizPartsPrefix) &&
                    f.type.declaredFields.count { it.type == LinearLayout::class.java } > 2
            } ?: return
            val partClass = partField.type
            partClass.declaredMethods.firstOrNull { it.name == "onInitView" }?.let { m ->
                m.isAccessible = true
                Hooks.afterIfEnabled(this, m) { param ->
                    val fields = param.thisObject!!.javaClass.declaredFields
                        .filter { it.type == LinearLayout::class.java }
                    if (fields.size > 1) {
                        val field = fields[1]
                        field.isAccessible = true
                        (field.get(param.thisObject) as? LinearLayout)?.let { zeroSize(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            QLog.w("RemoveDailySign", "NT variant skipped", t)
        }
    }

    private fun zeroSize(view: LinearLayout) {
        val lp = view.layoutParams ?: return
        lp.width = 0
        lp.height = 0
        view.layoutParams = lp
    }
}
