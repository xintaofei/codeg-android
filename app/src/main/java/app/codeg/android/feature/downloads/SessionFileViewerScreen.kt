package app.codeg.android.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.theme.CodegTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionFileViewerScreen(
    onBack: () -> Unit,
    onOpenDownload: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
    viewModel: SessionFilesViewModel = hiltViewModel(),
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val path = viewModel.path.orEmpty()
    val filename = path.substringAfterLast('/').substringAfterLast('\\')
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) } },
            actions = {
                IconButton(onClick = viewModel::loadPreview, enabled = !preview.loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.downloads_refresh)) }
                IconButton(onClick = { viewModel.download(path, onOpenDownload) }, enabled = state.error == null) { Icon(Icons.Rounded.Download, stringResource(R.string.session_file_download)) }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary))
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(path, color = colors.textTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 3)
            Text(stringResource(R.string.session_file_current), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.conversationId?.let(onOpenSource) }, enabled = state.error == null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.file_source_conversation)) }
                preview.text?.let { CopyButton(it.text) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    state.error != null -> Text(state.error.orEmpty(), color = colors.danger, modifier = Modifier.padding(24.dp))
                    preview.loading -> CircularProgressIndicator()
                    preview.image != null -> ZoomableImage(requireNotNull(preview.image), filename)
                    preview.text != null -> TextPreviewContent(requireNotNull(preview.text))
                    else -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(preview.error ?: stringResource(R.string.file_preview_external), color = colors.textSecondary)
                        Button(onClick = { viewModel.download(path, onOpenDownload) }) { Text(stringResource(R.string.session_file_download)) }
                        if (preview.error != null) TextButton(onClick = viewModel::loadPreview) { Text(stringResource(R.string.common_retry)) }
                    }
                }
            }
        }
    }
}
