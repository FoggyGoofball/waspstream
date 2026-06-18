package com.waspstream.broadcaster.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Manages the CameraX lifecycle and binds Preview, ImageCapture, and ImageAnalysis use cases.
 *
 * Configuration:
 * - ImageAnalysis runs at 480p resolution with STRATEGY_KEEP_ONLY_LATEST.
 * - ImageCapture captures full-resolution JPEG snapshots, encoded as base64 for RTDB.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val motionDetector: MotionDetector
) {

    companion object {
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    // Dedicated thread for image analysis
    private val analysisThread = HandlerThread("CameraAnalysis").apply { start() }
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CameraAnalysisExecutor")
    }

    // Callback for when a snapshot is captured — returns base64-encoded JPEG string
    var onSnapshotCaptured: ((String) -> Unit)? = null

    private val snapshotDirectory: File = File(context.cacheDir, "snapshots").also {
        it.mkdirs()
    }

    /**
     * Start the camera and bind all use cases.
     */
    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // Preview use case
        val preview = Preview.Builder()
            .build()
        preview.setSurfaceProvider(previewView.getSurfaceProvider())

        // ImageCapture use case (high-res for snapshots)
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()

        // ImageAnalysis use case (low-res 480p, keep only latest)
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, motionDetector) }

        // Bind all use cases to lifecycle
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
            imageAnalysis
        )
    }

    /**
     * Capture a JPEG snapshot, encode it as a base64 data URI, and call [onSnapshotCaptured].
     * The base64 string includes the "data:image/jpeg;base64," prefix for direct use in <img> tags.
     */
    fun captureSnapshot() {
        val capture = imageCapture ?: return
        val file = File(snapshotDirectory, "snapshot_${System.currentTimeMillis()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Read the saved JPEG file and encode as base64
                    try {
                        val bytes = file.readBytes()
                        val base64 = Base64.getEncoder().encodeToString(bytes)
                        val dataUri = "data:image/jpeg;base64,$base64"
                        onSnapshotCaptured?.invoke(dataUri)
                    } catch (e: Exception) {
                        android.util.Log.e("CameraManager", "Base64 encoding failed", e)
                    } finally {
                        file.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("CameraManager", "Snapshot capture failed", exception)
                }
            }
        )
    }

    /**
     * Stop the camera and release resources.
     */
    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisThread.quitSafely()
        analysisExecutor.shutdown()
    }

    /**
     * Check if the camera is currently initialized.
     */
    fun isActive(): Boolean = camera != null
}
