package com.piashmsuf.manga.util

import android.content.Context
import android.content.SharedPreferences

/** Persisted set of favorite manga URI strings. */
class FavoritesStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): Set<String> = sp.getStringSet(KEY, emptySet()) ?: emptySet()

    fun isFavorite(uri: String): Boolean = uri in all()

    /** Toggle and return the new state. */
    fun toggle(uri: String): Boolean {
        val current = all().toMutableSet()
        val nowFavorite = if (uri in current) {
            current -= uri; false
        } else {
            current += uri; true
        }
        sp.edit().putStringSet(KEY, current).apply()
        return nowFavorite
    }

    companion object {
        private const val FILE = "manga_favorites"
        private const val KEY = "favorite_uris"
    }
}
