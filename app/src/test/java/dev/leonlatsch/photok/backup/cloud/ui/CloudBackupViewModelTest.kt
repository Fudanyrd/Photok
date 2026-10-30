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

package dev.leonlatsch.photok.backup.cloud.ui

import dev.leonlatsch.photok.backup.cloud.domain.CloudArchive
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.domain.CloudBackupRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CreateCloudBackupUseCase
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CloudBackupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val tempDir = File(
        System.getProperty("java.io.tmpdir"),
        "photok-cloud-test-${UUID.randomUUID()}",
    )

    private lateinit var auth: CloudAuth
    private lateinit var repository: CloudBackupRepository
    private lateinit var createBackup: CreateCloudBackupUseCase
    private lateinit var lastError: MutableStateFlow<CloudError?>

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        tempDir.mkdirs()

        auth = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        createBackup = mockk(relaxed = true)
        lastError = MutableStateFlow(null)

        every { auth.lastError } returns lastError
        every { auth.clearLastError() } answers { lastError.value = null }
        every { repository.tempFile(any()) } answers { File(tempDir, firstArg<String>()) }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun `uploads a backup, prunes old ones and deletes the temp archive`() = runTest(dispatcher) {
        val connection = MutableStateFlow<CloudConnection>(
            CloudConnection.Connected("me@example.com")
        )
        every { auth.configured } returns true
        every { auth.connection } returns connection

        coEvery { repository.listBackups() } returns Result.success(emptyList())
        coEvery { repository.upload(any(), any(), any()) } returns Result.success(
            RemoteBackup(
                id = "remote-id",
                name = "photok_backup_2026-10-30_12-00-00.zip",
                sizeBytes = 123L,
                createdAt = 1_000L,
            )
        )
        coEvery { repository.prune(any()) } returns Result.success(0)

        var archiveFile: File? = null
        coEvery { createBackup(any(), any()) } answers {
            val target = firstArg<File>()
            target.parentFile?.mkdirs()
            target.writeText("archive")
            archiveFile = target

            Result.success(CloudArchive(file = target, photoCount = 1, failedPhotos = 0))
        }

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()

        val message = viewModel.uiState.value.message
        assertTrue("Expected an uploaded message, got $message", message is CloudMessage.Uploaded)
        assertNull(viewModel.uiState.value.busy)
        assertNull(viewModel.uiState.value.error)

        coVerify(exactly = 1) { repository.upload(any(), any(), any()) }
        coVerify(exactly = 1) { repository.prune(3) }

        assertFalse("The temp archive must be deleted", archiveFile!!.exists())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `does not prune when some photos failed`() = runTest(dispatcher) {
        val connection = MutableStateFlow<CloudConnection>(
            CloudConnection.Connected("me@example.com")
        )
        every { auth.configured } returns true
        every { auth.connection } returns connection

        coEvery { repository.listBackups() } returns Result.success(emptyList())
        coEvery { repository.upload(any(), any(), any()) } returns Result.success(
            RemoteBackup("remote-id", "backup.zip", 1L, 1_000L)
        )
        coEvery { createBackup(any(), any()) } answers {
            val target = firstArg<File>()
            target.parentFile?.mkdirs()
            target.writeText("archive")

            Result.success(CloudArchive(file = target, photoCount = 2, failedPhotos = 1))
        }

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.prune(any()) }
        assertTrue(viewModel.uiState.value.message is CloudMessage.Uploaded)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `maps an archive failure to a typed error and uploads nothing`() = runTest(dispatcher) {
        val connection = MutableStateFlow<CloudConnection>(
            CloudConnection.Connected("me@example.com")
        )
        every { auth.configured } returns true
        every { auth.connection } returns connection

        coEvery { repository.listBackups() } returns Result.success(emptyList())
        coEvery { createBackup(any(), any()) } returns Result.failure(
            CloudError.InsufficientSpace(requiredBytes = 10, availableBytes = 5)
        )

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertTrue("Expected InsufficientSpace, got $error", error is CloudError.InsufficientSpace)
        assertNull(viewModel.uiState.value.busy)

        coVerify(exactly = 0) { repository.upload(any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a failed connect surfaces the typed error`() = runTest(dispatcher) {
        val connection = MutableStateFlow<CloudConnection>(CloudConnection.Disconnected)
        every { auth.configured } returns true
        every { auth.connection } returns connection
        every { auth.connect(any()) } answers {
            lastError.value = CloudError.NotConfigured()
        }

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.ConnectClicked(OAuthBrowser { }))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error is CloudError.NotConfigured)

        viewModel.viewModelScope.cancel()
    }

    private fun viewModel() = CloudBackupViewModel(
        auth = auth,
        repository = repository,
        createBackup = createBackup,
    )
}
