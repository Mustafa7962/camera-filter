package com.opensource.camfilter

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * CameraFilterEngine
 * ===================
 * This is the heart of the "virtual filter" pipeline.
 *
 * How frames flow through here:
 *  1. CameraX's Preview use case is told to render into a [Surface] that this class
 *     owns (see [surface]). CameraX has no idea the surface is backed by OpenGL —
 *     from its point of view it is just handed a normal Android Surface to draw into,
 *     exactly like it would hand frames to a regular PreviewView.
 *  2. That Surface is backed by a [SurfaceTexture] bound to an OpenGL "external OES"
 *     texture (GL_TEXTURE_EXTERNAL_OES). This is the standard Android mechanism for
 *     getting camera/video frames directly into a GPU texture without ever touching
 *     the CPU (no Bitmap copies), which is why this approach is fast enough for
 *     30-60 fps real-time filtering.
 *  3. Every time a new camera frame lands, [onFrameAvailable] fires. We ask the
 *     attached GLSurfaceView to redraw.
 *  4. In [onDrawFrame], we call `surfaceTexture.updateTexImage()` which makes the
 *     latest camera frame available to the GPU as texture data, then we draw a
 *     full-screen quad using that texture as input to a fragment shader.
 *  5. The fragment shader is where the actual "filters" happen: brightness/contrast
 *     math, a cheap box-blur based skin-smoothing approximation, and a grayscale/
 *     sepia color grade — all per-pixel, on the GPU, in a single pass.
 */
