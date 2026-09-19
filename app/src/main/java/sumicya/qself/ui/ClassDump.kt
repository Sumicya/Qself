/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.content.Context
import sumicya.qself.log.QLog
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Dumps the host APK's classes and their **method signatures**.
 *
 * Class names alone cannot be hooked — a hook needs the method name and its
 * parameter types. NT QQ (9.x) moved everything under `com.tencent.qqnt.*` and
 * `com.tencent.mobileqq.qfix.*`, so the pre-NT feature set has to be rebuilt
 * against real signatures (docs/NT-ADAPTATION.md). Those signatures only exist
 * in the APK on the device, hence this tool: `su cp` the base.apk out, then
 * parse class_defs / class_data / method_ids with [DexReader].
 *
 * Two outputs, both written next to the module and copied to `/sdcard`:
 *
 * - `qself-classes.txt` — every `com.tencent.*` class whose name looks relevant;
 * - `qself-methods.txt` — for classes matching the given prefixes, every
 *   method (`flags name(params): return`) and field.
 */
object ClassDump {

    private const val TAG = "ClassDump"
    private const val OUT_CLASSES = "qself-classes.txt"
    private const val OUT_METHODS = "qself-methods.txt"
    private const val MAX_LINES = 40_000
    private const val MAX_METHODS_PER_CLASS = 80
    private const val MAX_DEX_BYTES = 120L * 1024 * 1024
    private const val COPY_TIMEOUT_SECONDS = 180L

    /** Prefixes that matter for the pre-NT → NT port, prefilled in the UI. */
    const val DEFAULT_PREFIXES =
        "com.tencent.mobileqq.qfix.,com.tencent.feedback.eup.," +
            "com.tencent.bugly.crashreport.,com.tencent.qqnt.startup."

