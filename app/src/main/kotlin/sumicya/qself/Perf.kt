package sumicya.qself

// 「滑动卡不卡」不能靠我觉得，也不能靠自评：用 Choreographer 记真实帧间隔，
// 导出日志里直接给出真机帧数、平均帧长、掉帧率和最长帧。测出来才算数，测不出来就别改。

internal const val FRAME_BUDGET_MS = 16.7f   // 没测出刷新率时的兜底（60Hz 一帧）
internal const val FRAME_MIN_MS = 1f         // 比这还短的间隔不是帧，是噪声
internal const val FRAME_PERIOD_MIN = 4f     // 刷新周期只可能在 250Hz ~ 40Hz 之间，出了这个区间就是抖动
internal const val FRAME_PERIOD_MAX = 25f
internal const val FRAME_PERIOD_EVERY = 120L // 每采 120 帧重估一次刷新周期
internal const val FRAME_STALL_MS = 50f      // 肉眼能明确感知的卡顿
internal const val FRAME_RING = 600          // 环形缓冲：最近 600 帧（约 10 秒）
internal const val FRAME_WINDOW_GAP = 30     // 连续 30 帧不卡就收口一段「操作窗口」
internal const val FRAME_WINDOW_MIN = 20     // 短于此的窗口不记（零星两帧不算一次操作）
internal const val FRAME_MAX_WINDOWS = 12
internal const val FRAME_GESTURE_MIN = 20    // 少于 20 帧的触摸不算一次滑动（点一下也画几帧，不能刷屏）
internal const val TOUCH_DOWN = 0            // MotionEvent.ACTION_DOWN，用原始值让 JVM 单测不必碰 android 类
internal const val TOUCH_UP = 1              // MotionEvent.ACTION_UP
internal const val TOUCH_CANCEL = 3          // MotionEvent.ACTION_CANCEL

/**
 * 每帧耗时归因：只记「哪个环节花了多久」，不进日志、不分配对象，导出时汇总成一行。
 * 帧率只有总数时只能猜是谁拖慢的；有这一行才能指着某一环说「就是它」。
 */
internal const val COST_GLASS = 0
internal const val COST_LIST = 1
internal const val COST_HEADER = 2

internal object CostMeter {
    private val names = arrayOf("玻璃合成", "消息几何", "顶栏")
    private val nanos = LongArray(names.size)
    private val counts = LongArray(names.size)

    /** 主线程专用：这批计数只在绘制/布局里加减，不跨线程。 */
    fun add(slot: Int, startNanos: Long) {
        if (slot !in names.indices) return
        nanos[slot] += System.nanoTime() - startNanos
        counts[slot]++
    }

    fun reset() { nanos.fill(0); counts.fill(0) }

    /** 取走本段统计并清零：给「最近一段」的归因行用，均值只算这一段。 */
    fun take(): String {
        val text = line()
        reset()
        return text
    }

    fun line(): String {
        val parts = names.indices.filter { counts[it] > 0 }.map { i ->
            "「${names[i]}」${fmt("%.2f", nanos[i] / 1e6 / counts[i])}ms/次（${counts[i]} 次）"
        }
        return if (parts.isEmpty()) "" else "耗时归因（主线程）：" + parts.joinToString("；")
    }
}

internal const val MSG_SLOW_MS = 32.0        // 单条主线程消息超过它就算一次「卡」
internal const val MSG_SAMPLE_STEP_MS = 20L  // 看门狗巡一次的间隔
internal const val MSG_SAMPLE_GAP_MS = 80.0  // 同一条卡消息两次抓栈的最小间隔（毫秒）
internal const val MSG_STACK_FRAMES = 4      // 一条栈只留前 4 个业务帧，长栈读不动

/**
 * 把「一条消息跨了几帧」按真实时间切开：每纳秒只记一次，且每帧拿到的部分不超过它真正占用的时间。
 * 单独成类是为了能脱离 Android 与时钟做单测 —— 灌进时刻表就能验算。
 */
