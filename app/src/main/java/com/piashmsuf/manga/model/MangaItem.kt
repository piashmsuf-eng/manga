package com.piashmsuf.manga.model

import android.net.Uri

/**
 * Represents a single manga / chapter discoverable in the library.
 *
 * - [uri] points either to a folder of images (under SAF, treeUri) or to a
 *   single archive file (CBZ/ZIP).
 * - [kind] tells the reader how to enumerate pages.
 */
data class MangaItem(
    val uri: Uri,
    val title: String,
    val kind: Kind,
    val pageCount: Int,
    val coverUri: Uri?,
) {
    enum class Kind { FOLDER, ARCHIVE }
}
