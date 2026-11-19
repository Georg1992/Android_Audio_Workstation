package com.georgv.audioworkstation.ui.playback

import com.georgv.audioworkstation.core.audio.convert.AacCodec
import com.georgv.audioworkstation.core.audio.convert.AacToPcm
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The one mix player reads PCM here. The bytes behind it are the stored AAC pieces. */
internal class AacMixSource(
    private val remote: MixSource = UrlMixSource(),
    private val codec: AacCodec,
) : MixSource {
    private val pcm = AacToPcm(codec)
    private val cache = AacByteCache(remote)

    override fun open(url: String, start: Long, endInclusive: Long): InputStream {
        val bytes = pcm.wav(url, start, endInclusive) { from, to -> cache.read(url, from, to) }
        return ByteArrayInputStream(bytes)
    }
}
