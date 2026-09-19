/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.content.Context
import sumicya.qself.config.SettingsBridge
import sumicya.qself.log.QLog
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Exports the host app's *real* class names, so NT-QQ support can be built
 * against what is actually on the device instead of guessed names.
 *
 * The module runs inside QQ, where `Application.getClassLoader()` sees the
 * host's classes — but listing them is not possible reflectively, and
 * obfuscated names cannot be enumerated from memory cheaply. Reading the
 * installed APK works everywhere instead: `su cp` the base.apk out, then
 * parse each DEX image's class-defs table. That needs no framework hooking,
 * no root-only APIs beyond the copy, and gives exact, version-specific names.
 *
 * The result is filtered to `com.tencent` classes whose names look relevant
 * and written next to the app plus `/sdcard/qself-classes.txt`, so `grep`
 * on the device answers "what is the NT class for X" in seconds.
 */
object ClassDump {

    private const val TAG = "ClassDump"
    private const val OUT_NAME = "qself-classes.txt"
    private const val MAX_LINES = 40_000
    private const val COPY_TIMEOUT_SECONDS = 120L

    /** Class-name fragments worth keeping (case-insensitive). */
    private val KEYWORDS = listOf(
        "nt", "kernel", "chat", "msg", "message", "aio", "troop", "friend",
        "contact", "avatar", "redpacket", "wallet", "qzone", "qwallet",
        "upgrade", "update", "rfix", "hotpatch", "patch", "crash", "report",
        "statistic", "setting", "config", "startup", "splash", "camera",
        "sign", "signin", "tianshu", "qcircle", "emotion", "flash", "gag",
        "gray", "tip", "revoke", "recall",
    )

    /**
     * Copies the host APK with root, parses its class names and writes the
     * filtered list. **Blocking** (runs `su`), so call it off the main thread.
     */
    fun dump(context: Context, hostPackage: String): String {
        val dir = File(context.cacheDir, "classdump").apply { mkdirs() }
        val apk = File(dir, "base.apk")
        apk.delete()

        val copied = copyApk(hostPackage, apk)
        if (!copied) {
            return "导出失败：无法用 su 复制 $hostPackage 的 APK（root 授予了吗？）"
        }

        val classes = LinkedHashSet<String>()
        var dexCount = 0
        try {
            ZipFile(apk).use { zip ->
                val dexes = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.matches(Regex("classes\\d*\\.dex")) }
                    .sorted()
                    .toList()
                for (name in dexes) {
                    dexCount++
                    val bytes = zip.getInputStream(zip.getEntry(name)).use(InputStream::readBytes)
                    collectClasses(bytes, classes)
                }
            }
        } catch (t: Throwable) {
            QLog.e(TAG, "failed to parse the host APK", t)
            return "导出失败：解析 DEX 出错（${t.javaClass.simpleName}）"
        } finally {
            apk.delete()
        }

        val matches = classes.filter { descriptor ->
            descriptor.startsWith("Lcom/tencent/") &&
                KEYWORDS.any { descriptor.contains(it, ignoreCase = true) }
        }.map { it.substring(1, it.length - 1).replace('/', '.') }.sorted()

        val truncated = matches.size > MAX_LINES
        val body = StringBuilder()
        body.append("# Qself class dump — $hostPackage\n")
        body.append("# dex images scanned: $dexCount\n")
        body.append("# classes with relevant names: ${matches.size}")
        if (truncated) body.append(" (truncated to $MAX_LINES)")
        body.append("\n")
        for (line in matches.take(MAX_LINES)) body.append(line).append('\n')

        val local = File(context.getExternalFilesDir(null) ?: context.filesDir, OUT_NAME)
        local.writeText(body.toString())
        val published = publishToSdcard(local)
        QLog.i(TAG, "dumped ${classes.size} classes, ${matches.size} relevant -> $local")

        return "已导出 ${matches.size} 个相关类名（共扫描 $dexCount 个 dex）\n" +
            if (published) "文件：/sdcard/$OUT_NAME" else "文件：${local.absolutePath}"
    }

    /** Finds the host's APK path (`pm path`) and copies it out with root. */
    private fun copyApk(hostPackage: String, destination: File): Boolean {
        val command = "pm path $hostPackage | head -1 | sed 's/^package://' | " +
            "xargs -r cp '$destination'"
        return try {
            val process = ProcessBuilder(
                "su", "0", "-c", "sh -c ${quote(command)}",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                QLog.w(TAG, "su copy timed out")
                return false
            }
            val ok = destination.exists() && destination.length() > 0
            if (!ok) QLog.w(TAG, "su copy produced no file")
            ok
        } catch (t: Throwable) {
            QLog.e(TAG, "su copy failed", t)
            false
        }
    }

    private fun publishToSdcard(local: File): Boolean = try {
        val target = "/sdcard/$OUT_NAME"
        val process = ProcessBuilder(
            "su", "0", "-c", "sh -c ${quote("cp '${local.absolutePath}' '$target'")}",
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (t: Throwable) {
        false
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ---- DEX parsing ------------------------------------------------------

    /**
     * Adds every `class_defs` descriptor of one DEX image to [out]. Only the
     * header, the string-id table and the class-defs table are touched, so a
     * 40 MB dex costs a few MB of transient memory.
     */
    private fun collectClasses(bytes: ByteArray, out: MutableSet<String>) {
        if (bytes.size < 0x70) return
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
            bytes[2] != 'x'.code.toByte() || bytes[3] != 0x0A.toByte()
        ) {
            return
        }
        val stringIdsSize = u32(bytes, 0x38)
        val stringIdsOff = u32(bytes, 0x3C)
        val classDefsSize = u32(bytes, 0x60)
        val classDefsOff = u32(bytes, 0x64)
        for (i in 0 until classDefsSize) {
            val classIdx = u32(bytes, classDefsOff + i * 32)
            if (classIdx < 0 || classIdx >= stringIdsSize) continue
            val dataOff = u32(bytes, stringIdsOff + classIdx * 4)
            out.add(readString(bytes, dataOff) ?: continue)
        }
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Reads a MUTF-8 string: uleb128 length, then the classic 1/2/3-byte
     * sequences terminated by NUL. Surrogate halves decode as-is, which is
     * exactly what a Java String holds.
     */
    private fun readString(bytes: ByteArray, offset: Int): String? {
        if (offset < 0 || offset >= bytes.size) return null
        var p = offset
        // uleb128 (the length itself is not needed: the NUL terminates it)
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
        return out.toString().takeIf { it.startsWith("L") && it.endsWith(";") }
    }
}
