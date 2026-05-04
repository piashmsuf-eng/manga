package com.piashmsuf.manga.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.piashmsuf.manga.BuildConfig
import com.piashmsuf.manga.R
import com.piashmsuf.manga.databinding.ActivityAboutBinding

private const val GITHUB_URL = "https://github.com/piashmsuf-eng/manga"

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_about)

        binding.version.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.description.setText(R.string.about_description)
        binding.developer.setText(R.string.about_developer)
        binding.github.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
