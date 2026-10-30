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

package dev.leonlatsch.photok.backup.cloud.data.drive

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DriveJsonTest {

    @Test
    fun `drive file becomes a remote backup`() {
        val remote = DriveFile(
            id = "file-id",
            name = "photok_backup_2026-10-30_12-00-00.zip",
            size = "12345",
            createdTime = "2026-10-30T12:00:00.000Z",
        ).toRemoteBackup()

        assertNotNull(remote)
        assertEquals("file-id", remote!!.id)
        assertEquals(12_345L, remote.sizeBytes)
        assertEquals(1_793_361_600_000L, remote.createdAt)
    }

    @Test
    fun `files without an id or name are ignored`() {
        assertNull(DriveFile(id = null, name = "a.zip").toRemoteBackup())
        assertNull(DriveFile(id = "1", name = null).toRemoteBackup())
    }

    @Test
    fun `unparseable dates and sizes do not break the list`() {
        val remote = DriveFile(
            id = "1",
            name = "a.zip",
            size = "not-a-number",
            createdTime = "yesterday",
        ).toRemoteBackup()

        assertNotNull(remote)
        assertEquals(0L, remote!!.sizeBytes)
        assertNull(remote.createdAt)
    }

    @Test
    fun `list response fields are mapped`() {
        val json = """
            {
              "nextPageToken": "next",
              "files": [
                {"id": "1", "name": "a.zip", "size": "10", "createdTime": "2026-10-30T12:00:00.000Z"}
              ]
            }
        """.trimIndent()

        val list = Gson().fromJson(json, DriveFileList::class.java)

        assertEquals("next", list.nextPageToken)
        assertEquals("1", list.files!!.single().id)
        assertEquals("10", list.files!!.single().size)
    }
}
