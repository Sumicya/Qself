// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.view.View
import android.view.ViewGroup

/**
 * 按看得见的东西精简 QQ 自己的界面。两条规则，各自把「见过什么」写进报告 ——
 * 认错了不是靠猜，是下一版拿报告里的真文字改。
 */

/** 聊天标题栏：不要一起听、QQ 秀这类娱乐按钮。 */
object TrimTitle : Rule("tg_title_bar") {
    private val words = listOf("一起听", "一起看", "一起玩", "一起派对", "一起K歌", "QQ秀", "厘米秀", "群游戏", "小世界")
    private val seen = LinkedHashSet<String>()

    override fun match(v: View): Boolean {
        val d = desc(v) ?: return false
        if (d.length > 10) return false
        if (words.none { d.contains(it, ignoreCase = true) }) return false
        if (v.height !in 1..dp(v, 72) || Screen.windowY(v) >= dp(v, 160)) return false
        if (seen.size < 8) seen += d
        return true
    }

    override fun status() = "命中 $hits" + if (seen.isEmpty()) "" else " · 见过 ${seen.joinToString("/")}"
}

/**
 * 侧栏：去掉商城、打卡、天气和等级牌，留相册、收藏、文件、设置。
 * 一行由它的主标签决定，所以只是碰巧含某个词的整行（设置 | 日间 | 天气）不会被整行拿掉。
 */
object TrimDrawer : Rule("tg_drawer", lists = true) {
    private val drop = setOf(
        "开通会员", "会员中心", "超级会员", "QQ会员", "QQ钱包", "钱包", "个性装扮", "装扮",
        "我的小世界", "小世界", "免流量", "QQ小游戏", "小游戏", "厘米秀", "超级QQ秀", "QQ秀",
        "QQ空间", "游戏中心", "腾讯文档", "打卡", "当地天气", "天气",
    )
    private val dropDesc = listOf("等级", "QQ会员", "天气")
    private val hosts = listOf("Drawer", "SettingMe", "QQSetting")
    private val seen = LinkedHashSet<String>()

    private fun button(v: View): View? {
        if (v.isClickable) return v
        if (v !is ViewGroup) return null
        val rest = (0 until v.childCount).map(v::getChildAt).filter { !it.javaClass.name.contains("Blur") }
        return rest.singleOrNull()?.takeIf { it.isClickable }
    }

    override fun match(v: View): Boolean {
        if (v.height !in 1..dp(v, 96)) return false
        val b = button(v) ?: return false
        if (Screen.up(v).none { a -> hosts.any { a.javaClass.name.contains(it) } }) return false
        val label = Screen.labels(b, 3).firstOrNull { t -> t.any(Character::isLetter) }
        if (label != null && seen.size < 12) seen += label
        val d = desc(b)
        return (d != null && d.length <= 12 && dropDesc.any { d.startsWith(it) }) || label in drop
    }

    override fun status() = "命中 $hits" + if (seen.isEmpty()) "" else " · 见过 ${seen.joinToString("/")}"
}
