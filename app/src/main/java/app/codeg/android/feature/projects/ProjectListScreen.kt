package app.codeg.android.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FolderBadge
import app.codeg.android.core.designsystem.component.GlassCard
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderOrdering

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onOpenFolder: (Int) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showOrder by remember(ui.hiddenScope) { mutableStateOf(false) }
    val visibleFolders = remember(ui.folders, ui.hiddenFolderIds, ui.folderOrder) {
        FolderOrdering.sorted(ui.visibleFolders, ui.folderOrder)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_folders)) },
                actions = {
                    IconButton(
                        onClick = { showOrder = true },
                        enabled = ui.hiddenScope != null && ui.folders.any { !it.isChatFolder },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.folders_manage))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.projects_add))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_open_folder)) },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { menuOpen = false; showBrowser = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_clone_repo)) },
                        leadingIcon = { Icon(Icons.Rounded.CloudDownload, null) },
                        onClick = { menuOpen = false; showClone = true },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
                !ui.hasLoaded && ui.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(Icons.Rounded.FolderOpen, stringResource(R.string.projects_load_failed), ui.error!!, viewModel::refresh)
                    }
                ui.hasLoaded && visibleFolders.isEmpty() ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            Icons.Rounded.FolderOpen,
                            stringResource(if (ui.folders.isNotEmpty()) R.string.folders_all_hidden_title else R.string.projects_empty_title),
                            stringResource(if (ui.folders.isNotEmpty()) R.string.folders_hidden_hint else R.string.projects_empty_message),
                        )
                        if (ui.hiddenFolderIds.isNotEmpty()) {
                            TextButton(onClick = { showOrder = true }) { Text(stringResource(R.string.folders_manage)) }
                        }
                        ui.error?.let { Text(it, color = colors.danger, modifier = Modifier.padding(16.dp)) }
                    }
                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    ) {
                        ui.error?.let { error ->
                            item { Text(error, color = colors.danger, modifier = Modifier.padding(12.dp)) }
                        }
                        // One continuous grouped surface holding every folder as a
                        // borderless row split by inset hairlines — the iOS grouped-list
                        // look, matching the Settings tab. Folder counts are small, so a
                        // single card inside the lazy list is fine.
                        item {
                            GlassCard(padding = 0.dp) {
                                val list = visibleFolders
                                list.forEachIndexed { index, folder ->
                                    if (index > 0) {
                                        HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 66.dp))
                                    }
                                    androidx.compose.runtime.key(ui.hiddenScope, folder.id) {
                                        FolderRow(folder, ui.runningCount(folder.id)) { onOpenFolder(folder.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOrder) {
        FolderManagementSheet(
            folders = FolderOrdering.sorted(ui.folders.filterNot { it.isChatFolder }, ui.folderOrder),
            hiddenIds = ui.hiddenFolderIds,
            onSetHidden = viewModel::setFolderHidden,
            onMove = viewModel::moveFolder,
            onReorder = viewModel::reorderFolders,
            onReset = viewModel::resetFolderOrder,
            onDismiss = { showOrder = false },
            error = ui.error,
        )
    }

    if (showBrowser) {
        DirectoryBrowserSheet(
            title = stringResource(R.string.projects_open_folder),
            confirmLabel = stringResource(R.string.projects_open_this_folder),
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            onSelect = { path -> showBrowser = false; viewModel.openFolder(path) {} },
            onDismiss = { showBrowser = false },
        )
    }
    if (showClone) {
        CloneRepoSheet(
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            clone = viewModel::clone,
            onCloned = { showClone = false; viewModel.refresh() },
            onDismiss = { showClone = false },
        )
    }
}

/**
 * One folder as a borderless grouped row: a colored [FolderBadge] tile, the name
 * with its full path + branch underneath, a trailing running-count pill or
 * relative last-opened time, then a drill-in chevron. The enclosing [GlassCard]
 * draws the surface; rows are split by inset hairlines.
 */
@Composable
private fun FolderRow(folder: FolderDetail, running: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val tile = colorFromHex(folder.color) ?: colors.accent
    Row(
        modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderBadge(color = tile, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folder.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                folder.gitBranch?.let { BranchPill(it) }
            }
        }
        if (running > 0) {
            RunningPill(running)
        } else {
            folder.lastOpenedAt?.let { Text(RelativeTime.compact(it), fontSize = 11.sp, color = colors.textTertiary) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Active-task indicator: a pulsing dot + count in an accent capsule (iOS folder row). */
@Composable
private fun RunningPill(count: Int) {
    val colors = CodegTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentDim).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LivePulse(dotSize = 7.dp)
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.accent)
    }
}

@Composable
fun BranchPill(branch: String) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.textPrimary.copy(alpha = 0.06f)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(11.dp))
        Text(
            branch,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
