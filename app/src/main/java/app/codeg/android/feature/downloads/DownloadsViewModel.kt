package app.codeg.android.feature.downloads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocalPreviewState(
    val loading: Boolean = false,
    val image: Bitmap? = null,
    val text: TextFilePreview? = null,
    val error: String? = null,
)

@HiltViewModel
internal class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val servers: ServerRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val key: String? = savedStateHandle["downloadKey"]
    val records = combine(downloads.records, servers.selectedProfile) { list, profile ->
        list.filter { profile != null && it.belongsTo(profile) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val record = downloads.records.map { list -> list.firstOrNull { it.key == key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val error = downloads.error
    val refreshing = downloads.refreshing
    val preview = MutableStateFlow(LocalPreviewState())
    val sourceError = MutableStateFlow<String?>(null)
    private var previewJob: Job? = null

    fun refresh() { downloads.refresh() }
    fun retry(key: String) { downloads.retry(key) }
    fun cancel(key: String) { downloads.cancel(key) }
    fun remove(key: String) { downloads.remove(key) }
    fun openExternal(key: String) { downloads.openExternal(key) }
    fun redownload(key: String): String? = downloads.redownload(key)

    fun source(key: String, onOpen: (Int) -> Unit) = viewModelScope.launch {
        val record = downloads.records.value.firstOrNull { it.key == key } ?: return@launch
        val profile = servers.selectedProfile.first()
        if (profile == null || !record.belongsTo(profile) || record.conversationId == null) {
            sourceError.value = context.getString(R.string.download_source_unavailable)
        } else {
            sourceError.value = null
            onOpen(record.conversationId)
        }
    }

    fun loadPreview() {
        val current = record.value ?: return
        if (current.status != FileDownloadStatus.COMPLETE) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            preview.value = LocalPreviewState(loading = true)
            try {
                val uri = downloads.contentUri(current.key)
                preview.value = withContext(Dispatchers.IO) {
                    when (filePreviewKind(current.filename)) {
                        FilePreviewKind.IMAGE -> {
                            val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                                val scale = minOf(1f, 2048f / info.size.width, 2048f / info.size.height)
                                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            LocalPreviewState(image = image)
                        }
                        FilePreviewKind.TEXT -> {
                            val text = context.contentResolver.openInputStream(uri)?.use(::readTextPreview)
                                ?: error(context.getString(R.string.download_missing))
                            LocalPreviewState(text = text)
                        }
                        FilePreviewKind.EXTERNAL -> LocalPreviewState()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                preview.value = LocalPreviewState(error = context.getString(R.string.file_preview_failed))
            }
        }
    }
}
