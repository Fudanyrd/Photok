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
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.CreateCloudBackupUseCase
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CloudBackupUiState(
            configured = auth.configured,
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
            auth.connection.collect { connection ->
                _uiState.update { it.copy(connection = connection) }

                if (connection is CloudConnection.Disconnected) {
                    refreshJob?.cancel()
                    _uiState.update { it.copy(loadingBackups = false) }
                }
            }
        }

        if (auth.connection.value is CloudConnection.Connected) {
            refresh()
        }
    }

    fun handleUiEvent(event: CloudBackupUiEvent) {
        when (event) {
            is CloudBackupUiEvent.ConnectClicked -> connect(event.browser)
            is CloudBackupUiEvent.DisconnectClicked -> disconnect()
            is CloudBackupUiEvent.RefreshClicked -> refresh()
            is CloudBackupUiEvent.BackupClicked -> startBackup()
            is CloudBackupUiEvent.RestoreClicked -> startRestore(event.backup)
            is CloudBackupUiEvent.DeleteClicked -> startDelete(event.backup)
            is CloudBackupUiEvent.CancelClicked -> cancel()
            is CloudBackupUiEvent.ErrorDismissed -> _uiState.update { it.copy(error = null) }
            is CloudBackupUiEvent.MessageDismissed -> _uiState.update { it.copy(message = null) }
        }
    }

    private fun connect(browser: OAuthBrowser) {
        if (!_uiState.value.canInteract) return

        viewModelScope.launch {
            _uiState.update { it.copy(error = null, message = null) }

            auth.connect(browser)
                .onSuccess { connection ->
                    _uiState.update { it.copy(connection = connection) }
                    refresh()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.asCloudError()) }
                }
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
                )
            }
        }
    }

    private fun refresh() {
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

    private fun startBackup() {
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

    private fun startRestore(backup: RemoteBackup) {
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

    private fun startDelete(backup: RemoteBackup) {
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

        _uiState.update {
            it.copy(
                busy = null,
                loadingBackups = false,
                error = error.asCloudError(),
            )
        }

        // A revoked or insufficient grant needs a fresh consent, not a retry with the
        // same token. Clearing the session also brings the Connect button back.
        if (error is CloudError.AuthorizationFailed) {
            auth.disconnect()
        }
    }

    private fun Throwable.asCloudError(): CloudError = this as? CloudError ?: CloudError.Unknown(this)
}
