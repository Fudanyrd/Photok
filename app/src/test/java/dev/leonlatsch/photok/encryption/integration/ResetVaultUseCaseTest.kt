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

package dev.leonlatsch.photok.encryption.integration

import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsSession
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudTokenStore
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsRepository
import dev.leonlatsch.photok.encryption.domain.ResetVaultUseCase
import dev.leonlatsch.photok.encryption.domain.SessionRepository
import dev.leonlatsch.photok.encryption.domain.VaultService
import dev.leonlatsch.photok.gallery.albums.domain.AlbumRepository
import dev.leonlatsch.photok.io.VaultCacheStorage
import dev.leonlatsch.photok.model.io.ThumbnailFiles
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import dev.leonlatsch.photok.settings.data.Config
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResetVaultUseCaseTest {

    private val photoRepository = mockk<PhotoRepository>(relaxed = true)
    private val albumRepository = mockk<AlbumRepository>(relaxed = true)
    private val vaultService = mockk<VaultService>(relaxed = true)
    private val config = mockk<Config>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val vaultCacheStorage = mockk<VaultCacheStorage>(relaxed = true)
    private val credentialsRepository = mockk<CloudCredentialsRepository>(relaxed = true)
    private val credentialsSession = mockk<CloudCredentialsSession>(relaxed = true)
    private val tokenStore = mockk<CloudTokenStore>(relaxed = true)

    private val useCase = ResetVaultUseCase(
        photoRepository = photoRepository,
        albumRepository = albumRepository,
        vaultService = vaultService,
        config = config,
        sessionRepository = sessionRepository,
        vaultCacheStorage = vaultCacheStorage,
        cloudCredentialsRepository = credentialsRepository,
        cloudCredentialsSession = credentialsSession,
        cloudTokenStore = tokenStore,
    )

    @Test
    fun `reset deletes drive credentials and the oauth session`() = runTest {
        coEvery { photoRepository.findAllPhotosByImportDateDesc() } returns emptyList()

        useCase()

        coVerify(exactly = 1) { credentialsRepository.clear() }
        verify(exactly = 1) { credentialsSession.clear() }
        verify(exactly = 1) { tokenStore.clear() }
        verify(exactly = 1) { sessionRepository.reset() }
        verify(exactly = 1) { vaultCacheStorage.deleteDir(ThumbnailFiles.DIR) }
    }
}
