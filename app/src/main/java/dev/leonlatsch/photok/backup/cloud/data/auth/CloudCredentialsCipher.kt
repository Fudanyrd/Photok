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

package dev.leonlatsch.photok.backup.cloud.data.auth

import dev.leonlatsch.photok.encryption.domain.crypto.KeyGen
import dev.leonlatsch.photok.encryption.domain.crypto.SALT_SIZE
import dev.leonlatsch.photok.encryption.domain.models.Algorithm
import dev.leonlatsch.photok.encryption.domain.models.Kdf
import dev.leonlatsch.photok.encryption.domain.models.KekSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM envelope for the Drive client credentials.
 *
 * Format: `[version][kdfIterations][salt][ivLength][iv][ciphertext]`.
 * The salt and IV are random for every write. AES-GCM authenticates the ciphertext, so a
 * wrong password or a tampered file fails instead of producing garbage.
 */
@Singleton
class CloudCredentialsCipher @Inject constructor(
    private val keyGen: KeyGen,
) {

    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = keyGen.derivePasswordKeyEncryptionKey(
            password = password,
            salt = salt,
            kdf = Kdf.PBKDF2WithHmacSHA256,
            kdfIterations = TARGET_ITERATIONS,
            keySize = KEY_SIZE,
        )

        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeByte(VERSION)
                data.writeInt(TARGET_ITERATIONS)
                data.write(salt)
                data.writeByte(iv.size)
                data.write(iv)
                data.write(ciphertext)
            }
            output.toByteArray()
        }
    }

    fun decrypt(envelope: ByteArray, password: String): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(envelope))

        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unsupported credentials envelope version" }

        val iterations = input.readInt()
        require(iterations > 0) { "Invalid KDF iterations" }

        val salt = ByteArray(SALT_SIZE).also(input::readFully)

        val ivLength = input.readUnsignedByte()
        require(ivLength in MIN_IV_SIZE..MAX_IV_SIZE) { "Invalid credentials IV length" }

        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertext = input.readBytes()

        val key = keyGen.derivePasswordKeyEncryptionKey(
            password = password,
            salt = salt,
            kdf = Kdf.PBKDF2WithHmacSHA256,
            kdfIterations = iterations,
            keySize = KEY_SIZE,
        )

        val cipher = Cipher.getInstance(Algorithm.AesGcmNoPadding.value).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }

        return cipher.doFinal(ciphertext)
    }

    /**
     * Reads the KDF iteration count recorded in [envelope] without decrypting it. Used to decide
     * whether an already stored credentials file needs to be upgraded to the current iterations.
     */
    fun iterations(envelope: ByteArray): Int {
        val input = DataInputStream(ByteArrayInputStream(envelope))

        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unsupported credentials envelope version" }

        val iterations = input.readInt()
        require(iterations > 0) { "Invalid KDF iterations" }

        return iterations
    }

    companion object {
        const val VERSION = 1

        // Match the password-derived KEK strength of the vault. Only the KDF params are shared;
        // this envelope still uses AES-GCM rather than the vault's AES-CBC.
        val TARGET_ITERATIONS: Int = KekSpec.Password.kdfIterations

        private const val KEY_SIZE = 256
        private const val TAG_BITS = 128
        private const val MIN_IV_SIZE = 12
        private const val MAX_IV_SIZE = 16
    }
}
