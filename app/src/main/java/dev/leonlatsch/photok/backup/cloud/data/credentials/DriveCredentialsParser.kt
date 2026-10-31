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

package dev.leonlatsch.photok.backup.cloud.data.credentials

import com.google.gson.JsonParser
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import dev.leonlatsch.photok.backup.cloud.domain.DriveClientCredentials
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses the OAuth client JSON downloaded from Google Cloud. The Desktop app format wraps
 * the values in an `installed` object; `web` is accepted as well for convenience.
 */
@Singleton
class DriveCredentialsParser @Inject constructor() {

    fun parse(raw: String): Result<DriveClientCredentials> = try {
        val root = JsonParser.parseString(raw).asJsonObject
        val client = root.getAsJsonObject("installed")
            ?: root.getAsJsonObject("web")
            ?: root

        val clientId = client.get("client_id")?.asString?.trim().orEmpty()
        val clientSecret = client.get("client_secret")?.asString?.trim().orEmpty()

        if (clientId.isBlank() || clientSecret.isBlank()) {
            Result.failure(CloudError.InvalidCredentialsJson())
        } else {
            Result.success(DriveClientCredentials(clientId, clientSecret))
        }
    } catch (e: Exception) {
        Result.failure(CloudError.InvalidCredentialsJson())
    }
}
