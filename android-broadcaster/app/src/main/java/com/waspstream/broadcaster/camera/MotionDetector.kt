package com.waspstream.broadcaster.camera

import android.graphics.ImageFormat
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * A lightweight motion detection analyzer using frame-differencing on luminance values.
 *
 * Converts each camera frame to grayscale luminance, compares the average luminance
 * between consecutive frames, and signals motion if the delta exceeds a threshold.
 *
 * Includes a 2-second debounce to prevent rapid toggling between IDLE/ACTIVE states.
 * This avoids heavy libraries like OpenCV and relies purely on CameraX's ImageAnalysis.
 */
class MotionDetector(
    private val onMotionDetected: (Boolean) -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        // Threshold raised to 60 to avoid triggering on minor lighting changes
        // (headlights, passing clouds, camera auto-exposure adjustments)
        private const val MOTION_THRESHOLD = 60
        // Process every 6th frame to reduce CPU load and avoid flicker
        private const val FRAME_SKIP = 6
        // Finer grid (32x32 sample) gives better spatial localization of motion
        private const val LUMINANCE_SAMPLE_SIZE = 32
        // 2-second debounce: once motion fires, no new events for 2 seconds
        private const val DEBOUNCE_MS = 2000L
    }

    @Volatile
    private var previousLuminance: Float = -1f
    private var frameCount = 0
    private var isInMotion = false
    private var lastMotionTimeMs = 0L
    private var lastEventTimeMs = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        frameCount++

        // Skip frames to reduce CPU load
        if (frameCount % FRAME_SKIP != 0) {
            imageProxy.close()
            return
        }

        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        try {
            // Only process YUV_420_888 format (standard for CameraX)
            if (image.format != ImageFormat.YUV_420_888) {
                return
            }

            val currentLuminance = computeAverageLuminance(image)
            val nowMs = System.currentTimeMillis()

            if (previousLuminance >= 0) {
                val delta = kotlin.math.abs(currentLuminance - previousLuminance)
                val motion = delta > MOTION_THRESHOLD

                // Debounce: suppress events within DEBOUNCE_MS of the last event
                val withinDebounce = (nowMs - lastEventTimeMs) < DEBOUNCE_MS

                if (motion != isInMotion && !withinDebounce) {
                    isInMotion = motion
                    lastEventTimeMs = nowMs
                    if (motion) {
                        lastMotionTimeMs = nowMs
                    }
                    onMotionDetected(motion)
                }
            }

            previousLuminance = currentLuminance

        } catch (e: Exception) {
            // Silently skip problematic frames
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Compute the average luminance (Y channel) by sampling pixels across the image.
     * Uses the Y (first) plane of the YUV_420_888 image format.
     */
    private fun computeAverageLuminance(image: Image): Float {
        val plane = image.planes[0] // Y plane (luminance)
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val width = image.width
        val height = image.height

        // Sample pixels in a grid pattern
        val stepX = maxOf(1, width / LUMINANCE_SAMPLE_SIZE)
        val stepY = maxOf(1, height / LUMINANCE_SAMPLE_SIZE)

        var totalLuminance = 0
        var sampleCount = 0

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val position = y * rowStride + x * pixelStride
                if (position < buffer.capacity()) {
                    val luminance = buffer.get(position).toInt() and 0xFF
                    totalLuminance += luminance
                    sampleCount++
                }
            }
        }

        return if (sampleCount > 0) totalLuminance.toFloat() / sampleCount else 0f
    }

    /**
     * Reset the motion detector state (call when restarting analysis).
     */
    fun reset() {
        previousLuminance = -1f
        isInMotion = false
    }
}
