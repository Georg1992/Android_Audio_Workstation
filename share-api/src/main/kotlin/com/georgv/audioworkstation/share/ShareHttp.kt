package com.georgv.audioworkstation.share

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal class ShareHttp(
    private val config: ShareApiConfig,
    private val tokens: CognitoAccessTokens,
    private val manifest: ShareManifest,
    private val objects: SharedObjects,
) {
    fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestHeaders.getFirst(GATEWAY_HEADER) != config.originSecret) {
                throw ShareFailure(403, "forbidden")
            }
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            if (method == "GET" && path == "/health") {
                send(exchange, 200, """{"ok":true}""")
                return
            }
            if (method != "POST") throw ShareFailure(404, "not found")
            val accountId = accountId(exchange)
            when {
                path == "/shared-projects" -> createProject(exchange, accountId)
                path.endsWith("/uploads") -> requestUpload(exchange, accountId, projectId(path))
                path.endsWith("/files") -> commitFile(exchange, accountId, projectId(path))
                path.endsWith("/downloads") -> download(exchange, accountId, projectId(path))
                else -> throw ShareFailure(404, "not found")
            }
        } catch (failure: ShareFailure) {
            send(exchange, failure.status, """{"error":"${failure.message}"}""")
        } catch (error: Exception) {
            send(exchange, 500, """{"error":"share failed"}""")
        }
    }

    private fun createProject(exchange: HttpExchange, accountId: String) {
        val body = json(exchange)
        val id = id(body.optString("id"), "project id")
        val title = title(body.optString("title"))
        manifest.saveProject(id, accountId, title)
        send(exchange, 200, """{"id":"$id"}""")
    }

    private fun requestUpload(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.requireOwner(projectId, accountId)
        val body = json(exchange)
        val hash = storageKey(body.optString("contentHash"))
        val size = size(body.optLong("size", -1))
        id(body.optString("trackId"), "track id")
        val stat = objects.stat(hash)
        if (stat.exists && stat.size != size) throw ShareFailure(409, "size mismatch")
        val grant = uploadGrant(hash, stat.exists)
        if (grant.alreadyStored) {
            send(exchange, 200, """{"storageKey":"$hash","alreadyStored":true}""")
            return
        }
        val target = objects.uploadTarget(hash, size)
        val headers = JSONObject()
        target.headers.forEach { (name, value) -> headers.put(name, value) }
        val response = JSONObject()
            .put("storageKey", hash)
            .put("alreadyStored", false)
            .put("uploadUrl", target.url)
            .put("headers", headers)
        send(exchange, 200, response.toString())
    }

    private fun commitFile(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.requireOwner(projectId, accountId)
        val body = json(exchange)
        val trackId = id(body.optString("trackId"), "track id")
        val hash = storageKey(body.optString("contentHash"))
        val key = storageKey(body.optString("storageKey"))
        if (key != hash) throw ShareFailure(400, "storage key must be the content hash")
        val size = size(body.optLong("size", -1))
        val stat = objects.stat(hash)
        if (!stat.exists) throw ShareFailure(409, "object missing")
        if (stat.size != size) throw ShareFailure(409, "size mismatch")
        manifest.saveFile(projectId, trackId, hash, key, size)
        send(exchange, 200, """{"storageKey":"$hash"}""")
    }

    private fun download(exchange: HttpExchange, accountId: String, projectId: String) {
        val hash = storageKey(json(exchange).optString("contentHash"))
        if (!manifest.hasFile(projectId, accountId, hash)) throw ShareFailure(404, "not found")
        val url = objects.downloadUrl(hash)
        send(exchange, 200, JSONObject().put("downloadUrl", url).toString())
    }

    private fun accountId(exchange: HttpExchange): String {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: throw ShareFailure(401, "unauthorized")
        if (!header.startsWith("Bearer ")) throw ShareFailure(401, "unauthorized")
        return tokens.accountId(header.removePrefix("Bearer ").trim())
    }

    private fun projectId(path: String): String {
        val parts = path.split("/")
        if (parts.size != 4 || parts[1] != "shared-projects") throw ShareFailure(404, "not found")
        return id(parts[2], "project id")
    }

    private fun json(exchange: HttpExchange): JSONObject {
        val length = exchange.requestHeaders.getFirst("Content-Length")?.toIntOrNull()
            ?: throw ShareFailure(400, "content length required")
        if (length !in 0..MAX_BODY) throw ShareFailure(400, "body too large")
        val text = String(exchange.requestBody.readNBytes(length), StandardCharsets.UTF_8)
        return try {
            JSONObject(text)
        } catch (error: Exception) {
            throw ShareFailure(400, "invalid json")
        }
    }

    private fun id(value: String, label: String): String {
        if (!Id.matches(value)) throw ShareFailure(400, "$label is invalid")
        return value
    }

    private fun title(value: String): String {
        if (value.length > MAX_TITLE) throw ShareFailure(400, "title is too long")
        return value
    }

    private fun size(value: Long): Long {
        if (value <= 0L) throw ShareFailure(400, "size is invalid")
        return value
    }

    private fun send(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val GATEWAY_HEADER = "X-Share-Gateway"
        const val MAX_BODY = 65_536
        const val MAX_TITLE = 200
        val Id = Regex("^[A-Za-z0-9_-]{1,80}$")
    }
}
