package app.codeg.android.feature.tags

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.BranchTagSetting
import app.codeg.android.core.model.ConversationTag
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagsManagerUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val tags: List<ConversationTag> = emptyList(),
    val branchTag: BranchTagSetting? = null,
    /** Top-level open folders: the ones that can own tags. */
    val folders: List<FolderDetail> = emptyList(),
    val selectedFolderId: Int? = null,
    /** A mutation's failure, said under the list (or in the open form). */
    val error: String? = null,
    val saving: Boolean = false,
) {
    fun scopeTags(scope: Int?): List<ConversationTag> = tags.filter { it.folderId == scope }.sortedWith(ConversationTags.ORDER)
}

/**
 * Manage tags (web `ConversationTagsManager`): the branch tag setting, the global tags, and one
 * folder's tags — create, rename / recolour, delete, reorder. Each change is the server's to
 * make; the list is read again after it, and the session lists are told to refetch.
 */
@HiltViewModel
class TagsManagerViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(TagsManagerUiState())
    val ui: StateFlow<TagsManagerUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val c = client ?: repository.selectedProfile.first()?.let { repository.client(it) }
                    ?: throw IllegalStateException(appContext.getString(R.string.sessions_missing_token))
                client = c
                val (tags, branch, folders) = coroutineScope {
                    val tags = async { c.listConversationTags() }
                    val branch = async { runCatching { c.conversationBranchTag() }.getOrNull() }
                    val folders = async { runCatching { c.listOpenFolders() }.getOrDefault(emptyList()) }
                    Triple(tags.await(), branch.await(), folders.await())
                }
                val roots = FolderVisibility.filterTopLevel(folders).filterNot { it.isChat }
                _ui.update {
                    it.copy(
                        loading = false,
                        tags = tags,
                        branchTag = branch,
                        folders = roots,
                        selectedFolderId = it.selectedFolderId?.takeIf { id -> roots.any { f -> f.id == id } } ?: roots.firstOrNull()?.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, loadError = e.displayMessage()) }
            }
        }
    }

    fun selectFolder(id: Int) = _ui.update { it.copy(selectedFolderId = id) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Create in [scope] (null = global). [onDone] true closes the form; false keeps it open
     *  with the server's reason under it. */
    fun create(scope: Int?, name: String, color: String, onDone: (Boolean) -> Unit) =
        mutate(R.string.tags_create_failed, onDone) { c ->
            val tag = c.createConversationTag(scope, name.trim(), color)
            _ui.update { it.copy(tags = it.tags + tag) }
        }

    /** Send only what changed. */
    fun update(tag: ConversationTag, name: String, color: String, onDone: (Boolean) -> Unit) {
        val newName = name.trim().takeIf { it != tag.name }
        val newColor = color.takeIf { !it.equals(tag.color, ignoreCase = true) }
        if (newName == null && newColor == null) {
            onDone(true)
            return
        }
        mutate(R.string.tags_update_failed, onDone) { c ->
            val saved = c.updateConversationTag(tag.id, newName, newColor)
            _ui.update { it.copy(tags = it.tags.map { t -> if (t.id == saved.id) saved else t }) }
        }
    }

    fun delete(tag: ConversationTag) = mutate(R.string.tags_delete_failed) { c ->
        c.deleteConversationTag(tag.id)
        _ui.update { it.copy(tags = it.tags.filterNot { t -> t.id == tag.id }) }
    }

    /** Move [tag] one place up ([by] -1) or down (+1) in its scope, sending the scope's whole order. */
    fun move(tag: ConversationTag, by: Int) {
        val scoped = _ui.value.scopeTags(tag.folderId).toMutableList()
        val from = scoped.indexOfFirst { it.id == tag.id }
        val to = from + by
        if (from < 0 || to !in scoped.indices) return
        scoped.add(to, scoped.removeAt(from))
        // Shown at once; the server's order replaces it after.
        val order = scoped.mapIndexed { i, t -> t.id to i + 1 }.toMap()
        _ui.update { it.copy(tags = it.tags.map { t -> order[t.id]?.let { o -> t.copy(sortOrder = o) } ?: t }) }
        mutate(R.string.tags_reorder_failed, refetchAfter = true) { c -> c.reorderConversationTags(scoped.map { it.id }) }
    }

    fun setBranchTag(enabled: Boolean, color: String) {
        val previous = _ui.value.branchTag
        _ui.update { it.copy(branchTag = BranchTagSetting(enabled, color)) }
        mutate(R.string.tags_branch_failed, onFailure = { _ui.update { it.copy(branchTag = previous) } }) { c ->
            val saved = c.updateConversationBranchTag(enabled, color)
            _ui.update { it.copy(branchTag = saved) }
        }
    }

    private fun mutate(
        failure: Int,
        onDone: (Boolean) -> Unit = {},
        refetchAfter: Boolean = false,
        onFailure: () -> Unit = {},
        block: suspend (CodegClient) -> Unit,
    ) {
        val c = client ?: return
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                block(c)
                _ui.update { it.copy(saving = false) }
                onDone(true)
                repository.notifyConversationsChanged()
                if (refetchAfter) load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                _ui.update { it.copy(saving = false, error = appContext.getString(failure) + ": " + e.displayMessage()) }
                onDone(false)
                // What the server holds now, whatever this client assumed.
                if (refetchAfter) load()
            }
        }
    }
}
