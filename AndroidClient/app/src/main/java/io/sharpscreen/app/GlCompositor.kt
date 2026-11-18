package io.sharpscreen.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
    private lateinit var surfaceTexture: SurfaceTexture
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
        handler.post { if (!released) surfaceTexture.setDefaultBufferSize(width, height) }
    }

    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            if (!released) {
                released = true
                try {
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

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTexture)
        surfaceTexture.setOnFrameAvailableListener({ drawFrame() }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun drawFrame() {
        if (released) return
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

        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        QUAD.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aPosition)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        recordTiming(startNs, System.nanoTime())
    }

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
                    "max=${"%.2f".format(drawMaxNs / 1e6)}ms",
            )
            frames = 0
            drawSumNs = 0
            drawMaxNs = 0
            handoffSumNs = 0
            handoffMaxNs = 0
            handoffSamples = 0
            statWindowStartNs = endNs
        }
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
        /** Off by default until M1a passes its latency gate; see MainActivity. */
        @Volatile
        var enabled = false

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
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
