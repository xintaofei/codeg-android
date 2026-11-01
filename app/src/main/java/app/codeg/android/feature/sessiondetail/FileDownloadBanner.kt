package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.feature.downloads.DownloadRecord
import app.codeg.android.feature.downloads.FileDownloadStatus
import app.codeg.android.core.designsystem.theme.CodegTheme

@Composable
internal fun FileDownloadBanner(state: DownloadRecord, onRetry: () -> Unit, onOpen: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.filename, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val label = when (state.status) {
                    FileDownloadStatus.PREPARING -> stringResource(R.string.download_preparing)
                    FileDownloadStatus.QUEUED -> stringResource(R.string.download_queued)
                    FileDownloadStatus.PAUSED -> stringResource(R.string.download_paused)
                    FileDownloadStatus.COMPLETE -> stringResource(R.string.download_complete)
                    FileDownloadStatus.FAILED -> state.error ?: stringResource(R.string.download_interrupted)
                    FileDownloadStatus.CANCELED -> stringResource(R.string.download_canceled)
                    FileDownloadStatus.DOWNLOADING -> if (state.total > 0) {
                        stringResource(R.string.download_progress, (state.bytes * 100 / state.total).coerceIn(0, 100).toInt())
                    } else stringResource(R.string.download_running)
                }
                Text(label, color = if (state.status == FileDownloadStatus.FAILED) colors.danger else colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (state.status != FileDownloadStatus.FAILED) state.error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
            }
            when (state.status) {
                FileDownloadStatus.FAILED, FileDownloadStatus.CANCELED -> TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                FileDownloadStatus.COMPLETE -> TextButton(onClick = onOpen) { Text(stringResource(if (state.apk) R.string.download_install else R.string.download_open)) }
                else -> Unit
            }
            if (!state.busy && onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), tint = colors.textSecondary) }
        }
        if (state.busy) {
            if (state.total > 0 && state.status != FileDownloadStatus.PREPARING) {
                LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
