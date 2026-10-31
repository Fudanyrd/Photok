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

import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.backup.cloud.domain.CLOUD_BACKUP_KEEP
import dev.leonlatsch.photok.backup.cloud.domain.CloudConnection
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsState
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.OAuthBrowser
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.main.ui.navigation.AppRoute
import dev.leonlatsch.photok.navigation.LocalNavigator
import dev.leonlatsch.photok.ui.ObserveAsEvents
import dev.leonlatsch.photok.ui.theme.AppTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun CloudBackupScreen(mode: CloudBackupMode) {
    val navigator = LocalNavigator.current
    val activity = LocalActivity.current

    val viewModel: CloudBackupViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val browser = remember(activity) {
        activity?.let { host -> OAuthBrowser { url -> openOAuthBrowser(host, url) } }
    }

    ObserveAsEvents(viewModel.navigationEvents) { event ->
        when (event) {
            is CloudNavigationEvent.OpenRestore ->
                navigator.navigate(AppRoute.RestoreBackup(event.backupUri.toString()))
        }
    }

    DisposableEffect(uiState.busy != null) {
        val window = activity?.window

        if (uiState.busy != null) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    CloudBackupContent(
        state = uiState,
        mode = mode,
        browser = browser,
        onBack = navigator::goBack,
        onEvent = viewModel::handleUiEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudBackupContent(
    state: CloudBackupUiState,
    mode: CloudBackupMode,
    browser: OAuthBrowser?,
    onBack: () -> Unit,
    onEvent: (CloudBackupUiEvent) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<RemoteBackup?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (mode == CloudBackupMode.Restore) {
                                R.string.cloud_backup_restore_title
                            } else {
                                R.string.cloud_backup_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.process_close),
                        )
                    }
                },
                actions = {
                    if (state.isConnected) {
                        IconButton(
                            onClick = { onEvent(CloudBackupUiEvent.RefreshClicked) },
                            enabled = state.canInteract && !state.loadingBackups,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.cloud_backup_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                item {
                    ErrorCard(
                        error = error,
                        onDismiss = { onEvent(CloudBackupUiEvent.ErrorDismissed) },
                    )
                }
            }

            state.message?.let { message ->
                item {
                    MessageCard(
                        message = message,
                        onDismiss = { onEvent(CloudBackupUiEvent.MessageDismissed) },
                    )
                }
            }

            item {
                ConnectionCard(
                    state = state,
                    browser = browser,
                    onEvent = onEvent,
                )
            }

            if (state.credentials is CloudCredentialsState.Missing) {
                item {
                    CredentialsMissingCard(
                        onImport = { onEvent(CloudBackupUiEvent.ImportCredentialsClicked) },
                    )
                }
            }

            state.busy?.let { busy ->
                item {
                    BusyCard(
                        busy = busy,
                        canceling = state.canceling,
                        onCancel = { onEvent(CloudBackupUiEvent.CancelClicked) },
                    )
                }
            }

            if (state.isConnected) {
                if (mode == CloudBackupMode.Manage) {
                    item {
                        BackupCard(
                            state = state,
                            onBackup = { onEvent(CloudBackupUiEvent.BackupClicked) },
                        )
                    }
                }

                item { PrivacyCard() }

                item {
                    Text(
                        text = stringResource(R.string.cloud_backup_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                when {
                    state.loadingBackups -> item { LoadingRow() }

                    state.backups.isEmpty() -> item {
                        Text(
                            text = stringResource(R.string.cloud_backup_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> items(state.backups, key = { it.id }) { backup ->
                        BackupRow(
                            backup = backup,
                            enabled = state.canInteract,
                            onRestore = {
                                onEvent(CloudBackupUiEvent.RestoreClicked(backup))
                            },
                            onDelete = { deleteTarget = backup },
                        )
                    }
                }
            }
        }
    }

    state.credentialsDialog?.let { dialog ->
        DriveCredentialsDialog(
            mode = dialog,
            error = state.credentialsError,
            loading = state.unlockingCredentials,
            onDismissRequest = { onEvent(CloudBackupUiEvent.CredentialsDialogDismissed) },
            onUnlock = { password ->
                onEvent(CloudBackupUiEvent.CredentialsPasswordEntered(password))
            },
            onImport = { json, password ->
                onEvent(CloudBackupUiEvent.CredentialsJsonSubmitted(json, password))
            },
        )
    }

    deleteTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.cloud_backup_delete_title)) },
            text = { Text(stringResource(R.string.cloud_backup_delete_message, backup.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onEvent(CloudBackupUiEvent.DeleteClicked(backup))
                    }
                ) {
                    Text(stringResource(R.string.cloud_backup_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    state: CloudBackupUiState,
    browser: OAuthBrowser?,
    onEvent: (CloudBackupUiEvent) -> Unit,
) {
    val subtitle = when {
        state.isConnected -> state.accountEmail?.let {
            stringResource(R.string.cloud_backup_connected_as, it)
        } ?: stringResource(R.string.cloud_backup_connected)

        state.isConnecting -> stringResource(R.string.cloud_backup_connecting)
        state.credentials is CloudCredentialsState.Missing ->
            stringResource(R.string.cloud_backup_not_configured)
        state.credentialsUnlocked -> stringResource(R.string.cloud_backup_not_connected)
        else -> stringResource(R.string.cloud_backup_credentials_locked)
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isConnected) R.drawable.ic_cloud else R.drawable.ic_cloud_off
                    ),
                    contentDescription = null,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cloud_backup_service_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            when {
                state.isConnected -> TextButton(
                    onClick = { onEvent(CloudBackupUiEvent.DisconnectClicked) },
                    enabled = state.canInteract,
                ) {
                    Text(stringResource(R.string.cloud_backup_disconnect))
                }

                state.isConnecting -> TextButton(
                    onClick = { onEvent(CloudBackupUiEvent.CancelConnectClicked) },
                ) {
                    Text(stringResource(R.string.cloud_backup_cancel))
                }

                state.credentials is CloudCredentialsState.Missing -> Unit

                else -> Button(
                    onClick = {
                        browser?.let { onEvent(CloudBackupUiEvent.ConnectClicked(it)) }
                    },
                    enabled = browser != null && state.canInteract,
                ) {
                    Text(stringResource(R.string.cloud_backup_connect))
                }
            }
        }
    }
}

@Composable
private fun CredentialsMissingCard(onImport: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cloud_backup_not_configured_help),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onImport) {
                Text(stringResource(R.string.cloud_backup_credentials_import))
            }
        }
    }
}

