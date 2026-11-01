package app.codeg.android.feature.downloads

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.FileDownloadBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileViewerScreen(
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(record?.id, record?.status) { viewModel.loadPreview() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(record?.filename ?: stringResource(R.string.file_viewer_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (record?.status == FileDownloadStatus.COMPLETE) IconButton(onClick = { record?.let { viewModel.redownload(it.key)?.let(onOpenFile) } }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.file_redownload))
                    }
                    if (record?.status == FileDownloadStatus.COMPLETE) IconButton(onClick = { record?.let { viewModel.openExternal(it.key) } }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.file_open_external))
                    }
                    IconButton(onClick = onDownloads) { Icon(Icons.Rounded.Download, stringResource(R.string.downloads_title)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val current = record
            if (current == null) {
                Text(error ?: stringResource(R.string.download_missing), color = colors.textSecondary, modifier = Modifier.padding(20.dp))
                return@Column
            }
            if (current.status != FileDownloadStatus.COMPLETE) {
                FileDownloadBanner(current, { viewModel.retry(current.key) }, {})
                if (current.busy) TextButton(onClick = { viewModel.cancel(current.key) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.common_cancel)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current.conversationId != null) TextButton(onClick = { viewModel.source(current.key, onOpenSource) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.file_source_conversation), maxLines = 1)
                } else Box(Modifier.weight(1f))
                if (current.total >= 0) Text(Formatter.formatFileSize(context, current.total), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
                preview.text?.let { CopyButton(it.text) }
            }
            (sourceError ?: error ?: current.error?.takeIf { current.status == FileDownloadStatus.COMPLETE })?.let {
                Text(it, color = colors.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    current.status != FileDownloadStatus.COMPLETE -> Text(stringResource(R.string.file_wait_for_download), color = colors.textSecondary, modifier = Modifier.padding(24.dp))
                    preview.loading -> CircularProgressIndicator()
                    preview.image != null -> ZoomableImage(requireNotNull(preview.image), current.filename)
                    preview.text != null -> TextPreviewContent(requireNotNull(preview.text))
                    else -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(preview.error ?: stringResource(if (current.apk) R.string.file_apk_description else R.string.file_preview_external), color = colors.textSecondary)
                        Button(onClick = { viewModel.openExternal(current.key) }) {
                            Text(stringResource(if (current.apk) R.string.download_install else R.string.file_open_external))
                        }
                        if (preview.error != null) TextButton(onClick = viewModel::loadPreview) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TextPreviewContent(preview: TextFilePreview) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxSize()) {
        if (preview.truncated) Text(stringResource(R.string.file_preview_truncated), color = colors.textTertiary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(preview.text, fontFamily = FontFamily.Monospace, color = colors.textPrimary, style = MaterialTheme.typography.bodySmall,
                softWrap = false, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
internal fun ZoomableImage(bitmap: Bitmap, description: String) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val state = rememberTransformableState { change, delta, _ ->
        zoom = (zoom * change).coerceIn(1f, 5f)
        val x = size.width * (zoom - 1) / 2
        val y = size.height * (zoom - 1) / 2
        pan = Offset((pan.x + delta.x).coerceIn(-x, x), (pan.y + delta.y).coerceIn(-y, y))
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }.transformable(state)
        .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }) {
        Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
    }
}
