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

import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsState
import dev.leonlatsch.photok.backup.cloud.domain.DriveClientCredentials
import dev.leonlatsch.photok.encryption.domain.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the decrypted Drive credentials only while the vault is unlocked.
 *
 * Keeping them for the whole session is deliberate: access-token refresh and the retry
 * after a `401` need the OAuth client secret too.
 */
@Singleton
class CloudCredentialsSession @Inject constructor(
    private val repository: CloudCredentialsRepository,
    sessionRepository: SessionRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        if (repository.exists()) CloudCredentialsState.Locked else CloudCredentialsState.Missing
    )
    val state: StateFlow<CloudCredentialsState> = _state.asStateFlow()

    @Volatile
    var credentials: DriveClientCredentials? = null
        private set

    init {
        scope.launch {
            sessionRepository.observe().collect { session ->
                if (session == null) lock()
            }
        }
    }

    fun unlock(credentials: DriveClientCredentials) {
        this.credentials = credentials
        _state.value = CloudCredentialsState.Unlocked
    }

    fun lock() {
        credentials = null
        _state.value = if (repository.exists()) {
            CloudCredentialsState.Locked
        } else {
            CloudCredentialsState.Missing
        }
    }

    fun clear() {
        credentials = null
        _state.value = CloudCredentialsState.Missing
    }
}
