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

import dev.leonlatsch.photok.backup.cloud.data.auth.CloudCredentialsCipher
import dev.leonlatsch.photok.encryption.domain.crypto.KeyGen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCredentialsCipherTest {

    private val cipher = CloudCredentialsCipher(KeyGen())
    private val plaintext = """{"clientId":"id","clientSecret":"secret"}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `round trips with the same password`() {
        val encrypted = cipher.encrypt(plaintext, "password")

        assertArrayEquals(plaintext, cipher.decrypt(encrypted, "password"))
    }

    @Test
    fun `encrypt records the current target iterations`() {
        val encrypted = cipher.encrypt(plaintext, "password")

        assertEquals(CloudCredentialsCipher.TARGET_ITERATIONS, cipher.iterations(encrypted))
        assertEquals(300_000, CloudCredentialsCipher.TARGET_ITERATIONS)
    }

    @Test
    fun `iterations reads the recorded count`() {
        val legacy = legacyCredentialsEnvelope(plaintext, "password", iterations = 100_000)

        assertEquals(100_000, cipher.iterations(legacy))
    }

    @Test
    fun `decrypts an envelope written with legacy 100k iterations`() {
        val legacy = legacyCredentialsEnvelope(plaintext, "password", iterations = 100_000)

        assertArrayEquals(plaintext, cipher.decrypt(legacy, "password"))
    }

    @Test
    fun `uses a fresh salt and nonce for every write`() {
        val first = cipher.encrypt(plaintext, "password")
        val second = cipher.encrypt(plaintext, "password")

        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(plaintext, cipher.decrypt(first, "password"))
        assertArrayEquals(plaintext, cipher.decrypt(second, "password"))
    }

    @Test
    fun `wrong password fails authentication`() {
        val encrypted = cipher.encrypt(plaintext, "password")

        assertTrue(runCatching { cipher.decrypt(encrypted, "wrong-password") }.isFailure)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val encrypted = cipher.encrypt(plaintext, "password")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertTrue(runCatching { cipher.decrypt(encrypted, "password") }.isFailure)
    }

    @Test
    fun `envelope does not contain the plaintext`() {
        val encrypted = cipher.encrypt(plaintext, "password")

        assertFalse(encrypted.containsSequence(plaintext))
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false

        return (0..(size - sequence.size)).any { start ->
            sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }
    }
}
