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

package dev.leonlatsch.photok.backup.cloud.data.oauth

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.leonlatsch.photok.backup.cloud.data.CloudConfig
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import java.net.URLDecoder

object GoogleOAuth {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val SCOPE = "$DRIVE_FILE_SCOPE openid email"

    fun authorizationUrl(
        config: CloudConfig,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ) = AUTHORIZATION_ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", config.clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", SCOPE)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state)
        .addQueryParameter("access_type", "offline")
        // Google only returns a refresh token on a fresh consent, otherwise the app would
        // have to reconnect after every access token expiry.
        .addQueryParameter("prompt", "consent")
        .build()

    fun tokenRequestBody(
        config: CloudConfig,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): RequestBody = FormBody.Builder()
        .add("code", code)
        .add("client_id", config.clientId)
        .add("client_secret", config.clientSecret)
        .add("redirect_uri", redirectUri)
        .add("grant_type", "authorization_code")
        .add("code_verifier", codeVerifier)
        .build()

    fun refreshRequestBody(config: CloudConfig, refreshToken: String): RequestBody =
        FormBody.Builder()
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
}

data class OAuthRedirect(
    val code: String?,
    val state: String?,
    val error: String?,
)

/**
 * Parses the query of the loopback redirect. Values are URL decoded; the code and state
 * are base64url but the error descriptions are not.
 */
fun parseOAuthRedirect(rawQuery: String?): OAuthRedirect {
    val parameters = rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { parameter ->
            val separator = parameter.indexOf('=')
            val name = if (separator < 0) parameter else parameter.substring(0, separator)
            val value = if (separator < 0) "" else parameter.substring(separator + 1)

            URLDecoder.decode(name, Charsets.UTF_8.name()) to
                URLDecoder.decode(value, Charsets.UTF_8.name())
        }

    return OAuthRedirect(
        code = parameters["code"],
        state = parameters["state"],
        error = parameters["error"],
    )
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val idToken: String?,
)

private data class OAuthTokenJson(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null,
    @SerializedName("id_token") val idToken: String? = null,
)

fun parseOAuthTokens(
    json: String,
    now: Long = System.currentTimeMillis(),
    gson: Gson = Gson(),
): OAuthTokens {
    val parsed = gson.fromJson(json, OAuthTokenJson::class.java)
    val accessToken = parsed.accessToken
        ?: throw IllegalArgumentException("Token response has no access token")

    // Treat a missing lifetime as already expired; the next call refreshes it.
    val expiresIn = parsed.expiresIn ?: 0L

    return OAuthTokens(
        accessToken = accessToken,
        refreshToken = parsed.refreshToken,
        expiresAt = now + expiresIn * 1_000L,
        idToken = parsed.idToken,
    )
}

private data class IdTokenPayload(
    val email: String? = null,
)

/**
 * Reads the email from the ID token payload for display only. The token comes directly
 * from Google over TLS; no signature check is needed to show the account name.
 */
fun emailFromIdToken(idToken: String?, gson: Gson = Gson()): String? {
    if (idToken.isNullOrBlank()) return null

    return try {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        gson.fromJson(json, IdTokenPayload::class.java)?.email
    } catch (e: Exception) {
        null
    }
}
