// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑回归检查：防撤回 protobuf 判断与 99+ 精确数字替换条件。 */
class ProtoTest {
    @Test fun badgeRisesHalfAGlyphFromOriginalPositionWithoutLayoutDrift() {
        assertEquals(-7f,raisedBadgeTranslation(0f,14))
        assertEquals(-3.5f,raisedBadgeTranslation(4f,15))
        assertEquals(4f,raisedBadgeTranslation(4f,0))
        assertEquals(4f,raisedBadgeTranslation(4f,-1))
        repeat(30) { assertEquals(-14f,raisedBadgeTranslation(7f,42)) }
        assertEquals(0f,raisedBadgeTranslation(7f,14))
    }

    @Test fun badgeCenterUsesParentUnitsAndIsIdempotentAcrossWindowScales() {
        // 模型验证局部量，不冒充 Android Matrix/GPU 测试。
        for (scale in listOf(.7f,1f,1.12f,2f,-1f)) {
            for (width in listOf(8f,28f,96f)) {
                val anchor = 118f
                val left = 143f
                var translation = 0f
                repeat(20) {
                    val anchorWindow = 231f+scale*anchor
                    val centerWindow = 231f+scale*(left+width/2+translation)
                    translation = centeredBadgeTranslation(translation,(anchorWindow-231f)/scale,(centerWindow-231f)/scale)
                    assertTrue(kotlin.math.abs(left+width/2+translation-anchor) < .0001f)
                }
            }
        }
        assertEquals(12f,centeredBadgeTranslation(12f,Float.NaN,4f))
    }

    @Test fun kernelSpringDoesNotDiscardTimeAfterAStalledFrame() {
        val once = KernelSpring(.7f,250f)
        val split = KernelSpring(.7f,250f)
        once.target = 1f; split.target = 1f
        once.step(.12f); repeat(2) { split.step(.06f) }
        assertTrue(kotlin.math.abs(once.value-split.value) < .00001f)
        assertTrue(kotlin.math.abs(once.velocity-split.velocity) < .00001f)
    }

    @Test fun badgeBindingRetainsRealCountBeforeSetRedTextResetsIt() {
        val original = arrayOf<Any?>(null, 3, 12345, 99, "")
        val fixed = unclampedBadgeArgs(original)
        assertEquals(12345, fixed[2])
        assertEquals(Int.MAX_VALUE, fixed[3])
        assertEquals(99, original[3])
        // 原生 d.a：count > limit 会转文字且丢失 mNum；修正后走 setRedNum 真数量。
        assertTrue((original[2] as Int) > (original[3] as Int))
        assertFalse((fixed[2] as Int) > (fixed[3] as Int))
        for (style in listOf(1, 2, 5, 6, 11)) {
            val wording = arrayOf<Any?>(null, style, 12345, 99, "NEW")
            assertSame(wording, unclampedBadgeArgs(wording))
        }
        val small = arrayOf<Any?>(null, 3, 99, 99, "")
        assertSame(small, unclampedBadgeArgs(small))
    }

    @Test fun bottomBackgroundForwardsOnlyVerticalDragsAfterSlop() {
        assertFalse(verticalBarDrag(0f, 8f, 8))
        assertTrue(verticalBarDrag(2f, 9f, 8))
        assertTrue(verticalBarDrag(0f, -9f, 8))
        assertFalse(verticalBarDrag(9f, 2f, 8))
        assertFalse(verticalBarDrag(9f, 9f, 8))
        assertFalse(verticalBarDrag(0f, 0f, 8))
    }

    @Test fun exactUnreadBadgeCountOnlyExpandsOverflowLabel() {
        assertEquals("100", exactBadgeText("99+", 100))
        assertEquals("12345", exactBadgeText("99+", 12345))
        assertEquals("1234567", exactBadgeText("99+", 1234567))
        assertTrue(exactBadgeText("99+", 99) == null)
        assertTrue(exactBadgeText("100", 12345) == null)
        assertTrue(exactBadgeText(null, 12345) == null)
    }

    @Test fun unreadCountIsThirdArgumentNotStyle() {
        assertEquals(1234L, legacyBadgeCount(arrayOf<Any?>(null, 4, 1234, 0, 99, null, false)))
        assertTrue(legacyBadgeCount(arrayOf<Any?>(null, 1234, 4, 0, 99, null, false)) == null)
        assertTrue(legacyBadgeCount(arrayOf<Any?>(null, 2, 1234, 0, 99, null, false)) == null)
        assertTrue(legacyBadgeCount(arrayOf<Any?>(null, 4, 99, 0, 99, null, false)) == null)
        assertEquals(10000L, legacyBadgeCount(arrayOf<Any?>(null, 3, 10000L, 0, 99, null, false)))
    }

    @Test fun badgeWidthFollowsMeasuredDigitsWithSymmetricPadding() {
        assertEquals(28, numericBadgeWidth(20f, 18, 4))
        assertEquals(59, numericBadgeWidth(50.25f, 18, 4))
        assertEquals(18, numericBadgeWidth(3f, 18, 4))
    }

    @Test fun messageRunsBreakOnSenderTimeSystemAndMidnight() {
        assertTrue(continuesMessageRun("a", "a", 1300, 1000, 2, 2))
        assertFalse(continuesMessageRun("a", "b", 1001, 1000, 2, 2))
        assertFalse(continuesMessageRun("a", "a", 1301, 1000, 2, 2))
        assertFalse(continuesMessageRun("a", "a", 999, 1000, 2, 2))
        assertFalse(continuesMessageRun("a", "a", 1001, 1000, 5, 2))
        assertFalse(continuesMessageRun("a", "a", 1001, 1000, 2, 29))
        assertFalse(continuesMessageRun(null, null, 1001, 1000, 2, 2))
        assertFalse(continuesMessageRun("a", "a", 57601, 57599, 2, 2))
    }

    @Test fun ownMessagesKeepOneSenderWhenUidIsEmpty() {
        val pair = runSenderPair("u_old", 1, true, "", null, true)
        assertEquals(pair.first, pair.second)
        val byUin = runSenderPair("", 123, false, "uid", 123, false)
        assertEquals(byUin.first, byUin.second)
        val different = runSenderPair("u_a", null, false, "u_b", null, false)
        assertFalse(different.first == different.second)
        assertEquals(null to null, runSenderPair("", 0, false, "", 0, false))
    }

    @Test fun senderMetadataArrivalDoesNotBreakTheRun() {
        val pair = runSenderPair("uid", 123, false, "uid", 0, false)
        assertEquals("uid:uid" to "uid:uid", pair)
        val conflict = runSenderPair("a", 123, false, "b", 123, false)
        assertFalse(conflict.first == conflict.second)
    }

    @Test fun kernelWidthUsesUpstream76RatherThanThe56Height() {
        assertEquals(76, kernelTabWidth(1f, 0))
        assertEquals(228, kernelTabWidth(3f, 120))
        assertEquals(300, kernelTabWidth(3f, 300))
    }

