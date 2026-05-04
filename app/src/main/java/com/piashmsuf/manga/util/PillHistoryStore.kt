package com.piashmsuf.manga.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rolling history of recent pill translations (most recent first).
 * Capped at [MAX] entries; older items are dropped when new ones are pushed.
 */
class PillHistoryStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Entry(
        val timestamp: Long,
        val sourceLang: String,
        val targetLang: String,
        val original: String,
        val translated: String,
    )

    fun all(): List<Entry> {
        val raw = sp.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    timestamp = o.optLong("ts", 0L),
                    sourceLang = o.optString("src", "auto"),
                    targetLang = o.optString("tgt", "en"),
                    original = o.optString("o", ""),
                    translated = o.optString("t", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun push(entry: Entry) {
        val current = all().toMutableList()
        // De-dupe consecutive identical translations (e.g. live-mode capturing
        // the same screen twice in a row).
        if (current.firstOrNull()?.translated == entry.translated &&
            current.firstOrNull()?.original == entry.original
        ) return
        current.add(0, entry)
        while (current.size > MAX) current.removeAt(current.lastIndex)
        write(current)
    }

    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    private fun write(items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject().apply {
                    put("ts", e.timestamp)
                    put("src", e.sourceLang)
                    put("tgt", e.targetLang)
                    put("o", e.original)
                    put("t", e.translated)
                }
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val FILE = "manga_pill_history"
        private const val KEY = "items"
        const val MAX = 10
    }
}
