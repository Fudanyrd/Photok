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

package dev.leonlatsch.photok.encryption.domain

import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsSession
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudTokenStore
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsRepository
import dev.leonlatsch.photok.encryption.domain.crypto.KeyGen
import dev.leonlatsch.photok.encryption.domain.models.CreateRequest
import dev.leonlatsch.photok.encryption.domain.models.VaultProtectionType
import dev.leonlatsch.photok.settings.data.Config
import timber.log.Timber
import javax.inject.Inject

data class ChangePasswordResult(
    val credentialsCleared: Boolean,
)

class ChangePasswordUseCase @Inject constructor(
    private val vaultService: VaultService,
    private val vaultProtectionRepository: VaultProtectionRepository,
    private val sessionRepository: SessionRepository,
    private val keyGen: KeyGen,
    private val config: Config,
    private val cloudCredentialsRepository: CloudCredentialsRepository,
    private val cloudCredentialsSession: CloudCredentialsSession,
    private val cloudTokenStore: CloudTokenStore,
) {
    suspend operator fun invoke(
        oldPassword: String?,
        newPassword: String,
    ): Result<ChangePasswordResult> = runCatching {
        try {
            updateVaultPassword(newPassword)
            ChangePasswordResult(
                credentialsCleared = clearOrReencryptCloudCredentials(oldPassword, newPassword),
            )
        } catch (e: Exception) {
            Timber.e(e, "Could not change the vault password")
            clearCloudCredentials()
            throw e
        }
    }

    private suspend fun updateVaultPassword(newPassword: String) {
        require(vaultService.isSetup(VaultProtectionType.Password)) { "Password protection is missing" }

        val session = requireNotNull(sessionRepository.get()) { "Vault is locked" }

        vaultService.update(CreateRequest.Password.ExistingVault(newPassword, session))
        config.lastUsedUnlockMethod = VaultProtectionType.Password
    }

    private suspend fun clearOrReencryptCloudCredentials(
        oldPassword: String?,
        newPassword: String,
    ): Boolean {
        if (!cloudCredentialsRepository.exists()) return false

        val old = oldPassword?.takeIf { it.isNotEmpty() }
            ?: return clearCloudCredentials()

        return cloudCredentialsRepository.reencrypt(old, newPassword).fold(
            onSuccess = { false },
            onFailure = {
                Timber.w(it, "Drive credentials could not be re-encrypted, clearing them")
                clearCloudCredentials()
                true
            },
        )
    }

    private suspend fun clearCloudCredentials(): Boolean {
        runCatching { cloudCredentialsRepository.clear() }
            .onFailure { Timber.e(it, "Could not delete the Drive credentials") }

        cloudTokenStore.clear()
        cloudCredentialsSession.clear()
        return true
    }
}
