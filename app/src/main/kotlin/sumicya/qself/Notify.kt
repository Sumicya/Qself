// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知现代化：QQ 的消息通知还是老式的一行标题 + 一行截断正文，长消息在锁屏上只剩半句话。
 *
 * 这里不改 QQ 的推送逻辑，只在 [NotificationManager] 落盘前把通知换成本代的大文本样式
 * （[Notification.BigTextStyle]）：标题保留，正文展开成完整内容，时间显示完整。
 *
 * 只动「系统标准样式」的通知：带自定义 RemoteViews（`contentView != null`）的一律放行，
 * 那种通知的排版在 QQ 自己的布局里，重建只会把它弄坏。改动通过 `chain.proceed(新参数)`
 * 回填，不重新调 notify，所以没有递归风险。
 */

/** 标题与正文各自的上限。通知栏本身也会截断，超长只会拖慢 `recoverBuilder`。 */
internal const val MODERN_TITLE_MAX = 120
internal const val MODERN_BODY_MAX = 400

fun modernNotifications() {
    val manager = NotificationManager::class.java

    /*
     * 两个 notify 重载都要挂。注意 chain.args 是「按声明顺序」的参数表，
     * 这两个重载里 Notification 都恰好是最后一个——下面的 replaceLast 依赖这一点，
     * 所以这里把「最后一个参数是 Notification」写成断言，而不是靠巧合。
     */
    val targets = buildList {
        runCatching {
            add(manager.getDeclaredMethod("notify", String::class.java, Int::class.javaPrimitiveType, Notification::class.java))
        }
        runCatching {
            add(manager.getDeclaredMethod("notify", Int::class.javaPrimitiveType, Notification::class.java))
        }
    }
    check(targets.isNotEmpty()) { "NotificationManager 没有可挂的 notify" }
    check(targets.all { it.parameterTypes.last() == Notification::class.java }) {
        "notify 的最后一个参数不再是 Notification，replaceLast 的前提不成立了"
    }

    for (target in targets) {
        hook(target) { chain -> interceptNotify(chain, chain.args.lastOrNull() as? Notification) }
    }
}

/**
 * 一个 notify 调用的处理：能改就改，改不了就原样放行。
 *
 * 抽出来是为了让「改还是不改」这条决策与 hook 的装配分开——
 * 决策本身在 [notificationText] 里，纯函数，可单测。
 */
private fun interceptNotify(chain: io.github.libxposed.api.XposedInterface.Chain, notification: Notification?): Any? {
    val context = chain.thisObject as? Context
    val better = if (notification == null || context == null) null else modernize(context, notification)
    recordOutcome(notification, better)
    if (better == null) return chain.proceed()
    return chain.proceed(replaceLast(chain.args, better))
}

/**
 * 把参数表最后一个换成 [value]，其余原样。chain.args 是 List，proceed 要 Array。
 *
 * 空表直接抛：空表的 `lastIndex` 是 -1，`Array(0)` 会安静地返回空数组，
 * 于是 proceed 拿到一个参数数不对的调用——那种错会在 QQ 内部变成
 * 「钩子前置异常」被兜底吞掉，真实原因（参数表空了）就再也看不到。
 */
internal fun replaceLast(args: List<Any?>, value: Any?): Array<Any?> {
    require(args.isNotEmpty()) { "replaceLast: 参数表是空的，没有最后一格可换" }
    return Array(args.size) { index -> if (index == args.lastIndex) value else args[index] }
}

/**
 * 换成本代大文本样式；不值得改或改失败就返回 null，让原通知照原样发出去。
 *
 * 注意这里刻意**不用** `= runCatching { … }.getOrNull()` 的写法：那样写在 lambda 里
 * `return null` 是非局部返回，会直接跳过 `.onFailure`，失败就静默了。
 * 改成显式 try/catch，每条退出路径都清楚，失败必留日志。
 */
private fun modernize(context: Context, n: Notification): Notification? {
    // 自定义布局：不是我们该动的。
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
        // 时间戳原来被 QQ 关掉了，锁屏上看不出是哪条；打开并按通知自带的 when 显示。
        builder.setShowWhen(true)
        if (n.`when` > 0L) builder.setWhen(n.`when`)
        builder.build()
    } catch (t: Throwable) {
        log("通知现代化失败，按原样发出", t)
        null
    }
}

// ── 计账 ─────────────────────────────────────────────────────────────────────

/**
 * 通知计账：真机上「这条功能到底有没有生效」只能靠数字回答。
 *
 * 用 AtomicInteger 而不是 `var seen = 0`：notify 是 QQ 自己按需调的，
 * 可能落在不同线程上，普通 Int 自增会漏计——而这份计数正是用来判断功能是否生效的，
 * 漏计就等于把一个说真话的仪表弄坏。
 */
private val seen = AtomicInteger()
private val changed = AtomicInteger()
private val custom = AtomicInteger()
private val noText = AtomicInteger()

private fun recordOutcome(n: Notification?, better: Notification?) {
    val total = seen.incrementAndGet()
    if (better != null) changed.incrementAndGet()
    else if (n?.contentView != null) custom.incrementAndGet()
    else noText.incrementAndGet()
    if (total % 100 == 0) {
        recordQselfEvent(
            "通知现代化：共 $total 条，换样式 ${changed.get()}，自定义布局跳过 ${custom.get()}，无可展开 ${noText.get()}",
        )
    }
}

/** 一条通知换成什么文字；`null` 表示按原样发。 */
internal data class NotifyText(val title: String, val body: String)

/**
 * 纯决策：该不该改、改成什么。Android 侧的 builder 不在里面，便于直接跑单测。
 *
 * - 正文取「大文本，没有就取正文」；两者都空说明没有可展开的内容，返回 `null`。
 * - 大文本与正文一模一样，说明这条已经是展开态（QQ 自己就用 BigTextStyle），
 *   重建只是白跑一遍 builder，返回 `null`。
 * - 标题与正文各自截断到上限。
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
