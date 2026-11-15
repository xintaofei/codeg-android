package app.codeg.android.feature.sessiondetail

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
import androidx.compose.foundation.gestures.stopScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.codeg.android.feature.sessiondetail.composer.ComposerReferenceCodec
import app.codeg.android.feature.sessiondetail.timeline.ForkControl
import app.codeg.android.feature.sessiondetail.timeline.LocalForkControl
import app.codeg.android.feature.sessiondetail.rendering.ActivityLive
import app.codeg.android.feature.sessiondetail.timeline.launchCardTitle
import app.codeg.android.feature.sessiondetail.timeline.runningSubAgents
import app.codeg.android.feature.sessiondetail.rendering.LocalActivityLive
import app.codeg.android.feature.sessiondetail.rendering.GoalControl
import app.codeg.android.feature.sessiondetail.rendering.LocalGoalControl
import app.codeg.android.feature.sessiondetail.rendering.LocalOpenConversation
import app.codeg.android.feature.sessiondetail.rendering.DelegationTaskFold
import app.codeg.android.feature.sessiondetail.rendering.LocalDelegationTasks
import app.codeg.android.feature.sessiondetail.rendering.RenderPart
import app.codeg.android.core.model.DelegationBinding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.LocalRichBlockGrew
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.FeedbackNote
import app.codeg.android.feature.sessiondetail.interactive.AskQuestionCard
import app.codeg.android.feature.sessiondetail.interactive.PermissionRequestCard
import app.codeg.android.feature.sessiondetail.interactive.PlanApprovalCard
import app.codeg.android.feature.sessiondetail.timeline.LocalDisclosures
import app.codeg.android.feature.sessiondetail.timeline.LocalReplyFoldActions
import app.codeg.android.feature.sessiondetail.timeline.LocalTimelineScroll
import app.codeg.android.feature.sessiondetail.timeline.NodeBody
import app.codeg.android.feature.sessiondetail.timeline.NodeContent
import app.codeg.android.core.model.TurnRole
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import app.codeg.android.feature.sessiondetail.timeline.ReplyFoldActions
import app.codeg.android.feature.sessiondetail.timeline.ReplyFoldInput
import app.codeg.android.feature.sessiondetail.timeline.ReplyFolds
import app.codeg.android.feature.sessiondetail.timeline.ThreadEntry
import app.codeg.android.feature.sessiondetail.timeline.TimelineRow
import app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * How close to the head of the list the viewport has to come before the next page
 * of older history is requested. Rows, not turns — one turn is many rows — so this
 * fires while the loader is still comfortably below the fold and the page lands
 * before the user reaches the top.
 */
private const val OLDER_HISTORY_TRIGGER_ROWS = 6

/** Rows past the last visible one beyond which [followToEnd] jumps rather than scrolls. */
private const val FOLLOW_SCROLL_MAX_ROWS = 20

/** Viewports [followToEnd] scrolls through before it gives up and jumps. */
private const val FOLLOW_SCROLL_MAX_STEPS = 12

/**
 * Bring the end of the list into view by SCROLLING to it, not by snapping to an index.
 *
 * In this Compose (foundation 1.7) a snap to an index resets the list's item animator to an
 * empty key map (`LazyLayoutItemAnimator.reset`), after which every row with an item animation
 * reads as newly inserted: following each streamed update that way faded the prompt, the reply
 * header, its thoughts and tool cards out and back in at every step. A scroll is not a jump.
 *
 * Far from the end (a long thread just opened, a send from well up the history) it jumps
 * instead: scrolling there would lay out every row in between.
 */
