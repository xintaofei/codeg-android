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
    /** What the user wants on the conversation (shown at once; the writes follow). */
    val tagIds: Set<Int> = emptySet(),
    val creating: Boolean = false,
    val error: String? = null,
) {
    /** The folder whose own tags this conversation is offered (none for a chat). */
    val scope: Int? get() = ConversationTags.scopeFolderId(folders.firstOrNull { it.id == conversation?.folderId })
    val scopeName: String? get() = scope?.let { id -> folders.firstOrNull { it.id == id }?.name }
}

/**
 * Puts tags on and takes them off one conversation, and creates a tag from the search box. A
 * toggle shows at once; the writes go out one at a time ([TagAssignment]), each outcome settling
 * only what wasn't toggled again meanwhile. Everything is bound to the server and conversation
 * it was opened on: a write in flight finishes there even if the picker has moved on.
 */
@HiltViewModel
class TagPickerViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(TagPickerUiState())
    val ui: StateFlow<TagPickerUiState> = _ui.asStateFlow()

    /** One opening's binding: which server, which conversation, and its tag writes. */
    private class Session(
        val profileId: String,
        val client: CodegClient,
        val conversationId: Int,
        val assignment: TagAssignment,
    ) {
        var writing = false
    }

    private var session: Session? = null

    fun open(conversation: ConversationSummary) {
        // Shown at once from what the caller has — its folder too, which a move may have
        // changed — and the binding settles below.
        val known = session?.takeIf { it.conversationId == conversation.id }
        _ui.value = TagPickerUiState(
            conversation = conversation,
            tagIds = known?.assignment?.desired ?: conversation.tagIds.toSet(),
            tags = _ui.value.tags.takeIf { known != null },
            folders = _ui.value.folders.takeIf { known != null }.orEmpty(),
        )
        viewModelScope.launch {
            try {
                val profile = repository.selectedProfile.first() ?: return@launch
                val client = repository.client(profile) ?: return@launch
                // Reopened on the same conversation of the same server: what this picker set is
                // fresher than a summary the caller may not have refetched yet. Anything else
                // starts over from the caller's summary.
                val current = session?.takeIf { it.profileId == profile.id && it.conversationId == conversation.id }
                    ?: Session(profile.id, client, conversation.id, TagAssignment(conversation.tagIds.toSet())).also { session = it }
                if (_ui.value.conversation?.id != conversation.id) return@launch
                _ui.update { it.copy(tagIds = current.assignment.desired) }
                val (tags, folders) = coroutineScope {
                    val tags = async { current.client.listConversationTags() }
                    val folders = async { runCatching { current.client.listFolders() }.getOrDefault(emptyList()) }
                    tags.await() to folders.await()
                }
                if (session !== current) return@launch
                _ui.update { it.copy(tags = tags, folders = folders) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(tags = it.tags ?: emptyList(), error = appContext.getString(R.string.tags_load_failed) + ": " + e.displayMessage()) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Put [tag] on, or take it off. */
    fun toggle(tag: ConversationTag) {
        val s = session ?: return
        if (_ui.value.conversation?.id != s.conversationId) return
        s.assignment.toggle(tag.id)
        publish(s)
        pump(s)
    }

    /**
     * Create a tag named [name] — global, or in this conversation's folder — and put it on that
     * conversation, even if the picker has moved on by the time it exists. It starts on the
     * first colour its neighbours don't use yet.
     */
    fun create(name: String, global: Boolean) {
        val s = session ?: return
        val state = _ui.value
        if (state.conversation?.id != s.conversationId) return
        val trimmed = name.trim().take(ConversationTags.MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || state.creating) return
        val scope = if (global) null else state.scope ?: return
        val neighbours = state.tags.orEmpty().filter { it.folderId == null || it.folderId == scope }
        _ui.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val tag = s.client.createConversationTag(scope, trimmed, ConversationTags.nextColor(neighbours))
                s.assignment.turnOn(tag.id)
                if (session === s) _ui.update { it.copy(tags = it.tags.orEmpty() + tag) }
                publish(s)
                pump(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session === s) _ui.update { it.copy(error = appContext.getString(R.string.tags_create_failed) + ": " + e.displayMessage()) }
            } finally {
                if (session === s) _ui.update { it.copy(creating = false) }
            }
        }
    }

    /** Send what [s] still owes the server, one write at a time. */
    private fun pump(s: Session) {
        if (s.writing) return
        val write = s.assignment.nextWrite() ?: return
        s.writing = true
        viewModelScope.launch {
            try {
                val summary = s.client.updateConversationTags(s.conversationId, write.add, write.remove)
                s.assignment.succeeded(write, summary.tagIds.toSet())
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.assignment.failed(write)
                if (session === s) _ui.update { it.copy(error = appContext.getString(R.string.tags_assign_failed) + ": " + e.displayMessage()) }
            } finally {
                s.writing = false
                publish(s)
            }
            // Toggles made while this was out go next; after a refusal only those remain.
            pump(s)
        }
    }

    private fun publish(s: Session) {
        if (session !== s || _ui.value.conversation?.id != s.conversationId) return
        _ui.update { it.copy(tagIds = s.assignment.desired) }
    }
}
