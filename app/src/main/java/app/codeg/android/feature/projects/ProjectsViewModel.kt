package app.codeg.android.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the Projects (Folders) tab: folder list + per-folder running counts. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProjectsUiState())
    val ui: StateFlow<ProjectsUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null

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
        if (profile == null) {
            client = null; loadedEndpoint = null
            _ui.value = ProjectsUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        client = repository.client(profile)
        if (changed) _ui.update { ProjectsUiState() }
        fetch(initial = true)
    }

    fun refresh() {
        if (_ui.value.isBusy) return
        viewModelScope.launch { fetch(initial = false) }
    }

    private suspend fun fetch(initial: Boolean) {
        val c = client ?: return
        _ui.update { if (initial) it.copy(loading = true, error = null) else it.copy(refreshing = true) }
        try {
            // The workspace's open folders, not every folder the server knows: one removed from
            // the workspace stays on record (closed) so its conversations still resolve by id.
            val result = coroutineScope {
                val f = async { c.listOpenFolders() }
                val conv = async { runCatching { c.listConversations() }.getOrDefault(emptyList()) }
                f.await() to conv.await()
            }
            val open = result.first
            _ui.update {
                it.copy(
                    folders = FolderVisibility.visibleFolders(open),
                    childToParent = FolderVisibility.childToParent(open),
                    conversations = result.second,
                    hasLoaded = true,
                    loading = false,
                    refreshing = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, refreshing = false, error = e.displayMessage()) }
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
    /** The rows: open folders, less chat folders and worktrees of an open root. */
    val folders: List<FolderDetail> = emptyList(),
    /** Hidden worktree → its root, whose row counts the worktree's conversations. */
    val childToParent: Map<Int, Int> = emptyMap(),
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = loading || refreshing
    fun runningCount(folderId: Int): Int =
        conversations.count { FolderVisibility.mergedFolderId(it.folderId, childToParent) == folderId && it.status.isLive }
}
