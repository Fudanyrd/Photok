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

import kotlinx.coroutines.flow.StateFlow

/**
 * Opens the Google consent page. The UI supplies it so the domain layer stays free of
 * Activity and Custom Tabs types.
 */
fun interface OAuthBrowser {
    fun open(url: String)
}

interface CloudAuth {

    val configured: Boolean

    val connection: StateFlow<CloudConnection>

    /**
     * Runs the loopback OAuth flow and stores the resulting session.
     */
    suspend fun connect(browser: OAuthBrowser): Result<CloudConnection>

    suspend fun disconnect()

    /**
     * Returns a valid access token, refreshing it when [forceRefresh] is set or it is
     * about to expire.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): Result<String>
}
