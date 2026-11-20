package io.sharpscreen.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Build
import android.util.Log
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Trace
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M1a: the decoder renders into a SurfaceTexture we own, and this class draws
 * it onto the SurfaceView's window with OpenGL ES. Video only for now; the
 * lossless tile layer (docs/design/M1-lossless-tiles.md) will be composited
 * here on top of it.
 *
 * Everything GL runs on one URGENT_DISPLAY thread. A decoded frame is drawn
 * as soon as it arrives (onFrameAvailable), with swap interval 0 so a swap
 * never waits for vsync behind an older frame.
 */
class GlCompositor(
    private val output: Surface,
) {
    private val thread = HandlerThread("GlCompositor", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexture = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCanvas = 0
    private var uMask = 0
    private var uMaskScale = 0
    private var uTiles = 0
    private var uTint = 0

    // M1b lossless layer: BGRA canvas at stream size + one mask byte per tile
    // (255 = show the canvas there). Touched only on the GL thread.
    private var canvasTexture = 0
    private var maskTexture = 0
    private var grid: TileGrid? = null
    private var mask = ByteArray(0)
    private var maskDirty = false
    private var hasVideoFrame = false
    private var tilesApplied = 0
    private var tilesInvalidated = 0
    private lateinit var surfaceTexture: SurfaceTexture
    // Vsync-aligned presentation (API 33+), GL thread only.
    private var scheduler: PresentScheduler? = null
    private var pendingPresentNs: Long? = null
    private var unknownContent = 0
    // Tile messages wait for the next video frame (they come every ~8 ms,
    // repeats included) instead of presenting on their own: an extra present
    // per message backed the window's queue up (queued -> shown 26 ms instead
    // of 19 ms) and pushed out frames that were then never shown.
    private var composePending = false
    private val deferredCompose =
        Runnable {
            composePending = false
            compose()
        }
    private var traceHostNs = 0L // content time of the frame being composed, for atrace
    private var swapCount = 0L
    private val texMatrix = FloatArray(16)
    private var released = false

    /** Hand this to MediaCodec.configure instead of the window surface. */
    lateinit var inputSurface: Surface
        private set

    // Timing: frame available -> swap returned, and swap -> swap.
    private var statWindowStartNs = 0L
    private var frames = 0
    private var drawSumNs = 0L
    private var drawMaxNs = 0L
    private var handoffSumNs = 0L
    private var handoffMaxNs = 0L
    private var handoffSamples = 0

    /** Set by the decoder right after releaseOutputBuffer(render = true). */
    @Volatile
    var lastReleaseNs = 0L

    init {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                setUpGl()
            } catch (t: Throwable) {
                error = t
            }
            ready.countDown()
        }
        if (!ready.await(2, TimeUnit.SECONDS)) throw IllegalStateException("GL setup timed out")
        error?.let {
            release()
            throw IllegalStateException("GL setup failed: ${it.message}", it)
        }
    }

    /** Size of the frames the decoder will produce. */
    fun setVideoSize(
        width: Int,
        height: Int,
    ) {
        handler.post {
            if (released) return@post
            surfaceTexture.setDefaultBufferSize(width, height)
            allocateTileLayer(TileGrid(width, height))
        }
    }

    /** Wire type 20: these tiles changed; show video there until refined. Any thread. */
    fun invalidateTiles(tiles: IntArray) {
        handler.post {
            val g = grid ?: return@post
            var changed = false
            for (t in tiles) {
                if (t in 0 until g.count && mask[t] != 0.toByte()) {
                    mask[t] = 0
                    changed = true
                }
            }
            tilesInvalidated += tiles.size
            if (changed) {
                maskDirty = true
                requestCompose()
            }
        }
    }

    /** Wire type 21: lossless BGRA pixels for these tiles. Any thread. */
    fun applyTiles(
        tileSize: Int,
        tiles: List<DecodedTile>,
    ) {
        handler.post {
            val g = grid ?: return@post
            if (tileSize != g.tileSize || !showTiles) return@post
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            for (t in tiles) {
                if (t.index !in 0 until g.count) continue
                val w = g.tileWidth(t.index)
                val h = g.tileHeight(t.index)
                if (t.pixels.size != w * h * 4) continue
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, g.x(t.index), g.y(t.index), w, h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(t.pixels),
                )
                mask[t.index] = 0xFF.toByte()
            }
            tilesApplied += tiles.size
            maskDirty = true
            requestCompose()
        }
    }

    private fun allocateTileLayer(g: TileGrid) {
        if (g == grid) return
        grid = g
        mask = ByteArray(g.count)
        maskDirty = true
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, g.width, g.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, g.columns, g.rows, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask),
        )
    }

    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            if (!released) {
                released = true
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) scheduler?.stop()
                    if (::surfaceTexture.isInitialized) {
                        surfaceTexture.setOnFrameAvailableListener(null)
                        inputSurface.release()
                        surfaceTexture.release()
                    }
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                        EGL14.eglReleaseThread()
                        EGL14.eglTerminate(eglDisplay)
                    }
                } catch (_: Exception) {
                }
            }
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        val config = configs[0]!!
        eglContext =
            EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        EGL14.eglSwapInterval(eglDisplay, 0)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCanvas = GLES20.glGetUniformLocation(program, "uCanvas")
        uMask = GLES20.glGetUniformLocation(program, "uMask")
        uMaskScale = GLES20.glGetUniformLocation(program, "uMaskScale")
        uTiles = GLES20.glGetUniformLocation(program, "uTiles")
        uTint = GLES20.glGetUniformLocation(program, "uTint")

        val two = IntArray(2)
        GLES20.glGenTextures(2, two, 0)
        canvasTexture = two[0]
        maskTexture = two[1]
        for (t in two) {
            // NEAREST: the canvas is drawn 1:1 and must stay pixel exact; the
            // mask must not blend between neighbouring tiles.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        if (vsyncPresent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            scheduler = PresentScheduler().also { it.start() }
        }
        surfaceTexture = SurfaceTexture(oesTexture)
        surfaceTexture.setOnFrameAvailableListener({ drawFrame() }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun requestCompose() {
        if (composePending) return
        composePending = true
        handler.postDelayed(deferredCompose, COMPOSE_DEFER_MS)
    }

    /** Host content time and surface size of the last video frame drawn. */
    private var shownHostNs = 0L
    private var shownWidth = 0
    private var shownHeight = 0
    private var repeatsSkipped = 0

    private fun isRepeatOfShown(host: Long?): Boolean {
        if (drawRepeats || host == null || !hasVideoFrame || host != shownHostNs) return false
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        return w[0] == shownWidth && h[0] == shownHeight
    }

    private fun drawFrame() {
        if (released) return
        if (composePending) {
            handler.removeCallbacks(deferredCompose)
            composePending = false
        }
        val startNs = System.nanoTime()
        val released = lastReleaseNs
        if (released in 1..startNs && startNs - released < 100_000_000L) {
            val h = startNs - released
            handoffSumNs += h
            handoffSamples++
            if (h > handoffMaxNs) handoffMaxNs = h
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val host = FrameContentTimes.take(surfaceTexture.timestamp / 1000)
        if (isRepeatOfShown(host)) {
            // Same pixels as the frame on screen (the host repeats its last
            // frame to keep the stream at the display rate). The decoder
            // still decoded it; drawing and presenting it again only kept
            // the GPU, SurfaceFlinger and the panel busy: ~0.9 A on a still
            // screen (issue #16).
            repeatsSkipped++
            return
        }
        hasVideoFrame = true
        traceHostNs = host ?: 0L
        val s = scheduler
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (host == null) {
                unknownContent++
            } else {
                pendingPresentNs = s.presentTimeFor(host, startNs + drawEstimateNs())
            }
        }
        compose(fromVideo = true)
        val endNs = System.nanoTime()
        recordTiming(startNs, endNs)
        reportFrameTiming(host, released, startNs, endNs)
    }

    private var lastReportedHostNs = 0L

    /**
     * Latency breakdown (issue #13): for each new frame (not a repeat), how long
     * it took from arrival to decoder output, from decoder output to this draw,
     * and from this draw to the vsync it was asked for (or to the swap without
     * vsync pacing). The arrival time is the decoder PTS (StreamClient's
     * receive time, µs).
     */
    private fun reportFrameTiming(
        host: Long?,
        decodedNs: Long,
        drawNs: Long,
        swappedNs: Long,
    ) {
        val report = onFrameTiming ?: return
        if (host == null || host <= lastReportedHostNs) return
        lastReportedHostNs = host
        val arrivalNs = surfaceTexture.timestamp
        if (arrivalNs <= 0L || decodedNs < arrivalNs || decodedNs > drawNs) return
        val s = scheduler
        val requested = pendingPresentNs
        val presentNs =
            if (s != null && requested != null) requested + s.periodNs / 2 else swappedNs
        // For matching against `dumpsys SurfaceFlinger --latency` (actual present
        // per buffer): this swap's end and the frame's host content time.
        Log.d("PresentTrace", "$swappedNs $host")
        report(
            host,
            ((decodedNs - arrivalNs) / 1000).toInt(),
            ((drawNs - decodedNs) / 1000).toInt(),
            ((presentNs - drawNs).coerceAtLeast(0L) / 1000).toInt(),
        )
    }

    /** Draw video with the lossless layer on top and present. GL thread. */
    private fun compose(fromVideo: Boolean = false) {
        if (released || !hasVideoFrame) return
        val g = grid
        if (maskDirty && g != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, g.columns, g.rows,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask),
            )
            maskDirty = false
        }

        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        if (fromVideo) {
            shownHostNs = traceHostNs
            shownWidth = w[0]
            shownHeight = h[0]
        }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexture)
        GLES20.glUniform1i(uCanvas, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        GLES20.glUniform1i(uMask, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (g != null) {
            // Tiles at the right/bottom edge are narrower than tileSize, so map
            // pixels to mask cells as pixel / tileSize, not as a fraction of the grid.
            GLES20.glUniform2f(uMaskScale, g.width.toFloat() / (g.columns * g.tileSize), g.height.toFloat() / (g.rows * g.tileSize))
            GLES20.glUniform1f(uTiles, 1f)
        } else {
            GLES20.glUniform1f(uTiles, 0f)
        }
        GLES20.glUniform1f(uTint, if (tintTiles) 1f else 0f)
        QUAD.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aPosition)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // A tile or invalidate compose goes with the newest video frame: same
        // present time while that frame waits for its vsync, else at once.
        val presentAt = pendingPresentNs
        if (presentAt != null) {
            if (presentAt > System.nanoTime()) {
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentAt)
            } else {
                pendingPresentNs = null
            }
        }
        // Measurement (docs/measurements.md): with app tracing on, tag each swap
        // with its content time so atrace frame numbers map to source frames.
        val tracing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()
        swapCount++
        if (tracing) Trace.beginSection("ssPresent n=$swapCount v=${if (fromVideo) 1 else 0} host=$traceHostNs req=${pendingPresentNs ?: 0}")
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (tracing) Trace.endSection()
    }

    /** Recent draw+swap time, for "queued by" when picking a vsync. */
    private fun drawEstimateNs(): Long = if (frames > 0) maxOf(1_000_000L, drawSumNs / frames + 500_000L) else 2_000_000L

    private fun recordTiming(
        startNs: Long,
        endNs: Long,
    ) {
        val d = endNs - startNs
        frames++
        drawSumNs += d
        if (d > drawMaxNs) drawMaxNs = d
        if (statWindowStartNs == 0L) statWindowStartNs = endNs
        if (endNs - statWindowStartNs >= 2_000_000_000L) {
            DiagLog.log(
                "GlCompositor",
                "frames=$frames handoff avg=${"%.2f".format(if (handoffSamples > 0) handoffSumNs / handoffSamples / 1e6 else 0.0)}ms " +
                    "max=${"%.2f".format(handoffMaxNs / 1e6)}ms, draw+swap avg=${"%.2f".format(drawSumNs / frames / 1e6)}ms " +
                    "max=${"%.2f".format(drawMaxNs / 1e6)}ms, tiles applied=$tilesApplied invalidated=$tilesInvalidated " +
                    "lossless now=${mask.count { it != 0.toByte() }}/${mask.size}, repeats not drawn=$repeatsSkipped" + schedulerStats(),
            )
            repeatsSkipped = 0
            frames = 0
            drawSumNs = 0
            drawMaxNs = 0
            handoffSumNs = 0
            handoffMaxNs = 0
            handoffSamples = 0
            tilesApplied = 0
            tilesInvalidated = 0
            statWindowStartNs = endNs
        }
    }

    private fun schedulerStats(): String {
        val s = scheduler ?: return ", vsync present off"
        val line =
            ", vsync present: offset=${"%.1f".format(s.currentOffsetMs)}ms scheduled=${s.scheduled} " +
                "late=${s.late} avg wait=${"%.2f".format(if (s.scheduled > 0) s.addedSumNs / s.scheduled / 1e6 else 0.0)}ms" +
                " no content time=$unknownContent"
        s.resetStats()
        unknownContent = 0
        return line
    }

    private fun buildProgram(
        vs: String,
        fs: String,
    ): Int {
        fun compile(
            type: Int,
            src: String,
        ): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        /** On by default since M1a passed its latency gate; diag.glCompositor=false turns it off. */
        @Volatile
        var enabled = false

        /** Diagnostics: false never asks the host for tiles (GL path, video only). */
        @Volatile
        var losslessTiles = true

        private const val COMPOSE_DEFER_MS = 12L

        /**
         * Latency breakdown (issue #13): host content time, then µs from arrival to
         * decoder output, from decoder output to draw, and from draw to the vsync.
         * Set only with diag.frameTiming. GL thread.
         */
        @Volatile
        var onFrameTiming: ((Long, Int, Int, Int) -> Unit)? = null

        /**
         * Draw and present repeat frames too (before issue #16). Per launch:
         * `--ez diag.drawRepeats true`.
         */
        @Volatile
        var drawRepeats = false

        /** Present each frame on the host's cadence (PresentScheduler); diag.vsyncPresent=false turns it off. */
        @Volatile
        var vsyncPresent = true

        /** Diagnostics: receive tiles but keep showing video (HEVC-only baseline). */
        @Volatile
        var showTiles = true

        /** Diagnostics: tint lossless tiles green. */
        @Volatile
        var tintTiles = false

        // x, y, u, v for a full-screen triangle strip.
        private val QUAD: FloatBuffer =
            ByteBuffer
                .allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            varying vec2 vImage;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                // Image coordinates, origin top-left like the host's tiles.
                vImage = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vImage;
            uniform samplerExternalOES uTexture;
            uniform sampler2D uCanvas;
            uniform sampler2D uMask;
            uniform vec2 uMaskScale;
            uniform float uTiles;
            uniform float uTint;
            void main() {
                if (uTiles > 0.5 && texture2D(uMask, vImage * uMaskScale).r > 0.5) {
                    // Host pixels are BGRA; the canvas was uploaded as RGBA bytes.
                    vec4 c = vec4(texture2D(uCanvas, vImage).bgr, 1.0);
                    gl_FragColor = uTint > 0.5 ? mix(c, vec4(0.0, 1.0, 0.0, 1.0), 0.3) : c;
                } else {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            }
        """
    }
}
