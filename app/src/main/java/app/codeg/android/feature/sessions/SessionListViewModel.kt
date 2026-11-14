package app.codeg.android.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.TagFilterStore
import app.codeg.android.core.model.BranchTagSetting
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.feature.tags.TagFilter
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the Chats (session list) screen: loads folders + conversations for the
 * selected server and keeps the full list in memory so grouping / pin toggles
 * stay instant. Faithful port of the iOS `SessionListViewModel` — the grouped
 * display (Pinned / per-folder / Other) is derived by [SessionGrouping].
 *
 * Rebinds automatically when the selected server changes (or is edited in place
 * to a new endpoint): a monotonic [fetchGeneration] guards against a slow fetch
 * clobbering a newer one.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val tagFilterStore: TagFilterStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionListUiState())
    val ui: StateFlow<SessionListUiState> = _ui.asStateFlow()

    /**
     * The grouped, fully-sorted sections the Chats list renders (Pinned / per-folder
     * / Other). Derived from [_ui] but recomputed ONLY when the folders/conversations
     * actually change — a refresh-spinner or error toggle keeps the same list
     * references, so [distinctUntilChanged] short-circuits and nothing re-sorts. The
     * O(n·log n) grouping runs on [Dispatchers.Default], never on the main thread or
     * during composition, which is what keeps a very large session list smooth.
     */
    val sections: StateFlow<List<SessionSection>> =
        _ui
            .map { GroupingInput(it.folders, it.conversations, it.tags, it.branchTag.enabled, it.activeFilter) }
            .distinctUntilChanged()
            .map { input ->
                buildSessionSections(input.folders, input.conversations, input.tags.associateBy { it.id }, input.branchTagEnabled, input.filter)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The only [_ui] fields the grouping depends on, so spinner/error toggles don't re-sort. */
    private data class GroupingInput(
        val folders: List<FolderDetail>,
        val conversations: List<ConversationSummary>,
        val tags: List<ConversationTag>,
        val branchTagEnabled: Boolean,
        val filter: TagFilter,
    )

    private var client: CodegClient? = null
    private var profileId: String? = null
    private var loadedEndpoint: String? = null
    private var fetchGeneration = 0

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile -> onProfile(profile) }
        }
        // Refetch when a conversation is renamed/pinned/status-changed/deleted elsewhere.
        viewModelScope.launch {
            repository.conversationsChanged.collect { if (client != null && !_ui.value.isBusy) fetch(isInitial = false) }
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        if (profile == null) {
            client = null
            profileId = null
            loadedEndpoint = null
            _ui.value = SessionListUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        if (profile.id != profileId) {
            profileId = profile.id
            val saved = runCatching { tagFilterStore.filter(profile.id) }.getOrDefault(TagFilter.NONE)
            _ui.update { it.copy(tagFilter = saved) }
        }
        val resolved = repository.client(profile)
        client = resolved
        if (resolved == null) {
            _ui.update { it.copy(isLoading = false, error = appContext.getString(R.string.sessions_missing_token)) }
            return
        }
        fetch(isInitial = true, force = changed)
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
            _ui.update { it.copy(folders = emptyList(), conversations = emptyList(), tags = emptyList(), branchTag = BranchTagSetting(), hasLoaded = false) }
        }
        _ui.update {
            if (isInitial) it.copy(isLoading = true, error = null)
            else it.copy(isRefreshing = true, error = null)
        }
        try {
            val result = coroutineScope {
                val folders = async { active.listFolders() }
                val conversations = async { active.listConversations() }
                // Tags dress the rows; a server without them (or a failed read) only goes without.
                val tags = async { runCatching { active.listConversationTags() }.getOrNull() }
                val branch = async { runCatching { active.conversationBranchTag() }.getOrNull() }
                Listing(folders.await(), conversations.await(), tags.await(), branch.await())
            }
            if (token != fetchGeneration) return
            _ui.update {
                it.copy(
                    folders = result.folders,
                    conversations = result.conversations,
                    tags = result.tags ?: it.tags,
                    branchTag = result.branchTag ?: it.branchTag,
                    hasLoaded = true,
                    isLoading = false,
                    isRefreshing = false,
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
            } catch (e: Exception) {
                _ui.update { it.copy(conversations = previous, error = e.displayMessage()) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    /** Change the tag filter; kept for this server. */
    fun setTagFilter(filter: TagFilter) {
        _ui.update { it.copy(tagFilter = filter) }
        val id = profileId ?: return
        viewModelScope.launch { runCatching { tagFilterStore.save(id, filter) } }
    }

    /** Read the open folders afresh for a move: one closed since the list loaded is no target. */
    fun loadMoveTargets() {
        val active = client ?: return
        _ui.update { it.copy(moveTargets = null) }
        viewModelScope.launch {
            try {
                val folders = active.listOpenFolders()
                _ui.update { it.copy(moveTargets = folders) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(moveTargets = emptyList(), notice = appContext.getString(R.string.move_conversation_failed, e.displayMessage())) }
            }
        }
    }

    /**
     * Move [conversation] to [folder]. Nothing optimistic: the server decides (a busy session is
     * skipped), and the refetch after it shows where everything landed.
     */
    fun move(conversation: ConversationSummary, folder: FolderDetail) {
        val active = client ?: return
        val label = FolderVisibility.displayName(folder, _ui.value.folders)
        viewModelScope.launch {
            try {
                val result = active.moveConversations(listOf(conversation.id), folder.id)
                _ui.update { it.copy(notice = moveOutcomeText(appContext, result, label)) }
                if (result.moved.isNotEmpty()) repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(notice = appContext.getString(R.string.move_conversation_failed, e.displayMessage())) }
            }
        }
    }
}

/** One fetch's results; the tag reads are null when they failed. */
private data class Listing(
    val folders: List<FolderDetail>,
    val conversations: List<ConversationSummary>,
    val tags: List<ConversationTag>?,
    val branchTag: BranchTagSetting?,
)

/** Raw list state; the grouped display is derived by [SessionGrouping]. */
data class SessionListUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    /** A one-shot message (a move's outcome), shown as a snackbar. */
    val notice: String? = null,
    /** The open folders a conversation may move to; null while they load. */
    val moveTargets: List<FolderDetail>? = null,
    /** Every tag this server has, for the rows' chips and the filter. */
    val tags: List<ConversationTag> = emptyList(),
    val branchTag: BranchTagSetting = BranchTagSetting(),
    /** As saved: it may still name tags since deleted — [activeFilter] is what applies. */
    val tagFilter: TagFilter = TagFilter.NONE,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

    /**
     * The filter that applies: only tags that still exist, once the tags have loaded (until
     * then a saved id can't be told from a deleted one). A dead id would otherwise turn "all"
     * into "nothing matches" for no visible reason.
     */
    val activeFilter: TagFilter
        get() = if (tags.isEmpty()) TagFilter.NONE else tagFilter.pruned(tags.mapTo(HashSet()) { it.id })

    /**
     * Empty ⟺ no conversations: every conversation lands in some section (Pinned, a
     * folder group, or Other), so folders-but-no-sessions correctly shows the empty
     * state instead of a wall of empty folder headers (matches iOS). Reads raw [_ui]
     * fields, so the decision stays atomic with the data and never flickers against
     * the separately-derived [SessionListViewModel.sections] flow.
     */
    val isEmpty: Boolean get() = conversations.isEmpty()
}

/** One folder and the (non-pinned) conversations shown under its group. */
data class FolderGroup(
    val folder: FolderDetail,
    val conversations: List<ConversationSummary>,
)

/**
 * Pure grouping/filtering of a session list, ported 1:1 from the iOS
 * `SessionListViewModel` derived-data accessors. Pinned conversations appear
 * only in the Pinned group; each folder group lists its non-pinned
 * conversations newest-first.
 */
object SessionGrouping {

    fun sortedFolders(folders: List<FolderDetail>): List<FolderDetail> =
        folders.sortedWith(
            compareBy({ it.sortOrder }, { it.name.lowercase() }),
        )

    /** `folderId -> name` for the dim folder tag shown on cross-folder rows. */
    fun folderNames(folders: List<FolderDetail>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (folder in folders) out.putIfAbsent(folder.id, folder.name)
        return out
    }

    /** Pinned conversations across all folders, most-recently-pinned first. */
    fun pinned(conversations: List<ConversationSummary>, search: String = ""): List<ConversationSummary> =
        matching(conversations.filter { it.isPinned }, search)
            .sortedByDescending { it.pinnedAt ?: Instant.MIN }

    /**
     * One group per folder (in [sortedFolders] order) holding that folder's
     * non-pinned conversations, newest-first. With no search every folder is
     * included; when searching, folders with no match are dropped.
     */
    fun folderGroups(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<FolderGroup> {
        val searching = search.trim().isNotEmpty()
        val unpinnedByFolder = conversations.filter { !it.isPinned }.groupBy { it.folderId }
        return sortedFolders(folders).mapNotNull { folder ->
            val convs = matching(unpinnedByFolder[folder.id] ?: emptyList(), search)
                .sortedByDescending { it.updatedAt }
            if (searching && convs.isEmpty()) null else FolderGroup(folder, convs)
        }
    }

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    fun ungrouped(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<ConversationSummary> {
        val known = folders.map { it.id }.toSet()
        return matching(
            conversations.filter { !it.isPinned && it.folderId !in known },
            search,
        ).sortedByDescending { it.updatedAt }
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
