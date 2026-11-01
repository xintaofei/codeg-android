package app.codeg.android.feature.sessiondetail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.logTranscriptTiming
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.CacheStatus
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.markdown.LocalMarkdownLinkHandler
import app.codeg.android.feature.sessiondetail.interactive.AskQuestionCard
import app.codeg.android.feature.sessiondetail.interactive.PermissionRequestCard
import app.codeg.android.feature.sessiondetail.interactive.PlanApprovalCard
import app.codeg.android.feature.sessiondetail.timeline.LocalTimelineScroll
import app.codeg.android.feature.sessiondetail.timeline.NodeBody
import app.codeg.android.feature.sessiondetail.timeline.NodeContent
import app.codeg.android.feature.sessiondetail.timeline.TimelineRow
import app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline
import app.codeg.android.feature.sessiondetail.timeline.TimelineNode
import app.codeg.android.core.model.TurnRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

private data class PersistedTimeline(val source: SessionDetailUiState, val nodes: List<TimelineNode>)

/**
 * The session detail screen: the transcript rendered as a vertical **timeline**
 * (one rail, every event a node) over a pinned composer. Sending resolves/opens an
 * ACP connection, streams the reply, and reconciles against the server transcript
 * on completion. Port of the iOS `SessionDetailView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onOpenSession: (Int) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onViewRemote: (Int, String) -> Unit = { _, _ -> },
    onOpenFile: (String) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val fileDownload by viewModel.fileDownload.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    var showInsert by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var scrollTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val onMarkdownLink: (String) -> Unit = remember(context, viewModel, onOpenFile, onViewRemote) {
        { link ->
            val web = messageWebUrl(link)
            if (web != null && !isApkLink(link)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.toASCIIString()))) }
                    .onFailure { Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_LONG).show() }
            } else {
                val id = viewModel.ui.value.summary?.id
                val path = if (web == null) viewModel.previewFilePath(link) else null
                if (id != null && path != null) onViewRemote(id, path)
                else viewModel.downloadFile(link)?.let(onOpenFile)
            }
        }
    }

    // A failed send returns the user's typed text to the composer so it isn't lost — but only
    // when the composer is empty, so it never clobbers a fresh draft typed during the send.
    LaunchedEffect(ui.restoreDraft) {
        ui.restoreDraft?.let { restored ->
            if (draft.isBlank()) draft = restored
            viewModel.consumeRestoredDraft()
        }
    }

    if (showOptions) AgentOptionsSheet(
        viewModel = viewModel,
        onOpenSession = onOpenSession,
        onDismiss = { showOptions = false },
    )
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentPrep.MAX_COUNT),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris, context.contentResolver) }

    if (showInsert) {
        ComposeInsertSheet(
            viewModel = viewModel,
            onInsert = { transform -> draft = transform(draft) },
            onDismiss = { showInsert = false },
        )
    }

    // Flatten the transcript into timeline nodes. The persisted half is memoized on its
    // own so the ~50ms live stream only re-runs the cheap live append, not a full re-adapt.
    // On reattach the live turn (rebuilt from the snapshot) owns the in-flight reply, so
    // hide the partial copy the server persists into `turns` to avoid a doubled reply.
    val hideReattachDup = ui.liveFromReattach && ui.live != null
    val timelineState = remember(ui.turns, ui.pendingUserTurns, ui.agent, hideReattachDup, ui.historyChunkStarts) { ui }
    val persistedTimeline by produceState<PersistedTimeline?>(
        initialValue = null,
        key1 = timelineState,
    ) {
        value = withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val adapted = TranscriptTimeline.buildPersisted(
                timelineState.turns, timelineState.pendingUserTurns, timelineState.agent,
                hideReattachDup, timelineState.historyChunkStarts,
            )
            logTranscriptTiming("timeline-build", started, records = timelineState.turns.size)
            PersistedTimeline(timelineState, adapted)
        }
    }
    val persistedNodes = persistedTimeline?.nodes.orEmpty()
    val historyRendered = persistedTimeline?.source === timelineState
    val nodes = remember(persistedNodes, ui.live, ui.agent) {
        TranscriptTimeline.withLive(persistedNodes, ui.live, ui.agent)
    }
    val hasContent = nodes.isNotEmpty()

    // Native stick-to-bottom: auto-follow only while the bottom anchor is in view.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    var stick by rememberSaveable { mutableStateOf(true) }
    var initiallyPositioned by rememberSaveable { mutableStateOf(false) }
    // Unpin immediately on a drag, including when a short window fits on screen.
    // Streamed growth alone must not turn auto-follow off.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) stick = false
        }
    }
    LaunchedEffect(listState, initiallyPositioned) {
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { inProgress ->
            if (initiallyPositioned && !inProgress) stick = isAtBottom
        }
    }
    LaunchedEffect(nodes.isNotEmpty()) {
        if (!initiallyPositioned && nodes.isNotEmpty()) {
            listState.scrollToItem(nodes.size + 1)
            initiallyPositioned = true
        }
    }
    LaunchedEffect(listState, ui.historyWindow?.offset, ui.loadingOlder, ui.olderError, initiallyPositioned, historyRendered, nodes.size) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                !stick && historyRendered && listState.layoutInfo.totalItemsCount == nodes.size + 2,
            )
        }.collect { (scrolling, index, ready) ->
            if (initiallyPositioned && scrolling && ready && index <= 4 &&
                (ui.historyWindow?.offset ?: 0) > 0 && !ui.loadingOlder && ui.olderError == null
            ) viewModel.loadOlderTurns()
        }
    }
    // A freshly sent message always re-pins to the bottom.
    var followedPendingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.pendingUserTurns.lastOrNull()?.id) {
        val pendingId = ui.pendingUserTurns.lastOrNull()?.id
        if (pendingId != null && pendingId != followedPendingId) stick = true
        followedPendingId = pendingId
    }
    // Follow new / streamed content while pinned.
    LaunchedEffect(nodes.size, ui.live, stick) {
        if (stick && nodes.isNotEmpty()) listState.scrollToItem(nodes.size + 1)
    }
    LaunchedEffect(scrollTarget) {
        scrollTarget?.let { id ->
            if (!viewModel.loadThroughTurn(id) && scrollTarget == id) scrollTarget = null
        }
    }
    LaunchedEffect(persistedNodes, scrollTarget) {
        val target = scrollTarget ?: return@LaunchedEffect
        val index = persistedNodes.indexOfFirst { it.id == target }
        if (index >= 0) {
            stick = false
            listState.animateScrollToItem(index + 1)
            scrollTarget = null
        }
    }
    val scrollToId: (String) -> Unit = { id -> stick = false; scrollTarget = id }

    val title = when {
        ui.isNew -> stringResource(R.string.session_new_task)
        else -> ui.summary?.trimmedTitle ?: stringResource(R.string.session_title_fallback)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = colors.textPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Rounded.FolderOpen, stringResource(R.string.session_files_title), tint = colors.textSecondary)
                    }
                    // The agent avatar is the options button (mode / config / folder / branch).
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClickLabel = stringResource(R.string.session_agent_options),
                                role = Role.Button,
                            ) { showOptions = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        AgentAvatar(ui.agent, size = 28.dp)
                    }
                    if (ui.summary != null) SessionActionsMenu(ui, viewModel, onDeleted = onBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
                if (!ui.isNew) CacheStatus(
                    refreshing = ui.refreshing,
                    showingCached = ui.showingCached,
                    lastUpdatedAt = ui.lastUpdatedAt,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                )
                fileDownload?.let { download ->
                    FileDownloadBanner(download, viewModel::retryFileDownload, { onOpenFile(download.key) }, viewModel::dismissFileDownload)
                }
                if (hasContent) ui.error?.let { error ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = colors.danger, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = viewModel::refresh, enabled = !ui.refreshing) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                ui.pendingPermission?.let { p ->
                    PermissionRequestCard(
                        parsed = p.parsed,
                        options = p.options,
                        onRespond = viewModel::respondPermission,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ui.pendingQuestion?.let { q ->
                    AskQuestionCard(
                        questions = q.questions,
                        onSubmit = viewModel::answerQuestion,
                        onSkip = viewModel::dismissQuestion,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ui.pendingPlanApproval?.let { p ->
                    PlanApprovalCard(
                        planMarkdown = p.planMarkdown,
                        onAnswer = viewModel::answerPlanApproval,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // Keep the last shown message so the exit animation has content to fade.
                var lastNotice by remember { mutableStateOf("") }
                LaunchedEffect(ui.notice) { ui.notice?.let { lastNotice = it } }
                AnimatedVisibility(
                    visible = ui.notice != null,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                ) {
                    NoticeBanner(lastNotice, onDismiss = viewModel::dismissNotice)
                }
                AnimatedVisibility(
                    visible = ui.sendStatus != null && ui.isInFlight,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                ) {
                    SendStatusLine(ui.sendStatus ?: "")
                }
                if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments, onRemove = viewModel::removeAttachment)
                ComposeBar(
                    text = draft,
                    onTextChange = { draft = it },
                    onSend = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    isInFlight = ui.isInFlight,
                    onStop = viewModel::cancel,
                    onPlus = { showInsert = true },
                    onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    canSendOverride = ui.attachments.isNotEmpty(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                (ui.loading || (persistedTimeline == null && ui.turns.isNotEmpty())) && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }

                ui.error != null && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.session_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = if (ui.isNew) {
                                stringResource(R.string.session_new_task)
                            } else {
                                stringResource(R.string.session_empty_title)
                            },
                            message = stringResource(R.string.session_empty_message),
                        )
                    }

                else -> CompositionLocalProvider(LocalTimelineScroll provides scrollToId, LocalMarkdownLinkHandler provides onMarkdownLink) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    ) {
                        item(key = "__timeline_history__") {
                            if ((ui.historyWindow?.offset ?: 0) > 0) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (ui.turns.firstOrNull()?.role == TurnRole.ASSISTANT) {
                                        Text(stringResource(R.string.session_round_continues), color = colors.textSecondary)
                                    }
                                    if (ui.loadingOlder) {
                                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        ui.olderError?.let { Text(it, color = colors.textSecondary) }
                                        TextButton(onClick = { stick = false; viewModel.loadOlderTurns() }) {
                                            Text(stringResource(if (ui.olderError != null) R.string.common_retry else R.string.session_load_older))
                                        }
                                    }
                                }
                            }
                        }
                        items(nodes, key = { it.id }, contentType = { it.contentTypeKey }) { node ->
                            TimelineRow(
                                marker = node.marker,
                                connectTop = node.connectTop,
                                connectBottom = node.connectBottom,
                                startsGroup = node.startsGroup,
                                rail = node.rail,
                                // Block rows skip item animation: streaming appends a tail
                                // block on every blank line, and the live→persisted re-key
                                // would otherwise flash all N blocks of the reply at once.
                                modifier = if (node.content is NodeContent.AssistantBlock) Modifier else Modifier.animateItem(),
                            ) {
                                NodeBody(node)
                            }
                        }
                        item(key = "__timeline_bottom_anchor__") { Spacer(Modifier.height(1.dp)) }
                    }
                }
            }
            // Floating "jump to latest" — overlaid bottom-center OVER the transcript rather
            // than sitting in the composer stack, where its full-width row pushed the bottom
            // of the transcript up under a band. Shown only while scrolled up.
            AnimatedVisibility(
                visible = hasContent && !isAtBottom,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                JumpToLatestButton {
                    stick = true
                    scope.launch { listState.animateScrollToItem(nodes.size + 1) }
                }
            }
        }
    }
}

/** A circular "jump to latest" affordance, floated above the composer when scrolled up. */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = stringResource(R.string.session_jump_to_latest),
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SendStatusLine(status: String) {
    val colors = CodegTheme.colors
    Text(
        text = status,
        fontSize = 12.sp,
        color = colors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgElevated.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss), tint = colors.textSecondary)
        }
    }
}

/** A horizontal strip of pending image attachments, each removable. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>, onRemove: (String) -> Unit) {
    val colors = CodegTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().background(colors.bgElevated.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { att ->
            val bitmap = remember(att.id) {
                runCatching {
                    val bytes = android.util.Base64.decode(att.base64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            Box(Modifier.size(60.dp)) {
                if (bitmap != null) {
                    Image(bitmap, contentDescription = stringResource(R.string.sessiondetail_attachment), contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(colors.codeSurface))
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(RoundedCornerShape(50)).background(colors.bg.copy(alpha = 0.7f)).clickable { onRemove(att.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_remove), tint = colors.textPrimary, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}
