package sumicya.qself

// 「滑动卡不卡」不能靠我觉得，也不能靠自评：用 Choreographer 记真实帧间隔，
// 导出日志里直接给出真机帧数、平均帧长、掉帧率和最长帧。测出来才算数，测不出来就别改。

internal const val FRAME_BUDGET_MS = 16.7f   // 60Hz 下一帧的预算；超过就算掉帧
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
    private var sum = 0.0
    private var wStart = ""
    private var wFrames = 0
    private var wJank = 0
    private var wStall = 0
    private var wWorst = 0f
    private var wSum = 0.0
    private var gap = 0
    private val windows = ArrayDeque<String>(FRAME_MAX_WINDOWS)
    @Volatile private var installed = false

    /** 记一帧的真实间隔（毫秒）。纯计算，JVM 单测可以直接灌数。 */
    @Synchronized
    fun feed(dtMs: Float) {
        // 负数与超过 1 秒的间隔都不算：那是从后台回前台的第一帧，不是掉帧。
        if (dtMs <= 0f || dtMs > 1000f) return
        frames++
        sum += dtMs
        ring[ringIdx] = dtMs
        ringIdx = (ringIdx + 1) % FRAME_RING
        if (ringSize < FRAME_RING) ringSize++
        if (dtMs > FRAME_BUDGET_MS) janky++
        if (dtMs > FRAME_STALL_MS) stalls++
        if (dtMs > worst) worst = dtMs
        if (wFrames == 0) wStart = synchronized(stampFormat) { stampFormat.format(java.util.Date()) }
        wFrames++
        wSum += dtMs
        if (dtMs > FRAME_BUDGET_MS) wJank++
        if (dtMs > FRAME_STALL_MS) { wStall++; gap = 0 } else gap++
        if (dtMs > wWorst) wWorst = dtMs
        if (gap >= FRAME_WINDOW_GAP) closeWindow()
    }

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
        val rJank = recent.count { it > FRAME_BUDGET_MS }
        val head = if (frames == 0L) "帧率（真机） : 尚未采到帧（Choreographer 未安装或进程未绘制）" else buildString {
            append("帧率（真机） : 累计 $frames 帧，平均 ${"%.1f".format(sum / frames)}ms，")
            append("掉帧 $janky（${"%.0f".format(janky * 100.0 / frames)}%），卡顿 $stalls，")
            append("最长 ${"%.0f".format(worst)}ms；最近 $ringSize 帧平均 ${"%.1f".format(rAvg)}ms、掉帧 $rJank")
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
