package com.georgv.audioworkstation.server

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class OnlineApiTest {
    private lateinit var root: File
    private lateinit var server: OnlineServer
    private lateinit var api: Api
    private val cognito = MapCognitoAccessTokens()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("online-api").toFile()
        server = OnlineServer.start(port = 0, root = root, cognito = cognito)
        api = Api("http://127.0.0.1:${server.port}")
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    @Test
    fun sha256OfAbc() {
        val hash = Sha256Hex.ofBytes("abc".toByteArray(Charsets.UTF_8))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun cognitoTokenOpensTheAccount() {
        cognito.accept("token-1", "sub-1", "Ada@Example.com")
        val current = api.get("/session", "token-1")
        assertEquals(200, current.statusCode())
        val body = JSONObject(current.body())
        assertEquals("sub-1", body.getString("accountId"))
        assertEquals("ada@example.com", body.getString("email"))
        assertEquals("sub-1", JSONObject(api.get("/session", "token-1").body()).getString("accountId"))
        assertEquals(401, api.get("/session", null).statusCode())
        assertEquals(401, api.get("/session", "other").statusCode())
    }

    @Test
    fun shareStoresTheProjectAndFilePointerWhileBytesStayInTheHashDirectory() {
        val token = tokenFor("owner@studio.test")
        val project = createProject(token, "Night Take")
        val projectId = project.getString("id")
        assertEquals(tokenAccount(token), project.getString("ownerAccountId"))

        val audio = "WAV-BYTES-UNIQUE-9f3a".toByteArray(Charsets.UTF_8)
        val hash = Sha256Hex.ofBytes(audio)
        val keyResponse = api.postJson(
            "/projects/$projectId/files/storage-key",
            """{"clientTrackId":"track-1","contentHash":"$hash","size":${audio.size}}""",
            token,
        )
        assertEquals(200, keyResponse.statusCode())
        assertEquals(hash, JSONObject(keyResponse.body()).getString("storageKey"))
        assertEquals(emptySet<String>(), storageNames())

        val written = api.putBytes("/storage/$hash", audio, token)
        assertEquals(200, written.statusCode())
        assertEquals(setOf(hash), storageNames())
        val beforeCommit = api.get("/projects/$projectId", token)
        assertEquals(0, JSONObject(beforeCommit.body()).getJSONArray("files").length())

        val committed = api.postJson(
            "/projects/$projectId/files",
            """{"clientTrackId":"track-1","contentHash":"$hash","storageKey":"$hash","size":${audio.size}}""",
            token,
        )
        assertEquals(201, committed.statusCode())

        val stored = JSONObject(api.get("/projects/$projectId", token).body())
        val file = stored.getJSONArray("files").getJSONObject(0)
        assertEquals("track-1", file.getString("clientTrackId"))
        assertEquals(hash, file.getString("contentHash"))
        assertEquals(hash, file.getString("storageKey"))
        assertEquals(audio.size.toLong(), file.getLong("size"))
        assertEquals(audio.toList(), File(storageDir(), hash).readBytes().toList())

        val database = File(root, OnlineServer.DATABASE_FILE).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(database.contains("WAV-BYTES-UNIQUE-9f3a"))
        assertFalse(stored.toString().contains("WAV-BYTES-UNIQUE-9f3a"))
        assertEquals(listOf(OnlineServer.STORAGE_DIRECTORY), rootDirectories())
    }

    @Test
    fun theSameContentHashUsesTheOneStorageFile() {
        val token = tokenFor("owner@studio.test")
        val first = createProject(token, "One").getString("id")
        val second = createProject(token, "Two").getString("id")
        val audio = "same-take".toByteArray(Charsets.UTF_8)
        val hash = Sha256Hex.ofBytes(audio)
        upload(token, first, "track-a", audio)
        upload(token, second, "track-b", audio)
        assertEquals(setOf(hash), storageNames())
        assertEquals(listOf(OnlineServer.STORAGE_DIRECTORY), rootDirectories())
    }

    @Test
    fun commitRejectsAMissingFileASizeMismatchAndAKeyThatIsNotTheHash() {
        val token = tokenFor("owner@studio.test")
        val projectId = createProject(token, "Take").getString("id")
        val audio = "recorded".toByteArray(Charsets.UTF_8)
        val hash = Sha256Hex.ofBytes(audio)
        val other = Sha256Hex.ofBytes("other".toByteArray(Charsets.UTF_8))

        val missing = commit(token, projectId, "track-1", hash, hash, audio.size)
        assertEquals(400, missing.statusCode())

        api.putBytes("/storage/$hash", audio, token)
        val wrongSize = commit(token, projectId, "track-1", hash, hash, audio.size + 1)
        assertEquals(400, wrongSize.statusCode())
        val wrongKey = commit(token, projectId, "track-1", hash, other, audio.size)
        assertEquals(400, wrongKey.statusCode())
        assertEquals(0, JSONObject(api.get("/projects/$projectId", token).body()).getJSONArray("files").length())

        val badPut = api.putBytes("/storage/$other", audio, token)
        assertEquals(400, badPut.statusCode())
        assertFalse(File(storageDir(), other).exists())
        assertEquals(audio.toList(), File(storageDir(), hash).readBytes().toList())
    }

    @Test
    fun aReplacedHashIsRejectedAndTheStoredBytesStay() {
        val token = tokenFor("owner@studio.test")
        val audio = "original-bytes".toByteArray(Charsets.UTF_8)
        val hash = Sha256Hex.ofBytes(audio)
        api.putBytes("/storage/$hash", audio, token)
        val replaced = api.putBytes("/storage/$hash", "different".toByteArray(Charsets.UTF_8), token)
        assertEquals(400, replaced.statusCode())
        assertEquals(audio.toList(), File(storageDir(), hash).readBytes().toList())
    }

    @Test
    fun anotherAccountCannotReadOrCommitTheProject() {
        val owner = tokenFor("owner@studio.test")
        val other = tokenFor("other@studio.test")
        val projectId = createProject(owner, "Private").getString("id")
        assertEquals(403, api.get("/projects/$projectId", other).statusCode())
        val hash = Sha256Hex.ofBytes("x".toByteArray(Charsets.UTF_8))
        val key = api.postJson(
            "/projects/$projectId/files/storage-key",
            """{"clientTrackId":"track-1","contentHash":"$hash","size":1}""",
            other,
        )
        assertEquals(403, key.statusCode())
        assertEquals(401, api.postJson("/projects", """{"title":"Nope"}""", null).statusCode())
        assertEquals(400, api.postJson("/projects", """{"title":"  "}""", owner).statusCode())
    }

    @Test
    fun anUnknownTokenIsRejected() {
        assertEquals(401, api.get("/session", "missing").statusCode())
        assertEquals(401, api.putBytes("/storage/${"ab".repeat(32)}", byteArrayOf(1), null).statusCode())
    }

    private fun tokenFor(email: String): String {
        val token = "token-$email"
        cognito.accept(token, "sub-$email", email)
        return token
    }

    private fun tokenAccount(token: String): String =
        JSONObject(api.get("/session", token).body()).getString("accountId")

    private fun createProject(token: String, title: String): JSONObject {
        val response = api.postJson("/projects", """{"title":"$title"}""", token)
        assertEquals(201, response.statusCode())
        return JSONObject(response.body())
    }

    private fun upload(token: String, projectId: String, trackId: String, audio: ByteArray) {
        val hash = Sha256Hex.ofBytes(audio)
        val key = api.postJson(
            "/projects/$projectId/files/storage-key",
            """{"clientTrackId":"$trackId","contentHash":"$hash","size":${audio.size}}""",
            token,
        )
        assertEquals(200, key.statusCode())
        assertEquals(200, api.putBytes("/storage/$hash", audio, token).statusCode())
        assertEquals(201, commit(token, projectId, trackId, hash, hash, audio.size).statusCode())
    }

    private fun commit(
        token: String,
        projectId: String,
        trackId: String,
        contentHash: String,
        storageKey: String,
        size: Int,
    ): HttpResponse<String> =
        api.postJson(
            "/projects/$projectId/files",
            """{"clientTrackId":"$trackId","contentHash":"$contentHash","storageKey":"$storageKey","size":$size}""",
            token,
        )

    private fun storageDir(): File = File(root, OnlineServer.STORAGE_DIRECTORY)

    private fun storageNames(): Set<String> = storageDir().list()?.toSet().orEmpty()

    private fun rootDirectories(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    private class Api(private val base: String) {
        private val http = HttpClient.newHttpClient()

        fun postJson(path: String, json: String, token: String? = null): HttpResponse<String> =
            send("POST", path, token, json.toByteArray(Charsets.UTF_8), "application/json")

        fun putBytes(path: String, bytes: ByteArray, token: String?): HttpResponse<String> =
            send("PUT", path, token, bytes, "application/octet-stream")

        fun get(path: String, token: String?): HttpResponse<String> =
            send("GET", path, token, null, null)

        fun delete(path: String, token: String): HttpResponse<String> =
            send("DELETE", path, token, null, null)

        private fun send(
            method: String,
            path: String,
            token: String?,
            body: ByteArray?,
            contentType: String?,
        ): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create(base + path))
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (contentType != null) builder.header("Content-Type", contentType)
            val publisher = if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofByteArray(body)
            }
            builder.method(method, publisher)
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
    }
}
