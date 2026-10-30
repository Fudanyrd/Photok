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

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonlatsch.photok.backup.cloud.data.CloudConfig
import dev.leonlatsch.photok.backup.cloud.data.oauth.GoogleOAuth
import dev.leonlatsch.photok.backup.cloud.data.oauth.GoogleTokenClient
import dev.leonlatsch.photok.backup.cloud.data.oauth.LoopbackOAuthServer
import dev.leonlatsch.photok.backup.cloud.data.oauth.createOAuthState
import dev.leonlatsch.photok.backup.cloud.data.oauth.createPkce
import dev.leonlatsch.photok.backup.cloud.data.oauth.emailFromIdToken
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CloudSession
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class GoogleCloudAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: CloudConfig,
    private val tokenClient: GoogleTokenClient,
    private val tokenStore: CloudTokenStore,
) : CloudAuth {

    /**
     * Lives as long as the singleton, not as long as a screen. The browser is in front
     * while the user logs in; the vault or the screen may be gone by the time it returns.
     */
    private val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val refreshMutex = Mutex()

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var currentServer: LoopbackOAuthServer? = null

    @Volatile
    private var flowGeneration = 0L

    private val _connection = MutableStateFlow(initialConnection())
    override val connection: StateFlow<CloudConnection> = _connection.asStateFlow()

    private val _lastError = MutableStateFlow<CloudError?>(null)
    override val lastError: StateFlow<CloudError?> = _lastError.asStateFlow()

    override val configured: Boolean = config.isConfigured

    override fun connect(browser: OAuthBrowser) {
        if (!config.isConfigured) {
            _lastError.value = CloudError.NotConfigured()
            return
        }

        if (connectJob?.isActive == true) return

        _lastError.value = null
        _connection.value = CloudConnection.Connecting

        val generation = ++flowGeneration

        startWaitService()

        connectJob = flowScope.launch {
            try {
                runConnect(browser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Closing the socket during cancelConnect surfaces as an IOException.
                // The job is already canceled then and must not report an error.
                coroutineContext.ensureActive()

                Timber.e(e, "Could not connect Google Drive")
                _connection.value = initialConnection()
                _lastError.value = e as? CloudError ?: CloudError.AuthorizationFailed(e)
            } finally {
                // A newer flow (cancel then connect again) owns the service now.
                if (generation == flowGeneration) {
                    connectJob = null
                    stopWaitService()
                }
            }
        }
    }

    override fun cancelConnect() {
        ++flowGeneration

        val job = connectJob
        connectJob = null

        job?.cancel()
        currentServer?.close()
        currentServer = null

        stopWaitService()
        _connection.value = initialConnection()
    }

    override fun clearLastError() {
        _lastError.value = null
    }

    override suspend fun disconnect() {
        cancelConnect()

        val session = tokenStore.load()
        tokenStore.clear()
        _connection.value = CloudConnection.Disconnected
        _lastError.value = null

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

    private suspend fun runConnect(browser: OAuthBrowser) = withContext(Dispatchers.IO) {
        val server = LoopbackOAuthServer(config.redirectHost)
        currentServer = server

        try {
            val pkce = createPkce()
            val state = createOAuthState()
            val authorizationUrl = GoogleOAuth.authorizationUrl(
                config = config,
                redirectUri = server.redirectUri,
                codeChallenge = pkce.challenge,
                state = state,
            )

            withContext(Dispatchers.Main.immediate) {
                browser.open(authorizationUrl.toString())
            }

            val redirect = server.awaitRedirect()

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
                .exchangeCode(code, server.redirectUri, pkce.verifier)
                .getOrThrow()

            val session = CloudSession(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAt = tokens.expiresAt,
                email = emailFromIdToken(tokens.idToken),
            )

            tokenStore.save(session)
            _connection.value = CloudConnection.Connected(session.email)
        } finally {
            server.close()
            if (currentServer === server) {
                currentServer = null
            }
        }
    }

    private fun startWaitService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OAuthWaitService::class.java),
            )
        }.onFailure {
            Timber.e(it, "Could not start the OAuth wait service")
        }
    }

    private fun stopWaitService() {
        runCatching {
            context.stopService(Intent(context, OAuthWaitService::class.java))
        }
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
