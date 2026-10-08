package com.georgv.audioworkstation.server

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

class UrlCognitoJwks : CognitoJwks {
    override fun key(keyId: String): PublicKey? {
        val keys = try {
            certificates()
        } catch (error: IOException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        } catch (error: JSONException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        } catch (error: InterruptedException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        }
        for (index in 0 until keys.length()) {
            val jwk = keys.getJSONObject(index)
            if (jwk.optString("kid") != keyId) continue
            if (jwk.optString("kty") != "RSA") throw OnlineFailure(401, CognitoJwt.INVALID)
            return rsaKey(jwk.getString("n"), jwk.getString("e"))
        }
        return null
    }

    private fun certificates() = JSONObject(fetch()).getJSONArray("keys")

    private fun fetch(): String {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build()
        val request = HttpRequest.newBuilder(URI.create(CognitoConfig.JWKS_URL))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in SUCCESS) throw IOException("cognito jwks status ${response.statusCode()}")
        return response.body()
    }

    private fun rsaKey(modulus: String, exponent: String): PublicKey =
        try {
            val spec = RSAPublicKeySpec(positive(modulus), positive(exponent))
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (error: IllegalArgumentException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        } catch (error: InvalidKeySpecException) {
            throw OnlineFailure(401, CognitoJwt.INVALID, error)
        }

    private fun positive(value: String): BigInteger {
        val padding = (4 - value.length % 4) % 4
        return BigInteger(1, Base64.getUrlDecoder().decode(value + "=".repeat(padding)))
    }

    companion object {
        private val SUCCESS = 200..299
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 15_000L
    }
}
