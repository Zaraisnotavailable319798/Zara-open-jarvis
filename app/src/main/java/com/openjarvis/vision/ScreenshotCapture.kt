package com.openjarvis.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class ScreenshotCapture(
    private val context: Context
) {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null

    private var virtualDisplay: VirtualDisplay? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var isCapturing = false

    /**
     * MediaProjection permission must be requested by an Activity.
     *
     * This method only verifies that the MediaProjection
     * system service is available.
     */
    fun startCapture(): Boolean {
        return try {
            context.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Initialize screenshot capture using an already-approved
     * MediaProjection instance.
     */
    fun initialize(
        projection: MediaProjection
    ): Bitmap? {

        release()

        try {
            val windowManager =
                context.getSystemService(
                    WindowManager::class.java
                ) ?: return null

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            if (screenWidth <= 0 || screenHeight <= 0) {
                return null
            }

            handlerThread = HandlerThread(
                "ScreenshotThread"
            ).apply {
                start()
            }

            handler = Handler(
                handlerThread!!.looper
            )

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            mediaProjection = projection

            val surface = imageReader?.surface
                ?: return null

            virtualDisplay = projection.createVirtualDisplay(
                "ZaraScreenshot",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )

            isCapturing = virtualDisplay != null

            return null

        } catch (e: Exception) {
            e.printStackTrace()
            release()
            return null
        }
    }

    /**
     * Capture the latest available screen image.
     */
    fun capture(): Bitmap? {

        if (!isCapturing) {
            return null
        }

        val reader = imageReader
            ?: return null

        return try {

            val image = reader.acquireLatestImage()
                ?: return null

            image.use { img ->

                val planes = img.planes

                if (planes.isEmpty()) {
                    return null
                }

                val plane = planes[0]

                val buffer: ByteBuffer = plane.buffer

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride

                if (pixelStride <= 0 || rowStride <= 0) {
                    return null
                }

                val rowPadding =
                    rowStride - pixelStride * img.width

                val bitmapWidth =
                    img.width + rowPadding / pixelStride

                val bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    img.height,
                    Bitmap.Config.ARGB_8888
                )

                buffer.rewind()

                bitmap.copyPixelsFromBuffer(buffer)

                if (bitmapWidth != img.width) {

                    val croppedBitmap =
                        Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            img.width,
                            img.height
                        )

                    bitmap.recycle()

                    croppedBitmap
                } else {
                    bitmap
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Release all MediaProjection and image resources.
     */
    fun release() {

        isCapturing = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null

        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {
        }

        handlerThread = null
        handler = null

        screenWidth = 0
        screenHeight = 0
        screenDensity = 0
    }

    companion object {

        @Volatile
        private var instance: ScreenshotCapture? = null

        fun getInstance(
            context: Context
        ): ScreenshotCapture {

            return instance ?: synchronized(this) {

                instance ?: ScreenshotCapture(
                    context.applicationContext
                ).also {
                    instance = it
                }
            }
        }
    }
}
