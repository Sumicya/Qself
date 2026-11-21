// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/*
 * 全量日志的「合并 / 去重」逻辑。与 Android 完全解耦，JVM 单测能直接跑真机日志。
 *
 * 目标：反复导出只产生一个 qq-diag.txt。新导出与上一次的导出按小节合并去重，
 * 环缓冲被挤掉的老行也能从上一次导出里捞回来，不必再发 qq-diag (1).txt 之类的编号副本。
 *
 * 小节的三种处理方式（这是理解本文件的主线）：
 *   报告头（key ""）  用新的
 *   视图树快照（key 3）按标题取并集，新块覆盖同名旧块，块数封顶
 *   流式小节（4/5/6）按记录去重、按时间戳排序、按条数与字节双重封顶
 *   其余小节          一律用新的
 */

internal const val LOG_FILE_NAME = "qq-diag.txt"
internal const val LOG_FILE_PREFIX = "qq-diag"

/** 上一版文件名里的尾巴；只用来删掉 Download 里的历史副本，删完即失效。 */
internal const val LOG_FILE_LEGACY = "full-log"

/** 上一版留下的 `*-full-log.txt` 与 `*-full-log (N).txt`：判据写死，免得误删别人的 `something.txt`。 */
internal fun isLegacyExport(name: String): Boolean =
    name.endsWith("$LOG_FILE_LEGACY.txt") || (name.contains("$LOG_FILE_LEGACY (") && name.endsWith(").txt"))

internal const val MAX_MERGED_RECORDS = 12000
internal const val MAX_MERGED_SNAPSHOTS = 16

/*
 * 只按条数封顶不够：真机实测 [5] 一节 12000 条就有 13.8MB（整份日志 19MB），
 * 文件既不好传也不好读，导出时的 readText + 合并还会把 QQ 主线程冻住。
 * 再按字节封一次顶，超出的从最老的开始丢，永远留最新的一段。
 */
internal const val MAX_MERGED_SECTION_BYTES = 2_000_000

/*
 * 单条记录的长度上限：QQ 的 QLog 偶尔吐 15KB 的 Notification Channels / 配置 dump，
 * 一条顶得上几十条正常日志。从中间掐断、保留头尾——堆栈的头和尾才有信息量。
 */
internal const val MAX_RECORD_CHARS = 4096
internal const val RECORD_TRIM_HEAD = 3072
internal const val RECORD_TRIM_TAIL = 768

/**
 * 流式小节的 key。
 *
 * 这些 key 在合并与增量统计两处都要用，原先在四个地方各写一遍字面量
 * `listOf("4", "5", "6")`——加一节日志时漏改一处，合并会把新节当「用新的」处理，
 * 于是历史记录静默丢掉。收到这里，只有一处需要维护。
 */
internal val STREAMING_SECTIONS = listOf("4", "5", "6")

/** 视图树快照小节。 */
internal const val SNAPSHOT_SECTION = "3"

internal val SECTION_HEADER = Regex("^--- \\[(\\d+)\\](.*) ---$")
internal val RECORD_START = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")

internal data class ReportSection(val key: String, val header: String, val body: String)

/** 按 `--- [x] ---` 小节拆开；key 为小节号（`""` 表示报告头），header 保留原始小节行以便按新数量重建。 */
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

/** 把小节正文按「时间戳起始行 + 其续行（堆栈等）」切成记录，便于整条去重与排序。 */
internal fun splitRecords(body: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()

    fun flush() {
        // 统一成「恰好一个换行结尾」：否则 splitReport 带来的尾随空行会让同一条记录
        // 出现两种写法，去重就失效了。
        val record = current.toString().trimEnd()
        if (record.isNotEmpty()) out.add(record + "\n")
        current.setLength(0)
    }

    for (line in body.lineSequence()) {
        if (line.isBlank()) {
            if (current.isNotEmpty()) current.append('\n')
            continue
        }
        if (RECORD_START.containsMatchIn(line) && current.isNotEmpty()) flush()
        current.append(line).append('\n')
    }
    flush()
    return out
}

internal fun timestampOf(record: String): String = record.take(12)

/** 单条记录超长时从中间掐断，头尾都留着；已经掐过的再掐一次长度不变（幂等，合并去重不会因此重复）。 */
internal fun trimRecord(record: String): String {
    if (record.length <= MAX_RECORD_CHARS) return record
    val body = record.trimEnd()
    if (body.length <= MAX_RECORD_CHARS) return record
    val cut = body.length - RECORD_TRIM_HEAD - RECORD_TRIM_TAIL
    return body.substring(0, RECORD_TRIM_HEAD) +
        "…[截断 " + cut + " 字符]…" +
        body.substring(body.length - RECORD_TRIM_TAIL) + "\n"
}

