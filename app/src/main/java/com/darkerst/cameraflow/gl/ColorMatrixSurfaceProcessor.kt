package com.darkerst.cameraflow.gl

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
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ColorMatrixGL"

/**
 * Renders every camera frame through a live-updatable color matrix on the
 * GPU, and forwards the result to whatever CameraX target this is attached
 * to (typically CameraEffect.VIDEO_CAPTURE).
 *
 * This exists because the "filtered look" the user sees in the viewfinder
 * is only a View-level tint (PreviewView.setLayerType + ColorMatrixColorFilter)
 * applied at composition time - it never touches the underlying camera
 * stream, so it was never present in recorded video. This class bakes the
 * same color matrix directly into the frames CameraX hands off to the
 * video encoder, so recordings actually match the live preview.
 *
 * All GL/EGL work happens on a single dedicated thread, as required by EGL.
 */
class ColorMatrixSurfaceProcessor : SurfaceProcessor {

    // Written from the UI thread whenever the selected filter/intensity
    // changes; read every frame from the GL thread. A 4x4 matrix + vec4
    // offset is the GLSL-friendly equivalent of android.graphics.ColorMatrix's
    // 4x5 array.
    @Volatile var colorMatrix: FloatArray = IDENTITY_4X4
    @Volatile var colorOffset: FloatArray = ZERO_OFFSET

    private val glThread = HandlerThread("ColorMatrixGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command -> glHandler.post(command) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var dummyPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var inputTextureId = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private val outputs = ConcurrentHashMap<SurfaceOutput, OutputTarget>()
    private val textureTransform = FloatArray(16)
    private val released = AtomicBoolean(false)
    private var frameCount = 0L

    private data class OutputTarget(val surfaceOutput: SurfaceOutput, val eglSurface: EGLSurface)

    init {
        glHandler.post { initGl() }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        Log.d(TAG, "onInputSurface: resolution=${request.resolution}")
        glHandler.post {
            val textureId = createOesTexture()
            inputTextureId = textureId
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener({ texture ->
                    glHandler.post { drawFrame(texture) }
                }, glHandler)
            }
            inputSurfaceTexture = surfaceTexture
            val surface = Surface(surfaceTexture)
            inputSurface = surface
            request.provideSurface(surface, glExecutor) {
                surface.release()
                surfaceTexture.release()
            }
        }
    }

    override fun onOutputSurface(request: SurfaceOutput) {
        Log.d(TAG, "onOutputSurface: target=${request.targets}, size=${request.size}")
        glHandler.post {
            val surface = request.getSurface(glExecutor) {
                Log.d(TAG, "onOutputSurface: surface released for target=${request.targets}")
                outputs.remove(request)?.let { target ->
                    EGL14.eglDestroySurface(eglDisplay, target.eglSurface)
                }
                request.close()
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            outputs[request] = OutputTarget(request, eglSurface)
            Log.d(TAG, "onOutputSurface: eglSurface created, total outputs=${outputs.size}")
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        glHandler.post {
            outputs.values.forEach { EGL14.eglDestroySurface(eglDisplay, it.eglSurface) }
            outputs.clear()
            inputSurfaceTexture?.release()
            inputSurface?.release()
            if (program != 0) GLES20.glDeleteProgram(program)
            if (dummyPbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, dummyPbuffer)
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            glThread.quitSafely()
        }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // A throwaway 1x1 pbuffer so the context has *something* current
        // before the first real output surface is attached.
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        dummyPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, dummyPbuffer, dummyPbuffer, eglContext)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun drawFrame(texture: SurfaceTexture) {
        texture.updateTexImage()
        texture.getTransformMatrix(textureTransform)

        // MediaCodec's surface input (used by CameraX's video encoder) requires
        // a valid, monotonically increasing presentation timestamp on every
        // buffer submitted via eglSwapBuffers. Without this call the EGL
        // surface has no timestamp attached, the encoder treats the frame as
        // invalid/out-of-order, and it silently drops it - the video track
        // ends up empty even though rendering "succeeds" from GL's point of
        // view. This was the missing piece: frames were being drawn, but
        // never actually reaching the encoded output.
        val timestampNs = texture.timestamp

        frameCount++
        if (frameCount % 60 == 0L) {
            Log.d(TAG, "drawFrame: frame #$frameCount, outputs=${outputs.size}, ts=$timestampNs")
        }

        if (outputs.isEmpty() || program == 0) {
            if (frameCount % 60 == 0L) {
                Log.w(TAG, "drawFrame: skipping - outputs.isEmpty=${outputs.isEmpty()} program=$program")
            }
            return
        }

        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val colorMatrixLoc = GLES20.glGetUniformLocation(program, "uColorMatrix")
        val colorOffsetLoc = GLES20.glGetUniformLocation(program, "uColorOffset")
        val samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

        outputs.values.forEach { target ->
            EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)
            GLES20.glViewport(0, 0, target.surfaceOutput.size.width, target.surfaceOutput.size.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            GLES20.glUniform1i(samplerLoc, 0)

            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, textureTransform, 0)
            GLES20.glUniformMatrix4fv(colorMatrixLoc, 1, false, colorMatrix, 0)
            GLES20.glUniform4fv(colorOffsetLoc, 1, colorOffset, 0)

            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, VERTEX_COORDS)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
            GLES20.glEnableVertexAttribArray(texLoc)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, target.eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, target.eglSurface)
        }
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        val IDENTITY_4X4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        val ZERO_OFFSET = floatArrayOf(0f, 0f, 0f, 0f)

        private val VERTEX_COORDS = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX_COORDS = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

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
            uniform samplerExternalOES sTexture;
            uniform mat4 uColorMatrix;
            uniform vec4 uColorOffset;
            void main() {
                vec4 base = texture2D(sTexture, vTexCoord);
                gl_FragColor = uColorMatrix * base + uColorOffset;
            }
        """
    }
}
