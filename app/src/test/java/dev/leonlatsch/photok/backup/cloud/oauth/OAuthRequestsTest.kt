/*
 *   Copyright 2020–2026 Leon Latsch
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.leonlatsch.photok.backup.cloud.oauth

import dev.leonlatsch.photok.backup.cloud.data.oauth.GoogleOAuth
import dev.leonlatsch.photok.backup.cloud.data.oauth.createPkce
import dev.leonlatsch.photok.backup.cloud.data.oauth.emailFromIdToken
import dev.leonlatsch.photok.backup.cloud.data.oauth.parseOAuthRedirect
import dev.leonlatsch.photok.backup.cloud.data.oauth.parseOAuthTokens
import dev.leonlatsch.photok.backup.cloud.data.CloudConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class OAuthRequestsTest {

    private val config = CloudConfig(
        clientId = "client-id",
        clientSecret = "client-secret",
        redirectHost = "localhost",
    )

    @Test
    fun `pkce verifier has a valid length and the challenge is its sha256`() {
        val pkce = createPkce()

        assertTrue(pkce.verifier.length in 43..128)

        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        )

        assertEquals(expected, pkce.challenge)
        assertTrue(pkce.verifier.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `authorization request asks for offline drive access with pkce`() {
        val url = GoogleOAuth.authorizationUrl(
            config = config,
            redirectUri = "http://localhost:1234",
            codeChallenge = "challenge",
            state = "state",
        )

        assertEquals("https", url.scheme)
        assertEquals("accounts.google.com", url.host)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client-id", url.queryParameter("client_id"))
        assertEquals("http://localhost:1234", url.queryParameter("redirect_uri"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("consent", url.queryParameter("prompt"))
        assertEquals("state", url.queryParameter("state"))
        assertTrue(url.queryParameter("scope")!!.contains(GoogleOAuth.DRIVE_FILE_SCOPE))
    }

    @Test
    fun `redirect query is decoded`() {
        val redirect = parseOAuthRedirect("code=abc%2F123&state=xyz")

        assertEquals("abc/123", redirect.code)
        assertEquals("xyz", redirect.state)
        assertNull(redirect.error)
    }

    @Test
    fun `redirect error is reported`() {
        val redirect = parseOAuthRedirect("error=access_denied&state=xyz")

        assertNull(redirect.code)
        assertEquals("access_denied", redirect.error)
    }

    @Test
    fun `empty redirect is tolerated`() {
        val redirect = parseOAuthRedirect(null)

        assertNull(redirect.code)
        assertNull(redirect.state)
        assertNull(redirect.error)
    }

    @Test
    fun `token response keeps the refresh token and computes expiry`() {
        val tokens = parseOAuthTokens(
            json = """{"access_token":"a","refresh_token":"r","expires_in":3600,"id_token":"id"}""",
            now = 1_000L,
        )

        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(1_000L + 3_600_000L, tokens.expiresAt)
        assertEquals("id", tokens.idToken)
    }

    @Test
    fun `email is read from the id token payload`() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"email":"me@example.com"}""".toByteArray())

        assertEquals("me@example.com", emailFromIdToken("header.$payload.signature"))
        assertNull(emailFromIdToken(null))
        assertNull(emailFromIdToken("not-a-jwt"))
    }

    @Test
    fun `config never prints the client secret`() {
        val config = CloudConfig(
            clientId = "client-id",
            clientSecret = "super-secret",
            redirectHost = "localhost",
        )

        assertFalse(config.toString().contains("super-secret"))
    }
}
