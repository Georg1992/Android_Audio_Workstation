package com.georgv.audioworkstation.server

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CognitoSignedAccessTokens(
    private val clientId: String,
    private val jwks: CognitoJwks,
    private val clock: () -> Long = System::currentTimeMillis,
) : CognitoAccessTokens {
    override fun verify(token: String): CognitoIdentity {
        val jwt = CognitoJwt.parse(token)
        if (jwt.algorithm != "RS256") throw OnlineFailure(401, CognitoJwt.INVALID)
        val key = jwks.key(jwt.keyId) ?: throw OnlineFailure(401, CognitoJwt.INVALID)
        if (!jwt.signatureMatches(key)) throw OnlineFailure(401, CognitoJwt.INVALID)
        val subject = jwt.subject(clientId, clock())
        return identity(token, subject)
    }

    private fun identity(token: String, subject: String): CognitoIdentity {
        val profile = try {
            userInfo(token)
        } catch (error: IOException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        } catch (error: JSONException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        } catch (error: InterruptedException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        }
        if (profile.optString("sub") != subject) throw OnlineFailure(401, CognitoJwt.INVALID)
        if (!verified(profile)) throw OnlineFailure(401, CognitoJwt.INVALID)
        val email = profile.optString("email")
        if (email.isBlank()) throw OnlineFailure(401, CognitoJwt.INVALID)
        return CognitoIdentity(subject = subject, email = email)
    }

    private fun userInfo(token: String): JSONObject {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build()
        val request = HttpRequest.newBuilder(URI.create(CognitoConfig.USER_INFO_URL))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in SUCCESS) throw IOException("cognito userinfo status ${response.statusCode()}")
        return JSONObject(response.body())
    }

    private fun verified(profile: JSONObject): Boolean {
        val value = profile.opt("email_verified")
        return value == true || value == "true"
    }

    companion object {
        private val SUCCESS = 200..299
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 15_000L
    }
}
