// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/**
 * 开关表。设置页和 QQ 里的代码都读这一份。
 *
 * 每个 id 必须在 hook/Switch.kt 那边有一个 Switch 对上，对不上会在 QQ 的自检报告里被点名。
 */
object Catalog {
    const val PREFS = "qself"
    const val QQ = "com.tencent.mobileqq"
    /** 设置页 → QQ：给我一份自检报告（有序广播，结果放在 resultData）。 */
    const val ACTION_REPORT = "sumicya.qself.REPORT"

    enum class Group(val title: String) {
        LOOK("外观"),
        CHAT("聊天"),
        FREE("净化"),
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
        Item("glass_bar", "悬浮玻璃底栏", "底栏浮起成胶囊，背面是实时折射的玻璃；选中项一个滑动胶囊", Group.LOOK),
        Item("hide_tab_guild", "底栏去掉「频道」", "", Group.LOOK),
        Item("hide_tab_feed", "底栏去掉「动态」", "只剩消息和联系人", Group.LOOK),
        Item("glass_title", "首页顶栏玻璃", "列表从顶栏下面滑过", Group.LOOK),
        Item("glass_chat", "聊天玻璃", "标题栏和输入栏变玻璃", Group.LOOK),
        Item("tg_input_bar", "输入栏 TG 化", "表情、输入框、相册、「+」、语音⇄发送合成一行", Group.LOOK),
        Item("tg_title_bar", "聊天标题栏精简", "去掉一起听歌、一起看、QQ 秀这类娱乐按钮", Group.LOOK),
        Item("tg_drawer", "侧栏精简", "去掉会员、钱包、装扮、打卡、天气和等级", Group.LOOK),
        Item("plain_bubble", "统一气泡", "所有人的消息都用默认气泡", Group.LOOK),
        Item("plain_font", "统一字体", "去掉会员字体和魔法字", Group.LOOK),
        Item("plain_nick", "昵称只留名字", "群聊里去掉群等级、头衔、荣誉和会员图标", Group.LOOK),
        Item("no_pendant", "去头像挂件", "只显示干净的头像", Group.LOOK),

        Item("anti_recall", "防撤回", "丢掉撤回推送，消息留在本地", Group.CHAT),
        Item("multi_forward", "转发多选", "转发页始终显示好友/群/多选入口", Group.CHAT),
        Item("tg_plus_panel", "「+」面板精简", "留照片、拍摄、文件、位置、红包，去掉一起派对、礼物、直播间这类", Group.CHAT),
        Item("no_light_interaction", "屏蔽轻互动", "早安、晚安、戳一戳之类的互动", Group.CHAT),
        Item("no_drop_sticker", "屏蔽表情雨", "关键词触发的全屏掉落表情", Group.CHAT),

        Item("system_webview", "系统 WebView", "禁用腾讯 X5 内核", Group.FREE),
        Item("no_telemetry", "屏蔽统计上报", "灯塔 / StatisticCollector 行为上报", Group.FREE),
        Item("no_crash_report", "屏蔽崩溃上报", "EUP 崩溃收集不再初始化", Group.FREE),

        Item("debug_dump", "界面结构导出", "每 3 秒把当前界面结构写到 QQ 的 files/qself/，用来适配新版 QQ", Group.DEV, default = false),
    )

    val byId = items.associateBy { it.id }
}
