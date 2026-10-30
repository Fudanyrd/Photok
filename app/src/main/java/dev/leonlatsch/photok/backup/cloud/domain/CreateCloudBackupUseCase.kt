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

import dev.leonlatsch.photok.backup.domain.BackupStrategyImpl
import dev.leonlatsch.photok.encryption.domain.VaultProtectionRepository
import dev.leonlatsch.photok.encryption.domain.models.VaultProtectionType
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Writes a normal local backup archive into [target] so it can be uploaded to a cloud
 * provider. The archive format is unchanged: the existing restore pipeline can read it.
 */
class CreateCloudBackupUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val backupStrategy: BackupStrategyImpl,
    private val vaultProtectionRepository: VaultProtectionRepository,
) {

    suspend operator fun invoke(
        target: File,
        onProgress: suspend (filesDone: Int, filesTotal: Int) -> Unit = { _, _ -> },
    ): Result<CloudArchive> = withContext(Dispatchers.IO) {
        try {
            checkNotNull(vaultProtectionRepository.getProtection(VaultProtectionType.Password)) {
                "No password protection found, cannot write a backup"
            }

            val photos = photoRepository.findAllPhotosByImportDateDesc()
            val requiredBytes = photos.sumOf { it.size }

            target.parentFile?.mkdirs()
            val availableBytes = target.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (requiredBytes > 0 && availableBytes < requiredBytes) {
                return@withContext Result.failure(
                    CloudError.InsufficientSpace(
                        requiredBytes = requiredBytes,
                        availableBytes = availableBytes,
                    )
                )
            }

            var failedPhotos = 0

            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
                // Encrypted files do not compress. This also keeps the archive size close to
                // the sum of the vault file sizes, which the space check above relies on.
                zip.setLevel(Deflater.NO_COMPRESSION)

                backupStrategy.createMetaFileInBackup(zip).getOrThrow()

                photos.forEachIndexed { index, photo ->
                    coroutineContext.ensureActive()

                    backupStrategy.writePhotoToBackup(photo, zip)
                        .onFailure {
                            failedPhotos++
                            Timber.e(it, "Could not add ${photo.uuid} to the cloud backup")
                        }

                    onProgress(index + 1, photos.size)
                }
            }

            Result.success(
                CloudArchive(
                    file = target,
                    photoCount = photos.size,
                    failedPhotos = failedPhotos,
                )
            )
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: CloudError) {
            target.delete()
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Could not create a cloud backup archive")
            target.delete()
            Result.failure(e)
        }
    }
}

data class CloudArchive(
    val file: File,
    val photoCount: Int,
    val failedPhotos: Int,
)
