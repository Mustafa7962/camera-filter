package com.opensource.camfilter

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * Thin GLSurfaceView subclass. All the actual GL/camera-frame work lives in
 * [CameraFilterEngine]; this class just wires the two together and keeps
 * render mode set to "only redraw when a new camera frame arrives" so the
 * GPU/battery aren't spun at full tilt when nothing changed.
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    fun initialize(engine: CameraFilterEngine) {
        setEGLContextClientVersion(2)
        engine.attachView(this)
        setRenderer(engine)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
