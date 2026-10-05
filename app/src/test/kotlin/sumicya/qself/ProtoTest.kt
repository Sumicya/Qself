// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防撤回那几十行 protobuf 判断的唯一一份可跑检查。 */
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

    @Test fun mergeGroupsOnlyAdjacentOrdinaryMessagesById() {
        val first = RunKey(1, "u", 1_000, 166)
        assertTrue(mergeRun(first, RunKey(2, "u", 1_300, 166))) // 五分钟边界包含
        assertTrue(mergeRun(first, RunKey(3, "u", 700, 166))) // 列表时间方向不影响判定
        assertFalse(mergeRun(first, RunKey(4, "u", 1_301, 166)))
        assertFalse(mergeRun(first, RunKey(5, "other", 1_100, 166)))
        assertFalse(mergeRun(first, RunKey(1, "u", 1_100, 166))) // 重复 ID 不自合并
        assertFalse(mergeRun(first, RunKey(6, "u", 1_100, 5))) // 灰字不并
        assertFalse(mergeRun(first, RunKey(7, "u", 1_100, 29))) // 开场白不并
        assertFalse(mergeRun(first, RunKey(8, "u", Long.MAX_VALUE, 166))) // 溢出差值不误判
        assertFalse(mergeRun(RunKey(9, "u", Long.MIN_VALUE, 166), RunKey(10, "u", Long.MAX_VALUE, 166)))
        assertFalse(mergeRun(null, first))
    }

    @Test fun garbageIsNotSilentlyARecall() {
        assertTrue(recall(byteArrayOf()) == null)
        assertTrue(recall(field(1, field(2, field(1, 528L)))) == null)
    }
}
