package com.piashmsuf.manga.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.piashmsuf.manga.model.MangaItem
import com.piashmsuf.manga.reader.MangaSource
import com.piashmsuf.manga.util.FavoritesStore
import com.piashmsuf.manga.util.Prefs
import com.piashmsuf.manga.util.ProgressStore
import java.util.Locale

/** Scans every persisted SAF tree for archive files and image folders. */
object Library {

    fun scan(ctx: Context): List<MangaItem> {
        val prefs = Prefs(ctx)
        val favorites = FavoritesStore(ctx).all()
        val progress = ProgressStore(ctx).all()
        val results = mutableListOf<MangaItem>()
        for (rootStr in prefs.libraryRoots) {
            val rootUri = runCatching { Uri.parse(rootStr) }.getOrNull() ?: continue
            val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: continue
            walk(root, results, favorites, progress)
        }
        return sort(results, prefs.librarySort)
    }

    private fun sort(items: List<MangaItem>, key: String): List<MangaItem> = when (key) {
        "title_desc" -> items.sortedByDescending { it.title.lowercase(Locale.US) }
        "added" -> items // SAF doesn't expose a creation timestamp; fall back to title.
            .sortedBy { it.title.lowercase(Locale.US) }
        "last_read" -> items.sortedWith(
            compareByDescending<MangaItem> { it.lastReadAt }
                .thenBy { it.title.lowercase(Locale.US) }
        )
        "pages" -> items.sortedByDescending { it.pageCount }
        else -> items.sortedBy { it.title.lowercase(Locale.US) }
    }

    private fun walk(
        node: DocumentFile,
        out: MutableList<MangaItem>,
        favorites: Set<String>,
        progress: Map<String, ProgressStore.Entry>,
    ) {
        if (!node.isDirectory) return

        val children = node.listFiles()
        val imageChildren = children.filter { it.isFile && it.name?.let(MangaSource.Companion::isImage) == true }
        if (imageChildren.isNotEmpty()) {
            val sortedImages = imageChildren.sortedBy { it.name?.lowercase(Locale.US) ?: "" }
            val key = node.uri.toString()
            val entry = progress[key]
            out += MangaItem(
                uri = node.uri,
                title = node.name ?: "Untitled",
                kind = MangaItem.Kind.FOLDER,
                pageCount = sortedImages.size,
                coverUri = sortedImages.firstOrNull()?.uri,
                favorite = key in favorites,
                lastReadPage = entry?.page,
                lastReadAt = entry?.timestamp ?: 0L,
            )
        }

        for (child in children) {
            if (child.isDirectory) {
                walk(child, out, favorites, progress)
            } else if (child.isFile && isArchive(child.name)) {
                val key = child.uri.toString()
                val entry = progress[key]
                out += MangaItem(
                    uri = child.uri,
                    title = child.name?.substringBeforeLast('.') ?: "Archive",
                    kind = MangaItem.Kind.ARCHIVE,
                    pageCount = -1,
                    coverUri = null,
                    favorite = key in favorites,
                    lastReadPage = entry?.page,
                    lastReadAt = entry?.timestamp ?: 0L,
                )
            }
        }
    }

    private fun isArchive(name: String?): Boolean {
        val lower = name?.lowercase(Locale.US) ?: return false
        return lower.endsWith(".cbz") || lower.endsWith(".zip")
    }
}
