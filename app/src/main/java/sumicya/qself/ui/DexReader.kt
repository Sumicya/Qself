/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import java.io.ByteArrayOutputStream

/**
 * Minimal DEX reader: enough of the format to answer "which classes exist"
 * and "which methods/fields does this class have, with which signatures".
 *
 * That second question is what NT support is built on — a class name alone
 * cannot be hooked, the method name and parameter types are needed, and on
 * this device nothing but the APK on disk knows them (see docs/NT-ADAPTATION.md).
 *
 * Layout used (all offsets little-endian):
 *
 * ```
 * header        string_ids 0x38/0x3C   type_ids 0x40/0x44   proto_ids 0x48/0x4C
 *               field_ids  0x50/0x54   method_ids 0x58/0x5C  class_defs 0x60/0x64
 * class_def     32 bytes  [0]=class_idx [24]=class_data_off
 * class_data    uleb static_fields, instance_fields, direct_methods, virtual_methods
 * method_id     8 bytes   class_idx:u16 proto_idx:u16 name_idx:u32
 * field_id      8 bytes   class_idx:u16 type_idx:u16  name_idx:u32
 * proto_id      12 bytes  shorty_idx:u32 return_type_idx:u32 parameters_off:u32
 * type_list     u32 size, u16[] type_idx
 * ```
 *
 * Two traps, both of which produced silently wrong names until they were
 * caught by cross-checking against androguard on real dex files:
 *
 *  - `class_idx` and every other `*_idx` in the tables above indexes **type_ids**,
 *    never `string_ids` directly; going straight to the string table yields an
 *    unrelated string (a type at position N and the string at position N differ).
 *  - the four arrays in `class_data_item` are each diff-coded **on their own**.
 *    The first entry of every array is absolute. Carrying the index across a
 *    group boundary walks out of the table: `field_ids` is sorted per class by
 *    name, so static and instance fields interleave and a negative diff would
 *    be required to express the second group's first entry.
 */
class DexReader(private val bytes: ByteArray) {

    private val stringIdsSize = u32(0x38)
    private val stringIdsOff = u32(0x3C)
    private val typeIdsSize = u32(0x40)
    private val typeIdsOff = u32(0x44)
    private val protoIdsSize = u32(0x48)
    private val protoIdsOff = u32(0x4C)
    private val fieldIdsSize = u32(0x50)
    private val fieldIdsOff = u32(0x54)
    private val methodIdsSize = u32(0x58)
    private val methodIdsOff = u32(0x5C)
    private val classDefsSize = u32(0x60)
    private val classDefsOff = u32(0x64)

    /** True when the magic says "dex" and the tables are inside the file. */
    val valid: Boolean
        get() = bytes.size > 0x70 &&
            bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == 0x0A.toByte()

