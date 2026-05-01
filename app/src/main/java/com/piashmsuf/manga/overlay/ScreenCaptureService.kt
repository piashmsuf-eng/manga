package com.piashmsuf.manga.overlay

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piashmsuf.manga.MangaApp
import com.piashmsuf.manga.R

/**
 * One-shot screen capture service. Started after the user grants
 * MediaProjection consent; captures a single frame, hands it to the running
 * [OverlayService], then stops itself.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("manga-screen-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        captureSingleFrame(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = NotificationCompat.Builder(this, MangaApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun captureSingleFrame(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        projection = mp

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // Some Android versions require a Callback before creating a virtual display.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanup() }
        }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "manga-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        var captured = false
        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                captured = true
                deliverAndStop(cropped)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun deliverAndStop(bitmap: Bitmap) {
        OverlayService.current()?.onFrameCaptured(bitmap)
        // Tear down on a fresh handler tick so the buffer dispatch fully unwinds first.
        handler.post { stopSelf() }
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        cleanup()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 0xFACE
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_data"
        const val ACTION_STOP = "com.piashmsuf.manga.SCREEN_CAPTURE_STOP"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            val stopIntent = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(stopIntent) }
        }

        fun stopPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            return PendingIntent.getService(
                ctx, 2, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
