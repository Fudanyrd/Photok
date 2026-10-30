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
import com.google.gson.JsonParser
import dev.leonlatsch.photok.backup.cloud.data.CloudConfig
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.data.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleTokenClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val config: CloudConfig,
    private val gson: Gson,
) {

    suspend fun exchangeCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): Result<OAuthTokens> = tokenRequest(
        GoogleOAuth.tokenRequestBody(config, code, redirectUri, codeVerifier)
    )

    suspend fun refresh(refreshToken: String): Result<OAuthTokens> = tokenRequest(
        GoogleOAuth.refreshRequestBody(config, refreshToken)
    )

    /**
     * Best effort: revoking a token of an already removed session is not an error.
     */
    suspend fun revoke(refreshToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(
                    GoogleOAuth.REVOKE_ENDPOINT.toHttpUrl().newBuilder()
                        .addQueryParameter("token", refreshToken)
                        .build()
                )
                .post(ByteArray(0).toRequestBody())
                .build()

            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Could not revoke the Google token: ${response.code}")
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not revoke the Google token")
            Result.failure(CloudError.Network(e))
        }
    }

    private suspend fun tokenRequest(body: okhttp3.RequestBody): Result<OAuthTokens> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GoogleOAuth.TOKEN_ENDPOINT)
                    .post(body)
                    .build()

                httpClient.newCall(request).awaitResponse().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            CloudError.AuthorizationFailed(
                                IllegalStateException(
                                    "Token request failed with ${response.code}: " +
                                        errorDescription(responseBody)
                                )
                            )
                        )
                    }

                    Result.success(parseOAuthTokens(responseBody, gson = gson))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.failure(CloudError.Network(e))
            } catch (e: Exception) {
                Result.failure(CloudError.Unknown(e))
            }
        }

    private fun errorDescription(json: String): String = try {
        JsonParser.parseString(json).asJsonObject
            .get("error_description")?.asString
            ?: JsonParser.parseString(json).asJsonObject.get("error")?.asString
            ?: "unknown error"
    } catch (e: Exception) {
        "unknown error"
    }
}
