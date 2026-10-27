package com.georgv.audioworkstation.share

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets
import org.json.JSONArray
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
            when {
                method == "GET" && path == "/health" -> send(exchange, 200, """{"ok":true}""")
                method == "GET" && path == "/shared-projects" -> {
                    accountId(exchange)
                    listProjects(exchange)
                }
                method == "POST" && path == "/shared-projects" -> createProject(exchange, accountId(exchange))
                path.startsWith("/shared-projects/") -> routeProject(exchange, method, path)
                else -> throw ShareFailure(404, "not found")
            }
        } catch (failure: ShareFailure) {
            send(exchange, failure.status, """{"error":"${failure.message}"}""")
        } catch (error: Exception) {
            send(exchange, 500, """{"error":"share failed"}""")
        }
    }

    private fun routeProject(exchange: HttpExchange, method: String, path: String) {
        val parts = path.split("/")
        if (parts.size < 3 || parts[1] != "shared-projects") throw ShareFailure(404, "not found")
        val projectId = id(parts[2], "project id")
        val accountId = accountId(exchange)
        val action = parts.drop(3)
        when {
            method == "POST" && action == listOf("card") -> updateCard(exchange, accountId, projectId)
            method == "POST" && action == listOf("owner") -> setOwner(exchange, accountId, projectId)
            method == "POST" && action == listOf("uploads") -> requestUpload(exchange, accountId, projectId)
            method == "POST" && action == listOf("files") -> commitFile(exchange, accountId, projectId)
            method == "POST" && action == listOf("downloads") -> download(exchange, accountId, projectId)
            method == "POST" && action == listOf("image") -> beginImage(exchange, accountId, projectId)
            method == "POST" && action == listOf("image", "commit") -> commitImage(exchange, accountId, projectId)
            method == "GET" && action == listOf("image") -> readImage(exchange, projectId)
            method == "POST" && action == listOf("playback") -> playback(exchange, projectId)
            method == "DELETE" && action.isEmpty() -> removeProject(exchange, accountId, projectId)
            else -> throw ShareFailure(404, "not found")
        }
    }

    private fun listProjects(exchange: HttpExchange) {
        val projects = JSONArray()
        for (project in manifest.listProjects()) {
            val row = JSONObject()
                .put("id", project.id)
                .put("title", project.title)
                .put("tags", storedTags(project.tags))
            if (project.imageHash == null) {
                row.put("imageHash", JSONObject.NULL)
            } else {
                row.put("imageHash", project.imageHash)
            }
            row.put("ownerName", project.ownerName)
            projects.put(row)
        }
        send(exchange, 200, JSONObject().put("projects", projects).toString())
    }

    private fun updateCard(exchange: HttpExchange, accountId: String, projectId: String) {
        val body = json(exchange)
        val cardTitle = title(body.optString("title"))
        val tags = tags(body.optJSONArray("tags"))
        val name = ownerName(body.optString("ownerName"))
        manifest.updateCard(projectId, accountId, cardTitle, tags, name)
        send(exchange, 200, """{"id":"$projectId"}""")
    }

    private fun beginImage(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.requireOwner(projectId, accountId)
        val body = json(exchange)
        val hash = storageKey(body.optString("contentHash"))
        val bytes = size(body.optLong("size", -1))
        val stat = objects.stat(hash)
        if (stat.exists && stat.size != bytes) throw ShareFailure(409, "size mismatch")
        if (stat.exists) {
            manifest.setImage(projectId, accountId, hash)
            send(exchange, 200, """{"storageKey":"$hash","alreadyStored":true}""")
            return
        }
        sendUpload(exchange, hash, bytes)
    }

    private fun commitImage(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.requireOwner(projectId, accountId)
        val body = json(exchange)
        val hash = storageKey(body.optString("contentHash"))
        val key = storageKey(body.optString("storageKey"))
        if (key != hash) throw ShareFailure(400, "storage key must be the content hash")
        val bytes = size(body.optLong("size", -1))
        val stat = objects.stat(hash)
        if (!stat.exists) throw ShareFailure(409, "object missing")
        if (stat.size != bytes) throw ShareFailure(409, "size mismatch")
        manifest.setImage(projectId, accountId, hash)
        send(exchange, 200, """{"storageKey":"$hash"}""")
    }

    private fun readImage(exchange: HttpExchange, projectId: String) {
        val hash = manifest.imageHash(projectId) ?: throw ShareFailure(404, "not found")
        send(exchange, 200, JSONObject().put("downloadUrl", objects.downloadUrl(hash)).toString())
    }

    private fun playback(exchange: HttpExchange, projectId: String) {
        val files = JSONArray()
        for (hash in manifest.fileHashes(projectId)) {
            files.put(
                JSONObject()
                    .put("contentHash", hash)
                    .put("downloadUrl", objects.downloadUrl(hash)),
            )
        }
        send(exchange, 200, JSONObject().put("files", files).toString())
    }

    private fun removeProject(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.deleteProject(projectId, accountId)
        send(exchange, 200, """{"id":"$projectId"}""")
    }

    private fun createProject(exchange: HttpExchange, accountId: String) {
        val body = json(exchange)
        val projectId = id(body.optString("id"), "project id")
        val cardTitle = title(body.optString("title"))
        val name = ownerName(body.optString("ownerName"))
        manifest.saveProject(projectId, accountId, cardTitle, name)
        send(exchange, 200, """{"id":"$projectId"}""")
    }

    private fun requestUpload(exchange: HttpExchange, accountId: String, projectId: String) {
        manifest.requireOwner(projectId, accountId)
        val body = json(exchange)
        val hash = storageKey(body.optString("contentHash"))
        val bytes = size(body.optLong("size", -1))
        id(body.optString("trackId"), "track id")
        val stat = objects.stat(hash)
        if (stat.exists && stat.size != bytes) throw ShareFailure(409, "size mismatch")
        if (stat.exists) {
            send(exchange, 200, """{"storageKey":"$hash","alreadyStored":true}""")
            return
        }
        sendUpload(exchange, hash, bytes)
    }

    private fun sendUpload(exchange: HttpExchange, hash: String, bytes: Long) {
        val target = objects.uploadTarget(hash, bytes)
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
        val bytes = size(body.optLong("size", -1))
        val stat = objects.stat(hash)
        if (!stat.exists) throw ShareFailure(409, "object missing")
        if (stat.size != bytes) throw ShareFailure(409, "size mismatch")
        manifest.saveFile(projectId, trackId, hash, key, bytes)
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

    private fun setOwner(exchange: HttpExchange, accountId: String, projectId: String) {
        val name = ownerName(json(exchange).optString("name"))
        manifest.setOwnerName(projectId, accountId, name)
        send(exchange, 200, """{"id":"$projectId"}""")
    }

    private fun ownerName(value: String): String {
        val name = value.trim()
        if (name.isEmpty() || name.length > MAX_NAME) throw ShareFailure(400, "name is invalid")
        return name
    }

    private fun tags(array: JSONArray?): String {
        if (array == null) throw ShareFailure(400, "tags are invalid")
        if (array.length() > MAX_TAGS) throw ShareFailure(400, "too many tags")
        val clean = JSONArray()
        for (index in 0 until array.length()) {
            val tag = array.optString(index).trim()
            if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH) throw ShareFailure(400, "tag is invalid")
            clean.put(tag)
        }
        return clean.toString()
    }

    private fun storedTags(value: String): JSONArray {
        if (value.isBlank()) return JSONArray()
        return JSONArray(value)
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
        const val MAX_NAME = 80
        const val MAX_TAGS = 12
        const val MAX_TAG_LENGTH = 24
        val Id = Regex("^[A-Za-z0-9_-]{1,80}$")
    }
}
