package dev.appolon.memoswear.data.api

import dev.appolon.memoswear.data.model.IdentityProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The client side of an OAuth 2.0 authorization-code sign-in with PKCE (RFC 7636). */
object OAuth {
    data class Challenge(
        val authorizeUrl: String,
        val redirectUri: String,
        val state: String,
        val codeVerifier: String,
    )

    sealed interface Callback {
        data class Code(val code: String, val state: String) : Callback
        data class Denied(val description: String) : Callback
    }

    private val random = SecureRandom()

    /** Starts a sign-in: the URL to open, and the secrets needed to finish it. The redirect URI is
     *  the instance's own callback, which is the one registered with the identity provider. */
    fun begin(provider: IdentityProvider, instanceUrl: String): Challenge? {
        if (!provider.isOAuth2) return null
        val config = provider.oauth2 ?: return null
        val authorize = config.authUrl.toHttpUrlOrNull() ?: return null
        if (authorize.scheme != "https" && authorize.scheme != "http") return null
        val origin = instanceUrl.trim().trimEnd('/')
        if (origin.isEmpty()) return null
        val redirect = "$origin/auth/callback".toHttpUrlOrNull() ?: return null
        val redirectUri = redirect.toString().trimEnd('/')

        val verifier = randomToken(32)
        val state = randomToken(32)
        val url = authorize.newBuilder().apply {
            addQueryParameter("client_id", config.clientId)
            addQueryParameter("redirect_uri", redirectUri)
            addQueryParameter("response_type", "code")
            addQueryParameter("state", state)
            if (config.scopes.isNotEmpty()) addQueryParameter("scope", config.scopes.joinToString(" "))
            addQueryParameter("code_challenge", codeChallenge(verifier))
            addQueryParameter("code_challenge_method", "S256")
        }.build()
        return Challenge(url.toString(), redirect.toString().trimEnd('/'), state, verifier)
    }

    /** Classifies a navigation the sign-in page made to the redirect URI. Returns null for any
     *  other address. */
    fun parseCallback(url: String, redirectUri: String): Callback? {
        val uri = url.toHttpUrlOrNull() ?: return null
        val expected = redirectUri.toHttpUrlOrNull() ?: return null
        val sameTarget = uri.scheme == expected.scheme && uri.host == expected.host && uri.port == expected.port &&
            uri.encodedPath == expected.encodedPath
        if (!sameTarget) return null

        val error = uri.queryParameter("error")
        if (error != null) return Callback.Denied(uri.queryParameter("error_description")?.ifBlank { null } ?: error)
        val code = uri.queryParameter("code") ?: return Callback.Denied("The sign-in page returned no code")
        return Callback.Code(code, uri.queryParameter("state").orEmpty())
    }

    /** Base64url of random bytes; 32 bytes give the 43-character verifier that RFC 7636 suggests. */
    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /** S256 code challenge: base64url without padding of SHA-256 over the ASCII verifier. */
    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
