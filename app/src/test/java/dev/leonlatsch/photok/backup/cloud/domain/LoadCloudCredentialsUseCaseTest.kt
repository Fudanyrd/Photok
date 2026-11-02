/*
 *   Copyright 2020-2026 Leon Latsch
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadCloudCredentialsUseCaseTest {

    private val vaultService = mockk<VaultService>(relaxed = true)
    private val repository = mockk<CloudCredentialsRepository>(relaxed = true)
    private val session = mockk<CloudCredentialsSession>(relaxed = true)

    private val useCase = LoadCloudCredentialsUseCase(vaultService, repository, session)

    @Test
    fun `unlocks and upgrades after a successful load`() = runTest {
        coEvery { vaultService.verifyPassword("password") } returns Result.success(Unit)
        coEvery { repository.load("password") } returns Result.success(
            DriveClientCredentials("id", "secret")
        )

        val result = useCase("password")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { session.unlock(any()) }
        coVerify(exactly = 1) { repository.upgradeIfOutdated("password") }
    }

    @Test
    fun `wrong password does not touch the repository`() = runTest {
        coEvery { vaultService.verifyPassword("wrong") } returns Result.failure(IllegalArgumentException())

        val result = useCase("wrong")

        assertTrue(result.exceptionOrNull() is CloudError.WrongPassword)
        coVerify(exactly = 0) { repository.load(any()) }
        coVerify(exactly = 0) { repository.upgradeIfOutdated(any()) }
    }

    @Test
    fun `failed load does not upgrade`() = runTest {
        coEvery { vaultService.verifyPassword("password") } returns Result.success(Unit)
        coEvery { repository.load("password") } returns Result.failure(CloudError.CredentialsCorrupt())

        val result = useCase("password")

        assertTrue(result.exceptionOrNull() is CloudError.CredentialsCorrupt)
        coVerify(exactly = 0) { repository.upgradeIfOutdated(any()) }
        verify(exactly = 0) { session.unlock(any()) }
    }
}
