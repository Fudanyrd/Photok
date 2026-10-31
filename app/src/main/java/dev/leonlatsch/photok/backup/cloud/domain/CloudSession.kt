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
 * Persisted OAuth session. Stored encrypted, see the token store.
 *
 * [folderId] is the Drive id of the app's `Photok` folder, cached to avoid a lookup on
 * every operation.
 */
data class CloudSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val email: String?,
    val folderId: String? = null,
    val clientId: String? = null,
) {
    fun isAccessTokenUsable(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && expiresAt > now + TOKEN_EXPIRY_MARGIN_MILLIS

    companion object {
        const val TOKEN_EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
