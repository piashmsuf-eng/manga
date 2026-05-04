package com.piashmsuf.manga

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.piashmsuf.manga.databinding.ActivityMainBinding
import com.piashmsuf.manga.library.Library
import com.piashmsuf.manga.library.LibraryAdapter
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.overlay.OverlayService
import com.piashmsuf.manga.reader.ReaderActivity
import com.piashmsuf.manga.settings.SettingsActivity
import com.piashmsuf.manga.util.Permissions
import com.piashmsuf.manga.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: LibraryAdapter

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // takePersistableUriPermission only accepts read/write flags — the
            // persistable bit is implicit in the call itself.
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

        adapter = LibraryAdapter(::openManga)
        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { openTreeLauncher.launch(null) }
        binding.btnTranslator.setOnClickListener { startTranslator() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
            adapter.submit(items)
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openManga(item: MangaItem) {
        startActivity(ReaderActivity.intent(this, item))
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
