package com.piashmsuf.manga.util

import android.content.Context
import androidx.preference.PreferenceManager

/** Lightweight wrapper around SharedPreferences for app-wide settings. */
class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    var sourceLang: String
        get() = sp.getString(KEY_SOURCE, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    var targetLang: String
        get() = sp.getString(KEY_TARGET, "en") ?: "en"
        set(value) = sp.edit().putString(KEY_TARGET, value).apply()

    var ocrScript: String
        get() = sp.getString(KEY_SCRIPT, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_SCRIPT, value).apply()

    var autoTranslateInReader: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO, value).apply()

    var libraryRoots: Set<String>
        get() = sp.getStringSet(KEY_ROOTS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_ROOTS, value).apply()

    var pillX: Int
        get() = sp.getInt(KEY_PILL_X, -1)
        set(value) = sp.edit().putInt(KEY_PILL_X, value).apply()

    var pillY: Int
        get() = sp.getInt(KEY_PILL_Y, -1)
        set(value) = sp.edit().putInt(KEY_PILL_Y, value).apply()

    companion object {
        private const val KEY_SOURCE = "source_lang"
        private const val KEY_TARGET = "target_lang"
        private const val KEY_SCRIPT = "ocr_script"
        private const val KEY_AUTO = "auto_translate_reader"
        private const val KEY_ROOTS = "library_roots"
        private const val KEY_PILL_X = "pill_x"
        private const val KEY_PILL_Y = "pill_y"
    }
}