internal class ThreadAccounting {
    private var active = false       // 不能拿 startNanos == 0 当空闲：单测与 uptime 基准里 0 是合法时刻
    private var startNanos = 0L
    private var attributed = 0L      // 这条在跑的消息已经交给帧窗口的纳秒数

    fun reset() { active = false; startNanos = 0L; attributed = 0L }

    fun start(now: Long) { active = true; startNanos = now; attributed = 0L }

    /** 取走「已经过去但还没记过」的一段；没有在跑的消息就返回 0。 */
    fun drain(now: Long): Long {
        if (!active) return 0L
        val elapsed = (now - startNanos).coerceAtLeast(0L)
        val fresh = (elapsed - attributed).coerceAtLeast(0L)
        attributed = elapsed
        return fresh
    }

    /** 消息结束：返回还没记过的那一段，并把状态清空。 */
    fun finish(now: Long): Long {
        val fresh = drain(now)
        active = false
        startNanos = 0L
        attributed = 0L
        return fresh
    }
}

/**
 * 主线程消息计时 + 卡顿当场抓栈。
 *
 * 帧间隔只说明「这一帧没出来」，说不清「谁不让它出来」：主线程真的在跑代码，和主线程闲着但渲染管线堵住，
 * 在帧率数字上长得一模一样。这里量两件事：
 *  ① 每帧主线程花在消息里多少毫秒 —— 和帧间隔一比就知道是不是主线程瓶颈；
 *  ② 超过 [MSG_SLOW_MS] 的消息，由看门狗线程[MSG_SAMPLE_STEP_MS]一巡，当场抓主线程栈，
 *     把「卡 300ms」写成「卡在 com.tencent.xxx#yyy ← ...」。
 * 热路径只多两次 nanoTime 和两个字段写入；字符串只在慢消息上才拼。
 */
internal object MainThreadMeter {
    @Volatile private var startNanos = 0L          // 0 = 两条消息之间，看门狗据此判断有没有卡着
    @Volatile private var currentWhat = 0
    @Volatile private var currentCallback: Class<*>? = null

    private var depth = 0                          // 只在主线程读写：嵌套 Looper.loop 只算最外层
    private val split = ThreadAccounting()         // 跨帧消息按真实时间切开，每纳秒只记一次
    private var totalNanos = 0L
    private var totalCount = 0
    private var slowCount = 0
    private var worstMs = 0.0
    private var worstWhere = ""

    private var frameNanos = 0L                    // 上一帧以来的一段，喂给帧率窗口
    private var frameCount = 0

    private var lastSampleMs = 0.0
    private val samples = ArrayDeque<String>(MSG_STACK_FRAMES)
    private var watchdog: Thread? = null

    fun reset() {
        startNanos = 0L; currentWhat = 0; currentCallback = null; split.reset()
        depth = 0; totalNanos = 0; totalCount = 0; slowCount = 0; worstMs = 0.0; worstWhere = ""
        frameNanos = 0; frameCount = 0; lastSampleMs = 0.0; samples.clear()
    }

    /** 一条消息开始。不分配对象：只存 what 与回调类，名字等真卡了再拼。 */
    fun enter(what: Int, callback: Class<*>?) {
        if (depth == 0) {
            currentWhat = what
            currentCallback = callback
            val now = System.nanoTime()
            startNanos = now
            split.start(now)
        }
        depth++
    }

    fun exit() {
        if (depth == 0) return
        depth--
        if (depth > 0) return
        val t0 = startNanos
        startNanos = 0L
        if (t0 == 0L) return
        val now = System.nanoTime()
        val totalMs = (now - t0) / 1e6
        // 已经被前面几帧取走的那部分不再重复计，只补剩下的。
        val freshNanos = split.finish(now)
        val where = if (totalMs >= MSG_SLOW_MS) messageLabel(currentWhat, currentCallback) else ""
        currentCallback = null
        if (freshNanos <= 0L) { totalCount++; if (totalMs > worstMs) { worstMs = totalMs; worstWhere = where }; if (totalMs >= MSG_SLOW_MS) slowCount++ }
        else record(freshNanos / 1e6, where, totalMs)
    }

