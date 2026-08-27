package com.webuntis.dashboard.util

import android.webkit.MimeTypeMap

/**
 * Central place to guess a file's MIME type from its name/extension.
 *
 * The previous ad-hoc checks only recognised pdf/png/jpg/docx/xlsx and fell back to
 * "application/octet-stream" for everything else — including very common image types like
 * .gif, .webp, .bmp or .heic. Gallery/viewer apps generally refuse to open a file whose
 * declared MIME type doesn't match an image type, which is why some downloaded attachments
 * (especially images) couldn't be opened. [guessMimeType] first asks Android's built-in,
 * comprehensive [MimeTypeMap] and only falls back to a small manual table for the handful of
 * types that map is known to miss on some Android versions (e.g. heic/heif).
 */
object FileTypeUtils {

    fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"

        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }

        return when (ext) {
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "webp" -> "image/webp"
            "bmp"  -> "image/bmp"
            "gif"  -> "image/gif"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "doc"  -> "application/msword"
            "xls"  -> "application/vnd.ms-excel"
            "ppt"  -> "application/vnd.ms-powerpoint"
            "zip"  -> "application/zip"
            "txt"  -> "text/plain"
            else   -> "application/octet-stream"
        }
    }

    /**
     * Prefers a server-declared content type when it looks meaningful, otherwise derives one
     * from the filename extension. Some server responses report a generic/empty content type
     * even for attachments whose extension makes the real type obvious.
     */
    fun resolveMimeType(declaredContentType: String?, filename: String): String {
        if (!declaredContentType.isNullOrBlank() && declaredContentType != "application/octet-stream") {
            return declaredContentType
        }
        return guessMimeType(filename)
    }
}
