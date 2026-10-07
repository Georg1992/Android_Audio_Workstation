package com.georgv.audioworkstation.server

class OnlineFailure(val status: Int, message: String) : Exception(message)
