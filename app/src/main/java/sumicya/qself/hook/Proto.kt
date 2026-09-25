// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import java.io.ByteArrayOutputStream

/** Just enough protobuf wire-format to recognise recall pushes. */
object Proto {
    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val x = b[p++].toInt()
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 63) error("varint")
            }
        }

        /** Returns (field, wire, start, endExclusive) of the next field's payload. */
        fun next(): IntArray {
            val key = varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            val start: Int
            when (wire) {
                0 -> { start = p; varint() }
                1 -> { start = p; p += 8 }
                2 -> { val len = varint().toInt(); start = p; p += len }
                5 -> { start = p; p += 4 }
                else -> error("wire $wire")
            }
            if (p > end) error("truncated")
            return intArrayOf(field, wire, start, p)
        }
    }

    private fun sub(b: ByteArray, from: Int, to: Int, field: Int): IntArray? {
        val r = Reader(b, from, to)
        while (r.more()) {
            val f = r.next()
            if (f[0] == field && f[1] == 2) return f
        }
        return null
    }

    private fun varintField(b: ByteArray, from: Int, to: Int, field: Int): Long? {
        val r = Reader(b, from, to)
        while (r.more()) {
            val f = r.next()
            if (f[0] == field && f[1] == 0) return Reader(b, f[2], f[3]).varint()
        }
        return null
    }

    /** MsgPush{1: Message{2: ContentHead{1: type, 2: subType}, 3: Body{2: content}}} */
    fun isRecallPush(push: ByteArray): Boolean = runCatching {
        val msg = sub(push, 0, push.size, 1) ?: return false
        val head = sub(push, msg[2], msg[3], 2) ?: return false
        val type = varintField(push, head[2], head[3], 1)
        val subType = varintField(push, head[2], head[3], 2)
        when {
            type == 528L && subType == 138L -> true
            type == 732L && subType == 17L -> {
                // Group notices share 732/17; the recall one has op_type 7 after a 7-byte prefix.
                val body = sub(push, msg[2], msg[3], 3) ?: return false
                val content = sub(push, body[2], body[3], 2) ?: return false
                content[3] - content[2] > 7 && varintField(push, content[2] + 7, content[3], 1) == 7L
            }
            else -> false
        }
    }.getOrDefault(false)

    /** Copy of [data] without top-level [field]; the same instance if nothing was removed. */
    fun stripField(data: ByteArray, field: Int): ByteArray = runCatching {
        val out = ByteArrayOutputStream(data.size)
        val r = Reader(data)
        var removed = false
        while (r.more()) {
            val keyStart = r.p
            val f = r.next()
            if (f[0] == field) removed = true else out.write(data, keyStart, r.p - keyStart)
        }
        if (removed) out.toByteArray() else data
    }.getOrDefault(data)
}
