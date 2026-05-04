package com.piashmsuf.manga.translate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit Text Recognition with a script-aware factory. ML Kit ships
 * separate models per script, so we expose them as [Script] values and
 * pick the matching recognizer at runtime.
 */
class OcrEngine {

    enum class Script { LATIN, JAPANESE, KOREAN, CHINESE }

    private val cache = mutableMapOf<Script, TextRecognizer>()

    private fun recognizerFor(script: Script): TextRecognizer = cache.getOrPut(script) {
        when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
    }

    suspend fun recognize(bitmap: Bitmap, script: Script): Text =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizerFor(script).process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    /** Convenience: try Japanese, Korean, Chinese, Latin in turn and pick the longest result. */
    suspend fun recognizeAuto(bitmap: Bitmap): Pair<Script, Text> {
        val candidates = listOf(Script.JAPANESE, Script.KOREAN, Script.CHINESE, Script.LATIN)
        var best: Pair<Script, Text>? = null
        for (script in candidates) {
            val text = runCatching { recognize(bitmap, script) }.getOrNull() ?: continue
            val len = text.text.length
            if (best == null || len > best.second.text.length) best = script to text
        }
        return best ?: (Script.LATIN to recognize(bitmap, Script.LATIN))
    }

    fun release() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
