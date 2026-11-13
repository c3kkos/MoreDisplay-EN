package io.github.ynkcc.moredisplay.cast

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives a virtual display's frames once and copies them, on the GPU and at full size, to several outputs:
 * the video encoder (with each frame's timestamp) and, optionally, the eye window. This is what lets casting and
 * the eye run at the same time.
 *
 * All GL work happens on one thread. [setOutput] and [release] wait for it, so a caller can safely let a surface
 * go right after removing it.
 */
class FrameHub(private val width: Int, private val height: Int) {

    companion object {
        private const val TAG = "MoreDisplay_FrameHub"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }"""
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }"""
    }

    private class Output(val surface: EGLSurface, val timestamps: Boolean)

    private val thread = HandlerThread("MoreDisplay-framehub").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private val outputs = LinkedHashMap<String, Output>()
    private var released = false

    private val quad: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** Where the virtual display should send its frames. Valid after [start]. */
    lateinit var inputSurface: Surface
        private set

    fun start() = runOnGl {
        setUpEgl()
        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
    }

    /**
     * Adds, replaces or (with a null [surface]) removes an output. [timestamps] passes each frame's capture time to
     * the output (needed by the video encoder).
     */
    fun setOutput(key: String, surface: Surface?, timestamps: Boolean = false) = runOnGl {
        outputs.remove(key)?.let { EGL14.eglDestroySurface(display, it.surface) }
        if (surface != null && surface.isValid && !released) {
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "output $key: eglCreateWindowSurface failed (0x${Integer.toHexString(EGL14.eglGetError())})")
            } else {
                outputs[key] = Output(egl, timestamps)
            }
        }
        EGL14.eglMakeCurrent(display, idle, idle, context)
    }

    fun release() {
        runOnGl {
            released = true
            outputs.values.forEach { EGL14.eglDestroySurface(display, it.surface) }
            outputs.clear()
            if (::surfaceTexture.isInitialized) {
                surfaceTexture.setOnFrameAvailableListener(null)
                inputSurface.release()
                surfaceTexture.release()
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, idle)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    private fun drawFrame() {
        if (released) return
        EGL14.eglMakeCurrent(display, idle, idle, context)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val timestamp = surfaceTexture.timestamp
        for (out in outputs.values) {
            if (!EGL14.eglMakeCurrent(display, out.surface, out.surface, context)) continue
            val size = IntArray(2)
            EGL14.eglQuerySurface(display, out.surface, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(display, out.surface, EGL14.EGL_HEIGHT, size, 1)
            GLES20.glViewport(0, 0, size[0], size[1])
            draw()
            if (out.timestamps) EGLExt.eglPresentationTimeANDROID(display, out.surface, timestamp)
            EGL14.eglSwapBuffers(display, out.surface)
        }
        EGL14.eglMakeCurrent(display, idle, idle, context)
    }

    private fun draw() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no suitable EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, idle, idle, context)) { "eglMakeCurrent failed" }
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source.trimIndent())
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    /** Runs [block] on the GL thread and waits for it (rethrowing its failure). */
    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) return block()
        var error: Throwable? = null
        val done = CountDownLatch(1)
        if (!handler.post {
                try {
                    block()
                } catch (t: Throwable) {
                    error = t
                } finally {
                    done.countDown()
                }
            }
        ) return
        if (!done.await(3, TimeUnit.SECONDS)) Log.w(TAG, "GL thread busy for more than 3 s")
        error?.let { throw it }
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }
}
