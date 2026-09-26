// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import org.junit.Assert.assertArrayEquals
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

    @Test fun c2cRecall() = assertTrue(isRecall(push(528, 138)))

    @Test fun ordinaryMessage() = assertFalse(isRecall(push(166, 11)))

    @Test fun groupRecallNeedsOpType7() {
        assertTrue(isRecall(push(732, 17, ByteArray(7) + field(1, 7L) + field(4, 123L))))
        assertFalse(isRecall(push(732, 17, ByteArray(7) + field(1, 6L))))
        assertFalse(isRecall(push(732, 17, ByteArray(3))))
    }

    @Test fun stripDropsOnlyField8() {
        val keep = field(3, 1L) + field(7, byteArrayOf(9, 9)) + field(10, 1L)
        assertArrayEquals(keep, stripSyncRecall(field(3, 1L) + field(8, field(4, byteArrayOf(1, 2))) + field(7, byteArrayOf(9, 9)) + field(10, 1L)))
        assertSame(keep, stripSyncRecall(keep))
    }

    @Test fun garbageIsNotSilentlyARecall() {
        assertFalse(isRecall(byteArrayOf()))
        assertFalse(isRecall(field(1, field(2, field(1, 528L)))))
    }
}
