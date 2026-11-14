package app.codeg.android.feature.tags

import android.content.Context
import androidx.annotation.StringRes
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
import kotlinx.coroutines.CoroutineScope
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
    /** Every tag the server has; null while loading — and nothing can be toggled until then. */
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

/** The server calls the picker makes. */
interface TagsApi {
    suspend fun listTags(): List<ConversationTag>
    suspend fun listFolders(): List<FolderDetail>
    suspend fun createTag(folderId: Int?, name: String, color: String): ConversationTag
    suspend fun setTags(conversationId: Int, add: List<Int>, remove: List<Int>): ConversationSummary
}

/**
 * Puts tags on and takes them off one conversation, and creates a tag from the search box. A
 * toggle shows at once; the writes go out one at a time ([TagAssignment]), each outcome settling
 * only what wasn't toggled again meanwhile.
 *
 * Every write belongs to one server endpoint and one conversation, and finishes there whatever
 * the picker shows by then. A conversation still owing writes keeps its one writer: reopening
 * it takes that writer over rather than starting a second.
 */
class TagPickerController(
    private val scope: CoroutineScope,
    /** The selected server: a key naming its endpoint, and its calls; null without one. */
    private val connect: suspend () -> Pair<String, TagsApi>?,
    private val onChanged: () -> Unit,
    /** A failure's line: what failed ([StringRes]), then why. */
    private val failure: (Int, Throwable) -> String,
) {
    private val _ui = MutableStateFlow(TagPickerUiState())
    val ui: StateFlow<TagPickerUiState> = _ui.asStateFlow()

    /** The tag writes of one conversation on one server, and its writer. */
    private class Session(val key: String, val api: TagsApi, val conversationId: Int, val assignment: TagAssignment) {
        var writing = false
        val idle: Boolean get() = !writing && assignment.nextWrite() == null
    }

    /** Sessions still owing the server something, by endpoint and conversation. */
    private val sessions = HashMap<String, Session>()

    /** The session the picker shows and edits; null until this opening has bound one. */
    private var session: Session? = null

    /** Bumped by every open: an older one resuming late must not bind over a newer one. */
    private var openSeq = 0

    fun open(conversation: ConversationSummary) {
        val seq = ++openSeq
        // Nothing is actionable until this opening has bound its session and read the tags,
        // so nothing can reach a server or conversation it isn't.
        session = null
        _ui.value = TagPickerUiState(conversation = conversation, tagIds = conversation.tagIds.toSet())
        scope.launch {
            try {
                val (endpoint, api) = connect() ?: return@launch
                if (seq != openSeq) return@launch
                val key = "$endpoint|${conversation.id}"
                // One still writing for this conversation holds what the user asked for since;
                // otherwise the caller's summary is the latest word.
                val bound = sessions[key]?.takeIf { !it.idle }
                    ?: Session(key, api, conversation.id, TagAssignment(conversation.tagIds.toSet())).also { sessions[key] = it }
                session = bound
                prune()
                _ui.update { it.copy(tagIds = bound.assignment.desired) }
                val (tags, folders) = coroutineScope {
                    val tags = async { bound.api.listTags() }
                    val folders = async { runCatching { bound.api.listFolders() }.getOrDefault(emptyList()) }
                    tags.await() to folders.await()
                }
                if (seq != openSeq || session !== bound) return@launch
                _ui.update { it.copy(tags = tags, folders = folders, tagIds = bound.assignment.desired) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != openSeq) return@launch
                // Without the tag list there is nothing to toggle: the error is all it shows.
                session = null
                _ui.update { it.copy(tags = emptyList(), error = failure(R.string.tags_load_failed, e)) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Put [tag] on, or take it off. */
    fun toggle(tag: ConversationTag) {
        val s = session ?: return
        if (_ui.value.conversation?.id != s.conversationId || _ui.value.tags == null) return
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
        if (state.conversation?.id != s.conversationId || state.tags == null) return
        val trimmed = name.trim().take(ConversationTags.MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || state.creating) return
        val folderScope = if (global) null else state.scope ?: return
        val neighbours = state.tags.filter { it.folderId == null || it.folderId == folderScope }
        _ui.update { it.copy(creating = true) }
        scope.launch {
            try {
                val tag = s.api.createTag(folderScope, trimmed, ConversationTags.nextColor(neighbours))
                s.assignment.turnOn(tag.id)
                if (session === s) _ui.update { it.copy(tags = it.tags.orEmpty() + tag) }
                publish(s)
                pump(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session === s) _ui.update { it.copy(error = failure(R.string.tags_create_failed, e)) }
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
        scope.launch {
            try {
                val summary = s.api.setTags(s.conversationId, write.add, write.remove)
                s.assignment.succeeded(write, summary.tagIds.toSet())
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.assignment.failed(write)
                if (session === s) _ui.update { it.copy(error = failure(R.string.tags_assign_failed, e)) }
            } finally {
                s.writing = false
                publish(s)
            }
            // Toggles made while this was out go next; after a refusal only those remain.
            pump(s)
            prune()
        }
    }

    /** Forget the sessions with nothing left to send, but the one on screen. */
    private fun prune() {
        sessions.values.removeAll { it !== session && it.idle }
    }

    private fun publish(s: Session) {
        if (session !== s || _ui.value.conversation?.id != s.conversationId) return
        _ui.update { it.copy(tagIds = s.assignment.desired) }
    }
}

/** The tag picker sheet's [TagPickerController], on the selected server. */
@HiltViewModel
class TagPickerViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val controller = TagPickerController(
        scope = viewModelScope,
        connect = {
            repository.selectedProfile.first()?.let { profile ->
                repository.client(profile)?.let { "${profile.id}|${profile.baseUrl}" to ClientTagsApi(it) }
            }
        },
        onChanged = repository::notifyConversationsChanged,
        failure = { res, e -> appContext.getString(res) + ": " + e.displayMessage() },
    )

    val ui: StateFlow<TagPickerUiState> = controller.ui

    fun open(conversation: ConversationSummary) = controller.open(conversation)
    fun dismissError() = controller.dismissError()
    fun toggle(tag: ConversationTag) = controller.toggle(tag)
    fun create(name: String, global: Boolean) = controller.create(name, global)
}

private class ClientTagsApi(private val client: CodegClient) : TagsApi {
    override suspend fun listTags() = client.listConversationTags()
    override suspend fun listFolders() = client.listFolders()
    override suspend fun createTag(folderId: Int?, name: String, color: String) = client.createConversationTag(folderId, name, color)
    override suspend fun setTags(conversationId: Int, add: List<Int>, remove: List<Int>) = client.updateConversationTags(conversationId, add, remove)
}
