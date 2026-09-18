/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.qzone

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.QQVersion
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "qzone.hide_title_bar_entrance",
    name = "隐藏空间动态\"此刻\"",
    summary = "隐藏 QQ 空间动态的\"此刻\"按钮或横幅",
    category = "qzone",
)
object HideQZMTitleBarEntrance : SwitchFeature() {

    override val id: String = "qzone.hide_title_bar_entrance"
    override val name: String = "隐藏空间动态\"此刻\""
    override val summary: String = "隐藏 QQ 空间动态的\"此刻\"按钮或横幅"
    override val category: FeatureCategory = FeatureCategory.QZONE
    override val experimental: Boolean = true
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host
        val installed = ArrayList<String>(2)

        // main path: QZMTitleBarEntranceManager.onInit(Context, ViewGroup, ImageView)
        try {
            val manager = host.require("com.qzone.reborn.qzmoment.itemview.QZMTitleBarEntranceManager")
            val rId = host.require("com.tencent.mobileqq.R\$id")
                .getDeclaredField("qzm_entrance_root").apply { isAccessible = true }
            val entranceRootId = rId.get(null) as Int
            check(entranceRootId != 0) { "qzm_entrance_root not found" }
            val method = manager.declaredMethods.singleOrNull {
                it.returnType == Void.TYPE &&
                    !it.isStatic &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == ViewGroup::class.java &&
                    it.parameterTypes[2] == ImageView::class.java
            }
            if (method != null) {
                method.isAccessible = true
                Hooks.afterIfEnabled(this, method) { param ->
                    val view = param.args[1] as? ViewGroup ?: return@afterIfEnabled
                    val entrance: View? = view.findViewById(entranceRootId)
                    entrance?.visibility = View.GONE
                    param.skip()
                }
                installed += "entrance"
            }
        } catch (t: Throwable) {
            QLog.w("HideQZMTitleBarEntrance", "main path skipped", t)
        }

        // banner path: QZoneFeedxTopEntranceManagerView.<obfuscated>()
        try {
            val bannerClass = host.require("com.qzone.reborn.feedx.widget.entrance.QZoneFeedxTopEntranceManagerView")
            val methodName = when {
                ctx.hostInfo.isAtLeast(QQVersion.QQ_9_0_30) -> "c0"
                ctx.hostInfo.isAtLeast(QQVersion.QQ_9_0_25) -> "e0"
                ctx.hostInfo.isAtLeast(QQVersion.QQ_9_0_20) -> "f0"
                else -> "e0"
            }
            val method = host.method(bannerClass, methodName)
            if (method != null) {
                method.isAccessible = true
                Hooks.beforeIfEnabled(this, method) { param ->
                    (param.thisObject as? View)?.isClickable = false
                    param.skip()
                }
                installed += "banner"
            }
        } catch (t: Throwable) {
            QLog.w("HideQZMTitleBarEntrance", "banner path skipped", t)
        }

        if (installed.isEmpty()) {
            QLog.w("HideQZMTitleBarEntrance", "no path installed for this QQ version")
        }
        return true
    }
}
