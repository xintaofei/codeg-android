package app.codeg.android.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.RecentSessionFilter
import app.codeg.android.core.datastore.SessionListPreferences
import app.codeg.android.core.datastore.SessionSortMode
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.CacheStatus
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderOrdering
import app.codeg.android.feature.main.LocalBarsVisible
import app.codeg.android.feature.projects.FolderManagementSheet

/**
 * The Chats tab: Pinned / Chats / projects / Other / Recent, with per-section previews
 * and access to global search. Source data is never truncated by the preview.
 *
 * The iOS card-zoom interaction is adapted to native **collapsible sections**: tap a
 * section header to fold/unfold its rows. The grouping itself is computed off the main
 * thread ([SessionListViewModel.sections]) and the list is virtualized with per-type
 * recycling, so a very large session list stays smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelectServer: (String) -> Unit,
    onManageServers: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    onNewTask: () -> Unit,
    onSearch: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val storedLayout by viewModel.collapseLayout.collectAsStateWithLifecycle()
    // Wait for this server's saved layout instead of briefly expanding every group.
    val layout = storedLayout?.takeIf { it.serverId == selectedId }
    val collapseState = layout?.state ?: SessionListCollapseState()
    val colors = CodegTheme.colors
    val selectedName = servers.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.app_name)
    var showOrder by remember(selectedId, ui.hiddenScope) { mutableStateOf(false) }
    val orderedFolders = remember(ui.folders, ui.folderOrder) {
        FolderOrdering.sorted(ui.folders.filterNot { it.isChatFolder }, ui.folderOrder)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
            TopAppBar(
                title = {
                    ServerSwitcher(
                        name = selectedName,
                        servers = servers,
                        selectedId = selectedId,
                        onSelect = onSelectServer,
                        onManageServers = onManageServers,
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = selectedId != null && !ui.isRefreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = colors.textSecondary)
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.tab_search), tint = colors.textPrimary)
                    }
                    SessionListOptions(
                        preferences = ui.preferences,
                        onSort = viewModel::setSortMode,
                        onShowRecent = viewModel::setShowRecent,
                        onRecentFilter = viewModel::setRecentFilter,
                        onRecentShowCompleted = viewModel::setRecentShowCompleted,
                        canOrderFolders = ui.hiddenScope != null && orderedFolders.isNotEmpty(),
                        onOrderFolders = { showOrder = true },
                    )
                    IconButton(onClick = onManageServers) {
                        Icon(
                            Icons.Rounded.Dns,
                            contentDescription = stringResource(R.string.home_manage_servers),
                            tint = colors.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        CacheStatus(
                            refreshing = ui.isRefreshing,
                            showingCached = ui.showingCached,
                            lastUpdatedAt = ui.lastUpdatedAt,
                        )
                    }
                    TextButton(
                        onClick = { layout?.let { viewModel.toggleCompact(it.serverId) } },
                        enabled = layout != null && sections.isNotEmpty() && !ui.isEmpty,
                    ) {
                        Icon(
                            if (collapseState.compact) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(if (collapseState.compact) R.string.sessions_restore_layout else R.string.sessions_collapse_groups),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                // Follows the bottom bar: collapses to just "+" while scrolling up,
                // expands back to the full pill when scrolling down (driven by MainShell).
                expanded = LocalBarsVisible.current,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.session_new_task)) },
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.isLoading && !ui.hasLoaded -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }
                }

                !ui.hasLoaded && ui.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }
                }

                ui.hasLoaded && ui.isEmpty -> {
                    Column(Modifier.fillMaxSize()) {
                        ui.error?.let { error ->
                            RefreshErrorBanner(
                                message = error,
                                onRetry = viewModel::refresh,
                                onDismiss = viewModel::dismissError,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_empty_title),
                            message = stringResource(R.string.sessions_empty_message),
                            actionLabel = stringResource(R.string.session_new_task),
                            onAction = onNewTask,
                        )
                            TextButton(onClick = viewModel::refresh, enabled = !ui.isRefreshing) {
                                Text(stringResource(R.string.common_refresh))
                            }
                        }
                    }
                }

                layout == null && selectedId != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SessionList(
                            serverId = selectedId,
                            sections = sections,
                            preferences = ui.preferences,
                            collapseState = collapseState,
                            onToggleSection = { id -> layout?.let { viewModel.toggleSection(it.serverId, id) } },
                            onOpenConversation = onOpenConversation,
                            onTogglePin = { conv -> viewModel.setPinned(conv, !conv.isPinned) },
                            hasHiddenFolders = ui.hiddenFolderIds.isNotEmpty(),
                            onManageFolders = { showOrder = true },
                            // A refresh that failed over a still-populated list: surface
                            // it inline above the rows rather than swallowing it.
                            refreshError = ui.error,
                            onRetry = viewModel::refresh,
                            onDismissError = viewModel::dismissError,
                        )
                    }
                }
            }
        }
    }
    if (showOrder) {
        FolderManagementSheet(
            folders = orderedFolders,
            hiddenIds = ui.hiddenFolderIds,
            onSetHidden = viewModel::setFolderHidden,
            onMove = viewModel::moveFolder,
            onReorder = viewModel::reorderFolders,
            onReset = viewModel::resetFolderOrder,
            onDismiss = { showOrder = false },
            error = ui.error,
        )
    }
}

@Composable
private fun SessionList(
    serverId: String?,
    sections: List<SessionSection>,
    preferences: SessionListPreferences,
    collapseState: SessionListCollapseState,
    onToggleSection: (String) -> Unit,
    onOpenConversation: (Int) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
    hasHiddenFolders: Boolean,
    onManageFolders: () -> Unit,
    refreshError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    var rowLimits by rememberSaveable(serverId, preferences) { mutableStateOf(mapOf<String, Int>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 88.dp, // clear the extended FAB
        ),
    ) {
        if (refreshError != null) {
            item(key = "refresh-error", contentType = "banner") {
                RefreshErrorBanner(
                    message = refreshError,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        if (hasHiddenFolders && sections.none { it.count > 0 }) {
            item(key = "hidden-folders-empty", contentType = "empty") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.folders_hidden_hint), color = CodegTheme.colors.textSecondary)
                    TextButton(onClick = onManageFolders) { Text(stringResource(R.string.folders_manage)) }
                }
            }
        }
        sections.forEach { section ->
            val isCollapsed = collapseState.isCollapsed(section.id)
            item(key = "h-${section.id}", contentType = "header") {
                val style = sectionStyle(section.kind)
                CollapsibleSectionHeader(
                    icon = style.icon,
                    tint = style.tint,
                    label = if (section.kind == SectionKind.Recent && preferences.recentFilter != RecentSessionFilter.ALL) {
                        "${style.label} · ${stringResource(recentFilterLabel(preferences.recentFilter))}"
                    } else style.label,
                    count = section.count,
                    collapsed = isCollapsed,
                    onToggle = { onToggleSection(section.id) },
                    collapsible = section.count > 0,
                    modifier = Modifier.animateItem(),
                )
            }
            if (!isCollapsed) {
                val limit = rowLimits[section.id] ?: section.pageSize
                val visibleRows = section.rows.take(limit)
                items(
                    items = visibleRows,
                    key = { "row-${section.id}-${it.conversation.id}" },
                    contentType = { "row" },
                ) { row ->
                    SessionRow(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.id) },
                        modifier = Modifier.animateItem(),
                        folderName = row.folderName,
                        onTogglePin = { onTogglePin(row.conversation) },
                        timestamp = row.timestamp,
                    )
                }
                if (section.kind == SectionKind.Recent && section.count == 0) {
                    item(key = "recent-empty", contentType = "empty") {
                        Text(
                            stringResource(R.string.sessions_recent_empty),
                            color = CodegTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (visibleRows.size < section.count) {
                    item(key = "more-${section.id}", contentType = "more") {
                        TextButton(
                            onClick = { rowLimits = rowLimits + (section.id to (limit + section.pageSize)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) {
                            Text(stringResource(R.string.sessions_load_more, section.count - visibleRows.size))
                        }
                    }
                }
                if (limit > section.pageSize) {
                    item(key = "reset-${section.id}", contentType = "more") {
                        TextButton(
                            onClick = { rowLimits = rowLimits - section.id },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) {
                            Text(stringResource(R.string.sessions_reset_preview, section.pageSize))
                        }
                    }
                }
            }
        }
    }
}

/** Resolved header presentation for a [SectionKind] (icon + theme-aware tint + label). */
private data class SectionStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun sectionStyle(kind: SectionKind): SectionStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        SectionKind.Pinned -> SectionStyle(
            icon = Icons.Rounded.PushPin,
            tint = colors.accent,
            label = stringResource(R.string.sessions_pinned),
        )
        SectionKind.Chats -> SectionStyle(
            icon = Icons.Rounded.Forum,
            tint = colors.accent,
            label = stringResource(R.string.sessions_chats),
        )
        SectionKind.Recent -> SectionStyle(
            icon = Icons.Rounded.History,
            tint = colors.accent,
            label = stringResource(R.string.sessions_recent),
        )
        is SectionKind.Folder -> SectionStyle(
            icon = Icons.Rounded.Folder,
            tint = colorFromHex(kind.colorHex) ?: colors.accent,
            label = kind.name,
        )
        SectionKind.Other -> SectionStyle(
            icon = Icons.Rounded.Inbox,
            tint = colors.textSecondary,
            label = stringResource(R.string.sessions_other),
        )
    }
}

