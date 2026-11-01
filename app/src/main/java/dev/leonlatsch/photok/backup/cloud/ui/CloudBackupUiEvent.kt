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

import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup

sealed interface CloudBackupUiEvent {

    data class ConnectClicked(val browser: OAuthBrowser) : CloudBackupUiEvent

    data object ImportCredentialsClicked : CloudBackupUiEvent

    data class CredentialsPasswordEntered(val password: String) : CloudBackupUiEvent

    data class CredentialsJsonSubmitted(
        val json: String,
        val password: String,
    ) : CloudBackupUiEvent

    data object CredentialsDialogDismissed : CloudBackupUiEvent

    data object CancelConnectClicked : CloudBackupUiEvent

    data object DisconnectClicked : CloudBackupUiEvent

    data object RemoveCredentialsClicked : CloudBackupUiEvent

    data object RefreshClicked : CloudBackupUiEvent

    data object BackupClicked : CloudBackupUiEvent

    data class RestoreClicked(val backup: RemoteBackup) : CloudBackupUiEvent

    data class DeleteClicked(val backup: RemoteBackup) : CloudBackupUiEvent

    data object CancelClicked : CloudBackupUiEvent

    data object ErrorDismissed : CloudBackupUiEvent

    data object MessageDismissed : CloudBackupUiEvent
}
