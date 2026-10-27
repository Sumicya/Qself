// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.Notification

/**
 * 通知现代化：QQ 的消息通知还是老式的一行标题 + 一行截断正文，长消息在锁屏上只剩半句话。
 * 这里不改 QQ 的推送逻辑，只在其 [android.app.NotificationManager] 落盘前把通知换成本代的
 * 大文本样式（[Notification.BigTextStyle]）：标题保留，正文展开成完整内容，时间显示完整。
 *
 * 只动「系统标准样式」的通知：带自定义 RemoteViews（`contentView != null`）的一律放行，
 * 那种通知的排版在 QQ 自己的布局里，重建只会把它弄坏。改动通过 `chain.proceed(新参数)` 回填，
 * 不重新调 notify，所以没有递归风险。
 */
private const val MODERN_TITLE_MAX = 120
private const val MODERN_BODY_MAX = 400

fun modernNotifications() {
    val manager = android.app.NotificationManager::class.java
    val targets = buildList {
        runCatching {
            add(
                manager.getDeclaredMethod(
                    "notify",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Notification::class.java,
                ),
            )
        }
        runCatching { add(manager.getDeclaredMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)) }
    }
    check(targets.isNotEmpty()) { "NotificationManager 没有可挂的 notify" }
    for (m in targets) {
        hook(m) { chain ->
            val n = chain.args.lastOrNull() as? Notification
            val ctx = chain.thisObject as? android.content.Context
            val better = if (n == null || ctx == null) null else modernize(ctx, n)
            if (better == null) chain.proceed()
            else chain.proceed(chain.args.dropLast(1).toTypedArray() + better)
        }
    }
}

/** 换成本代大文本样式；不值得改或改失败就返回 null，让原通知照原样发出去。 */
private fun modernize(context: android.content.Context, n: Notification): Notification? = runCatching {
    if (n.contentView != null) return null            // 自定义布局：不是我们该动的
    val extras = n.extras ?: return null
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val body = big.ifEmpty { text }
    if (body.isEmpty() && title.isEmpty()) return null
    // 已经是展开样式（正文完整且没被截断）就不动，避免每次重发都重建一遍。
    if (big.isNotEmpty() && big == text) return null
    val builder = Notification.Builder.recoverBuilder(context, n)
    val style = Notification.BigTextStyle().bigText(body.take(MODERN_BODY_MAX))
    runCatching { style.setBigContentTitle(title.take(MODERN_TITLE_MAX)) }
    builder.setStyle(style)
    // 时间戳原来被 QQ 关掉了，锁屏上看不出是哪条；打开并按通知自带的 when 显示。
    builder.setShowWhen(true)
    if (n.`when` > 0L) builder.setWhen(n.`when`)
    builder.build()
}.onFailure { log("通知现代化失败，按原样发出", it) }.getOrNull()
