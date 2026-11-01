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

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonlatsch.photok.backup.cloud.domain.CLOUD_BACKUP_KEEP
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.domain.CloudBackupRepository
import dev.leonlatsch.photok.backup.cloud.domain.ClearCloudCredentialsUseCase
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsState
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CreateCloudBackupUseCase
import dev.leonlatsch.photok.backup.cloud.domain.LoadCloudCredentialsUseCase
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.backup.cloud.domain.StoreCloudCredentialsUseCase
import dev.leonlatsch.photok.backup.cloud.domain.cloudBackupFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val auth: CloudAuth,
    private val repository: CloudBackupRepository,
    private val createBackup: CreateCloudBackupUseCase,
    private val loadCredentials: LoadCloudCredentialsUseCase,
    private val storeCredentials: StoreCloudCredentialsUseCase,
    private val clearCredentials: ClearCloudCredentialsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CloudBackupUiState(
            credentials = auth.credentialsState.value,
            connection = auth.connection.value,
        )
    )
    val uiState = _uiState.asStateFlow()

    private val navigationChannel = Channel<CloudNavigationEvent>(Channel.BUFFERED)
    val navigationEvents = navigationChannel.receiveAsFlow()

    private var busyJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupTempFiles()
        }

        viewModelScope.launch {
            var wasUnlocked = _uiState.value.credentialsUnlocked

            auth.credentialsState.collect { credentials ->
                val isUnlocked = credentials is CloudCredentialsState.Unlocked
                _uiState.update { it.copy(credentials = credentials) }

                if (isUnlocked &&
                    !wasUnlocked &&
                    _uiState.value.pendingAction == null &&
                    _uiState.value.credentialsDialog == null &&
                    _uiState.value.isConnected
                ) {
                    refreshInternal()
                }

                wasUnlocked = isUnlocked
            }
        }

        viewModelScope.launch {
            auth.connection.collect { connection ->
                val wasConnected = _uiState.value.isConnected
                _uiState.update { it.copy(connection = connection) }

                if (connection is CloudConnection.Connected &&
                    !wasConnected &&
                    _uiState.value.credentialsUnlocked
                ) {
                    refreshInternal()
                }

                if (connection is CloudConnection.Disconnected) {
                    refreshJob?.cancel()
                    _uiState.update { it.copy(loadingBackups = false) }
                }
            }
        }

        viewModelScope.launch {
            auth.lastError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                    auth.clearLastError()
                }
            }
        }

        if (auth.credentialsState.value is CloudCredentialsState.Unlocked &&
            auth.connection.value is CloudConnection.Connected
        ) {
            refreshInternal()
        }
    }

    fun handleUiEvent(event: CloudBackupUiEvent) {
        when (event) {
            is CloudBackupUiEvent.ConnectClicked -> requestCredentials(
                CloudPendingAction.Connect(event.browser)
            ) {
                auth.connect(event.browser)
            }

            is CloudBackupUiEvent.ImportCredentialsClicked -> showCredentialsDialog(
                CloudCredentialsDialog.Import,
                action = null,
            )

            is CloudBackupUiEvent.CredentialsPasswordEntered ->
                unlockCredentials(event.password)

            is CloudBackupUiEvent.CredentialsJsonSubmitted ->
                importCredentials(event.json, event.password)

            is CloudBackupUiEvent.CredentialsDialogDismissed -> _uiState.update {
                it.copy(
                    credentialsDialog = null,
                    pendingAction = null,
                    credentialsError = null,
                    unlockingCredentials = false,
                )
            }

            is CloudBackupUiEvent.CancelConnectClicked -> auth.cancelConnect()
            is CloudBackupUiEvent.DisconnectClicked -> disconnect()
            is CloudBackupUiEvent.RemoveCredentialsClicked -> removeCredentials()
            is CloudBackupUiEvent.RefreshClicked -> requestCredentials(CloudPendingAction.Refresh) {
                refreshInternal()
            }

            is CloudBackupUiEvent.BackupClicked -> requestCredentials(CloudPendingAction.Backup) {
                performBackup()
            }

            is CloudBackupUiEvent.RestoreClicked -> requestCredentials(
                CloudPendingAction.Restore(event.backup)
            ) {
                performRestore(event.backup)
            }

            is CloudBackupUiEvent.DeleteClicked -> requestCredentials(
                CloudPendingAction.Delete(event.backup)
            ) {
                performDelete(event.backup)
            }

            is CloudBackupUiEvent.CancelClicked -> cancel()
            is CloudBackupUiEvent.ErrorDismissed -> _uiState.update { it.copy(error = null) }
            is CloudBackupUiEvent.MessageDismissed -> _uiState.update { it.copy(message = null) }
        }
    }

    private fun requestCredentials(
        action: CloudPendingAction,
        block: () -> Unit,
    ) {
        if (!_uiState.value.canInteract) return

        if (_uiState.value.credentialsUnlocked) {
            block()
            return
        }

        val dialog = when (_uiState.value.credentials) {
            CloudCredentialsState.Missing -> CloudCredentialsDialog.Import
            CloudCredentialsState.Locked, CloudCredentialsState.Unlocked ->
                CloudCredentialsDialog.Unlock
        }

        showCredentialsDialog(dialog, action)
    }

    private fun showCredentialsDialog(
        dialog: CloudCredentialsDialog,
        action: CloudPendingAction?,
    ) {
        if (!_uiState.value.canInteract) return

        _uiState.update {
            it.copy(
                credentialsDialog = dialog,
                pendingAction = action,
                credentialsError = null,
            )
        }
    }

    private fun unlockCredentials(password: String) {
        if (_uiState.value.credentialsDialog != CloudCredentialsDialog.Unlock) return
        if (password.isEmpty() || _uiState.value.unlockingCredentials) return

        _uiState.update { it.copy(unlockingCredentials = true, credentialsError = null) }

        viewModelScope.launch {
            loadCredentials(password).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            unlockingCredentials = false,
                            credentialsDialog = null,
                            credentialsError = null,
                        )
                    }
                    continuePendingAction()
                },
                onFailure = { error ->
                    val cloudError = error.asCloudError()
                    _uiState.update {
                        it.copy(
                            unlockingCredentials = false,
                            credentialsError = cloudError,
                            credentialsDialog = if (
                                cloudError is CloudError.CredentialsCorrupt ||
                                cloudError is CloudError.CredentialsMissing
                            ) {
                                CloudCredentialsDialog.Import
                            } else {
                                it.credentialsDialog
                            },
                        )
                    }
                },
            )
        }
    }

    private fun importCredentials(json: String, password: String) {
        if (_uiState.value.credentialsDialog != CloudCredentialsDialog.Import) return
        if (json.isBlank() || password.isEmpty() || _uiState.value.unlockingCredentials) return

        _uiState.update { it.copy(unlockingCredentials = true, credentialsError = null) }

        viewModelScope.launch {
            storeCredentials(json, password).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            unlockingCredentials = false,
                            credentialsDialog = null,
                            credentialsError = null,
                        )
                    }
                    continuePendingAction()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            unlockingCredentials = false,
                            credentialsError = error.asCloudError(),
                        )
                    }
                },
            )
        }
    }

    private fun continuePendingAction() {
        val action = _uiState.value.pendingAction
        _uiState.update { it.copy(pendingAction = null) }

        when (action) {
            is CloudPendingAction.Connect -> auth.connect(action.browser)
            is CloudPendingAction.Refresh -> refreshInternal()
            is CloudPendingAction.Backup -> performBackup()
            is CloudPendingAction.Restore -> performRestore(action.backup)
            is CloudPendingAction.Delete -> performDelete(action.backup)
            null -> if (_uiState.value.isConnected) refreshInternal()
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            busyJob?.cancelAndJoin()
            busyJob = null
            refreshJob?.cancel()
            refreshJob = null

            auth.disconnect()

            _uiState.update {
                it.copy(
                    backups = emptyList(),
                    loadingBackups = false,
                    busy = null,
                    error = null,
                    message = null,
                    canceling = false,
                    credentialsDialog = null,
                    pendingAction = null,
                    credentialsError = null,
                    unlockingCredentials = false,
                )
            }
        }
    }

    private fun removeCredentials() {
        viewModelScope.launch {
            busyJob?.cancelAndJoin()
            busyJob = null
            refreshJob?.cancel()
            refreshJob = null

            clearCredentials()

            _uiState.update {
                it.copy(
                    backups = emptyList(),
                    loadingBackups = false,
                    busy = null,
                    error = null,
                    message = null,
                    canceling = false,
                    credentialsDialog = null,
                    pendingAction = null,
                    credentialsError = null,
                    unlockingCredentials = false,
                )
            }
        }
    }

    private fun refreshInternal() {
        if (_uiState.value.connection !is CloudConnection.Connected) return

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingBackups = true) }

            repository.listBackups().fold(
                onSuccess = { backups ->
                    _uiState.update {
                        it.copy(backups = backups, loadingBackups = false, error = null)
                    }
                },
                onFailure = { error ->
                    fail(error)
                },
            )
        }
    }

    private fun performBackup() {
        if (!_uiState.value.canInteract) return
        if (_uiState.value.connection !is CloudConnection.Connected) return

        val name = cloudBackupFileName()
        val target = repository.tempFile(name)

        _uiState.update {
            it.copy(busy = CloudBusy.Packing(), error = null, message = null, canceling = false)
        }

        busyJob = viewModelScope.launch {
            try {
                val archive = createBackup(target) { done, total ->
                    _uiState.update { state ->
                        state.copy(busy = CloudBusy.Packing(done, total))
                    }
                }.getOrElse { error ->
                    fail(error)
                    return@launch
                }

                _uiState.update {
                    it.copy(busy = CloudBusy.Uploading(0L, archive.file.length()))
                }

                repository.upload(archive.file, name) { sent, total ->
                    _uiState.update { state ->
                        state.copy(busy = CloudBusy.Uploading(sent, total))
                    }
                }.fold(
                    onSuccess = { uploaded ->
                        // An incomplete archive must not replace one of the three good ones.
                        if (archive.failedPhotos == 0) {
                            repository.prune(CLOUD_BACKUP_KEEP).onFailure {
                                Timber.w(it, "Could not prune old cloud backups")
                            }
                        }

                        _uiState.update {
                            it.copy(
                                busy = null,
                                message = CloudMessage.Uploaded(uploaded, archive.failedPhotos),
                            )
                        }
                        reloadBackupsSilently()
                    },
                    onFailure = { error -> fail(error) },
                )
            } finally {
                target.delete()
                if (_uiState.value.busy is CloudBusy.Packing ||
                    _uiState.value.busy is CloudBusy.Uploading
                ) {
                    _uiState.update { it.copy(busy = null) }
                }
            }
        }
    }

    private fun performRestore(backup: RemoteBackup) {
        if (!_uiState.value.canInteract) return
        if (_uiState.value.connection !is CloudConnection.Connected) return

        val target = repository.tempFile("restore_${backup.name}")
        var openedRestore = false

        _uiState.update {
            it.copy(
                busy = CloudBusy.Downloading(backup, 0L, backup.sizeBytes),
                error = null,
                message = null,
                canceling = false,
            )
        }

        busyJob = viewModelScope.launch {
            try {
                repository.download(backup, target) { done, total ->
                    _uiState.update { state ->
                        state.copy(busy = CloudBusy.Downloading(backup, done, total))
                    }
                }.fold(
                    onSuccess = { file ->
                        openedRestore = true
                        navigationChannel.send(
                            CloudNavigationEvent.OpenRestore(Uri.fromFile(file))
                        )
                        _uiState.update { it.copy(busy = null) }
                    },
                    onFailure = { error -> fail(error) },
                )
            } finally {
                if (!openedRestore) {
                    target.delete()
                }

                if (_uiState.value.busy is CloudBusy.Downloading) {
                    _uiState.update { it.copy(busy = null) }
                }
            }
        }
    }

    private fun performDelete(backup: RemoteBackup) {
        if (!_uiState.value.canInteract) return
        if (_uiState.value.connection !is CloudConnection.Connected) return

        _uiState.update {
            it.copy(
                busy = CloudBusy.Deleting(backup),
                error = null,
                message = null,
                canceling = false,
            )
        }

        busyJob = viewModelScope.launch {
            repository.delete(backup.id).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            busy = null,
                            backups = it.backups.filterNot { item -> item.id == backup.id },
                            message = CloudMessage.Deleted(backup.name),
                        )
                    }
                },
                onFailure = { error -> fail(error) },
            )
        }
    }

    private fun cancel() {
        if (_uiState.value.canceling || _uiState.value.busy == null) return

        _uiState.update { it.copy(canceling = true) }

        viewModelScope.launch {
            busyJob?.cancelAndJoin()
            busyJob = null

            _uiState.update { it.copy(busy = null, canceling = false) }
        }
    }

    private fun reloadBackupsSilently() {
        viewModelScope.launch {
            repository.listBackups()
                .onSuccess { backups ->
                    _uiState.update { it.copy(backups = backups) }
                }
                .onFailure {
                    Timber.w(it, "Could not reload cloud backups")
                }
        }
    }

    private suspend fun fail(error: Throwable) {
        if (error is CancellationException) return

        Timber.e(error, "Cloud backup operation failed")

        val cloudError = error.asCloudError()

        when (cloudError) {
            is CloudError.CredentialsMissing -> _uiState.update {
                it.copy(
                    busy = null,
                    loadingBackups = false,
                    error = null,
                    credentialsDialog = CloudCredentialsDialog.Import,
                    pendingAction = null,
                )
            }

            is CloudError.CredentialsLocked -> _uiState.update {
                it.copy(
                    busy = null,
                    loadingBackups = false,
                    error = null,
                    credentialsDialog = CloudCredentialsDialog.Unlock,
                    pendingAction = null,
                )
            }

            else -> _uiState.update {
                it.copy(
                    busy = null,
                    loadingBackups = false,
                    error = cloudError,
                )
            }
        }

        // A revoked or insufficient grant needs a fresh consent, not a retry with the
        // same token. Keep the decrypted credentials so reconnecting does not ask for the
        // vault password again.
        if (cloudError is CloudError.AuthorizationFailed) {
            auth.forgetOAuthSession()
        }
    }

    private fun Throwable.asCloudError(): CloudError = this as? CloudError ?: CloudError.Unknown(this)
}
