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

package dev.leonlatsch.photok.backup.cloud.oauth

import dev.leonlatsch.photok.backup.cloud.data.oauth.LoopbackOAuthServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Socket
import java.net.URI

class LoopbackOAuthServerTest {

    @Test
    fun `receives code and state from the redirect`() = runBlocking {
        val server = LoopbackOAuthServer()
        val port = URI(server.redirectUri).port
        val redirect = async(Dispatchers.IO) { server.awaitRedirect() }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.getOutputStream().write(
                "GET /?code=abc&state=xyz HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }

        val result = withTimeout(TIMEOUT_MILLIS.toLong()) { redirect.await() }

        assertEquals("abc", result.code)
        assertEquals("xyz", result.state)
        assertNull(result.error)
    }

    @Test
    fun `reports a denied authorization`() = runBlocking {
        val server = LoopbackOAuthServer()
        val port = URI(server.redirectUri).port
        val redirect = async(Dispatchers.IO) { server.awaitRedirect() }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.getOutputStream().write(
                "GET /?error=access_denied&state=xyz HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }

        val result = withTimeout(TIMEOUT_MILLIS.toLong()) { redirect.await() }

        assertNull(result.code)
        assertEquals("access_denied", result.error)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
    }
}