    /**
     * `com.tencent.qqnt.` and `Lcom/tencent/qqnt/` both become `Lcom/tencent/qqnt/`.
     * Accepting both forms matters: the UI pre-fills the dotted form while
     * anything copied out of a dex dump is already in slash form.
     */
    fun normalizePrefix(text: String): String {
        val trimmed = text.trim().trimEnd(',')
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.contains('/') -> if (trimmed.startsWith("L")) trimmed else "L$trimmed"
            else -> "L" + trimmed.replace('.', '/')
        }
    }

    /** Class-name fragments worth keeping (case-insensitive). */
    private val KEYWORDS = listOf(
        "nt", "kernel", "chat", "msg", "message", "aio", "troop", "friend",
        "contact", "avatar", "redpacket", "wallet", "qzone", "qwallet",
        "upgrade", "update", "rfix", "qfix", "hotpatch", "patch", "crash",
        "report", "statistic", "setting", "config", "startup", "splash",
        "camera", "sign", "signin", "tianshu", "qcircle", "emotion", "flash",
        "gag", "gray", "tip", "revoke", "recall",
    )

    /**
     * Copies the host APK with root, then writes both dumps. **Blocking**
     * (runs `su` and parses up to ~400 MB of APK), so call it off the main
     * thread. Returns a human-readable summary for the dialog.
     */
    fun dump(context: Context, hostPackage: String, prefixes: List<String>): String {
        val wanted = prefixes.map(::normalizePrefix).filter { it.isNotEmpty() }
        val dir = java.io.File(context.cacheDir, "classdump").apply { mkdirs() }
        val apk = java.io.File(dir, "base.apk")
        apk.delete()
        if (!copyApk(hostPackage, apk)) {
            return "导出失败：无法用 su 复制 $hostPackage 的 APK（root 授予了吗？）"
        }

        val allClasses = LinkedHashSet<String>()
        val details = ArrayList<DexClass>()
        var dexCount = 0
        var skippedDexes = 0
        try {
            ZipFile(apk).use { zip ->
                val dexes = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.matches(Regex("classes\\d*\\.dex")) }
                    .sorted()
                    .toList()
                for (name in dexes) {
                    val entry = zip.getEntry(name) ?: continue
                    if (entry.size > MAX_DEX_BYTES) {
                        QLog.w(TAG, "skipping $name (${entry.size} bytes, too large to parse safely)")
                        skippedDexes++
                        continue
                    }
                    try {
                        val bytes = zip.getInputStream(entry).use(InputStream::readBytes)
                        val reader = DexReader(bytes)
                        if (!reader.valid) {
                            skippedDexes++
                            continue
                        }
                        allClasses.addAll(reader.classDescriptors())
                        for (descriptor in reader.matchingDescriptors(wanted)) {
                            reader.classDetail(descriptor)?.let { details.add(it) }
                        }
                        dexCount++
                    } catch (t: Throwable) {
                        // A single odd dex image must not lose the whole dump.
                        skippedDexes++
                        QLog.w(TAG, "skipping $name: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            QLog.e(TAG, "failed to parse the host APK", t)
            return "导出失败：解析 DEX 出错（${t.javaClass.simpleName}: ${t.message}）"
        } finally {
            apk.delete()
        }

        val classesText = buildClassList(hostPackage, dexCount, allClasses)
        val methodsText = buildMethodList(hostPackage, wanted, details)

        val classesFile = write(context, OUT_CLASSES, classesText)
        val methodsFile = write(context, OUT_METHODS, methodsText)
        val published = publish(listOf(classesFile to "/sdcard/$OUT_CLASSES", methodsFile to "/sdcard/$OUT_METHODS"))

        return buildString {
            append("类名：${allClasses.size} 个（扫描 $dexCount 个 dex")
            if (skippedDexes > 0) append("，跳过 $skippedDexes 个")
            append("）\n")
            append("方法签名：${details.size} 个类匹配前缀\n")
            append("  方法 ${details.sumOf { it.methods.size }} / 字段 ${details.sumOf { it.fields.size }}\n")
            append("文件：\n  ${classesFile.absolutePath}\n  ${methodsFile.absolutePath}\n")
            append(if (published) "已复制到 /sdcard/" else "（/sdcard 复制失败，用上面的路径）")
        }
    }

    private fun buildClassList(
        hostPackage: String,
        dexCount: Int,
        classes: Set<String>,
    ): String {
        val matches = classes.filter { descriptor ->
            descriptor.startsWith("Lcom/tencent/") &&
                KEYWORDS.any { descriptor.contains(it, ignoreCase = true) }
        }.map { it.substring(1, it.length - 1).replace('/', '.') }.sorted()
        val body = StringBuilder()
        body.append("# Qself class dump — $hostPackage\n")
        body.append("# dex images scanned: $dexCount\n")
        body.append("# classes with relevant names: ${matches.size}\n")
        for (line in matches.take(MAX_LINES)) body.append(line).append('\n')
        return body.toString()
    }

    private fun buildMethodList(
        hostPackage: String,
        prefixes: List<String>,
        details: List<DexClass>,
    ): String {
        val body = StringBuilder()
        body.append("# Qself method dump — $hostPackage\n")
        body.append("# prefixes: ${prefixes.joinToString()}\n")
        body.append("# usage: look up the exact method to hook\n")
        for (cls in details.sortedBy { it.descriptor }) {
            body.append('\n')
            body.append("class ${cls.descriptor.substring(1, cls.descriptor.length - 1).replace('/', '.')}\n")
            for (m in cls.methods.take(MAX_METHODS_PER_CLASS)) {
                body.append("  m ${DexReader.methodFlags(m.flags)} ${m.name}${m.signature}\n")
            }
            if (cls.methods.size > MAX_METHODS_PER_CLASS) {
                body.append("  # … ${cls.methods.size - MAX_METHODS_PER_CLASS} more methods\n")
            }
            for (f in cls.fields) {
                body.append("  f ${DexReader.fieldFlags(f.flags)} ${f.name}\n")
            }
        }
        return body.toString()
    }

    private fun write(context: Context, name: String, text: String): java.io.File {
        val file = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, name)
        file.writeText(text)
        return file
    }

    /** Best effort: publishing to /sdcard is a convenience, not a requirement. */
    private fun publish(pairs: List<Pair<java.io.File, String>>): Boolean {
        val script = pairs.joinToString(" && ") { (from, to) ->
            "cp '${from.absolutePath}' '$to'"
        }
        return try {
            val process = ProcessBuilder("su", "0", "-c", "sh -c ${quote(script)}")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (t: Throwable) {
            false
        }
    }

    /** Finds the host's APK path (`pm path`) and copies it out with root. */
    private fun copyApk(hostPackage: String, destination: java.io.File): Boolean {
        val command = "pm path $hostPackage | head -1 | sed 's/^package://' | " +
            "xargs -r cp '$destination'"
        return try {
            val process = ProcessBuilder("su", "0", "-c", "sh -c ${quote(command)}")
                .redirectErrorStream(true).start()
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

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
