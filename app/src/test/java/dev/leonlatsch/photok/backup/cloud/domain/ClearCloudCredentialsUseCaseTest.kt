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
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClearCloudCredentialsUseCaseTest {

    @Test
    fun `clears the stored credentials, the session and the oauth session`() = runTest {
        val repository = mockk<CloudCredentialsRepository>(relaxed = true)
        val session = mockk<CloudCredentialsSession>(relaxed = true)
        val auth = mockk<CloudAuth>(relaxed = true)

        ClearCloudCredentialsUseCase(repository, session, auth).invoke()

        coVerify(exactly = 1) { repository.clear() }
        verify(exactly = 1) { session.clear() }
        coVerify(exactly = 1) { auth.forgetOAuthSession() }
    }
}
