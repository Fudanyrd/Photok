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

package dev.leonlatsch.photok.backup.cloud.data.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class Pkce(
    val verifier: String,
    val challenge: String,
)

/**
 * RFC 7636 S256 code verifier and challenge. The verifier is 64 random bytes, which is
 * inside the 43–128 character range required by the spec.
 */
fun createPkce(random: SecureRandom = SecureRandom()): Pkce {
    val verifierBytes = ByteArray(64)
    random.nextBytes(verifierBytes)

    val encoder = Base64.getUrlEncoder().withoutPadding()
    val verifier = encoder.encodeToString(verifierBytes)
    val challenge = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )

    return Pkce(verifier = verifier, challenge = challenge)
}

fun createOAuthState(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
