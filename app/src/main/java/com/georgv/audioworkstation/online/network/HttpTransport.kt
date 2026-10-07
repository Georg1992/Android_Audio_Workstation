package com.georgv.audioworkstation.online.network

import java.io.OutputStream

class HttpCall(
    val method: String,
    val path: String,
    val token: String?,
    val contentType: String?,
    val contentLength: Long,
    val writeBody: ((OutputStream) -> Unit)?,
)

class HttpResult(val status: Int, val body: String)

interface HttpTransport {
    fun exchange(call: HttpCall): HttpResult
}
