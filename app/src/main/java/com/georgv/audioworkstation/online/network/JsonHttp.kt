package com.georgv.audioworkstation.online.network

import com.georgv.audioworkstation.online.OnlineApiException
import java.io.File
import org.json.JSONObject

class JsonHttp(private val transport: HttpTransport) {
    fun post(path: String, token: String?, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return parse(
            transport.exchange(
                HttpCall(
                    method = "POST",
                    path = path,
                    token = token,
                    contentType = "application/json; charset=utf-8",
                    contentLength = bytes.size.toLong(),
                    writeBody = { it.write(bytes) },
                ),
            ),
        )
    }

    fun delete(path: String, token: String) {
        parse(
            transport.exchange(
                HttpCall(
                    method = "DELETE",
                    path = path,
                    token = token,
                    contentType = null,
                    contentLength = 0,
                    writeBody = null,
                ),
            ),
        )
    }

    fun putFile(path: String, token: String, file: File): JSONObject =
        parse(
            transport.exchange(
                HttpCall(
                    method = "PUT",
                    path = path,
                    token = token,
                    contentType = "application/octet-stream",
                    contentLength = file.length(),
                    writeBody = { output -> file.inputStream().use { input -> input.copyTo(output) } },
                ),
            ),
        )

    private fun parse(result: HttpResult): JSONObject {
        if (result.status !in 200..299) {
            throw OnlineApiException(result.status, result.body.ifBlank { "HTTP ${result.status}" })
        }
        if (result.body.isBlank()) return JSONObject()
        return JSONObject(result.body)
    }
}

internal fun JSONObject.requiredText(name: String): String {
    if (!has(name) || isNull(name)) throw OnlineApiException(0, "$name is required")
    val value = getString(name)
    if (value.isBlank()) throw OnlineApiException(0, "$name is required")
    return value
}