    /** Every class descriptor defined here (`Lx/y/Z;`). */
    fun classDescriptors(): List<String> {
        val out = ArrayList<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            typeDescriptor(u32(classDefsOff + i * 32))?.let { out.add(it) }
        }
        return out
    }

    /** Class descriptors starting with any of [prefixes] (`Lx/y/` form). */
    fun matchingDescriptors(prefixes: List<String>): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until classDefsSize) {
            val descriptor = typeDescriptor(u32(classDefsOff + i * 32)) ?: continue
            if (prefixes.any { descriptor.startsWith(it) }) out.add(descriptor)
        }
        return out
    }

    /** Methods and fields of one class, or null when it is not defined here. */
    fun classDetail(descriptor: String): DexClass? {
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            if (typeDescriptor(u32(base)) != descriptor) continue
            val classDataOff = u32(base + 24)
            if (classDataOff <= 0 || classDataOff >= bytes.size) return DexClass(descriptor, emptyList(), emptyList())
            return parseClassData(descriptor, classDataOff)
        }
        return null
    }

    // ---- class_data_item --------------------------------------------------

    private fun parseClassData(descriptor: String, offset: Int): DexClass {
        val p = IntRef(offset)
        val staticFields = uleb(p)
        val instanceFields = uleb(p)
        val directMethods = uleb(p)
        val virtualMethods = uleb(p)

        val methods = ArrayList<DexMember>(directMethods + virtualMethods)
        val fields = ArrayList<DexMember>(staticFields + instanceFields)

        // field entries: idx_diff, access_flags
        fun readFields(count: Int) {
            var index = 0
            repeat(count) { i ->
                val diff = uleb(p)
                val flags = uleb(p)
                index = if (i == 0) diff else index + diff
                fieldName(index)?.let { fields.add(DexMember(it, flags, "")) }
            }
        }
        // method entries: idx_diff, access_flags, code_off
        fun readMethods(count: Int) {
            var index = 0
            repeat(count) { i ->
                val diff = uleb(p)
                val flags = uleb(p)
                uleb(p) // code_off — unused
                index = if (i == 0) diff else index + diff
                if (index in 0 until methodIdsSize) {
                    methodSignature(index)?.let { methods.add(DexMember(it.first, flags, it.second)) }
                }
            }
        }
        readFields(staticFields)
        readFields(instanceFields)
        readMethods(directMethods)
        readMethods(virtualMethods)
        return DexClass(descriptor, methods, fields)
    }

    private fun fieldName(fieldIdx: Int): String? {
        if (fieldIdx < 0 || fieldIdx >= fieldIdsSize) return null
        val base = fieldIdsOff + fieldIdx * 8
        val nameIdx = u32(base + 4)
        if (nameIdx < 0 || nameIdx >= stringIdsSize) return null
        return stringAt(stringIdsOff + nameIdx * 4)
    }

    /** `name(params): return` for one method_id, or null when unreadable. */
    private fun methodSignature(methodIdx: Int): Pair<String, String>? {
        val base = methodIdsOff + methodIdx * 8
        val protoIdx = u16(base + 2)
        val nameIdx = u32(base + 4)
        if (nameIdx < 0 || nameIdx >= stringIdsSize) return null
        if (protoIdx < 0 || protoIdx >= protoIdsSize) return null
        val name = stringAt(stringIdsOff + nameIdx * 4) ?: return null

        val protoBase = protoIdsOff + protoIdx * 12
        val returnTypeIdx = u32(protoBase + 4)
        val parametersOff = u32(protoBase + 8)
        val returnType = typeName(returnTypeIdx) ?: "?"
        val params = if (parametersOff <= 0 || parametersOff >= bytes.size) {
            emptyList()
        } else {
            val size = u32(parametersOff)
            (0 until size).mapNotNull { i -> typeName(u16(parametersOff + 4 + i * 2)) }
        }
        return name to "(${params.joinToString(", ")}): $returnType"
    }

    // ---- primitives -------------------------------------------------------

    /** Raw `Lx/y/Z;` / `[I` descriptor for a type index, or null when unreadable. */
    private fun typeDescriptor(typeIdx: Int): String? {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) return null
        val descriptorIdx = u32(typeIdsOff + typeIdx * 4)
        if (descriptorIdx < 0 || descriptorIdx >= stringIdsSize) return null
        return stringAt(stringIdsOff + descriptorIdx * 4)
    }

    private fun typeName(typeIdx: Int): String? = typeDescriptor(typeIdx)?.let { javaName(it) }

    private fun javaName(descriptor: String): String {
        var array = 0
        var i = 0
        while (i < descriptor.length && descriptor[i] == '[') {
            array++
            i++
        }
        val body = when (val c = descriptor.getOrNull(i)) {
            'L' -> descriptor.substring(i + 1, descriptor.length - 1).replace('/', '.')
            'V' -> "void"
            'Z' -> "boolean"
            'B' -> "byte"
            'S' -> "short"
            'C' -> "char"
            'I' -> "int"
            'J' -> "long"
            'F' -> "float"
            'D' -> "double"
            else -> "?${c ?: ""}"
        }
        return body + "[]".repeat(array)
    }

    private fun u32(offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Mutable int cursor, because Kotlin has no by-ref primitive. */
    class IntRef(var value: Int)

    /** Unsigned LEB128, advancing the cursor. */
    private fun uleb(p: IntRef): Int {
        var result = 0
        var shift = 0
        while (true) {
            val index = p.value
            if (index < 0 || index >= bytes.size || shift > 28) return result
            val byte = bytes[index].toInt() and 0xFF
            p.value = index + 1
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    /** Reads a MUTF-8 string table entry; returns only type descriptors. */
    private fun stringAt(offset: Int): String? {
        if (offset < 0 || offset >= bytes.size) return null
        var p = offset
        var guard = 0
        while (p < bytes.size && (bytes[p].toInt() and 0x80) != 0 && guard < 5) {
            p++
            guard++
        }
        p++
        if (p >= bytes.size) return null
        val out = StringBuilder(48)
        while (p < bytes.size && bytes[p].toInt() != 0) {
            val b = bytes[p].toInt() and 0xFF
            when {
                b < 0x80 -> {
                    out.append(b.toChar())
                    p++
                }
                b and 0xE0 == 0xC0 -> {
                    if (p + 1 >= bytes.size) return null
                    out.append((((b and 0x1F) shl 6) or (bytes[p + 1].toInt() and 0x3F)).toChar())
                    p += 2
                }
                else -> {
                    if (p + 2 >= bytes.size) return null
                    out.append(
                        (
                            ((b and 0x0F) shl 12) or
                                ((bytes[p + 1].toInt() and 0x3F) shl 6) or
                                (bytes[p + 2].toInt() and 0x3F)
                            ).toChar(),
                    )
                    p += 3
                }
            }
        }
        return out.toString()
    }

    // ---- access flags -----------------------------------------------------

    companion object {
        fun methodFlags(flags: Int): String = buildList {
            if (flags and 0x1 != 0) add("public")
            if (flags and 0x2 != 0) add("private")
            if (flags and 0x4 != 0) add("protected")
            if (flags and 0x8 != 0) add("static")
            if (flags and 0x10 != 0) add("final")
            if (flags and 0x20 != 0) add("synchronized")
            if (flags and 0x100 != 0) add("native")
            if (flags and 0x400 != 0) add("abstract")
            if (flags and 0x1000 != 0) add("synthetic")
            if (flags and 0x20000 != 0) add("constructor")
        }.joinToString(" ")

        fun fieldFlags(flags: Int): String = buildList {
            if (flags and 0x1 != 0) add("public")
            if (flags and 0x2 != 0) add("private")
            if (flags and 0x4 != 0) add("protected")
            if (flags and 0x8 != 0) add("static")
            if (flags and 0x10 != 0) add("final")
            if (flags and 0x40 != 0) add("volatile")
            if (flags and 0x80 != 0) add("transient")
        }.joinToString(" ")
    }
}

/** One method or field: [name], decoded [flags] text and (methods) [signature]. */
data class DexMember(val name: String, val flags: Int, val signature: String)

/** A class with the members Qself would need to hook. */
data class DexClass(val descriptor: String, val methods: List<DexMember>, val fields: List<DexMember>)
