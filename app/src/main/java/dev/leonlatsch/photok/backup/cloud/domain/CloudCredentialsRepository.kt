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

/**
 * Encrypted, on-device storage for the Google Drive OAuth client credentials.
 *
 * The credentials are encrypted with a key derived from the vault password. They are not
 * part of the vault, a local backup archive, or Android Auto Backup.
 */
interface CloudCredentialsRepository {

    fun exists(): Boolean

    suspend fun load(password: String): Result<DriveClientCredentials>

    /**
     * Parses the pasted `google.json` and replaces the stored credentials on success.
     * A malformed document leaves the previous file untouched.
     */
    suspend fun store(json: String, password: String): Result<DriveClientCredentials>

    suspend fun clear()

    /**
     * Re-encrypts the stored credentials with [newPassword]. Deletes the file when the
     * old credentials cannot be read or the new file cannot be written.
     */
    suspend fun reencrypt(oldPassword: String, newPassword: String): Result<Unit>

    /**
     * Re-encrypts the stored credentials with the current KDF iteration count when they were
     * written with an older count. A no-op for missing or already-current files. Failures leave
     * the existing file untouched and are reported through the returned [Result].
     */
    suspend fun upgradeIfOutdated(password: String): Result<Unit>
}
