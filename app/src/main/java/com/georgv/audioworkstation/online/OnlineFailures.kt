package com.georgv.audioworkstation.online

const val HttpUnauthorized = 401

class OnlineApiException(val status: Int, message: String) : Exception(message)

class ShareException(message: String) : Exception(message)
