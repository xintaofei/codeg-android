package app.codeg.android.feature.sessions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionListCollapseStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private suspend fun withStore(file: File, block: suspend (SessionListCollapseStore) -> Unit) {
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file },
        )
        try {
            block(SessionListCollapseStore(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a fresh app launch restores compact mode and the original mixed layout from disk`() = runBlocking {
        val file = File(temporaryFolder.root, "layout.preferences_pb")
        val normal = SessionListCollapseState().toggleSection("chats").toggleSection("folder-1")
        val compact = normal.toggleCompact().toggleSection("recent")
        withStore(file) { store ->
            store.update("a") { compact }
        }
        // A new DataStore and store instance have no in-memory UI state to reuse.
        withStore(file) { store ->
            val restored = store.state("a").first()
            assertEquals(compact, restored)
            assertTrue(restored.compact)
            assertFalse(restored.isCollapsed("recent"))
            store.update("a") { it.toggleCompact() }
            assertEquals(normal, store.state("a").first())
        }
        withStore(file) { store ->
            assertEquals(normal, store.state("a").first())
        }
    }

    @Test
    fun `servers keep independent layouts and a new server starts with the default`() = runBlocking {
        withStore(File(temporaryFolder.root, "layout.preferences_pb")) { store ->
            assertEquals(SessionListCollapseState(), store.state("a").first())
            store.update("a") { it.toggleSection("chats").toggleCompact() }
            store.update("b") { it.toggleSection("recent") }
            assertTrue(store.state("a").first().compact)
            assertTrue(store.state("a").first().collapsed.contains("chats"))
            assertFalse(store.state("b").first().compact)
            assertEquals(setOf("recent"), store.state("b").first().collapsed)
            assertEquals(SessionListCollapseState(), store.state("c").first())
        }
    }

    @Test
    fun `rapid toggles update the persisted state atomically`() = runBlocking {
        withStore(File(temporaryFolder.root, "layout.preferences_pb")) { store ->
            store.update("a") { it.toggleSection("chats") }
            coroutineScope {
                repeat(21) { launch { store.update("a") { it.toggleCompact() } } }
            }
            val saved = store.state("a").first()
            assertTrue(saved.compact)
            assertEquals(setOf("chats"), saved.collapsed)
            assertTrue(saved.expandedInCompact.isEmpty())
        }
    }
}
