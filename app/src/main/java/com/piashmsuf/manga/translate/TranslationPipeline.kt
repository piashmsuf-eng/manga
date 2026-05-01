package com.piashmsuf.manga.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.piashmsuf.manga.util.Prefs

/**
 * Single entry-point that combines OCR + translation. Used by both the in-reader
 * auto-translate and the floating overlay pill.
 */
class TranslationPipeline(
    private val prefs: Prefs,
    val ocr: OcrEngine = OcrEngine(),
    val translator: TranslatorEngine = TranslatorEngine(),
) {

    data class Block(val original: String, val translated: String, val box: Rect)
    data class Result(val sourceLang: String, val targetLang: String, val blocks: List<Block>) {
        val isEmpty: Boolean get() = blocks.isEmpty()
        val combinedOriginal: String get() = blocks.joinToString("\n") { it.original }
        val combinedTranslation: String get() = blocks.joinToString("\n") { it.translated }
    }

    suspend fun process(bitmap: Bitmap): Result {
        val target = prefs.targetLang
        val script = scriptFromPref(prefs.ocrScript)

        val (resolvedScript, recognized) = if (script == null) ocr.recognizeAuto(bitmap)
        else resolvedScript(script, ocr.recognize(bitmap, script))

        val sourceLang = if (prefs.sourceLang != "auto") prefs.sourceLang else scriptToLang(resolvedScript)
        val blocks = recognized.textBlocks.mapNotNull { block ->
            val text = block.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val translated = runCatching { translator.translate(text, sourceLang, target) }
                .getOrDefault(text)
            Block(text, translated, block.boundingBox ?: Rect())
        }
        return Result(sourceLang, target, blocks)
    }

    private fun resolvedScript(script: OcrEngine.Script, text: Text): Pair<OcrEngine.Script, Text> = script to text

    private fun scriptFromPref(value: String): OcrEngine.Script? = when (value) {
        "japanese" -> OcrEngine.Script.JAPANESE
        "korean" -> OcrEngine.Script.KOREAN
        "chinese" -> OcrEngine.Script.CHINESE
        "latin" -> OcrEngine.Script.LATIN
        else -> null
    }

    private fun scriptToLang(script: OcrEngine.Script): String = when (script) {
        OcrEngine.Script.JAPANESE -> "ja"
        OcrEngine.Script.KOREAN -> "ko"
        OcrEngine.Script.CHINESE -> "zh"
        OcrEngine.Script.LATIN -> "en"
    }

    fun release() {
        ocr.release()
        translator.release()
    }
}
