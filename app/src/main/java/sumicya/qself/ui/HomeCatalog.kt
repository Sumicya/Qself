/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

/** Presentation shortcuts only: never enables, disables or renames a feature's config key. */
object HomeCatalog {
    data class Section(val id: String, val title: String, val summary: String, val features: List<String>)

    @JvmField
    val sections = listOf(
        Section("appearance", "外观", "玻璃底栏 · 头像 · 净化", listOf(
            "sumicya.qself.feature.ui.LiquidGlassBottomBar",
            "sumicya.qself.feature.ui.AvatarRounding",
            "cc.hicore.hook.UploadTransparentAvatar",
            "sumicya.qself.feature.consolidation.AdPurifySuite",
        )),
        Section("chat", "聊天", "消息标记 · 回复 · +1", listOf(
            "cc.hicore.hook.RepeaterPlus",
            "cc.hicore.hook.ReplyMsgWithImg",
            "cc.ioctl.hook.msg.RevokeMsgHook",
            "sumicya.qself.feature.chat.RevokeWrapHint",
            "me.ketal.hook.ChatItemShowQQUin",
        )),
        Section("people", "群与好友", "群日志 · 共同群 · 好友记录", listOf(
            "sumicya.qself.feature.chat.GroupAdminMenu",
            "sumicya.qself.feature.dev.GrayTipCapture",
            "cc.ioctl.hook.friend.CheckCommonGroupMenu",
            "cc.ioctl.hook.friend.FriendDeletionNotification",
            "cc.ioctl.hook.friend.ShowDeletedFriendListEntry",
            "cc.ioctl.hook.friend.OpenFriendChatHistory",
        )),
        Section("tools", "工具", "通知 · 收藏 · 使用限制", listOf(
            "moe.zapic.hook.MessagingStyleNotification",
            "io.github.duzhaokun123.hook.NotificationChannelManager",
            "me.ketal.hook.SendFavoriteHook",
            "io.github.nakixii.hook.SendFavoriteVoice",
            "me.hd.hook.simplify.ui.misc.RemoveFavPreviewLimit",
        )),
    )

    const val DIAGNOSTICS = "sumicya.qself.diagnostics.ReportDiagnostics"
    const val SEARCH = "search"
    const val THEME = "theme"
    const val BACKUP = "backup"
    const val CATALOG = "catalog"
    const val ABOUT = "about"
}
