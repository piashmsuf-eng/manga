package com.piashmsuf.manga.overlay

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piashmsuf.manga.MainActivity
import com.piashmsuf.manga.MangaApp
import com.piashmsuf.manga.R
import com.piashmsuf.manga.databinding.OverlayPillBinding
import com.piashmsuf.manga.databinding.OverlayPanelBinding
import com.piashmsuf.manga.translate.TranslationPipeline
import com.piashmsuf.manga.util.PillHistoryStore
import com.piashmsuf.manga.util.Prefs
import com.piashmsuf.manga.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hosts the floating translation pill. The pill is draggable; tapping it asks
 * the system for a [android.media.projection.MediaProjection] (delegated to a
 * transparent activity), captures one frame, runs OCR + translation, and shows
 * the result in an inline panel.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var pipeline: TranslationPipeline
    private lateinit var historyStore: PillHistoryStore

    private var pillView: View? = null
    private var pillBinding: OverlayPillBinding? = null
    private var panelView: View? = null

    /** Theme-aware context for inflating overlay layouts. The bare Service
     *  context does not resolve Material3 attributes like
     *  `?attr/selectableItemBackgroundBorderless`, which causes
     *  `Error inflating class <unknown>` on overlay_pill.xml line 32. */
    private val themedContext: Context by lazy {
        ContextThemeWrapper(this, R.style.Theme_Manga)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var translateJob: Job? = null

    private var foregroundStarted = false

    /** True when the user has enabled "live mode" via long-press. While true,
     *  ScreenCaptureService keeps the MediaProjection alive and feeds us
     *  frames at [Prefs.liveModeIntervalMs]. */
    @Volatile private var liveMode: Boolean = false

    /** Guards against translation pile-up when frames arrive faster than
     *  OCR + translate can keep up. */
    @Volatile private var translating: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1) Always call startForeground first — if we don't and onCreate throws
        //    later, the system raises ForegroundServiceDidNotStartInTimeException
        //    and the user just sees the launcher ("auto close").
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = Prefs(this)
            pipeline = TranslationPipeline(prefs)
            historyStore = PillHistoryStore(this)
            startForegroundCompat()
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            instance = null
            toast(getString(R.string.translator_failed_to_start, t.localizedMessage ?: t.javaClass.simpleName))
            stopSelf()
            return
        }

        // 2) Re-check the overlay permission — the user could have revoked
        //    it between MainActivity's check and the service actually starting.
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted at service start")
            toast(getString(R.string.need_overlay_permission))
            instance = null
            stopSelf()
            return
        }

        // 3) Add the pill window. addView throws BadTokenException on a few
        //    OEM ROMs even when canDrawOverlays() returns true; surface it as
        //    a clear error rather than a silent service crash.
        try {
            showPill()
        } catch (t: Throwable) {
            Log.e(TAG, "showPill failed", t)
            toast(getString(R.string.translator_failed_to_start, t.localizedMessage ?: t.javaClass.simpleName))
            instance = null
            stopSelf()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun startForegroundCompat() {
        val tapIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, MangaApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.stop), stopPendingIntent())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun showPill() {
        if (pillView != null) return
        val binding = OverlayPillBinding.inflate(LayoutInflater.from(themedContext))
        pillBinding = binding
        val view: View = binding.root

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.pillX >= 0) prefs.pillX else 24
            y = if (prefs.pillY >= 0) prefs.pillY else 200
        }

        attachDragAndTap(view, binding, lp)
        wm.addView(view, lp)
        pillView = view
    }

    private fun attachDragAndTap(view: View, binding: OverlayPillBinding, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longPressFired = false
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onPillLongPress()
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    longPressFired = false
                    view.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) > 10 || abs(dy) > 10)) {
                        moved = true
                        view.removeCallbacks(longPressRunnable)
                    }
                    lp.x = (startX + dx).toInt()
                    lp.y = (startY + dy).toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressFired) onPillTap()
                    prefs.pillX = lp.x
                    prefs.pillY = lp.y
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
        binding.btnClose.setOnClickListener { stopSelf() }
    }

    private fun onPillTap() {
        // Single-shot translate: ask for screen-capture consent, capture one
        // frame, then ScreenCaptureService stops itself.
        if (liveMode) {
            // Already in live mode — do nothing; frames are flowing already.
            toast(getString(R.string.live_mode_on))
            return
        }
        val intent = MediaProjectionRequestActivity.intent(this, continuous = false)
        startActivity(intent)
    }

    private fun onPillLongPress() {
        if (liveMode) {
            // Turning live mode OFF.
            liveMode = false
            ScreenCaptureService.stop(this)
            updateLiveIndicator()
            toast(getString(R.string.live_mode_off))
            return
        }
        // Turning live mode ON — grab consent and start continuous capture.
        liveMode = true
        updateLiveIndicator()
        toast(getString(R.string.live_mode_on))
        val intent = MediaProjectionRequestActivity.intent(
            this,
            continuous = true,
            intervalMs = prefs.liveModeIntervalMs,
        )
        startActivity(intent)
    }

    private fun updateLiveIndicator() {
        val binding = pillBinding ?: return
        binding.root.setBackgroundResource(
            if (liveMode) R.drawable.bg_pill_live else R.drawable.bg_pill
        )
    }

    /** Called by [ScreenCaptureService] once a frame is captured. */
    fun onFrameCaptured(bitmap: android.graphics.Bitmap) {
        if (liveMode && translating) {
            // A previous frame is still being processed — drop this one to
            // avoid pile-up. The next frame from ScreenCaptureService will
            // catch us up to the current screen state.
            bitmap.recycle()
            return
        }
        if (!liveMode) translateJob?.cancel()
        translateJob = scope.launch {
            translating = true
            try {
                if (!liveMode) showPanelLoading()
                val result = runSuspendCatching { pipeline.process(bitmap) }
                    .onFailure { showPanelError(it.localizedMessage ?: "Error") }
                    .getOrNull() ?: return@launch
                showPanelResult(result)
            } finally {
                translating = false
            }
        }
    }

    private fun showPanelLoading() {
        ensurePanel { panel ->
            panel.progress.visibility = View.VISIBLE
            panel.text.text = getString(R.string.translating)
        }
    }

    private fun showPanelError(message: String) {
        ensurePanel { panel ->
            panel.progress.visibility = View.GONE
            panel.text.text = message
        }
    }

    private fun showPanelResult(result: TranslationPipeline.Result) {
        ensurePanel { panel ->
            panel.progress.visibility = View.GONE
            panel.text.text = if (result.isEmpty) {
                getString(R.string.no_text_found)
            } else buildString {
                for (block in result.blocks) {
                    append(block.original).append("\n→ ").append(block.translated).append("\n\n")
                }
            }.trim()
        }
        if (!result.isEmpty) {
            historyStore.push(
                PillHistoryStore.Entry(
                    timestamp = System.currentTimeMillis(),
                    sourceLang = result.sourceLang,
                    targetLang = result.targetLang,
                    original = result.combinedOriginal,
                    translated = result.combinedTranslation,
                )
            )
        }
    }

    private inline fun ensurePanel(block: (OverlayPanelBinding) -> Unit) {
        val existing = panelView
        val binding: OverlayPanelBinding
        if (existing == null) {
            binding = OverlayPanelBinding.inflate(LayoutInflater.from(themedContext))
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 64
            }
            binding.btnClose.setOnClickListener {
                runCatching { wm.removeView(binding.root) }
                panelView = null
            }
            binding.btnCopy.setOnClickListener {
                val cm = ContextCompat.getSystemService(this, android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("translation", binding.text.text.toString()))
            }
            wm.addView(binding.root, lp)
            panelView = binding.root
            binding.root.tag = binding
        } else {
            binding = existing.tag as OverlayPanelBinding
        }
        block(binding)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        // If onCreate failed earlier we've already called stopSelf(); make that
        // explicit to the framework so it doesn't try to redeliver the intent.
        if (!foregroundStarted) return START_NOT_STICKY
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        liveMode = false
        translateJob?.cancel()
        scope.cancel()
        runCatching { pillView?.let { wm.removeView(it) } }
        runCatching { panelView?.let { wm.removeView(it) } }
        pillBinding = null
        pipeline.release()
        // Stop screen capture, if any
        ScreenCaptureService.stop(this)
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 0xCAFE
        const val ACTION_STOP = "com.piashmsuf.manga.OVERLAY_STOP"

        @Volatile
        private var instance: OverlayService? = null

        fun current(): OverlayService? = instance

        fun start(ctx: Context) {
            val intent = Intent(ctx, OverlayService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
