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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number of remote archives kept after a successful upload. */
const val CLOUD_BACKUP_KEEP = 3

private const val FILE_PREFIX = "photok_backup_"
private const val FILE_EXTENSION = ".zip"

private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

fun cloudBackupFileName(
    instant: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = "$FILE_PREFIX${fileNameFormatter.format(instant.atZone(zone))}$FILE_EXTENSION"

fun isCloudBackupFile(name: String): Boolean =
    name.startsWith(FILE_PREFIX) && name.endsWith(FILE_EXTENSION)

/** Newest first, with the name as a stable tie breaker for files uploaded in the same second. */
fun List<RemoteBackup>.newestFirst(): List<RemoteBackup> = sortedWith(
    compareByDescending<RemoteBackup> { it.createdAt ?: 0L }
        .thenByDescending { it.name }
)

fun List<RemoteBackup>.archivesToPrune(keep: Int): List<RemoteBackup> =
    newestFirst().drop(keep.coerceAtLeast(0))