    /** 纯函数入口：单测直接灌数，不碰计时与 Android 类。 */
    fun record(ms: Double, where: String) = record(ms, where, ms)

    /** [freshMs] 记进当前帧，[totalMs] 只用于「最慢一条」的判定（跨帧消息两帧都不能算满）。 */
    private fun record(freshMs: Double, where: String, totalMs: Double) {
        val nanos = (freshMs * 1_000_000).toLong()
        totalNanos += nanos
        totalCount++
        frameNanos += nanos
        frameCount++
        if (totalMs > worstMs) { worstMs = totalMs; worstWhere = where }
        if (totalMs >= MSG_SLOW_MS) slowCount++
    }

    /**
     * 取走上一帧以来的一段（[0]=毫秒，[1]=条数）并清零。不分配对象。
     *
     * 正在跑的那条消息也要切一刀：它在两帧之间持续占着主线程，原来只在 exit 时一次性记进
     * 「结束的那一帧」，一条 200ms 的消息会让那一帧的主线程合计远超帧间隔（真机日志里出现过
     * 「均 75.6ms/帧」配 8.5ms 的帧间隔，就是这条）。现在把已经过掉的部分先记给当前这一帧，
     * exit 时只补剩下的部分，每纳秒只进一次合计。
     */
    fun take(into: DoubleArray) {
        val fresh = split.drain(System.nanoTime())
        into[0] = (frameNanos + fresh) / 1e6
        into[1] = frameCount.toDouble()
        frameNanos = 0L
        frameCount = 0
    }

    /** 导出用的一行：有数据才写，没装钩子就返回空串。 */
    fun summary(): String {
        if (totalCount == 0) return ""
        val sb = StringBuilder()
        sb.append("主线程 : 共 ${totalCount} 条消息，平均每帧 ")
            .append(fmt("%.1f", totalNanos / 1e6 / totalCount)).append("ms 一条")
        if (slowCount > 0) {
            sb.append("；≥").append(fmt("%.0f", MSG_SLOW_MS)).append("ms 的 $slowCount 条，最慢 ")
                .append(fmt("%.0f", worstMs)).append("ms 在 ").append(worstWhere)
        }
        if (samples.isNotEmpty()) sb.append('\n').append("卡顿栈 : ").append(samples.joinToString("\n         "))
        return sb.toString()
    }

    private fun messageLabel(what: Int, callback: Class<*>?): String =
        (callback?.name ?: "(无回调)") + "#what=" + what

    /**
     * 看门狗：主线程有消息卡着就抓栈。
     * 用独立线程而不是在主线程里抓 —— 主线程正卡着，它自己没机会抓自己。
     */
    fun startWatchdog() {
        if (watchdog != null) return
        val main = android.os.Looper.getMainLooper().thread
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(MSG_SAMPLE_STEP_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val t0 = startNanos
                if (t0 == 0L) { lastSampleMs = 0.0; continue }
                val ms = (System.nanoTime() - t0) / 1e6
                if (ms < MSG_SLOW_MS || ms - lastSampleMs < MSG_SAMPLE_GAP_MS) continue
                lastSampleMs = ms
                val frames = runCatching { main.stackTrace }.getOrDefault(emptyArray())
                val where = culprit(frames)
                if (samples.size >= MSG_STACK_FRAMES) samples.removeFirst()
                if (where !in samples) samples.addLast(where)
                frameEventSink?.invoke("主线程卡 ${fmt("%.0f", ms)}ms 在 $where")
            }
        }, "Qself-StallWatch")
        thread.isDaemon = true
        thread.start()
        watchdog = thread
    }
}

