package app.codeg.android.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.feature.main.LocalBarsVisible
import app.codeg.android.feature.tags.TagFilter
import app.codeg.android.feature.tags.TagFilterBar
import app.codeg.android.feature.tags.TagFilterSheet
import app.codeg.android.feature.tags.TagPickerSheet

/**
 * The Chats tab: the grouped session list for the selected server (Pinned /
 * per-folder / Other), a server switcher in the title, and a "New Task" action.
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
    onManageTags: (() -> Unit)? = null,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    // Collapsed section ids. In the composable (not the VM) so collapsing never
    // re-runs the grouping flow; rememberSaveable keeps the fold state across
    // rotation / process death.
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // The conversation whose "Move to" is open.
    var moving by remember { mutableStateOf<ConversationSummary?>(null) }
    // The conversation whose tags are being edited, and whether the tag filter is open.
    var tagging by remember { mutableStateOf<ConversationSummary?>(null) }
    var filtering by remember { mutableStateOf(false) }
    val filter = ui.activeFilter
    // Off the raw list, not the sections (derived off the main thread, so a frame behind).
    val filteredAway = remember(ui.conversations, filter) { filter.isActive && ui.conversations.none(filter::matches) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.dismissNotice()
    }
    val colors = CodegTheme.colors
    val selectedName = servers.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.app_name)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
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
                    // Only once there are tags to filter by (or a filter still on).
                    if (ui.tags.isNotEmpty() || filter.isActive) {
                        IconButton(onClick = { filtering = true }) {
                            BadgedBox(badge = { if (filter.isActive) Badge(containerColor = colors.accent) { Text("${filter.tagIds.size}") } }) {
                                Icon(
                                    Icons.Rounded.FilterList,
                                    contentDescription = stringResource(R.string.tags_filter_title),
                                    tint = if (filter.isActive) colors.accent else colors.textSecondary,
                                )
                            }
                        }
                    }
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

                // Every conversation filtered away: say so, and offer the way back.
                ui.hasLoaded && !ui.isEmpty && filteredAway -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.FilterList,
                            title = stringResource(R.string.tags_filter_no_matches),
                            message = "",
                            actionLabel = stringResource(R.string.tags_filter_clear),
                            onAction = { viewModel.setTagFilter(TagFilter.NONE) },
                        )
                    }
                }

                ui.hasLoaded && ui.isEmpty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_empty_title),
                            message = stringResource(R.string.sessions_empty_message),
                            actionLabel = stringResource(R.string.session_new_task),
                            onAction = onNewTask,
                        )
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SessionList(
                            sections = sections,
                            collapsed = collapsed,
                            onToggleSection = { id ->
                                collapsed = if (id in collapsed) collapsed - id else collapsed + id
                            },
                            onOpenConversation = onOpenConversation,
                            onTogglePin = { conv -> viewModel.setPinned(conv, !conv.isPinned) },
                            onMove = { conv ->
                                viewModel.loadMoveTargets()
                                moving = conv
                            },
                            onEditTags = { conv -> tagging = conv },
                            branchColor = ui.branchTag.color,
                            filterBar = if (filter.isActive) ({
                                TagFilterBar(filter, ui.tags, viewModel::setTagFilter, Modifier.padding(horizontal = 4.dp))
                            }) else null,
                            // A refresh that failed over a still-populated list: surface
                            // it inline above the rows rather than swallowing it.
                            refreshError = ui.error,
                            onRetry = viewModel::refresh,
                            onDismissError = viewModel::dismissError,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        }
    }

    tagging?.let { conv ->
        TagPickerSheet(conversation = conv, onManageTags = onManageTags, onDismiss = { tagging = null })
    }
    if (filtering) {
        TagFilterSheet(
            tags = ui.tags,
            conversations = ui.conversations,
            folders = ui.folders,
            filter = filter,
            onChange = viewModel::setTagFilter,
            onManageTags = onManageTags,
            onDismiss = { filtering = false },
        )
    }

    moving?.let { conv ->
        MoveConversationSheet(
            conversation = conv,
            folders = ui.moveTargets,
            allFolders = ui.folders,
            currentFolderPath = ui.folders.firstOrNull { it.id == conv.folderId }?.path,
            onMove = { folder -> viewModel.move(conv, folder) },
            onDismiss = { moving = null },
        )
    }
}

@Composable
private fun SessionList(
    sections: List<SessionSection>,
    collapsed: Set<String>,
    onToggleSection: (String) -> Unit,
    onOpenConversation: (Int) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
    onMove: (ConversationSummary) -> Unit,
    onEditTags: (ConversationSummary) -> Unit,
    branchColor: String,
    filterBar: (@Composable () -> Unit)?,
    refreshError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
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

        if (filterBar != null) {
            item(key = "tag-filter", contentType = "filter") { filterBar() }
        }

        sections.forEach { section ->
            val isCollapsed = section.id in collapsed
            item(key = "h-${section.id}", contentType = "header") {
                val style = sectionStyle(section.kind)
                CollapsibleSectionHeader(
                    icon = style.icon,
                    tint = style.tint,
                    label = style.label,
                    count = section.count,
                    collapsed = isCollapsed,
                    onToggle = { onToggleSection(section.id) },
                    collapsible = section.count > 0,
                    modifier = Modifier.animateItem(),
                )
            }
            if (!isCollapsed) {
                items(
                    items = section.rows,
                    key = { "row-${it.conversation.id}" },
                    contentType = { "row" },
                ) { row ->
                    SessionRow(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.id) },
                        modifier = Modifier.animateItem(),
                        folderName = row.folderName,
                        onTogglePin = { onTogglePin(row.conversation) },
                        onMove = if (row.conversation.isMovable) ({ onMove(row.conversation) }) else null,
                        onEditTags = { onEditTags(row.conversation) },
                        tags = row.tags,
                        branchChip = row.branchChip,
                        branchColor = branchColor,
                    )
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
