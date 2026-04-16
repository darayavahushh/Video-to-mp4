package com.videoconverter.app

import android.net.Uri

/**
 * Represents a video file selected by the user.
 *
 * @param uri             The content URI of the video file
 * @param displayName     The file name shown on screen
 * @param absolutePath    The resolved absolute path (may be null if unresolvable)
 * @param sizeBytes       File size in bytes
 * @param lastModified    Original last-modified timestamp (epoch ms)
 */
data class VideoFile(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String?,
    val sizeBytes: Long,
    val lastModified: Long
)
