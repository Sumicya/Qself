package sumicya.qself

// 全量日志的「合并/去重」逻辑：与 Android 完全解耦，方便 JVM 单测直接跑真机日志。
// 目标：反复导出只产生一个 qself-full-log.txt，新导出与上一次的导出按小节合并去重，
// 环缓冲被挤掉的老行也能从上一次导出里捞回来，不必再发多个 qself-full-log (1).txt 之类的编号副本。

internal const val LOG_FILE_NAME = "qself-full-log.txt"
internal const val LOG_FILE_PREFIX = "qself-full-log"
internal const val MAX_MERGED_RECORDS = 12000
internal const val MAX_MERGED_SNAPSHOTS = 16
// 只按条数封顶不够：真机实测 [5] 一节 12000 条就有 13.8MB（整份日志 19MB），
// 文件既不好传也不好读，导出时的 readText + 合并还会把 QQ 主线程冻住。
// 再按字节封一次顶，超出的从最老的开始丢，永远留最新的一段。
internal const val MAX_MERGED_SECTION_BYTES = 2_000_000
// 单条记录的长度上限：QQ 的 QLog 偶尔吐 15KB 的 Notification Channels / 配置 dump，
// 一条顶得上几十条正常日志，从中间掐断、保留头尾（堆栈的头和尾才有信息量）。
internal const val MAX_RECORD_CHARS = 4096
internal const val RECORD_TRIM_HEAD = 3072
internal const val RECORD_TRIM_TAIL = 768
internal val SECTION_HEADER = Regex("^--- \\[(\\d+)\\](.*) ---$")
internal val RECORD_START = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")

internal data class ReportSection(val key: String, val header: String, val body: String)

/** 按 `--- [x] ---` 小节拆开；key 为小节号（`""` 表示报告头），header 保留原始小节行以便按新数量重建。 */
internal fun splitReport(text: String): List<ReportSection> {
    val out = ArrayList<ReportSection>()
    var key = ""
    var header = ""
    val sb = StringBuilder()
    for (line in text.lineSequence()) {
        val m = SECTION_HEADER.matchEntire(line)
        if (m != null) {
            out.add(ReportSection(key, header, sb.toString()))
            sb.setLength(0)
            key = m.groupValues[1]
            header = line
        } else {
            sb.append(line).append('\n')
        }
    }
    out.add(ReportSection(key, header, sb.toString()))
    return out
}

