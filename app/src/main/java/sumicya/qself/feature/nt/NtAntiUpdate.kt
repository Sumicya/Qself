/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.nt

import android.os.Message
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.HostGeneration

/**
 * Update switch for NT QQ.
 *
 * QQ downloads update packages **on its own** — that is not a user action, it
 * is the upgrade SDK doing a version check, receiving a strategy and pulling
 * the APK (the download callbacks in `upgrade.download.d`, including
 * `installSucceed`, are in the dump for exactly that reason). This feature
 * exists so that never happens; upgrading is then a deliberate act: pick a
 * build and install it yourself.
 *
 * Every signature is from the device (QQ 9.2.10) — see docs/NT-ADAPTATION.md.
 * The layers are ordered from "before anything happens" to "after it already
 * happened", because a QQ build may only exercise some of them:
 *
 *   ① 判定 decision — the boolean predicates that answer "is there an upgrade
 *      for me": force false, so no upgrade is ever concluded.
 *   ② 请求 request  — the SDK's request dispatcher (`core.b.b(request.a)`):
 *      no request leaves the device, so no strategy can come back.
 *   ③ 提示 prompt   — shiply bridges and QQ's `upgrade.k` / `upgrade.j` entry
 *      points that turn a strategy into UI or persisted state.
 *   ④ 下载 download — the download/callback surface, so a package that is
 *      already on disk still does not reach "installed".
 *   ⑤ 横幅 banner   — the two recent-list banner processors.
 *
 * Object-returning getters and constructors are deliberately **not** hooked:
 * a null where the caller dereferences is a crash, and blocking the state that
 * drives them (①-③) is enough. Counts per layer are logged, so a device log
 * shows exactly which layers a future QQ build still needs.
 */
@QselfFeature(
    id = "misc.anti_update_nt",
    name = "屏蔽更新（NT）",
    summary = "阻断 QQ 9.x 的升级检查、下载与提示（升级包自行选装）",
    category = "misc",
    experimental = true,
)
object NtAntiUpdate : SwitchFeature() {

    private const val TAG = "NtAntiUpdate"

    override val id: String = "misc.anti_update_nt"
    override val name: String = "屏蔽更新（NT）"
    override val summary: String = "阻断 QQ 9.x 的升级检查、下载与提示（升级包自行选装）"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = true
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false
    override val hostGeneration: HostGeneration = HostGeneration.NT

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host

        val strategy = host.resolve("com.tencent.upgrade.bean.UpgradeStrategy")
        val wrapper = host.resolve("com.tencent.mobileqq.upgrade.UpgradeDetailWrapper")
        val request = host.resolve("com.tencent.upgrade.request.a")
        val downloadInfo = host.resolve("com.tencent.open.downloadnew.DownloadInfo")
        val upgradeInfo = host.resolve("protocol.KQQConfig\$UpgradeInfo")
        val banner = host.resolve("com.tencent.mobileqq.banner.a")
        if (strategy == null && request == null && wrapper == null) {
            QLog.w(TAG, "upgrade SDK not present — wrong host generation?")
            return false
        }

        val bool = java.lang.Boolean.TYPE
        val long = java.lang.Long.TYPE
        val string = String::class.java
        val list = java.util.List::class.java
        val message = Message::class.java

        var decisions = 0
        var requests = 0
        var prompts = 0
        var downloads = 0
        var banners = 0

        // ① 判定：把"有新版本"这个结论掐死。这几个都是 boolean 谓词，false 即"无需升级"。
        if (strategy != null) {
            if (NtHooks.replaceIn(this, host, "com.tencent.upgrade.checker.a", "b", false, strategy)) {
                decisions++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.upgrade.checker.b", "b", false, strategy)) {
                decisions++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.upgrade.checker.b", "a", false, strategy, strategy)) {
                decisions++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.upgrade.core.c", "b", false, strategy)) {
                decisions++
            }
        }

        // ② 请求：连"查一下有没有新版本"都不发。
        if (request != null) {
            if (NtHooks.replaceIn(this, host, "com.tencent.upgrade.core.b", "b", null, request)) {
                requests++
            }
        }

        // ③ 提示：策略 → UI/持久化的桥。
        if (strategy != null) {
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.shiply.a", "c", null, strategy, bool)) {
                prompts++
            }
        }
        if (wrapper != null && strategy != null) {
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.shiply.b", "a", null, wrapper, strategy)) {
                prompts++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.k", "a", null, wrapper)) {
                prompts++
            }
        }
        if (upgradeInfo != null) {
            // 记录"有新版本"的那一步 —— 跳过它，升级状态就不会被写下来。
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.j", "o", null, upgradeInfo)) {
                prompts++
            }
        }

        // ④ 下载/安装回调：磁盘上就算已经有包，也走不到"装好了"。
        if (downloadInfo != null) {
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.download.d", "onDownloadFinish", null, downloadInfo)) {
                downloads++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.download.d", "onDownloadUpdate", null, list)) {
                downloads++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.download.d", "installSucceed", null, string, string)) {
                downloads++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.download.c", "b", null, downloadInfo)) {
                downloads++
            }
            if (NtHooks.replaceIn(this, host, "com.tencent.mobileqq.upgrade.download.c", "onResult", null, list)) {
                downloads++
            }
        }

        // ⑤ 横幅：只钩消息与更新（initBanner 返回 View，返回 null 会让框架崩，不碰）。
        for (name in arrayOf(
            "com.tencent.mobileqq.activity.recent.bannerprocessor.UpgradeBannerProcessor",
            "com.tencent.mobileqq.activity.recent.bannerprocessor.InstallUpgradeBannerProcessor",
        )) {
            if (banner != null &&
                NtHooks.replaceIn(this, host, name, "updateBanner", null, banner, message)
            ) {
                banners++
            }
            if (NtHooks.replaceIn(this, host, name, "onMessage", null, message, long, bool)) {
                banners++
            }
        }

        val total = decisions + requests + prompts + downloads + banners
        if (total == 0) {
            QLog.w(TAG, "no upgrade hook could be installed")
            return false
        }
        QLog.i(
            TAG,
            "installed $total hooks (decision=$decisions request=$requests " +
                "prompt=$prompts download=$downloads banner=$banners)",
        )
        return true
    }
}
