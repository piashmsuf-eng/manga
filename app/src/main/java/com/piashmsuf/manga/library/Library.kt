package com.piashmsuf.manga.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.reader.MangaSource
import com.piashmsuf.manga.util.Prefs
import java.util.Locale

/** Scans every persisted SAF tree for archive files and image folders. */
object Library {

    fun scan(ctx: Context): List<MangaItem> {
        val prefs = Prefs(ctx)
        val results = mutableListOf<MangaItem>()
        for (rootStr in prefs.libraryRoots) {
            val rootUri = runCatching { Uri.parse(rootStr) }.getOrNull() ?: continue
            val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: continue
            walk(root, results)
        }
        // Stable ordering for the UI.
        return results.sortedBy { it.title.lowercase(Locale.US) }
    }

    private fun walk(node: DocumentFile, out: MutableList<MangaItem>) {
        if (!node.isDirectory) return

        val children = node.listFiles()
        val imageChildren = children.filter { it.isFile && it.name?.let(MangaSource.Companion::isImage) == true }
        // A directory containing images is treated as a single manga/chapter.
        if (imageChildren.isNotEmpty()) {
            val sortedImages = imageChildren.sortedBy { it.name?.lowercase(Locale.US) ?: "" }
            out += MangaItem(
                uri = node.uri,
                title = node.name ?: "Untitled",
                kind = MangaItem.Kind.FOLDER,
                pageCount = sortedImages.size,
                coverUri = sortedImages.firstOrNull()?.uri,
            )
        }

        for (child in children) {
            if (child.isDirectory) {
                walk(child, out)
            } else if (child.isFile && isArchive(child.name)) {
                out += MangaItem(
                    uri = child.uri,
                    title = child.name?.substringBeforeLast('.') ?: "Archive",
                    kind = MangaItem.Kind.ARCHIVE,
                    pageCount = -1, // computed lazily by the source
                    coverUri = null,
                )
            }
        }
    }

    private fun isArchive(name: String?): Boolean {
        val lower = name?.lowercase(Locale.US) ?: return false
        return lower.endsWith(".cbz") || lower.endsWith(".zip")
    }
}