/**
 * 装主线程消息钩子。counted = false：这条钩子每条消息都会走一遍（每秒上千次），
 * 不能进事件流的钩子计数，否则事件流被它刷满，真正要看的钩子反倒被挤掉。
 * 只认主线程：后台 Handler 的消息与帧率无关，混进来还会把嵌套深度搅乱。
 */
fun stallWatch() {
    val main = android.os.Looper.getMainLooper().thread
    val dispatch = android.os.Handler::class.java
        .getDeclaredMethod("dispatchMessage", android.os.Message::class.java)
    hook(dispatch, gated = false, counted = false) { chain ->
        if (Thread.currentThread() !== main) return@hook chain.proceed()
        val msg = chain.getArg(0) as? android.os.Message
        MainThreadMeter.enter(msg?.what ?: -1, msg?.callback?.javaClass)
        try {
            chain.proceed()
        } finally {
            MainThreadMeter.exit()
        }
    }
    MainThreadMeter.startWatchdog()
}

/** 从栈里挑出真正干活的帧：跳掉 android./java./kotlin. 这些谁都会过的壳。纯函数，可单测。 */
internal fun culprit(frames: Array<StackTraceElement>): String {
    val out = ArrayList<String>(MSG_STACK_FRAMES)
    for (f in frames) {
        val c = f.className
        if (c.startsWith("android.") || c.startsWith("java.") || c.startsWith("kotlin.") ||
            c.startsWith("dalvik.") || c.startsWith("com.android.") || c.startsWith("sun.")
        ) continue
        out.add(c + "#" + f.methodName)
        if (out.size >= MSG_STACK_FRAMES) break
    }
    return if (out.isEmpty()) "(栈里全是系统帧)" else out.joinToString(" ← ")
}

/** 事件出口由宿主注入：帧率统计就不需要 Android 类也能在 JVM 上单测。 */
internal var frameEventSink: ((String) -> Unit)? = null

private val stampFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

/** 小数一律按 US 格式写：别的 locale 会把 8.3 写成 8,3，日志与断言就对不上了。 */
private fun fmt(pattern: String, value: Double): String = String.format(java.util.Locale.US, pattern, value)
private fun fmt(pattern: String, value: Float): String = String.format(java.util.Locale.US, pattern, value)
private fun frameStamp(): String = synchronized(stampFormat) { stampFormat.format(java.util.Date()) }

internal object FrameMeter {
    /** 单测与热重载用：清空累计重新采样。两条用例共用同一个采样器，不重置就会互相污染。 */
    @Synchronized
    fun reset() {
        ring.fill(0f); ringIdx = 0; ringSize = 0; lastNanos = 0L
        frames = 0L; janky = 0L; stalls = 0L; missed = 0L; worst = 0f; sum = 0.0
        gap = 0; wFrames = 0; wJank = 0; wStall = 0; wWorst = 0f; wSum = 0.0; wStart = ""
        windows.clear(); periodMs = FRAME_BUDGET_MS
        gOpen = false; gFrames = 0; gJank = 0; gStall = 0; gWorst = 0f; gSum = 0.0; gStart = ""; gLast = ""
        wMainMs = 0.0; wMainN = 0.0
        MainThreadMeter.reset()
        reportFrames = 0; reportSum = 0.0
        CostMeter.reset()
    }

