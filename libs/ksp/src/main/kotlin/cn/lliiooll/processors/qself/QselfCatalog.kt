/* SPDX-License-Identifier: GPL-3.0-or-later */
package cn.lliiooll.processors.qself

/**
 * Qself capability catalog, the single in-code source for:
 *  - the registration whitelist consumed by both entry processors;
 *  - the settings groups generated for the in-app catalog.
 * Columns: group id, home section, group title, section id, section title, provider class name.
 * _core rows register infrastructure components and never show in the settings catalog.
 */
object QselfCatalog {
    val rows: List<String> = listOf(
        "qself-purify	appearance	统一净化	ads	广告	me.hd.hook.DisableGrowHalfLayer",
        "qself-purify	appearance	统一净化	ads	广告	me.hd.hook.simplify.main.ui.titile.DisableThirdContainer",
        "qself-purify	appearance	统一净化	ads	广告	me.hd.hook.simplify.main.ui.misc.RemoveCommentAd",
        "qself-purify	appearance	统一净化	ads	广告	io.github.relimus.hook.HideQZoneAD",
        "qself-purify	appearance	统一净化	ads	广告	cc.ioctl.hook.mini.HideMiniAppLoadingAd",
        "qself-purify	appearance	统一净化	ads	广告	cc.ioctl.hook.ui.title.RemoveQbossAD",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.ui.main.HideMiniAppPullEntry",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.ui.title.RemoveDailySign",
        "qself-purify	appearance	统一净化	entries	界面入口	me.hd.hook.simplify.main.ui.titile.DisableChatsCardContainer",
        "qself-purify	appearance	统一净化	entries	界面入口	me.hd.hook.simplify.chat.other.HideShortcutBar",
        "qself-purify	appearance	统一净化	entries	界面入口	me.hd.hook.simplify.chat.goup.title.HideTroopSquare",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.troop.RemoveGroupApp",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.troop.RemovePlayTogether",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.troop.HideListenTogetherPanel",
        "qself-purify	appearance	统一净化	entries	界面入口	cc.ioctl.hook.troop.DisableFlingToTroopGuild",
        "qself-purify	appearance	统一净化	entries	界面入口	me.singleneuron.hook.decorator.DisableQzoneSlideCamera",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	cc.ioctl.hook.chat.DefaultFont",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	cc.ioctl.hook.chat.DefaultBubbleHook",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	cc.ioctl.hook.ui.profile.DisableAvatarDecoration",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	cc.ioctl.hook.ui.profile.RemoveDiyCard",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	cc.ioctl.hook.troop.DisableColorNickName",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	me.hd.hook.simplify.chat.goup.other.HideEditTroopNick",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	me.hd.hook.simplify.chat.goup.other.RemoveRedPackSkin",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	me.hd.hook.simplify.chat.goup.other.HideJoinTroopBriefContent",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	xyz.nextalone.hook.HideChatVipImage",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	xyz.nextalone.hook.HideTroopLevel",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	xyz.nextalone.hook.HideRedPoints",
        "qself-purify	appearance	统一净化	decoration	装扮与提示	xyz.nextalone.hook.HideProfileBubble",
        "qself-purify	appearance	统一净化	effects	动画与互动	cc.hicore.hook.DisablePopOutEmoticon",
        "qself-purify	appearance	统一净化	effects	动画与互动	cc.ioctl.hook.chat.DisableDropSticker",
        "qself-purify	appearance	统一净化	effects	动画与互动	cc.ioctl.hook.chat.DisablePokeEffect",
        "qself-purify	appearance	统一净化	effects	动画与互动	cc.ioctl.hook.chat.DisableShakeWindow",
        "qself-purify	appearance	统一净化	effects	动画与互动	cc.ioctl.hook.chat.HideGiftAnim",
        "qself-purify	appearance	统一净化	effects	动画与互动	sumicya.qself.feature.chat.DisableEnterEffect",
        "qself-purify	appearance	统一净化	effects	动画与互动	sumicya.qself.feature.chat.DisableLightInteraction",
        "qself-purify	appearance	统一净化	effects	动画与互动	sumicya.qself.feature.ui.RemoveSuperQQShow",
        "qself-messages	chat	消息工具	counts	消息数量	cc.ioctl.hook.msg.ShowMsgCount",
        "qself-messages	chat	消息工具	actions	统一消息菜单	cc.hicore.hook.RepeaterPlus",
        "qself-messages	chat	消息工具	actions	统一消息菜单	cc.ioctl.hook.msg.CopyCardMsg",
        "qself-messages	chat	消息工具	actions	统一消息菜单	io.github.duzhaokun123.hook.MessageCopyHook",
        "qself-messages	chat	消息工具	actions	统一消息菜单	cc.ioctl.hook.msg.PttForwardHook",
        "qself-messages	chat	消息工具	actions	统一消息菜单	cc.ioctl.hook.msg.PicMd5Hook",
        "qself-messages	chat	消息工具	actions	统一消息菜单	me.ketal.hook.PicCopyToClipboard",
        "qself-messages	chat	消息工具	actions	统一消息菜单	me.hd.hook.menu.CopyMarkdown",
        "qself-messages	chat	消息工具	input	输入与回复	cc.hicore.hook.ReplyMsgWithImg",
        "qself-messages	chat	消息工具	input	输入与回复	cc.ioctl.hook.msg.AioChatPieClipPasteHook",
        "qself-messages	chat	消息工具	input	输入与回复	com.xiaoniu.hook.CtrlEnterToSend",
        "qself-messages	chat	消息工具	input	输入与回复	cc.ioctl.hook.ui.chat.ReplyNoAtHook",
        "qself-messages	chat	消息工具	input	输入与回复	me.hd.hook.simplify.ui.chat.msg.RemoveReplyImagePreviewLimit",
        "qself-messages	chat	消息工具	sharing	收藏与分享	me.ketal.hook.SendFavoriteHook",
        "qself-messages	chat	消息工具	sharing	收藏与分享	io.github.nakixii.hook.SendFavoriteVoice",
        "qself-messages	chat	消息工具	sharing	收藏与分享	me.hd.hook.simplify.ui.misc.RemoveFavPreviewLimit",
        "qself-messages	chat	消息工具	sharing	收藏与分享	cc.ioctl.hook.msg.SharePicExtHook",
        "qself-messages	chat	消息工具	sharing	收藏与分享	cc.ioctl.hook.msg.FileShareExtHook",
        "qself-messages	chat	消息工具	sharing	收藏与分享	cc.ioctl.hook.msg.MultiForwardAvatarHook",
        "qself-messages	chat	消息工具	cards	消息显示转换	me.singleneuron.hook.decorator.MiniAppToStruckMsg",
        "qself-messages	chat	消息工具	cards	消息显示转换	me.singleneuron.hook.decorator.CardMsgToText",
        "qself-annotations	chat	消息标注	revoke	撤回与提示	cc.ioctl.hook.msg.RevokeMsgHook",
        "qself-annotations	chat	消息标注	revoke	撤回与提示	sumicya.qself.feature.chat.RevokeWrapHint",
        "qself-annotations	chat	消息标注	tail	统一消息尾注	me.ketal.hook.ChatItemShowQQUin",
        "qself-annotations	chat	消息标注	tail	统一消息尾注	nep.timeline.PromptForNoSeqMessage",
        "qself-annotations	chat	消息标注	tail	统一消息尾注	me.ketal.hook.ShowMsgAt",
        "qself-contacts	people	好友与群工具	friends	好友	cc.ioctl.hook.friend.CheckCommonGroupMenu",
        "qself-contacts	people	好友与群工具	friends	好友	cc.ioctl.hook.friend.FriendDeletionNotification",
        "qself-contacts	people	好友与群工具	friends	好友	cc.ioctl.hook.friend.ShowDeletedFriendListEntry",
        "qself-contacts	people	好友与群工具	friends	好友	cc.ioctl.hook.friend.OpenFriendChatHistory",
        "qself-contacts	people	好友与群工具	friends	好友	cc.ioctl.fragment.FriendListExportFragment${'$'}ItemEntry",
        "qself-contacts	people	好友与群工具	groups	群聊	sumicya.qself.feature.chat.GroupAdminMenu",
        "qself-contacts	people	好友与群工具	groups	群聊	sumicya.qself.feature.dev.GrayTipCapture",
        "qself-contacts	people	好友与群工具	groups	群聊	sumicya.qself.feature.chat.GagInfoDisclosure",
        "qself-contacts	people	好友与群工具	groups	群聊	cc.hicore.hook.ShowAccurateGaggedTime",
        "qself-contacts	people	好友与群工具	groups	群聊	cc.hicore.hook.TroopMemberLeaveGreyTip",
        "qself-notifications	tools	通知管理	style	样式与渠道	moe.zapic.hook.MessagingStyleNotification",
        "qself-notifications	tools	通知管理	style	样式与渠道	io.github.duzhaokun123.hook.NotificationChannelManager",
        "qself-notifications	tools	通知管理	style	样式与渠道	me.singleneuron.hook.GroupSpecialCare",
        "qself-notifications	tools	通知管理	style	样式与渠道	me.singleneuron.hook.SpecialCareNewChannel",
        "qself-notifications	tools	通知管理	filter	提醒过滤	cc.ioctl.hook.ui.chat.MutePokePacket",
        "qself-appearance	appearance	外观调整	local	局部效果	sumicya.qself.feature.ui.LiquidGlassBottomBar",
        "qself-appearance	appearance	外观调整	avatar	头像	sumicya.qself.feature.ui.AvatarRounding",
        "qself-appearance	appearance	外观调整	avatar	头像	cc.hicore.hook.UploadTransparentAvatar",
        "qself-appearance	appearance	外观调整	display	显示	cc.ioctl.hook.ui.misc.OptXListViewScrollBar",
        "qself-files	tools	媒体与系统工具	media	图片	me.teble.hook.CancelPicCompress",
        "qself-files	tools	媒体与系统工具	media	图片	xyz.nextalone.hook.AutoReceiveOriginalPhoto",
        "qself-files	tools	媒体与系统工具	media	图片	xyz.nextalone.hook.AutoSendOriginalPhoto",
        "qself-files	tools	媒体与系统工具	system	系统打开方式	me.singleneuron.hook.decorator.ForceSystemAlbum",
        "qself-files	tools	媒体与系统工具	system	系统打开方式	me.singleneuron.hook.decorator.ForceSystemFile",
        "qself-files	tools	媒体与系统工具	system	系统打开方式	me.singleneuron.hook.decorator.FxxkQQBrowser",
        "qself-files	tools	媒体与系统工具	storage	缓存	io.github.duzhaokun123.util.CacheManager",
        "qself-safety	tools	维护与诊断	protection	既有保护选项	cc.ioctl.hook.misc.DisableHotPatch",
        "qself-safety	tools	维护与诊断	protection	既有保护选项	cc.ioctl.hook.misc.DisableQQCrashReportManager",
        "qself-safety	tools	维护与诊断	protection	既有保护选项	sumicya.qself.feature.device.RiskReportInterceptor",
        "qself-safety	tools	维护与诊断	diagnostics	功能开关与错误记录	sumicya.qself.diagnostics.FeatureDiagnosticsItem",
        "cfg-theme		主题与显示	theme	界面	io.github.qauxv.fragment.ThemeColorStyleDialog",
        "cfg-theme		主题与显示	theme	界面	io.github.qauxv.fragment.ThemeModeStyleDialog",
        "_core					cc.hicore.message.chat.SessionHooker",
        "_core					cc.ioctl.hook.DeletionObserver",
        "_core					cc.ioctl.hook.SettingEntryHook",
        "_core					cc.ioctl.hook.misc.CleanUpMitigation",
        "_core					cc.ioctl.hook.misc.ShadowInitDependencyStub",
        "_core					com.xiaoniu.dispatcher.MenuBuilderHook",
        "_core					io.github.qauxv.router.dispacher.InputButtonHookDispatcher",
        "_core					io.github.qauxv.router.dispacher.StartActivityHook",
        "_core					io.github.qauxv.router.dispacher.ItemBuilderFactoryHook",
        "_core					me.ketal.dispacher.BaseBubbleBuilderHook",
        "qself-appearance	appearance	外观调整	local	局部效果	xyz.nextalone.hook.SimplifyBottomTab",
    )

    init {
        require(rows.all { it.split('\t').size == 6 }) { "Malformed qself catalog row" }
        val providers = rows.map { it.substringAfterLast('\t') }
        require(providers.distinct().size == providers.size) { "Duplicate qself capability" }
    }

    /** Provider FQNs allowed to register; nested-class separators become dots. */
    val allowedProviders: Set<String>
        get() = rows.map { it.substringAfterLast('\t').replace('$', '.') }.toSet()

    /** Rows baked into the in-app settings catalog (infrastructure excluded). */
    val catalogRows: List<String>
        get() = rows.filterNot { it.startsWith("_core\t") }
}
