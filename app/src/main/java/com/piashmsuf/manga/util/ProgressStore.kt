package com.piashmsuf.manga.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-manga reading progress + last-read timestamp. Backed by a dedicated
 * SharedPreferences file so it doesn't pollute the main settings store.
 *
 * Each entry is keyed by the manga's URI string and stores the last viewed
 * page index and the wall-clock timestamp of when it was viewed.
 */
class ProgressStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Entry(val page: Int, val timestamp: Long)

    fun get(uri: String): Entry? {
        val raw = sp.getString(uri, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            Entry(obj.optInt("page", 0), obj.optLong("ts", 0L))
        }.getOrNull()
    }

    fun put(uri: String, page: Int) {
        val obj = JSONObject().apply {
            put("page", page)
            put("ts", System.currentTimeMillis())
        }
        sp.edit().putString(uri, obj.toString()).apply()
    }

    fun all(): Map<String, Entry> {
        val out = mutableMapOf<String, Entry>()
        for ((k, v) in sp.all) {
            val raw = v as? String ?: continue
            val entry = runCatching {
                val o = JSONObject(raw)
                Entry(o.optInt("page", 0), o.optLong("ts", 0L))
            }.getOrNull() ?: continue
            out[k] = entry
        }
        return out
    }

    fun clear(uri: String) {
        sp.edit().remove(uri).apply()
    }

    companion object {
        private const val FILE = "manga_progress"
    }
}
