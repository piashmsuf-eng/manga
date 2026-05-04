package com.piashmsuf.manga.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.piashmsuf.manga.R
import com.piashmsuf.manga.about.AboutActivity
import com.piashmsuf.manga.translate.TranslationCache
import com.piashmsuf.manga.translate.TranslatorEngine

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            val src = findPreference<ListPreference>("source_lang")
            val tgt = findPreference<ListPreference>("target_lang")
            populate(src, TranslatorEngine.SUPPORTED_SOURCES)
            populate(tgt, TranslatorEngine.SUPPORTED_TARGETS)
            findPreference<SwitchPreferenceCompat>("auto_translate_reader")
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                TranslationCache(ctx).clearAll()
                Toast.makeText(ctx, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AboutActivity::class.java))
                true
            }
        }

        private fun populate(pref: ListPreference?, options: List<Pair<String, String>>) {
            if (pref == null) return
            pref.entries = options.map { it.second }.toTypedArray()
            pref.entryValues = options.map { it.first }.toTypedArray()
            if (pref.value == null) pref.setValueIndex(0)
        }
    }
}