    @Test fun kernelPressHoldThenReleaseActuallyDrivesBothSprings() {
        val press = KernelSpring(1f, 1000f)
        val scale = KernelSpring(.6f, 250f, 1f)
        press.target = 1f; scale.target = 78f/56f
        repeat(30) { press.step(1f/60); scale.step(1f/60) }
        assertTrue(press.value > .99f)
        assertTrue(scale.value > 1.38f)
        press.target = 0f; scale.target = 1f
        repeat(120) { press.step(1f/60); scale.step(1f/60) }
        assertEquals(0f, press.value)
        assertEquals(1f, scale.value)
    }

    @Test fun knobMatchesBarVisibleHeightAndUses12dpOpticalGap() {
        val p = knobPlacement(420,800,100f,500f,260f,564f,1f,false)
        assertTrue(p.beside)
        assertEquals(92,p.width); assertEquals(80,p.height)
        val outerLeft = 420-p.end-p.width
        val outerTop = 800-p.bottom-p.height
        assertEquals(12,outerLeft+8-260)
        assertEquals(500,outerTop+8)
        assertEquals(564,800-p.bottom-8)
    }

    @Test fun knobOpticalGapAndHeightMirrorInRtl() {
        val p = knobPlacement(420,800,160f,500f,320f,564f,1f,true)
        assertTrue(p.beside)
        assertEquals(12,160-(p.end+p.width-8))
        assertEquals(532,800-p.bottom-p.height/2)
        assertEquals(knobPlacement(420,800,100f,500f,260f,564f,1f,false).bottom,p.bottom)
    }

    @Test fun knobFallsAboveWithoutAddingTheMaximumPressEnvelope() {
        for (d in listOf(1f,1.3f,2.75f,3f)) {
            val height = (800*d).toInt()
            val p = knobPlacement((360*d).toInt(),height,100*d,500*d,260*d,564*d,d,false)
            assertFalse(p.beside)
            val inset = (8*d).toInt()
            assertEquals((64*d).toInt(),p.height-2*inset)
            val visibleBottom = height-p.bottom-inset
            assertTrue(kotlin.math.abs(500*d-visibleBottom-(12*d).toInt()) <= 1f)
            val wide = knobPlacement((420*d).toInt(),height,100*d,500*d,260*d,564*d,d,false)
            assertTrue(wide.beside)
            val visibleTop = height-wide.bottom-wide.height+inset
            assertTrue(kotlin.math.abs(visibleTop-500*d) <= 1f)
        }
    }

    @Test fun kernelCriticalSpringSettlesWithoutOvershoot() {
        val spring = KernelSpring(1f, 1000f)
        spring.target = 1f
        var previous = 0f
        repeat(240) {
            spring.step(1f / 120)
            assertTrue(spring.value >= previous && spring.value <= 1f)
            previous = spring.value
        }
        assertEquals(1f, spring.value)
        assertEquals(0f, spring.velocity)
        assertFalse(spring.step(1f / 60))
    }

    @Test fun kernelScaleUsesUnderdampedOvershootAndRestores() {
        val spring = KernelSpring(.6f, 250f, 1f)
        spring.target = 78f / 56f
        var peak = 1f
        repeat(240) { spring.step(1f / 120); peak = maxOf(peak, spring.value) }
        assertTrue(peak > spring.target)
        spring.target = 1f
        repeat(240) { spring.step(1f / 120) }
        assertEquals(1f, spring.value)
    }

    @Test fun kernelSpringIsIndependentOfDisplayRefreshRate() {
        fun run(fps: Int): Float {
            val spring = KernelSpring(.7f, 250f)
            spring.target = 1f
            repeat(fps / 2) { spring.step(1f / fps) }
            return spring.value
        }
        assertTrue(kotlin.math.abs(run(30) - run(120)) < .0001f)
        assertTrue(kotlin.math.abs(run(60) - run(120)) < .0001f)
    }

    @Test fun kernelSpringRetargetDoesNotResetPositionOrVelocity() {
        val spring = KernelSpring(.6f, 250f)
        spring.target = 1f
        repeat(4) { spring.step(1f / 60) }
        val position = spring.value
        val velocity = spring.velocity
        spring.target = 0f
        assertEquals(position, spring.value)
        assertEquals(velocity, spring.velocity)
        repeat(240) { spring.step(1f / 120) }
        assertEquals(0f, spring.value)
    }

    @Test fun kernelIndexHonorsPaddingRtlAndBounds() {
        assertEquals(0, kernelIndex(-10f, 232f, 4, false))
        assertEquals(3, kernelIndex(240f, 232f, 4, false))
        assertEquals(3, kernelIndex(4f, 232f, 4, true))
        assertEquals(0, kernelIndex(228f, 232f, 4, true))
        assertEquals(1, kernelIndex(88f, 232f, 4, false))
        assertEquals(0, kernelIndex(8f, 0f, 0, false))
    }

    @Test fun plainBottomCountDoesNotReplaceDotsNewOrIconBadges() {
        assertTrue(bottomNumericBadge("1200", 2))
        assertTrue(bottomNumericBadge("9", 9))
        assertFalse(bottomNumericBadge("NEW", 4))
        assertFalse(bottomNumericBadge("9", 7))
        assertFalse(bottomNumericBadge("9", 0))
        assertFalse(bottomNumericBadge("0", 2))
        assertFalse(bottomNumericBadge("000", 3))
    }

    @Test fun barGestureStartsInsideNativePageAndPreservesMovement() {
        val shift = barTouchOffset(900f, 800)
        assertEquals(799f, 900f + shift)
        assertEquals(-60f, (840f + shift) - (900f + shift))
        assertEquals(0f, barTouchOffset(400f, 800))
        assertEquals(1f, -30f + barTouchOffset(-30f, 800))
    }

    @Test fun onlyTheLastRowOfARunKeepsTheAvatar() {
        // NagramXF `ChatMessageCell.java:7081`：多格块只有底边那一格画头像。
        // 映射到连发组：只有 `continues == false` 的那一行保留原生头像。
        val run = List(5) { avatarVisibleInRun(continues = it < 4, enabled = true) }
        assertEquals(listOf(false, false, false, false, true), run)
        assertTrue(avatarVisibleInRun(continues = false, enabled = true))
        assertFalse(avatarVisibleInRun(continues = true, enabled = true))
    }

    @Test fun turningOffMergeRestoresEveryNativeAvatar() {
        assertTrue(avatarVisibleInRun(continues = true, enabled = false))
        assertTrue(avatarVisibleInRun(continues = false, enabled = false))
    }

    @Test fun continuationHeightUsesContentNotTheInvisibleAvatarSlot() {
        assertEquals(22, compactRunHeight(44, 22, 0, 0))
        assertEquals(26, compactRunHeight(44, 22, 4, 0))
        assertEquals(32, compactRunHeight(44, 22, 0, 32))
        assertEquals(80, compactRunHeight(80, 80, 0, 0))
        assertEquals(44, compactRunHeight(44, 0, 0, 0))
    }

    @Test fun nativeVisibilityUpdatesCannotReshowFoldedNickOrDuplicateAvatar() {
        assertEquals(8, runVisibility(0, 8))
        assertEquals(4, runVisibility(0, 4))
        assertEquals(8, runVisibility(8, 4))
        assertEquals(0, runVisibility(0, null))
        assertEquals(8, runVisibility(8, null))
    }

