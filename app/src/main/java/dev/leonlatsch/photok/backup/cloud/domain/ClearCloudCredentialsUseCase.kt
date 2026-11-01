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
import javax.inject.Inject

/**
 * Removes the locally stored Drive credentials and clears the in-memory credential session
 * and the OAuth token session. The account grant on Google is not revoked.
 */
class ClearCloudCredentialsUseCase @Inject constructor(
    private val repository: CloudCredentialsRepository,
    private val session: CloudCredentialsSession,
    private val auth: CloudAuth,
) {

    suspend operator fun invoke() {
        repository.clear()
        session.clear()
        auth.forgetOAuthSession()
    }
}
