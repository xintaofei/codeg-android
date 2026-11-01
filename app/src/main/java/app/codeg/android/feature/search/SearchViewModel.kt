package app.codeg.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import app.codeg.android.feature.sessions.SessionGrouping
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Global conversation search: debounced server query + persisted recent terms. Port of iOS `SearchView`. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val recentStore: RecentSearchesStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    val recent: StateFlow<List<String>> =
        recentStore.searches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val client = MutableStateFlow<CodegClient?>(null)
    private val retries = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            repository.selectedProfile.distinctUntilChanged().collectLatest { profile ->
                client.value = null
                _ui.value = SearchUiState(query = _query.value.trim())
                val active = profile?.let { repository.client(it) }
                client.value = active
                if (active != null) {
                    val folders = try {
                        active.listFolders()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (client.value === active) {
                        _ui.update { it.copy(folderNames = SessionGrouping.folderNames(folders)) }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(_query.debounce(300), client, retries) { q, active, _ -> q to active }
                .collectLatest { (q, active) -> runSearch(q, active) }
        }
    }

    fun onQueryChange(value: String) {
        if (_query.value == value) return
        _query.value = value
        _ui.update {
            it.copy(query = value.trim(), results = emptyList(), searching = value.isNotBlank() && client.value != null, error = null)
        }
    }

    fun submit() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { recentStore.add(q) }
    }

    fun useRecent(term: String) = onQueryChange(term)

    fun clearRecent() { viewModelScope.launch { recentStore.clear() } }

    /** Re-run the current query after a failure (drives the inline-error Retry button). */
    fun retry() { retries.value += 1 }

    private suspend fun runSearch(raw: String, active: CodegClient?) {
        val q = raw.trim()
        if (q != _query.value.trim()) return
        if (q.isEmpty() || active == null) {
            _ui.update { it.copy(results = emptyList(), searching = false, query = q, error = null) }
            return
        }
        _ui.update { it.copy(searching = true, query = q, error = null) }
        try {
            // Global server search is independent of the home list's folders, folds and row limits.
            val results = active.listConversations(search = q, sortBy = "updated")
            if (client.value !== active || _query.value.trim() != q) return
            _ui.update { it.copy(results = results, searching = false) }
            recentStore.add(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client.value !== active || _query.value.trim() != q) return
            _ui.update { it.copy(searching = false, error = e.displayMessage()) }
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationSummary> = emptyList(),
    val folderNames: Map<Int, String> = emptyMap(),
    val searching: Boolean = false,
    val error: String? = null,
)
