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
import android.os.Looper
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piashmsuf.manga.MangaApp
import com.piashmsuf.manga.R

/**
 * Screen capture service. Two modes:
 *   - one-shot: captures a single frame, hands it to [OverlayService], then
 *     stops itself (used by the pill's tap-to-translate flow).
 *   - continuous: keeps the [MediaProjection] alive and emits a frame at most
 *     every [intervalMs] ms (used by the pill's live-translation mode).
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("manga-screen-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile private var continuous: Boolean = false
    @Volatile private var intervalMs: Int = 1500
    @Volatile private var lastDeliveryNs: Long = 0L
    @Volatile private var captured: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            // Android 14+: foreground type MUST be declared on this call AND
            // posted before getMediaProjection(). Failing here silently aborts
            // the whole pill-tap flow, so log and toast.
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.capture_failed, t.localizedMessage ?: t.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        continuous = intent?.getBooleanExtra(EXTRA_CONTINUOUS, false) ?: false
        intervalMs = intent?.getIntExtra(EXTRA_INTERVAL_MS, 1500) ?: 1500
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "missing extras: resultCode=$resultCode data=$data")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            captureSingleFrame(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "captureSingleFrame failed", t)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.capture_failed, t.localizedMessage ?: t.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
            cleanup()
            stopSelf()
        }
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
        // Release any leftover resources from a previous (in-flight) capture
        // before allocating new ones — guards against the user tapping the pill
        // twice quickly and granting consent each time.
        cleanup()
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

        captured = false
        lastDeliveryNs = 0L
        reader.setOnImageAvailableListener({ r ->
            if (!continuous && captured) {
                // One-shot mode: we already delivered, ignore further frames.
                runCatching { r.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            // Continuous mode: rate-limit to roughly intervalMs between deliveries.
            if (continuous) {
                val now = System.nanoTime()
                val elapsedMs = (now - lastDeliveryNs) / 1_000_000
                if (lastDeliveryNs != 0L && elapsedMs < intervalMs) {
                    runCatching { r.acquireLatestImage()?.close() }
                    return@setOnImageAvailableListener
                }
                lastDeliveryNs = now
            }
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
                if (continuous) deliverContinuous(cropped) else deliverAndStop(cropped)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun deliverAndStop(bitmap: Bitmap) {
        // Hop to the main thread so OverlayService's coroutine state is
        // mutated from a single, predictable thread.
        val service = OverlayService.current()
        if (service != null) {
            Handler(Looper.getMainLooper()).post { service.onFrameCaptured(bitmap) }
        }
        // Tear down on a fresh handler tick so the buffer dispatch fully unwinds first.
        handler.post { stopSelf() }
    }

    private fun deliverContinuous(bitmap: Bitmap) {
        // Live mode: keep the MediaProjection alive and just feed the frame
        // to OverlayService. OverlayService is responsible for skipping frames
        // while a translation is in flight so we don't pile up work.
        val service = OverlayService.current()
        if (service != null) {
            Handler(Looper.getMainLooper()).post { service.onFrameCaptured(bitmap) }
        } else {
            bitmap.recycle()
        }
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
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 0xFACE
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_data"
        private const val EXTRA_CONTINUOUS = "extra_continuous"
        private const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val ACTION_STOP = "com.piashmsuf.manga.SCREEN_CAPTURE_STOP"

        fun start(
            ctx: Context,
            resultCode: Int,
            data: Intent,
            continuous: Boolean = false,
            intervalMs: Int = 1500,
        ) {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_CONTINUOUS, continuous)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
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
