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

package dev.leonlatsch.photok.backup.cloud.domain

import java.io.File

data class RemoteBackup(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdAt: Long?,
)

interface CloudBackupRepository {

    /**
     * Creates a cache file for an archive or a restore download.
     */
    fun tempFile(name: String): File

    /**
     * Removes temp files left behind by a killed process.
     */
    fun cleanupTempFiles()

    suspend fun listBackups(): Result<List<RemoteBackup>>

    suspend fun upload(
        file: File,
        name: String,
        onProgress: (bytesSent: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): Result<RemoteBackup>

    suspend fun download(
        backup: RemoteBackup,
        target: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): Result<File>

    suspend fun delete(id: String): Result<Unit>

    /**
     * Deletes the oldest backups beyond [keep]. Returns how many were deleted.
     */
    suspend fun prune(keep: Int): Result<Int>
}
