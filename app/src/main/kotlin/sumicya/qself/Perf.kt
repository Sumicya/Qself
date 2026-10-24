package sumicya.qself

// 「滑动卡不卡」不能靠我觉得，也不能靠自评：用 Choreographer 记真实帧间隔，
// 导出日志里直接给出真机帧数、平均帧长、掉帧率和最长帧。测出来才算数，测不出来就别改。

internal const val FRAME_BUDGET_MS = 16.7f   // 没测出刷新率时的兜底（60Hz 一帧）
internal const val FRAME_MIN_MS = 1f         // 比这还短的间隔不是帧，是噪声
internal const val FRAME_STALL_MS = 50f      // 肉眼能明确感知的卡顿
internal const val FRAME_RING = 600          // 环形缓冲：最近 600 帧（约 10 秒）
internal const val FRAME_WINDOW_GAP = 30     // 连续 30 帧不卡就收口一段「操作窗口」
internal const val FRAME_WINDOW_MIN = 20     // 短于此的窗口不记（零星两帧不算一次操作）
internal const val FRAME_MAX_WINDOWS = 12

/** 事件出口由宿主注入：帧率统计就不需要 Android 类也能在 JVM 上单测。 */
internal var frameEventSink: ((String) -> Unit)? = null

private val stampFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

internal object FrameMeter {
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
    private var minDt = Float.NaN   // 观测到的最短帧间隔 = 这台机器的刷新周期
    private val windows = ArrayDeque<String>(FRAME_MAX_WINDOWS)
    @Volatile private var installed = false

    /** 记一帧的真实间隔（毫秒）。纯计算，JVM 单测可以直接灌数。 */
    @Synchronized
    fun feed(dtMs: Float) {
        // 负数与超过 1 秒的间隔都不算：那是从后台回前台的第一帧，不是掉帧。
        if (dtMs <= 0f || dtMs > 1000f) return
        // 刷新周期从观测里取：这台机器是 120Hz 时 16.7ms 的预算会把一半掉帧漏掉。
        if (dtMs >= FRAME_MIN_MS && (minDt.isNaN() || dtMs < minDt)) minDt = dtMs
        if (frames % FRAME_RING == 0L) {
            var m = Float.NaN
            for (i in 0 until ringSize) {
                val v = ring[i]
                if (v >= FRAME_MIN_MS && (m.isNaN() || v < m)) m = v
            }
            if (!m.isNaN()) minDt = m
        }
        frames++
        sum += dtMs
        ring[ringIdx] = dtMs
        ringIdx = (ringIdx + 1) % FRAME_RING
        if (ringSize < FRAME_RING) ringSize++
        if (dtMs > jankBudget()) janky++
        // 掉帧只数帧数会低估：120Hz 上一帧 25ms 是错过两次同步，不是一个坏帧。
        missed += (dtMs / jankBudget() * 1.5f).toInt().coerceAtLeast(1) - 1
        if (dtMs > FRAME_STALL_MS) stalls++
        if (dtMs > worst) worst = dtMs
        if (wFrames == 0) wStart = synchronized(stampFormat) { stampFormat.format(java.util.Date()) }
        wFrames++
        wSum += dtMs
        if (dtMs > jankBudget()) wJank++
        if (dtMs > FRAME_STALL_MS) { wStall++; gap = 0 } else gap++
        if (dtMs > wWorst) wWorst = dtMs
        if (gap >= FRAME_WINDOW_GAP) closeWindow()
    }

    /** 掉帧线：错过了一次垂直同步就算。60Hz 约 25ms，120Hz 约 12.5ms。 */
    private fun jankBudget(): Float = (if (minDt.isNaN() || minDt < FRAME_MIN_MS) FRAME_BUDGET_MS else minDt) * 1.5f

    /** 观测到的刷新周期，测不出来就用 60Hz 兜底。 */
    @Synchronized fun period(): Float = if (minDt.isNaN() || minDt < FRAME_MIN_MS) FRAME_BUDGET_MS else minDt

    private fun closeWindow() {
        val n = wFrames
        val jank = wJank
        val stall = wStall
        val worstMs = wWorst
        val avg = if (n > 0) wSum / n else 0.0
        val start = wStart
        wFrames = 0; wJank = 0; wStall = 0; wWorst = 0f; wSum = 0.0; gap = 0; wStart = ""
        if (n < FRAME_WINDOW_MIN || (jank == 0 && stall == 0)) return
        val line = "帧率窗口 $start 帧=$n 平均=${"%.1f".format(avg)}ms 掉帧=$jank(${"%.0f".format(jank * 100.0 / n)}%) 卡顿=$stall 最长=${"%.0f".format(worstMs)}ms"
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
            append("帧率（真机） : 累计 $frames 帧，平均 ${"%.1f".format(sum / frames)}ms，")
            append("掉帧 $janky（${"%.0f".format(janky * 100.0 / frames)}%，错过同步 $missed 次），卡顿 $stalls，")
            append("最长 ${"%.0f".format(worst)}ms；帧周期 ${"%.1f".format(period())}ms（约 ${"%.0f".format(1000f / period())}Hz），")
            append("最近 $ringSize 帧平均 ${"%.1f".format(rAvg)}ms、掉帧 $rJank")
        }
        if (windows.isEmpty()) return head
        return head + "\n" + windows.joinToString("\n")
    }

    /** 主线程装一次，之后每帧只记一个时间戳，开销可忽略。 */
    fun install() {
        if (installed) return
        val looper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == looper) attach() else android.os.Handler(looper).post { attach() }
    }

    private fun attach() {
        if (installed) return
        installed = true
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                android.view.Choreographer.getInstance().postFrameCallback(this)
                val last = lastNanos
                lastNanos = frameTimeNanos
                if (last != 0L) feed((frameTimeNanos - last) / 1_000_000f)
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(cb)
    }
}
