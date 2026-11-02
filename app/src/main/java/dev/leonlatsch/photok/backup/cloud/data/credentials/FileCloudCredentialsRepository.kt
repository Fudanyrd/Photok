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

package dev.leonlatsch.photok.backup.cloud.data.credentials

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsCipher
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.DriveClientCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileCloudCredentialsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: CloudCredentialsCipher,
    private val parser: DriveCredentialsParser,
    private val gson: Gson,
) : CloudCredentialsRepository {

    override fun exists(): Boolean = credentialsFile().exists()

    override suspend fun load(password: String): Result<DriveClientCredentials> =
        withContext(Dispatchers.IO) {
            val file = credentialsFile()
            if (!file.exists()) {
                return@withContext Result.failure(CloudError.CredentialsMissing())
            }

            try {
                val plaintext = cipher.decrypt(file.readBytes(), password)
                val credentials = gson.fromJson(
                    String(plaintext, Charsets.UTF_8),
                    DriveClientCredentials::class.java,
                )

                if (credentials == null ||
                    credentials.clientId.isBlank() ||
                    credentials.clientSecret.isBlank()
                ) {
                    Result.failure(CloudError.CredentialsCorrupt())
                } else {
                    Result.success(credentials)
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not read the stored Drive credentials")
                Result.failure(CloudError.CredentialsCorrupt())
            }
        }

    override suspend fun store(
        json: String,
        password: String,
    ): Result<DriveClientCredentials> = withContext(Dispatchers.IO) {
        parser.parse(json).mapCatching { credentials ->
            write(credentials, password)
            credentials
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        deleteFiles()
    }

    override suspend fun reencrypt(
        oldPassword: String,
        newPassword: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val credentials = load(oldPassword).getOrThrow()
            write(credentials, newPassword)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Could not re-encrypt the Drive credentials, deleting them")
            deleteFiles()
            Result.failure(e)
        }
    }

    override suspend fun upgradeIfOutdated(password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val file = credentialsFile()
            if (!file.exists()) {
                return@withContext Result.failure(CloudError.CredentialsMissing())
            }

            try {
                if (cipher.iterations(file.readBytes()) >= CloudCredentialsCipher.TARGET_ITERATIONS) {
                    return@withContext Result.success(Unit)
                }

                val credentials = load(password).getOrThrow()
                write(credentials, password)
                Timber.i("Upgraded Drive credentials to current KDF iterations")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.w(e, "Failed to upgrade Drive credentials, keeping the old file")
                Result.failure(e)
            }
        }

    private fun write(credentials: DriveClientCredentials, password: String) {
        val plaintext = gson.toJson(credentials).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plaintext, password)

        val file = credentialsFile()
        file.parentFile?.mkdirs()

        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeBytes(encrypted)
            moveIntoPlace(temp, file)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteFiles() {
        val file = credentialsFile()
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun credentialsFile(): File =
        File(File(context.noBackupFilesDir, DIRECTORY), FILE_NAME)

    private companion object {
        const val DIRECTORY = "cloud"
        const val FILE_NAME = "drive-credentials.enc"
    }
}
