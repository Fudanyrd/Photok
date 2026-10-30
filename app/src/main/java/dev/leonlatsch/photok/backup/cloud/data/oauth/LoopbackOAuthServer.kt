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

import dev.leonlatsch.photok.backup.cloud.domain.CloudError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Minimal one-shot loopback redirect receiver for the OAuth authorization code.
 *
 * Google blocks custom URI schemes for Android OAuth clients, so the consent page redirects
 * to `http://localhost:<port>` and this server hands the code to the waiting coroutine. It
 * only listens on the loopback interface and stops after the first valid redirect.
 *
 * Both IPv4 and IPv6 loopback are bound when possible: `localhost` may resolve to either
 * depending on the browser and the device.
 */
class LoopbackOAuthServer(
    private val host: String = "localhost",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : Closeable {

    private val serverSockets: List<ServerSocket> = createServerSockets()

    val redirectUri: String
        get() = "http://$host:${serverSockets.first().localPort}"

    suspend fun awaitRedirect(): OAuthRedirect = withContext(Dispatchers.IO) {
        serverSockets.forEach { socket -> socket.soTimeout = timeoutMillis.toInt() }

        try {
            supervisorScope {
                val ipv4 = async(Dispatchers.IO) { acceptRedirect(serverSockets.first()) }
                val ipv6 = serverSockets.getOrNull(1)?.let { socket ->
                    async(Dispatchers.IO) { acceptRedirect(socket) }
                }

                try {
                    if (ipv6 == null) {
                        ipv4.await()
                    } else {
                        select<OAuthRedirect> {
                            ipv4.onAwait { it }
                            ipv6.onAwait { it }
                        }
                    }
                } finally {
                    ipv4.cancel()
                    ipv6?.cancel()
                    close()
                }
            }
        } catch (e: SocketTimeoutException) {
            throw CloudError.AuthorizationFailed(e)
        } finally {
            close()
        }
    }

    private fun acceptRedirect(socket: ServerSocket): OAuthRedirect {
        while (true) {
            socket.accept().use { client ->
                client.soTimeout = REQUEST_TIMEOUT_MILLIS

                val requestLine = client.getInputStream()
                    .bufferedReader()
                    .readLine()
                val target = requestLine?.split(' ')?.getOrNull(1)
                val uri = target?.let { runCatching { URI(it) }.getOrNull() }

                if (uri == null || uri.path != "/") {
                    respond(client, notFound = true, body = errorPage())
                } else {
                    val redirect = parseOAuthRedirect(uri.rawQuery)

                    if (redirect.code == null && redirect.error == null) {
                        // A bare / (for example a browser probe) is not the redirect yet.
                        respond(client, notFound = true, body = errorPage())
                    } else {
                        respond(
                            socket = client,
                            notFound = false,
                            body = if (redirect.error == null) successPage() else errorPage(),
                        )
                        return redirect
                    }
                }
            }
        }
    }

    /**
     * Binds IPv4 loopback first, then tries the same port on IPv6 loopback. A device
     * without one of the two still gets a working server on the other.
     */
    private fun createServerSockets(): List<ServerSocket> {
        val sockets = mutableListOf<ServerSocket>()

        val ipv4 = runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }.getOrNull()

        if (ipv4 != null) {
            sockets += ipv4

            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(
                        InetSocketAddress(InetAddress.getByName("::1"), ipv4.localPort),
                        1,
                    )
                }
            }.onSuccess { sockets += it }
        } else {
            runCatching {
                ServerSocket(0, 1, InetAddress.getByName("::1"))
            }.onSuccess { sockets += it }
        }

        check(sockets.isNotEmpty()) { "No loopback address available" }
        return sockets
    }

    private fun respond(socket: Socket, notFound: Boolean, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val status = if (notFound) "404 Not Found" else "200 OK"
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bodyBytes)
            flush()
        }
    }

    private fun successPage() = page(
        title = "Google Drive connected",
        message = "Photok is connected to Google Drive. You can close this tab and return to the app.",
    )

    private fun errorPage() = page(
        title = "Connection cancelled",
        message = "Photok was not connected. You can close this tab and try again in the app.",
    )

    private fun page(title: String, message: String) = """
        <!doctype html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
        </head>
        <body style="font-family: sans-serif; margin: 3rem 1.5rem; color: #1f1f1f;">
            <h1 style="font-size: 1.4rem;">$title</h1>
            <p style="line-height: 1.5;">$message</p>
        </body>
        </html>
    """.trimIndent()

    override fun close() {
        serverSockets.forEach { socket -> runCatching { socket.close() } }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 10 * 60_000L
        private const val REQUEST_TIMEOUT_MILLIS = 5_000
    }
}
