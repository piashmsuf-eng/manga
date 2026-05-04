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
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.piashmsuf.manga.R
import com.piashmsuf.manga.databinding.ActivityReaderBinding
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.translate.TranslationCache
import com.piashmsuf.manga.translate.TranslationPipeline
import com.piashmsuf.manga.util.BookmarksStore
import com.piashmsuf.manga.util.Prefs
import com.piashmsuf.manga.util.ProgressStore
import com.piashmsuf.manga.util.runSuspendCatching
import com.google.android.material.slider.Slider
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
    private lateinit var progressStore: ProgressStore
    private lateinit var bookmarksStore: BookmarksStore
    private lateinit var translationCache: TranslationCache

    private val scope: CoroutineScope = MainScope()
    private var translateJob: Job? = null
    private var autoScrollJob: Job? = null
    private var ignoreSlider = false
    private lateinit var mangaUri: String
    private var resumeTarget: Int = 0
    private var hasResumed: Boolean = false

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
        progressStore = ProgressStore(this)
        bookmarksStore = BookmarksStore(this)
        translationCache = TranslationCache(this)

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Manga"
        val uriString = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val kindOrdinal = intent.getIntExtra(EXTRA_KIND, 0)
        mangaUri = uriString
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
        binding.pager.orientation = when (prefs.readingDirection) {
            "vertical" -> ViewPager2.ORIENTATION_VERTICAL
            else -> ViewPager2.ORIENTATION_HORIZONTAL
        }
        // RTL: ViewPager2 reverses direction by inverting layout direction.
        binding.pager.layoutDirection =
            if (prefs.readingDirection == "rtl") View.LAYOUT_DIRECTION_RTL
            else View.LAYOUT_DIRECTION_LTR

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                autoScrollJob?.cancel()
                updatePageUi(position)
                progressStore.put(mangaUri, position)
                if (prefs.autoTranslateInReader) translateCurrentPage(position)
                else binding.translation.visibility = View.GONE
            }
        })

        // Initial slider configuration. Source.pageCount may be 0 for archives
        // that fail to open; clamp so the slider still renders.
        val maxIndex = max(0, source.pageCount - 1)
        binding.pageSlider.valueFrom = 0f
        binding.pageSlider.valueTo = maxIndex.toFloat().coerceAtLeast(1f)
        binding.pageSlider.value = 0f
        binding.pageSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || ignoreSlider) return@OnChangeListener
            val target = value.toInt().coerceIn(0, maxIndex)
            if (target != binding.pager.currentItem) {
                binding.pager.setCurrentItem(target, false)
            }
        })

        binding.brightnessSlider.value = prefs.readerDimAlpha.toFloat()
        applyBrightness(prefs.readerDimAlpha)
        binding.brightnessSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser) return@OnChangeListener
            val v = value.toInt().coerceIn(0, 200)
            prefs.readerDimAlpha = v
            applyBrightness(v)
        })

        binding.btnClose.setOnClickListener { finish() }
        binding.btnTranslate.setOnClickListener { translateCurrentPage(binding.pager.currentItem) }
        binding.btnBookmark.setOnClickListener { toggleBookmark(binding.pager.currentItem) }
        binding.btnBookmark.setOnLongClickListener {
            showBookmarksDialog(); true
        }
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

        binding.tapLeft.setOnClickListener { goPrev() }
        binding.tapRight.setOnClickListener { goNext() }

        // Resume to the last viewed page (only the very first time the pager
        // settles; subsequent listener callbacks still drive normal updates).
        val saved = progressStore.get(mangaUri)
        resumeTarget = saved?.page?.coerceIn(0, max(0, source.pageCount - 1)) ?: 0
        if (resumeTarget > 0) {
            binding.pager.post {
                binding.pager.setCurrentItem(resumeTarget, false)
                hasResumed = true
                Snackbar.make(binding.root, getString(R.string.reader_resume, resumeTarget + 1), Snackbar.LENGTH_SHORT).show()
            }
        }

        updatePageUi(resumeTarget)

        if (prefs.autoTranslateInReader && source.pageCount > 0) {
            binding.pager.post { translateCurrentPage(resumeTarget) }
        }
    }

    private fun updatePageUi(position: Int) {
        binding.pageIndicator.text = getString(R.string.page_progress, position + 1, max(1, source.pageCount))
        ignoreSlider = true
        binding.pageSlider.value = position.toFloat().coerceIn(binding.pageSlider.valueFrom, binding.pageSlider.valueTo)
        ignoreSlider = false
        val isBookmarked = bookmarksStore.isBookmarked(mangaUri, position)
        binding.btnBookmark.alpha = if (isBookmarked) 1f else 0.55f
    }

    private fun applyBrightness(value: Int) {
        binding.dimOverlay.alpha = (value / 255f).coerceIn(0f, 1f)
    }

    private fun goPrev() {
        val target = binding.pager.currentItem - 1
        if (target >= 0) binding.pager.setCurrentItem(target, true)
    }

    private fun goNext() {
        val target = binding.pager.currentItem + 1
        if (target < source.pageCount) binding.pager.setCurrentItem(target, true)
    }

    private fun toggleBookmark(page: Int) {
        val now = bookmarksStore.toggle(mangaUri, page)
        Snackbar.make(
            binding.root,
            if (now) R.string.reader_bookmark_added else R.string.reader_bookmark_removed,
            Snackbar.LENGTH_SHORT,
        ).show()
        binding.btnBookmark.alpha = if (now) 1f else 0.55f
    }

    private fun showBookmarksDialog() {
        val pages = bookmarksStore.get(mangaUri)
        if (pages.isEmpty()) {
            Snackbar.make(binding.root, R.string.empty_bookmarks, Snackbar.LENGTH_SHORT).show()
            return
        }
        val items = pages.map { getString(R.string.page_progress, it + 1, max(1, source.pageCount)) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_jump_to_bookmark)
            .setItems(items) { _, which ->
                val target = pages[which].coerceIn(0, max(0, source.pageCount - 1))
                binding.pager.setCurrentItem(target, true)
            }
            .show()
    }

    private fun translateCurrentPage(index: Int) {
        translateJob?.cancel()
        autoScrollJob?.cancel()
        if (source.pageCount == 0) return
        binding.translationProgress.visibility = View.VISIBLE
        binding.translation.visibility = View.GONE
        translateJob = scope.launch {
            val cached = if (prefs.translationCacheEnabled) {
                withContext(Dispatchers.IO) {
                    translationCache.load(mangaUri, index, prefs.targetLang)
                }
            } else null
            val bitmap = withContext(Dispatchers.IO) { source.decodePage(index) }
            if (bitmap == null) {
                binding.translationProgress.visibility = View.GONE
                return@launch
            }
            val result = cached ?: runSuspendCatching { pipeline.process(bitmap) }
                .onFailure { binding.translation.text = it.localizedMessage ?: "Error" }
                .getOrNull()
            binding.translationProgress.visibility = View.GONE
            if (result == null || result.isEmpty) {
                binding.translation.text = ""
                binding.translation.visibility = View.GONE
                scheduleAutoScroll(index)
                return@launch
            }
            if (cached == null && prefs.translationCacheEnabled) {
                withContext(Dispatchers.IO) { translationCache.save(mangaUri, index, result) }
            }
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
        if (fromIndex >= source.pageCount - 1) return
        val delayMs = prefs.autoScrollDelaySec.coerceAtLeast(1) * 1000L
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            delay(delayMs)
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
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (hideOriginal) Color.argb(255, 255, 255, 255) else Color.argb(200, 0, 0, 0)
        }
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (hideOriginal) Color.BLACK else Color.WHITE
        }
        for (block in blocks) {
            val box: Rect = block.box
            if (box.width() <= 0 || box.height() <= 0) continue
            val pad = (box.height() * 0.08f).coerceAtLeast(4f)
            val rect = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad).coerceAtLeast(0f),
                (box.right + pad).coerceAtMost(source.width.toFloat()),
                (box.bottom + pad).coerceAtMost(source.height.toFloat()),
            )
            canvas.drawRoundRect(rect, 12f, 12f, bg)
            fg.textSize = (rect.height() / 4.5f).coerceAtLeast(source.width / 80f)
            val maxWidth = rect.width() - 16f
            val lines = wrap(block.translated, fg, maxWidth)
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

    override fun onPause() {
        super.onPause()
        // Persist the current position whenever we leave the reader, so that
        // even if the activity is killed without onDestroy running the user
        // can still resume.
        progressStore.put(mangaUri, binding.pager.currentItem)
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
