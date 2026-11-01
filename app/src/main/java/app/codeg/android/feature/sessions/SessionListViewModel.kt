package app.codeg.android.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.HiddenFoldersStore
import app.codeg.android.core.datastore.FolderOrderStore
import app.codeg.android.core.datastore.RecentSessionFilter
import app.codeg.android.core.datastore.SessionListPreferences
import app.codeg.android.core.datastore.SessionListPreferencesStore
import app.codeg.android.core.datastore.SessionSortMode
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderOrdering
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the Chats (session list) screen: loads folders + conversations for the
 * selected server and keeps the full list in memory so grouping / pin toggles
 * stay instant. Faithful port of the iOS `SessionListViewModel` — the grouped
 * display (Pinned / Chats / per-project / Other / Recent) is derived by [SessionGrouping].
 *
 * Rebinds automatically when the selected server changes (or is edited in place
 * to a new endpoint): a monotonic [fetchGeneration] guards against a slow fetch
 * clobbering a newer one.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
    private val preferencesStore: SessionListPreferencesStore,
    private val collapseStore: SessionListCollapseStore,
    private val hiddenFoldersStore: HiddenFoldersStore,
    private val folderOrderStore: FolderOrderStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionListUiState())
    val ui: StateFlow<SessionListUiState> = _ui.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val collapseLayout: StateFlow<ServerSessionListLayout?> = repository.selectedProfile
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { serverId ->
            if (serverId == null) flowOf(null)
            else collapseStore.state(serverId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(error = e.displayMessage()) }
                    emit(SessionListCollapseState())
                }
                .map { ServerSessionListLayout(serverId, it) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The grouped, fully-sorted sections the Chats list renders (Pinned / Chats / per-project
     * / Other / Recent). Derived from [_ui] but recomputed ONLY when the source data or preferences
     * actually change — a refresh-spinner or error toggle keeps the same list
     * references, so [distinctUntilChanged] short-circuits and nothing re-sorts. The
     * O(n·log n) grouping runs on [Dispatchers.Default], never on the main thread or
     * during composition, which is what keeps a very large session list smooth.
     */
    val sections: StateFlow<List<SessionSection>> =
        _ui
            .map { GroupingInput(it.folders, it.conversations, it.openFolderIds, it.preferences, it.hiddenFolderIds, it.folderOrder) }
            .distinctUntilChanged()
            .map { buildSessionSections(it.folders, it.conversations, it.preferences, it.openFolderIds, it.hiddenFolderIds, it.folderOrder) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The only [_ui] fields the grouping depends on, so spinner/error toggles don't re-sort. */
    private data class GroupingInput(
        val folders: List<FolderDetail>,
        val conversations: List<ConversationSummary>,
        val openFolderIds: Set<Int>,
        val preferences: SessionListPreferences,
        val hiddenFolderIds: Set<Int>,
        val folderOrder: List<Int>,
    )

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null
    private var fetchGeneration = 0

    init {
        viewModelScope.launch {
            preferencesStore.preferences.collect { preferences ->
                _ui.update { it.copy(preferences = preferences) }
            }
        }
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile -> onProfile(profile) }
        }
        // Refetch when a conversation is renamed/pinned/status-changed/deleted elsewhere.
        viewModelScope.launch {
            repository.conversationsChanged.collect { if (client != null && !_ui.value.isBusy) fetch(isInitial = false) }
        }
        viewModelScope.launch {
            repository.foldersChanged.collect { if (client != null && !_ui.value.isBusy) fetch(isInitial = false) }
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        fetchGeneration++
        if (profile == null) {
            client = null
            loadedEndpoint = null
            _ui.update { SessionListUiState(preferences = it.preferences) }
            return
        }
        val resolved = repository.client(profile)
        val endpoint = resolved?.cacheIdentity ?: "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        client = resolved
        if (changed) _ui.update { SessionListUiState(preferences = it.preferences, isLoading = true) }
        if (resolved == null) {
            _ui.update { SessionListUiState(preferences = it.preferences, error = appContext.getString(R.string.sessions_missing_token)) }
            return
        }
        val scope = hiddenFoldersStore.scope(profile)
        val hiddenIds = hiddenFoldersStore.hiddenIds(scope).first()
        val folderOrder = folderOrderStore.order(scope).first()
        _ui.update { it.copy(hiddenScope = scope, hiddenFolderIds = hiddenIds, folderOrder = folderOrder) }
        coroutineScope {
            launch {
                folderOrderStore.order(scope).collect { order ->
                    _ui.update { it.copy(folderOrder = order) }
                }
            }
            launch {
                hiddenFoldersStore.hiddenIds(scope).collect { ids ->
                    _ui.update { it.copy(hiddenFolderIds = ids) }
                }
            }
            fetch(isInitial = true, force = changed)
        }
    }

    /** Pull-to-refresh / toolbar refresh — keeps the current list on screen. */
    fun refresh() {
        if (_ui.value.isBusy) return
        viewModelScope.launch { fetch(isInitial = false) }
    }

    private suspend fun fetch(isInitial: Boolean, force: Boolean = false) {
        val active = client ?: return
        fetchGeneration += 1
        val token = fetchGeneration
        if (force) {
            _ui.update { it.copy(folders = emptyList(), conversations = emptyList(), openFolderIds = emptySet(), hasLoaded = false, lastUpdatedAt = null, showingCached = false) }
        }
        _ui.update {
            if (isInitial) it.copy(isLoading = true, isRefreshing = false, error = null)
            else it.copy(isRefreshing = true, error = null)
        }
        try {
            if (isInitial) {
                val cached = active.cachedSessionList()
                if (token != fetchGeneration) return
                if (cached != null) {
                    _ui.update {
                        it.copy(
                            folders = cached.value.folders,
                            conversations = cached.value.conversations,
                            openFolderIds = cached.value.openFolderIds,
                            hasLoaded = true, isLoading = false,
                            lastUpdatedAt = cached.savedAt, showingCached = true,
                        )
                    }
                    if (cached.isFresh() && cached.value.conversations.none { it.status.isLive }) return
                    _ui.update { it.copy(isRefreshing = true) }
                }
            }
            val result = active.sessionList()
            if (token != fetchGeneration) return
            _ui.update {
                it.copy(
                    folders = result.folders,
                    conversations = result.conversations,
                    openFolderIds = result.openFolderIds,
                    hasLoaded = true,
                    isLoading = false,
                    isRefreshing = false,
                    lastUpdatedAt = System.currentTimeMillis(),
                    showingCached = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token != fetchGeneration) return
            _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.displayMessage()) }
        }
    }

    /**
     * Optimistically pin/unpin, then persist. On failure the local change reverts
     * and an error is surfaced. The optimistic `pinnedAt` is a local stand-in for
     * ordering; the next refresh replaces it with the server's value.
     */
    fun setPinned(conversation: ConversationSummary, pinned: Boolean) {
        val active = client ?: return
        val generation = fetchGeneration
        val previous = _ui.value.conversations
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversation.id) it.copy(pinnedAt = if (pinned) Instant.now() else null) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                active.setPinned(conversation.id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == fetchGeneration && client === active) {
                    _ui.update { it.copy(conversations = previous, error = e.displayMessage()) }
                }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun setFolderHidden(folderId: Int, hidden: Boolean) {
        val scope = _ui.value.hiddenScope ?: return
        savePreference { hiddenFoldersStore.setHidden(scope, folderId, hidden) }
    }

    fun moveFolder(folderId: Int, offset: Int) {
        val state = _ui.value
        val scope = state.hiddenScope ?: return
        saveFolderOrder(scope) {
            folderOrderStore.move(scope, state.folders, emptySet(), folderId, offset)
        }
    }

    fun resetFolderOrder() {
        val scope = _ui.value.hiddenScope ?: return
        saveFolderOrder(scope) {
            folderOrderStore.reset(scope)
            if (_ui.value.hiddenScope == scope) repository.notifyFoldersChanged()
        }
    }

    fun reorderFolders(visibleOrder: List<Int>) {
        val state = _ui.value
        val scope = state.hiddenScope ?: return
        saveFolderOrder(scope) {
            folderOrderStore.reorder(scope, state.folders, emptySet(), visibleOrder)
        }
    }

    private fun saveFolderOrder(scope: String, save: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.hiddenScope == scope) _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    internal fun toggleCompact(serverId: String) = savePreference {
        collapseStore.update(serverId) { it.toggleCompact() }
    }

    internal fun toggleSection(serverId: String, sectionId: String) = savePreference {
        collapseStore.update(serverId) { it.toggleSection(sectionId) }
    }

    fun setSortMode(mode: SessionSortMode) = savePreference { preferencesStore.setSortMode(mode) }
    fun setShowRecent(show: Boolean) = savePreference { preferencesStore.setShowRecent(show) }
    fun setRecentFilter(filter: RecentSessionFilter) = savePreference { preferencesStore.setRecentFilter(filter) }
    fun setRecentShowCompleted(show: Boolean) = savePreference { preferencesStore.setRecentShowCompleted(show) }

    private fun savePreference(save: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }
}