    private val ring = FloatArray(FRAME_RING)
    private var ringIdx = 0
    private var ringSize = 0
    private var lastNanos = 0L
    private var frames = 0L
    private var janky = 0L
    private var stalls = 0L
    private var worst = 0f
    private var missed = 0L
    private var sum = 0.0
    private var wStart = ""
    private var wFrames = 0
    private var wJank = 0
    private var wStall = 0
    private var wWorst = 0f
    private var wSum = 0.0
    private var gap = 0
    private var periodMs = FRAME_BUDGET_MS   // 这台机器的刷新周期，测出来之前按 60Hz 兜底
    private val windows = ArrayDeque<String>(FRAME_MAX_WINDOWS)
    private var reportFrames = 0
    private var reportSum = 0.0
    // 手势窗口：按下到抬起之间单独攒一份，抬手无条件写一行，滑动一次就有一次可读帧率。
    private var gOpen = false
    private var gFrames = 0
    private var gJank = 0
    private var gStall = 0
    private var gWorst = 0f
    private var gSum = 0.0
    private var gStart = ""
    private var gLast = ""
    private val msgDrain = DoubleArray(2)      // [0]=毫秒 [1]=条数，每帧取一次，不分配对象
    private var wMainMs = 0.0
    private var wMainN = 0.0
    @Volatile private var installed = false
    @Volatile private var drewSinceCallback = false
    @Volatile private var drawSignalSeen = false
    @Volatile private var loopPosted = false

    /** 记一帧的真实间隔（毫秒）。纯计算，JVM 单测可以直接灌数。 */
    @Synchronized
    fun feed(dtMs: Float) {
        // 负数与超过 1 秒的间隔都不算：那是从后台回前台的第一帧，不是掉帧。
        if (dtMs <= 0f || dtMs > 1000f) return
        // 刷新周期从观测里取：这台机器是 120Hz 时，16.7ms 的预算会把一半掉帧漏掉。
        if (frames % FRAME_PERIOD_EVERY == 0L) refreshPeriod()
        // 上一帧以来主线程在消息里花了多少：和帧间隔一比，就知道这帧是主线程忙掉的还是等出来的。
        MainThreadMeter.take(msgDrain)
        wMainMs += msgDrain[0]
        wMainN += msgDrain[1]
        frames++
        sum += dtMs
        ring[ringIdx] = dtMs
        ringIdx = (ringIdx + 1) % FRAME_RING
        if (ringSize < FRAME_RING) ringSize++
        if (dtMs > jankBudget()) janky++
        // 掉帧只数帧数会低估：120Hz 上一帧 25ms 是错过两次同步，不是一个坏帧。
        missed += kotlin.math.round(dtMs / periodMs).toInt().coerceAtLeast(1) - 1
        if (dtMs > FRAME_STALL_MS) stalls++
        if (dtMs > worst) worst = dtMs
        if (wFrames == 0) wStart = frameStamp()
        wFrames++
        wSum += dtMs
        if (dtMs > jankBudget()) wJank++
        if (dtMs > wWorst) wWorst = dtMs
        if (gOpen) {
            gFrames++
            gSum += dtMs
            if (dtMs > jankBudget()) gJank++
            if (dtMs > FRAME_STALL_MS) gStall++
            if (dtMs > gWorst) gWorst = dtMs
        }
        if (dtMs > FRAME_STALL_MS) {
            wStall++
            gap = 0
        } else {
            gap++
            // 卡顿段一结束就收口：一直滑的时候 stall 会把 gap 反复清零，等「连续 30 帧不卡」再收口等于永远等不到，
            // 真机日志里最卡的那几段就是这样丢的（导出的窗口全是 帧=30 掉帧=1 的静止画面）。
            // 窗口至少有 FRAME_WINDOW_MIN 帧才值得记，否则轻度卡顿会把窗口切成一堆碎片。
            if (wStall > 0 && wFrames >= FRAME_WINDOW_MIN) closeWindow()
        }
        // 每 60 帧吐一行归因：帧间隔归帧间隔，各环节花多久归各环节，谁在吃预算一眼可见。
        reportFrames++
        reportSum += dtMs
        if (reportFrames >= 60) {
            val avg = reportSum / reportFrames
            reportFrames = 0
            reportSum = 0.0
            val cost = CostMeter.take()
            frameEventSink?.invoke(
                "帧耗时 最近 60 帧 ${fmt("%.1f", avg)}ms（${fmt("%.0f", 1000.0 / avg)}fps）" +
                    if (cost.isEmpty()) "" else "；" + cost,
            )
        }
        if (gap >= FRAME_WINDOW_GAP) closeWindow()
    }

