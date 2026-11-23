// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知现代化：把 QQ 的通知换成 BigTextStyle，长消息在锁屏上不再只剩半句。
 * 只动系统标准样式；带自定义 RemoteViews 的一律放行。改写参数回填，不重调 notify。
 */

/** 标题与正文上限。通知栏本身也会截断，超长只会拖慢 recoverBuilder。 */
internal const val MODERN_TITLE_MAX = 120
internal const val MODERN_BODY_MAX = 400

fun modernNotifications() {
    val manager = NotificationManager::class.java

    // 两个重载的 Notification 都恰好在最后一个参数位。
    val targets = buildList {
        runCatching {
            add(manager.getDeclaredMethod("notify", String::class.java, Int::class.javaPrimitiveType, Notification::class.java))
        }
        runCatching {
            add(manager.getDeclaredMethod("notify", Int::class.javaPrimitiveType, Notification::class.java))
        }
    }
    check(targets.isNotEmpty()) { "NotificationManager 没有可挂的 notify" }
    for (target in targets) {
        hook(target) { chain ->
            val n = chain.args.lastOrNull() as? Notification
            val better = if (n == null || chain.thisObject !is Context) null else modernize(chain.thisObject as Context, n)
            recordOutcome(n, better)
            if (better == null) chain.proceed() else chain.proceed(chain.args.dropLast(1).toTypedArray() + better)
        }
    }
}

/**
 * 不值得改或改失败就返回 null，让原通知照原样发。
 * 刻意不用 `= runCatching{}.getOrNull()`：lambda 里的 `return null` 是非局部返回，
 * 会跳过 onFailure，失败就静默了。
 */
private fun modernize(context: Context, n: Notification): Notification? {
    if (n.contentView != null) return null

    return try {
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
        // 时间戳原来被 QQ 关掉了，锁屏上看不出是哪条。
        builder.setShowWhen(true)
        if (n.`when` > 0L) builder.setWhen(n.`when`)
        builder.build()
    } catch (t: Throwable) {
        log("通知现代化失败，按原样发出", t)
        null
    }
}

// ── 计账 ─────────────────────────────────────────────────────────────────────

/** 计账用 AtomicInteger：notify 可能落在不同线程，普通 Int 自增会漏计。 */
private val tally = Array(4) { AtomicInteger() } // 共 / 换样式 / 自定义 / 无可展开
private fun recordOutcome(n: Notification?, better: Notification?) {
    val slot = when { better != null -> 1; n?.contentView != null -> 2; else -> 3 }
    val total = tally[0].incrementAndGet()
    tally[slot].incrementAndGet()
    if (total % 100 == 0) recordQselfEvent(
        "通知现代化：共 $total 条，换样式 ${tally[1].get()}，自定义布局跳过 ${tally[2].get()}，无可展开 ${tally[3].get()}",
    )
}

/** 一条通知换成什么文字；`null` 表示按原样发。 */
internal data class NotifyText(val title: String, val body: String)

/**
 * 纯决策：正文取「大文本，没有就取正文」；两者都空或内容相同（已展开）返回 null。
 * 标题与正文各截断到上限。
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