@Composable
private fun BackupCard(
    state: CloudBackupUiState,
    onBackup: () -> Unit,
) {
    val context = LocalContext.current

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onBackup,
                enabled = state.canInteract,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_save_as),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cloud_backup_backup_now))
            }

            state.newestBackup?.let { newest ->
                Text(
                    text = stringResource(
                        R.string.cloud_backup_last_backup,
                        formatDate(context, newest.createdAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = stringResource(R.string.cloud_backup_retention_note, CLOUD_BACKUP_KEEP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = stringResource(R.string.cloud_backup_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.cloud_backup_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BackupRow(
    backup: RemoteBackup,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val subtitle = listOfNotNull(
        formatDate(context, backup.createdAt).takeIf { it.isNotBlank() },
        formatBytes(context, backup.sizeBytes).takeIf { it.isNotBlank() },
    ).joinToString(" • ")

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_backup_restore),
                contentDescription = null,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(
                onClick = onRestore,
                enabled = enabled,
            ) {
                Text(stringResource(R.string.cloud_backup_restore))
            }

            IconButton(
                onClick = onDelete,
                enabled = enabled,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.cloud_backup_delete),
                )
            }
        }
    }
}

@Composable
private fun BusyCard(
    busy: CloudBusy,
    canceling: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    val title = when (busy) {
        is CloudBusy.Packing -> stringResource(R.string.cloud_backup_packing)
        is CloudBusy.Uploading -> stringResource(R.string.cloud_backup_uploading)
        is CloudBusy.Downloading -> stringResource(R.string.cloud_backup_downloading)
        is CloudBusy.Deleting -> stringResource(R.string.cloud_backup_deleting)
    }

    val progress = when (busy) {
        is CloudBusy.Packing ->
            if (busy.filesTotal > 0) busy.filesDone.toFloat() / busy.filesTotal else null

        is CloudBusy.Uploading ->
            if (busy.bytesTotal > 0) busy.bytesSent.toFloat() / busy.bytesTotal else null

        is CloudBusy.Downloading ->
            if (busy.bytesTotal > 0) busy.bytesDone.toFloat() / busy.bytesTotal else null

        is CloudBusy.Deleting -> null
    }

    val detail = when (busy) {
        is CloudBusy.Packing -> stringResource(
            R.string.cloud_backup_progress_files,
            busy.filesDone,
            busy.filesTotal,
        )

        is CloudBusy.Uploading -> stringResource(
            R.string.cloud_backup_progress_bytes,
            formatBytes(context, busy.bytesSent),
            formatBytes(context, busy.bytesTotal),
        )

        is CloudBusy.Downloading -> stringResource(
            R.string.cloud_backup_progress_bytes,
            formatBytes(context, busy.bytesDone),
            formatBytes(context, busy.bytesTotal),
        )

        is CloudBusy.Deleting -> busy.backup.name
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (busy !is CloudBusy.Deleting) {
                TextButton(
                    onClick = onCancel,
                    enabled = !canceling,
                ) {
                    Text(
                        stringResource(
                            if (canceling) {
                                R.string.cloud_backup_canceling
                            } else {
                                R.string.cloud_backup_cancel
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    error: CloudError,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = errorText(error),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cloud_backup_dismiss))
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: CloudMessage,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (message) {
                    is CloudMessage.Uploaded ->
                        if (message.failedPhotos > 0) {
                            stringResource(
                                R.string.cloud_backup_uploaded_with_failures,
                                message.failedPhotos,
                            )
                        } else {
                            stringResource(R.string.cloud_backup_uploaded)
                        }

                    is CloudMessage.Deleted ->
                        stringResource(R.string.cloud_backup_deleted, message.name)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cloud_backup_dismiss))
            }
        }
    }
}

@Composable
private fun errorText(error: CloudError): String = when (error) {
    is CloudError.NotConfigured,
    is CloudError.CredentialsMissing -> stringResource(R.string.cloud_backup_error_not_configured)

    is CloudError.CredentialsLocked -> stringResource(R.string.cloud_backup_error_credentials_locked)
    is CloudError.InvalidCredentialsJson ->
        stringResource(R.string.cloud_backup_error_credentials_invalid)

    is CloudError.CredentialsCorrupt ->
        stringResource(R.string.cloud_backup_error_credentials_corrupt)

    is CloudError.WrongPassword -> stringResource(R.string.cloud_backup_error_wrong_password)
    is CloudError.NotConnected -> stringResource(R.string.cloud_backup_error_not_connected)
    is CloudError.AuthorizationFailed -> stringResource(R.string.cloud_backup_error_auth)
    is CloudError.QuotaExceeded -> stringResource(R.string.cloud_backup_error_quota)
    is CloudError.InsufficientSpace -> stringResource(
        R.string.cloud_backup_error_space,
        Formatter.formatShortFileSize(LocalContext.current, error.requiredBytes),
        Formatter.formatShortFileSize(LocalContext.current, error.availableBytes),
    )

    is CloudError.Network -> stringResource(R.string.cloud_backup_error_network)
    is CloudError.Remote -> stringResource(R.string.cloud_backup_error_remote, error.code)
    is CloudError.Cancelled -> stringResource(R.string.cloud_backup_error_cancelled)
    is CloudError.Unknown -> stringResource(R.string.cloud_backup_error_generic)
}

private fun formatDate(context: android.content.Context, millis: Long?): String =
    millis?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    }.orEmpty()

private fun formatBytes(context: android.content.Context, bytes: Long): String =
    if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else "—"

@PreviewLightDark
@Composable
private fun PreviewConnected() {
    AppTheme {
        CloudBackupContent(
            state = CloudBackupUiState(
                credentials = CloudCredentialsState.Unlocked,
                connection = CloudConnection.Connected("me@example.com"),
                backups = listOf(
                    RemoteBackup(
                        id = "1",
                        name = "photok_backup_2026-10-30_12-00-00.zip",
                        sizeBytes = 42_000_000,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
            ),
            mode = CloudBackupMode.Manage,
            browser = null,
            onBack = {},
            onEvent = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewDisconnected() {
    AppTheme {
        CloudBackupContent(
            state = CloudBackupUiState(credentials = CloudCredentialsState.Missing),
            mode = CloudBackupMode.Restore,
            browser = null,
            onBack = {},
            onEvent = {},
        )
    }
}
