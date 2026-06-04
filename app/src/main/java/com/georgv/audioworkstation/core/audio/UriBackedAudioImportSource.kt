package com.georgv.audioworkstation.core.audio

import android.content.ContentResolver
import android.net.Uri

/**
 * [AudioImportSource] that exposes a [Uri] for platform decoders such as [android.media.MediaExtractor].
 * [ContentResolverAudioImportSource] is the production implementation.
 */
interface UriBackedAudioImportSource : AudioImportSource {
    val contentResolver: ContentResolver
    val uri: Uri
}