/**
 * 从最新一条往回累加字节，超出预算就停；返回仍按时间顺序排列的保留段。
 *
 * 语义是「软上限」：**无论 [budget] 多小，至少保留最新一条**。
 * 理由是单条记录本身可能就有 15KB，硬砍会让整个小节空掉——
 * 那比超一点字节糟得多（导出日志的价值全在最新那几条）。
 * 因此 budget <= 0 不是「全丢」，而是「只留最新一条」；调用方别指望它清空。
 */
internal fun capByBytes(records: List<String>, budget: Int): List<String> {
    var used = 0
    var first = records.size
    while (first > 0) {
        val length = records[first - 1].length
        // `first < records.size` 是「至少留一条」的落点：只要还没拿过任何一条，超预算也拿。
        if (used + length > budget && first < records.size) break
        used += length
        first--
    }
    return if (first <= 0) records else records.subList(first, records.size)
}

/** 把小节标题行里的条数换成 [count]；没有括号就追加一个。 */
internal fun headerWithCount(header: String, count: Int): String {
    val open = header.lastIndexOf('(')
    val close = header.lastIndexOf(')')
    return if (open > 0 && close > open) {
        header.substring(0, open) + "($count)" + header.substring(close + 1)
    } else {
        "$header ($count)"
    }
}

/** 视图树快照按 `=== 标题 ===` 分块取并集（新块覆盖同名旧块），保持旧块在前、新块在后的顺序。 */
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
            if (line.startsWith("=== ")) {
                flush()
                title = line
                body.append(line).append('\n')
            } else if (title.isNotEmpty()) {
                body.append(line).append('\n')
            }
        }
        flush()
    }
    return blocks
}

/** 流式小节的合并：去重 → 排序 → 条数封顶 → 掐长记录 → 字节封顶。 */
private fun mergeStreamingSection(oldBody: String, newBody: String): List<String> {
    val byContent = LinkedHashMap<String, String>()
    for (record in splitRecords(oldBody)) byContent[record] = record
    for (record in splitRecords(newBody)) byContent.putIfAbsent(record, record)

    val all = byContent.keys.toMutableList()
    all.sortBy(::timestampOf)
    val byCount = if (all.size > MAX_MERGED_RECORDS) all.takeLast(MAX_MERGED_RECORDS) else all
    // 先掐长记录再按字节封顶：顺序反了的话，一条 15KB 的 dump 会把预算吃掉一大块。
    return capByBytes(byCount.map(::trimRecord), MAX_MERGED_SECTION_BYTES)
}

/**
 * 与上一次导出的内容合并：报告头用新的，快照按标题取并集（新覆盖旧），
 * 流式小节（[STREAMING_SECTIONS]）按记录去重后按时间戳排序取并集，
 * 其余小节（失败清单、钩子调用次数、logcat）一律用新的。
 *
 * 这样环缓冲里被挤掉的老行也能从上一次导出里捞回来——一次导出就是到此刻为止的
 * 完整累计，不必再发多个编号文件。
 */
internal fun mergeDiagnosticReports(old: String?, new: String): String {
    if (old.isNullOrBlank()) return new
    val newParts = splitReport(new)
    if (newParts.isEmpty()) return new
    val oldByKey = splitReport(old).associateBy { it.key }

    val out = StringBuilder(new.length + old.length / 2)
    for (section in newParts) {
        when {
            section.key == "" -> out.append(section.body.trimEnd()).append('\n')

            section.key == SNAPSHOT_SECTION -> {
                val merged = mergeSnapshotBlocks(listOf(oldByKey[SNAPSHOT_SECTION]?.body.orEmpty(), section.body))
                // 视图树快照最占体积（单块可达数十 KB），只保留最新的若干块，按标题插入顺序淘汰最老的。
                val kept = LinkedHashMap<String, String>()
                var toDrop = (merged.size - MAX_MERGED_SNAPSHOTS).coerceAtLeast(0)
                for ((title, text) in merged) if (toDrop-- <= 0) kept[title] = text
                out.append(headerWithCount(section.header, kept.size)).append('\n')
                kept.values.forEach { out.append(it.trimEnd()).append('\n') }
            }

            section.key in STREAMING_SECTIONS -> {
                val records = mergeStreamingSection(oldByKey[section.key]?.body.orEmpty(), section.body)
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
