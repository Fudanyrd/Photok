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
import dev.leonlatsch.photok.encryption.domain.crypto.IV_SIZE
import dev.leonlatsch.photok.encryption.domain.crypto.KeyGen
import dev.leonlatsch.photok.encryption.domain.crypto.SALT_SIZE
import dev.leonlatsch.photok.encryption.domain.models.VaultProtectionType
import dev.leonlatsch.photok.settings.data.Config
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import kotlin.io.encoding.Base64

data class ChangePasswordResult(
    val credentialsCleared: Boolean,
)

class ChangePasswordUseCase @Inject constructor(
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
        val currentProtection = vaultProtectionRepository.getProtection(VaultProtectionType.Password)
        requireNotNull(currentProtection)

        val session = requireNotNull(sessionRepository.get()) { "Vault is locked" }

        val newSalt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val newIv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

        val newKek = keyGen.derivePasswordKeyEncryptionKey(
            password = newPassword,
            salt = newSalt,
            kdf = requireNotNull(currentProtection.params.kdf) { "Password protection is missing KDF" },
            kdfIterations = requireNotNull(currentProtection.params.kdfIterations) { "Password protection is missing KDF iterations" },
            keySize = currentProtection.params.keySize,
        )

        val cipher = Cipher.getInstance(currentProtection.params.algorithm.value).apply {
            init(Cipher.ENCRYPT_MODE, newKek, IvParameterSpec(newIv))
        }

        val newWrappedVmk = cipher.doFinal(session.vmk.encoded)

        val newParams = currentProtection.params.copy(
            salt = Base64.encode(newSalt),
            iv = Base64.encode(newIv),
        )

        val newProtection = currentProtection.copy(
            wrappedVMK = newWrappedVmk,
            params = newParams,
        )

        vaultProtectionRepository.updateProtection(newProtection)
        config.lastUsedUnlockMethod = newProtection.type
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
