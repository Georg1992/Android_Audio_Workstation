package com.georgv.audioworkstation.core.audio.convert

internal fun androidWavToAac(): AudioConverter = WavToAac(MediaCodecAac())

internal fun androidAacCodec(): AacCodec = MediaCodecAac()
