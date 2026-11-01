package app.codeg.android.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.codeg.android.core.model.FolderDetail
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderOrderStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val folders = (1..3).map { FolderDetail(it, "f$it", "/f$it", sortOrder = it) }

    private suspend fun withStore(file: File, block: suspend (FolderOrderStore) -> Unit) {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        try {
            block(FolderOrderStore(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `manual order survives closing and reopening the datastore`() = runBlocking {
        val file = File(temporaryFolder.root, "order.preferences_pb")
        withStore(file) { store ->
            store.move("server-a", folders, emptySet(), 3, -1)
            store.move("server-a", folders, emptySet(), 3, -1)
        }
        withStore(file) { store ->
            assertEquals(listOf(3, 1, 2), store.order("server-a").first())
        }
    }

    @Test
    fun `dropped order survives reopening and reset restores server order`() = runBlocking {
        val file = File(temporaryFolder.root, "drag.preferences_pb")
        withStore(file) { store ->
            store.reorder("server-a", folders, emptySet(), listOf(3, 1, 2))
        }
        withStore(file) { store ->
            assertEquals(listOf(3, 1, 2), store.order("server-a").first())
            store.reset("server-a")
            assertEquals(listOf(1, 2, 3), app.codeg.android.core.model.FolderOrdering.sorted(folders, store.order("server-a").first()).map { it.id })
        }
    }

    @Test
    fun `independent scopes can reset their order without changing the other server`() = runBlocking {
        withStore(File(temporaryFolder.root, "order.preferences_pb")) { store ->
            assertEquals(emptyList<Int>(), store.order("server-a").first())
            store.move("server-a", folders, emptySet(), 3, -1)
            store.move("server-b", folders, emptySet(), 1, 1)
            store.reset("server-a")
            assertEquals(emptyList<Int>(), store.order("server-a").first())
            assertEquals(listOf(2, 1, 3), store.order("server-b").first())
        }
    }

    @Test
    fun `rapid moves use the latest saved order rather than an old screen snapshot`() = runBlocking {
        withStore(File(temporaryFolder.root, "order.preferences_pb")) { store ->
            coroutineScope {
                repeat(2) { launch { store.move("server-a", folders, emptySet(), 3, -1) } }
            }
            assertEquals(listOf(3, 1, 2), store.order("server-a").first())
        }
    }
}