    /**
     * 重估刷新周期：取环缓冲里落在合理区间的帧间隔的 10 分位。
     * 用最小值不行 —— Choreographer 偶尔会给出 1~5ms 的抖动，最小值一带偏，
     * 掉帧线就跟着塌到 8ms 以下，8.2ms 的正常帧会被整片判成掉帧（真机日志里出现过 掉帧 48%）。
     */
    private fun refreshPeriod() {
        val vals = ArrayList<Float>(ringSize)
        for (i in 0 until ringSize) {
            val v = ring[i]
            if (v in FRAME_PERIOD_MIN..FRAME_PERIOD_MAX) vals.add(v)
        }
        if (vals.isEmpty()) return
        vals.sort()
        periodMs = vals[(vals.size * 0.1f).toInt().coerceIn(0, vals.size - 1)]
    }

    /** 掉帧线：错过了一次垂直同步就算。60Hz 约 25ms，120Hz 约 12.5ms。 */
    private fun jankBudget(): Float = periodMs * 1.5f

    /** 观测到的刷新周期，测不出来就用 60Hz 兜底。 */
    @Synchronized fun period(): Float = periodMs

    private fun closeWindow() {
        val n = wFrames
        val jank = wJank
        val stall = wStall
        val worstMs = wWorst
        val avg = if (n > 0) wSum / n else 0.0
        val start = wStart
        val mainMs = wMainMs
        val mainN = wMainN
        wFrames = 0; wJank = 0; wStall = 0; wWorst = 0f; wSum = 0.0; gap = 0; wStart = ""
        wMainMs = 0.0; wMainN = 0.0
        if (n < FRAME_WINDOW_MIN || (jank == 0 && stall == 0)) return
        val main = if (mainN > 0) " 主线程=均${fmt("%.1f", mainMs / n)}ms/帧（${mainN.toInt()} 条消息）" else ""
        val line = "帧率窗口 $start 帧=$n 平均=${fmt("%.1f", avg)}ms 掉帧=$jank(${fmt("%.0f", jank * 100.0 / n)}%) 卡顿=$stall 最长=${fmt("%.0f", worstMs)}ms$main"
        while (windows.size >= FRAME_MAX_WINDOWS) windows.removeFirst()
        windows.addLast(line)
        // 只把「真有卡顿」的窗口写进 Qself 事件流，正常滑动不刷屏。
        if (stall > 0) frameEventSink?.invoke(line)
    }

    /** 导出日志用的一段文字：累计统计 + 最近 600 帧 + 最近若干次卡顿窗口。 */
    @Synchronized
    fun snapshot(): String {
        val recent = (0 until ringSize).map { ring[(ringIdx - ringSize + it + FRAME_RING * 2) % FRAME_RING] }
        val rAvg = if (recent.isNotEmpty()) recent.average() else 0.0
        val rJank = recent.count { it > jankBudget() }
        val head = if (frames == 0L) "帧率（真机） : 尚未采到帧（Choreographer 未安装或进程未绘制）" else buildString {
            append("帧率（真机） : 累计 $frames 帧，平均 ${fmt("%.1f", sum / frames)}ms，")
            append("掉帧 $janky（${fmt("%.0f", janky * 100.0 / frames)}%，错过同步 $missed 次），卡顿 $stalls，")
            append("最长 ${fmt("%.0f", worst)}ms；帧周期 ${fmt("%.1f", period())}ms（约 ${fmt("%.0f", 1000f / period())}Hz），")
            append("最近 $ringSize 帧平均 ${fmt("%.1f", rAvg)}ms、掉帧 $rJank")
        }
        // 「最近手势」放头部：滑动一次就有一行，进程重启前也能看到上一个进程留下的结论在事件流里。
        val sb = StringBuilder(head)
        val main = MainThreadMeter.summary()
        if (main.isNotEmpty()) sb.append('\n').append(main)
        sb.append('\n').append("最近手势 : ").append(
            gLast.ifEmpty { "本进程还没有滑动记录（按下到抬起算一段，抬手即写入事件流）" })
        val cost = CostMeter.line()
        if (cost.isNotEmpty()) sb.append('\n').append(cost)
        windows.forEach { sb.append('\n').append(it) }
        return sb.toString()
    }

