package com.piashmsuf.manga.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Per-manga bookmark page indices. Keyed by manga URI string; the value is
 * a JSON array of integers (page indices). Pages can be quickly checked for
 * bookmark status while reading.
 */
class BookmarksStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(uri: String): List<Int> {
        val raw = sp.getString(uri, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getInt(it) }.sorted()
        }.getOrDefault(emptyList())
    }

    fun isBookmarked(uri: String, page: Int): Boolean = page in get(uri)

    /** Toggle and return the new state. */
    fun toggle(uri: String, page: Int): Boolean {
        val current = get(uri).toMutableSet()
        val nowBookmarked = if (page in current) {
            current -= page; false
        } else {
            current += page; true
        }
        write(uri, current.sorted())
        return nowBookmarked
    }

    fun clear(uri: String) {
        sp.edit().remove(uri).apply()
    }

    private fun write(uri: String, pages: List<Int>) {
        if (pages.isEmpty()) {
            sp.edit().remove(uri).apply()
            return
        }
        val arr = JSONArray()
        for (p in pages) arr.put(p)
        sp.edit().putString(uri, arr.toString()).apply()
    }

    companion object {
        private const val FILE = "manga_bookmarks"
    }
}
