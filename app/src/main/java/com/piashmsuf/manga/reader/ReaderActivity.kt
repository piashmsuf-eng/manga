package com.piashmsuf.manga.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.piashmsuf.manga.databinding.ActivityReaderBinding
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.translate.TranslationPipeline
import com.piashmsuf.manga.util.Prefs
import com.piashmsuf.manga.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var prefs: Prefs
    private lateinit var pipeline: TranslationPipeline
    private lateinit var source: MangaSource
    private lateinit var adapter: PageAdapter
    private val scope: CoroutineScope = MainScope()
    private var translateJob: Job? = null
    private var autoScrollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        pipeline = TranslationPipeline(prefs)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Manga"
        val uriString = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val kindOrdinal = intent.getIntExtra(EXTRA_KIND, 0)
        val item = MangaItem(
            uri = Uri.parse(uriString),
            title = title,
            kind = MangaItem.Kind.values()[kindOrdinal],
            pageCount = -1,
            coverUri = null,
        )
        binding.title.text = title
        source = MangaSource.fromItem(this, item)

        adapter = PageAdapter(source)
        binding.pager.adapter = adapter

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Cancel any pending auto-scroll when the user manually moves
                // pages — we don't want to fight a swipe that just happened.
                autoScrollJob?.cancel()
                binding.pageIndicator.text = "${position + 1} / ${source.pageCount}"
                if (prefs.autoTranslateInReader) translateCurrentPage(position)
                else binding.translation.visibility = View.GONE
            }
        })
        binding.pageIndicator.text = "1 / ${max(1, source.pageCount)}"
        binding.btnClose.setOnClickListener { finish() }
        binding.btnTranslate.setOnClickListener { translateCurrentPage(binding.pager.currentItem) }
        binding.btnAuto.isChecked = prefs.autoTranslateInReader
        binding.btnAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoTranslateInReader = checked
            if (checked) translateCurrentPage(binding.pager.currentItem)
            else binding.translation.visibility = View.GONE
        }
        binding.btnAutoScroll.isChecked = prefs.autoScrollPages
        binding.btnAutoScroll.setOnCheckedChangeListener { _, checked ->
            prefs.autoScrollPages = checked
            if (!checked) autoScrollJob?.cancel()
        }

        if (prefs.autoTranslateInReader && source.pageCount > 0) {
            binding.pager.post { translateCurrentPage(0) }
        }
    }

    private fun translateCurrentPage(index: Int) {
        translateJob?.cancel()
        autoScrollJob?.cancel()
        if (source.pageCount == 0) return
        binding.translationProgress.visibility = View.VISIBLE
        binding.translation.visibility = View.GONE
        translateJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { source.decodePage(index) }
            if (bitmap == null) {
                binding.translationProgress.visibility = View.GONE
                return@launch
            }
            val result = runSuspendCatching { pipeline.process(bitmap) }
                .onFailure { binding.translation.text = it.localizedMessage ?: "Error" }
                .getOrNull()
            binding.translationProgress.visibility = View.GONE
            if (result == null || result.isEmpty) {
                binding.translation.text = ""
                binding.translation.visibility = View.GONE
                // Even on "no text", honor auto-scroll so the user keeps reading.
                scheduleAutoScroll(index)
                return@launch
            }
            // Render an overlay on the current page covering the original text
            // with the translation. When hideOriginalInReader is on the bottom
            // caption is suppressed entirely — the translation lives on the page.
            val overlay = renderOverlay(bitmap, result.blocks, prefs.hideOriginalInReader)
            adapter.setOverlay(index, overlay)

            if (prefs.hideOriginalInReader) {
                binding.translation.visibility = View.GONE
            } else {
                binding.translation.text = buildString {
                    for (block in result.blocks) {
                        append(block.original).append("\n→ ").append(block.translated).append("\n\n")
                    }
                }.trim()
                binding.translation.visibility = View.VISIBLE
            }
            scheduleAutoScroll(index)
        }
    }

    private fun scheduleAutoScroll(fromIndex: Int) {
        if (!prefs.autoScrollPages) return
        if (fromIndex >= source.pageCount - 1) return // already on the last page
        val delayMs = prefs.autoScrollDelaySec.coerceAtLeast(1) * 1000L
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            delay(delayMs)
            // Re-check in case the user manually swiped or toggled auto-scroll off
            // while we were waiting.
            if (!prefs.autoScrollPages) return@launch
            val currentItem = binding.pager.currentItem
            if (currentItem != fromIndex) return@launch
            binding.pager.setCurrentItem(currentItem + 1, true)
        }
    }

    private fun renderOverlay(
        source: Bitmap,
        blocks: List<TranslationPipeline.Block>,
        hideOriginal: Boolean,
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        // When hiding originals we paint a fully opaque white box so the
        // foreign text underneath is invisible. When showing both we use a
        // softer, dark-translucent box so the original text remains legible.
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (hideOriginal) Color.argb(255, 255, 255, 255) else Color.argb(200, 0, 0, 0)
        }
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (hideOriginal) Color.BLACK else Color.WHITE
        }
        for (block in blocks) {
            val box: Rect = block.box
            if (box.width() <= 0 || box.height() <= 0) continue
            // Inflate the box slightly so the underlying foreign text doesn't
            // peek out around the corners.
            val pad = (box.height() * 0.08f).coerceAtLeast(4f)
            val rect = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad).coerceAtLeast(0f),
                (box.right + pad).coerceAtMost(source.width.toFloat()),
                (box.bottom + pad).coerceAtMost(source.height.toFloat()),
            )
            canvas.drawRoundRect(rect, 12f, 12f, bg)
            // Pick a text size that fits the box height comfortably (roughly
            // 4 lines of text inside the box, but never below 14sp-ish).
            fg.textSize = (rect.height() / 4.5f).coerceAtLeast(source.width / 80f)
            val maxWidth = rect.width() - 16f
            val lines = wrap(block.translated, fg, maxWidth)
            // If the lines are taller than the box, shrink until they fit.
            while (lines.size * fg.textSize * 1.1f > rect.height() && fg.textSize > 10f) {
                fg.textSize -= 1f
            }
            var y = rect.top + fg.textSize
            for (line in lines) {
                if (y > rect.bottom) break
                canvas.drawText(line, rect.left + 8f, y, fg)
                y += fg.textSize * 1.1f
            }
        }
        return output
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val words = text.split(' ', '\n').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(candidate) <= maxWidth) {
                current.clear(); current.append(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current.clear(); current.append(w)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        autoScrollJob?.cancel()
        if (::adapter.isInitialized) adapter.release()
        scope.cancel()
        if (::pipeline.isInitialized) pipeline.release()
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_KIND = "extra_kind"

        fun intent(ctx: Context, item: MangaItem): Intent =
            Intent(ctx, ReaderActivity::class.java).apply {
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_URI, item.uri.toString())
                putExtra(EXTRA_KIND, item.kind.ordinal)
            }
    }
}


