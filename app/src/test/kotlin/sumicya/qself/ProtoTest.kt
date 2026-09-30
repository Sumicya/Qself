// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TestMsgRecord(val senderUid: String, val msgType: Int, val msgTime: Long)
class TestMsgItem(private val value: TestMsgRecord) { fun getMsgRecord() = value }

/** 防撤回 protobuf 与连发分组边界的可跑检查。 */
class ProtoTest {
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

    @Test fun groupOnlyAdjacentSenderWithinFiveMinutes() {
        val first = TestMsgItem(TestMsgRecord("u_a", 2, 1000))
        assertTrue(follows(first, TestMsgItem(TestMsgRecord("u_a", 2, 1300))))
        assertFalse(follows(first, TestMsgItem(TestMsgRecord("u_a", 2, 1301))))
        assertFalse(follows(first, TestMsgItem(TestMsgRecord("u_b", 2, 1200))))
        assertFalse(follows(first, TestMsgItem(TestMsgRecord("u_a", 5, 1200))))
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

    @Test fun truncatedFieldsCannotProduceFakeRecalls() {
        assertTrue(runCatching { byteArrayOf(0x0a, 0x05, 0x01).pb() }.isFailure)
        assertTrue(runCatching { byteArrayOf(0x80.toByte()).pb() }.isFailure)
        assertTrue(runCatching { byteArrayOf(0x09, 1).pb() }.isFailure)
        assertFalse(runCatching { stripSyncRecall(field(3, 1L)) }.isFailure)
    }
}
