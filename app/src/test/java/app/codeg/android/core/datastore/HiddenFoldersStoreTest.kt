package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderOrdering
import app.codeg.android.core.model.FolderVisibility
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HiddenFoldersStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `choices are isolated by profile and endpoint and can be restored`() = runTest {
        val store = HiddenFoldersStore(MemoryPreferences())
        val profile = ServerProfile("one", "Server", "http://localhost:3080/")
        val scope = store.scope(profile)
        assertEquals(scope, store.scope(profile.copy(name = "Renamed", baseUrl = "http://localhost:3080")))
        val otherProfile = store.scope(profile.copy(id = "two"))
        val otherEndpoint = store.scope(profile.copy(baseUrl = "http://localhost:4000"))
        assertNotEquals(scope, otherProfile)
        assertNotEquals(scope, otherEndpoint)
        store.setHidden(scope, 7, true)
        store.setHidden(scope, 9, true)
        store.setHidden(scope, 7, true)
        assertEquals(setOf(7, 9), store.hiddenIds(scope).first())
        assertEquals(emptySet<Int>(), store.hiddenIds(otherProfile).first())
        assertEquals(emptySet<Int>(), store.hiddenIds(otherEndpoint).first())
        store.setHidden(scope, 7, false)
        assertEquals(setOf(9), store.hiddenIds(scope).first())
    }

    @Test
    fun `hidden folders survive closing and reopening the datastore`() = runTest {
        val file = File(temporaryFolder.root, "hidden.preferences_pb")
        val profile = ServerProfile("one", "Server", "http://localhost:3080")
        val firstJob = SupervisorJob()
        val first = HiddenFoldersStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob)) { file })
        first.setHidden(first.scope(profile), 12, true)
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val second = HiddenFoldersStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob)) { file })
            assertEquals(setOf(12), second.hiddenIds(second.scope(profile)).first())
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test
    fun `management can reorder hidden folders and restore them without losing order`() = runTest {
        val data = MemoryPreferences()
        val hidden = HiddenFoldersStore(data)
        val order = FolderOrderStore(data)
        val folders = (1..3).map { FolderDetail(it, "f$it", "/f$it", sortOrder = it) }
        hidden.setHidden("server-a", 2, true)
        order.reorder("server-a", folders, emptySet(), listOf(2, 3, 1))
        assertEquals(setOf(2), hidden.hiddenIds("server-a").first())
        assertEquals(listOf(2, 3, 1), order.order("server-a").first())
        val visible = folders.filter { it.id !in FolderVisibility.hiddenIds(folders, hidden.hiddenIds("server-a").first()) }
        assertEquals(listOf(3, 1), FolderOrdering.sorted(visible, order.order("server-a").first()).map { it.id })
        hidden.setHidden("server-a", 2, false)
        assertEquals(emptySet<Int>(), hidden.hiddenIds("server-a").first())
        assertEquals(listOf(2, 3, 1), FolderOrdering.sorted(folders, order.order("server-a").first()).map { it.id })
        order.reset("server-a")
        assertEquals(listOf(1, 2, 3), FolderOrdering.sorted(folders, order.order("server-a").first()).map { it.id })
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
