package com.georgv.audioworkstation.share

internal class ShareFailure(val status: Int, message: String) : RuntimeException(message)
