package com.piashmsuf.manga.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation backed by ML Kit. The first invocation for a given
 * (source, target) pair downloads the language pack — subsequent calls work
 * fully offline.
 */
class TranslatorEngine {

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val ready = mutableSetOf<Pair<String, String>>()

    private val languageId = LanguageIdentification.getClient()

    private fun translatorFor(source: String, target: String): Translator {
        val key = source to target
        return translators.getOrPut(key) {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(opts)
        }
    }

    /** Ensures the model for [source]→[target] is downloaded. */
    suspend fun prepare(source: String, target: String, requireWifi: Boolean = false) {
        val key = source to target
        if (key in ready) return
        val translator = translatorFor(source, target)
        val conditions = DownloadConditions.Builder().apply {
            if (requireWifi) requireWifi()
        }.build()
        suspendCancellableCoroutine<Unit> { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
        ready += key
    }

    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return ""
        val src = if (source == "auto") detectLanguage(text) else source
        if (src.isEmpty() || src == "und") return text
        if (src == target) return text
        prepare(src, target)
        val translator = translatorFor(src, target)
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    suspend fun detectLanguage(text: String): String =
        suspendCancellableCoroutine { cont ->
            languageId.identifyLanguage(text)
                .addOnSuccessListener { tag -> cont.resume(tag ?: "und") }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    fun release() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        ready.clear()
        runCatching { languageId.close() }
    }

    companion object {
        /** Languages we surface in the settings UI. */
        val SUPPORTED_TARGETS: List<Pair<String, String>> = listOf(
            TranslateLanguage.ENGLISH to "English",
            TranslateLanguage.BENGALI to "Bengali",
            TranslateLanguage.HINDI to "Hindi",
            TranslateLanguage.SPANISH to "Spanish",
            TranslateLanguage.FRENCH to "French",
            TranslateLanguage.GERMAN to "German",
            TranslateLanguage.RUSSIAN to "Russian",
            TranslateLanguage.ARABIC to "Arabic",
            TranslateLanguage.INDONESIAN to "Indonesian",
            TranslateLanguage.PORTUGUESE to "Portuguese",
            TranslateLanguage.URDU to "Urdu",
            TranslateLanguage.TURKISH to "Turkish",
            TranslateLanguage.VIETNAMESE to "Vietnamese",
        )

        val SUPPORTED_SOURCES: List<Pair<String, String>> = listOf(
            "auto" to "Auto-detect",
            TranslateLanguage.JAPANESE to "Japanese",
            TranslateLanguage.KOREAN to "Korean",
            TranslateLanguage.CHINESE to "Chinese",
            TranslateLanguage.ENGLISH to "English",
        )
    }
}
