# 消息外观与上游功能核对

本轮优先修复真实异常，再加入有明确用途、可独立关闭和恢复的消息功能。未将所有上游功能默认搬入，也未重建 QQ 输入区或消息层级。

## 实现范围

| 项目 | 本轮实现 | 边界 |
| --- | --- | --- |
| 聊天顶栏 | 照 NagramXF `ActionBar.setupGlass` + `ChatAvatarContainer` 三胶囊（`p=6dp`、`s=46dp`、`r=23dp`、中胶囊 `42dp` 圆形会话头像 + 左对齐 `17.5sp`/`13.5sp` 标题副标题），隐藏 `reserve1.blur.f` 通栏模糊 | 头像经 `com.tencent.qqnt.avatar.a` 从 `AIOContact` 异步加载，关闭完整恢复；真机视觉待复验 |
| 原生菜单与面板 | `QQCustomMenuExpandableLayout` 重排为 12dp 圆角纵向 `ActionBarPopupWindow` 列表（48dp 行高、18dp 左右内边距、20dp 图标 + 16sp 完整文案、6dp 按压圆角），随指单有效项高亮、移出取消、危险项标红 | 面板不整块按压缩放，正常底栏透镜不变；真机手势待复验 |
| 转发人数 | 私有方法按完整签名 declared + accessible 查找；同步原生搜索勾选 | 真包可访问性不证明服务端无限制 |
| 消息行、气泡与时间 | 己方隐藏头像并右贴 6dp，对方 42dp 头像（51dp 总缩进）随组浮动；外角 17dp / 连发内角 6dp 气泡与 3dp 竖线引用块；全 9 类内容组件覆盖 `f1()`/`h1()` 绘制正文下部 `MessageStamp` 或右下角 `SurfaceStamp`（最小高 17dp）并折叠顶部 `AIOTimestampComponent` | 不改文字数据和复制内容；关闭/复用恢复；真机视觉待复验 |
| 输入区 | 接入 `InputEditTextVBDelegate.U`、`AIOInputExpandableVB`、`compat.b`、`BottomBlurVB`、`AIOShortcutBarVB`，22dp 圆角玻璃胶囊并隐藏通栏底部模糊与顶部分割线 | 保留全部原生按钮与层级；真机键盘交互待复验 |
| At 对象 | 群消息按 TextElement 元素顺序匹配；UIN 或缓存 UID 映射打开资料卡 | 原有点击段优先；保留长按和滑动；无 UID 映射明确报错，不猜 QQ 号 |
| 头像圆角 | QQ 原生 RoundRectImageView 半径，0～50%，默认 20% | 原图已经裁圆时无法凭空补角；关闭还原原值 |
| 头像定位 | 无浮绘源时不隐藏原生头像；旧位置限定在可见范围附近 | 不代表全部真实列表回收/滚动问题已经验收 |

设置入口：菜单内部「消息外观」。自定义格式最长 80 字符、单行；非法格式不写入偏好。恢复默认同时还原时间格式和头像比例。消息内时间、At 对象、头像圆角分别有开关；头像连发浮绘仍由「连发合并」控制。

## 上游核对与取舍

实际读取 QAuxiliary [`4791fc9615b766808aae31b28d67854d02f5fac5`](https://github.com/cinit/QAuxiliary/tree/4791fc9615b766808aae31b28d67854d02f5fac5)：

- `me/ketal/hook/ShowMsgAt.kt` 的功能名是「消息显示At对象」，不是给输入框新增 At 面板。参考其功能含义，本仓依据 QQ 9.2.10 的实际组件独立实现；不引入其运行库或复制其代码。
- `cc/ioctl/hook/chat/CustomMsgTimeFormat.java` 修改的是原生时间格式，不是消息内的独立时间行；本仓按本次需求自行预留并绘制时间行。
- `cc/ioctl/hook/chat/RoundAvatarHook.java` 明确仅支持 QQ 8.8.11 以下，不照搬到当前 QQ；改用当前原生圆角接口。
- `io/github/memory2314/hook/RemoveShareLimit.kt` 作为行为参考；本仓使用真包已有的 `setSearchSelectedAndJoinedUins()`，不复制上游对搜索对象的假定。

尚未逐项评估的其他上游功能不默认新增；优先保证当前功能的实际生效、关闭恢复和列表复用。

## 真包证据与验证

只读 QQ APK SHA-256：`34bdea66e738062c3f762f7da98132addc7170394605a4f5ca1944bee1280c84`，37 个 DEX。`tools/symbols.txt` 检查名称，`tools/ui_paths.py` 检查完整签名、字段读写、调用边及私有访问标记。

圆角模式不是位掩码：`RoundRectImageView.checkPathChanged` 中 mode=1 的分支调用四角等半径 `Path.addRoundRect(RectF, float, float, Direction)`；脚本对此增加固定真包指令检查。

实际运行记录以对应提交 CI 为准。纯逻辑测试验证时间格式、时区、非法输入、圆角范围、重复名字和 UTF-16 偏移；源码契约验证恢复、触摸取消和原生链接保留。这些不能证明设备效果，尤其需要复验：普通文字长短消息/引用/表情、大字体、消息复制与长按、滚动回收、头像点击与圆角、开关恢复、转发搜索勾选以及资料卡跳转。

## 分支历史

主分支唯一未包含的提交为 `f9c1fc06939d0692f284c90db23fa97d2c5791ad`，只删除旧 `spec-check.yml`。本轮正常合入该历史，保留本分支同样的删除结果；不隐藏提示、不改默认分支、不重写已出包历史。推送后以 GitHub compare 的 behind_by 复核。
