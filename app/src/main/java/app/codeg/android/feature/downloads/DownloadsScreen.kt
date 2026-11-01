package app.codeg.android.feature.downloads

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.FileDownloadBanner
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSource: (Int) -> Unit,
    onViewRemote: (Int, String) -> Unit,
    initialTab: Int = 0,
    viewModel: DownloadsViewModel = hiltViewModel(),
    filesViewModel: SessionFilesViewModel = hiltViewModel(),
) {
    val files by filesViewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val records by viewModel.records.collectAsStateWithLifecycle()
    val downloadingRefresh by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshing = if (tab == 0) files.loading else downloadingRefresh
    val refresh: () -> Unit = { if (tab == 0) filesViewModel.refresh() else viewModel.refresh() }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_files_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { IconButton(onClick = refresh, enabled = !refreshing) { Icon(Icons.Rounded.Refresh, stringResource(R.string.downloads_refresh)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = colors.textPrimary) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.session_files_tab)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.downloads_title)) })
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.weight(1f)) {
            if (tab == 0) {
                SessionFilesContent(files, records, filesViewModel::selectConversation, onViewRemote,
                    { path -> filesViewModel.download(path, onOpenFile) }, onOpenSource)
            } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.downloads_description), modifier = Modifier.padding(horizontal = 16.dp), color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                (error ?: sourceError)?.let { message -> item { Text(message, color = colors.danger, modifier = Modifier.padding(horizontal = 16.dp)) } }
                if (records.isEmpty() && !refreshing) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.downloads_empty), color = colors.textSecondary)
                    }
                }
                items(records, key = { it.key }) { record ->
                    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable { onOpenFile(record.key) },
                        colors = CardDefaults.cardColors(containerColor = colors.bgElevated.copy(alpha = 0.65f)),
                        border = BorderStroke(0.5.dp, colors.surfaceStroke),
                    ) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            FileDownloadBanner(record, { viewModel.retry(record.key) }, { onOpenFile(record.key) })
                            Text(
                                "${DateFormat.getDateFormat(context).format(Date(record.createdAt))} ${DateFormat.getTimeFormat(context).format(Date(record.createdAt))}" +
                                    if (record.total >= 0) " · ${Formatter.formatFileSize(context, record.total)}" else "",
                                color = colors.textTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (record.conversationId != null) TextButton(
                                    onClick = { viewModel.source(record.key, onOpenSource) }, modifier = Modifier.weight(1f),
                                ) {
                                    Text(record.conversationTitle.ifBlank { stringResource(R.string.file_source_conversation) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else Box(Modifier.weight(1f))
                                if (record.busy) TextButton(onClick = { viewModel.cancel(record.key) }) { Text(stringResource(R.string.common_cancel)) }
                                else TextButton(onClick = { viewModel.remove(record.key) }) { Text(stringResource(R.string.downloads_remove_record)) }
                            }
                        }
                    }
                }
            }
            }
        }
        }
    }
}
