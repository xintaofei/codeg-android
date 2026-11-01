package app.codeg.android.feature.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import app.codeg.android.core.common.DispatcherProvider
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.network.CodegClientFactory
import app.codeg.android.core.security.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `global search includes history beyond the home preview without folder or status filters`() = runTest {
        val harness = harness { summaries((1..45).toList()) }
        try {
            harness.viewModel.onQueryChange("needle")
            advanceUntilIdle()

            assertEquals((1..45).toList(), harness.viewModel.ui.value.results.map { it.id })
            val body = (harness.requests.single().body as TextContent).text
            assertTrue(body.contains("\"search\":\"needle\""))
            assertFalse(body.contains("folderIds"))
            assertFalse(body.contains("status"))
            assertFalse(body.contains("limit"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `changing servers reruns the same query against the new server`() = runTest {
        val harness = harness { request -> summaries(listOf(if (request.url.host == "a.test") 1 else 2)) }
        try {
            harness.viewModel.onQueryChange("needle")
            advanceUntilIdle()
            assertEquals(listOf(1), harness.viewModel.ui.value.results.map { it.id })

            harness.serverStore.select("b")
            advanceUntilIdle()

            assertEquals(listOf(2), harness.viewModel.ui.value.results.map { it.id })
            assertEquals(listOf("a.test", "b.test"), harness.requests.map { it.url.host })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `clearing a query immediately rejects its late response during debounce`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val harness = harness {
            started.complete(Unit)
            response.await()
        }
        try {
            harness.viewModel.onQueryChange("needle")
            advanceTimeBy(301)
            runCurrent()
            assertTrue(started.isCompleted)

            harness.viewModel.onQueryChange("")
            response.complete(summaries(listOf(1)))
            runCurrent()

            assertTrue(harness.viewModel.ui.value.results.isEmpty())
            assertEquals("", harness.viewModel.ui.value.query)
            assertFalse(harness.viewModel.ui.value.searching)
            advanceUntilIdle()
        } finally {
            response.complete("[]")
            harness.close()
        }
    }

    private suspend fun TestScope.harness(
        searchResponse: suspend (HttpRequestData) -> String,
    ): Harness {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = this@SearchViewModelTest.dispatcher
            addHandler { request ->
                val body = if (request.url.encodedPath.endsWith("list_all_conversations")) {
                    requests += request
                    searchResponse(request)
                } else "[]"
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        val store = ServerStore(MemoryPreferences())
        store.upsert(ServerProfile("a", "A", "https://a.test"))
        store.upsert(ServerProfile("b", "B", "https://b.test"))
        val dispatchers = object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }
        val secrets = object : SecretStore {
            override fun get(key: String) = "test-token"
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        }
        val cache = app.codeg.android.core.cache.ConversationDiskCache(
            java.nio.file.Files.createTempDirectory("search-cache-test").toFile().apply { deleteOnExit() },
        )
        val repository = ServerRepository(store, secrets, CodegClientFactory(dagger.Lazy { http }, cache), dispatchers, cache)
        val viewModel = SearchViewModel(repository, RecentSearchesStore(MemoryPreferences()))
        val viewModels = ViewModelStore().apply { put("search", viewModel) }
        advanceUntilIdle()
        return Harness(viewModel, store, requests, viewModels, http)
    }

    private data class Harness(
        val viewModel: SearchViewModel,
        val serverStore: ServerStore,
        val requests: List<HttpRequestData>,
        val viewModels: ViewModelStore,
        val http: HttpClient,
    ) {
        fun close() {
            viewModels.clear()
            http.close()
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private fun summaries(ids: List<Int>) = ids.joinToString(",", "[", "]") { id ->
        """{"id":$id,"folder_id":$id,"kind":"chat","title":"needle $id",
            "agent_type":"codex","status":"completed","created_at":"2024-01-02T03:04:05Z",
            "updated_at":"2024-01-02T03:04:06Z"}"""
    }
}
