// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import sumicya.qself.BuildConfig
import sumicya.qself.Catalog

/**
 * Self-check without files: the settings app asks QQ (ordered broadcast), QQ answers with a short
 * plain-text report — versions, every switch's state or failure, rule hit counts, glass status and
 * the last log lines — which the settings app shows with a copy button.
 */
object Report {
    private val lines = ArrayDeque<String>()
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null

    fun remember(priority: Int, msg: String) = synchronized(lines) {
        val t = SystemClock.elapsedRealtime() / 1000
        val p = when (priority) { Log.WARN -> "W"; Log.ERROR -> "E"; else -> "I" }
        lines.addLast("${t / 60 % 60}:${"%02d".format(t % 60)} $p $msg".take(200))
        while (lines.size > 40) lines.removeFirst()
    }

    fun listen(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                resultData = runCatching { build(c) }.getOrElse { "报告生成失败: $it" }
            }
        }
        runCatching { ctx.registerReceiver(r, IntentFilter(Catalog.ACTION_REPORT), Context.RECEIVER_EXPORTED) }
            .onSuccess { receiver = r; context = ctx }
            .onFailure { Runtime.log(Log.WARN, "report receiver: $it") }
    }

    fun unlisten() {
        receiver?.let { r -> runCatching { context?.unregisterReceiver(r) } }
        receiver = null
        context = null
    }

    private fun build(c: Context): String = buildString {
        val qq = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        appendLine("Qself ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · QQ ${qq?.versionName} (${qq?.longVersionCode})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("前台: ${Runtime.front?.javaClass?.name?.substringAfterLast('.') ?: "-"}")
        appendLine("玻璃折射: ${GlassDrawable.shaderError?.let { "不可用 $it" } ?: "可用"}")
        appendLine("功能:")
        for (f in Runtime.features) {
            val mark = when { f.active -> "✓"; f.error != null -> "✗"; else -> "·" }
            append(mark).append(' ').append(f.id)
            when {
                f.active -> {
                    if (f.hookCount > 0) append(" 钩子 ").append(f.hookCount)
                    f.status()?.let { append(" · ").append(it) }
                }
                f.error != null -> append(" 失败: ").append(f.error)
                else -> append(" 关")
            }
            appendLine()
        }
        appendLine("日志:")
        synchronized(lines) { lines.forEach { appendLine(it) } }
    }
}
