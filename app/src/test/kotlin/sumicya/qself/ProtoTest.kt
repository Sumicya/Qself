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
        assertEquals(emptyList<String>(), recall(push(528, 138))) // 解不出细节也照样吞，只是标不了
        // from / to 两个 uid 各记一条：别的设备上自己撤回时，会话的 peerUid 是对面那条
        val keys = recall(push(528, 138, field(1, field(1, "u_abc".toByteArray()) + field(2, "u_me".toByteArray()) + field(3, 4567L))))
        assertEquals(listOf("1:u_abc:4567", "1:u_me:4567"), keys)
    }

    @Test fun ordinaryMessage() = assertTrue(recall(push(166, 11)) == null)

    /** 群撤回和别的群通知共用 732/17，只有 NotifyMsgBody 字段 1 = 7 才是撤回，别的一律不能吞。 */
    @Test fun groupRecallNeedsOpType7() {
        val body = field(1, 7L) + field(4, 123L) + field(11, field(1, "u_op".toByteArray()) + field(3, field(1, 88L)) + field(3, field(1, 89L)))
        assertEquals(listOf("2:123:88", "2:123:89"), recall(push(732, 17, ByteArray(7) + body)))
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
