// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/* 全量日志合并去重，与 Android 解耦。报告头用新的；快照按标题并；流式节去重排序后双重封顶。 */

internal const val LOG_FILE_NAME = "qq-diag.txt"
internal const val LOG_FILE_PREFIX = "qq-diag"

/** 上一版文件名里的尾巴；只用来删掉 Download 里的历史副本，删完即失效。 */
internal const val LOG_FILE_LEGACY = "full-log"

/** 上一版留下的 *-full-log.txt 与 *-full-log (N).txt；判据写死，免得误删。 */
internal fun isLegacyExport(name: String): Boolean =
    name.endsWith("$LOG_FILE_LEGACY.txt") || (name.contains("$LOG_FILE_LEGACY (") && name.endsWith(").txt"))

internal const val MAX_MERGED_RECORDS = 12000
internal const val MAX_MERGED_SNAPSHOTS = 16

/* 只按条数不够：真机 [5] 一节 12000 条就 13.8MB，导出时 readText + 合并会冻住主线程。再按字节封顶。 */
internal const val MAX_MERGED_SECTION_BYTES = 2_000_000

/* 单条上限：QLog 偶尔吐 15KB 的 dump，从中间掐断、保留头尾（堆栈两头才有信息量）。 */
internal const val MAX_RECORD_CHARS = 4096
internal const val RECORD_TRIM_HEAD = 3072
internal const val RECORD_TRIM_TAIL = 768

/** 流式小节（原先三处各写一遍死字面量）与视图树快照小节。 */
internal val STREAMING_SECTIONS = listOf("4", "5", "6")
internal const val SNAPSHOT_SECTION = "3"

internal val SECTION_HEADER = Regex("^--- \\[(\\d+)\\](.*) ---$")
internal val RECORD_START = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")

internal data class ReportSection(val key: String, val header: String, val body: String)

/** 按 `--- [x] ---` 拆开；key 是小节号（`""` 为报告头），header 保留原行以便重建。 */
internal fun splitReport(text: String): List<ReportSection> {
    val out = ArrayList<ReportSection>()
    var key = ""
    var header = ""
    val body = StringBuilder()
    for (line in text.lineSequence()) {
        val match = SECTION_HEADER.matchEntire(line)
        if (match == null) {
            body.append(line).append('\n')
            continue
        }
        out.add(ReportSection(key, header, body.toString()))
        body.setLength(0)
        key = match.groupValues[1]
        header = line
    }
    out.add(ReportSection(key, header, body.toString()))
    return out
}

/** 按「时间戳起始行 + 续行（堆栈）」切成记录，便于整条去重与排序。 */
internal fun splitRecords(body: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    fun flush() {
        // 统一成恰好一个换行结尾，否则同一条记录会有两种写法，去重失效。
        current.toString().trimEnd().takeIf { it.isNotEmpty() }?.let { out.add(it + "\n") }
        current.setLength(0)
    }

    for (line in body.lineSequence()) {
        if (line.isBlank()) { if (current.isNotEmpty()) current.append('\n'); continue }
        if (RECORD_START.containsMatchIn(line) && current.isNotEmpty()) flush()
        current.append(line).append('\n')
    }
    flush()
    return out
}

internal fun timestampOf(record: String): String = record.take(12)

/** 超长从中间掐断，保留头尾；掐过的再掐长度不变（幂等，不会让去重失效）。 */
internal fun trimRecord(record: String): String {
    if (record.length <= MAX_RECORD_CHARS) return record
    val body = record.trimEnd()
    if (body.length <= MAX_RECORD_CHARS) return record
    val cut = body.length - RECORD_TRIM_HEAD - RECORD_TRIM_TAIL
    return body.substring(0, RECORD_TRIM_HEAD) + "…[截断 " + cut + " 字符]…" +
        body.substring(body.length - RECORD_TRIM_TAIL) + "\n"
}

/** 从最新往回累加字节，超预算就停。软上限：无论 budget 多小，至少留最新一条。 */
internal fun capByBytes(records: List<String>, budget: Int): List<String> {
    var used = 0
    var first = records.size
    while (first > 0) {
        val length = records[first - 1].length
        if (used + length > budget && first < records.size) break // first < size：至少留一条
        used += length
        first--
    }
    return if (first <= 0) records else records.subList(first, records.size)
}

