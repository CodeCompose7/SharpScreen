package io.sharpscreen.app

/**
 * Measures how evenly frames reach the panel, to tell transport jitter from
 * the frame rate itself. Logs every [WINDOW_NS] on whichever thread records.
 *
 * - transit: (local arrival - host capture). The two clocks differ, so only
 *   the spread is meaningful: each sample is reported relative to the
 *   smallest one in the window (the fastest frame's path).
 * - render: interval between consecutive releaseOutputBuffer(render=true)
 *   calls. At 120 Hz an even stream sits at 8.3 ms; < 4 ms means two frames
 *   landed in one vsync (one is never seen), > 12.5 ms means a vsync showed
 *   the previous frame again.
 */
object PacingDiag {
    private const val WINDOW_NS = 2_000_000_000L
    private val lock = Any()
    private val transit = ArrayList<Long>(512)
    private val render = ArrayList<Long>(512)
    private var lastRenderNs = 0L
    private var windowStartNs = 0L

    fun recordArrival(
        localArrivalNs: Long,
        hostCaptureNs: Long,
    ) {
        if (hostCaptureNs <= 0L) return
        synchronized(lock) { transit.add(localArrivalNs - hostCaptureNs) }
    }

    fun recordRender(nowNs: Long) {
        val line: String?
        synchronized(lock) {
            if (lastRenderNs != 0L) render.add(nowNs - lastRenderNs)
            lastRenderNs = nowNs
            if (windowStartNs == 0L) windowStartNs = nowNs
            line =
                if (nowNs - windowStartNs >= WINDOW_NS) {
                    summarize(transit, render).also {
                        transit.clear()
                        render.clear()
                        windowStartNs = nowNs
                    }
                } else {
                    null
                }
        }
        line?.let { DiagLog.log("PacingDiag", it) }
    }

    fun reset() =
        synchronized(lock) {
            transit.clear()
            render.clear()
            lastRenderNs = 0L
            windowStartNs = 0L
        }

    /** Percentile (0..100) of an already sorted list, nearest rank. */
    fun percentile(
        sorted: List<Long>,
        p: Int,
    ): Long {
        if (sorted.isEmpty()) return 0
        val rank = ((p / 100.0) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    fun summarize(
        transitNs: List<Long>,
        renderNs: List<Long>,
    ): String {
        fun ms(ns: Long) = "%.1f".format(ns / 1_000_000.0)
        val r = renderNs.sorted()
        val doubled = r.count { it < 4_000_000L }
        val held = r.count { it > 12_500_000L }
        val renderPart =
            if (r.isEmpty()) {
                "render: none"
            } else {
                "render ${r.size + 1} frames, interval ms p10=${ms(percentile(r, 10))} " +
                    "p50=${ms(percentile(r, 50))} p90=${ms(percentile(r, 90))} max=${ms(r.last())}, " +
                    "<4ms=${100 * doubled / r.size}% >12.5ms=${100 * held / r.size}%"
            }
        if (transitNs.isEmpty()) return "$renderPart; transit: no host timestamps"
        val base = transitNs.min()
        val t = transitNs.map { it - base }.sorted()
        return "$renderPart; transit jitter ms p50=${ms(percentile(t, 50))} " +
            "p90=${ms(percentile(t, 90))} p99=${ms(percentile(t, 99))} max=${ms(t.last())}"
    }
}
