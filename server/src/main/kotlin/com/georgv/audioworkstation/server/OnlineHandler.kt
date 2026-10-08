package com.georgv.audioworkstation.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.sql.SQLException

class OnlineHandler(
    private val accounts: AccountService,
    private val projects: OnlineService,
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            dispatch(exchange)
        } catch (failure: OnlineFailure) {
            sendJson(exchange, failure.status, JSONObject().put("error", failure.message))
        } catch (_: JSONException) {
            sendJson(exchange, 400, JSONObject().put("error", "invalid json"))
        } catch (_: IOException) {
            sendJson(exchange, 500, JSONObject().put("error", "storage failed"))
        } catch (_: SQLException) {
            sendJson(exchange, 500, JSONObject().put("error", "database failed"))
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        when {
            method == "GET" && path == "/session" -> getSession(exchange)
            method == "POST" && path == "/projects" -> postProject(exchange)
            method == "GET" && projectById.matches(path) -> getProject(exchange, pathId(projectById, path))
            method == "POST" && storageKeyPath.matches(path) -> postStorageKey(exchange, pathId(storageKeyPath, path))
            method == "PUT" && storageObject.matches(path) -> putStorage(exchange, pathId(storageObject, path))
            method == "POST" && projectFiles.matches(path) -> postFile(exchange, pathId(projectFiles, path))
            else -> throw OnlineFailure(404, "not found")
        }
    }

    private fun getSession(exchange: HttpExchange) {
        val account = accounts.currentAccount(token(exchange))
        sendJson(
            exchange,
            200,
            JSONObject().put("accountId", account.id).put("email", account.email),
        )
    }

    private fun postProject(exchange: HttpExchange) {
        val body = jsonBody(exchange)
        val project = projects.createProject(token(exchange), body.requiredString("title"))
        sendJson(exchange, 201, projectJson(project))
    }

    private fun getProject(exchange: HttpExchange, projectId: String) {
        val details = projects.project(token(exchange), projectId)
        val files = JSONArray()
        details.files.forEach { file -> files.put(fileJson(file)) }
        sendJson(
            exchange,
            200,
            projectJson(SharedProjectRecord(details.id, details.ownerAccountId, details.title)).put("files", files),
        )
    }

    private fun postStorageKey(exchange: HttpExchange, projectId: String) {
        val body = jsonBody(exchange)
        val key = projects.storageKey(
            token = token(exchange),
            projectId = projectId,
            clientTrackId = body.requiredString("clientTrackId"),
            contentHash = body.requiredString("contentHash"),
            size = body.requiredLong("size"),
        )
        sendJson(exchange, 200, JSONObject().put("storageKey", key))
    }

    private fun putStorage(exchange: HttpExchange, storageKey: String) {
        val sessionToken = token(exchange)
        exchange.requestBody.use { body -> projects.write(sessionToken, storageKey, body) }
        sendJson(exchange, 200, JSONObject().put("storageKey", storageKey))
    }

    private fun postFile(exchange: HttpExchange, projectId: String) {
        val body = jsonBody(exchange)
        val file = SharedFileRecord(
            clientTrackId = body.requiredString("clientTrackId"),
            contentHash = body.requiredString("contentHash"),
            storageKey = body.requiredString("storageKey"),
            size = body.requiredLong("size"),
        )
        projects.commitFile(token(exchange), projectId, file)
        sendJson(exchange, 201, fileJson(file).put("sharedProjectId", projectId))
    }

    private companion object {
        val projectById = Regex("^/projects/([^/]+)$")
        val storageKeyPath = Regex("^/projects/([^/]+)/files/storage-key$")
        val projectFiles = Regex("^/projects/([^/]+)/files$")
        val storageObject = Regex("^/storage/([^/]+)$")

        fun pathId(pattern: Regex, path: String): String =
            pattern.matchEntire(path)?.groupValues?.get(1) ?: throw OnlineFailure(404, "not found")

        fun token(exchange: HttpExchange): String {
            val header = exchange.requestHeaders.getFirst("Authorization")
                ?: throw OnlineFailure(401, "session is not valid")
            if (!header.startsWith("Bearer ")) throw OnlineFailure(401, "session is not valid")
            val value = header.removePrefix("Bearer ").trim()
            if (value.isEmpty()) throw OnlineFailure(401, "session is not valid")
            return value
        }

        fun jsonBody(exchange: HttpExchange): JSONObject {
            val text = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            if (text.isBlank()) throw OnlineFailure(400, "json body is required")
            return JSONObject(text)
        }

        fun JSONObject.requiredString(name: String): String {
            if (!has(name) || isNull(name)) throw OnlineFailure(400, "$name is required")
            return getString(name)
        }

        fun JSONObject.requiredLong(name: String): Long {
            if (!has(name) || isNull(name)) throw OnlineFailure(400, "$name is required")
            return getLong(name)
        }

        fun projectJson(project: SharedProjectRecord): JSONObject =
            JSONObject().put("id", project.id).put("ownerAccountId", project.ownerAccountId).put("title", project.title)

        fun fileJson(file: SharedFileRecord): JSONObject =
            JSONObject()
                .put("clientTrackId", file.clientTrackId)
                .put("contentHash", file.contentHash)
                .put("storageKey", file.storageKey)
                .put("size", file.size)

        fun sendJson(exchange: HttpExchange, status: Int, body: JSONObject) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

    }
}
