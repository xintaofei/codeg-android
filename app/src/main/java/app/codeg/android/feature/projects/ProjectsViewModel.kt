package app.codeg.android.feature.projects

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.HiddenFoldersStore
import app.codeg.android.core.datastore.FolderOrderStore
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the Projects (Folders) tab: folder list + per-folder running counts. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val hiddenFoldersStore: HiddenFoldersStore,
    private val folderOrderStore: FolderOrderStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProjectsUiState())
    val ui: StateFlow<ProjectsUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null
    private var fetchGeneration = 0

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile -> onProfile(profile) }
        }
        // Refetch when a folder is registered/opened elsewhere (e.g. a worktree
        // created during a session's branch switch).
        viewModelScope.launch {
            repository.foldersChanged.collect { if (client != null && !_ui.value.isBusy) fetch(initial = false) }
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        fetchGeneration++
        if (profile == null) {
            client = null; loadedEndpoint = null
            _ui.value = ProjectsUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        client = repository.client(profile)
        if (changed) _ui.value = ProjectsUiState(loading = true)
        if (client == null) {
            _ui.value = ProjectsUiState(error = appContext.getString(R.string.sessions_missing_token))
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
            fetch(initial = true)
        }
    }

    fun refresh() {
        if (_ui.value.isBusy) return
        viewModelScope.launch { fetch(initial = false) }
    }

    private suspend fun fetch(initial: Boolean) {
        val c = client ?: return
        val generation = ++fetchGeneration
        _ui.update { if (initial) it.copy(loading = true, error = null) else it.copy(refreshing = true) }
        try {
            val result = coroutineScope {
                val f = async { c.listFolders() }
                val conv = async { runCatching { c.listConversations() }.getOrDefault(emptyList()) }
                f.await() to conv.await()
            }
            if (generation != fetchGeneration || client !== c) return
            _ui.update {
                it.copy(folders = result.first, conversations = result.second, hasLoaded = true, loading = false, refreshing = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != fetchGeneration || client !== c) return
            _ui.update { it.copy(loading = false, refreshing = false, error = e.displayMessage()) }
        }
    }

    fun setFolderHidden(folderId: Int, hidden: Boolean) {
        val scope = _ui.value.hiddenScope ?: return
        viewModelScope.launch {
            try {
                hiddenFoldersStore.setHidden(scope, folderId, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.hiddenScope == scope) _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
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

    fun openFolder(path: String, onResult: (String?) -> Unit) {
        val c = client ?: return onResult("No server selected")
        viewModelScope.launch {
            val err = runCatching { c.openFolder(path) }.exceptionOrNull()?.displayMessage()
            if (err == null) fetch(initial = false)
            onResult(err)
        }
    }

    /** The server's home directory (for the filesystem browser's initial location). */
    suspend fun homeDirectory(): String =
        client?.let { runCatching { it.getHomeDirectory() }.getOrNull() } ?: "/"

    /** Subdirectories of [path] (browser; dirs only). */
    suspend fun listDirectories(path: String): List<app.codeg.android.core.model.DirectoryEntry> =
        client?.let { runCatching { it.listDirectoryEntries(path) }.getOrDefault(emptyList()) } ?: emptyList()

    /** Clone a repo, then register the new folder. Returns an error message or null on success. */
    suspend fun clone(
        url: String,
        targetDir: String,
        credentials: app.codeg.android.core.model.GitCredentials?,
    ): String? {
        val c = client ?: return "No server selected"
        return try {
            c.cloneRepository(url, targetDir, credentials)
            runCatching { c.openFolder(targetDir) }
            fetch(initial = false)
            null
        } catch (e: Exception) {
            e.displayMessage()
        }
    }
}

data class ProjectsUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val hiddenScope: String? = null,
    val hiddenFolderIds: Set<Int> = emptySet(),
    val folderOrder: List<Int> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = loading || refreshing
    val visibleFolders: List<FolderDetail> get() {
        val hidden = FolderVisibility.hiddenIds(folders, hiddenFolderIds)
        return folders.filter { it.id !in hidden }
    }
    fun runningCount(folderId: Int): Int =
        conversations.count { it.folderId == folderId && it.status.isLive }
}
