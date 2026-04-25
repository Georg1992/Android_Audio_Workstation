package com.georgv.audioworkstation.core.audio

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

/**
 * [AudioImportSource] backed by an Android [ContentResolver] / [Uri].
 *
 * The importer is allowed to call [open] more than once (e.g. once to validate the WAV header,
 * again to copy the audio frames), so each call must return a fresh independent stream.
 */
class ContentResolverAudioImportSource(
    private val resolver: ContentResolver,
    private val uri: Uri
) : AudioImportSource {
    override fun open(): InputStream? = resolver.openInputStream(uri)
}
