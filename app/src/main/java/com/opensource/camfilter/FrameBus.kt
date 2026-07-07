package com.opensource.camfilter

import android.graphics.Bitmap

/**
 * Tiny in-process singleton bus.
 *
 * Why this exists: a single physical camera can only be opened by one client at a
 * time. The floating overlay bubble (drawn by [OverlayService], which can appear on
 * top of other apps) can't independently open the camera again — Android would
 * simply deny it, and even if it were allowed, two competing capture sessions is
 * not something any Android device supports for a single lightweight app.
 *
 * Instead, MainActivity's already-filtered GL output is periodically read back as a
 * small [Bitmap] (see MainActivity.maybePublishOverlayFrame) and pushed here. The
 * overlay bubble just displays whatever the latest bitmap is. This keeps the bubble
 * in sync with the real filtered feed without a second camera session.
 */
object FrameBus {
    @Volatile private var latestFrame: Bitmap? = null
    private val listeners = mutableListOf<(Bitmap) -> Unit>()

    @Synchronized
    fun publish(bitmap: Bitmap) {
        latestFrame = bitmap
        listeners.forEach { it(bitmap) }
    }

    @Synchronized
    fun subscribe(listener: (Bitmap) -> Unit) {
        listeners.add(listener)
        latestFrame?.let { listener(it) }
    }

    @Synchronized
    fun unsubscribe(listener: (Bitmap) -> Unit) {
        listeners.remove(listener)
    }
}
