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

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonlatsch.photok.backup.cloud.data.CloudCache
import dev.leonlatsch.photok.backup.cloud.data.awaitResponse
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudTokenStore
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.domain.CloudBackupRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.backup.cloud.domain.archivesToPrune
import dev.leonlatsch.photok.backup.cloud.domain.isCloudBackupFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class GoogleDriveRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val auth: CloudAuth,
    private val tokenStore: CloudTokenStore,
    private val gson: Gson,
    @ApplicationContext private val context: Context,
) : CloudBackupRepository {

    private val filesUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
    private val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()
    private val folderMutex = Mutex()

    override fun tempFile(name: String): File = CloudCache.file(context, name)

    override fun cleanupTempFiles() = CloudCache.cleanup(context)

    override suspend fun listBackups(): Result<List<RemoteBackup>> = cloudResult {
        withContext(Dispatchers.IO) {
            val folder = folderId()
            val files = mutableListOf<DriveFile>()
            var pageToken: String? = null

            do {
                val url = filesUrl.newBuilder()
                    .addQueryParameter("q", "'$folder' in parents and trashed = false")
                    .addQueryParameter("fields", "nextPageToken,files(id,name,size,createdTime)")
                    .addQueryParameter("orderBy", "createdTime desc,name desc")
                    .addQueryParameter("pageSize", PAGE_SIZE.toString())
                    .addQueryParameter("spaces", "drive")
                    .apply {
                        pageToken?.let { token -> addQueryParameter("pageToken", token) }
                    }
                    .build()

                val page = execute(Request.Builder().url(url).build()).use { response ->
                    response.requireSuccess()
                    response.readJson(DriveFileList::class.java)
                }

                files += page.files.orEmpty()
                pageToken = page.nextPageToken
            } while (pageToken != null)

            files
                .filter { file -> file.name?.let(::isCloudBackupFile) == true }
                .mapNotNull { it.toRemoteBackup() }
        }
    }

    override suspend fun upload(
        file: File,
        name: String,
        onProgress: (bytesSent: Long, bytesTotal: Long) -> Unit,
    ): Result<RemoteBackup> = cloudResult {
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                throw CloudError.Unknown(IllegalArgumentException("Backup file does not exist"))
            }

            val total = file.length()
            if (total == 0L) {
                throw CloudError.Remote(0, "emptyArchive")
            }

            val folder = folderId()
            val sessionUrl = createUploadSession(name, total, folder)
            uploadChunks(sessionUrl, file, total, onProgress)
        }
    }

    override suspend fun download(
        backup: RemoteBackup,
        target: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit,
    ): Result<File> = cloudResult {
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()

            val availableBytes = target.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (backup.sizeBytes > 0 && availableBytes < backup.sizeBytes) {
                throw CloudError.InsufficientSpace(
                    requiredBytes = backup.sizeBytes,
                    availableBytes = availableBytes,
                )
            }

            val url = filesUrl.newBuilder()
                .addPathSegment(backup.id)
                .addQueryParameter("alt", "media")
                .build()

            val response = execute(Request.Builder().url(url).build())

            try {
                response.requireSuccess().use { bodyResponse ->
                    val total = bodyResponse.body?.contentLength()
                        ?.takeIf { it > 0 }
                        ?: backup.sizeBytes

                    val body = bodyResponse.body
                        ?: throw CloudError.Remote(bodyResponse.code, "emptyDownload")

                    FileOutputStream(target).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            var done = 0L

                            while (true) {
                                coroutineContext.ensureActive()

                                val read = input.read(buffer)
                                if (read < 0) break

                                output.write(buffer, 0, read)
                                done += read
                                onProgress(done, total)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            }

            target
        }
    }

    override suspend fun delete(id: String): Result<Unit> = cloudResult {
        withContext(Dispatchers.IO) {
            val url = filesUrl.newBuilder().addPathSegment(id).build()
            val response = execute(Request.Builder().url(url).delete().build())

            response.use { bodyResponse ->
                // Already gone is the state we wanted.
                if (bodyResponse.code != 404) {
                    bodyResponse.requireSuccess()
                }
            }
        }
    }

    override suspend fun prune(keep: Int): Result<Int> = cloudResult {
        val backups = listBackups().getOrThrow()
        val toDelete = backups.archivesToPrune(keep)

        toDelete.forEach { backup ->
            delete(backup.id).getOrThrow()
        }

        toDelete.size
    }

    private suspend fun folderId(): String = folderMutex.withLock {
        tokenStore.load()?.folderId?.let { return@withLock it }

        val query = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = filesUrl.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("spaces", "drive")
            .build()

        val existingId = execute(Request.Builder().url(url).build()).use { response ->
            response.requireSuccess()
            response.readJson(DriveFileList::class.java).files?.firstOrNull()?.id
        }

        val id = existingId ?: createFolder()
        cacheFolderId(id)

        id
    }

    private suspend fun createFolder(): String {
        val metadata = mapOf(
            "name" to FOLDER_NAME,
            "mimeType" to FOLDER_MIME,
        )

        val url = filesUrl.newBuilder()
            .addQueryParameter("fields", "id")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(metadata).toRequestBody(JSON_MEDIA))
            .build()

        return execute(request).use { response ->
            response.requireSuccess()
            response.readJson(DriveFile::class.java).id
                ?: throw CloudError.Remote(response.code, "folderWithoutId")
        }
    }

    private fun cacheFolderId(id: String) {
        tokenStore.update { session -> session.copy(folderId = id) }
    }

    private suspend fun createUploadSession(
        name: String,
        totalBytes: Long,
        folder: String,
    ): HttpUrl {
        val metadata = mapOf(
            "name" to name,
            "mimeType" to "application/zip",
            "parents" to listOf(folder),
        )

        val url = uploadUrl.newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,size,createdTime")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(metadata).toRequestBody(JSON_MEDIA))
            .header("X-Upload-Content-Type", "application/zip")
            .header("X-Upload-Content-Length", totalBytes.toString())
            .build()

        return execute(request).use { response ->
            response.requireSuccess()

            response.header("Location")?.toHttpUrlOrNull()
                ?: throw CloudError.Remote(response.code, "noUploadSession")
        }
    }

    private suspend fun uploadChunks(
        sessionUrl: HttpUrl,
        file: File,
        total: Long,
        onProgress: (bytesSent: Long, bytesTotal: Long) -> Unit,
    ): RemoteBackup {
        val buffer = ByteArray(CHUNK_SIZE)
        var offset = 0L

        FileInputStream(file).use { input ->
            while (offset < total) {
                coroutineContext.ensureActive()

                val read = input.read(buffer)
                if (read <= 0) break

                val end = offset + read - 1
                val request = Request.Builder()
                    .url(sessionUrl)
                    .put(buffer.toRequestBody(ZIP_MEDIA, 0, read))
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .build()

                val response = execute(request)

                if (response.code == 308) {
                    // Incomplete, the server accepted this chunk and wants the next one.
                    response.close()
                    offset += read
                    onProgress(offset, total)
                    continue
                }

                response.use { bodyResponse ->
                    bodyResponse.requireSuccess()

                    val driveFile = bodyResponse.readJson(DriveFile::class.java)
                    val uploaded = driveFile.toRemoteBackup()
                        ?: throw CloudError.Remote(bodyResponse.code, "uploadWithoutId")

                    onProgress(total, total)
                    return uploaded
                }
            }
        }

        throw CloudError.Remote(0, "uploadDidNotFinish")
    }

    private suspend fun execute(
        request: Request,
        retryOnUnauthorized: Boolean = true,
    ): Response {
        val token = auth.accessToken().getOrThrow()
        val response = httpClient
            .newCall(request.newBuilder().header("Authorization", "Bearer $token").build())
            .awaitResponse()

        if (response.code == 401 && retryOnUnauthorized) {
            response.close()

            val refreshedToken = auth.accessToken(forceRefresh = true).getOrThrow()
            return httpClient
                .newCall(
                    request.newBuilder()
                        .header("Authorization", "Bearer $refreshedToken")
                        .build()
                )
                .awaitResponse()
        }

        return response
    }

    private fun Response.requireSuccess(): Response {
        if (isSuccessful) return this

        val statusCode = code
        val reason = parseErrorReason(body?.string())
        close()

        throw when {
            reason == "storageQuotaExceeded" -> CloudError.QuotaExceeded()
            statusCode == 401 -> CloudError.AuthorizationFailed()
            statusCode == 403 && reason in AUTHORIZATION_ERROR_REASONS ->
                CloudError.AuthorizationFailed()

            else -> CloudError.Remote(statusCode, reason)
        }
    }

    private fun <T> Response.readJson(type: Class<T>): T = gson.fromJson(
        body?.charStream() ?: throw CloudError.Remote(code, "emptyResponse"),
        type,
    ) ?: throw CloudError.Remote(code, "emptyResponse")

    private fun parseErrorReason(json: String?): String? {
        if (json.isNullOrBlank()) return null

        return try {
            gson.fromJson(json, DriveErrorEnvelope::class.java)
                .error
                ?.errors
                ?.firstOrNull()
                ?.reason
        } catch (e: Exception) {
            Timber.w(e, "Could not parse the Google Drive error")
            null
        }
    }

    private suspend fun <T> cloudResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudError) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(CloudError.Network(e))
    } catch (e: Exception) {
        Result.failure(CloudError.Unknown(e))
    }

    private companion object {
        const val FOLDER_NAME = "Photok"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val PAGE_SIZE = 1000
        const val CHUNK_SIZE = 8 * 1024 * 1024
        const val COPY_BUFFER_SIZE = 64 * 1024

        val AUTHORIZATION_ERROR_REASONS = setOf(
            "insufficientPermissions",
            "insufficientFilePermissions",
            "appNotAuthorizedToFile",
        )

        val JSON_MEDIA: MediaType = "application/json; charset=UTF-8".toMediaType()
        val ZIP_MEDIA: MediaType = "application/zip".toMediaType()
    }
}
