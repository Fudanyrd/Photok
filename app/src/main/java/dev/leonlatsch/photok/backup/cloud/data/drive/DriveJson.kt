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

package dev.leonlatsch.photok.backup.cloud.data.drive

import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import java.time.Instant

data class DriveFileList(
    val files: List<DriveFile>? = null,
    val nextPageToken: String? = null,
)

data class DriveFile(
    val id: String? = null,
    val name: String? = null,
    val size: String? = null,
    val createdTime: String? = null,
)

data class DriveErrorEnvelope(
    val error: DriveErrorBody? = null,
)

data class DriveErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val errors: List<DriveErrorDetail>? = null,
)

data class DriveErrorDetail(
    val reason: String? = null,
)

fun DriveFile.toRemoteBackup(): RemoteBackup? {
    val fileId = id ?: return null
    val fileName = name ?: return null

    val createdAt = createdTime?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    return RemoteBackup(
        id = fileId,
        name = fileName,
        sizeBytes = size?.toLongOrNull() ?: 0L,
        createdAt = createdAt,
    )
}