    /** 主线程装一次，之后每帧只记一个时间戳，开销可忽略。 */
    fun install() {
        if (installed) return
        val looper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == looper) attach() else android.os.Handler(looper).post { attach() }
    }

    /**
     * 只在界面真的画了一帧时才取下一帧。
     *
     * 原来的循环无论界面动不动都每帧重投一次回调：屏幕静止时也把进程钉在最高刷新率上，
     * 真机日志里 14 分钟攒出 101132 帧（14 × 60 × 122 ≈ 102000，正好等于「一直空转」），
     * 「累计帧数」因此不是画了多少帧，而是跑了多久 —— 数字好看，但不是事实。
     * 现在由 [noteDraw] 在真正绘制时唤醒，空闲就停；没接过绘制通知时退回老的自循环，避免依赖别的钩子。
     */
    private fun attach() {
        if (installed) return
        installed = true
        val choreographer = android.view.Choreographer.getInstance()
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                loopPosted = false
                val last = lastNanos
                lastNanos = frameTimeNanos
                if (last != 0L) feed((frameTimeNanos - last) / 1_000_000f)
                val drew = drewSinceCallback
                drewSinceCallback = false
                // 画过就接着取下一帧；没画过且已经能从绘制钩子收到通知，就停下来等下一次绘制。
                if (drew || !drawSignalSeen) {
                    loopPosted = true
                    choreographer.postFrameCallback(this)
                } else {
                    lastNanos = 0L   // 空闲后的第一帧间隔不是帧耗时，不能算进去
                }
            }
        }
        // 唤醒器存成 Any：JVM 单测里没有 Choreographer，字段带上 FrameCallback 类型会在类加载时就炸。
        waker = { if (!loopPosted) { loopPosted = true; choreographer.postFrameCallback(callback) } }
        loopPosted = true
        choreographer.postFrameCallback(callback)
    }

    @Volatile private var waker: Any? = null

    /**
     * 触摸只用来切「手势窗口」：按下开一段，抬起或取消收一段。
     * 每一段都无条件写一行到事件流——要回答的是「我刚滑这一下多少 fps」，
     * 不能靠「有掉帧才记、还要连续 30 帧不卡才收口」的窗口碰运气。
     */
    @Synchronized
    fun noteTouch(action: Int) {
        when (action) {
            TOUCH_DOWN -> {
                gOpen = true
                gFrames = 0; gJank = 0; gStall = 0; gWorst = 0f; gSum = 0.0
                gStart = frameStamp()
            }
            TOUCH_UP, TOUCH_CANCEL -> {
                if (!gOpen) return
                gOpen = false
                if (gFrames < FRAME_GESTURE_MIN) return
                val avg = gSum / gFrames
                gLast = "$gStart-${frameStamp()} 帧=$gFrames 平均=${fmt("%.1f", avg)}ms（${fmt("%.0f", 1000.0 / avg)}fps）" +
                    " 掉帧=$gJank(${fmt("%.0f", gJank * 100.0 / gFrames)}%) 卡顿=$gStall 最长=${fmt("%.0f", gWorst)}ms"
                frameEventSink?.invoke("帧率手势 $gLast")
            }
        }
    }

    /** 界面画了一帧：唤醒采样。由 `ViewGroup#dispatchDraw` 钩子调用，主线程。 */
    fun noteDraw() {
        drawSignalSeen = true
        drewSinceCallback = true
        @Suppress("UNCHECKED_CAST")
        (waker as? () -> Unit)?.invoke()
    }
}
