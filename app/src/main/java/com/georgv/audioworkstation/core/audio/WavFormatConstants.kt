package com.georgv.audioworkstation.core.audio

internal const val WavRiffHeaderSizeBytes = 12
internal const val WavChunkHeaderSizeBytes = 8
internal const val WavFmtChunkMinSizeBytes = 16
internal const val WavFmtChannelCountOffset = 2
internal const val WavFmtSampleRateOffset = 4
internal const val WavFmtBitsPerSampleOffset = 14
internal const val WavUnsigned16BitMask = 0xFFFF
internal const val WavCanonicalHeaderSizeBytes = 36
internal const val WavFmtSubchunkPayloadSizeBytes = 16
