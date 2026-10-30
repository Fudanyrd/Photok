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
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.backup.cloud.domain.newestFirst
import kotlinx.serialization.Serializable

@Serializable
enum class CloudBackupMode {
    Manage,
    Restore,
}

data class CloudBackupUiState(
    val configured: Boolean = true,
    val connection: CloudConnection = CloudConnection.Disconnected,
    val backups: List<RemoteBackup> = emptyList(),
    val loadingBackups: Boolean = false,
    val busy: CloudBusy? = null,
    val canceling: Boolean = false,
    val error: CloudError? = null,
    val message: CloudMessage? = null,
) {
    val isConnected: Boolean
        get() = connection is CloudConnection.Connected

    val isConnecting: Boolean
        get() = connection is CloudConnection.Connecting

    val accountEmail: String?
        get() = (connection as? CloudConnection.Connected)?.email

    val newestBackup: RemoteBackup?
        get() = backups.newestFirst().firstOrNull()

    val canInteract: Boolean
        get() = busy == null && !canceling
}

sealed interface CloudBusy {

    data class Packing(
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
    ) : CloudBusy

    data class Uploading(
        val bytesSent: Long = 0L,
        val bytesTotal: Long = 0L,
    ) : CloudBusy

    data class Downloading(
        val backup: RemoteBackup,
        val bytesDone: Long = 0L,
        val bytesTotal: Long = 0L,
    ) : CloudBusy

    data class Deleting(val backup: RemoteBackup) : CloudBusy
}

sealed interface CloudMessage {

    data class Uploaded(
        val backup: RemoteBackup,
        val failedPhotos: Int,
    ) : CloudMessage

    data class Deleted(val name: String) : CloudMessage
}

sealed interface CloudNavigationEvent {

    data class OpenRestore(val backupUri: Uri) : CloudNavigationEvent
}
