package com.opensource.camfilter

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.widget.SeekBar
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.opensource.camfilter.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MainActivity
 * ============
 * Responsibilities:
 *  - Ask for camera permission (and, optionally, the "draw over other apps" permission).
 *  - Own the CameraX lifecycle (ProcessCameraProvider, Preview use case).
 *  - Bridge CameraX's Preview use case to [CameraFilterEngine]'s OpenGL Surface —
 *    this is the actual "frame interception" point: instead of handing CameraX a
 *    PreviewView, we hand it a Surface that feeds a GPU texture we control.
 *  - Wire the seek bars / toggle buttons to the engine's filter parameters.
 *  - Start/stop the floating overlay bubble service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: CameraFilterEngine
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

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

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()

            preview.setSurfaceProvider { request ->
                // Tell the SurfaceTexture what resolution to expect *before* handing
                // the Surface back, otherwise the camera and GL texture sizes can
                // disagree and frames will appear stretched/cropped.
                engine.setPreviewResolution(request.resolution.width, request.resolution.height)
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { }
            }

            val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                binding.tvStatus.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "Camera bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
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

        val toggles = listOf(binding.btnFilterNone, binding.btnFilterGrayscale, binding.btnFilterSepia)
        fun selectToggle(selected: ToggleButton, mode: CameraFilterEngine.ColorFilter) {
            toggles.forEach { it.isChecked = it === selected }
            engine.colorFilter = mode
            binding.glSurfaceView.requestRender()
        }
        binding.btnFilterNone.setOnClickListener {
            selectToggle(binding.btnFilterNone, CameraFilterEngine.ColorFilter.NONE)
        }
        binding.btnFilterGrayscale.setOnClickListener {
            selectToggle(binding.btnFilterGrayscale, CameraFilterEngine.ColorFilter.GRAYSCALE)
        }
        binding.btnFilterSepia.setOnClickListener {
            selectToggle(binding.btnFilterSepia, CameraFilterEngine.ColorFilter.SEPIA)
        }

        binding.btnOverlay.setOnClickListener { onOverlayButtonClicked() }
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
