package app.codeg.android.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.codeg.android.core.common.DispatcherProvider
import app.codeg.android.core.datastore.MeshSettings
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.network.CodegClientFactory
import app.codeg.android.core.security.SecretStore
import app.codeg.android.core.common.DefaultDispatcherProvider
import app.codeg.android.feature.server.ServerEditorViewModel
import androidx.lifecycle.SavedStateHandle
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionRevisionTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private class Secrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { values[key] = value }
        override fun get(key: String) = values[key]
        override fun remove(key: String) { values.remove(key) }
    }

    @Test fun `a direct connection can still be tested before it has a display name`() {
        val repository = ServerRepository(ServerStore(Store()), Secrets(),
            CodegClientFactory(Lazy { error("No request is made while editing") }), DefaultDispatcherProvider)
        val editor = ServerEditorViewModel(repository, SavedStateHandle())
        editor.onUrlChange("https://example.test")
        editor.onTokenChange("fixture-token")
        assertTrue(editor.canTest)
        assertFalse(editor.canSave)
        editor.onNameChange("Test server")
        assertTrue(editor.canSave)
        editor.onUseMeshChange(true)
        assertFalse("An incomplete mesh profile must not be testable", editor.canTest)
    }

    @Test fun `unrelated edits preserve selection while token and password edits invalidate it`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }
        val store = Store()
        val secrets = Secrets()
        val factory = CodegClientFactory(Lazy { error("A storage edit must not connect to a server") })
        val repository = ServerRepository(ServerStore(store), secrets, factory, dispatchers)
        val selected = ServerProfile("selected", "Selected", "http://10.44.0.2:3080",
            MeshSettings("test-network", listOf("tcp://peer.example.test:11010")))
        repository.save(selected, "fixture-token", "fixture-password")
        val observed = mutableListOf<ServerProfile?>()
        backgroundScope.launch(dispatcher) { repository.selectedProfile.collect { observed += it } }
        val firstRevision = observed.single()!!.connectionRevision
        assertTrue(firstRevision.isNotBlank())

        repository.save(ServerProfile("other", "Other", "https://other.example.test"), "other-fixture-token")
        assertEquals("Editing another server must not tear down the selected mesh", 1, observed.size)

        repository.save(selected, "updated-fixture-token", "fixture-password")
        assertEquals(2, observed.size)
        assertNotEquals(firstRevision, observed.last()!!.connectionRevision)
        assertEquals("updated-fixture-token", repository.token(selected.id))
        repository.save(selected, "updated-fixture-token", "updated-fixture-password")
        assertEquals(3, observed.size)
        assertEquals("updated-fixture-password", repository.meshPassword(selected.id))

        val restored = ServerRepository(ServerStore(store), secrets, factory, dispatchers)
        assertEquals(observed.last(), restored.selectedProfile.first())
        assertFalse(store.data.value.asMap().values.any { it.toString().contains("fixture-password") })
        repository.delete(selected.id)
        assertEquals("other", repository.selectedProfile.first()?.id)
        assertNull(secrets.values["mesh-password:selected"])
    }
}