/** Raw list state; the grouped display is derived by [SessionGrouping]. */
data class SessionListUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val openFolderIds: Set<Int> = emptySet(),
    val preferences: SessionListPreferences = SessionListPreferences(),
    val hiddenScope: String? = null,
    val hiddenFolderIds: Set<Int> = emptySet(),
    val folderOrder: List<Int> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val showingCached: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

    /** Source-empty state; a list filtered empty by hidden folders offers a restore action. */
    val isEmpty: Boolean get() = conversations.isEmpty()
}

/** One folder and the (non-pinned) conversations shown under its group. */
data class FolderGroup(
    val folder: FolderDetail,
    val conversations: List<ConversationSummary>,
)

/**
 * Web-style grouping: pinned once, folderless chats in one flat section, and
 * worktree sessions under their root project. Display pagination never trims
 * these source lists, so every conversation remains reachable.
 */
object SessionGrouping {

    private val updatedFirst = compareByDescending<ConversationSummary> { it.updatedAt }
        .thenByDescending { it.createdAt }.thenByDescending { it.id }
    private val createdFirst = compareByDescending<ConversationSummary> { it.createdAt }
        .thenByDescending { it.updatedAt }.thenByDescending { it.id }

    private fun comparator(mode: SessionSortMode) =
        if (mode == SessionSortMode.CREATED) createdFirst else updatedFirst

