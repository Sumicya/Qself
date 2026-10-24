// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("DEPRECATION")

package sumicya.qself

import android.app.Activity
import android.content.ClipData
import android.app.RecoverableSecurityException
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MAX_QSELF_EVENTS = 1500
private const val MAX_UI_QLOGS = 2500
private const val MAX_ALL_QLOGS = 3000
private const val MAX_HOOK_SAMPLE_PER_METHOD = 10

private val qselfEvents = ConcurrentLinkedDeque<String>()
private val qselfEventCount = AtomicInteger(0)

private val uiQLogs = ConcurrentLinkedDeque<String>()
private val uiQLogCount = AtomicInteger(0)

private val allQLogs = ConcurrentLinkedDeque<String>()
private val allQLogCount = AtomicInteger(0)

private val hookCallCounts = ConcurrentHashMap<String, AtomicLong>()
private val activitySnapshots = ConcurrentHashMap<String, String>()
private val mainHandler = Handler(Looper.getMainLooper())
private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Qself-DiagLog").apply { isDaemon = true }
}
@Volatile
private var lastAutoFlushMs = 0L
/** 最近一次导出清理掉的历史副本数，供提示复用。 */
@Volatile
private var lastMergeRemoved = 0
/** 删除别的应用写进 Download 的历史副本所需的系统授权 IntentSender（null 表示无需授权）。 */
@Volatile
private var pendingCleanupConsent: IntentSender? = null

private val uiKeywords = Regex(
    "AIO|Title|NavBar|QUISec|SearchBar|Immersive|Blur|Mask|Safe|Status|Header|Padding|Margin|" +
        "TabFrame|TabLayout|Conversation|ChatsList|MainUI|MainResource|SimpleMode|Skin|Theme|" +
        "Bubble|Avatar|Input|Shortcut|EdgeTransparent|RecycleView|Qself|Exception|Error",
    RegexOption.IGNORE_CASE,
)

internal fun nowStamp(): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

private fun pushBounded(deque: ConcurrentLinkedDeque<String>, counter: AtomicInteger, max: Int, line: String) {
    deque.addLast(line)
    if (counter.incrementAndGet() > max) {
        deque.pollFirst()
        counter.decrementAndGet()
    }
}

fun recordQselfEvent(msg: String, t: Throwable? = null) {
    val line = buildString {
        append(nowStamp()).append(" [").append(Thread.currentThread().name).append("] ").append(msg)
        if (t != null) append("\n").append(Log.getStackTraceString(t))
    }
    pushBounded(qselfEvents, qselfEventCount, MAX_QSELF_EVENTS, line)
}

fun recordHookInvocation(sig: String, thisObj: Any?, args: List<Any?>, result: Any?) {
    val count = hookCallCounts.computeIfAbsent(sig) { AtomicLong(0L) }.incrementAndGet()
    if (count <= MAX_HOOK_SAMPLE_PER_METHOD) {
        val thisDesc = thisObj?.let { "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}" } ?: "static"
        val argDesc = args.take(4).joinToString(", ") { summarizeObj(it) }
        val resDesc = summarizeObj(result)
        recordQselfEvent("HOOK#$count $sig this=$thisDesc args=[$argDesc] -> $resDesc")
    }
}

private fun summarizeObj(obj: Any?): String = when (obj) {
    null -> "null"
    is Number, is Boolean -> obj.toString()
    is CharSequence -> "\"${obj.toString().replace("\n", "\\n").take(48)}\""
    is View -> {
        val idHex = if (obj.id != View.NO_ID) "0x${Integer.toHexString(obj.id)}" else "NO_ID"
        "${obj.javaClass.simpleName}($idHex,${obj.width}x${obj.height},pad=[${obj.paddingLeft},${obj.paddingTop},${obj.paddingRight},${obj.paddingBottom}],vis=${visName(obj.visibility)})"
    }
    is Drawable -> describeDrawable(obj)
    else -> "${obj.javaClass.name}@${Integer.toHexString(System.identityHashCode(obj))}"
}

private fun visName(v: Int): String = when (v) {
    View.VISIBLE -> "V"
    View.INVISIBLE -> "I"
    View.GONE -> "G"
    else -> v.toString()
}