private suspend fun LazyListState.followToEnd() {
    val info = layoutInfo
    val last = info.totalItemsCount - 1
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return
    if (last - lastVisible <= FOLLOW_SCROLL_MAX_ROWS) {
        val step = info.viewportSize.height.toFloat().coerceAtLeast(1f)
        scroll {
            var steps = 0
            while (canScrollForward && steps++ < FOLLOW_SCROLL_MAX_STEPS) {
                if (scrollBy(step) <= 0f) break
            }
        }
    }
    if (canScrollForward) scrollToItem(last)
}

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
    /** Open another conversation by id — a delegated child's session. */
    onOpenConversation: ((Int) -> Unit)? = null,
    onManageTags: (() -> Unit)? = null,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // The live stream follows the screen: paused while it's stopped (backgrounded, or covered
    // by another destination), picked back up from its cursor when it starts again.
    LifecycleStartEffect(viewModel) {
        viewModel.setScreenVisible(true)
        onStopOrDispose { viewModel.setScreenVisible(false) }
    }
    val colors = CodegTheme.colors
    var draft by remember { mutableStateOf("") }
    var showInsert by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A failed send returns the user's typed text to the composer so it isn't lost — but only
    // when the composer is empty, so it never clobbers a fresh draft typed during the send.
    LaunchedEffect(ui.restoreDraft) {
        ui.restoreDraft?.let { restored ->
            // It left in its wire form; its mention links read as tokens again.
            if (draft.isBlank()) draft = viewModel.composer.restore(restored)
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
    // Out-of-turn turns read off the agent's transcript join the finished rounds after the
    // server's window, in wall-clock order, until a detail load brings their persisted copies.
    // A turn an overlay re-tells (a background reply read while it was still growing) gives
    // way to the overlay's newer copy, so no step is drawn twice.
    val persistedEntries = remember(ui.turns, ui.localTurns, ui.backgroundTurns, ui.pendingUserTurns, ui.agent, hideReattachDup, ui.inFlightUserTurnId) {
        val retold = BackgroundOverlays.callIds(ui.backgroundTurns)
        TranscriptTimeline.persistedEntries(
            BackgroundOverlays.withoutRetold(ui.turns, retold), ui.pendingUserTurns, ui.agent, hideReattachDup, ui.inFlightUserTurnId,
            BackgroundOverlays.interleave(BackgroundOverlays.withoutRetold(ui.localTurns, retold), ui.backgroundTurns),
        )
    }
    val liveEntries = remember(ui.live, ui.agent) { TranscriptTimeline.liveEntries(ui.live, ui.agent) }
    val entries = remember(persistedEntries, liveEntries) {
        TranscriptTimeline.dedupeCompactions(TranscriptTimeline.joinLive(persistedEntries, liveEntries))
    }
    // What the checks on each delegated task said — newest last — for its card and marker. The
    // transcript is read once; only the live reply is read again as it streams.
    val persistedTasks = remember(persistedEntries, ui.delegations) {
        DelegationTaskFold().also { it.addCalls(persistedEntries, ui.delegations) }
    }
    val delegationTasks = remember(persistedTasks, liveEntries, ui.delegations) {
        persistedTasks.copy().also { it.addCalls(liveEntries, ui.delegations) }.index()
    }
    // Which replies show their steps (ReplyFold.kt): observed here, stored after the frame
    // that draws it, so a send and the fold it causes land together.
    val folds = remember { ReplyFolds() }
    val disclosures = remember { mutableStateMapOf<String, Boolean>() }
    val newestReply = remember(entries) { entries.lastOrNull { it is ThreadEntry.Reply } as ThreadEntry.Reply? }
    val foldView = folds.observe(
        ReplyFoldInput(ui.sendSignal, running = newestReply?.rows?.completed == false, runId = ui.live?.id),
        newestReply?.rows?.id,
    )
    SideEffect { folds.commit(foldView) }
    val nodes = remember(entries, foldView, folds.version) {
        // Row ids key the list, and two rows under one id crash it: whatever source a repeat
        // came by, only its first copy is drawn.
        TranscriptTimeline.terminate(TranscriptTimeline.flatten(entries) { folds.isOpen(foldView, it) }.distinctBy { it.id })
    }
    val hasContent = nodes.isNotEmpty()
    // What the connection knows that the transcript doesn't: the call a permission request
    // waits on, and how the answered ones went (the chips on their rows).
    val activityLive = remember(ui.pendingPermission, ui.pendingQuestion, ui.pendingPlanApproval, ui.toolApprovals, ui.delegations) {
        ActivityLive(
            awaitingToolCallId = ui.pendingPermission?.parsed?.toolCallId,
            awaitingUser = ui.pendingPermission != null || ui.pendingQuestion != null || ui.pendingPlanApproval != null,
            approvals = ui.toolApprovals,
            delegations = ui.delegations,
        )
    }
    // Goal controls go out on the live connection, gated on what its adapter offers.
    // The actions are a live connection's (they go when it does).
    val currentGoal = remember(entries) { TranscriptTimeline.latestGoalCallId(entries) }
    val goalControl = remember(ui.goalActions, currentGoal) {
        GoalControl(onControl = viewModel::goalControl, actions = ui.goalActions, currentCallId = currentGoal)
    }
    // A fork goes out on the live connection, for an agent that can fork, on a saved conversation.
    val canFork = ui.forkSupported && ui.summary != null
    val forkControl = remember(canFork, ui.isInFlight) {
        ForkControl(onFork = if (canFork) viewModel::forkFrom else null, busy = ui.isInFlight, onBlocked = viewModel::showNotice)
    }
    // The "load older" row sits at the head of the list, so every list index the
    // screen computes from a NODE index has to step over it.
    val headerCount = if (ui.hasOlderTurns) 1 else 0

    // Native stick-to-bottom: auto-follow only while the bottom anchor is in view.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    var stick by remember { mutableStateOf(true) }
    // An explicit ask for the bottom (a send, "jump to latest") that holds until the bottom is
    // reached or the user drags: a scroll already under way when it came — a fling, the jump's
    // own animation — mustn't unpin it as it settles short of the end.
    var repin by remember { mutableStateOf(false) }
    // Re-evaluate "follow" only when a *user* scroll settles — programmatic scrolls
    // and streamed growth must not flip it (that would race the auto-follow off).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (inProgress) return@collect
            val atBottom = isAtBottom
            stick = atBottom || repin
            if (atBottom) repin = false
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) repin = false }
    }
    // A freshly sent message always re-pins to the bottom — stopping a fling still under way,
    // which the follow would otherwise wait out.
    LaunchedEffect(ui.pendingUserTurns.lastOrNull()?.id) {
        if (ui.pendingUserTurns.isEmpty()) return@LaunchedEffect
        stick = true
        repin = true
        if (listState.isScrollInProgress) listState.stopScroll()
    }
    // Follow while pinned: whenever the end of the list is out of view and no one is scrolling,
    // scroll to it. Driven by the layout, not by the update that grew it, so a card unfolding,
    // a diagram landing or the keyboard taking room is followed like a streamed word.
    LaunchedEffect(listState) {
        snapshotFlow { stick && listState.canScrollForward && !listState.isScrollInProgress }
            .collect { behind ->
                if (!behind) return@collect
                try {
                    listState.followToEnd()
                } catch (e: CancellationException) {
                    // A drag outranks the follow and interrupts it; only this effect ending
                    // ends the collection.
                    currentCoroutineContext().ensureActive()
                }
            }
    }
    // A diagram or HTML preview that lands after the first layout grows its row: a reader who
    // was at the bottom stays there (iOS 0893d53). The follow above sees that growth too; this
    // only asks sooner.
    val latestStick by rememberUpdatedState(stick)
    val richBlockGrew: () -> Unit = remember(listState, scope) {
        { if (latestStick) scope.launch { listState.followToEnd() } }
    }
    // Set by a deliberate jump (a transcript link, "jump to latest") so a scroll
    // restore queued before it does not undo it. `stick` alone does not cover
    // these: a link jump explicitly unpins.
    var jumped by remember { mutableStateOf(false) }
    val scrollToId: (String) -> Unit = remember(nodes, headerCount) {
        { id ->
            val idx = nodes.indexOfFirst { it.id == id }
            if (idx >= 0) {
                stick = false // unpin so the stream doesn't immediately yank us back down
                jumped = true
                scope.launch { listState.animateScrollToItem(idx + headerCount) }
            }
        }
    }
    // The navigator's jump: once the target row is drawn (after paging, if it had to).
    LaunchedEffect(ui.scrollToTurn, nodes) {
        val target = ui.scrollToTurn ?: return@LaunchedEffect
        val idx = nodes.indexOfFirst { it.id == target.turnId }
        if (idx >= 0) {
            stick = false
            jumped = true
            // In the screen's scope: consuming the request restarts this effect.
            scope.launch { listState.scrollToItem(idx + headerCount) }
        }
        viewModel.consumeScrollToTurn()
    }
    var showNav by remember { mutableStateOf(false) }
    // A row a fold action wants in view, scrolled to once the fold has drawn it.
    var pendingScroll by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nodes, pendingScroll) {
        val id = pendingScroll ?: return@LaunchedEffect
        val idx = nodes.indexOfFirst { it.id == id }
        if (idx < 0) return@LaunchedEffect
        // Cleared here, which restarts this effect: the scroll runs in the screen's scope, or
        // that restart would cancel it.
        pendingScroll = null
        stick = false
        jumped = true
        scope.launch { listState.animateScrollToItem(idx + headerCount) }
    }
    val latestFoldView by rememberUpdatedState(foldView)
    val foldActions = remember(folds) {
        object : ReplyFoldActions {
            override fun setOpen(replyId: String, open: Boolean) {
                // Opening a reply is reading it: the stream mustn't pull the view away.
                if (open) stick = false
                folds.setOpen(latestFoldView, replyId, open)
            }

            override fun foldBack(replyId: String) {
                folds.setOpen(latestFoldView, replyId, false)
                pendingScroll = "$replyId#header"
            }

            override fun showFailure(replyId: String, rowId: String) {
                folds.setOpen(latestFoldView, replyId, true)
                // A group (keyed as its first call's row), or a goal run, opens onto the
                // failed step it holds; a lone call's card keeps no state here.
                if (rowId.startsWith("tool-") || rowId.startsWith("goal-")) disclosures[rowId] = true
                pendingScroll = rowId
            }
        }
    }

    // ── Reverse infinite scroll over older history ───────────────────────────
    // Ask for the next page once the viewport nears the top, handing the ViewModel
    // the row to keep in place; it comes back on `ui.prepend` with the turns.
    //
    // Gated on `!stick`, because the follow-the-bottom effect above would fight it:
    // the two would take turns paging and yanking the view back down. A list too
    // short to scroll is exempt — it is at the top and the bottom at once, so it
    // would never page at all, and with nothing to scroll there is no fight.
    LaunchedEffect(listState, nodes, headerCount, ui.hasOlderTurns, stick, ui.loadingOlder) {
        if (!ui.hasOlderTurns || ui.loadingOlder) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                // Not `visibleItemsInfo.size >= totalItemsCount` — a partially clipped
                // row still counts as visible, so that reads "fits" for a list that
                // does scroll. Whether either direction can move is the actual question.
                !listState.canScrollForward && !listState.canScrollBackward,
            )
        }.collect { (first, offset, fitsOnScreen) ->
            if (stick && !fitsOnScreen) return@collect
            if (first > OLDER_HISTORY_TRIGGER_ROWS) return@collect
            // The loader row is not a node; while it is the first visible row, the
            // row to keep in place is the one right after it, aligned to the top.
            val nodeIndex = first - headerCount
            jumped = false
            viewModel.loadOlderTurns( // single-flight in the ViewModel
                PrependAnchor(
                    epoch = 0, // assigned by the ViewModel when the page lands
                    rowId = nodes.getOrNull(nodeIndex.coerceAtLeast(0))?.id,
                    rowIndex = nodeIndex.coerceAtLeast(0),
                    rowCount = nodes.size,
                    offset = if (nodeIndex < 0) 0 else offset,
                ),
            )
        }
    }
    // Put the viewport back. Compose does re-anchor by the first visible item's
    // key, but only across the lazy layout's nearest-item range (~130 rows) — a
    // page expands to more rows than that, so the correction misses and everything
    // the user was reading slides off the bottom.
    LaunchedEffect(ui.prepend) {
        val anchor = ui.prepend ?: return@LaunchedEffect
        // Consumed either way: left pending it would replay on a fresh composition
        // (a rotation, a navigation return) and scroll back to a position the user
        // has long left.
        viewModel.consumePrepend()
        // `stick` means the user went to the bottom while the page was in flight —
        // by scrolling there, or by tapping "jump to latest". That is newer intent
        // than the anchor, and the follow-the-bottom effect is about to act on it;
        // restoring here would drag them back up and the two would fight.
        if (stick || jumped) return@LaunchedEffect
        // By id first: exact, and unaffected by anything that arrived at the tail
        // while the page was in flight. By growth otherwise — the id is gone when
        // the prepend re-keyed the run it belonged to.
        val byId = anchor.rowId?.let { id -> nodes.indexOfFirst { it.id == id } } ?: -1
        val idx = if (byId >= 0) byId else anchor.rowIndex + (nodes.size - anchor.rowCount)
        if (idx in nodes.indices) listState.scrollToItem(idx + headerCount, anchor.offset)
    }

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
                        // The agent avatar is the options button (mode / config / folder / branch),
                        // with the session's model, effort and mode beside it.
                        AgentOptionsButton(
                            viewModel = viewModel,
                            agent = ui.agent,
                            isInFlight = ui.isInFlight,
                            sheetOpen = showOptions,
                            conversationId = ui.summary?.id,
                            onClick = { showOptions = true },
                        )
                        // Message navigation: once there is a message to go to.
                        if (!ui.isNew && (ui.window?.hasOlder == true || ui.turns.any { it.role == TurnRole.USER })) {
                            IconButton(onClick = { viewModel.loadOutline(); showNav = true }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.FormatListBulleted,
                                    contentDescription = stringResource(R.string.message_nav_title),
                                    tint = colors.textPrimary,
                                )
                            }
                        }
                        if (ui.summary != null) SessionActionsMenu(ui, viewModel, onDeleted = onBack, onManageTags = onManageTags)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = colors.textPrimary,
                    ),
                )
                // Where the web docks it: over the transcript, since it is about the session as a
                // whole rather than the next message.
                ui.remoteControl?.let { rc ->
                    RemoteControlStrip(
                        info = rc,
                        onTurnOff = viewModel::turnOffRemoteControl,
                        turnRunning = ui.isInFlight,
                    )
                }
            }
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                ui.pendingPermission?.let { p ->
                    PermissionRequestCard(
                        parsed = p.parsed,
                        options = p.options,
                        onRespond = viewModel::respondPermission,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        queued = p.queued,
                    )
                }
                ui.pendingQuestion?.let { q ->
                    AskQuestionCard(
                        questionId = q.questionId,
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
                // What runs beyond the reply — and outlives its turn.
                val runs = remember(ui.asyncTasks, ui.backgroundTasks, ui.delegatedChildren, ui.delegations, ui.live, ui.turns, ui.localTurns) {
                    LiveRuns.collect(
                        asyncTasks = ui.asyncTasks,
                        foreground = runningSubAgents(ui.live),
                        background = ui.backgroundTasks,
                        delegatedChildren = ui.delegatedChildren,
                        launchTitle = { id -> launchCardTitle(ui.localTurns, id) ?: launchCardTitle(ui.turns, id) },
                        delegationBindings = ui.delegations.values,
                    )
                }
                LiveRunsBar(runs, ui.agent.displayName, onStopTask = viewModel::stopAsyncTask, onOpenConversation = onOpenConversation)
                ComposerStatusStrips(
                    retry = ui.apiRetry,
                    failures = ui.sessionFailures,
                    running = ui.isInFlight,
                    onDismissFailures = viewModel::dismissSessionFailures,
                )
                AnimatedVisibility(
                    visible = ui.sendStatus != null && ui.isInFlight,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                ) {
                    SendStatusLine(ui.sendStatus ?: "")
                }
                if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments, onRemove = viewModel::removeAttachment)
                // Notes belong to the turn that is running; once it ends they describe
                // nothing the user can still act on, so they go with it.
                //
                // BOTH conditions, and neither is redundant. `noteTurnOpen` is the narrow
                // one — the server's own turn, not the optimistic `isInFlight` that is set
                // the moment Send is tapped and so also covers connect and attach. Most of
                // that window simply has no turn to steer (the note bounces), but the tail of
                // it does the real damage: once the server arms the turn and before it emits
                // `user_message`, a note is accepted and then wiped unread. `isInFlight` is
                // the broad backstop: several turn-end paths (transcript reconcile,
                // unreconciled-turn promotion) clear only that flag, and leaving a live note
                // button on a finished turn is worse than losing it a moment early.
                val noteChannelLive = ui.noteTurnOpen && ui.isInFlight
                // A note steered into the reply is shown there, as the user's message.
                val steered = ui.live?.segments.orEmpty().mapNotNullTo(HashSet()) { (it as? LiveSegment.Steer)?.noteId }
                val stripNotes = ui.feedbackNotes.filterNot { it.id in steered }
                if (stripNotes.isNotEmpty() && noteChannelLive) FeedbackNotesStrip(stripNotes)
                if (ui.queue.isNotEmpty()) {
                    QueueStrip(
                        items = ui.queue,
                        // While a turn runs the queue waits for it anyway.
                        paused = ui.queuePaused && !ui.isInFlight,
                        onResume = viewModel::resumeQueue,
                        onEdit = { id ->
                            // Back into the composer, after anything already being written there.
                            viewModel.takeQueued(id)?.let { item ->
                                // Queued text is in its wire form; its mention links read as tokens.
                                val restored = viewModel.composer.restore(item.text)
                                draft = if (draft.isBlank()) restored else draft.trimEnd() + "\n" + restored
                            }
                        },
                        onRemove = viewModel::removeQueued,
                    )
                }
                ui.configStale?.takeIf { !it.dismissed }?.let { stale ->
                    ConfigStaleBanner(
                        stale = stale,
                        turnRunning = ui.isInFlight,
                        onReconnect = viewModel::reapplyConfig,
                        onDismiss = viewModel::dismissConfigStale,
                        remoteControlOn = ui.remoteControl != null,
                    )
                }
                // A session that couldn't load takes the composer's place: a send would only
                // fail the same way.
                val loadError = ui.sessionLoadError
                if (loadError != null) {
                    SessionLoadErrorBanner(
                        error = loadError,
                        onReload = viewModel::reloadAfterLoadFailure,
                        onNewSession = loadError.folderId?.let { id -> { onOpenSession(id) } },
                    )
                } else ComposeBar(
                    text = draft,
                    onTextChange = { draft = it },
                    // The `@` mentions leave as the web's Markdown links: from here on (the queue
                    // included) the text is in its wire form.
                    onSend = {
                        viewModel.send(viewModel.composer.encode(draft))
                        draft = ""
                    },
                    isInFlight = ui.isInFlight,
                    onStop = viewModel::cancel,
                    // Mid-turn the bar carries the queue (and note) buttons too; the insert sheet
                    // gives up its slot to keep the field usable on a phone.
                    onPlus = if (ui.isInFlight) null else ({ showInsert = true }),
                    onQueue = if (ui.isInFlight) ({
                        viewModel.send(viewModel.composer.encode(draft))
                        draft = ""
                    }) else null,
                    onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    canSendOverride = ui.attachments.isNotEmpty(),
                    onSendNote = if (ui.supportsNotes && noteChannelLive) {
                        {
                            val sent = draft
                            scope.launch {
                                // Clear only once the note is really with the server, and
                                // only if the composer still holds what was sent — a refusal
                                // (or an edit made while it was in flight) leaves the text
                                // exactly where the user can see it.
                                // Sent in its wire form; compared against what the field showed.
                                if (viewModel.sendNote(viewModel.composer.encode(sent)).await() && draft == sent) draft = ""
                            }
                        }
                    } else {
                        null
                    },
                    noteLandsInstantly = ui.notesLandInstantly,
                    sendingNote = ui.sendingNote,
                    suggestion = ui.promptSuggestion?.takeIf { !ui.isInFlight },
                    suggestions = viewModel.composer,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }

                ui.error != null && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.session_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::load,
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

                else -> CompositionLocalProvider(
                    LocalTimelineScroll provides scrollToId,
                    LocalReplyFoldActions provides foldActions,
                    LocalDisclosures provides disclosures,
                    LocalActivityLive provides activityLive,
                    LocalDelegationTasks provides delegationTasks,
                    LocalOpenConversation provides onOpenConversation,
                    LocalGoalControl provides goalControl,
                    LocalForkControl provides forkControl,
                    LocalRichBlockGrew provides richBlockGrew,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    ) {
                        if (ui.hasOlderTurns) {
                            item(key = "__timeline_older_loader__", contentType = "older-loader") {
                                OlderHistoryRow(loading = ui.loadingOlder)
                            }
                        }
                        items(nodes, key = { it.id }, contentType = { it.contentTypeKey }) { node ->
                            // No item animation: every change lands at once. A fade blinked a
                            // re-keyed row (live → saved, a pending prompt → the saved one) out
                            // and back, left a removed row over its successor, and after any
                            // index snap faded every row on screen in anew ([followToEnd]).
                            // Moves alone slid rows across the ones that land in place — the
                            // reply's text, a row just inserted — as a turn ended or a reply
                            // folded.
                            TimelineRow(
                                marker = node.marker,
                                connectTop = node.connectTop,
                                connectBottom = node.connectBottom,
                                startsGroup = node.startsGroup,
                                rail = node.rail,
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
            // of the transcript up under a band. Shown only while scrolled up — not while
            // following, when growth puts the end out of view for the frame before the follow.
            AnimatedVisibility(
                visible = hasContent && !isAtBottom && !stick,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                JumpToLatestButton {
                    stick = true
                    repin = true
                    jumped = true
                    scope.launch { listState.animateScrollToItem(nodes.size + headerCount) }
                }
            }
        }
    }
    if (showNav) {
        val window = ui.window
        val above = remember(ui.outline, window) {
            val ready = ui.outline as? OutlineState.Ready
            if (ready != null && window != null) MessageNav.outlinePrefix(ready.outline, window) else null
        }
        val entries = remember(above, ui.turns, ui.localTurns, ui.pendingUserTurns) {
            MessageNav.entries(above.orEmpty(), ui.turns + ui.localTurns + ui.pendingUserTurns)
        }
        // The message being read: the one at or above the top of the viewport.
        val current = remember(nodes, headerCount) {
            val top = (listState.firstVisibleItemIndex - headerCount).coerceIn(0, (nodes.size - 1).coerceAtLeast(0))
            (top downTo 0).firstNotNullOfOrNull { i -> nodes.getOrNull(i)?.takeIf { it.content is NodeContent.User }?.id }
        }
        val hasEarlier = window?.hasOlder == true && ui.outlineSupported
        MessageNavSheet(
            entries = entries,
            numbered = window?.hasOlder != true || above != null,
            outline = ui.outline,
            hasEarlier = hasEarlier,
            currentTurnId = current,
            running = ui.isInFlight,
            onRetryEarlier = viewModel::loadOutline,
            onPick = { entry ->
                showNav = false
                viewModel.revealTurn(entry.turnId, entry.turnIndex)
            },
            onDismiss = { showNav = false },
        )
    }
}

/**
 * Head-of-transcript row for the history still on the server. It is a status
 * line, not a button: the page is requested by scrolling near it, and a row that
 * looks tappable but loads on approach reads as broken when it starts by itself.
 */
@Composable
private fun OlderHistoryRow(loading: Boolean) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = colors.textTertiary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(R.string.session_older_history),
            fontSize = 12.sp,
            color = colors.textTertiary,
        )
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