    fun sortedFolders(folders: List<FolderDetail>, folderOrder: List<Int> = emptyList()): List<FolderDetail> =
        FolderOrdering.sorted(folders, folderOrder)

    /** `folderId -> name` for the dim folder tag shown on cross-folder rows. */
    fun folderNames(folders: List<FolderDetail>): Map<Int, String> {
        val byId = folders.associateBy { it.id }
        val out = LinkedHashMap<Int, String>()
        for (folder in folders) {
            if (folder.isChatFolder) continue
            val root = byId[folder.parentId]?.takeUnless { it.isChatFolder } ?: folder
            out.putIfAbsent(folder.id, root.name)
        }
        return out
    }

    /** Pinned conversations across all folders, most-recently-pinned first. */
    fun pinned(conversations: List<ConversationSummary>, search: String = ""): List<ConversationSummary> =
        matching(conversations.filter { it.isPinned }, search)
            .sortedWith(compareByDescending<ConversationSummary> { it.pinnedAt ?: Instant.MIN }.thenByDescending { it.id })

    /** Chat kind survives a missing folder; the folder flag supports older servers. */
    private fun isChat(conversation: ConversationSummary, folders: Map<Int, FolderDetail>): Boolean =
        conversation.kind == "chat" || folders[conversation.folderId]?.isChatFolder == true

    fun chats(folders: List<FolderDetail>, conversations: List<ConversationSummary>): List<ConversationSummary> {
        val byId = folders.associateBy { it.id }
        return conversations.filter { !it.isPinned && isChat(it, byId) }
            .sortedWith(updatedFirst)
    }

    /** Non-empty project groups; a worktree with a missing parent keeps its own group. */
    fun folderGroups(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
        sortMode: SessionSortMode = SessionSortMode.CREATED,
        folderOrder: List<Int> = emptyList(),
    ): List<FolderGroup> {
        val byId = folders.associateBy { it.id }
        val byProject = conversations.filter { !it.isPinned && !isChat(it, byId) }.groupBy { conversation ->
            val folder = byId[conversation.folderId]
            byId[folder?.parentId]?.takeUnless { it.isChatFolder } ?: folder
        }
        return sortedFolders(byProject.keys.filterNotNull(), folderOrder).mapNotNull { folder ->
            val convs = matching(byProject.getValue(folder), search)
                .sortedWith(comparator(sortMode))
            if (convs.isEmpty()) null else FolderGroup(folder, convs)
        }
    }

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    fun ungrouped(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
        sortMode: SessionSortMode = SessionSortMode.CREATED,
    ): List<ConversationSummary> {
        val known = folders.associateBy { it.id }
        return matching(
            conversations.filter { !it.isPinned && !isChat(it, known) && it.folderId !in known },
            search,
        ).sortedWith(comparator(sortMode))
    }

    /** Recent is an additional entry point, with no age cutoff and no source-list limit. */
    fun recent(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        openFolderIds: Set<Int>,
        preferences: SessionListPreferences,
    ): List<ConversationSummary> {
        val byId = folders.associateBy { it.id }
        return conversations.filter { conversation ->
            val chat = isChat(conversation, byId)
            !conversation.isPinned &&
                (preferences.recentShowCompleted || conversation.status != ConversationStatus.COMPLETED) &&
                (chat || conversation.folderId in openFolderIds) &&
                when (preferences.recentFilter) {
                    RecentSessionFilter.ALL -> true
                    RecentSessionFilter.CHATS -> chat
                    RecentSessionFilter.FOLDERS -> !chat
                    RecentSessionFilter.CLI -> conversation.source == "cli"
                }
        }.sortedWith(updatedFirst)
    }

    private fun matching(convs: List<ConversationSummary>, search: String): List<ConversationSummary> {
        val query = search.trim()
        if (query.isEmpty()) return convs
        return convs.filter { conv ->
            (conv.trimmedTitle?.contains(query, ignoreCase = true) == true) ||
                (conv.model?.contains(query, ignoreCase = true) == true)
        }
    }
}
