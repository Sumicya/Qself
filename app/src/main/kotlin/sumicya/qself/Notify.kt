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
internal const val MODERN_TITLE_MAX = 120
internal const val MODERN_BODY_MAX = 400

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
            count(better != null, n)
            if (better == null) chain.proceed()
            else chain.proceed(chain.args.dropLast(1).toTypedArray() + better)
        }
    }
}

/** 换成本代大文本样式；不值得改或改失败就返回 null，让原通知照原样发出去。 */
private fun modernize(context: android.content.Context, n: Notification): Notification? = runCatching {
    if (n.contentView != null) return null            // 自定义布局：不是我们该动的
    val extras = n.extras ?: return null
    val text = notificationText(
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_TEXT),
    ) ?: return null
    val builder = Notification.Builder.recoverBuilder(context, n)
    val style = Notification.BigTextStyle().bigText(text.body)
    if (text.title.isNotEmpty()) runCatching { style.setBigContentTitle(text.title) }
    builder.setStyle(style)
    // 时间戳原来被 QQ 关掉了，锁屏上看不出是哪条；打开并按通知自带的 when 显示。
    builder.setShowWhen(true)
    if (n.`when` > 0L) builder.setWhen(n.`when`)
    builder.build()
}.onFailure { log("通知现代化失败，按原样发出", it) }.getOrNull()

/**
 * 通知计账：真机上「这条功能到底有没有生效」只能靠数字回答。
 * 每 100 条写一行到 Qself 事件流，下次导出日志直接能核；不逐条记，免得刷屏。
 */
private var seen = 0
private var changed = 0
private var custom = 0
private var noText = 0

private fun count(ok: Boolean, n: Notification?) {
    seen++
    if (ok) changed++
    else if (n?.contentView != null) custom++
    else noText++
    if (seen % 100 == 0) {
        recordQselfEvent("通知现代化：共 $seen 条，换样式 $changed，自定义布局跳过 $custom，无可展开 $noText")
    }
}

/** 一条通知换成什么文字；`null` 表示按原样发。 */
internal data class NotifyText(val title: String, val body: String)

/**
 * 纯决策：该不该改、改成什么。Android 侧的 builder 不在里面，便于直接跑单测。
 *
 * - 正文取「大文本，没有就取正文」；两者都空说明没有可展开的内容，返回 `null`。
 * - 大文本与正文一模一样，说明这条已经是展开态（QQ 自己就用 BigTextStyle），重建只是白跑一遍 builder，返回 `null`。
 * - 标题与正文各自截断到上限：通知栏本身也是截断显示，超长内容只会拖慢 `recoverBuilder`。
 */
internal fun notificationText(title: CharSequence?, bigText: CharSequence?, text: CharSequence?): NotifyText? {
    val t = title?.toString()?.trim().orEmpty()
    val big = bigText?.toString()?.trim().orEmpty()
    val small = text?.toString()?.trim().orEmpty()
    val body = big.ifEmpty { small }
    if (body.isEmpty()) return null
    if (big.isNotEmpty() && big == small) return null
    return NotifyText(t.take(MODERN_TITLE_MAX), body.take(MODERN_BODY_MAX))
}
