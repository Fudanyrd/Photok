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
import dev.leonlatsch.photok.backup.cloud.domain.ClearCloudCredentialsUseCase
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsState
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CreateCloudBackupUseCase
import dev.leonlatsch.photok.backup.cloud.domain.DriveClientCredentials
import dev.leonlatsch.photok.backup.cloud.domain.LoadCloudCredentialsUseCase
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.backup.cloud.domain.StoreCloudCredentialsUseCase
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertEquals
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
    private lateinit var loadCredentials: LoadCloudCredentialsUseCase
    private lateinit var storeCredentials: StoreCloudCredentialsUseCase
    private lateinit var clearCredentials: ClearCloudCredentialsUseCase
    private lateinit var credentialsState: MutableStateFlow<CloudCredentialsState>
    private lateinit var lastError: MutableStateFlow<CloudError?>

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        tempDir.mkdirs()

        auth = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        createBackup = mockk(relaxed = true)
        loadCredentials = mockk(relaxed = true)
        storeCredentials = mockk(relaxed = true)
        clearCredentials = mockk(relaxed = true)
        credentialsState = MutableStateFlow(CloudCredentialsState.Unlocked)
        lastError = MutableStateFlow(null)

        every { auth.credentialsState } returns credentialsState
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

    @Test
    fun `missing credentials show the import dialog before a backup`() = runTest(dispatcher) {
        credentialsState.value = CloudCredentialsState.Missing
        every { auth.connection } returns MutableStateFlow(CloudConnection.Disconnected)

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()

        assertEquals(
            CloudCredentialsDialog.Import,
            viewModel.uiState.value.credentialsDialog,
        )
        coVerify(exactly = 0) { createBackup(any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `locked credentials ask for the password and then run the pending action`() =
        runTest(dispatcher) {
            credentialsState.value = CloudCredentialsState.Locked
            every { auth.connection } returns MutableStateFlow(
                CloudConnection.Connected("me@example.com")
            )

            coEvery { loadCredentials(any()) } coAnswers {
                credentialsState.value = CloudCredentialsState.Unlocked
                Result.success(DriveClientCredentials("id", "secret"))
            }
            coEvery { repository.listBackups() } returns Result.success(emptyList())
            coEvery { repository.upload(any(), any(), any()) } returns Result.success(
                RemoteBackup("remote-id", "backup.zip", 1L, 1_000L)
            )
            coEvery { repository.prune(any()) } returns Result.success(0)
            coEvery { createBackup(any(), any()) } answers {
                val target = firstArg<File>()
                target.parentFile?.mkdirs()
                target.writeText("archive")

                Result.success(CloudArchive(file = target, photoCount = 1, failedPhotos = 0))
            }

            val viewModel = viewModel()

            viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
            advanceUntilIdle()

            assertEquals(
                CloudCredentialsDialog.Unlock,
                viewModel.uiState.value.credentialsDialog,
            )
            assertNull(viewModel.uiState.value.busy)
            coVerify(exactly = 0) { createBackup(any(), any()) }

            viewModel.handleUiEvent(CloudBackupUiEvent.CredentialsPasswordEntered("password"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.credentialsDialog)
            coVerify(exactly = 1) { loadCredentials("password") }
            coVerify(exactly = 1) { createBackup(any(), any()) }

            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `wrong credentials password keeps the dialog open`() = runTest(dispatcher) {
        credentialsState.value = CloudCredentialsState.Locked
        every { auth.connection } returns MutableStateFlow(CloudConnection.Disconnected)
        coEvery { loadCredentials(any()) } returns Result.failure(CloudError.WrongPassword())

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()
        viewModel.handleUiEvent(CloudBackupUiEvent.CredentialsPasswordEntered("wrong"))
        advanceUntilIdle()

        assertEquals(
            CloudCredentialsDialog.Unlock,
            viewModel.uiState.value.credentialsDialog,
        )
        assertTrue(viewModel.uiState.value.credentialsError is CloudError.WrongPassword)
        coVerify(exactly = 0) { createBackup(any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `corrupt credentials switch to the import dialog`() = runTest(dispatcher) {
        credentialsState.value = CloudCredentialsState.Locked
        every { auth.connection } returns MutableStateFlow(CloudConnection.Disconnected)
        coEvery { loadCredentials(any()) } returns Result.failure(CloudError.CredentialsCorrupt())

        val viewModel = viewModel()

        viewModel.handleUiEvent(CloudBackupUiEvent.BackupClicked)
        advanceUntilIdle()
        viewModel.handleUiEvent(CloudBackupUiEvent.CredentialsPasswordEntered("password"))
        advanceUntilIdle()

        assertEquals(
            CloudCredentialsDialog.Import,
            viewModel.uiState.value.credentialsDialog,
        )
        assertTrue(viewModel.uiState.value.credentialsError is CloudError.CredentialsCorrupt)
        coVerify(exactly = 0) { createBackup(any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `importing credentials resumes a pending connect`() = runTest(dispatcher) {
        credentialsState.value = CloudCredentialsState.Missing
        every { auth.connection } returns MutableStateFlow(CloudConnection.Disconnected)

        coEvery { storeCredentials(any(), any()) } coAnswers {
            credentialsState.value = CloudCredentialsState.Unlocked
            Result.success(DriveClientCredentials("id", "secret"))
        }

        val viewModel = viewModel()
        val browser = OAuthBrowser { }

        viewModel.handleUiEvent(CloudBackupUiEvent.ConnectClicked(browser))
        advanceUntilIdle()

        assertEquals(
            CloudCredentialsDialog.Import,
            viewModel.uiState.value.credentialsDialog,
        )

        viewModel.handleUiEvent(
            CloudBackupUiEvent.CredentialsJsonSubmitted(
                json = """{"installed":{"client_id":"id","client_secret":"secret"}}""",
                password = "password",
            )
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.credentialsDialog)
        coVerify(exactly = 1) { storeCredentials(any(), "password") }
        verify(exactly = 1) { auth.connect(browser) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `removing credentials clears them and resets the screen`() = runTest(dispatcher) {
        every { auth.connection } returns MutableStateFlow(
            CloudConnection.Connected("me@example.com")
        )
        coEvery { repository.listBackups() } returns Result.success(emptyList())

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.handleUiEvent(CloudBackupUiEvent.RemoveCredentialsClicked)
        advanceUntilIdle()

        coVerify(exactly = 1) { clearCredentials() }
        assertTrue(viewModel.uiState.value.backups.isEmpty())
        assertNull(viewModel.uiState.value.busy)
        assertNull(viewModel.uiState.value.credentialsDialog)
        assertNull(viewModel.uiState.value.pendingAction)
        assertNull(viewModel.uiState.value.error)

        viewModel.viewModelScope.cancel()
    }

    private fun viewModel() = CloudBackupViewModel(
        auth = auth,
        repository = repository,
        createBackup = createBackup,
        loadCredentials = loadCredentials,
        storeCredentials = storeCredentials,
        clearCredentials = clearCredentials,
    )
}
