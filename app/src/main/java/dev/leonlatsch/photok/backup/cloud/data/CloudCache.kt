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

import android.content.Context
import java.io.File

object CloudCache {

    private const val DIRECTORY = "cloud"
    private const val STALE_MILLIS = 24 * 60 * 60 * 1000L

    fun file(context: Context, name: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        return File(directory, name)
    }

    /**
     * Removes downloads and archives left behind by a killed process. The cache dir itself
     * may be cleared by the OS at any time; this only keeps it tidy.
     */
    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { file ->
            if (now - file.lastModified() > STALE_MILLIS) {
                file.delete()
            }
        }
    }
}