class CameraFilterEngine(
    private val onSurfaceReady: (Surface) -> Unit
) : android.opengl.GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    enum class ColorFilter { NONE, GRAYSCALE, SEPIA, INVERT, COOL, WARM, VINTAGE, MIRROR }

    // ---- Public tunables, safe to set from any thread; read on the GL thread ----
    @Volatile var brightness: Float = 0f       // -1f .. +1f additive
    @Volatile var contrast: Float = 1f         // 0f .. 2f multiplicative around mid-gray
    @Volatile var smoothing: Float = 0f        // 0f .. 1f blend factor for skin smoothing
    @Volatile var colorFilter: ColorFilter = ColorFilter.NONE

    // When non-null, roughly every 6th drawn frame is read back from the GPU and
    // handed to this callback as an ARGB Bitmap — used to mirror the filtered feed
    // into the floating overlay bubble (see FrameBus / OverlayService). Left null
    // (the default) this readback work never runs, so normal in-app preview pays
    // zero extra cost.
    @Volatile var overlayFrameCallback: ((android.graphics.Bitmap) -> Unit)? = null
    private var frameCounter = 0

    private var glSurfaceView: android.opengl.GLSurfaceView? = null

    fun attachView(view: android.opengl.GLSurfaceView) {
        glSurfaceView = view
    }

    // ---- OpenGL handles ----
    private var programHandle = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uSTMatrixHandle = 0
    private var uBrightnessHandle = 0
    private var uContrastHandle = 0
    private var uSmoothingHandle = 0
    private var uFilterModeHandle = 0
    private var uTexelSizeHandle = 0
    private var oesTextureId = 0

    private var viewWidth = 0
    private var viewHeight = 0

    // ---- Camera-facing surface ----
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val stMatrix = FloatArray(16)

    /** Must be called from the camera thread before binding CameraX's Preview use case. */
    fun setPreviewResolution(width: Int, height: Int) {
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.setDefaultBufferSize(width, height)
        }
    }

    fun getInputSurface(): Surface = surface

    // Full-screen quad: position (x, y) interleaved with texture coords (u, v)
    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(quadVertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(quadVertices)
                position(0)
            }

    // -------------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        oesTextureId = createExternalOesTexture()
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)

        programHandle = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programHandle, "uSTMatrix")
        uBrightnessHandle = GLES20.glGetUniformLocation(programHandle, "uBrightness")
        uContrastHandle = GLES20.glGetUniformLocation(programHandle, "uContrast")
        uSmoothingHandle = GLES20.glGetUniformLocation(programHandle, "uSmoothing")
        uFilterModeHandle = GLES20.glGetUniformLocation(programHandle, "uFilterMode")
        uTexelSizeHandle = GLES20.glGetUniformLocation(programHandle, "uTexelSize")

        // Hand the Surface back to MainActivity so it can bind CameraX's Preview to it.
        onSurfaceReady(surface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Pull in the most recent camera frame (no-op if nothing new arrived).
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programHandle)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uBrightnessHandle, brightness)
        GLES20.glUniform1f(uContrastHandle, contrast)
        GLES20.glUniform1f(uSmoothingHandle, smoothing)
        GLES20.glUniform1i(uFilterModeHandle, colorFilter.ordinal)
        // Texel size drives how far the smoothing blur samples reach; camera frame
        // resolution (not view resolution) is what matters here, but the view size
        // is a reasonable approximation since the quad fills the viewport 1:1.
        val texelW = if (viewWidth > 0) 1f / viewWidth else 0f
        val texelH = if (viewHeight > 0) 1f / viewHeight else 0f
        GLES20.glUniform2f(uTexelSizeHandle, texelW, texelH)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)

        overlayFrameCallback?.let { callback ->
            frameCounter++
            if (frameCounter % 6 == 0 && viewWidth > 0 && viewHeight > 0) {
                readCurrentFrameAsBitmap()?.let(callback)
            }
        }
    }

    /**
     * Reads the just-drawn framebuffer back into CPU memory as a Bitmap. This is
     * relatively expensive (a full GPU->CPU copy) which is exactly why it's throttled
     * to a handful of times per second and only runs at all while the overlay bubble
     * is active.
     */
    private fun readCurrentFrameAsBitmap(): android.graphics.Bitmap? {
        return try {
            val w = viewWidth
            val h = viewHeight
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            // glReadPixels returns rows bottom-to-top relative to a typical Bitmap; flip it.
            val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, w / 2f, h / 2f) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, false)
        } catch (e: Exception) {
            null
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        // Frames can arrive on a binder thread — request a redraw on the GL thread.
        glSurfaceView?.requestRender()
    }

    fun release() {
        if (::surface.isInitialized) surface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        if (programHandle != 0) GLES20.glDeleteProgram(programHandle)
    }

    // -------------------------------------------------------------------------------
    // GL helpers
    // -------------------------------------------------------------------------------

    private fun createExternalOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return texId
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }
        return program
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                // SurfaceTexture frames may be rotated/cropped by the camera HAL;
                // uSTMatrix (from SurfaceTexture.getTransformMatrix) corrects that.
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        // GL_OES_EGL_image_external is required to sample the camera's external texture.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uBrightness;   // -1..1
            uniform float uContrast;     // 0..2
            uniform float uSmoothing;    // 0..1
            uniform int uFilterMode;     // 0 none, 1 grayscale, 2 sepia, 3 invert,
                                          // 4 cool, 5 warm, 6 vintage, 7 mirror
            uniform vec2 uTexelSize;

            vec3 applySkinSmoothing(vec2 texCoord, vec3 original) {
                // Cheap real-time "smoothing" approximation: average a small ring of
                // neighboring texels (a box blur) and blend it back in with the sharp
                // original. This softens high-frequency detail (skin texture, blemishes)
                // while `uSmoothing` controls how strong the effect is. It is not a true
                // bilateral/edge-aware filter (which is too expensive for a single mobile
                // fragment shader pass), but it is a lightweight, dependency-free stand-in
                // that runs comfortably at 30-60fps.
                vec3 sum = vec3(0.0);
                sum += texture2D(sTexture, texCoord + vec2(-1.0,  0.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2( 1.0,  0.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2( 0.0, -1.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2( 0.0,  1.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2(-1.0, -1.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2( 1.0,  1.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2(-1.0,  1.0) * uTexelSize * 2.0).rgb;
                sum += texture2D(sTexture, texCoord + vec2( 1.0, -1.0) * uTexelSize * 2.0).rgb;
                vec3 blurred = sum / 8.0;
                return mix(original, blurred, uSmoothing);
            }

            void main() {
                // MIRROR folds the right half of the frame onto the left half (a classic
                // "clone booth" effect). This has to happen to the *sample coordinate*
                // itself, before any other lookup, so blur/brightness/etc. all agree on
                // which half of the picture they're looking at.
                vec2 texCoord = vTexCoord;
                if (uFilterMode == 7) {
                    texCoord.x = texCoord.x > 0.5 ? 1.0 - texCoord.x : texCoord.x;
                }

                vec4 texColor = texture2D(sTexture, texCoord);
                vec3 color = texColor.rgb;

                // 1) Skin smoothing (box-blur blend)
                if (uSmoothing > 0.001) {
                    color = applySkinSmoothing(texCoord, color);
                }

                // 2) Brightness / contrast: contrast pivots around mid-gray (0.5)
                //    so increasing contrast doesn't just wash the image out.
                color = (color - 0.5) * uContrast + 0.5 + uBrightness;

                // 3) Snapchat-style color grade / effect, selected by swiping in the UI.
                if (uFilterMode == 1) {
                    // Grayscale
                    float gray = dot(color, vec3(0.299, 0.587, 0.114));
                    color = vec3(gray);
                } else if (uFilterMode == 2) {
                    // Sepia
                    float gray = dot(color, vec3(0.299, 0.587, 0.114));
                    color = vec3(gray * 1.07 + 0.02, gray * 0.86 + 0.01, gray * 0.63);
                } else if (uFilterMode == 3) {
                    // Invert
                    color = 1.0 - color;
                } else if (uFilterMode == 4) {
                    // Cool (push toward blue)
                    color = vec3(color.r - 0.06, color.g + 0.01, color.b + 0.14);
                } else if (uFilterMode == 5) {
                    // Warm (push toward orange)
                    color = vec3(color.r + 0.14, color.g + 0.05, color.b - 0.10);
                } else if (uFilterMode == 6) {
                    // Vintage: light sepia tint plus a soft vignette toward the edges
                    float gray = dot(color, vec3(0.299, 0.587, 0.114));
                    vec3 sepia = vec3(gray * 1.05 + 0.02, gray * 0.9, gray * 0.7);
                    color = mix(color, sepia, 0.55);
                    float dist = distance(vTexCoord, vec2(0.5));
                    color *= smoothstep(0.85, 0.35, dist);
                }
                // uFilterMode == 7 (mirror) needs no extra color grading — the coordinate
                // fold above already produced the effect.

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), texColor.a);
            }
        """
    }
}
