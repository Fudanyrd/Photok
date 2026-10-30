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

package dev.leonlatsch.photok.backup.cloud.data.auth

import com.google.gson.Gson
import dev.leonlatsch.photok.backup.cloud.domain.CloudSession
import dev.leonlatsch.photok.settings.data.Config
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

interface CloudTokenStore {
    fun load(): CloudSession?

    fun save(session: CloudSession)

    /**
     * Atomically changes the stored session, for example to cache the Drive folder id
     * without losing a token that was refreshed at the same time.
     */
    fun update(transform: (CloudSession) -> CloudSession): CloudSession?

    fun clear()
}

@Singleton
class KeystoreCloudTokenStore @Inject constructor(
    private val config: Config,
    private val tokenCipher: TokenCipher,
    private val gson: Gson,
) : CloudTokenStore {

    private val lock = Any()

    override fun load(): CloudSession? = synchronized(lock) {
        readLocked()
    }

    override fun save(session: CloudSession) = synchronized(lock) {
        writeLocked(session)
    }

    override fun update(transform: (CloudSession) -> CloudSession): CloudSession? =
        synchronized(lock) {
            val current = readLocked() ?: return null
            val updated = transform(current)
            writeLocked(updated)
            updated
        }

    override fun clear() = synchronized(lock) {
        config.putString(KEY_SESSION, null)
    }

    private fun readLocked(): CloudSession? {
        val stored = config.getString(KEY_SESSION, null) ?: return null

        return try {
            val plain = tokenCipher.decrypt(Base64.getDecoder().decode(stored))
            gson.fromJson(String(plain, Charsets.UTF_8), CloudSession::class.java)
        } catch (e: Exception) {
            // A key can be invalidated by a lock screen change. An old session is useless then.
            Timber.e(e, "Could not read the cloud session, clearing it")
            config.putString(KEY_SESSION, null)
            null
        }
    }

    private fun writeLocked(session: CloudSession) {
        val plain = gson.toJson(session).toByteArray(Charsets.UTF_8)
        val encrypted = tokenCipher.encrypt(plain)
        config.putString(KEY_SESSION, Base64.getEncoder().encodeToString(encrypted))
    }

    private companion object {
        const val KEY_SESSION = "cloud^oauthSession"
    }
}
