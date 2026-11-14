package app.codeg.android.feature.tags

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
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

/** One conversation's tags being edited (web `ConversationTagPickerPanel`). */
data class TagPickerUiState(
    val conversation: ConversationSummary? = null,
    /** Every tag the server has; null while loading. */
    val tags: List<ConversationTag>? = null,
    val folders: List<FolderDetail> = emptyList(),
    /** What is on the conversation, as this client last set or read it. */
    val tagIds: Set<Int> = emptySet(),
    val creating: Boolean = false,
    val error: String? = null,
) {
    /** The folder whose own tags this conversation is offered (none for a chat). */
    val scope: Int? get() = ConversationTags.scopeFolderId(folders.firstOrNull { it.id == conversation?.folderId })
    val scopeName: String? get() = scope?.let { id -> folders.firstOrNull { it.id == id }?.name }
}

/**
 * Puts tags on and takes them off one conversation, and creates a tag from the search box. Each
 * toggle is sent at once as a delta and shows at once; a refusal puts it back. The list screens
 * read the outcome on their next refetch.
 */
@HiltViewModel
class TagPickerViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(TagPickerUiState())
    val ui: StateFlow<TagPickerUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null

    fun open(conversation: ConversationSummary) {
        // Reopened on the same conversation, what this picker set is fresher than a summary the
        // caller may not have refetched yet.
        val known = _ui.value.takeIf { it.conversation?.id == conversation.id }
        _ui.value = TagPickerUiState(
            conversation = known?.conversation ?: conversation,
            tagIds = known?.tagIds ?: conversation.tagIds.toSet(),
            tags = known?.tags,
            folders = known?.folders.orEmpty(),
        )
        viewModelScope.launch {
            try {
                val c = client ?: repository.selectedProfile.first()?.let { repository.client(it) } ?: return@launch
                client = c
                val (tags, folders) = coroutineScope {
                    val tags = async { c.listConversationTags() }
                    val folders = async { runCatching { c.listFolders() }.getOrDefault(emptyList()) }
                    tags.await() to folders.await()
                }
                if (_ui.value.conversation?.id != conversation.id) return@launch
                _ui.update { it.copy(tags = tags, folders = folders) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(tags = emptyList(), error = appContext.getString(R.string.tags_load_failed) + ": " + e.displayMessage()) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Put [tag] on, or take it off. */
    fun toggle(tag: ConversationTag) {
        val c = client ?: return
        val conversation = _ui.value.conversation ?: return
        val on = tag.id !in _ui.value.tagIds
        _ui.update { it.copy(tagIds = if (on) it.tagIds + tag.id else it.tagIds - tag.id) }
        viewModelScope.launch {
            try {
                val summary = c.updateConversationTags(conversation.id, add = if (on) listOf(tag.id) else emptyList(), remove = if (on) emptyList() else listOf(tag.id))
                // What shows stays: it already has this toggle, and any sent since.
                if (_ui.value.conversation?.id == conversation.id) _ui.update { it.copy(conversation = summary) }
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.conversation?.id != conversation.id) return@launch
                _ui.update {
                    it.copy(
                        tagIds = if (on) it.tagIds - tag.id else it.tagIds + tag.id,
                        error = appContext.getString(R.string.tags_assign_failed) + ": " + e.displayMessage(),
                    )
                }
            }
        }
    }

    /**
     * Create a tag named [name] — global, or in this conversation's folder — and put it on. It
     * starts on the first colour its neighbours don't use yet.
     */
    fun create(name: String, global: Boolean) {
        val c = client ?: return
        val state = _ui.value
        val conversation = state.conversation ?: return
        val trimmed = name.trim().take(ConversationTags.MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || state.creating) return
        val scope = if (global) null else state.scope ?: return
        val neighbours = state.tags.orEmpty().filter { it.folderId == null || it.folderId == scope }
        _ui.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val tag = c.createConversationTag(scope, trimmed, ConversationTags.nextColor(neighbours))
                _ui.update { it.copy(tags = it.tags.orEmpty() + tag, creating = false) }
                if (_ui.value.conversation?.id == conversation.id) toggle(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(creating = false, error = appContext.getString(R.string.tags_create_failed) + ": " + e.displayMessage()) }
            }
        }
    }
}
