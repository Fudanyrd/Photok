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
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
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
 */
class LoopbackOAuthServer(
    private val host: String = "localhost",
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : Closeable {

    private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String
        get() = "http://$host:${serverSocket.localPort}"

    suspend fun awaitRedirect(): OAuthRedirect = withContext(Dispatchers.IO) {
        serverSocket.soTimeout = timeoutMillis.toInt()

        try {
            while (true) {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = REQUEST_TIMEOUT_MILLIS

                    val requestLine = socket.getInputStream()
                        .bufferedReader()
                        .readLine()
                    val target = requestLine?.split(' ')?.getOrNull(1)
                    val uri = target?.let { runCatching { URI(it) }.getOrNull() }

                    if (uri == null || uri.path != "/") {
                        respond(socket, notFound = true, body = errorPage())
                    } else {
                        val redirect = parseOAuthRedirect(uri.rawQuery)
                        respond(
                            socket = socket,
                            notFound = false,
                            body = if (redirect.error == null) successPage() else errorPage(),
                        )
                        return@withContext redirect
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: SocketTimeoutException) {
            throw CloudError.AuthorizationFailed(e)
        } finally {
            close()
        }
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
        runCatching { serverSocket.close() }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 5 * 60_000L
        private const val REQUEST_TIMEOUT_MILLIS = 5_000
    }
}