/** 把小节正文按「时间戳起始行 + 其续行（堆栈等）」切成记录，便于整条去重与排序。 */
internal fun splitRecords(body: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
        // 统一成「恰好一个换行结尾」，否则 splitReport 带来的尾随空行会让同一条记录出现两种写法、去重失效。
        val rec = sb.toString().trimEnd()
        if (rec.isNotEmpty()) out.add(rec + "\n")
        sb.setLength(0)
    }
    for (line in body.lineSequence()) {
        if (line.isBlank()) { if (sb.isNotEmpty()) sb.append('\n'); continue }
        if (RECORD_START.containsMatchIn(line) && sb.isNotEmpty()) flush()
        sb.append(line).append('\n')
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

/** 从最新一条往回累加字节，超出预算就停；返回仍按时间顺序排列的保留段，至少保留一条。 */
internal fun capByBytes(records: List<String>, budget: Int): List<String> {
    var used = 0
    var first = records.size
    while (first > 0) {
        val len = records[first - 1].length
        if (used + len > budget && first < records.size) break
        used += len
        first--
    }
    return if (first <= 0) records else records.subList(first, records.size)
}

internal fun headerWithCount(header: String, count: Int): String {
    val open = header.lastIndexOf('(')
    val close = header.lastIndexOf(')')
    return if (open > 0 && close > open) header.substring(0, open) + "($count)" + header.substring(close + 1)
    else "$header ($count)"
}

/** 视图树快照按 `=== 标题 ===` 分块取并集（新块覆盖同名旧块），保持旧块在前、新块在后的顺序。 */
internal fun mergeSnapshotBlocks(chunks: List<String>): LinkedHashMap<String, String> {
    val blocks = LinkedHashMap<String, String>()
    for (chunk in chunks) {
        var title = ""
        val sb = StringBuilder()
        fun flush() {
            if (title.isNotEmpty()) blocks[title] = sb.toString()
            sb.setLength(0)
        }
        for (line in chunk.lineSequence()) {
            if (line.startsWith("=== ")) { flush(); title = line; sb.append(line).append('\n') }
            else if (title.isNotEmpty()) sb.append(line).append('\n')
        }
        flush()
    }
    return blocks
}

/**
 * 与上一次导出的内容合并：报告头用新的，快照按标题取并集（新覆盖旧），
 * 流式小节（[4] Qself 事件 / [5] UI 专项 QLog / [6] 全量 QLog）按记录去重后按时间戳排序取并集，
 * 其余小节（失败清单、钩子调用次数、logcat）一律用新的。
 * 这样环缓冲里被挤掉的老行也能从上一次导出里捞回来 —— 一次导出就是到此刻为止的完整累计，不必再发多个编号文件。
 */
internal fun mergeDiagnosticReports(old: String?, new: String): String {
    if (old.isNullOrBlank()) return new
    val oldParts = splitReport(old)
    val newParts = splitReport(new)
    if (newParts.isEmpty()) return new
    val oldMap = oldParts.associateBy { it.key }
    val out = StringBuilder(new.length + old.length / 2)
    for (section in newParts) {
        when (section.key) {
            "" -> out.append(section.body.trimEnd()).append('\n')
            "3" -> {
                val all = mergeSnapshotBlocks(listOf(oldMap["3"]?.body.orEmpty(), section.body))
                // 视图树快照最占体积（单块可达数十 KB），只保留最新的若干块，按标题插入顺序淘汰最老的。
                val blocks = LinkedHashMap<String, String>()
                var drop = (all.size - MAX_MERGED_SNAPSHOTS).coerceAtLeast(0)
                for ((k, v) in all) if (drop-- <= 0) blocks[k] = v
                out.append(headerWithCount(section.header, blocks.size)).append('\n')
                blocks.values.forEach { out.append(it.trimEnd()).append('\n') }
            }
            "4", "5", "6" -> {
                val merged = LinkedHashMap<String, String>()
                for (r in splitRecords(oldMap[section.key]?.body.orEmpty())) merged[r] = r
                for (r in splitRecords(section.body)) merged.putIfAbsent(r, r)
                val all = merged.keys.toMutableList()
                all.sortBy(::timestampOf)
                val byCount = if (all.size > MAX_MERGED_RECORDS) all.takeLast(MAX_MERGED_RECORDS) else all
                // 先掐长记录再按字节封顶：顺序反了的话，一条 15KB 的 dump 会把预算吃掉一大块。
                val capped = capByBytes(byCount.map(::trimRecord), MAX_MERGED_SECTION_BYTES)
                out.append(headerWithCount(section.header, capped.size)).append('\n')
                capped.forEach { out.append(it) }
            }
            else -> out.append(section.header).append('\n').append(section.body.trimEnd()).append('\n')
        }
    }
    return out.toString()
}

/** 本次导出相对上一次导出的新增记录数（只统计流式小节），用于 LSPosed 镜像只吐增量、不重复刷屏。 */
internal fun reportDeltaRecords(old: String?, new: String): Int {
    val newParts = splitReport(new).associateBy { it.key }
    if (old.isNullOrBlank()) return listOf("4", "5", "6").sumOf { splitRecords(newParts[it]?.body.orEmpty()).size }
    val oldParts = splitReport(old).associateBy { it.key }
    val oldSet = HashSet<String>()
    for (k in listOf("4", "5", "6")) oldSet.addAll(splitRecords(oldParts[k]?.body.orEmpty()))
    return listOf("4", "5", "6").sumOf { k ->
        splitRecords(newParts[k]?.body.orEmpty()).count { it !in oldSet }
    }
}

