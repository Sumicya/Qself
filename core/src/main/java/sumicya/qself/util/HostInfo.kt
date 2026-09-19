/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import sumicya.qself.log.QLog

/** Snapshot of the host (QQ) app as seen from the process we run in. */
class HostInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
) {
    val isQq: Boolean
        get() = packageName == HostInfoProvider.PACKAGE_NAME_QQ

    val isTim: Boolean
        get() = packageName == HostInfoProvider.PACKAGE_NAME_TIM

    fun isAtLeast(version: Long): Boolean = versionCode >= version
}

object HostInfoProvider {

    const val PACKAGE_NAME_QQ = "com.tencent.mobileqq"
    const val PACKAGE_NAME_QQ_INTERNATIONAL = "com.tencent.mobileqqi"
    const val PACKAGE_NAME_QQ_LITE = "com.tencent.qqlite"
    const val PACKAGE_NAME_QQ_HD = "com.tencent.minihd.qq"
    const val PACKAGE_NAME_TIM = "com.tencent.tim"

    val HOST_PACKAGES = listOf(
        PACKAGE_NAME_QQ,
        PACKAGE_NAME_QQ_INTERNATIONAL,
        PACKAGE_NAME_QQ_LITE,
        PACKAGE_NAME_QQ_HD,
        PACKAGE_NAME_TIM,
    )

    fun load(context: Context): HostInfo = load(context, context.packageName)

    /**
     * Load info for [packageName], which may be a different app (the
     * module's UI process queries the host this way).
     */
    fun load(context: Context, packageName: String): HostInfo {
        return try {
            val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
            HostInfo(
                packageName = packageName,
                versionName = info.versionName ?: "",
                versionCode = longVersionCode(info),
            )
        } catch (t: Throwable) {
            // A missing package (or one hidden by Android 11+ package
            // visibility) is a normal state, not a crash: warn once, without a
            // stack trace, and let the callers show "?".
            QLog.w("HostInfo", "host info unavailable for $packageName (${t.javaClass.simpleName})")
            HostInfo(packageName, "", 0)
        }
    }

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
}