    @Test fun prependingHistoryAndReplacementUpdateBoundMessageIndex() {
        val item = PendingMessage(7)
        assertEquals(2, runItemIndex(listOf(PendingMessage(3), PendingMessage(4), item), item, 0))
        assertEquals(1, runItemIndex(listOf(PendingMessage(3), PendingMessage(7)), item, 2))
        assertEquals(-1, runItemIndex(emptyList<Any>(), item, 0))
        val pending = PendingMessage(0)
        assertEquals(-1, runItemIndex(listOf(PendingMessage(0)), pending, 0))
    }

    class PendingMessage(private val id: Long) { fun getMsgId(): Long = id }
    @Test fun zeroIdPendingMessageStillHasAStableGroupKey() {
        val first = PendingMessage(0)
        assertEquals(RunKey(first), RunKey(first))
        assertFalse(RunKey(first) == RunKey(PendingMessage(0)))
        assertEquals(RunKey(PendingMessage(123)), RunKey(PendingMessage(123)))
    }

    class PrivateAvatar { private fun C1(): String = "original-avatar" }
    @Test fun avatarAccessorCanBePrivate() {
        val method = PrivateAvatar::class.java.method("C1").apply { isAccessible = true }
        assertEquals("original-avatar", method.invoke(PrivateAvatar()))
    }

    @Test fun runStartsSharesLongGroupAndRespectsBreaks() {
        val cache = RunStarts(1000)
        var calls = 0
        val joins: (Int) -> Boolean = { calls++; it != 400 }
        assertEquals(400, cache.start(999, joins))
        assertEquals(400, cache.start(900, joins))
        assertEquals(600, calls) // 已缓存的组不再向前重复反射。
        assertEquals(0, cache.start(399, joins))
        assertEquals(0, cache.start(0, joins))
        assertEquals(999, calls)
    }

    @Test fun runStartsOutOfOrderAndNewSnapshotDoNotReuseOldGroup() {
        val cache = RunStarts(8)
        assertEquals(2, cache.start(3) { it != 2 })
        assertEquals(5, cache.start(7) { it != 2 && it != 5 })
        assertEquals(2, cache.start(4) { it != 2 && it != 5 })
        assertEquals(0, RunStarts(8).start(7) { true })
        assertEquals(7, RunStarts(8).start(7) { false })
    }

    @Test fun inputSpacingNeverConsumesButtonTouchWidth() {
        assertEquals(8, inputButtonGap(320, 240, 5, 8))
        assertEquals(5, inputButtonGap(260, 240, 5, 8))
        assertEquals(0, inputButtonGap(200, 240, 5, 8))
        assertEquals(0, inputButtonGap(320, 48, 1, 8))
        assertEquals(0, inputButtonGap(320, 0, 0, 8))
    }

    @Test fun compactSpacingIsBoundedAndRestoresOriginal() {
        assertEquals(1, compactMessageTop(20, true, 1f))
        assertEquals(1, compactMessageTop(2, true, 1f))
        assertEquals(0, compactMessageTop(0, true, 3f))
        assertEquals(-3, compactMessageTop(-3, true, 1f))
        assertEquals(6, compactMessageTop(60, false, 3f))
        assertEquals(2, compactMessageTop(3, false, 1f))
        assertEquals(2, compactMessageTop(20, true, 2f))
    }

    @Test fun compactMenuFitsAvailableWindowAndImeSpace() {
        assertEquals(360 to 520, menuSize(1080, 1920, 1f))
        assertEquals(328 to 452, menuSize(360, 600, 1f))
        assertEquals(328 to 168, menuSize(360, 200, 1f))
        assertEquals(0 to 0, menuSize(0, 0, 1f))
        assertEquals(984 to 1560, menuSize(1080, 2400, 3f))
    }

    @Test fun menuDocksAboveBottomEntryAndBelowTopEntry() {
        assertEquals(MenuPlacement(24, 1168, 360, 520), menuPlacement(400, 1920, 300, 1700, 376, 1764, 1f))
        assertEquals(MenuPlacement(24, 156, 360, 520), menuPlacement(400, 1920, 300, 80, 376, 144, 1f))
        val left = menuPlacement(400, 1920, 16, 1700, 92, 1764, 1f)
        assertEquals(16, left.left)
    }

    @Test fun menuPlacementStaysInsideSafeAreaAcrossDensityAndResize() {
        for (density in listOf(1f, 1.5f, 3f)) for (width in listOf(0, 20, 360, 1080)) {
            for (height in listOf(0, 20, 168, 600, 2400)) for (anchorY in listOf(-100, 80, height + 100)) {
                val p = menuPlacement(width, height, -20, anchorY, width + 40, anchorY + 64, density)
                assertTrue(p.width >= 0 && p.height >= 0)
                assertTrue(p.left >= 0 && p.top >= 0)
                assertTrue(p.left + p.width <= width && p.top + p.height <= height)
            }
        }
    }

    @Test fun sharedMaterialKeepsPanelCornersInsteadOfTurningItIntoAPill() {
        assertEquals(22f, kernelRadius(360f, 520f, 22f))
        assertEquals(32f, kernelRadius(76f, 64f, Float.MAX_VALUE))
        assertEquals(10f, kernelRadius(20f, 520f, 22f))
        assertEquals(0f, kernelRadius(0f, 0f, 22f))
    }

    @Test fun messageTimestampFormatsSecondsAndRejectsInvalidPatterns() {
        val utc = java.time.ZoneOffset.UTC
        assertEquals("2023-11-14 22:13:20", messageTime(1700000000L, "yyyy-MM-dd HH:mm:ss", utc))
        assertEquals("06:13", messageTime(1700000000L, "HH:mm", java.time.ZoneOffset.ofHours(8)))
        assertEquals("22:13", messageTime(1700000000L, "invalid[", utc))
        assertEquals("", messageTime(0, "HH:mm", utc))
        assertEquals("", messageTime(Long.MAX_VALUE, "HH:mm", utc))
        assertFalse(validTimePattern(""))
        assertFalse(validTimePattern("invalid["))
        assertFalse(validTimePattern("HH:mm\nss"))
        assertTrue(validTimePattern("'" + "x".repeat(90) + "'"))
        assertTrue(validTimePattern("MM-dd HH:mm:ss"))
    }

    @Test fun avatarRadiusIsBoundedAndScalesWithTheRealImageSize() {
        assertEquals(0, avatarCorner(48, 0))
        assertEquals(9, avatarCorner(48, 20))
        assertEquals(24, avatarCorner(48, 50))
        assertEquals(48, avatarCorner(48, 100))
        assertEquals(0, avatarCorner(48, -1))
        assertEquals(0, avatarCorner(-1, 50))
    }

    @Test fun mentionRangesKeepRepeatedNamesUnicodeAndAtAllDistinct() {
        val ranges = mentionRanges("😀 @甲 @甲 @全体成员", listOf(Triple("@甲", 12L, ""), Triple("@甲", 13L, ""),
            Triple("@全体成员", 0L, "")))
        assertEquals(listOf(MentionRange(3, 5, 12L, ""), MentionRange(6, 8, 13L, "")), ranges)
        assertEquals(listOf(MentionRange(0, 2, 0L, "u_test")), mentionRanges("@乙", listOf(Triple("@乙", 0L, "u_test"))))
        assertEquals(emptyList<MentionRange>(), mentionRanges("plain", listOf(Triple("@甲", 12L, ""))))
    }

