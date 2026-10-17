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

    @Test fun floatingAvatarUsesContentCentersNotRowBottom() {
        // 一行短文本即使比头像矮，也严格同中心；高图片同理。
        assertEquals(10f, floatingAvatarTop(30f, 30f, 0f, 500f, 40f, 4f))
        assertEquals(180f, floatingAvatarTop(200f, 200f, 0f, 500f, 40f, 4f))
        // 连发最后一条中心对齐，长组受可见窗口底部约束。
        assertEquals(100f, floatingAvatarTop(30f, 120f, 0f, 500f, 40f, 4f))
        assertEquals(56f, floatingAvatarTop(30f, 120f, 0f, 100f, 40f, 4f))
        assertEquals(446f, floatingAvatarTop(-300f, 900f, 0f, 500f, 50f, 4f))
        assertTrue(floatingAvatarTop(-100f, -30f, 0f, 500f, 50f, 4f) == null)
        assertTrue(floatingAvatarTop(530f, 600f, 0f, 500f, 50f, 4f) == null)
        assertTrue(floatingAvatarTop(Float.POSITIVE_INFINITY, 20f, 0f, 500f, 40f, 4f) == null)
    }

    @Test fun avatarTracksFractionalScrollWithoutRounding() {
        for (step in 0..800) {
            val offset = step * 0.125f
            val y = floatingAvatarTop(180f - offset, 300f - offset, 0f, 500f, 40f, 4f)!!
            assertTrue(kotlin.math.abs(y - (280f - offset)) < 0.0001f)
        }
    }

    @Test fun avatarClampCrossesViewportBoundaryContinuously() {
        var previous: Float? = null
        for (step in 0..400) {
            val last = 440f + step * 0.25f
            val y = floatingAvatarTop(100f, last, 0f, 500f, 40f, 4f)!!
            assertEquals(minOf(last, 476f) - 20f, y)
            previous?.let { assertTrue(y >= it && y - it <= 0.25f) }
            previous = y
        }
    }

    @Test fun avatarReverseScrollDoesNotDependOnPreviousFrame() {
        val forward = (0..400).map { step ->
            floatingAvatarTop(100f - step * 0.25f, 600f - step * 0.25f, 0f, 500f, 40f, 4f)
        }
        val reverse = (400 downTo 0).map { step ->
            floatingAvatarTop(100f - step * 0.25f, 600f - step * 0.25f, 0f, 500f, 40f, 4f)
        }
        assertEquals(forward, reverse.reversed())
    }

    @Test fun avatarUsesCurrentViewportImmediatelyAfterKeyboardResize() {
        assertEquals(456f, floatingAvatarTop(-300f, 900f, 0f, 500f, 40f, 4f))
        assertEquals(256f, floatingAvatarTop(-300f, 900f, 0f, 300f, 40f, 4f))
        assertEquals(456f, floatingAvatarTop(-300f, 900f, 0f, 500f, 40f, 4f))
    }

    @Test fun avatarRejectsInvalidViewportAndNonFiniteGeometry() {
        assertTrue(floatingAvatarTop(10f, 20f, 0f, 0f, 40f, 4f) == null)
        assertTrue(floatingAvatarTop(10f, 20f, 50f, 0f, 40f, 4f) == null)
        assertTrue(floatingAvatarTop(10f, 20f, 0f, Float.NaN, 40f, 4f) == null)
        assertTrue(floatingAvatarTop(10f, 20f, 0f, 500f, Float.POSITIVE_INFINITY, 4f) == null)
        assertTrue(floatingAvatarTop(10f, 20f, 0f, 500f, 40f, -1f) == null)
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

    @Test fun onlyAttachedMiddleRowIsNotASingleMessageGroup() {
        assertEquals(456f, floatingRunAvatarTop(200f, 200f, 0f, 500f, 40f, 4f, false, false))
        assertEquals(180f, floatingRunAvatarTop(200f, 200f, 0f, 500f, 40f, 4f, true, true))
        assertEquals(180f, floatingRunAvatarTop(200f, 200f, 0f, 500f, 40f, 4f, false, true))
        assertEquals(456f, floatingRunAvatarTop(200f, 200f, 0f, 500f, 40f, 4f, true, false))
    }

    @Test fun detachingIntermediateRowsDoesNotMoveTheFloatingAvatar() {
        for (i in 0..300) {
            assertEquals(456f, floatingRunAvatarTop(i.toFloat(), 400f + i, 0f, 500f, 40f, 4f, false, false))
        }
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
        assertEquals(2, compactMessageTop(20, true, 1f))
        assertEquals(2, compactMessageTop(2, true, 1f))
        assertEquals(0, compactMessageTop(0, true, 3f))
        assertEquals(-3, compactMessageTop(-3, true, 1f))
        assertEquals(20, compactMessageTop(20, false, 3f))
        assertEquals(4, compactMessageTop(20, true, 2f))
    }

    @Test fun compactMenuFitsAvailableWindowAndImeSpace() {
        assertEquals(360 to 520, menuSize(1080, 1920, 1f))
        assertEquals(328 to 452, menuSize(360, 600, 1f))
        assertEquals(328 to 168, menuSize(360, 200, 1f))
        assertEquals(0 to 0, menuSize(0, 0, 1f))
        assertEquals(984 to 1560, menuSize(1080, 2400, 3f))
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
}
