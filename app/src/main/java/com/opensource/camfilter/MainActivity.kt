package com.opensource.camfilter

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.opensource.camfilter.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * MainActivity
 * ============
 * Responsibilities:
 *  - Ask for camera permission (and, optionally, the "draw over other apps" permission).
 *  - Own the CameraX lifecycle (ProcessCameraProvider, Preview use case).
 *  - Bridge CameraX's Preview use case to [CameraFilterEngine]'s OpenGL Surface —
 *    this is the actual "frame interception" point: instead of handing CameraX a
 *    PreviewView, we hand it a Surface that feeds a GPU texture we control.
 *  - Keep the Preview use case's target rotation in sync with the device's physical
 *    rotation, so the image stays upright whether you're in portrait or landscape.
 *  - Wire the seek bars to the engine's filter parameters, and let the person swipe
 *    left/right on the preview to cycle through Snapchat-style color filters.
 *  - Start/stop the floating overlay bubble service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: CameraFilterEngine
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    // ---------------------------------------------------------------------------
    // Snapchat-style swipeable filter carousel
    // ---------------------------------------------------------------------------
    // Order here is the order you'll swipe through. Add a new CameraFilterEngine.ColorFilter
    // value plus a shader branch in CameraFilterEngine.kt, then just add one line here to
    // include it in the carousel.
    private val filterCarousel = listOf(
        CameraFilterEngine.ColorFilter.NONE to R.string.filter_none,
        CameraFilterEngine.ColorFilter.GRAYSCALE to R.string.filter_grayscale,
        CameraFilterEngine.ColorFilter.SEPIA to R.string.filter_sepia,
        CameraFilterEngine.ColorFilter.INVERT to R.string.filter_invert,
        CameraFilterEngine.ColorFilter.COOL to R.string.filter_cool,
        CameraFilterEngine.ColorFilter.WARM to R.string.filter_warm,
        CameraFilterEngine.ColorFilter.VINTAGE to R.string.filter_vintage,
        CameraFilterEngine.ColorFilter.MIRROR to R.string.filter_mirror
    )
    private var filterIndex = 0

    private lateinit var gestureDetector: GestureDetector

    private val requestCameraPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraPipeline()
            } else {
                binding.tvStatus.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = getString(R.string.permission_denied)
            }
        }

    private val requestOverlayPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                launchOverlayBubble()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // The engine calls this back on the GL thread the moment its SurfaceTexture
        // is ready. That's our cue that CameraX can safely be bound to it.
        engine = CameraFilterEngine(onSurfaceReady = { surface ->
            runOnUiThread { bindCameraToSurface(surface) }
        })
        binding.glSurfaceView.initialize(engine)

        setupControls()
        setupSwipeGesture()
        showFilterLabel(animate = false)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPipeline()
        } else {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    /** Nothing camera-specific happens here — the real binding happens once the GL surface is ready. */
    private fun startCameraPipeline() {
        binding.tvStatus.visibility = android.view.View.GONE
        // Triggers onSurfaceCreated -> onSurfaceReady -> bindCameraToSurface(surface)
        binding.glSurfaceView.onResume()
    }

    /**
     * This is the key integration point between CameraX and our OpenGL pipeline.
     * Instead of using CameraX's built-in PreviewView (which draws frames for you but
     * gives you no chance to intercept them), we implement Preview.SurfaceProvider
     * ourselves and hand back the Surface that CameraFilterEngine created from its
     * SurfaceTexture/external-OES-texture. Every camera frame CameraX produces gets
     * written straight into that texture, where our fragment shader can filter it.
     */
    private fun bindCameraToSurface(surface: Surface) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val newPreview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .setTargetRotation(currentDisplayRotation())
                .build()
            preview = newPreview

            newPreview.setSurfaceProvider { request ->
                // Tell the SurfaceTexture what resolution to expect *before* handing
                // the Surface back, otherwise the camera and GL texture sizes can
                // disagree and frames will appear stretched/cropped.
                engine.setPreviewResolution(request.resolution.width, request.resolution.height)
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, newPreview)
            } catch (e: Exception) {
                binding.tvStatus.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "Camera bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The activity is declared with android:configChanges in the manifest so Android
     * does NOT destroy/recreate it on rotation (which would tear down and rebuild the
     * whole camera + GL session, causing a visible flicker/restart). Instead this
     * callback fires, and all we need to do is tell CameraX's Preview use case which
     * way the display is now facing — CameraX combines that with the SurfaceTexture's
     * own transform matrix to keep the image upright and correctly cropped.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preview?.targetRotation = currentDisplayRotation()
        binding.glSurfaceView.requestRender()
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun setupControls() {
        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // progress 0..200 -> -1f..+1f
                engine.brightness = (progress - 100) / 100f
                binding.glSurfaceView.requestRender()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // progress 0..200 -> 0f..2f
                engine.contrast = progress / 100f
                binding.glSurfaceView.requestRender()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekSmoothing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // progress 0..100 -> 0f..1f
                engine.smoothing = progress / 100f
                binding.glSurfaceView.requestRender()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnOverlay.setOnClickListener { onOverlayButtonClicked() }
    }

    // ---------------------------------------------------------------------------
    // Snapchat-style swipe-to-change-filter
    // ---------------------------------------------------------------------------

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // Require a clearly horizontal, deliberate swipe so this doesn't fight
                // with vertical scrolling/dragging elsewhere on screen.
                if (abs(dx) > abs(dy) && abs(dx) > 120 && abs(velocityX) > 200) {
                    if (dx < 0) nextFilter() else previousFilter()
                    return true
                }
                return false
            }
        })

        // Swiping anywhere on the live preview cycles filters, exactly like tapping
        // through Snapchat's filter carousel by swiping the camera screen itself.
        binding.glSurfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun nextFilter() {
        filterIndex = (filterIndex + 1) % filterCarousel.size
        applyCurrentFilter()
    }

    private fun previousFilter() {
        filterIndex = (filterIndex - 1 + filterCarousel.size) % filterCarousel.size
        applyCurrentFilter()
    }

    private fun applyCurrentFilter() {
        engine.colorFilter = filterCarousel[filterIndex].first
        binding.glSurfaceView.requestRender()
        showFilterLabel(animate = true)
    }

    /** Briefly shows the current filter's name, like Snapchat's little label when you swipe. */
    private fun showFilterLabel(animate: Boolean) {
        binding.tvFilterLabel.text = getString(filterCarousel[filterIndex].second)
        binding.tvFilterLabel.animate().cancel()
        if (!animate) {
            binding.tvFilterLabel.alpha = 1f
            return
        }
        binding.tvFilterLabel.alpha = 1f
        binding.tvFilterLabel.animate()
            .alpha(0f)
            .setStartDelay(900)
            .setDuration(400)
            .start()
    }

    // ---------------------------------------------------------------------------
    // Floating overlay bubble
    // ---------------------------------------------------------------------------

    private fun onOverlayButtonClicked() {
        if (isOverlayServiceRunning()) {
            stopService(Intent(this, OverlayService::class.java))
            binding.btnOverlay.text = getString(R.string.btn_overlay_start)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermission.launch(intent)
        } else {
            launchOverlayBubble()
        }
    }

    private fun launchOverlayBubble() {
        // Start reading back filtered frames so the bubble has something to show.
        engine.overlayFrameCallback = { bitmap -> FrameBus.publish(bitmap) }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.btnOverlay.text = getString(R.string.btn_overlay_stop)
    }

    private fun isOverlayServiceRunning(): Boolean = OverlayService.isRunning

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
        if (isOverlayServiceRunning()) {
            binding.btnOverlay.text = getString(R.string.btn_overlay_stop)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        engine.release()
        cameraExecutor.shutdown()
    }
}
