// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/** Everything Qself can do. One list, shared by the settings app and the QQ-side runtime. */
object Catalog {
    const val PREFS = "qself"
    const val QQ = "com.tencent.mobileqq"

    enum class Group(val title: String) {
        LOOK("外观 · TG 化"),
        CHAT("聊天"),
        FREE("净化 · 自由化"),
        DEV("调试"),
    }

    class Item(
        val id: String,
        val title: String,
        val summary: String,
        val group: Group,
        val default: Boolean = true,
    )

    val items = listOf(
        Item("glass_bar", "悬浮玻璃底栏", "QQ 原底栏变成悬浮胶囊，背后是实时模糊的聊天内容", Group.LOOK),
        Item("hide_tab_guild", "底栏去掉「频道」", "只留聊天该有的东西", Group.LOOK),
        Item("hide_tab_feed", "底栏去掉「动态」", "像 Telegram 一样只剩消息和联系人", Group.LOOK),
        Item("tg_input_bar", "输入栏 TG 化", "输入框下只留语音、图片、表情和「+」，红包/相机/GIF/戳一戳收进「+」面板", Group.LOOK),
        Item("tg_title_bar", "聊天标题栏精简", "去掉一起听歌、一起看、QQ 秀这类娱乐按钮", Group.LOOK),
        Item("tg_drawer", "侧栏精简", "侧栏去掉会员、钱包、装扮、小世界、小游戏等商城入口", Group.LOOK),
        Item("plain_bubble", "统一气泡", "所有人的消息都用默认气泡", Group.LOOK),
        Item("plain_font", "统一字体", "去掉会员字体与魔法字", Group.LOOK),
        Item("no_pendant", "去头像挂件", "聊天里只显示干净的头像", Group.LOOK),

        Item("anti_recall", "防撤回", "丢弃好友与群的撤回推送，消息留在本地", Group.CHAT),
        Item("multi_forward", "转发多选", "转发页始终显示好友/群/多选入口", Group.CHAT),
        Item("no_light_interaction", "屏蔽轻互动", "早安、晚安、戳一戳之类的互动表情", Group.CHAT),
        Item("no_drop_sticker", "屏蔽表情雨", "关键词触发的全屏掉落表情", Group.CHAT),

        Item("system_webview", "系统 WebView", "禁用腾讯 X5 内核，网页走系统 WebView", Group.FREE),
        Item("no_telemetry", "屏蔽统计上报", "灯塔 / StatisticCollector 行为上报", Group.FREE),
        Item("no_crash_report", "屏蔽崩溃上报", "Bugly / EUP 崩溃收集不再初始化", Group.FREE),

        Item("debug_dump", "界面结构导出", "每 3 秒把当前界面结构写到 Android/data/com.tencent.mobileqq/files/qself/latest.txt，用来适配新版 QQ", Group.DEV, default = false),
    )

    val byId = items.associateBy { it.id }
}
