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

import dev.leonlatsch.photok.backup.cloud.data.credentials.DriveCredentialsParser
import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCredentialsParserTest {

    private val parser = DriveCredentialsParser()

    @Test
    fun `parses a desktop client in the installed envelope`() {
        val credentials = parser.parse(
            """
            {
              "installed": {
                "client_id": "client-id",
                "client_secret": "client-secret",
                "redirect_uris": ["http://localhost"]
              }
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("client-id", credentials.clientId)
        assertEquals("client-secret", credentials.clientSecret)
    }

    @Test
    fun `parses a web client and ignores extra fields`() {
        val credentials = parser.parse(
            """
            {
              "web": {
                "project_id": "project",
                "client_id": "web-id",
                "client_secret": "web-secret"
              }
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("web-id", credentials.clientId)
        assertEquals("web-secret", credentials.clientSecret)
    }

    @Test
    fun `rejects malformed json without echoing the secret`() {
        val result = parser.parse("""{"installed":{"client_id":"id","client_secret":""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CloudError.InvalidCredentialsJson)
        assertFalse(result.exceptionOrNull().toString().contains("client-secret"))
    }

    @Test
    fun `rejects missing client fields`() {
        val result = parser.parse("""{"installed":{"client_id":"only-id"}}""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CloudError.InvalidCredentialsJson)
    }

    @Test
    fun `credentials string hides the secret`() {
        val credentials = parser.parse(
            """{"installed":{"client_id":"id","client_secret":"super-secret"}}"""
        ).getOrThrow()

        assertFalse(credentials.toString().contains("super-secret"))
    }
}
