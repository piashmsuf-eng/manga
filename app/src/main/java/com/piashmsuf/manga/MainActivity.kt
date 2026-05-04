package com.piashmsuf.manga

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.piashmsuf.manga.about.AboutActivity
import com.piashmsuf.manga.databinding.ActivityMainBinding
import com.piashmsuf.manga.history.HistoryActivity
import com.piashmsuf.manga.library.ContinueAdapter
import com.piashmsuf.manga.library.Library
import com.piashmsuf.manga.library.LibraryAdapter
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.overlay.OverlayService
import com.piashmsuf.manga.reader.ReaderActivity
import com.piashmsuf.manga.settings.SettingsActivity
import com.piashmsuf.manga.util.FavoritesStore
import com.piashmsuf.manga.util.Permissions
import com.piashmsuf.manga.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var favorites: FavoritesStore
    private lateinit var adapter: LibraryAdapter
    private lateinit var continueAdapter: ContinueAdapter

    private var allItems: List<MangaItem> = emptyList()
    private var currentQuery: String = ""

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            prefs.libraryRoots = prefs.libraryRoots + uri.toString()
            refreshLibrary()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice ignored — we only ask for cosmetic foreground notifs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        prefs = Prefs(this)
        favorites = FavoritesStore(this)

        adapter = LibraryAdapter(::openManga, ::toggleFavorite)
        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.adapter = adapter

        continueAdapter = ContinueAdapter(::openManga)
        binding.continueRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.continueRow.adapter = continueAdapter

        binding.fabAdd.setOnClickListener { openTreeLauncher.launch(null) }
        binding.btnTranslator.setOnClickListener { startTranslator() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty().trim()
                applyFilter()
            }
        })

        when (prefs.libraryFilter) {
            "favorites" -> binding.chipFavorites.isChecked = true
            "in_progress" -> binding.chipInProgress.isChecked = true
            else -> binding.chipAll.isChecked = true
        }
        binding.filterChips.setOnCheckedStateChangeListener { group, _ ->
            prefs.libraryFilter = when (group.checkedChipId) {
                binding.chipFavorites.id -> "favorites"
                binding.chipInProgress.id -> "in_progress"
                else -> "all"
            }
            applyFilter()
        }

        if (Permissions.needsPostNotifications() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortMenu(); true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortMenu() {
        // Anchor on the toolbar — `action_sort` is a menu item, not a view in
        // the activity's content tree, so findViewById is unreliable.
        val popup = PopupMenu(this, binding.toolbar)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)
        when (prefs.librarySort) {
            "title_desc" -> popup.menu.findItem(R.id.sort_title_desc)?.isChecked = true
            "last_read" -> popup.menu.findItem(R.id.sort_last_read)?.isChecked = true
            "pages" -> popup.menu.findItem(R.id.sort_pages)?.isChecked = true
            else -> popup.menu.findItem(R.id.sort_title_asc)?.isChecked = true
        }
        popup.setOnMenuItemClickListener { item ->
            prefs.librarySort = when (item.itemId) {
                R.id.sort_title_desc -> "title_desc"
                R.id.sort_last_read -> "last_read"
                R.id.sort_pages -> "pages"
                else -> "title"
            }
            refreshLibrary()
            true
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary()
    }

    private fun refreshLibrary() {
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { Library.scan(this@MainActivity) }
            binding.progress.visibility = View.GONE
            allItems = items
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filter = prefs.libraryFilter
        val query = currentQuery.lowercase()
        var items = allItems
        if (filter == "favorites") items = items.filter { it.favorite }
        if (filter == "in_progress") items = items.filter { it.isInProgress }
        if (query.isNotEmpty()) items = items.filter { it.title.lowercase().contains(query) }
        adapter.submit(items)

        val inProgress = allItems.filter { it.isInProgress }
            .sortedByDescending { it.lastReadAt }
            .take(10)
        val showRow = inProgress.isNotEmpty() && filter != "favorites"
        binding.continueLabel.visibility = if (showRow) View.VISIBLE else View.GONE
        binding.continueRow.visibility = if (showRow) View.VISIBLE else View.GONE
        if (showRow) continueAdapter.submit(inProgress)

        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(
            when {
                allItems.isEmpty() -> R.string.empty_library
                filter == "favorites" -> R.string.empty_favorites
                query.isNotEmpty() -> R.string.empty_search
                else -> R.string.empty_library
            }
        )
    }

    private fun openManga(item: MangaItem) {
        startActivity(ReaderActivity.intent(this, item))
    }

    private fun toggleFavorite(item: MangaItem) {
        favorites.toggle(item.uri.toString())
        // Re-apply filter from the in-memory list rather than re-scanning SAF.
        allItems = allItems.map {
            if (it.uri == item.uri) it.copy(favorite = !it.favorite) else it
        }
        applyFilter()
    }

    private fun startTranslator() {
        if (!Permissions.canDrawOverlays(this)) {
            Snackbar.make(binding.root, R.string.need_overlay_permission, Snackbar.LENGTH_LONG)
                .setAction(R.string.grant) {
                    startActivity(Permissions.overlaySettingsIntent(this))
                }
                .show()
            return
        }
        OverlayService.start(this)
        Snackbar.make(binding.root, R.string.translator_started, Snackbar.LENGTH_SHORT).show()
    }
}
