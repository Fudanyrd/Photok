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
 * Typed failures of the cloud backup, so the UI can show a useful message instead of a
 * generic error. Network and authorization failures are retryable; quota and space
 * failures need user action.
 */
sealed class CloudError(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {

    class NotConfigured : CloudError("Google Drive is not configured")

    class NotConnected : CloudError("Not connected to Google Drive")

    class AuthorizationFailed(cause: Throwable? = null) :
        CloudError("Google Drive authorization failed", cause)

    class QuotaExceeded(cause: Throwable? = null) :
        CloudError("Google Drive storage quota exceeded", cause)

    class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) :
        CloudError("Not enough free space")

    data class Remote(
        val code: Int,
        val reason: String?,
        override val cause: Throwable? = null,
    ) : CloudError("Google Drive request failed with $code", cause)

    class Network(cause: Throwable) : CloudError("Network error", cause)

    class Cancelled : CloudError("Cancelled")

    data class Unknown(override val cause: Throwable) : CloudError("Cloud backup failed", cause)
}