/**
 * The steering notes sent during the running turn, listed just above the composer.
 *
 * Read-only — a note can't be edited or withdrawn once the server has it. What each row
 * really carries is whether the note has *reached* the agent: delivery here is cooperative
 * (on the pull channel the agent picks notes up when it next checks), so "sent" and
 * "received" are different states, and only the second one can still change the turn.
 */
@Composable
private fun FeedbackNotesStrip(notes: List<FeedbackNote>) {
    val colors = CodegTheme.colors
    val scroll = rememberScrollState()
    // Past ~four rows the strip is capped and the newest note lands below the fold — where
    // the user would never see the note they just sent, nor its "received" flip. Follow the
    // tail on every append.
    LaunchedEffect(notes.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated.copy(alpha = 0.92f))
            // A long turn can collect more notes than the composer should give up room for.
            .heightIn(max = 104.dp)
            .verticalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        notes.forEach { note ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeSurface)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Decorative: the trailing label already states the status in words, and
                // announcing it twice is worse than not at all.
                Icon(
                    imageVector = if (note.isDelivered) Icons.Rounded.Check else Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = if (note.isDelivered) colors.accent else colors.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    note.text,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(if (note.isDelivered) R.string.feedback_read else R.string.feedback_waiting),
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/**
 * The messages waiting for the running turn to end, in the order they will go out. [paused]
 * after a failure: they wait for "send now" ([onResume]) instead of going out on their own.
 */
@Composable
private fun QueueStrip(
    items: List<QueuedPrompt>,
    paused: Boolean,
    onResume: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(items.size) { scroll.animateScrollTo(scroll.maxValue) }
    if (paused) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.bgElevated.copy(alpha = 0.92f))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.PauseCircle,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.queue_paused),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.queue_send_now), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated.copy(alpha = 0.92f))
            .heightIn(max = 112.dp)
            .verticalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeSurface)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("#${index + 1}", fontSize = 11.sp, color = colors.textTertiary)
                Text(
                    // Queued text is in its wire form; a mention reads as its label here.
                    ComposerReferenceCodec.foldLinks(item.text).ifEmpty {
                        pluralStringResource(R.plurals.queue_images, item.attachments.size, item.attachments.size)
                    },
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onEdit(item.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.queue_edit),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.queue_remove),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
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

/** A thread's calls, in order, into [this] fold. */
private fun DelegationTaskFold.addCalls(entries: List<ThreadEntry>, bindings: Map<String, DelegationBinding>) {
    for (entry in entries) {
        val rows = (entry as? ThreadEntry.Reply)?.rows ?: continue
        for (part in rows.calls) (part as? RenderPart.Tool)?.let { add(it.vm, bindings) }
    }
}
