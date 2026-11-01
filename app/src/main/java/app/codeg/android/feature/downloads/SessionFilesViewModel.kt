package app.codeg.android.feature.downloads

import android.content.Context
import android.graphics.ImageDecoder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.SessionFile
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import app.codeg.android.feature.sessiondetail.workspaceFileLink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SessionFilesState(
    val conversationId: Int? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val files: List<SessionFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
internal class SessionFilesViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val downloads: DownloadRepository,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val initialId: Int? = savedState.get<Int>("conversationId")?.takeIf { it > 0 }
    val path: String? = savedState["path"]
    val state = MutableStateFlow(SessionFilesState(conversationId = initialId))
    val preview = MutableStateFlow(LocalPreviewState())
    private var refreshJob: Job? = null
    private var previewJob: Job? = null
    private var profile: app.codeg.android.core.datastore.ServerProfile? = null

    init {
        viewModelScope.launch {
            servers.selectedProfile.collectLatest { selected ->
                refreshJob?.cancel()
                previewJob?.cancel()
                profile = selected
                val identity = selected?.let { "${it.id}\u0000${it.baseUrl.trimEnd('/')}" }
                val origin: String? = savedState["fileOrigin"]
                if (origin != null && origin != identity) {
                    state.value = SessionFilesState(error = context.getString(R.string.download_source_unavailable))
                    preview.value = LocalPreviewState()
                    profile = null
                    return@collectLatest
                }
                if (identity != null) savedState["fileOrigin"] = identity
                state.value = SessionFilesState(conversationId = initialId)
                preview.value = LocalPreviewState()
                if (path != null) loadPreview() else refresh()
            }
        }
    }

    fun selectConversation(id: Int?) {
        state.value = state.value.copy(conversationId = id, files = emptyList(), error = null)
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val selected = profile ?: return
        refreshJob = viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            try {
                val client = servers.client(selected) ?: error(context.getString(R.string.download_source_unavailable))
                val id = state.value.conversationId
                if (id == null) {
                    val sessions = client.listConversations(sortBy = "updated_at")
                    state.value = state.value.copy(conversations = sessions.sortedByDescending { it.updatedAt }, loading = false)
                } else {
                    if (state.value.files.isEmpty()) client.cachedConversationFiles(id)?.let {
                        state.value = state.value.copy(files = it)
                    }
                    val files = client.conversationFiles(id)
                    state.value = state.value.copy(files = files, loading = false)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val message = if (e is ApiError.Server && e.status == 404 && e.code == null) context.getString(R.string.session_files_server_update) else e.displayMessage()
                state.value = state.value.copy(loading = false, error = message)
            }
        }
    }

    fun download(filePath: String, onStarted: (String) -> Unit) = viewModelScope.launch {
        val selected = profile ?: return@launch
        if (servers.selectedProfile.first() != selected) return@launch
        val id = state.value.conversationId
        val title = state.value.conversations.firstOrNull { it.id == id }?.title.orEmpty()
        onStarted(downloads.start(selected, id, title, null, android.net.Uri.encode(filePath, "/\\:")))
    }

    fun loadPreview() {
        previewJob?.cancel()
        val selected = profile ?: return
        val target = path?.let { workspaceFileLink(android.net.Uri.encode(it, "/\\:"), emptyList()) } ?: return
        val filename = path.substringAfterLast('/')
        if (filePreviewKind(filename) == FilePreviewKind.EXTERNAL) return
        previewJob = viewModelScope.launch {
            preview.value = LocalPreviewState(loading = true)
            try {
                val client = servers.client(selected) ?: error("Server unavailable")
                val image = filePreviewKind(filename) == FilePreviewKind.IMAGE
                val limit = if (image) 12 * 1024 * 1024 + 1 else MAX_PREVIEW_BYTES + 1
                val bytes = client.workspacePreviewBytes(target.rootPath, target.path, limit)
                if (image && bytes.size >= limit) {
                    preview.value = LocalPreviewState(error = context.getString(R.string.session_file_image_large))
                    return@launch
                }
                preview.value = withContext(Dispatchers.IO) {
                    if (image) {
                        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                            val scale = minOf(1f, 2048f / info.size.width, 2048f / info.size.height)
                            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        LocalPreviewState(image = bitmap)
                    } else LocalPreviewState(text = bytes.inputStream().use(::readTextPreview))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                preview.value = LocalPreviewState(error = context.getString(R.string.session_file_preview_failed))
            }
        }
    }
}
