package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.online.network.HttpAccountApi
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpProjectShareApi
import com.georgv.audioworkstation.online.network.HttpResult
import com.georgv.audioworkstation.online.network.HttpTransport
import com.georgv.audioworkstation.online.network.JsonHttp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpAccountApiTest {
    @Test
    fun registerPostsAccountsWithoutStartingASession() = runTest {
        val transport = ScriptedTransport(
            HttpResult(201, """{"accountId":"a1","email":"ada@example.com"}"""),
        )
        val api = HttpAccountApi(JsonHttp(transport), UnconfinedTestDispatcher())
        val account = api.register("Ada@Example.com", "secret")
        assertEquals("a1", account.accountId)
        assertEquals("ada@example.com", account.email)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/accounts", call.path)
        assertNull(call.token)
        assertTrue(written(call).contains("Ada@Example.com"))
    }

    @Test
    fun gmailPostsTheIdTokenToTheGoogleSession() = runTest {
        val transport = ScriptedTransport(
            HttpResult(200, """{"token":"t","accountId":"a1","email":"ada@gmail.com"}"""),
        )
        val api = HttpAccountApi(JsonHttp(transport), UnconfinedTestDispatcher())
        val session = api.createGoogleSession("id-token")
        assertEquals("t", session.token)
        assertEquals("/sessions/google", transport.calls.single().path)
        assertTrue(written(transport.calls.single()).contains("id-token"))
    }

    @Test
    fun sharePostsTheProjectOnTheShareApi() = runTest {
        val transport = ScriptedTransport(
            HttpResult(201, """{"id":"p1","ownerAccountId":"a1","title":"Night"}"""),
        )
        val api = HttpProjectShareApi(JsonHttp(transport), UnconfinedTestDispatcher())
        val project = api.createSharedProject("token", "Night")
        assertEquals("p1", project.id)
        assertEquals("/projects", transport.calls.single().path)
        assertEquals("token", transport.calls.single().token)
    }
}

private fun written(call: HttpCall): String {
    val buffer = ByteArrayOutputStream()
    call.writeBody?.invoke(buffer)
    return buffer.toString(Charsets.UTF_8)
}

private class ScriptedTransport(private vararg val results: HttpResult) : HttpTransport {
    val calls = mutableListOf<HttpCall>()
    private var index = 0

    override fun exchange(call: HttpCall): HttpResult {
        calls.add(call)
        return results[index++]
    }
}
