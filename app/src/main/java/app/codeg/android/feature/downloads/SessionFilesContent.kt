package app.codeg.android.feature.downloads

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import java.time.Instant
import java.util.Date

@Composable
internal fun SessionFilesContent(
    state: SessionFilesState,
    records: List<DownloadRecord>,
    onSelect: (Int?) -> Unit,
    onView: (Int, String) -> Unit,
    onDownload: (String) -> Unit,
    onSource: (Int) -> Unit,
) {
    val colors = CodegTheme.colors
    val context = LocalContext.current
    var query by rememberSaveable(state.conversationId) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(12.dp)) {
        item {
            Text(stringResource(R.string.session_files_description), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.session_files_search)) })
            if (state.conversationId != null) Row {
                TextButton(onClick = { onSelect(null) }) { Text(stringResource(R.string.session_files_choose)) }
                TextButton(onClick = { onSource(state.conversationId) }) { Text(stringResource(R.string.file_source_conversation)) }
            } else Text(stringResource(R.string.session_files_choose), color = colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        state.error?.let { message -> item { Text(message, color = colors.danger) } }
        if (state.conversationId == null) {
            items(state.conversations.filter { it.title.orEmpty().contains(query, ignoreCase = true) }, key = { it.id }) { session ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(session.id) }, colors = CardDefaults.cardColors(containerColor = colors.bgElevated)) {
                    Text(session.title ?: "#${session.id}", modifier = Modifier.padding(16.dp), color = colors.textPrimary, maxLines = 2)
                }
            }
        } else {
            val visible = state.files.filter { it.path.contains(query, ignoreCase = true) }
            if (visible.isEmpty() && !state.loading && state.error == null) item {
                Text(stringResource(R.string.session_files_empty), color = colors.textSecondary, modifier = Modifier.padding(20.dp))
            }
            items(visible, key = { it.path }) { file ->
                Card(Modifier.fillMaxWidth().clickable(enabled = file.available != false) { onView(state.conversationId, file.path) },
                    colors = CardDefaults.cardColors(containerColor = colors.bgElevated), border = BorderStroke(0.5.dp, colors.surfaceStroke)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(file.filename, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(file.path, color = colors.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when (file.operation) {
                            "write" -> R.string.session_file_written
                            "edit", "apply_patch" -> R.string.session_file_modified
                            "delete" -> R.string.session_file_deleted
                            else -> R.string.session_file_referenced
                        }) + (file.size?.let { " · ${Formatter.formatFileSize(context, it)}" } ?: ""), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        val timestamp = runCatching {
                            val date = Date.from(Instant.parse(file.timestamp))
                            "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
                        }.getOrDefault(file.timestamp)
                        Text(timestamp, color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
                        if (file.available == false) Text(stringResource(R.string.session_file_unavailable), color = colors.danger, style = MaterialTheme.typography.bodySmall)
                        if (file.available == null) Text(stringResource(R.string.session_file_check_on_open), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { onView(state.conversationId, file.path) }, enabled = file.available != false) { Text(stringResource(R.string.session_file_view)) }
                            TextButton(onClick = { onDownload(file.path) }, enabled = file.available != false) { Text(stringResource(R.string.session_file_download)) }
                            if (records.any { it.conversationId == state.conversationId && android.net.Uri.decode(it.link) == file.path && it.status == FileDownloadStatus.COMPLETE }) {
                                Text(stringResource(R.string.session_file_saved), color = colors.textTertiary, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
