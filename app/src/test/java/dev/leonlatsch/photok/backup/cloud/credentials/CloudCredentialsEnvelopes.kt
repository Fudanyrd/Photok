/*
 *   Copyright 2020-2026 Leon Latsch
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
import dev.leonlatsch.photok.encryption.domain.crypto.SALT_SIZE
import dev.leonlatsch.photok.encryption.domain.models.Algorithm
import dev.leonlatsch.photok.encryption.domain.models.Kdf
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * Builds a credentials envelope with an explicit KDF iteration count, mirroring the on-disk
 * format of [CloudCredentialsCipher]. Used by migration tests to simulate files written before
 * the iteration count was raised.
 */
fun legacyCredentialsEnvelope(
    plaintext: ByteArray,
    password: String,
    iterations: Int,
): ByteArray {
    val keyGen = KeyGen()
    val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
    val key = keyGen.derivePasswordKeyEncryptionKey(
        password = password,
        salt = salt,
        kdf = Kdf.PBKDF2WithHmacSHA256,
        kdfIterations = iterations,
        keySize = KEY_SIZE,
    )

    val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
        init(Cipher.ENCRYPT_MODE, key)
    }

    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plaintext)

    return ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeByte(CloudCredentialsCipher.VERSION)
            data.writeInt(iterations)
            data.write(salt)
            data.writeByte(iv.size)
            data.write(iv)
            data.write(ciphertext)
        }
        output.toByteArray()
    }
}

private const val KEY_SIZE = 256
