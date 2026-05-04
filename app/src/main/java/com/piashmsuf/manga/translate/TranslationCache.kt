package com.piashmsuf.manga.translate

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed cache of OCR + translation results, keyed by
 * (manga URI, page index, target language). Lets the reader skip re-running
 * the full pipeline on pages it has already translated. Files are tiny
 * JSON blobs in cacheDir, so a normal cache eviction is harmless.
 */
class TranslationCache(context: Context) {

    private val root: File = File(context.cacheDir, "translations").apply { mkdirs() }

    fun load(uri: String, page: Int, targetLang: String): TranslationPipeline.Result? {
        val file = fileFor(uri, page, targetLang)
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val sourceLang = obj.optString("source", "auto")
            val target = obj.optString("target", targetLang)
            val arr = obj.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                TranslationPipeline.Block(
                    original = b.optString("o"),
                    translated = b.optString("t"),
                    box = Rect(
                        b.optInt("l", 0),
                        b.optInt("u", 0),
                        b.optInt("r", 0),
                        b.optInt("d", 0),
                    ),
                )
            }
            TranslationPipeline.Result(sourceLang, target, blocks)
        }.getOrNull()
    }

    fun save(uri: String, page: Int, result: TranslationPipeline.Result) {
        val file = fileFor(uri, page, result.targetLang)
        val obj = JSONObject().apply {
            put("source", result.sourceLang)
            put("target", result.targetLang)
            val arr = JSONArray()
            for (b in result.blocks) {
                arr.put(
                    JSONObject().apply {
                        put("o", b.original)
                        put("t", b.translated)
                        put("l", b.box.left)
                        put("u", b.box.top)
                        put("r", b.box.right)
                        put("d", b.box.bottom)
                    }
                )
            }
            put("blocks", arr)
        }
        runCatching { file.writeText(obj.toString()) }
    }

    fun clearAll() {
        root.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(uri: String, page: Int, target: String): File {
        val key = sha1("$uri|$page|$target")
        return File(root, "$key.json")
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
