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

package dev.leonlatsch.photok.backup.cloud.data.auth

import dev.leonlatsch.photok.backup.cloud.data.oauth.GoogleOAuth
import dev.leonlatsch.photok.backup.cloud.data.oauth.GoogleTokenClient
import dev.leonlatsch.photok.backup.cloud.data.oauth.LoopbackOAuthServer
import dev.leonlatsch.photok.backup.cloud.data.oauth.createOAuthState
import dev.leonlatsch.photok.backup.cloud.data.oauth.createPkce
import dev.leonlatsch.photok.backup.cloud.data.oauth.emailFromIdToken
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.data.CloudConfig
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CloudSession
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleCloudAuth @Inject constructor(
    private val config: CloudConfig,
    private val tokenClient: GoogleTokenClient,
    private val tokenStore: CloudTokenStore,
) : CloudAuth {

    private val refreshMutex = Mutex()

    private val _connection = MutableStateFlow(initialConnection())

    override val connection: StateFlow<CloudConnection> = _connection.asStateFlow()

    override val configured: Boolean = config.isConfigured

    override suspend fun connect(browser: OAuthBrowser): Result<CloudConnection> =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) {
                return@withContext Result.failure(CloudError.NotConfigured())
            }

            _connection.value = CloudConnection.Connecting

            var server: LoopbackOAuthServer? = null
            try {
                val loopbackServer = LoopbackOAuthServer(config.redirectHost)
                server = loopbackServer
                val pkce = createPkce()
                val state = createOAuthState()
                val authorizationUrl = GoogleOAuth.authorizationUrl(
                    config = config,
                    redirectUri = loopbackServer.redirectUri,
                    codeChallenge = pkce.challenge,
                    state = state,
                )

                withContext(Dispatchers.Main.immediate) {
                    browser.open(authorizationUrl.toString())
                }

                val redirect = loopbackServer.awaitRedirect()

                if (redirect.error != null) {
                    throw CloudError.AuthorizationFailed(
                        IllegalStateException("Authorization denied: ${redirect.error}")
                    )
                }
                if (redirect.state != state) {
                    throw CloudError.AuthorizationFailed(
                        IllegalStateException("OAuth state does not match")
                    )
                }

                val code = redirect.code
                    ?: throw CloudError.AuthorizationFailed(
                        IllegalStateException("No authorization code")
                    )

                val tokens = tokenClient
                    .exchangeCode(code, loopbackServer.redirectUri, pkce.verifier)
                    .getOrThrow()

                val session = CloudSession(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAt = tokens.expiresAt,
                    email = emailFromIdToken(tokens.idToken),
                )

                tokenStore.save(session)
                _connection.value = CloudConnection.Connected(session.email)

                Result.success(_connection.value)
            } catch (e: CancellationException) {
                _connection.value = initialConnection()
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not connect Google Drive")
                _connection.value = initialConnection()
                Result.failure(e as? CloudError ?: CloudError.AuthorizationFailed(e))
            } finally {
                server?.close()
            }
        }

    override suspend fun disconnect() {
        val session = tokenStore.load()
        tokenStore.clear()
        _connection.value = CloudConnection.Disconnected

        session?.refreshToken?.let { tokenClient.revoke(it) }
    }

    override suspend fun accessToken(forceRefresh: Boolean): Result<String> =
        refreshMutex.withLock {
            val session = tokenStore.load()
                ?: return@withLock Result.failure(CloudError.NotConnected())

            if (!forceRefresh && session.isAccessTokenUsable()) {
                return@withLock Result.success(session.accessToken)
            }

            val refreshToken = session.refreshToken
                ?: return@withLock Result.failure(CloudError.NotConnected())

            tokenClient.refresh(refreshToken).fold(
                onSuccess = { tokens ->
                    val updated = tokenStore.update { current ->
                        current.copy(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken ?: current.refreshToken,
                            expiresAt = tokens.expiresAt,
                            email = emailFromIdToken(tokens.idToken) ?: current.email,
                        )
                    } ?: session.copy(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken ?: session.refreshToken,
                        expiresAt = tokens.expiresAt,
                    )

                    _connection.value = CloudConnection.Connected(updated.email)
                    Result.success(updated.accessToken)
                },
                onFailure = { error ->
                    if (error is CloudError.AuthorizationFailed) {
                        tokenStore.clear()
                        _connection.value = CloudConnection.Disconnected
                    }
                    Result.failure(error)
                },
            )
        }

    private fun initialConnection(): CloudConnection {
        val session = tokenStore.load() ?: return CloudConnection.Disconnected

        return if (session.refreshToken != null || session.isAccessTokenUsable()) {
            CloudConnection.Connected(session.email)
        } else {
            CloudConnection.Disconnected
        }
    }
}