    @Test fun menuTouchHitAndDangerClassificationFollowSingleItemAndOutsideCancel() {
        val cells = listOf(
            MenuItemBounds(0f, 0f, 60f, 56f),
            MenuItemBounds(60f, 0f, 120f, 56f),
            MenuItemBounds(0f, 56f, 60f, 112f),
        )
        assertEquals(0, hitMenuItemIndex(10f, 20f, cells))
        assertEquals(1, hitMenuItemIndex(60f, 20f, cells))
        assertEquals(2, hitMenuItemIndex(30f, 80f, cells))
        assertEquals(-1, hitMenuItemIndex(-1f, 20f, cells))
        assertEquals(-1, hitMenuItemIndex(120f, 20f, cells))
        assertEquals(-1, hitMenuItemIndex(90f, 80f, cells))
        assertEquals(-1, hitMenuItemIndex(Float.NaN, 20f, cells))
        assertTrue(dangerousMenuItem("DelMenuItem", "复制"))
        assertTrue(dangerousMenuItem("RevokeMenuItem", "复制"))
        assertTrue(dangerousMenuItem(null, "删除"))
        assertTrue(dangerousMenuItem(null, "撤回"))
        assertTrue(dangerousMenuItem(null, "结束进程"))
        assertFalse(dangerousMenuItem("CopyMenuItem", "复制"))
        assertFalse(dangerousMenuItem(null, "恢复默认"))
    }

    private fun varint(v: Long): ByteArray {
        var x = v
        val out = ArrayList<Byte>()
        while (x >= 0x80) { out += (x and 0x7f or 0x80).toByte(); x = x ushr 7 }
        out += x.toByte()
        return out.toByteArray()
    }
    private fun field(num: Int, v: Long) = varint((num shl 3).toLong()) + varint(v)
    private fun field(num: Int, b: ByteArray) = varint((num shl 3 or 2).toLong()) + varint(b.size.toLong()) + b

    /** MsgPush{1: Message{2: ContentHead{1: type, 2: subType}, 3: Body{2: content}}} */
    private fun push(type: Long, sub: Long, content: ByteArray = ByteArray(0)) =
        field(1, field(2, field(1, type) + field(2, sub) + field(4, 1L shl 40)) + field(3, field(2, content)))

    @Test fun c2cRecall() {
        assertTrue(recall(push(528, 138)) != null) // 解不出细节也照样吞
        val r = recall(push(528, 138, field(1, field(1, "u_abc".toByteArray()) + field(2, "u_me".toByteArray()) + field(3, 4567L))))!!
        assertEquals(1, r.chatType)
        assertEquals("u_abc", r.peer)
        assertEquals(listOf(4567L), r.seqs)
    }

    @Test fun ordinaryMessage() = assertTrue(recall(push(166, 11)) == null)

    @Test fun groupRecallNeedsOpType7() {
        val body = field(1, 7L) + field(4, 123L) + field(11, field(1, "u_op".toByteArray()) + field(3, field(1, 88L)) + field(3, field(1, 89L)))
        val r = recall(push(732, 17, ByteArray(7) + body))!!
        assertEquals(2, r.chatType)
        assertEquals("123", r.peer)
        assertEquals(listOf(88L, 89L), r.seqs)
        assertTrue(recall(push(732, 17, ByteArray(7) + field(1, 6L))) == null)
        assertTrue(recall(push(732, 17, ByteArray(3))) == null)
    }

    @Test fun stripDropsOnlyField8() {
        val keep = field(3, 1L) + field(7, byteArrayOf(9, 9)) + field(10, 1L)
        assertArrayEquals(keep, stripSyncRecall(field(3, 1L) + field(8, field(4, byteArrayOf(1, 2))) + field(7, byteArrayOf(9, 9)) + field(10, 1L)))
        assertSame(keep, stripSyncRecall(keep))
    }

    @Test fun garbageIsNotSilentlyARecall() {
        assertTrue(recall(byteArrayOf()) == null)
        assertTrue(recall(field(1, field(2, field(1, 528L)))) == null)
    }

    @Test fun nagramHeaderThreePillsAndAvatarVisualWidthMatchActionBarAndChatAvatarContainer() {
        val single = nagramHeaderLayout(360, 58, 1f, unreadWidth = 0, rightCount = 1, titleWidth = 80, subtitleWidth = 50, hasAvatar = true)
        assertEquals(PillBox(6, 6, 52, 52), single.leftPill)
        assertEquals(PillBox(308, 6, 354, 52), single.rightPill)
        assertEquals(144, single.centerPill.width())
        assertEquals(42, single.avatarSize)
        assertEquals(single.centerPill.left + 2, single.avatarLeft)
        assertEquals(single.centerPill.top + 2, single.avatarTop)
        assertEquals(single.centerPill.left + 50, single.textLeft)
        assertEquals(single.centerPill.right - 14, single.textRight)

        val multi = nagramHeaderLayout(360, 58, 1f, unreadWidth = 24, rightCount = 2, titleWidth = 240, subtitleWidth = 60, hasAvatar = true)
        assertEquals(70, multi.leftPill.width())
        assertEquals(88, multi.rightPill.width())
        assertTrue(multi.centerPill.left >= multi.leftPill.right + 6)
        assertTrue(multi.centerPill.right <= multi.rightPill.left - 6)
    }

