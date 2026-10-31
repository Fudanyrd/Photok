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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.ui.components.PasswordField

@Composable
fun DriveCredentialsDialog(
    mode: CloudCredentialsDialog,
    error: CloudError?,
    loading: Boolean,
    onDismissRequest: () -> Unit,
    onUnlock: (password: String) -> Unit,
    onImport: (json: String, password: String) -> Unit,
) {
    val importing = mode == CloudCredentialsDialog.Import

    var json by rememberSaveable(mode) { mutableStateOf("") }
    var password by rememberSaveable(mode) { mutableStateOf("") }

    val canSubmit = password.isNotEmpty() && (!importing || json.isNotBlank()) && !loading

    AlertDialog(
        onDismissRequest = { if (!loading) onDismissRequest() },
        title = {
            Text(
                stringResource(
                    if (importing) {
                        R.string.cloud_backup_not_configured
                    } else {
                        R.string.cloud_backup_credentials_unlock_title
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (importing) {
                    Text(
                        text = stringResource(R.string.cloud_backup_not_configured_help),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    OutlinedTextField(
                        value = json,
                        onValueChange = { json = it },
                        label = { Text(stringResource(R.string.cloud_backup_credentials_json)) },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.cloud_backup_credentials_unlock_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.common_password),
                    imeAction = ImeAction.Done,
                    onDone = {
                        if (canSubmit) {
                            if (importing) onImport(json, password) else onUnlock(password)
                        }
                    },
                )

                credentialErrorText(error)?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (importing) onImport(json, password) else onUnlock(password)
                },
                enabled = canSubmit,
            ) {
                Text(
                    stringResource(
                        if (importing) {
                            R.string.cloud_backup_credentials_import
                        } else {
                            R.string.cloud_backup_credentials_unlock
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !loading,
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun credentialErrorText(error: CloudError?): String? = when (error) {
    null -> null
    is CloudError.CredentialsMissing -> stringResource(R.string.cloud_backup_error_not_configured)
    is CloudError.CredentialsLocked -> stringResource(R.string.cloud_backup_error_credentials_locked)
    is CloudError.InvalidCredentialsJson ->
        stringResource(R.string.cloud_backup_error_credentials_invalid)

    is CloudError.CredentialsCorrupt ->
        stringResource(R.string.cloud_backup_error_credentials_corrupt)

    is CloudError.WrongPassword -> stringResource(R.string.cloud_backup_error_wrong_password)
    else -> stringResource(R.string.cloud_backup_error_generic)
}
