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

package dev.leonlatsch.photok.backup.cloud

import dev.leonlatsch.photok.backup.cloud.domain.RemoteBackup
import dev.leonlatsch.photok.backup.cloud.domain.archivesToPrune
import dev.leonlatsch.photok.backup.cloud.domain.cloudBackupFileName
import dev.leonlatsch.photok.backup.cloud.domain.isCloudBackupFile
import dev.leonlatsch.photok.backup.cloud.domain.newestFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CloudFilesTest {

    @Test
    fun `file names sort by date and keep the zip extension`() {
        val name = cloudBackupFileName(
            instant = Instant.parse("2026-10-30T12:34:56Z"),
            zone = ZoneId.of("UTC"),
        )

        assertEquals("photok_backup_2026-10-30_12-34-56.zip", name)
        assertTrue(isCloudBackupFile(name))
        assertFalse(isCloudBackupFile("holiday.zip"))
        assertFalse(isCloudBackupFile("photok_backup_2026-10-30_12-34-56.txt"))
    }

    @Test
    fun `newest first puts missing dates last`() {
        val backups = listOf(
            backup("old", 1_000L),
            backup("new", 3_000L),
            backup("unknown", null),
            backup("middle", 2_000L),
        )

        assertEquals(
            listOf("new", "middle", "old", "unknown"),
            backups.newestFirst().map { it.id },
        )
    }

    @Test
    fun `pruning keeps the requested number of newest archives`() {
        val backups = listOf(
            backup("one", 1_000L),
            backup("two", 2_000L),
            backup("three", 3_000L),
            backup("four", 4_000L),
        )

        assertEquals(
            listOf("two", "one"),
            backups.archivesToPrune(keep = 2).map { it.id },
        )
        assertTrue(backups.archivesToPrune(keep = 4).isEmpty())
        assertTrue(backups.archivesToPrune(keep = 0).size == 4)
    }

    private fun backup(id: String, createdAt: Long?) = RemoteBackup(
        id = id,
        name = "photok_backup_$id.zip",
        sizeBytes = 1L,
        createdAt = createdAt,
    )
}
