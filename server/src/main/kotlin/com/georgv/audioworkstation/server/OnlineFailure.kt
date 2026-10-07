package com.georgv.audioworkstation.server

class OnlineFailure(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
