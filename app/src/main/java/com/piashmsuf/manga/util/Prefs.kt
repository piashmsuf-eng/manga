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

    /** Hide the original (foreign-language) text on the page; show only the
     *  translated overlay. */
    var hideOriginalInReader: Boolean
        get() = sp.getBoolean(KEY_HIDE_ORIGINAL, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_ORIGINAL, value).apply()

    /** Auto-advance to the next page once the current page has been translated. */
    var autoScrollPages: Boolean
        get() = sp.getBoolean(KEY_AUTO_SCROLL, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SCROLL, value).apply()

    /** How long to wait after translation completes before scrolling to the
     *  next page (seconds). */
    var autoScrollDelaySec: Int
        get() = (sp.getString(KEY_AUTO_SCROLL_DELAY, "5") ?: "5").toIntOrNull() ?: 5
        set(value) = sp.edit().putString(KEY_AUTO_SCROLL_DELAY, value.toString()).apply()

    /** Continuous-capture interval for the floating pill's live mode (ms). */
    var liveModeIntervalMs: Int
        get() = (sp.getString(KEY_LIVE_INTERVAL, "1500") ?: "1500").toIntOrNull() ?: 1500
        set(value) = sp.edit().putString(KEY_LIVE_INTERVAL, value.toString()).apply()

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
        private const val KEY_HIDE_ORIGINAL = "hide_original_in_reader"
        private const val KEY_AUTO_SCROLL = "auto_scroll_pages"
        private const val KEY_AUTO_SCROLL_DELAY = "auto_scroll_delay_sec"
        private const val KEY_LIVE_INTERVAL = "live_mode_interval_ms"
        private const val KEY_ROOTS = "library_roots"
        private const val KEY_PILL_X = "pill_x"
        private const val KEY_PILL_Y = "pill_y"
    }
}
