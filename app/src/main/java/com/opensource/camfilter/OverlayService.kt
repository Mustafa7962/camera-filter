package com.opensource.camfilter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat

/**
 * OverlayService
 * ===============
 * Shows a small draggable "bubble" on top of other apps (using the
 * SYSTEM_ALERT_WINDOW / TYPE_APPLICATION_OVERLAY window type) that mirrors the
 * filtered camera feed, refreshed a few times per second via [FrameBus].
 *
 * IMPORTANT — please read this before assuming this replaces your real camera:
 * This bubble is an *in-app floating preview window*, similar to a chat-heads
 * bubble or a picture-in-picture widget. It is a legitimate, fully-permitted
 * Android mechanism, and it's genuinely useful as a floating "how do I look"
 * self-view while you use another app.
 *
 * It is NOT a system-wide virtual camera. Tapping "use camera" inside a third-party
 * app (e.g. a video call app) will still open that app's own unmodified camera feed
 * — this overlay has no way to intercept or replace that. Unprivileged Android apps
 * have no public API to register a new camera device or to substitute the system
 * camera's output system-wide; that capability is restricted to platform/OEM code
 * or requires root plus a custom camera HAL / virtual video driver (comparable to
 * v4l2loopback on desktop Linux), which is well outside what an unprivileged,
 * lightweight, sideloadable APK can legitimately do — on stock Android, EMUI/HarmonyOS,
 * or any other major OEM skin. If your real goal is "use my filtered face inside
 * Zoom/Meet/WhatsApp," the practical options are: (a) a rooted device with a
 * community virtual-camera Magisk module, or (b) shipping filters *inside* your own
 * video-calling app via its SDK, not as a system-wide passthrough.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleImage: ImageView? = null

    private val frameListener: (android.graphics.Bitmap) -> Unit = { bitmap ->
        bubbleImage?.post { bubbleImage?.setImageBitmap(bitmap) }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
        FrameBus.subscribe(frameListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        FrameBus.unsubscribe(frameListener)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_floating, null)
        bubbleView = view
        bubbleImage = view.findViewById(R.id.bubbleImage)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        // Simple drag-to-move touch handling for the bubble.
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.bubbleClose).setOnClickListener {
            stopSelf()
        }

        windowManager.addView(view, params)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 42
        @Volatile var isRunning: Boolean = false
            private set
    }
}
