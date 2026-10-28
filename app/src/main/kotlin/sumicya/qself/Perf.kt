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

/**
 * 每帧耗时归因：只记「哪个环节花了多久」，不进日志、不分配对象，导出时汇总成一行。
 * 帧率只有总数时只能猜是谁拖慢的；有这一行才能指着某一环说「就是它」。
 */
internal const val COST_GLASS = 0
internal const val COST_AVATAR = 1
internal const val COST_LIST = 2
internal const val COST_HEADER = 3

internal object CostMeter {
    private val names = arrayOf("玻璃合成", "头像浮绘", "消息几何", "顶栏")
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

/** 事件出口由宿主注入：帧率统计就不需要 Android 类也能在 JVM 上单测。 */
internal var frameEventSink: ((String) -> Unit)? = null

private val stampFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

/** 小数一律按 US 格式写：别的 locale 会把 8.3 写成 8,3，日志与断言就对不上了。 */
private fun fmt(pattern: String, value: Double): String = String.format(java.util.Locale.US, pattern, value)
private fun fmt(pattern: String, value: Float): String = String.format(java.util.Locale.US, pattern, value)

internal object FrameMeter {
    /** 单测与热重载用：清空累计重新采样。两条用例共用同一个采样器，不重置就会互相污染。 */
    @Synchronized
    fun reset() {
        ring.fill(0f); ringIdx = 0; ringSize = 0; lastNanos = 0L
        frames = 0L; janky = 0L; stalls = 0L; missed = 0L; worst = 0f; sum = 0.0
        gap = 0; wFrames = 0; wJank = 0; wStall = 0; wWorst = 0f; wSum = 0.0; wStart = ""
        windows.clear(); periodMs = FRAME_BUDGET_MS
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
        if (wFrames == 0) wStart = synchronized(stampFormat) { stampFormat.format(java.util.Date()) }
        wFrames++
        wSum += dtMs
        if (dtMs > jankBudget()) wJank++
        if (dtMs > FRAME_STALL_MS) { wStall++; gap = 0 } else gap++
        if (dtMs > wWorst) wWorst = dtMs
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
        wFrames = 0; wJank = 0; wStall = 0; wWorst = 0f; wSum = 0.0; gap = 0; wStart = ""
        if (n < FRAME_WINDOW_MIN || (jank == 0 && stall == 0)) return
        val line = "帧率窗口 $start 帧=$n 平均=${fmt("%.1f", avg)}ms 掉帧=$jank(${fmt("%.0f", jank * 100.0 / n)}%) 卡顿=$stall 最长=${fmt("%.0f", worstMs)}ms"
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
        val cost = CostMeter.line()
        if (cost.isNotEmpty()) return (head + "\n" + cost + windows.joinToString("") { "\n" + it })
        if (windows.isEmpty()) return head
        return head + "\n" + windows.joinToString("\n")
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

    /** 界面画了一帧：唤醒采样。由 `ViewGroup#dispatchDraw` 钩子调用，主线程。 */
    fun noteDraw() {
        drawSignalSeen = true
        drewSinceCallback = true
        @Suppress("UNCHECKED_CAST")
        (waker as? () -> Unit)?.invoke()
    }
}