private fun describeDrawable(d: Drawable?): String {
    if (d == null) return "null"
    return when (d) {
        is ColorDrawable -> "ColorDrawable(#${String.format("%08X", d.color)},alpha=${d.alpha})"
        is LayerDrawable -> {
            val layers = (0 until d.numberOfLayers).joinToString(",") { describeDrawable(d.getDrawable(it)) }
            "LayerDrawable(${d.numberOfLayers}:[$layers])"
        }
        else -> "${d.javaClass.name}(alpha=${d.alpha},bounds=${d.bounds.toShortString()})"
    }
}

private fun resNameOf(v: View): String {
    val id = v.id
    if (id == View.NO_ID) return "NO_ID"
    val hex = "0x${Integer.toHexString(id)}"
    val entry = runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
    return if (entry != null) "$hex($entry)" else hex
}

fun scheduleActivitySnapshot(activity: Activity, tag: String = "resume") {
    if (!on("全量日志")) return
    for (delay in longArrayOf(450L, 1800L)) {
        mainHandler.postDelayed({
            runCatching { captureActivitySnapshot(activity, "$tag+${delay}ms") }
        }, delay)
    }
}

fun captureViewRootSnapshot(view: View?, sceneTag: String) {
    if (!on("全量日志") || view == null) return
    mainHandler.postDelayed({
        runCatching {
            val root = view.rootView ?: return@runCatching
            val dump = dumpViewTreeSummary(root, sceneTag)
            activitySnapshots[sceneTag] = dump
            maybeAutoFlush(view.context)
        }
    }, 250L)
}

