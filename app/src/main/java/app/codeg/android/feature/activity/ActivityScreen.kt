package app.codeg.android.feature.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessions.CollapsibleSectionHeader
import app.codeg.android.feature.sessions.SessionRow
import java.time.Instant

/**
 * The Activity tab: a live feed split into **Running** and **Last 24 Hours** sections.
 * Mirrors the Chats list's polish — the grouping is computed off the main thread
 * ([ActivityViewModel.sections]), the sections share the prominent collapsible header,
 * and the list recycles per item type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onOpenConversation: (Int) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val colors = CodegTheme.colors

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_activity)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }

                ui.hasLoaded && ui.isEmptyFeed ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_message))
                    }

                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    val toggle: (String) -> Unit = { id ->
                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                    }
                    val listState = rememberLazyListState()
                    val atTop by remember(listState) {
                        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
                    }
                    // What lands above the first row — Running, once the live statuses arrive, or a
                    // newly active session — would otherwise open the feed part-way down: the list
                    // keeps the row it showed first in view. At the top it stays at the top; a list
                    // the user has scrolled keeps its place.
                    if (atTop) {
                        // Read here, in composition, so every new feed reruns the effect while the
                        // state still describes the list as it was (the rows aren't measured yet).
                        val feed = sections
                        SideEffect { if (feed.isNotEmpty()) listState.requestScrollToItem(0) }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        sections.forEach { section ->
                            val isCollapsed = section.id in collapsed
                            item(key = "h-${section.id}", contentType = "header") {
                                val style = activitySectionStyle(section.kind)
                                CollapsibleSectionHeader(
                                    icon = style.icon,
                                    tint = style.tint,
                                    label = style.label,
                                    count = section.count,
                                    collapsed = isCollapsed,
                                    onToggle = { toggle(section.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            if (!isCollapsed) {
                                items(section.rows, key = { "row-${it.conversation.id}" }, contentType = { "row" }) { row ->
                                    SessionRow(
                                        row.conversation,
                                        onClick = { onOpenConversation(row.conversation.id) },
                                        modifier = Modifier.animateItem(),
                                        folderName = row.folderName,
                                    )
                                }
                            }
                        }
                        ui.lastRefreshed?.let { ts ->
                            item("updated", contentType = "footer") { UpdatedFooter(ts) }
                        }
                    }
                }
            }
        }
    }
}

/** Resolved header presentation for an [ActivityKind] (icon + theme-aware tint + label). */
private data class ActivityStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun activitySectionStyle(kind: ActivityKind): ActivityStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        ActivityKind.RUNNING -> ActivityStyle(
            icon = Icons.Rounded.GraphicEq,
            tint = colors.accent,
            label = stringResource(R.string.activity_running),
        )
        ActivityKind.RECENT -> ActivityStyle(
            icon = Icons.Rounded.History,
            tint = colors.textSecondary,
            label = stringResource(R.string.activity_recent),
        )
    }
}

/** "Updated 2 minutes ago" — a dim, centred footer echoing the iOS Activity feed. */
@Composable
private fun UpdatedFooter(timestamp: Instant) {
    val relative = DateUtils.getRelativeTimeSpanString(
        timestamp.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    Text(
        text = stringResource(R.string.activity_updated, relative),
        style = MaterialTheme.typography.labelSmall,
        color = CodegTheme.colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}