    @Test fun nagramBubbleCornersAndRowMarginsFollowDirectionAndRunEdges() {
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = false, continued = false, continues = false, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = false, continued = false, continues = true, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = false, continued = true, continues = true, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = false, continued = true, continues = false, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = true, continued = false, continues = false, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = true, continued = false, continues = true, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = true, continued = true, continues = true, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = true, continued = true, continues = false, density = 1f))
        assertEquals(BubbleCornerRadii(17, 17, 17, 17), nagramBubbleRadii(self = false, continued = false, continues = false, density = 1f, heightPx = 24))

        val incoming = nagramRowMargins(self = false, density = 1f)
        assertEquals(40, incoming.avatarWidth)
        assertEquals(8, incoming.avatarMarginStart)
        assertEquals(6, incoming.contentMarginStart)
        assertEquals(12, incoming.contentMarginEnd)
        assertEquals(54, incoming.avatarMarginStart + incoming.avatarWidth + incoming.contentMarginStart)

        val outgoing = nagramRowMargins(self = true, density = 1f)
        assertEquals(40, outgoing.avatarWidth)
        assertEquals(0, outgoing.avatarMarginStart)
        assertEquals(8, outgoing.avatarMarginEnd)
        assertEquals(12, outgoing.contentMarginStart)
        assertEquals(6, outgoing.contentMarginEnd)
    }

    @Test fun timeFormatGuideCoversEveryTokenAndMidnightUsesHour24() {
        for (token in listOf("yyyy/yy", "MM/M", "dd/d", "E", "HH/H", "kk/k 1-24时", "hh/h", "KK/K 0-11时", "a", "mm/m", "ss/s", "SSS/S", "左对齐")) {
            assertTrue(TIME_FORMAT_GUIDE.contains(token))
        }
        val utc = java.time.ZoneOffset.UTC
        assertEquals("24:00", messageTime(1700006400L, "kk:mm", utc))
        assertEquals("0:00", messageTime(1700006400L, "K:mm", utc))
    }

    @Test fun topSafeAreaOffsetAndUnclampedRunGapAndDefaultBlurWorkDeterministically() {
        val shifted = nagramHeaderLayout(360, 50, 1f, unreadWidth = 0, rightCount = 1, titleWidth = 80, subtitleWidth = 50, hasAvatar = true, topOffsetDp = 1f)
        assertEquals(PillBox(6, 1, 52, 47), shifted.leftPill)
        assertEquals(PillBox(308, 1, 354, 47), shifted.rightPill)
        assertEquals(3, shifted.avatarTop)
        val raised = nagramHeaderLayout(360, 50, 1f, unreadWidth = 0, rightCount = 1, titleWidth = 80, subtitleWidth = 50, hasAvatar = true, topOffsetDp = -4f)
        assertEquals(PillBox(6, -4, 52, 42), raised.leftPill)
        val customRadii = nagramBubbleRadii(self = false, continued = false, continues = false, density = 2f, cornerDp = 24f)
        assertEquals(BubbleCornerRadii(48, 48, 48, 48), customRadii)
        assertEquals(17f, bubbleCornerDp())
        assertEquals(5f, bubblePaddingDp())
        assertTrue(continuesMessageRun("a", "a", 3600, 1000, 2, 2, maxGapSeconds = Long.MAX_VALUE))
        assertEquals(1f, glassBlurDp())

        val noRight = nagramHeaderLayout(1272, 94, 1.953125f, unreadWidth = 0, rightCount = 0, titleWidth = 97, subtitleWidth = 0, hasAvatar = false)
        assertEquals(636, noRight.centerPill.centerX())
        assertEquals(636, (noRight.textLeft + noRight.textRight) / 2)
        assertTrue(noRight.centerPill.top >= 0)

        // 顶栏停在状态栏／摄像头下沿（真机 141px），不是顶到 y=0：滑条只在它以下再往下让。
        assertEquals(0, safeTopShiftDp())
        assertEquals(141, reducedSafeTop(141, 1.9761429f, edgePx = 141))
        assertEquals(172, reducedSafeTop(141, 1.9761429f, 15.75f, edgePx = 141))  // 15.75dp × 1.976 ≈ 31px
        assertEquals(200, reducedSafeTop(141, 1.9761429f, 30f, edgePx = 141))     // 30dp × 1.976 ≈ 59px
        // 下沿比 QQ 预留值还高时，按 QQ 的原值走：绝不把内容推到屏幕最上沿。
        assertEquals(100, reducedSafeTop(100, 1.9761429f, edgePx = 141))
        // 没量到下沿（全屏页把状态栏让成 0）时退回 QQ 原值，同样不顶到 0。
        assertEquals(141, reducedSafeTop(141, 1.9761429f, edgePx = 0))
        assertEquals(0, reducedSafeTop(0, 1.953125f))
        assertEquals(331, reducedSafeTop(141, 1.9761429f, 400f, edgePx = 141))    // 夹紧到 141 + 96dp = 331px

        assertEquals(-85.5f, centeredBarTranslationX(1272, 460, 1.953125f, rtl = false))
        assertEquals(85.5f, centeredBarTranslationX(1272, 460, 1.953125f, rtl = true))
        assertEquals(0f, centeredBarTranslationX(400, 460, 1.953125f, rtl = false))
        val tabW = kernelTabWidth(1.953125f, 0)
        assertEquals(148, tabW)
        val pad4 = (4 * 1.953125f).toInt()
        val barW = 3 * tabW + 2 * pad4
        assertEquals(458, barW)
        assertEquals((barW - 2 * pad4) / 3, tabW)
    }

    // ---- 全量日志合并：反复导出只应产生一个累计文件，编号副本由导出前清理负责 ----
    private fun fakeReport(times: List<String>, snapshotTitles: List<String>, hookTop: String): String {
        val sb = StringBuilder()
        sb.append("Qself Full QQ Diagnostic Report\nVersion      : test\n\n")
        sb.append("--- [2] Hook Invocation Counts (Top 120) ---\n")
        sb.append("     1  $hookTop\n")
        sb.append("--- [3] Live Activity & Scene View Hierarchy Snapshots (${snapshotTitles.size}) ---\n")
        for (t in snapshotTitles) {
            sb.append("=== Scene Snapshot: $t ===\n")
            sb.append("[0] root ${'$'}{t.hashCode()}\n")
        }
        sb.append("--- [4] Qself Events & Hook Samples (${times.size}) ---\n")
        for (t in times) sb.append("$t I/Qself: event\n")
        sb.append("--- [5] UI / AIO / Header / Error QLog Stream (${times.size}) ---\n")
        for (t in times) sb.append("$t D/Qself: ui\n")
        sb.append("--- [6] All QQ QLog Stream (${times.size}) ---\n")
        for (t in times) sb.append("$t V/Qself: qq\n")
        return sb.toString()
    }

    @Test fun mergeDiagnosticReportsUnionDedupesAndSortsStreamingSections() {
        val a = fakeReport(listOf("03:00:01.000", "03:00:03.000"), listOf("Home:1", "AIO:1"), "hook.A")
        val b = fakeReport(listOf("03:00:03.000", "03:00:02.000"), listOf("AIO:1", "Home:2"), "hook.B")
        val m = mergeDiagnosticReports(a, b)
        // 报告头与 [2] 钩子调用次数永远用最新一份（次数是累计的，不该叠加旧值）
        assertTrue(m.contains("hook.B"))
        assertFalse(m.contains("hook.A"))
        // 快照按标题取并集：同标题新的覆盖旧的，不同标题都保留
        assertTrue(m.contains("=== Scene Snapshot: Home:1 ==="))
        assertTrue(m.contains("=== Scene Snapshot: Home:2 ==="))
        assertTrue(m.contains("=== Scene Snapshot: AIO:1 ==="))
        assertTrue(m.indexOf("--- [3]") < m.indexOf("Home:1"))
        // 流式小节 4/5/6 按整条记录去重后按时间戳排序：3 条唯一时间 × 3 个小节
        assertTrue(m.contains("--- [4] Qself Events & Hook Samples (3) ---"))
        assertTrue(m.contains("--- [5] UI / AIO / Header / Error QLog Stream (3) ---"))
        assertTrue(m.contains("--- [6] All QQ QLog Stream (3) ---"))
        for (sec in listOf("event", "ui", "qq")) {
            val idx = m.lineSequence()
                .filter { it.endsWith(sec) }
                .map { it.take(12) }
                .toList()
            assertEquals(listOf("03:00:01.000", "03:00:02.000", "03:00:03.000"), idx)
        }
        // 合并幂等：再合并一次同样内容，长度与增量都不再变化
        val again = mergeDiagnosticReports(m, b)
        assertEquals(m.length, again.length)
        assertEquals(0, reportDeltaRecords(m, again))
        // 首次导出（没有历史文件）原样返回
        assertEquals(b, mergeDiagnosticReports(null, b))
        assertEquals(b, mergeDiagnosticReports("", b))
    }

    @Test fun reportDeltaRecordsOnlyCountsNewlyAddedRecords() {
        val a = fakeReport(listOf("03:00:01.000"), listOf("Home:1"), "hook.A")
        val b = fakeReport(listOf("03:00:01.000", "03:00:09.000"), listOf("Home:1"), "hook.A")
        val merged = mergeDiagnosticReports(a, b)
        // 三个流式小节各新增 1 条
        assertEquals(3, reportDeltaRecords(a, merged))
        // 没有历史记录时，增量就是本次全部条数（3 个小节 × 2 条 = 6）
        assertEquals(6, reportDeltaRecords(null, b))
        // 完全相同的内容没有增量
        assertEquals(0, reportDeltaRecords(a, a))
    }

    @Test fun mergeDiagnosticReportsCapsStreamingRecordsAndSnapshots() {
        val times = (0 until 40).map { "03:%02d:00.000".format(it) }
        val a = fakeReport(times, (0 until 20).map { "S$it" }, "hook.A")
        val b = fakeReport(times.takeLast(2), listOf("S99"), "hook.B")
        val m = mergeDiagnosticReports(a, b)
        // 记录数上限：只保留最新的 MAX_MERGED_RECORDS 条，40 < 12000 所以全留
        assertTrue(m.contains("--- [6] All QQ QLog Stream (40) ---"))
        // 快照上限：只保留最新的 MAX_MERGED_SNAPSHOTS 块（旧的被淘汰）
        assertEquals(MAX_MERGED_SNAPSHOTS, m.lineSequence().count { it.startsWith("=== Scene Snapshot: ") })
        assertTrue(m.contains("=== Scene Snapshot: S99 ==="))
        assertFalse(m.contains("=== Scene Snapshot: S0 ==="))
    }

    @Test fun trimRecordCutsTheMiddleAndIsIdempotent() {
        val short = "03:00:01.000 短\n"
        assertEquals(short, trimRecord(short))
        // QQ 的 QLog 会吐 15KB 的整段 dump（Notification Channels / 配置解析结果），头尾才有信息量
        val long = "03:00:01.000 HEAD" + "x".repeat(20000) + "TAIL\n"
        val t = trimRecord(long)
        assertTrue(t.length <= MAX_RECORD_CHARS + 64)
        assertTrue(t.startsWith("03:00:01.000 HEAD"))
        assertTrue(t.trimEnd().endsWith("TAIL"))
        assertTrue(t.contains("…[截断 "))
        // 幂等：掐过的记录再掐一次长度不变，否则合并去重会把同一条当成两条
        assertEquals(t, trimRecord(t))
    }

    @Test fun capByBytesKeepsTheNewestAndAlwaysKeepsOne() {
        val recs = (0 until 10).map { "%010d\n".format(it) } // 每条 11 字节
        assertEquals(listOf(recs[7], recs[8], recs[9]), capByBytes(recs, 33))
        assertEquals(recs, capByBytes(recs, 1000))
        // 预算再小也至少保留最新一条，不会因为一条超长记录把整个小节清空
        assertEquals(listOf(recs[9]), capByBytes(recs, 1))
        assertEquals(emptyList<String>(), capByBytes(emptyList(), 100))
    }

    private fun frameCount(text: String): Long =
        Regex("""累计 (\d+) 帧""").find(text)?.groupValues?.get(1)?.toLong() ?: -1L

    @Test fun frameMeterCountsStallsClosesWindowsAndIgnoresHugeGaps() {
        // 两条帧率用例共用一个采样器，JUnit 的方法顺序不保证，先清空再喂
        FrameMeter.reset()
        // 先喂一批不卡的帧，把上一轮窗口收干净（全不卡的窗口不记录）
        repeat(FRAME_WINDOW_GAP + 30) { FrameMeter.feed(16f) }
        assertFalse(FrameMeter.snapshot().contains("帧率窗口 "))
        val n0 = frameCount(FrameMeter.snapshot())
        assertTrue(n0 > 0)
        // 回前台的第一帧间隔动辄几秒，不能算掉帧
        FrameMeter.feed(5_000f)
        assertEquals(n0, frameCount(FrameMeter.snapshot()))
        // 5 帧卡顿之后再喂正常帧，窗口收口并记下这一段
        repeat(5) { FrameMeter.feed(90f) }
        repeat(FRAME_WINDOW_GAP + 5) { FrameMeter.feed(16f) }
        val snap = FrameMeter.snapshot()
        assertTrue(snap.contains("帧率窗口 "))
        assertTrue(snap.contains("卡顿=5"))
        assertTrue(snap.contains("掉帧="))
        assertFalse(snap.contains("NaN"))
        assertEquals(n0 + 5 + FRAME_WINDOW_GAP + 5, frameCount(snap))
    }

    @Test fun frameMeterDerivesJankBudgetFromTheObservedRefreshRate() {
        FrameMeter.reset()
        // 120Hz 机器：好帧 8.3ms，掉帧线该是 12.5ms 而不是写死的 16.7ms
        repeat(200) { FrameMeter.feed(8.3f) }
        repeat(5) { FrameMeter.feed(60f) }
        repeat(FRAME_WINDOW_GAP + 5) { FrameMeter.feed(8.3f) }
        val snap = FrameMeter.snapshot()
        assertTrue("周期没测出来：\n$snap", snap.contains("帧周期 8.3ms"))
        assertTrue("刷新率没算出来：\n$snap", snap.contains("120Hz"))
        val win = snap.lineSequence().filter { it.startsWith("帧率窗口 ") }.last()
        assertTrue("窗口：\n$win", win.contains("掉帧=5"))
        assertTrue("窗口：\n$win", win.contains("卡顿=5"))
        assertTrue("窗口：\n$win", win.contains("最长=60ms"))
    }

    @Test fun frameMeterPeriodIgnoresSpuriousShortFrames() {
        FrameMeter.reset()
        repeat(200) { FrameMeter.feed(8.3f) }
        FrameMeter.feed(1.2f)   // 偶发抖动：不能把刷新周期从 8.3ms 带塌，否则正常帧整片判成掉帧
        repeat(FRAME_PERIOD_EVERY.toInt() + 10) { FrameMeter.feed(8.3f) }
        val snap = FrameMeter.snapshot()
        val head = snap.lineSequence().first { it.startsWith("帧率（真机）") }
        assertTrue("周期被抖动带偏：\n$snap", snap.contains("帧周期 8.3ms"))
        assertTrue("8.3ms 的正常帧被判成掉帧：\n$head", head.contains("掉帧 0（0%"))
        assertTrue("错过同步不该有：\n$head", head.contains("错过同步 0 次"))
    }

    @Test fun frameMeterRecordsOneLinePerGestureAndSkipsTaps() {
        FrameMeter.reset()
        repeat(200) { FrameMeter.feed(8.3f) }          // 先测出 120Hz 周期，掉帧线才落在 12.45ms
        var emitted = ""
        frameEventSink = { emitted = it }
        try {
            FrameMeter.noteTouch(TOUCH_DOWN)
            repeat(120) { FrameMeter.feed(8.3f) }
            repeat(3) { FrameMeter.feed(60f) }          // 三帧卡顿
            FrameMeter.noteTouch(TOUCH_UP)
            val line = FrameMeter.snapshot().lineSequence().first { it.startsWith("最近手势 : ") }
            assertTrue("手势没记到：\n$line", line.contains("帧=123"))
            assertTrue("掉帧数不对：\n$line", line.contains("掉帧=3"))
            assertTrue("卡顿数不对：\n$line", line.contains("卡顿=3"))
            assertTrue("最长帧不对：\n$line", line.contains("最长=60ms"))
            assertTrue("没算出 fps：\n$line", line.contains("fps）"))
            // 同一段手势还要无条件进事件流：导出日志里滑动一次就有一行，不必等窗口碰运气收口
            assertTrue("手势没进事件流：\n$emitted", emitted.startsWith("帧率手势 ") && emitted.contains("帧=123"))
        } finally {
            frameEventSink = null
        }
        // 点一下只画两三帧，不算一次滑动，不许刷屏
        FrameMeter.reset()
        FrameMeter.noteTouch(TOUCH_DOWN)
        repeat(3) { FrameMeter.feed(8.3f) }
        FrameMeter.noteTouch(TOUCH_UP)
        assertTrue("短触摸也记成了手势", FrameMeter.snapshot().contains("本进程还没有滑动记录"))
    }

    @Test fun frameMeterClosesTheWindowAtTheStallInsteadOfThirtyFramesLater() {
        FrameMeter.reset()
        // 200 帧后每 30 帧收一次空窗口（无掉帧不入库），恰好在第 180 帧收过一次，此时窗口里已有 20 帧
        repeat(200) { FrameMeter.feed(8.3f) }
        // 卡一帧，下一帧正常：卡顿段一结束窗口就要收口，否则一直滑的时候 gap 被反复清零，这段卡顿永远不进日志
        FrameMeter.feed(90f)
        FrameMeter.feed(8.3f)
        val win = FrameMeter.snapshot().lineSequence().lastOrNull { it.startsWith("帧率窗口 ") }
        assertTrue("卡顿当下没有收口", win != null)
        assertTrue("窗口内容不对：\n$win", win!!.contains("卡顿=1"))
        assertTrue("窗口内容不对：\n$win", win.contains("最长=90ms"))
        assertTrue("窗口帧数不对：\n$win", win.contains("帧=${FRAME_WINDOW_MIN + 2} "))
    }

    /**
     * 底部让位：真机导出实测列表 141..1881、输入浮层顶 1778。
     * 旧算法拿「窗口根视图高 2800」减列表底边得到 919，再用 216-919 算出一个负数 → 恒为 0，
     * 于是最后一条消息整条压在输入栏底下。这里钉住三件事：有重叠就必须让位、浮层在下方就不让、
     * 键盘把浮层抬上去时让位跟着变大。
     */
    @Test fun listBottomInsetFollowsTheRealOverlayInsteadOfTheWindowHeight() {
        assertEquals(115, listBottomInsetPx(1881, 1778, 12, 696))
        assertTrue("浮层完全在列表下方时不该让位", listBottomInsetPx(1881, 1979, 12, 696) == 0)
        assertEquals(735, listBottomInsetPx(1881, 1158, 12, 1000))
        assertTrue("还没布局时不能瞎让位", listBottomInsetPx(0, 1778, 12, 696) == 0)
        assertEquals(696, listBottomInsetPx(1881, 100, 12, 696))
    }

    @Test fun statusFadeIsOpaqueAtTheTopClearAtTheBottomWithNoKinkAtEitherEnd() {
        assertEquals(1.0, statusFadeAlpha(0f).toDouble(), 1e-6)
        assertEquals(0.0, statusFadeAlpha(1f).toDouble(), 1e-6)
        // 系统给的进度会越界，必须夹住，不能算出负的不透明度
        assertEquals(1.0, statusFadeAlpha(-1f).toDouble(), 1e-6)
        assertEquals(0.0, statusFadeAlpha(2f).toDouble(), 1e-6)
        var previous = 1.0
        for (i in 0..1000) {
            val a = statusFadeAlpha(i / 1000f).toDouble()
            assertTrue("第 $i 档比上一档还亮：$a > $previous", a <= previous + 1e-9)
            previous = a
        }
        // 中段最陡、两端斜率为 0：换成折线也不会在首尾留下能被看出来的硬边
        val middle = (statusFadeAlpha(0.49f) - statusFadeAlpha(0.51f)).toDouble()
        val top = (statusFadeAlpha(0f) - statusFadeAlpha(0.02f)).toDouble()
        val bottom = (statusFadeAlpha(0.98f) - statusFadeAlpha(1f)).toDouble()
        assertTrue("顶端有折线：中段 $middle / 顶端 $top", middle > top * 4)
        assertTrue("底端有折线：中段 $middle / 底端 $bottom", middle > bottom * 4)
    }

    @Test fun statusFadePixelsNeverJumpSixLevelsBetweenAdjacentRows() {
        // 真机高度 194px。亮度是各通道的凸组合，所以相邻行亮度差的上限就是相邻行不透明度差。
        val height = 194
        var previous = statusFadeAlphaAt(0, height)
        assertEquals(255, previous)
        var worst = 0
        for (y in 1 until height) {
            val a = statusFadeAlphaAt(y, height)
            worst = maxOf(worst, kotlin.math.abs(a - previous))
            previous = a
        }
        assertEquals(0, previous)
        assertTrue("相邻行 $worst/255 超过 6", worst < 6)
        // 停靠点自己也要守住首尾，否则 Skia 那头给不出 255 与 0
        for (stops in intArrayOf(2, 9, 13, 33)) {
            val colors = statusFadeColors(0xFF0F1113.toInt(), stops)
            assertEquals(stops, colors.size)
            assertEquals(255, (colors.first() ushr 24) and 0xFF)
            assertEquals(0, (colors.last() ushr 24) and 0xFF)
        }
    }

    @Test fun longMessageIsSplitAcrossFramesInsteadOfChargedToTheFrameItEndsIn() {
        // 一条 200ms 的消息跨了 24 帧：每一帧只拿到它在这一帧里真正占用的那一段，
        // 加起来必须正好是 200ms，不能被「结束的那一帧」一次性吃掉。
        val acc = ThreadAccounting()
        val frame = 8_300_000L
        acc.start(0L)
        var sum = 0L
        for (i in 1..24) {
            val got = acc.drain(i * frame)
            assertTrue("单帧取走 $got ns，超过一个帧间隔", got <= frame)
            sum += got
        }
        // 消息在第 200ms 结束，最后这 0.8ms 由 finish 补上；合计必须正好等于消息时长
        sum += acc.finish(200_000_000L)
        assertEquals(200_000_000L, sum)
        // 取完必须归零，同一段不能给下一帧再算一次
        assertEquals(0L, acc.drain(900_000_000L))
        // 没有消息在跑时不能凭空产时间
        assertEquals(0L, acc.drain(1_000_000_000L))
    }

    @Test fun pageExpandsFromWhereTheFingerWasAndFallsBackToTheCenter() {
        // 点在哪一行就从哪一行长出来；夹在 15% 至 85% 之间，避免整页从屏幕边缘甩出来
        assertEquals(600f, expandPivot(600f, 1000))
        assertEquals(150f, expandPivot(0f, 1000))
        assertEquals(850f, expandPivot(1000f, 1000))
        assertEquals(150f, expandPivot(-40f, 1000))
        // 没有点按（通知冷启动、程序内跳转）就落回中心
        assertEquals(500f, expandPivot(Float.NaN, 1000))
        // 还没量出尺寸时不能拿 NaN 去当 pivot
        assertEquals(0f, expandPivot(320f, 0))
    }

    @Test fun backGestureShrinksThePageAndFollowsTheEdgeItCameFrom() {
        assertEquals(1.0, backScale(0f).toDouble(), 1e-6)
        assertEquals(0.94, backScale(1f).toDouble(), 1e-6)
        // 系统在取消时会给出越界进度，必须夹住，不能把页面缩成负的
        assertEquals(1.0, backScale(-2f).toDouble(), 1e-6)
        assertEquals(0.94, backScale(3f).toDouble(), 1e-6)
        assertEquals(0.0, backShift(0f, 1000, true).toDouble(), 1e-6)
        assertEquals(100.0, backShift(1f, 1000, true).toDouble(), 1e-6)
        assertEquals(-100.0, backShift(1f, 1000, false).toDouble(), 1e-6)
        assertEquals(50.0, backShift(0.5f, 1000, true).toDouble(), 1e-6)
        assertEquals(0.0, backShift(1f, 0, true).toDouble(), 1e-6)
    }

    @Test fun mainThreadMeterNamesTheWorstMessageAndKeepsPerFrameTotals() {
        MainThreadMeter.reset()
        MainThreadMeter.record(3.0, "")
        MainThreadMeter.record(41.0, "com.tencent.mobileqq.aio.msglist.b#what=113")
        MainThreadMeter.record(12.0, "")
        val into = DoubleArray(2)
        MainThreadMeter.take(into)
        // 浮点必须走带 delta 的三参重载：JUnit 4.13 起两参 assertEquals(double, double) 的源码
        // 就是一句 fail("Use assertEquals(expected, actual, delta)…")，跟数值对不对无关，写一次红一次。
        assertEquals(56.0, into[0], 1e-9)
        assertEquals(3.0, into[1], 1e-9)
        // 取走之后这一段必须清零，否则同一个窗口会被重复计一遍
        MainThreadMeter.take(into)
        assertEquals(0.0, into[0], 1e-9)
        assertEquals(0.0, into[1], 1e-9)
        val line = MainThreadMeter.summary()
        assertTrue("没算出总条数：\n$line", line.contains("共 3 条消息"))
        assertTrue("没点名最慢的那条：\n$line", line.contains("≥32ms 的 1 条"))
        assertTrue("没写出最慢耗时：\n$line", line.contains("最慢 41ms 在 com.tencent.mobileqq.aio.msglist.b#what=113"))
    }

    @Test fun culpritSkipsFrameworkFramesAndKeepsBusinessOnes() {
        val stack = arrayOf(
            StackTraceElement("android.view.Choreographer", "doFrame", null, 0),
            StackTraceElement("android.os.Handler", "dispatchMessage", null, 0),
            StackTraceElement("com.tencent.mobileqq.aio.msglist.b", "a", null, 0),
            StackTraceElement("com.tencent.qqnt.kernel.nativeinterface.MsgRecord", "doParse", null, 0),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, 0),
            StackTraceElement("sumicya.qself.PerfKt", "stallWatch", null, 0),
        )
        val where = culprit(stack)
        assertTrue("没跳过系统帧：\n$where", where.startsWith("com.tencent.mobileqq.aio.msglist.b#a"))
        assertTrue("没带上下一个业务帧：\n$where", where.contains("com.tencent.qqnt.kernel.nativeinterface.MsgRecord#doParse"))
        assertFalse("把系统帧写进去了：\n$where", where.contains("android."))
        assertEquals("(栈里全是系统帧)", culprit(arrayOf(StackTraceElement("android.view.View", "draw", null, 0))))
    }

    @Test fun arkCardIsRecognisedByItsClassName() {
        // 真机撞见过的三类：内容组件包名、ArkApp 视图、ArkView 包装。
        assertTrue(looksLikeArkCard("com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent"))
        assertTrue(looksLikeArkCard("com.tencent.mobileqq.ark.ArkAppRootLayout"))
        assertTrue(looksLikeArkCard("com.tencent.mobileqq.widget.ArkView"))
        // 普通消息的容器不能被当成卡片：误判会把正文气泡也套上裁剪。
        assertFalse(looksLikeArkCard("android.widget.LinearLayout"))
        assertFalse(looksLikeArkCard("com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent"))
        assertFalse(looksLikeArkCard("com.tencent.fastertextview.wrapper.a"))
    }

    @Test fun notificationExpandsTruncatedPreviewToFullBody() {
        val n = notificationText("张三", null, "  明天下午三点在图书馆门口见，带上那份合同和身份证复印件  ")
        assertEquals("张三", n?.title)
        assertEquals("明天下午三点在图书馆门口见，带上那份合同和身份证复印件", n?.body)
        // QQ 已经自己塞了大文本时优先用它，而不是被截断的小正文。
        assertEquals("完整长文", notificationText("张三", "完整长文", "完整长…")?.body)
    }

    @Test fun notificationLeavesExpandedOrEmptyNotificationsAlone() {
        // 大文本与正文一致，说明这条本来就是展开态，重建只是白跑一遍 builder。
        assertEquals(null, notificationText("张三", "同一段", "同一段"))
        // 没有正文可展开（只有标题，或什么都没有）：换样式没有意义。
        assertEquals(null, notificationText("张三", null, null))
        assertEquals(null, notificationText(null, "  ", "  "))
    }

    @Test fun notificationClampsOverlongTitleAndBody() {
        val n = notificationText("题".repeat(500), null, "文".repeat(2000))
        assertEquals(MODERN_TITLE_MAX, n?.title?.length)
        assertEquals(MODERN_BODY_MAX, n?.body?.length)
        assertEquals(120, MODERN_TITLE_MAX)
        assertEquals(400, MODERN_BODY_MAX)
    }

    @Test fun mergeDiagnosticReportsTrimsOverLongStreamingRecords() {
        val pad = "z".repeat(20000)
        val rep = "Qself Full QQ Diagnostic Report\n" +
            "--- [6] All QQ QLog Stream (1) ---\n" +
            "03:00:01.000 big $pad\n"
        val m = mergeDiagnosticReports(rep, rep)
        val line = m.lineSequence().first { it.startsWith("03:00:01.000 big") }
        assertTrue(line.length <= MAX_RECORD_CHARS + 64)
        assertTrue(line.contains("…[截断 "))
        // 截断后仍是同一条记录：再合并一次不产生增量
        assertEquals(0, reportDeltaRecords(m, mergeDiagnosticReports(m, rep)))
    }
}
