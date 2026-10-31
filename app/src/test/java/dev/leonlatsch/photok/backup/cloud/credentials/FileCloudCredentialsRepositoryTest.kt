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

package dev.leonlatsch.photok.backup.cloud.credentials

import com.google.gson.Gson
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsCipher
import dev.leonlatsch.photok.backup.cloud.data.credentials.DriveCredentialsParser
import dev.leonlatsch.photok.backup.cloud.data.credentials.FileCloudCredentialsRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.encryption.domain.crypto.KeyGen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileCloudCredentialsRepositoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val repository = FileCloudCredentialsRepository(
        context = context,
        cipher = CloudCredentialsCipher(KeyGen()),
        parser = DriveCredentialsParser(),
        gson = Gson(),
    )

    private val json = """{"installed":{"client_id":"id","client_secret":"secret"}}"""

    @Before
    fun clearStorage() = runTest {
        repository.clear()
    }

    @Test
    fun `missing file is reported as missing`() = runTest {
        assertFalse(repository.exists())

        val result = repository.load("password")

        assertTrue(result.exceptionOrNull() is CloudError.CredentialsMissing)
    }

    @Test
    fun `stores and loads credentials encrypted`() = runTest {
        repository.store(json, "password").getOrThrow()

        assertTrue(repository.exists())
        val loaded = repository.load("password").getOrThrow()

        assertEquals("id", loaded.clientId)
        assertEquals("secret", loaded.clientSecret)

        val bytes = credentialsFile().readBytes()
        assertFalse(bytes.containsSequence("secret".toByteArray()))
    }

    @Test
    fun `wrong password is rejected`() = runTest {
        repository.store(json, "password").getOrThrow()

        val result = repository.load("wrong-password")

        assertTrue(result.exceptionOrNull() is CloudError.CredentialsCorrupt)
    }

    @Test
    fun `malformed import leaves the previous file untouched`() = runTest {
        repository.store(json, "password").getOrThrow()
        val before = credentialsFile().readBytes()

        val result = repository.store("not-json", "password")

        assertTrue(result.exceptionOrNull() is CloudError.InvalidCredentialsJson)
        assertArrayEquals(before, credentialsFile().readBytes())
        assertEquals("id", repository.load("password").getOrThrow().clientId)
    }

    @Test
    fun `corrupt file is reported as corrupt`() = runTest {
        repository.store(json, "password").getOrThrow()
        credentialsFile().writeBytes(byteArrayOf(1, 2, 3))

        val result = repository.load("password")

        assertTrue(result.exceptionOrNull() is CloudError.CredentialsCorrupt)
    }

    @Test
    fun `reencrypt makes the file readable with the new password`() = runTest {
        repository.store(json, "old-password").getOrThrow()

        repository.reencrypt("old-password", "new-password").getOrThrow()

        assertTrue(repository.load("old-password").isFailure)
        assertEquals("id", repository.load("new-password").getOrThrow().clientId)
    }

    @Test
    fun `failed reencrypt deletes the unusable file`() = runTest {
        repository.store(json, "old-password").getOrThrow()

        assertTrue(repository.reencrypt("wrong-password", "new-password").isFailure)
        assertFalse(repository.exists())
    }

    private fun credentialsFile(): File =
        File(File(context.noBackupFilesDir, "cloud"), "drive-credentials.enc")

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false

        return (0..(size - sequence.size)).any { start ->
            sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }
    }
}
