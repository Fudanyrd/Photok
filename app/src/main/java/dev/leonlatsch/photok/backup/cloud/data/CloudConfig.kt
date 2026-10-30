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

package dev.leonlatsch.photok.backup.cloud.data

import dev.leonlatsch.photok.BuildConfig

/**
 * OAuth client configuration for the Google Drive backup.
 *
 * The values come from the build (Gradle property, environment, or the untracked `.env`
 * file) and are never committed. Without them the feature reports itself as not configured
 * and makes no network calls.
 */
data class CloudConfig(
    val clientId: String = BuildConfig.DRIVE_CLIENT_ID,
    val clientSecret: String = BuildConfig.DRIVE_CLIENT_SECRET,
    val redirectHost: String = BuildConfig.DRIVE_REDIRECT_HOST,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString(): String =
        "CloudConfig(clientId=$clientId, clientSecret=***, redirectHost=$redirectHost)"
}