private fun recentFilterLabel(filter: RecentSessionFilter): Int = when (filter) {
    RecentSessionFilter.ALL -> R.string.sessions_recent_all
    RecentSessionFilter.CHATS -> R.string.sessions_chats
    RecentSessionFilter.FOLDERS -> R.string.sessions_recent_folders
    RecentSessionFilter.CLI -> R.string.sessions_recent_cli
}

@Composable
private fun SessionListOptions(
    preferences: SessionListPreferences,
    onSort: (SessionSortMode) -> Unit,
    onShowRecent: (Boolean) -> Unit,
    onRecentFilter: (RecentSessionFilter) -> Unit,
    onRecentShowCompleted: (Boolean) -> Unit,
    canOrderFolders: Boolean,
    onOrderFolders: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.sessions_list_options),
                tint = CodegTheme.colors.textPrimary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_manage)) },
                enabled = canOrderFolders,
                onClick = { open = false; onOrderFolders() },
            )
            HorizontalDivider()
            SessionSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (mode == SessionSortMode.CREATED) R.string.sessions_sort_created else R.string.sessions_sort_updated)) },
                    trailingIcon = { if (mode == preferences.sortMode) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onSort(mode); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_show_recent)) },
                trailingIcon = { if (preferences.showRecent) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = { onShowRecent(!preferences.showRecent); open = false },
            )
            if (preferences.showRecent) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessions_recent_filter)) },
                    enabled = false,
                    onClick = {},
                )
                RecentSessionFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(stringResource(recentFilterLabel(filter))) },
                        trailingIcon = { if (filter == preferences.recentFilter) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = { onRecentFilter(filter); open = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessions_recent_completed)) },
                    trailingIcon = { if (preferences.recentShowCompleted) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onRecentShowCompleted(!preferences.recentShowCompleted); open = false },
                )
            }
        }
    }
}

/**
 * A dismissible banner for a refresh that failed while rows are still on screen —
 * the stale rows stay visible and the error is surfaced inline with Retry / dismiss
 * (the Android analogue of iOS `RefreshErrorBanner`). The initial-load failure still
 * uses the full-screen [InlineError]; this is only for the keep-the-list case.
 */
@Composable
private fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.danger.copy(alpha = 0.12f))
            .border(CodegTheme.dimens.hairlineWidth, colors.danger.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) {
            Text(stringResource(R.string.common_retry))
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_dismiss),
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ServerSwitcher(
    name: String,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManageServers: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = CodegTheme.colors
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        open = false
                        if (server.id != selectedId) onSelect(server.id)
                    },
                    trailingIcon = {
                        if (server.id == selectedId) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_manage_servers)) },
                leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                onClick = {
                    open = false
                    onManageServers()
                },
            )
        }
    }
}