fun captureActivitySnapshot(activity: Activity, reason: String = "manual"): String {
    val decor = activity.window?.decorView ?: return "Activity ${activity.javaClass.name}: decorView=null"
    val key = activity.javaClass.name
    val dump = buildString(16384) {
        val w = activity.window
        val insets = runCatching { decor.rootWindowInsets }.getOrNull()
        val sb = runCatching { insets?.getInsets(WindowInsets.Type.statusBars()) }.getOrNull()
        val nb = runCatching { insets?.getInsets(WindowInsets.Type.navigationBars()) }.getOrNull()
        val ime = runCatching { insets?.getInsets(WindowInsets.Type.ime()) }.getOrNull()
        append("=== Activity Snapshot: ").append(key).append(" (reason=").append(reason).append(", time=").append(nowStamp()).append(") ===\n")
        append("decor=").append(decor.width).append("x").append(decor.height)
            .append(" density=").append(decor.resources.displayMetrics.density)
            .append(" statusBarColor=#").append(String.format("%08X", w?.statusBarColor ?: 0))
            .append(" navBarColor=#").append(String.format("%08X", w?.navigationBarColor ?: 0))
            .append(" winFlags=0x").append(Integer.toHexString(w?.attributes?.flags ?: 0))
            .append(" sysUiVis=0x").append(Integer.toHexString(decor.systemUiVisibility))
            .append("\n")
        append("insets: statusBars=").append(sb?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null")
            .append(" navBars=").append(nb?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null")
            .append(" ime=").append(ime?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null")
            .append(" reducedSafeTop=").append(reducedSafeTop(sb?.top ?: 140, decor.resources.displayMetrics.density))
            .append("\n")
        appendViewTree(this, decor, 0, AtomicInteger(0), maxNodes = 650, maxDepth = 18)
    }
    activitySnapshots[key] = dump
    maybeAutoFlush(activity)
    return dump
}

private fun dumpViewTreeSummary(root: View, sceneTag: String): String = buildString(12288) {
    append("=== Scene Snapshot: ").append(sceneTag).append(" (time=").append(nowStamp()).append(") ===\n")
    appendViewTree(this, root, 0, AtomicInteger(0), maxNodes = 550, maxDepth = 18)
}

private fun appendViewTree(
    sb: StringBuilder,
    v: View,
    depth: Int,
    counter: AtomicInteger,
    maxNodes: Int,
    maxDepth: Int,
) {
    if (counter.incrementAndGet() > maxNodes || depth > maxDepth) return
    repeat(depth) { sb.append("  ") }
    val loc = IntArray(2)
    runCatching { v.getLocationOnScreen(loc) }
    val lp = v.layoutParams
    val marginStr = if (lp is ViewGroup.MarginLayoutParams) {
        "[${lp.leftMargin},${lp.topMargin},${lp.rightMargin},${lp.bottomMargin}]"
    } else "[-]"
    sb.append(v.javaClass.name)
        .append(" id=").append(resNameOf(v))
        .append(" vis=").append(visName(v.visibility))
        .append(" b=[").append(v.left).append(',').append(v.top).append(',').append(v.right).append(',').append(v.bottom).append(']')
        .append('(').append(v.width).append('x').append(v.height).append(')')
        .append(" scr=[").append(loc[0]).append(',').append(loc[1]).append(']')
        .append(" pad=[").append(v.paddingLeft).append(',').append(v.paddingTop).append(',').append(v.paddingRight).append(',').append(v.paddingBottom).append(']')
        .append(" mar=").append(marginStr)
    if (v.translationX != 0f || v.translationY != 0f || v.translationZ != 0f) {
        sb.append(" trans=[").append(v.translationX).append(',').append(v.translationY).append(',').append(v.translationZ).append(']')
    }
    if (v.alpha != 1f) sb.append(" alpha=").append(v.alpha)
    if (v is ViewGroup) {
        sb.append(" clip=[ch=").append(v.clipChildren).append(",pad=").append(v.clipToPadding).append(",out=").append(v.clipToOutline).append(']')
    } else if (v.clipToOutline) {
        sb.append(" clipOut=true")
    }
    if (v.background != null) {
        sb.append(" bg=").append(describeDrawable(v.background))
    }
    if (v.javaClass.name == "com.tencent.mobileqq.aio.widget.EdgeTransparentView") {
        val dVal = runCatching { v.get("D") }.getOrNull()
        val iVal = runCatching { v.get("i") }.getOrNull()
        val mVal = runCatching { v.get("m") }.getOrNull()
        sb.append(" edge=[D=").append(dVal).append(",i=").append(iVal).append(",m=").append(mVal).append(']')
    } else if (v.javaClass.name == "com.tencent.qqnt.chats.view.SkinnableRecycleView") {
        val fVal = runCatching { v.get("f") }.getOrNull()
        sb.append(" skinClipF=").append(fVal)
    }
    if (v is TextView) {
        val txt = v.text?.toString()?.replace("\n", "\\n")?.take(36).orEmpty()
        sb.append(" text=\"").append(txt).append("\" color=#").append(String.format("%08X", v.currentTextColor))
    }
    sb.append('\n')
    if (v is ViewGroup) {
        for (i in 0 until v.childCount) {
            val ch = v.getChildAt(i) ?: continue
            appendViewTree(sb, ch, depth + 1, counter, maxNodes, maxDepth)
        }
    }
}

private fun maybeAutoFlush(ctx: Context?) {
    if (ctx == null) return
    val now = System.currentTimeMillis()
    if (now - lastAutoFlushMs < 2500L) return
    lastAutoFlushMs = now
    val appCtx = ctx.applicationContext ?: ctx
    ioExecutor.execute {
        runCatching { writeLogFiles(appCtx, includeLogcat = false) }
    }
}

fun buildFullDiagnosticReport(ctx: Context?, includeLogcat: Boolean = true): String = buildString(131072) {
    append("================================================================================\n")
    append("Qself Full QQ Diagnostic Report\n")
    append("Version      : ").append(BuildConfig.VERSION_NAME).append('\n')
    append("Time         : ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())).append('\n')
    append("Device       : Android ").append(Build.VERSION.RELEASE).append(' ')
        .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
    append("PID / Thread : pid=").append(android.os.Process.myPid()).append(" thread=").append(Thread.currentThread().name).append('\n')
    ctx?.resources?.displayMetrics?.let { dm ->
        append("Display      : ").append(dm.widthPixels).append("x").append(dm.heightPixels)
            .append(" density=").append(dm.density).append(" scaledDensity=").append(dm.scaledDensity).append('\n')
    }
    append("Night Mode   : ").append(runCatching { night() }.getOrDefault(false)).append('\n')
    append(runCatching { FrameMeter.snapshot() }.getOrDefault("帧率（真机） : 取快照失败")).append('\n')
    append("Switches     : ")
    features.forEach { f ->
        append(f.name).append('=').append(if (f.name in failed) "FAIL" else if (on(f.name)) "ON" else "OFF").append(' ')
    }
    append('\n')
    store()?.let { p ->
        append("Params       : TIME_PATTERN=").append(p.getString(TIME_PATTERN, "HH:mm"))
            .append(" AVATAR_RADIUS=").append(p.getInt(AVATAR_RADIUS, 20))
            .append(" GLASS_BLUR=").append(p.getInt(GLASS_BLUR, 1))
            .append(" BUBBLE_CORNER=").append(p.getInt(BUBBLE_CORNER, 17))
            .append(" BUBBLE_PADDING=").append(p.getInt(BUBBLE_PADDING, 5))
            .append(" SAFE_TOP=").append(p.getInt(SAFE_TOP, 0))
            .append(" SAFE_TOP_SHIFT=").append(p.getInt(SAFE_TOP_SHIFT, 0))
            .append('\n')
    }
    if (failures.isNotEmpty()) {
        append("\n--- [1] Feature Install Failures ---\n")
        failures.forEach { (k, v) ->
            append("✗ ").append(k).append(":\n").append(v).append('\n')
        }
    }
    append("\n--- [2] Hook Invocation Counts (Top 120) ---\n")
    hookCallCounts.entries.sortedByDescending { it.value.get() }.take(120).forEach { (k, v) ->
        append(String.format(Locale.US, "%8d  %s\n", v.get(), k))
    }
    append("\n--- [3] Live Activity & Scene View Hierarchy Snapshots (").append(activitySnapshots.size).append(") ---\n")
    activitySnapshots.forEach { (_, dump) ->
        append(dump).append('\n')
    }
    append("\n--- [4] Qself Events & Hook Samples (").append(qselfEvents.size).append(") ---\n")
    qselfEvents.forEach { append(it).append('\n') }
    append("\n--- [5] UI / AIO / Header / Error QLog Stream (").append(uiQLogs.size).append(") ---\n")
    uiQLogs.forEach { append(it).append('\n') }
    append("\n--- [6] All QQ QLog Stream (").append(allQLogs.size).append(") ---\n")
    allQLogs.forEach { append(it).append('\n') }
    if (includeLogcat) {
        append("\n--- [7] Process logcat -d (last 1800 lines) ---\n")
        append(captureProcessLogcat(1800))
    }
}

private fun captureProcessLogcat(lines: Int): String = runCatching {
    val pid = android.os.Process.myPid().toString()
    val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", lines.toString())
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor(1500, TimeUnit.MILLISECONDS)
    out
}.getOrElse { "logcat capture failed: $it\n" }

// ---- 多次导出的合并与清理：一个进程只产出一个 qself-full-log.txt，重复导出合并去重而不是堆编号副本 ----

/** 导出前清掉 Download 与本应用目录里所有历史 `qself-full-log*.txt`（含分享面板另存出的编号副本）。 */
private fun cleanupPriorExports(ctx: Context): Int {
    var removed = 0
    pendingCleanupConsent = null
    runCatching {
        val resolver = ctx.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        resolver.query(uri, projection, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$LOG_FILE_PREFIX%"), null)
            ?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    val item = ContentUris.withAppendedId(uri, c.getLong(idCol))
                    // 别的应用写进 Download 的副本不能直接删，抛出的是 RecoverableSecurityException：
                    // 记下系统给的授权 IntentSender，写完新文件后弹一次系统授权框，用户点了就真删掉。
                    try {
                        removed += resolver.delete(item, null, null)
                    } catch (e: RecoverableSecurityException) {
                        pendingCleanupConsent = e.userAction.actionIntent.intentSender
                    }
                }
            }
    }
    val dirs = listOfNotNull(
        runCatching { ctx.getExternalFilesDir(null) }.getOrNull(),
        runCatching { ctx.externalCacheDir }.getOrNull(),
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
    )
    for (dir in dirs) {
        runCatching {
            dir.listFiles()?.filter { it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(".txt") }
                ?.forEach { if (it.delete()) removed++ }
        }
    }
    return removed
}

/**
 * @param prebuiltReport 调用方已经合并好的报告；给了就不再读历史文件、不再合第二遍（19MB 的合并要跑好几秒）。
 * @param priorLength 调用方读到的历史文件字节数，-1 表示没有历史文件；只用于写「合并」那一行说明。
 */
private fun writeLogFiles(
    ctx: Context,
    includeLogcat: Boolean,
    prebuiltReport: String? = null,
    priorLength: Int = -1,
): List<String> {
    // 先读上一次的导出用于合并；filesDir 是本应用私有目录，无需任何存储权限即可读。
    val prior = if (prebuiltReport != null) null
    else runCatching { File(ctx.filesDir, LOG_FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()
    val fresh = prebuiltReport
        ?: runCatching { buildFullDiagnosticReport(ctx, includeLogcat = includeLogcat) }.getOrNull()
        ?: return emptyList()
    val merged = if (prebuiltReport != null) fresh
    else runCatching { mergeDiagnosticReports(prior, fresh) }.getOrDefault(fresh)
    val removed = cleanupPriorExports(ctx)
    val knownPrior = prior?.length ?: priorLength
    val mergeNote = if (knownPrior > 0) {
        "已与上一次导出按小节合并去重（上次 $knownPrior 字节）"
    } else {
        "首次导出，无历史文件可合并"
    }
    val content = merged.replace(
        "Qself Full QQ Diagnostic Report\n",
        "Qself Full QQ Diagnostic Report\n" +
            "合并         : $mergeNote；导出前清理历史副本 $removed 个，同名文件只保留 $LOG_FILE_NAME\n",
    )
    val bytes = content.toByteArray(Charsets.UTF_8)
    lastMergeRemoved = removed
    recordQselfEvent("合并导出写入 ${content.length} 字节：$mergeNote，清理历史副本 $removed 个")
    val savedPaths = ArrayList<String>()
    for (dir in listOfNotNull(
        runCatching { ctx.getExternalFilesDir(null) }.getOrNull(),
        runCatching { ctx.externalCacheDir }.getOrNull(),
        runCatching { ctx.filesDir }.getOrNull(),
    )) {
        runCatching {
            val f = File(dir, LOG_FILE_NAME)
            f.writeBytes(bytes)
            savedPaths.add(f.absolutePath)
        }
    }
    runCatching {
        val dl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_FILE_NAME)
        dl.writeBytes(bytes)
        savedPaths.add(dl.absolutePath)
    }
    runCatching {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { itemUri ->
            resolver.openOutputStream(itemUri)?.use { it.write(bytes) }
            savedPaths.add("/sdcard/Download/$LOG_FILE_NAME")
        }
    }
    return savedPaths.distinct()
}

fun exportFullQqLog(ctx: Context, currentActivity: Activity? = null) {
    currentActivity?.let { runCatching { captureActivitySnapshot(it, "button-export") } }
    // 上一次的导出实测已到 19MB：readText + 合并会把 QQ 主线程冻住好几秒（也是「点一下卡住」的来源之一），
    // 整段挪到后台线程，主线程只留界面快照、Toast、授权弹窗和分享面板。
    ioExecutor.execute {
        val prior = runCatching { File(ctx.filesDir, LOG_FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()
        val fresh = runCatching { buildFullDiagnosticReport(ctx, includeLogcat = true) }.getOrNull()
        if (fresh == null) {
            mainHandler.post {
                runCatching { Toast.makeText(ctx, "全量日志构建失败，见 logcat -s QselfDiag", Toast.LENGTH_LONG).show() }
            }
            return@execute
        }
        val fullReport = runCatching { mergeDiagnosticReports(prior, fresh) }.getOrDefault(fresh)
        // 1. 先复制核心诊断摘要+界面树+近期日志至系统剪贴板（限制在 320KB 以内确保 Android Binder 剪贴板零截断抛错）
        val clipText = if (fullReport.length > 320_000) {
            fullReport.take(200_000) + "\n...[中间省略，完整日志见 $LOG_FILE_NAME]...\n" + fullReport.takeLast(110_000)
        } else fullReport
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Qself Full QQ Log", clipText))
        }
        // 2. 只把本次相对上一次导出的新增记录镜像到 LSPosed 日志与 logcat -s QselfDiag，重复导出不再刷屏
        val delta = runCatching { reportDeltaRecords(prior, fullReport) }.getOrDefault(0)
        runCatching {
            if (delta == 0) {
                Log.i("QselfDiag", "[合并导出] 无新增记录，完整累计日志见 /sdcard/Download/$LOG_FILE_NAME")
                xposed.log(Log.INFO, "QselfDiag", "[合并导出] 无新增记录，完整累计日志见 /sdcard/Download/$LOG_FILE_NAME", null)
            } else {
                Log.i("QselfDiag", "[合并导出] 新增 $delta 条记录")
                xposed.log(Log.INFO, "QselfDiag", "[合并导出] 新增 $delta 条记录", null)
            }
        }
        // 3. 写入 Download/qself-full-log.txt 与外部应用目录（已经合并过，不再合第二遍），然后回主线程提示与分享
        val paths = runCatching {
            writeLogFiles(ctx, includeLogcat = false, prebuiltReport = fullReport, priorLength = prior?.length ?: -1)
        }.getOrDefault(emptyList())
        val removed = lastMergeRemoved
        mainHandler.post {
            val primaryPath = paths.firstOrNull { it.contains("Download") } ?: paths.firstOrNull() ?: "剪贴板"
            Toast.makeText(
                ctx,
                "已复制合并后的全量日志到剪贴板，并写入：$primaryPath${if (removed > 0) "（清理历史副本 $removed 个）" else ""}",
                Toast.LENGTH_LONG,
            ).show()
            // Download 里若还有别的应用写入的历史副本，弹一次系统授权框；用户允许后下次导出就彻底只剩一个文件。
            val consent = pendingCleanupConsent
            if (consent != null) {
                pendingCleanupConsent = null
                val act = currentActivity
                if (act != null) runCatching {
                    act.startIntentSenderForResult(consent, 0x5165, null, 0, 0, 0)
                    Toast.makeText(ctx, "Download 里还有别的应用写入的历史副本，请在系统弹窗里允许删除", Toast.LENGTH_LONG).show()
                    return@post
                }
            }
            runCatching {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Qself Full QQ Log (${BuildConfig.VERSION_NAME})")
                    putExtra(Intent.EXTRA_TEXT, clipText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(share, "导出 Qself 全量日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

fun fullQqLogger() {
    recordQselfEvent("fullQqLogger installing...")
    // 捕获未处理崩溃栈并立即落盘
    val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            recordQselfEvent("UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
            if (app != null) writeLogFiles(app, includeLogcat = true)
        }
        prevHandler?.uncaughtException(thread, throwable)
    }
    val qlog = cls("com.tencent.qphone.base.util.QLog")
    // 开启 QLog.isColorLevel()，让 QQ 内部被 if (QLog.isColorLevel()) 包裹的 UI/AIO/顶栏/底栏调试日志全部输出
    qlog.declaredMethods.firstOrNull { it.name == "isColorLevel" && it.parameterTypes.isEmpty() }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = true) { chain ->
            chain.proceed()
            true
        }
    }
    // 拦截 QLog#addLogItem(byte androidLogLevel, String tag, int qlogLevel, String msg, Throwable tr) 汇总全量 QQ 日志
    qlog.declaredMethods.firstOrNull { it.name == "addLogItem" && it.parameterTypes.size == 5 }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("全量日志")) {
                runCatching {
                    val lvByte = (chain.args.getOrNull(0) as? Number)?.toInt() ?: 0
                    val tag = chain.args.getOrNull(1) as? String ?: ""
                    val qLv = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
                    val msg = chain.args.getOrNull(3) as? String ?: ""
                    val tr = chain.args.getOrNull(4) as? Throwable
                    val lvChar = when (lvByte) {
                        3 -> 'D'
                        4 -> 'I'
                        5 -> 'W'
                        6, 7 -> 'E'
                        else -> 'V'
                    }
                    val line = buildString {
                        append(nowStamp()).append(' ').append(lvChar).append('/').append(tag)
                            .append("(L").append(qLv).append(',').append(Thread.currentThread().name).append("): ")
                            .append(msg)
                        if (tr != null) append('\n').append(Log.getStackTraceString(tr))
                    }
                    pushBounded(allQLogs, allQLogCount, MAX_ALL_QLOGS, line)
                    if (lvByte >= 5 || tr != null || uiKeywords.containsMatchIn(tag) || uiKeywords.containsMatchIn(msg)) {
                        pushBounded(uiQLogs, uiQLogCount, MAX_UI_QLOGS, line)
                    }
                }
            }
            chain.proceed()
        }
    }
}
