package app.codeg.android.feature.downloads

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.codeg.android.core.datastore.ServerProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val profile = ServerProfile("server1", "Local", "http://localhost:3080")
    private fun record(key: String) = DownloadRecord(key, "/Downloads/app.apk", "app.apk", profile.id, profile.baseUrl, profile.name,
        conversationId = 12, createdAt = 1, id = 42, status = FileDownloadStatus.COMPLETE)

    @Test fun `metadata survives process restart updates atomically and removal retains other records`() = runTest {
        val file = File(temporaryFolder.root, "downloads.preferences_pb")
        val firstJob = SupervisorJob()
        val first = DownloadStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob)) { file })
        first.put(record("one"))
        first.put(record("two").copy(createdAt = 2))
        first.put(record("one").copy(filename = "updated.apk"))
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val second = DownloadStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob)) { file })
            assertEquals(listOf("two", "one"), second.read().map { it.key })
            assertEquals("updated.apk", second.read().last().filename)
            assertEquals(42L, second.read().last().id)
            second.remove("one")
            assertEquals(listOf("two"), second.read().map { it.key })
        } finally { secondJob.cancelAndJoin() }
    }

    @Test fun `source identity and duplicate lookup cannot reuse files from a different server or conversation`() {
        val records = listOf(record("one"))
        assertTrue(records.first().belongsTo(profile.copy(name = "Renamed", baseUrl = profile.baseUrl + "/")))
        assertFalse(records.first().belongsTo(profile.copy(id = "another")))
        assertFalse(records.first().belongsTo(profile.copy(baseUrl = "http://localhost:4000")))
        val active = records.map { it.copy(status = FileDownloadStatus.DOWNLOADING) }
        assertEquals("one", active.activeDownload(profile, 12, "/Downloads/app.apk")?.key)
        assertNull(active.activeDownload(profile, 13, "/Downloads/app.apk"))
        assertNull(active.activeDownload(profile.copy(id = "another"), 12, "/Downloads/app.apk"))
        assertNull(records.activeDownload(profile, 12, "/Downloads/app.apk")) // A repeated artifact link must fetch the latest build.
        assertNull(listOf(record("failed").copy(status = FileDownloadStatus.FAILED)).activeDownload(profile, 12, "/Downloads/app.apk"))
    }

    @Test fun `restart only interrupts preparation that has no system download id`() {
        assertEquals(FileDownloadStatus.FAILED, record("one").copy(id = null, status = FileDownloadStatus.PREPARING).restored("Interrupted").status)
        val active = record("one").copy(status = FileDownloadStatus.DOWNLOADING)
        assertEquals(active, active.restored("Interrupted"))
        val canceled = active.copy(id = null, status = FileDownloadStatus.CANCELED)
        assertEquals(canceled, canceled.restored("Interrupted"))
    }
}
