package com.piashmsuf.manga.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.piashmsuf.manga.model.MangaItem
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Reads pages from either a folder (SAF tree) or a CBZ/ZIP archive. The
 * implementations are kept simple on purpose — they enumerate page names once
 * and re-open the underlying stream for each requested page.
 */
sealed class MangaSource {

    abstract val pageCount: Int

    /** Returns a fresh [InputStream] for [index], or null if invalid. */
    abstract fun openPage(index: Int): InputStream?

    /** Convenience helper: decode page [index] into a [Bitmap]. */
    fun decodePage(index: Int): Bitmap? {
        val stream = openPage(index) ?: return null
        return stream.use { BitmapFactory.decodeStream(it) }
    }

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        fun isImage(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return ext in IMAGE_EXTS
        }

        fun fromItem(ctx: Context, item: MangaItem): MangaSource = when (item.kind) {
            MangaItem.Kind.FOLDER -> FolderSource(ctx, item.uri)
            MangaItem.Kind.ARCHIVE -> ArchiveSource(ctx, item.uri)
        }
    }
}

/** Source backed by a SAF tree URI containing image files. */
class FolderSource(private val ctx: Context, private val tree: Uri) : MangaSource() {

    private val pages: List<Uri> by lazy {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return@lazy emptyList<Uri>()
        root.listFiles()
            .asSequence()
            .filter { it.isFile && it.name?.let { n -> isImage(n) } == true }
            .sortedBy { it.name?.lowercase(Locale.US) ?: "" }
            .map { it.uri }
            .toList()
    }

    override val pageCount: Int get() = pages.size

    override fun openPage(index: Int): InputStream? {
        if (index !in pages.indices) return null
        return ctx.contentResolver.openInputStream(pages[index])
    }
}

/**
 * Source backed by a single CBZ/ZIP file. ZIPs cannot be random-accessed
 * cheaply via the platform APIs (especially when the file lives behind SAF), so
 * we walk the archive sequentially each time. For typical chapter sizes this
 * is fast enough and avoids extracting the archive to disk.
 */
class ArchiveSource(private val ctx: Context, private val archive: Uri) : MangaSource() {

    private val pageNames: List<String> by lazy {
        val names = mutableListOf<String>()
        runCatching {
            ctx.contentResolver.openInputStream(archive)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry: ZipEntry = zip.nextEntry ?: break
                        if (!entry.isDirectory && isImage(entry.name)) {
                            names += entry.name
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        names.sortedBy { it.lowercase(Locale.US) }
    }

    override val pageCount: Int get() = pageNames.size

    override fun openPage(index: Int): InputStream? {
        if (index !in pageNames.indices) return null
        val target = pageNames[index]
        val stream = ctx.contentResolver.openInputStream(archive) ?: return null
        val zip = ZipInputStream(stream)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == target) return zip
            zip.closeEntry()
        }
        zip.close()
        return null
    }
}