/** 把标题行里的条数换成 count；没有括号就追加一个。 */
internal fun headerWithCount(header: String, count: Int): String {
    val open = header.lastIndexOf('(')
    val close = header.lastIndexOf(')')
    return if (open > 0 && close > open) header.substring(0, open) + "($count)" + header.substring(close + 1)
    else "$header ($count)"
}

/** 快照按 `=== 标题 ===` 分块取并集，新块覆盖同名旧块，旧块在前。 */
internal fun mergeSnapshotBlocks(chunks: List<String>): LinkedHashMap<String, String> {
    val blocks = LinkedHashMap<String, String>()
    for (chunk in chunks) {
        var title = ""
        val body = StringBuilder()

        fun flush() {
            if (title.isNotEmpty()) blocks[title] = body.toString()
            body.setLength(0)
        }
        for (line in chunk.lineSequence()) {
            if (line.startsWith("=== ")) { flush(); title = line; body.append(line).append('\n') }
            else if (title.isNotEmpty()) body.append(line).append('\n')
        }
        flush()
    }
    return blocks
}

/** 流式小节的合并：去重 → 排序 → 条数封顶 → 掐长记录 → 字节封顶。 */
/** 与上一次导出合并：环缓冲被挤掉的老行也能捞回来，一次导出就是到此刻的完整累计。 */
internal fun mergeDiagnosticReports(old: String?, new: String): String {
    if (old.isNullOrBlank()) return new
    val newParts = splitReport(new)
    if (newParts.isEmpty()) return new
    val oldByKey = splitReport(old).associateBy { it.key }
    val out = StringBuilder(new.length + old.length / 2)
    for (section in newParts) {
        when (section.key) {
            "" -> out.append(section.body.trimEnd()).append('\n')
            SNAPSHOT_SECTION -> {
                val merged = mergeSnapshotBlocks(listOf(oldByKey[SNAPSHOT_SECTION]?.body.orEmpty(), section.body))
                // 快照最占体积（单块数十 KB），只留最新若干块，按插入顺序淘汰最老的。
                val kept = merged.entries.drop((merged.size - MAX_MERGED_SNAPSHOTS).coerceAtLeast(0)).associate { it.toPair() }
                out.append(headerWithCount(section.header, kept.size)).append('\n')
                kept.values.forEach { out.append(it.trimEnd()).append('\n') }
            }
            in STREAMING_SECTIONS -> {
                val merged = LinkedHashMap<String, String>()
                for (r in splitRecords(oldByKey[section.key]?.body.orEmpty())) merged[r] = r
                for (r in splitRecords(section.body)) merged.putIfAbsent(r, r)
                val all = merged.keys.sortedBy(::timestampOf)
                val byCount = if (all.size > MAX_MERGED_RECORDS) all.takeLast(MAX_MERGED_RECORDS) else all
                // 先掐长记录再按字节封顶：反过来的话一条 15KB 的 dump 会吃掉一大块预算。
                val records = capByBytes(byCount.map(::trimRecord), MAX_MERGED_SECTION_BYTES)
                out.append(headerWithCount(section.header, records.size)).append('\n')
                records.forEach { out.append(it) }
            }
            else -> out.append(section.header).append('\n').append(section.body.trimEnd()).append('\n')
        }
    }
    return out.toString()
}

/** 本次导出相对上一次导出的新增记录数（只统计流式小节），用于 LSPosed 镜像只吐增量、不重复刷屏。 */
internal fun reportDeltaRecords(old: String?, new: String): Int {
    val newByKey = splitReport(new).associateBy { it.key }
    fun streamingCount(parts: Map<String, ReportSection>): Int =
        STREAMING_SECTIONS.sumOf { splitRecords(parts[it]?.body.orEmpty()).size }
    if (old.isNullOrBlank()) return streamingCount(newByKey)

    val oldByKey = splitReport(old).associateBy { it.key }
    val oldRecords = HashSet<String>()
    for (key in STREAMING_SECTIONS) oldRecords.addAll(splitRecords(oldByKey[key]?.body.orEmpty()))
    return STREAMING_SECTIONS.sumOf { key ->
        splitRecords(newByKey[key]?.body.orEmpty()).count { it !in oldRecords }
    }
}
