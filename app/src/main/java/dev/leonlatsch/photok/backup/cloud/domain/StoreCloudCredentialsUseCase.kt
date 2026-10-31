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

package dev.leonlatsch.photok.backup.cloud.domain

import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsSession
import dev.leonlatsch.photok.encryption.domain.VaultService
import javax.inject.Inject

/**
 * Verifies the vault password, parses the pasted `google.json` and stores it encrypted.
 * Importing a different OAuth client invalidates the old Drive token session.
 */
class StoreCloudCredentialsUseCase @Inject constructor(
    private val vaultService: VaultService,
    private val repository: CloudCredentialsRepository,
    private val session: CloudCredentialsSession,
    private val auth: CloudAuth,
) {

    suspend operator fun invoke(json: String, password: String): Result<DriveClientCredentials> {
        vaultService.verifyPassword(password).onFailure {
            return Result.failure(CloudError.WrongPassword())
        }

        val previous = if (repository.exists()) {
            repository.load(password).getOrNull()
        } else {
            null
        }

        return repository.store(json, password).fold(
            onSuccess = { credentials ->
                session.unlock(credentials)

                if (previous?.clientId != credentials.clientId) {
                    auth.forgetOAuthSession()
                }

                Result.success(credentials)
            },
            onFailure = { error ->
                Result.failure(
                    when (error) {
                        is CloudError.InvalidCredentialsJson -> error
                        else -> CloudError.CredentialsCorrupt()
                    }
                )
            },
        )
    }
}
