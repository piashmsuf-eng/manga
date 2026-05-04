package com.piashmsuf.manga.model

import android.net.Uri

/**
 * Represents a single manga / chapter discoverable in the library.
 *
 * - [uri] points either to a folder of images (under SAF, treeUri) or to a
 *   single archive file (CBZ/ZIP).
 * - [kind] tells the reader how to enumerate pages.
 * - [favorite] / [lastReadPage] / [lastReadAt] are non-source-of-truth view
 *   fields populated by the library scanner from the favorites and progress
 *   stores. They drive the "Continue reading" row, sort orders, and the star
 *   badge on cards.
 */
data class MangaItem(
    val uri: Uri,
    val title: String,
    val kind: Kind,
    val pageCount: Int,
    val coverUri: Uri?,
    val favorite: Boolean = false,
    val lastReadPage: Int? = null,
    val lastReadAt: Long = 0L,
) {
    enum class Kind { FOLDER, ARCHIVE }

    val isInProgress: Boolean
        get() = lastReadPage != null && lastReadPage > 0 &&
            (pageCount <= 0 || lastReadPage < pageCount - 1)
}
