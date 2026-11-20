package io.sharpscreen.app

import android.os.Build
import android.view.Choreographer
import androidx.annotation.RequiresApi

/**
 * Host content time for each decoded frame, keyed by the decoder PTS (the
 * local arrival time in µs that StreamClient hands to MediaCodec). The host
 * sends when the frame's pixels were composited on the Mac; a repeat frame
 * carries its source's time.
 */
object FrameContentTimes {
    private const val CAPACITY = 64
    private val map =
        object : LinkedHashMap<Long, Long>(CAPACITY, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > CAPACITY
        }

    fun note(
        ptsUs: Long,
        hostContentNs: Long,
    ) {
        if (hostContentNs <= 0L) return
        synchronized(map) { map[ptsUs] = hostContentNs }
    }

    fun take(ptsUs: Long): Long? = synchronized(map) { map.remove(ptsUs) }

    fun clear() = synchronized(map) { map.clear() }
}

/**
 * Picks a present time for each frame so frames reach the panel on the
 * host's own cadence (docs/measurements.md, "Vsync-aligned presentation").
 *
 * Presenting each frame as soon as it is drawn lets transport and decode
 * jitter decide which vsync it lands on: two frames in one vsync (one never
 * seen) next to a vsync that shows the previous frame again. Here every frame
 * is shown at the first vsync at or after (host content time + offset), where
 * offset is the median of how soon frames could make a vsync over the last
 * ~2 s, and never on the vsync of the frame before it. Content composited
 * 8.3 ms apart on the Mac then appears 8.3 ms apart on the tablet; frames
 * slower than the median show as soon as they can, as before.
 *
 * Uses Choreographer.FrameData (API 33): each upcoming vsync's expected
 * present time and the deadline a buffer must be queued by to make it.
 * GL thread only.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PresentScheduler {
    private val choreographer = Choreographer.getInstance()
    private var presents = LongArray(0)
    private var deadlines = LongArray(0)
    var periodNs = 8_333_333L
        private set
    private var running = false

    // Recent (earliest achievable present - host content time), ns.
    private val samples = LongArray(SAMPLE_WINDOW)
    private var sampleCount = 0
    private var sampleNext = 0
    private var offsetNs = Long.MIN_VALUE
    private var framesSinceOffset = 0
    private var lastHostNs = Long.MIN_VALUE
    private var lastRequestNs: Long? = null
    private var lastTargetNs = Long.MIN_VALUE

    // Stats for the periodic log.
    var scheduled = 0
        private set
    var late = 0
        private set
    var addedSumNs = 0L
        private set

    private val vsyncCallback =
        object : Choreographer.VsyncCallback {
            override fun onVsync(data: Choreographer.FrameData) {
                val tl = data.frameTimelines
                presents = LongArray(tl.size) { tl[it].expectedPresentationTimeNanos }
                deadlines = LongArray(tl.size) { tl[it].deadlineNanos }
                if (tl.size > 1) {
                    val p = presents[1] - presents[0]
                    if (p in 4_000_000L..50_000_000L) periodNs = p
                }
                if (running) choreographer.postVsyncCallback(this)
            }
        }

    fun start() {
        if (running) return
        running = true
        // Resuming after a pause (power saving, issue #16): what frames could
        // make at a slower panel rate says nothing about now. Relearn; frames
        // show as soon as they can meanwhile (MIN_SAMPLES, ~0.25 s at 120 fps).
        sampleCount = 0
        sampleNext = 0
        offsetNs = Long.MIN_VALUE
        framesSinceOffset = 0
        lastHostNs = Long.MIN_VALUE
        lastRequestNs = null
        lastTargetNs = Long.MIN_VALUE
        presents = LongArray(0)
        deadlines = LongArray(0)
        choreographer.postVsyncCallback(vsyncCallback)
    }

    fun stop() {
        running = false
        choreographer.removeVsyncCallback(vsyncCallback)
    }

    /**
     * The present time to request for a frame of host content time
     * [hostContentNs] that will be queued at about [queueNs], or null to
     * present as soon as possible (no vsync timeline yet).
     */
    fun presentTimeFor(
        hostContentNs: Long,
        queueNs: Long,
    ): Long? {
        if (!running || presents.isEmpty()) return null
        // A repeat (same pixels, the source's content time) goes with the
        // frame it repeats; it must not pull the offset up either.
        if (hostContentNs <= lastHostNs) return lastRequestNs
        lastHostNs = hostContentNs
        val earliest = earliestPresent(queueNs)
        record(earliest - hostContentNs)
        val offset = offsetNs
        if (offset == Long.MIN_VALUE) return null.also { lastRequestNs = null }
        val wanted = vsyncAtOrAfter(hostContentNs + offset)
        // Never two new frames on one vsync (the earlier would never be seen),
        // but only by pushing one vsync: Wi-Fi delivers frames in bursts, and
        // spacing a burst out one by one chained up to 42 ms of extra wait.
        val floor = maxOf(wanted, earliest)
        var target = maxOf(floor, lastTargetNs + periodNs)
        if (target - floor > periodNs) target = floor
        lastTargetNs = target
        scheduled++
        if (earliest > wanted) late++
        addedSumNs += target - earliest
        // Ask for a time inside the vsync before the target's, so SurfaceFlinger
        // shows it exactly at the target and not one vsync early.
        return (target - periodNs / 2).also { lastRequestNs = it }
    }

    fun resetStats() {
        scheduled = 0
        late = 0
        addedSumNs = 0
    }

    val currentOffsetMs: Double get() = if (offsetNs == Long.MIN_VALUE) -1.0 else offsetNs / 1e6

    /** First vsync whose deadline a buffer queued at [queueNs] still makes. */
    private fun earliestPresent(queueNs: Long): Long {
        for (i in deadlines.indices) {
            if (deadlines[i] > queueNs) return presents[i]
        }
        // Past the known timelines: extrapolate on the vsync grid.
        val lastDeadline = deadlines.last()
        val steps = (queueNs - lastDeadline) / periodNs + 1
        return presents.last() + steps * periodNs
    }

    /** First vsync present time at or after [t], on the known grid. */
    private fun vsyncAtOrAfter(t: Long): Long {
        val anchor = presents[0]
        if (t <= anchor) {
            val back = (anchor - t) / periodNs
            return anchor - back * periodNs
        }
        val steps = (t - anchor + periodNs - 1) / periodNs
        return anchor + steps * periodNs
    }

    private fun record(sampleNs: Long) {
        samples[sampleNext] = sampleNs
        sampleNext = (sampleNext + 1) % SAMPLE_WINDOW
        if (sampleCount < SAMPLE_WINDOW) sampleCount++
        framesSinceOffset++
        if (sampleCount >= MIN_SAMPLES && (offsetNs == Long.MIN_VALUE || framesSinceOffset >= RECOMPUTE_EVERY)) {
            framesSinceOffset = 0
            val sorted = samples.copyOf(sampleCount).also { it.sort() }
            offsetNs = sorted[((sorted.size - 1) * PERCENTILE) / 100]
        }
    }

    companion object {
        private const val SAMPLE_WINDOW = 240 // ~2 s at 120 fps
        private const val MIN_SAMPLES = 30
        private const val RECOMPUTE_EVERY = 30
        // p50: judder 48-51% -> 10-16% of steps with no added latency; p85 halved
        // that again for one more vsync (8.3 ms). diag.presentPercentile overrides.
        @Volatile
        var PERCENTILE = 50
    }
}
