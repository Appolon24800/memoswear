package dev.appolon.memoswear.data.api

import dev.appolon.memoswear.data.model.IdentityProvider
import dev.appolon.memoswear.data.model.IdentityProviderConfigEnvelope
import dev.appolon.memoswear.data.model.OAuth2Config
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OAuthTest {
    private val provider = IdentityProvider(
        name = "identity-providers/pocket-id",
        title = "Pocket ID",
        type = "OAUTH2",
        config = IdentityProviderConfigEnvelope(
            oauth2Config = OAuth2Config(
                clientId = "memos",
                authUrl = "https://auth.example.com/authorize",
                scopes = listOf("openid", "profile", "email"),
            ),
        ),
    )

    @Test
    fun `code challenge matches the worked example in RFC 7636`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `sign-in starts with the parameters the provider expects`() {
        val challenge = OAuth.begin(provider, "https://memos.example.com") ?: return fail("no challenge")

        val url = challenge.authorizeUrl.toHttpUrl()
        assertEquals("auth.example.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        assertEquals("memos", url.queryParameter("client_id"))
        assertEquals("https://memos.example.com/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("openid profile email", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(OAuth.codeChallenge(challenge.codeVerifier), url.queryParameter("code_challenge"))

        assertEquals("https://memos.example.com/auth/callback", challenge.redirectUri)
        assertEquals(43, challenge.state.length)
        assertEquals(43, challenge.codeVerifier.length)
    }

    @Test
    fun `a trailing slash on the instance URL does not leak into the redirect`() {
        val challenge = OAuth.begin(provider, "https://memos.example.com/") ?: return fail("no challenge")
        assertEquals("https://memos.example.com/auth/callback", challenge.redirectUri)
    }

    @Test
    fun `providers without OAuth configuration cannot start`() {
        assertNull(OAuth.begin(provider.copy(type = "TYPE_UNSPECIFIED"), "https://memos.example.com"))
        assertNull(OAuth.begin(provider, ""))
    }

    @Test
    fun `the callback yields its code and state`() {
        val challenge = OAuth.begin(provider, "https://memos.example.com")!!
        val callback = OAuth.parseCallback(
            "https://memos.example.com/auth/callback?code=abc%2F123&state=${challenge.state}&iss=https%3A%2F%2Fauth.example.com",
            challenge.redirectUri,
        )
        assertEquals(OAuth.Callback.Code("abc/123", challenge.state), callback)
    }

    @Test
    fun `a refusal is reported as denied`() {
        val callback = OAuth.parseCallback(
            "https://memos.example.com/auth/callback?error=access_denied&error_description=User+rejected",
            "https://memos.example.com/auth/callback",
        )
        assertEquals(OAuth.Callback.Denied("User rejected"), callback)
    }

    @Test
    fun `every other address is left alone`() {
        val redirect = "https://memos.example.com/auth/callback"
        assertNull(OAuth.parseCallback("https://auth.example.com/authorize?code=1", redirect))
        assertNull(OAuth.parseCallback("https://memos.example.com/other?code=1", redirect))
        assertNull(OAuth.parseCallback("http://memos.example.com/auth/callback?code=1", redirect))
        assertNull(OAuth.parseCallback("https://memos.example.com:8443/auth/callback?code=1", redirect))
        assertNull(OAuth.parseCallback("not a url", redirect))
    }

    @Test
    fun `a callback without a code is denied rather than mistaken for success`() {
        val callback = OAuth.parseCallback("https://memos.example.com/auth/callback?state=x", "https://memos.example.com/auth/callback")
        assertTrue(callback is OAuth.Callback.Denied)
    }
}
