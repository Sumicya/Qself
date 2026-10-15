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
    @Test fun exactUnreadBadgeCountOnlyExpandsOverflowLabel() {
        assertEquals("100", exactBadgeText("99+", 100))
        assertEquals("12345", exactBadgeText("99+", 12345))
        assertEquals("1234567", exactBadgeText("99+", 1234567))
        assertTrue(exactBadgeText("99+", 99) == null)
        assertTrue(exactBadgeText("100", 12345) == null)
        assertTrue(exactBadgeText(null, 12345) == null)
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

    @Test fun ownMessagesKeepOneSenderWhenUidIsEmpty() {
        assertEquals(messageRunSender("u_old", 1, true), messageRunSender("", null, true))
        assertEquals(messageRunSender("", 123, false), messageRunSender("uid", 123, false))
        assertFalse(messageRunSender("u_a", null, false) == messageRunSender("u_b", null, false))
        assertTrue(messageRunSender("", 0, false) == null)
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

    @Test fun compactSpacingIsBoundedAndRestoresOriginal() {
        assertEquals(16, compactMessageTop(20, true, 1f))
        assertEquals(0, compactMessageTop(2, true, 1f))
        assertEquals(0, compactMessageTop(0, true, 3f))
        assertEquals(-3, compactMessageTop(-3, true, 1f))
        assertEquals(20, compactMessageTop(20, false, 3f))
        assertEquals(12, compactMessageTop(20, true, 2f))
    }

    @Test fun compactMenuFitsAvailableWindowAndImeSpace() {
        assertEquals(360 to 440, menuSize(1080, 1920, 1f))
        assertEquals(328 to 372, menuSize(360, 600, 1f))
        assertEquals(328 to 124, menuSize(360, 200, 1f))
        assertEquals(0 to 0, menuSize(0, 0, 1f))
        assertEquals(984 to 1320, menuSize(1080, 2400, 3f))
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
