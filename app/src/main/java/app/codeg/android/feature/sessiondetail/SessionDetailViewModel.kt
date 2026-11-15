package app.codeg.android.feature.sessiondetail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.SelectorPrefs
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentChoice
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationConnectionInfo
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.feature.sessions.moveOutcomeText
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanApprovalDecision
import app.codeg.android.core.model.PromptInputBlock
import app.codeg.android.core.model.QuickMessage
import app.codeg.android.core.model.SessionStats
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindow
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.conversationMoved
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isStaleConnection
import app.codeg.android.core.model.WorkspaceSearchRequest
import app.codeg.android.feature.sessiondetail.composer.ComposerReferenceCodec
import app.codeg.android.feature.sessiondetail.composer.ComposerSuggestions
import app.codeg.android.feature.sessiondetail.interactive.ParsedPermission
import app.codeg.android.feature.sessiondetail.interactive.PermissionMerge
import app.codeg.android.feature.sessiondetail.interactive.permissionToolCallId
import app.codeg.android.feature.sessiondetail.rendering.ToolApprovals
import app.codeg.android.feature.sessiondetail.live.SessionStream
import app.codeg.android.feature.sessiondetail.live.REASON_CONNECTION_GONE
import app.codeg.android.feature.sessiondetail.live.StreamAttachFailed
import app.codeg.android.feature.sessiondetail.live.asFrameSource
import app.codeg.android.feature.sessiondetail.timeline.endsRound
import app.codeg.android.feature.sessiondetail.timeline.hideInFlightReply
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Turns fetched when a session opens, and per page of older history — both
 * matching the desktop client. A long transcript is no longer transferred whole
 * on every detail fetch; the rest is paged in as the user scrolls up.
 *
 * The server clamps either to [TurnWindow.MAX_TURNS] and aligns a tail's start
 * backward to a user-round boundary — though only within its own walk-back cap,
 * so a window CAN still open mid-reply. See [TurnWindow.Tail].
 */
/** Stop reason recorded for a turn whose `turn_complete` was lost in a reconnect gap. */
private const val STOP_REASON_UNSEEN = "end_turn"

/** What the server puts before a fork's title (`persist_fork_outcome`). */
private const val FORK_TITLE_PREFIX = "[Fork] "

/** Claude Code's own command that turns Remote Control off (`remote_control_command`). */
private const val REMOTE_CONTROL_OFF = "/remote-control off"

/** [verifyUnseenTurn]: transcript checks before leaving the turn to its live events. */
private const val VERIFY_UNSEEN_ATTEMPTS = 5

private const val INITIAL_TAIL_TURNS = 120
private const val OLDER_TURNS_PAGE_SIZE = 120

/** Waits before each post-turn metadata read ([SessionDetailViewModel.syncTurnMetadata]),
 *  each after the previous read: the web's `TURN_METADATA_SYNC_DELAYS_MS`, landing ~1.5s,
 *  4.5s, 9.5s, 17.5s and 30.5s after the turn ends. */
private val TURN_METADATA_SYNC_DELAYS_MS = longArrayOf(1_500L, 3_000L, 5_000L, 8_000L, 13_000L)

/** Transcript polls a turn that ended without a `turn_complete` gets to judge. */
private const val TURN_RECONCILE_ATTEMPTS = 5

/** Tries at a goal-action vocabulary not decided yet, and the first back-off (it doubles). */
private const val GOAL_ACTIONS_RETRIES = 4
private const val GOAL_ACTIONS_RETRY_BASE_MS = 400L

/** Consecutive history pages that add nothing before the loader row stops trying. */
private const val MAX_PAGE_STALLS = 3

/** Status-line copy shown while the agent is thinking (no tool running). Hardcoded to
 *  match the send-flow literal — the transient status micro-copy is intentionally not
 *  localized (see the app's i18n notes). */
private const val STATUS_THINKING = "Thinking…"

/**
 * Drives the session detail screen end to end: loads the persisted transcript,
 * then runs the live ACP lifecycle — resolve/open a connection, attach to the
 * `/ws/events` stream, send a prompt, fold streamed events into a live turn, and
 * reconcile against the server transcript on completion. Faithful port of the
 * core of the iOS `SessionDetailViewModel` (the reconnect-with-backoff and
 * snapshot live-message reconstruction are simplified; see notes).
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val selectorPrefs: SelectorPrefsStore,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val conversationIdArg: Int? = savedStateHandle.get<Int>("id")?.takeIf { it >= 0 }
    /** A new task may be pre-seeded with a folder (e.g. a worktree a branch switch opened). */
    private val folderIdArg: Int? = savedStateHandle.get<Int>("folderId")?.takeIf { it >= 0 }

    private var profile: ServerProfile? = null
    private var client: CodegClient? = null
    private var conversationId: Int? = conversationIdArg
    private var connectionId: String? = null
    private var folder: FolderDetail? = null
    private var allFolders: List<FolderDetail> = emptyList()
    /**
     * Whether the server has started the turn [liveBuilder] stands for. A send creates the
     * builder optimistically, before its prompt reaches the connection. When an agent is
     * running a turn of its own at that moment (Grok's agent-initiated turns, codeg 0.4x),
     * that turn's tail and its `turn_complete(cancelled)` arrive first and must not be
     * taken as this send's. Set by `prompting`, by this send's own `user_message`, or by a
     * reconnect snapshot showing the turn ([snapshotStartsOwnTurn]); a reattach owns the
     * running turn from the start.
     */
    private var ownTurnStarted = true

    /** This send's optimistic user turn: what [verifyUnseenTurn] looks for in the transcript. */
    private var ownPendingTurn: MessageTurn? = null

    /** The [verifyUnseenTurn] poll in flight. */
    private var verifyJob: Job? = null

    /** Whether the session screen is showing. The stream is paused while it isn't. */
    private var screenVisible = true

    /** The live turn is another client's (or an agent's own), observed on the stream rather
     *  than started by a send here. */
    private var observedTurn = false

    /** The next stream snapshot answers [observeIfConnected]'s attach. */
    private var pendingObserve = false

    /** The `clientMessageId` of the latest send. The server stamps it on that turn's
     *  `user_message`, which proves the turn started even when a reconnect snapshot skipped
     *  its `prompting` (see [opensOwnTurn]). */
    private var ownClientMessageId: String? = null
    private var agent: AgentType = AgentType.CLAUDE_CODE
    private var externalId: String? = null
    /**
     * Where the next [openStream] resumes from, when the caller knows better than the stream's
     * own cursor: a looked-up connection's current seq, or 0 to force a snapshot (a fresh
     * connection, or a reattach that seeds from it). Null = the stream's cursor for that
     * connection, if any. Consumed by [openStream].
     */
    private var attachCursor: Long? = null
    /** True once a draft's first send begins — locks the agent/folder pickers. */
    private var hasStartedFirstSend = false
    /** The working tree's current branch, shown + checkmarked in the branch picker. */
    private var currentBranch: String? = null

    private var sendJob: Job? = null
    /** The post-turn transcript reconcile. Tracked so a new send / cancel can cancel it,
     *  preventing an orphaned reconcile from a finished turn wiping a freshly-started one. */
    private var reconcileJob: Job? = null
    /** Revision notes waiting to be sent as a follow-up prompt after a plan-approval
     *  "request changes" (see [answerPlanApproval]), bound to the turn that produced
     *  the approval so no other turn can ever deliver them. */
    private var pendingPlanFollowUp: ParkedPlanNotes? = null
    /** True while a reattach still owes its first snapshot seed. If the socket drops
     *  mid-handshake, the reconnect re-requests the seed instead of streaming blind. */
    private var pendingReattachSeed = false
    private var liveBuilder: LiveTurnBuilder? = null
    private var liveFlushScheduled = false
    /**
     * Where the current turn's round would start in the FULL transcript — global, not
     * window-relative, so a page prepended meanwhile doesn't move it. With local turns
     * unconfirmed it is their batch start, since the server may hold them in a different
     * number of turns than they show here; [turnPriorPrompts] then says how many of the
     * prompts past it are theirs. Set by [captureTurnBaseline].
     */
    private var turnBaseline = 0
    /** Prompts of the unconfirmed local batch that sit past [turnBaseline]. */
    private var turnPriorPrompts = 0
    /**
     * The full-transcript index where [SessionDetailUiState.localTurns] begin, or null while
     * there are none. Set by the hand-off that creates the first of them ([promoteLocally]),
     * cleared with them when a server read takes their place.
     */
    private var batchStart: Int? = null
    /** The fingerprint of the transcript before [batchStart], as the server computes it: what
     *  proves a read from there opens where the batch does ([syncTurnMetadata]). Null when it
     *  can't be known (a pre-0.24.0 server). Pinned and cleared with [batchStart]. */
    private var batchPrefixHash: String? = null
    /** The post-turn metadata read ([syncTurnMetadata]). */
    private var metadataJob: Job? = null
    /** Plan-approval answers awaiting the server; the queue holds for them ([answerPlanApproval]). */
    private var planAnswersInFlight = 0
    /** The live turn last handed off locally ([promoteLocally]). A plan approval answered on it
     *  may only hear back after it ended, and still owes it the revision notes. */
    private var handedOffBuilder: LiveTurnBuilder? = null
    /** Single-flight guard for history paging. */
    private var pagingJob: Job? = null
    /** Consecutive history pages that added nothing; reset by any real prepend. */
    private var pageStalls = 0
    /**
     * Total order over transcript fetches. Every window refresh and history page
     * takes a number before it starts and drops its own response if a later one
     * has since been issued — otherwise a refresh that left before a page can
     * land after it and replace the prepended history with the narrower window
     * it was built from.
     */
    private var fetchGeneration = 0

    private val _ui = MutableStateFlow(SessionDetailUiState(isNew = conversationIdArg == null))
    val ui: StateFlow<SessionDetailUiState> = _ui.asStateFlow()

    /** Mid-turn steering notes. */
    private val notes = SessionNotes(
        scope = viewModelScope,
        ui = _ui,
        client = { client },
        connectionId = { connectionId },
        strings = appContext::getString,
    )

    /** Send a steering note to the running turn. See [SessionNotes.send]. */
    fun sendNote(text: String): Deferred<Boolean> = notes.send(text)

    /** The agent-options sheet's model and the live selector state. */
    private val options = AgentOptionsController(
        scope = viewModelScope,
        ui = _ui,
        selectorPrefs = selectorPrefs,
        host = object : AgentOptionsController.Host {
            override val client: CodegClient? get() = this@SessionDetailViewModel.client
            override val agent: AgentType get() = this@SessionDetailViewModel.agent
            override val folder: FolderDetail? get() = this@SessionDetailViewModel.folder
            override val conversationId: Int? get() = this@SessionDetailViewModel.conversationId
            override val externalId: String? get() = this@SessionDetailViewModel.externalId
            override var connectionId: String?
                get() = this@SessionDetailViewModel.connectionId
                set(value) {
                    this@SessionDetailViewModel.connectionId = value
                }

            override fun forgetConnection() = this@SessionDetailViewModel.forgetConnection()

            override suspend fun connect(c: CodegClient, agent: AgentType, folder: FolderDetail?): String =
                connectWithPreferences(c, agent, folder)
        },
    )

    /** Messages waiting for the running turn to end. */
    private val queue = SendQueueController(
        scope = viewModelScope,
        ui = _ui,
        flushDispatcher = Dispatchers.Main,
        clock = SystemClock::elapsedRealtime,
        held = {
            // Out of sight the stream is paused, and a send would attach it behind the
            // screen's back; the revision notes of a plan answer still in flight must be the
            // next prompt; and a turn whose end went unseen is still being read back — a send
            // now would cancel that and keep only the part that streamed. Each re-arms the
            // flush when it clears.
            !screenVisible || planAnswersInFlight > 0 || reconcileJob?.isActive == true || connectionOps > 0
        },
        sendQueued = { next -> send(next.text, next.attachments, SendOrigin.QUEUE) },
    )

    /**
     * The composer's inline `/`·`$` menu and `@` panel, and the mention tokens the draft holds
     * (the screen encodes them into links on every send, queue and steer).
     */
    val composer = ComposerSuggestions(
        scope = viewModelScope,
        source = object : ComposerSuggestions.Source {
            override fun context() = ComposerSuggestions.Context(client?.baseUrl.orEmpty(), agent, folder?.path)

            override suspend fun fetchCommands(allowProbe: Boolean) = fetchComposerCommands(allowProbe)

            override suspend fun runtimeSkills(agent: AgentType, workspaceRoot: String?) =
                composerClient().listAgentRuntimeSkills(agent, workspaceRoot)

            override suspend fun agents() = composerClient().acpListAgents()

            override suspend fun searchFiles(root: String, query: String, limit: Int, request: WorkspaceSearchRequest) =
                composerClient().searchWorkspaceFiles(root, query, limit, request)

            override suspend fun cancelFileSearch(request: WorkspaceSearchRequest) {
                client?.cancelWorkspaceFileSearch(request)
            }
        },
    )

    private fun composerClient(): CodegClient = client ?: throw IllegalStateException("No server")

    /** The event subscription to [connectionId]. See [streamListener]. */
    private val stream = SessionStream(
        scope = viewModelScope,
        open = { profile?.let { repository.eventStream(it) }?.asFrameSource() },
        listener = object : SessionStream.Listener {
            override fun onSnapshot(snapshot: LiveSessionSnapshot, cause: SessionStream.AttachCause, missedEvents: Boolean) =
                onStreamSnapshot(snapshot, cause, missedEvents)

            override fun onEvent(event: AcpEvent, seq: Long) = applyEvent(event, seq)

            // A replay can't seed a reattach (only a snapshot carries the live message), and a
            // snapshot that could has already done so: either way the seed is no longer owed.
            override fun onAttached(cause: SessionStream.AttachCause) {
                pendingReattachSeed = false
            }

            // The stream follows the connection while the screen is up, between turns too:
            // out-of-turn permission cards, another client's turns and late events all arrive
            // on it. A hidden screen pauses it instead ([setScreenVisible]).
            override fun shouldReconnect(): Boolean = screenVisible

            override fun onReconnecting(attempt: Int) {
                _ui.update { if (it.isInFlight) it.copy(sendStatus = "Reconnecting…") else it }
            }

            // Terminal: the ACP connection itself is gone, so no socket reopen recovers it, and
            // the next send must connect afresh.
            override fun onConnectionGone() {
                connectionId = null
                _ui.update { it.withoutConnectionState() }
                if (_ui.value.isInFlight) reconcileAfterTurn()
            }

            // Reconnecting kept failing: fall back to the transcript so the reply isn't lost.
            override fun onGaveUp() {
                if (_ui.value.isInFlight) reconcileAfterTurn()
            }
        },
    )

    init {
        queue.start()
        load()
    }

    /**
     * Take a fresh tail of this conversation's transcript, and the row with it, in place of what
     * the screen holds: the first read, and the one that shows a fork ([reloadAfterFork]).
     */
    private fun adoptDetail(detail: ConversationDetail) {
        clearBatch()
        agent = detail.summary.agentType
        externalId = detail.summary.externalId
        folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
        currentBranch = detail.summary.gitBranch ?: folder?.gitBranch
        _ui.update {
            it.copy(
                loading = false,
                outlineSupported = detail.userTurnsBeforeOffset != null,
                backgroundTurns = BackgroundOverlays.retire(it.backgroundTurns, detail.transcriptWatermark, detail.uncoveredPrefixMaxTs),
                summary = detail.summary,
                turns = detail.turns,
                localTurns = emptyList(),
                window = detail.window,
                inFlightUserTurnId = detail.inFlightUserTurnId,
                sessionStats = detail.sessionStats,
                agent = detail.summary.agentType,
                folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                folderPath = folder?.path,
                selectedFolderId = folder?.id,
                currentBranch = currentBranch,
                isDraftEditable = false,
                error = null,
            )
        }
        // Reattach when the server reports the turn live — via an explicit
        // in-flight user turn id or the conversation status (iOS parity).
        if (detail.inFlightUserTurnId != null || detail.summary.status.isLive) {
            reattachIfLive()
        } else {
            observeIfConnected()
        }
    }

    fun load(): Job {
        _ui.update { it.copy(loading = true, error = null) }
        return viewModelScope.launch {
            try {
                val p = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                profile = p
                val c = repository.client(p) ?: throw IllegalStateException("Missing token for this server")
                client = c

                allFolders = runCatching { c.listFolders() }.getOrDefault(emptyList())

                val id = conversationId
                if (id != null) {
                    // Only the tail is transferred; older rounds are paged in as the user
                    // scrolls up. A pre-0.24.0 server ignores the selector and answers in
                    // full, which `detail.window == null` then reports.
                    adoptDetail(c.conversationDetail(id, TurnWindow.Tail(INITIAL_TAIL_TURNS)))
                } else {
                    // New task: load the draft pickers' sources (top-level open folders +
                    // installed/enabled agents) and pick sensible defaults — honoring a
                    // preselected folder (e.g. a worktree a branch switch opened).
                    val open = runCatching { c.listOpenFolders() }.getOrDefault(emptyList())
                    val topLevel = FolderVisibility.filterTopLevel(open)
                        .sortedByDescending { it.lastOpenedAt ?: Instant.MIN }
                    // Kept as AgentChoice, not bare types: a custom agent's label exists only on
                    // the server row (its type carries just the registry id).
                    val agents = runCatching { c.acpListAgents() }.getOrDefault(emptyList())
                        .filter { it.available && it.enabled }
                        .sortedBy { it.sortOrder }
                        .distinctBy { it.agentType }
                        .map(AgentChoice::of)
                        // Only reached when the agents call failed or returned nothing —
                        // fall back to the built-ins, the one set we can name offline.
                        .ifEmpty { AgentType.BUILTINS.map(AgentChoice::of) }
                    folder = allFolders.firstOrNull { it.id == folderIdArg }
                        ?: topLevel.firstOrNull()
                        ?: allFolders.maxByOrNull { it.lastOpenedAt ?: Instant.MIN }
                        ?: allFolders.firstOrNull()
                    agent = folder?.defaultAgentType?.takeIf { d -> agents.any { it.type == d } }
                        ?: agents.first().type
                    currentBranch = folder?.gitBranch
                    _ui.update {
                        it.copy(
                            loading = false,
                            agent = agent,
                            folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                            folderPath = folder?.path,
                            selectedFolderId = folder?.id,
                            currentBranch = currentBranch,
                            isDraftEditable = true,
                            availableFolders = topLevel,
                            availableAgents = agents,
                        )
                    }
                }
                // The agent and folder are known now, and they are what the composer's `/`
                // menu lists for.
                composer.screenDidLoad()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage()) }
            }
        }
    }

    // region Send / cancel

    /**
     * Send the composer's draft. While a turn runs — or while earlier messages are still
     * waiting, so this one doesn't overtake them — it joins the queue instead, and goes out
     * once the turn ends ([SendQueueController]).
     */
    fun send(text: String) {
        val trimmed = text.trim()
        val state = _ui.value
        if (trimmed.isEmpty() && state.attachments.isEmpty()) return
        // A move or fork re-points the connection; what is sent meanwhile waits for it.
        if (state.isInFlight || state.queue.isNotEmpty() || connectionOps > 0) {
            queue.enqueue(QueuedPrompt(UUID.randomUUID().toString(), trimmed, state.attachments), front = false, resume = true)
            _ui.update { it.copy(attachments = emptyList()) }
            return
        }
        send(trimmed, state.attachments, SendOrigin.COMPOSER)
    }

    /** Who a send is for, which decides what it consumes and where a bounce puts it back. */
    private enum class SendOrigin {
        /** The composer's draft: consumes its attachments; a bounce queues it at the tail. */
        COMPOSER,

        /** A queued message: a bounce puts it back at the head, ahead of the ones behind it. */
        QUEUE,

        /** A prompt the app itself wrote (today: the revision notes from a plan-approval
         *  "request changes", which Grok expects as the very next turn). Never consumes the
         *  composer's attachments, so images the user was staging survive; a bounce puts it
         *  at the head, since it has to come first. */
        GENERATED,
    }

    private fun send(text: String, attachments: List<Attachment>, origin: SendOrigin) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || _ui.value.isInFlight) return

        // Supersede any post-turn reconcile still running for the previous turn so it can't
        // wipe the turn we're about to start (its identity guard is the backstop).
        reconcileJob?.cancel()
        // A previous turn that finished / cancelled / errored but was never reconciled into
        // `turns` still lives in `liveBuilder`; fold it in before we reuse the live slot so
        // its (partial) reply isn't dropped by this send. Mirrors iOS `send()`.
        liveBuilder?.takeIf { !it.isStreaming && !it.isEmpty }?.let { promoteLocally() }

        // A draft's first send locks the agent/folder pickers (the conversation is
        // about to be created against them).
        val wasNew = conversationId == null
        if (wasNew) hasStartedFirstSend = true

        // Optimistic user turn: text (if any) then image blocks.
        val userTurn = MessageTurn(
            id = "pending-${UUID.randomUUID()}",
            role = TurnRole.USER,
            blocks = buildList {
                if (trimmed.isNotEmpty()) add(ContentBlock.Text(trimmed))
                attachments.forEach { add(ContentBlock.Image(it.imageData)) }
            },
            timestamp = Instant.now(),
        )
        val builder = LiveTurnBuilder()
        liveBuilder = builder
        // Not this send's turn yet: whatever the connection streams until the server starts
        // it (`prompting`) belongs to the turn before. See [ownTurnStarted].
        ownTurnStarted = false
        observedTurn = false
        ownPendingTurn = userTurn
        verifyJob?.cancel()
        captureTurnBaseline()
        // Steering notes belong to the turn that was running when they were written; the
        // server drops them on this send's `user_message` and so do we. `isInFlight` above
        // is optimistic — it covers connect and attach, during which the server has no turn
        // to steer — so the note affordance stays shut until the server says otherwise.
        notes.clear()
        _ui.update { it.copy(noteTurnOpen = false) }
        _ui.update {
            it.copy(
                pendingUserTurns = it.pendingUserTurns + userTurn,
                sendSignal = it.sendSignal + 1,
                live = builder.snapshot(),
                liveFromReattach = false,
                isInFlight = true,
                sendStatus = "Connecting…",
                notice = null,
                restoreDraft = null,
                attachments = if (origin == SendOrigin.COMPOSER) emptyList() else it.attachments,
                isDraftEditable = false,
            )
        }
        sendJob = viewModelScope.launch { runSend(trimmed, userTurn, attachments, wasNew, origin) }
    }

    private suspend fun runSend(
        text: String,
        userTurn: MessageTurn,
        attachments: List<Attachment>,
        createdConversation: Boolean,
        origin: SendOrigin,
    ) {
        val c = client ?: return
        // One id for both attempts so the server dedupes if the first prompt actually landed.
        val clientMessageId = UUID.randomUUID().toString()
        // The server echoes it as this turn's `user_message` id, which proves the turn is ours.
        ownClientMessageId = clientMessageId
        // The EXACT conversation this send created up front (a first send), so a rollback deletes
        // that row and not whatever conversationId happens to be by the time the failure lands.
        var createdId: Int? = null
        try {
            ensureConversationCreated(text.ifEmpty { "Image" })
            if (createdConversation) createdId = conversationId
            try {
                attemptSend(c, text, attachments, clientMessageId, freshConnection = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A stale connection — a reused id the server has since dropped, or an attach
                // the server rejected — can't carry the prompt. Drop it, connect fresh, retry
                // ONCE. A second failure falls through to the outer catch (discard + notice).
                val moved = (e as? ApiError)?.conversationMoved
                if (e is StreamAttachFailed || (e is ApiError && e.isStaleConnection)) {
                    closeStream()
                    connectionId = null
                    attemptSend(c, text, attachments, clientMessageId, freshConnection = true)
                } else if (moved != null && repointFolder(c, moved.folderId)) {
                    // The session was moved to another project after this connection was
                    // started; the server refuses to run the turn in the old directory. Follow
                    // it and connect fresh there — same single retry as a stale connection.
                    closeStream()
                    connectionId = null
                    attemptSend(c, text, attachments, clientMessageId, freshConnection = true)
                } else throw e
            }
            _ui.update { if (it.isInFlight) it.copy(sendStatus = "Thinking…") else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.TurnInProgress) {
            // Another client's turn holds the connection. Not a failure: the message waits
            // for that turn like any other queued one. (A conversation this send just created
            // can't be busy, but if it were there'd be nothing to queue it on.)
            if (createdConversation) {
                discardOptimistic(userTurn, text, attachments, origin, createdConversation, createdId, notice = "A turn is already running on this session.")
            } else {
                bounceToQueue(userTurn, QueuedPrompt(UUID.randomUUID().toString(), text, attachments), origin)
            }
        } catch (e: Exception) {
            // A resume whose connection was gone before the stream could attach — twice: the
            // server ends a connection whose `session/load` failed for good, and announces why
            // only on that connection's own stream, which nothing could be listening to yet.
            // Say it the way the banner says a reported one, and keep the draft.
            val unloaded = e is StreamAttachFailed && e.reason == REASON_CONNECTION_GONE && externalId != null && !createdConversation
            if (unloaded) {
                _ui.update {
                    it.copy(
                        sessionLoadError = it.sessionLoadError ?: SessionLoadErrorUi(
                            message = appContext.getString(R.string.session_load_unconfirmed, agent.displayName),
                            command = null,
                            folderId = folder?.id,
                        ),
                    )
                }
                discardOptimistic(userTurn, text, attachments, origin, createdConversation, createdId, notice = null)
            } else {
                discardOptimistic(userTurn, text, attachments, origin, createdConversation, createdId, notice = e.displayMessage())
            }
        }
    }

    /**
     * Point this session at the folder it was moved to ([movedTo], from the server's refusal;
     * re-read from the conversation when the message didn't name one). Returns whether the
     * folder actually changed — if not, a retry would only be refused again.
     */
    private suspend fun repointFolder(c: CodegClient, movedTo: Int?): Boolean {
        val target = movedTo ?: conversationId?.let { id ->
            orNull { c.conversationDetail(id, TurnWindow.Tail(1)).summary.folderId }
        } ?: return false
        if (target == folder?.id) return false
        val folders = orNull { c.listFolders() } ?: allFolders
        val next = folders.firstOrNull { it.id == target } ?: return false
        allFolders = folders
        folder = next
        currentBranch = next.gitBranch
        _ui.update {
            it.copy(
                folderName = FolderVisibility.displayName(next, folders),
                folderPath = next.path,
                selectedFolderId = next.id,
                currentBranch = next.gitBranch,
            )
        }
        return true
    }

    /** One send attempt: resolve (or force-fresh) a connection, attach, then prompt. */
    private suspend fun attemptSend(
        c: CodegClient,
        text: String,
        attachments: List<Attachment>,
        clientMessageId: String,
        freshConnection: Boolean,
    ) {
        val conn = if (freshConnection) connectFresh(c) else resolveConnection(c)
        connectionId = conn
        openStream(conn)
        // Start reading the note channel now, but do NOT assume the answer is final:
        // `acp_connect` returns as soon as a FRESH agent process is registered, with its
        // `initialize` — which is what writes the feedback flags — still in flight. A read
        // that beats it comes back "no channel"; the `prompting` self-heal below re-asks.
        notes.sync(conn, hydrate = false)
        c.prompt(
            connectionId = conn,
            blocks = buildList {
                if (text.isNotEmpty()) add(PromptInputBlock.Text(text))
                attachments.forEach { add(it.promptBlock) }
            },
            folderId = folder?.id,
            conversationId = conversationId,
            clientMessageId = clientMessageId,
        )
    }

    /** Force a brand-new ACP connection, bypassing any cached/looked-up id and resetting
     *  the event cursor so the fresh stream is seeded from its own snapshot (no stale
     *  `since_seq` from the connection we just abandoned). */
    private suspend fun connectFresh(c: CodegClient): String {
        attachCursor = 0
        return connectWithPreferences(c)
    }

    /** `acp_connect` for a specific agent/folder (defaulting to the session's current ones),
     *  carrying that agent's remembered mode/config (per-agent [SelectorPrefsStore]) so the
     *  server applies them before reporting state. The explicit [connectAgent]/[connectFolder]
     *  let the options path pin the context captured before any suspension, so a draft
     *  agent/folder switch mid-connect can't retarget the connection. web/iOS parity. */
    private suspend fun connectWithPreferences(
        c: CodegClient,
        connectAgent: AgentType = agent,
        connectFolder: FolderDetail? = folder,
    ): String {
        val prefs = runCatching { selectorPrefs.prefs(connectAgent) }.getOrNull() ?: SelectorPrefs()
        return c.connect(
            agentType = connectAgent,
            workingDir = connectFolder?.path,
            sessionId = externalId,
            preferredModeId = prefs.modeId,
            preferredConfigValues = prefs.configValues?.takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun ensureConversationCreated(firstPrompt: String) {
        if (conversationId != null) return
        val c = client ?: return
        val f = folder ?: throw IllegalStateException("No folder available to start a task")
        // Mention links fold to their labels first, as the web's title seed does — never a raw
        // `[README.md](file://…)`, nor one cut in half.
        val id = c.createConversation(f.id, agent, title = ComposerReferenceCodec.foldLinks(firstPrompt).take(60))
        conversationId = id
        _ui.update { it.copy(isNew = false) }
        // The Chats list holds its own copy of the rows: tell it there's a new one (iOS
        // `ensureConversationCreated` does the same), or it only shows up on a manual refresh.
        repository.notifyConversationsChanged()
    }

    private suspend fun resolveConnection(c: CodegClient): String {
        connectionId?.let { return it }
        val convId = conversationId
        if (convId != null) {
            runCatching { c.findConnection(convId, externalId, agent) }.getOrNull()?.let { info ->
                attachCursor = info.eventSeq
                return info.connectionId
            }
        }
        return connectWithPreferences(c)
    }

    /** Attach the event stream to [conn] and suspend until the server confirms the
     *  subscription (or [SessionStream]'s safety timeout: [StreamAttachFailed]).
     *  [seedOnAttach] rebuilds an in-flight turn from the attach snapshot (reattach). */
    private suspend fun openStream(conn: String, seedOnAttach: Boolean = false) {
        // Already streaming this connection (kept attached since an earlier turn, or observing
        // it since the screen opened): a send needs nothing more. A reattach still re-attaches,
        // for the snapshot it seeds from.
        if (!seedOnAttach && attachCursor == null && stream.isAttachedTo(conn)) return
        options.resetSelectorsForConnection(conn)
        pendingReattachSeed = seedOnAttach
        val since = when (val cursor = attachCursor) {
            null -> stream.cursorFor(conn)
            0L -> null
            else -> cursor
        }
        attachCursor = null
        stream.attach(conn, since)
    }

    /** A snapshot answering an attach ([SessionStream.AttachCause.ATTACH]) or a silent
     *  re-attach after a drop ([SessionStream.AttachCause.RECONNECT]). */
    private fun onStreamSnapshot(snapshot: LiveSessionSnapshot, cause: SessionStream.AttachCause, missedEvents: Boolean) {
        val isReconnect = cause == SessionStream.AttachCause.RECONNECT
        // Owed until a snapshot seeds it — so a socket that dropped mid-handshake has the
        // reconnect's snapshot seed the reattach instead of streaming blind.
        val seed = pendingReattachSeed
        // A reconnect that fell back to a snapshot may have skipped this send's `prompting`,
        // which is what opens [ownTurnStarted]. Without this, the turn's content and its
        // `turn_complete` would be ignored for good.
        val builder = liveBuilder
        if (builder != null && !ownTurnStarted && snapshotStartsOwnTurn(snapshot, isReconnect)) {
            ownTurnStarted = true
            // The server cleared its live message when this turn started, so what it holds now
            // is this turn's, which the closed gate dropped.
            if (builder.isEmpty) {
                builder.seedFrom(snapshot)
                scheduleLiveEmit()
            }
        }
        // Before any early return below: the snapshot's selectors are this connection's
        // current ones whichever branch we take next, and an idle connection is exactly when
        // an open sheet has no other source.
        options.adoptSnapshotSelectors(snapshot)
        adoptSnapshotConnectionState(snapshot)
        if (pendingObserve && cause == SessionStream.AttachCause.ATTACH) {
            pendingObserve = false
            // A turn that started between the transcript load and this attach is shown as it
            // runs, like any other observed turn.
            if (liveBuilder?.isStreaming != true && snapshotTurnRunning(snapshot)) {
                beginObservedTurn()
                showSnapshotPrompt(snapshot)
                seedLiveFromSnapshot(snapshot)
            }
            // Unlike a send's pre-prompt handshake, this snapshot IS the session's current
            // state: a permission card waiting out of turn has to show.
            restorePending(snapshot)
            return
        }
        if (seed && !seedLiveFromSnapshot(snapshot)) {
            // Idle connection — nothing in flight; abort the reattach. Deliberately does NOT
            // surface the snapshot's `last_error` here: an idle snapshot cannot say whether
            // that failure is the last turn's or the previous one's — see [applySnapshotError].
            abortIdleReattach(attached = true)
            return
        }
        // Only adopt a snapshot's pending permission/question on a reattach or a mid-turn
        // reconnect — the initial send handshake snapshot is pre-prompt state and could
        // surface a stale card (iOS skips it there).
        //
        // A reconnect first settles the live turn against the snapshot: ending a turn clears
        // the cards, and the snapshot's are restored after it.
        if (isReconnect && !seed) recoverFromReconnectSnapshot(snapshot, missedEvents)
        if (seed || isReconnect) restorePending(snapshot)
    }

    /**
     * A silent re-attach (a socket drop, or the screen coming back) was answered with a
     * snapshot: the gap was too wide to replay, so the events in it are gone for good. The
     * snapshot is the only account of what happened meanwhile.
     */
    private fun recoverFromReconnectSnapshot(snapshot: LiveSessionSnapshot, missedEvents: Boolean) {
        val builder = liveBuilder?.takeIf { it.isStreaming }
        val recovery = reconnectRecovery(
            snapshot,
            turnRunning = builder != null,
            ownTurnStarted = ownTurnStarted,
            missedEvents = missedEvents,
        )
        when (recovery) {
            ReconnectRecovery.RESEED -> {
                builder?.reseedFrom(snapshot)
                emitLiveNow()
            }
            // The turn finished while nothing was listening, so its `turn_complete` is among
            // the lost events. End it the way that event would have: the reconcile fetches
            // the reply as persisted.
            ReconnectRecovery.ENDED_UNSEEN -> finalizeTurn(STOP_REASON_UNSEEN, unseen = true)
            ReconnectRecovery.VERIFY -> verifyUnseenTurn()
            ReconnectRecovery.OBSERVE -> {
                beginObservedTurn()
                showSnapshotPrompt(snapshot)
                liveBuilder?.seedFrom(snapshot)
                emitLiveNow()
            }
            // A whole turn may have come and gone in the gap (another client's, the agent's
            // own) with nothing running here to finalize: the transcript is the only record.
            ReconnectRecovery.REFRESH -> reconcileAfterMissedLive()
            ReconnectRecovery.NONE -> Unit
        }
    }

    fun cancel() {
        val builder = liveBuilder
        if (builder != null) {
            builder.isStreaming = false
            if (builder.isEmpty) {
                builder.error = LiveTurnError(appContext.getString(R.string.live_cancelled))
                builder.cancelledLocally = true
            }
            emitLiveNow()
        }
        sendJob?.cancel()
        reconcileJob?.cancel()
        // The stream stays: the server answers the cancel with the turn's `turn_complete`,
        // which reconciles it, and the connection lives on for the next turn.
        val conn = connectionId
        if (conn != null) viewModelScope.launch { runCatching { client?.cancel(conn) } }
        clearInteractivePrompts()
    }

    // endregion

    // region Streaming

    private fun reattachIfLive() {
        val c = client ?: return
        val convId = conversationId ?: return
        viewModelScope.launch {
            // The live ACP connection may not be discoverable the instant the turn is
            // reported live; retry a few times with a short backoff (iOS parity).
            var info: ConversationConnectionInfo? = null
            for (attempt in 0 until 4) {
                info = runCatching { c.findConnection(convId, externalId, agent) }.getOrNull()
                if (info != null) break
                delay(400L * (attempt + 1))
            }
            val found = info ?: run {
                // Server said live but no connection surfaced — the turn likely just
                // finished. Refetch so we show its final persisted state.
                reconcileAfterMissedLive()
                return@launch
            }
            connectionId = found.connectionId
            // Attach with NO since_seq so the server sends a full snapshot to seed the
            // in-flight turn from (a since_seq makes it replay events instead). The
            // snapshot's eventSeq becomes the resume point for any later mid-turn reconnect.
            attachCursor = 0
            // Rebuild the in-flight turn from the attach snapshot (seedOnAttach) and keep
            // streaming; the snapshot's live message + active tools are seeded on arrival.
            val builder = LiveTurnBuilder()
            liveBuilder = builder
            // Reattaching to the running turn: what streams IS that turn.
            ownTurnStarted = true
            observedTurn = true
            captureTurnBaseline()
            _ui.update { it.copy(isInFlight = true, sendStatus = "Reattaching…", live = builder.snapshot()) }
            try {
                openStream(found.connectionId, seedOnAttach = true)
                // Attaching to someone else's running turn: pull the notes already on it too,
                // since their one-shot `feedback_submitted` events fired before we were here.
                notes.sync(found.connectionId, hydrate = true)
            } catch (e: StreamAttachFailed) {
                // The live connection vanished before we could attach — the turn has ended;
                // clear the placeholder and refetch the final transcript.
                abortIdleReattach(attached = false)
            }
        }
    }

    /**
     * This send's turn hasn't been seen to start, and a reconnect snapshot shows the
     * connection idle. Either the prompt is still on its way or queued (the server buffers a
     * prompt while the agent initializes, reporting `connected` meanwhile), or the whole turn
     * ran and ended while the stream was paused. The snapshot can't tell these apart; the
     * transcript can.
     *
     * Polls it a few times for this send's prompt past the turn's baseline ([sentPromptAt]),
     * with the transcript's last round ended, and only then ends the turn, through the usual reconcile. Anything else leaves
     * the turn alone: if it starts, its live events open it as usual, which also ends this
     * poll.
     */
    private fun verifyUnseenTurn() {
        val c = client ?: return
        val id = conversationId ?: return
        val builder = liveBuilder ?: return
        val sent = ownPendingTurn ?: return
        val baseline = turnBaseline
        val priorPrompts = turnPriorPrompts
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            repeat(VERIFY_UNSEEN_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 300L else 1_000L)
                if (liveBuilder !== builder || ownTurnStarted || !builder.isStreaming) return@launch
                // Read from the baseline on: a tail could miss the prompt entirely behind one
                // long round (the server's window alignment walks back only so far).
                val fromBaseline = orNull { c.conversationDetail(id, TurnWindow.FromIndex(baseline)) } ?: return@repeat
                if (!sentPromptAt(fromBaseline, baseline, sent, priorPrompts)) return@repeat
                // The window is capped, so it may stop short of the end; the round's end is
                // then read off the tail.
                val ended = if (reachesEnd(fromBaseline)) {
                    endsRound(fromBaseline.turns)
                } else {
                    orNull { c.conversationDetail(id, TurnWindow.Tail(1)) }?.let { endsRound(it.turns) } == true
                }
                if (liveBuilder !== builder || ownTurnStarted || !builder.isStreaming) return@launch
                if (ended) {
                    finalizeTurn(STOP_REASON_UNSEEN, unseen = true)
                    return@launch
                }
            }
        }
    }

    /**
     * Attach to the session's connection, if the server has one, without starting anything:
     * no agent is spawned for a session that is merely being looked at. The stream then
     * carries what happens on it while the screen is up — a permission card waiting out of
     * turn, a turn another client starts.
     */
    private fun observeIfConnected() {
        val c = client ?: return
        val convId = conversationId ?: return
        viewModelScope.launch {
            val info = orNull { c.findConnection(convId, externalId, agent) } ?: return@launch
            // A send got there first and owns the connection now.
            if (connectionId != null || _ui.value.isInFlight) return@launch
            connectionId = info.connectionId
            attachCursor = 0
            pendingObserve = true
            try {
                openStream(info.connectionId)
            } catch (e: StreamAttachFailed) {
                pendingObserve = false
                // Superseded means a send took the stream over for this same connection.
                if (e.reason != SessionStream.REASON_SUPERSEDED && connectionId == info.connectionId) {
                    connectionId = null
                }
            }
        }
    }

    /**
     * Start showing a turn this client didn't send: another client's, or one the agent
     * started itself. Mirrors what a send sets up, minus the optimistic prompt.
     */
    private fun beginObservedTurn() {
        reconcileJob?.cancel()
        // The previous turn ended but was never reconciled: fold it in before reusing the slot.
        liveBuilder?.takeIf { !it.isStreaming && !it.isEmpty }?.let { promoteLocally() }
        val builder = LiveTurnBuilder()
        liveBuilder = builder
        ownTurnStarted = true
        observedTurn = true
        captureTurnBaseline()
        notes.clear()
        _ui.update {
            it.copy(
                live = builder.snapshot(),
                liveFromReattach = false,
                isInFlight = true,
                sendStatus = STATUS_THINKING,
            )
        }
    }

    /**
     * Show the prompt of a turn observed from a snapshot: its `user_message` fired before
     * this client was listening, and the transcript loaded earlier may not hold it yet.
     * Skipped when the loaded transcript has it already (stamped with the broadcast id).
     */
    private fun showSnapshotPrompt(snapshot: LiveSessionSnapshot) {
        val message = snapshot.pendingUserMessage ?: return
        val turn = observedUserTurn(message) ?: return
        _ui.update { st ->
            if (st.turns.any { it.id == message.messageId } || st.pendingUserTurns.any { it.id == turn.id }) st
            else st.copy(pendingUserTurns = st.pendingUserTurns + turn)
        }
    }

    /** Show or hide the session screen: a hidden one keeps no socket open, and picks the
     *  stream back up from its cursor when shown again. */
    fun setScreenVisible(visible: Boolean) {
        if (visible == screenVisible) return
        screenVisible = visible
        if (visible) stream.resume() else stream.pause()
        if (visible) queue.schedule()
    }

    /** The connection the connection-scoped state (async tasks, failures) was last seeded for,
     *  and the newest event seq applied on it — what dates a later snapshot against them. */
    private var connectionStateOwner: String? = null
    private var appliedSeq: Long = 0
    /** The agent session that connection runs, to tell a fork's `session_started` from a replay. */
    private var connectionSessionId: String? = null
    /** The latest session-level `error` text since the turn started ([AcpErrorRoute.Kind.SESSION]). */
    private var lastSessionError: String? = null

    /**
     * An `error` event, routed by its code ([AcpErrorRoute]). It never ends the turn: that is
     * `turn_complete`'s job, or `status_changed: error`'s when the process dies — ending it here
     * would drop the rest of a reply that runs on past a dropped image or a failed mode switch.
     */
    private fun onAcpError(event: AcpEvent.Error) {
        val decision = AcpErrorRoute.decide(event.code, event.message)
        val text = decision.text.trim()
        if (text.isEmpty()) return
        if (decision.kind == AcpErrorRoute.Kind.SESSION) lastSessionError = text
        val builder = liveBuilder?.takeIf { ownTurnStarted && it.isStreaming }
        if (decision.failsTurn && builder != null) {
            // One account per turn: the agent's own words win over codeg's verdict, whichever
            // comes first; otherwise the later account replaces the earlier.
            if (builder.error?.fromAgent != true || decision.fromAgent) {
                builder.error = LiveTurnError(text, event.details?.trim()?.ifEmpty { null }, fromAgent = decision.fromAgent)
                emitLiveNow()
            }
            return
        }
        if (decision.kind == AcpErrorRoute.Kind.TRANSCRIPT) return
        _ui.update { it.copy(notice = text) }
    }

    private fun applyEvent(event: AcpEvent, seq: Long) {
        if (seq > appliedSeq) appliedSeq = seq
        // The server starts this send's turn with `prompting`. Until then the live builder is
        // only optimistic, and what arrives is the previous turn's tail, so none of its content
        // is folded in.
        if (opensOwnTurn(event, ownClientMessageId)) ownTurnStarted = true
        // A turn starting with none running here is another client's, or the agent's own:
        // show it as it runs instead of only after the fact.
        if (liveBuilder?.isStreaming != true && event is AcpEvent.StatusChanged &&
            event.status == ConnectionStatus.PROMPTING
        ) {
            beginObservedTurn()
        }
        applyConnectionState(event)
        // Only a running turn takes content: what arrives after it ended (late frames after a
        // cancel, a turn not yet observed) must not grow a finished reply.
        val builder = liveBuilder?.takeIf { ownTurnStarted && it.isStreaming }
        when (event) {
            // `append` drops a subagent-attributed chunk and reports false; only then is
            // there nothing to redraw. A null builder (no live turn) keeps the emit it
            // has always had.
            is AcpEvent.ContentDelta -> if (builder?.append(event) != false) scheduleLiveEmit()
            is AcpEvent.Thinking -> if (builder?.append(event) != false) scheduleLiveEmit()
            is AcpEvent.ToolCall -> {
                builder?.upsertToolCall(event.id, event.title, event.kind, event.status, event.rawInput, event.rawOutput, event.content, event.meta)
                refreshPendingPermission(event.id)
                _ui.update { if (it.isInFlight) it.copy(sendStatus = runningStatus()) else it }
                scheduleLiveEmit()
            }
            is AcpEvent.ToolCallUpdate -> {
                builder?.updateToolCall(event.id, event.title, event.status, event.rawInput, event.rawOutput, event.content, event.append, event.meta)
                refreshPendingPermission(event.id)
                // A tool finishing must revert the status line from "Running <tool>…" back to
                // "Thinking…" (or to the next still-running tool); iOS recomputes on every update.
                _ui.update { if (it.isInFlight) it.copy(sendStatus = runningStatus()) else it }
                scheduleLiveEmit()
            }
            is AcpEvent.PlanUpdate -> { builder?.updatePlan(event.entries); scheduleLiveEmit() }
            is AcpEvent.UsageUpdate -> _ui.update {
                it.copy(
                    sessionStats = (it.sessionStats ?: SessionStats()).copy(
                        contextWindowUsedTokens = event.used.toInt(),
                        contextWindowMaxTokens = event.size.toInt(),
                    ),
                )
            }
            is AcpEvent.ConversationLinked -> adoptLinkedConversation(event.conversationId)
            is AcpEvent.StatusChanged -> when (event.status) {
                // A session that failed to load says why, not just that the connection died; so
                // does the session's own last error, which the server sends just before.
                ConnectionStatus.ERROR -> failLive(
                    _ui.value.sessionLoadError?.message ?: lastSessionError
                        ?: appContext.getString(R.string.live_agent_connection_errored),
                )
                ConnectionStatus.PROMPTING -> {
                    // A new turn: the last error belonged to the one before (the server clears
                    // its own `last_error` here too).
                    lastSessionError = null
                    // Self-heal the note channel. The read in `attemptSend` can beat a fresh
                    // agent's `initialize`, and would then have latched "no channel" for the
                    // whole turn. `prompting` is strictly after session setup, so the flags
                    // are written by now. Only re-asked while the answer is still no, so this
                    // can't loop — a yes is narrowed from elsewhere (a revoked native channel
                    // comes back through the submit result), never re-widened here.
                    connectionId?.takeIf { !_ui.value.supportsNotes }
                        ?.let { notes.sync(it, hydrate = false) }
                    // Deliberately does NOT open the note channel. The connection loop emits
                    // this immediately BEFORE `user_message` (connection.rs), and that event
                    // is what clears the turn's notes — so a note sent in between is accepted
                    // and then wiped unread. `user_message` is the only safe opener; a turn
                    // this client didn't see start is opened from the attach snapshot instead.
                    _ui.update {
                        // The agent is actively prompting — reflect it as "Thinking…" unless a tool is
                        // currently running (that status is more specific). Mirrors the iOS .prompting.
                        if (it.isInFlight && liveBuilder?.activeToolTitle() == null) it.copy(sendStatus = STATUS_THINKING) else it
                    }
                }
                // .connecting is a no-op here: the status line only renders while in-flight, and
                // by then we're already past connecting (iOS shows it only from its idle state).
                else -> Unit
            }
            // A `turn_complete` before this send's turn started ends the turn before it: Grok's
            // own agent-initiated turn, which the server closes as `cancelled` just ahead of
            // starting a prompt queued behind it. Finalizing on it would end THIS turn as
            // cancelled and close the stream before its reply arrives.
            is AcpEvent.TurnComplete -> if (liveBuilder == null || ownTurnStarted) finalizeTurn(event.stopReason)
            is AcpEvent.Error -> onAcpError(event)
            is AcpEvent.PermissionRequest -> {
                val pending = permissionUi(event.requestId, event.toolCall, event.options, event.queued)
                _ui.update { it.copy(pendingPermission = pending) }
            }
            // A request queued up behind the card on screen. No card up, nothing to annotate:
            // a late depth after a drain must not resurrect one.
            is AcpEvent.PermissionQueueDepth -> _ui.update {
                val pending = it.pendingPermission
                if (pending == null || pending.queued == event.depth) it else it.copy(pendingPermission = pending.copy(queued = event.depth))
            }
            is AcpEvent.PermissionResolved -> _ui.update {
                if (it.pendingPermission?.requestId == event.requestId) it.copy(pendingPermission = null) else it
            }
            is AcpEvent.ToolApproval -> _ui.update {
                val merged = ToolApprovals.merge(it.toolApprovals, listOf(event.approval))
                if (merged === it.toolApprovals) it else it.copy(toolApprovals = merged)
            }
            is AcpEvent.QuestionRequest -> _ui.update {
                it.copy(pendingQuestion = PendingQuestionUi(event.questionId, event.questions))
            }
            is AcpEvent.QuestionResolved -> _ui.update {
                if (it.pendingQuestion?.questionId == event.questionId) it.copy(pendingQuestion = null) else it
            }
            // Grok finished planning and is blocked until the user decides. The card is
            // pinned above the compose bar (like the permission/question ones), so no
            // scroll nudge is needed — it can't be scrolled out of view.
            is AcpEvent.PlanApprovalRequest -> _ui.update {
                it.copy(pendingPlanApproval = PendingPlanApprovalUi(event.approvalId, event.toolCallId, event.planMarkdown))
            }
            is AcpEvent.PlanApprovalResolved -> _ui.update {
                if (it.pendingPlanApproval?.approvalId == event.approvalId) it.copy(pendingPlanApproval = null) else it
            }
            // Any client's note, including the echo of one this client just sent — deduped by
            // id against the note the submit call already returned.
            is AcpEvent.FeedbackSubmitted -> {
                notes.onSubmitted(event.note)
                // A note born delivered went straight into the running turn (native steering):
                // the agent takes it in here, so the reply splits around it, as the transcript
                // will show it. A pending note waits for the agent to check; it stays a note.
                val builder = liveBuilder?.takeIf { it.isStreaming && ownTurnStarted }
                if (event.note.isDelivered && builder?.appendSteer(event.note.id, event.note.text) == true) emitLiveNow()
            }
            is AcpEvent.FeedbackConsumed -> notes.onConsumed(event.ids)
            // A new turn — from this client or another. Notes are turn-scoped: the server
            // clears them here, so leaving ours on screen would attach the previous turn's
            // steering to a turn that never received it. This is also the earliest point a
            // note is safe to send: the server clears `feedback` when it emits this, so
            // anything submitted before it would be accepted and then wiped unread.
            is AcpEvent.UserMessage -> {
                notes.clear()
                _ui.update { it.copy(noteTurnOpen = true) }
                // Another client's prompt: show it above the reply it starts. The transcript
                // takes over when the turn is reconciled.
                if (observedTurn && liveBuilder?.isStreaming == true && event.messageId != ownClientMessageId) {
                    observedUserTurn(event)?.let { turn ->
                        _ui.update { st ->
                            if (st.pendingUserTurns.any { it.id == turn.id }) st
                            else st.copy(pendingUserTurns = st.pendingUserTurns + turn)
                        }
                    }
                }
            }
            is AcpEvent.SessionConfigOptions, AcpEvent.SelectorsReady,
            is AcpEvent.SessionModes, is AcpEvent.ModeChanged,
            -> options.onSelectorEvent(event)
            else -> Unit // session_started / user_prompt_sent / etc. are informational
        }
    }

    /**
     * What an event does to the connection's state beyond the reply's content (web reducer):
     * the retry line and the AIR failure table.
     *
     * - Progress — a token, a thought, a new call — proves a retry recovered: the line goes,
     *   and so does every in-flight retry incident. A call update or a plan update clears the
     *   line too.
     * - A new prompt acknowledges every failure (a still-real one comes back with a higher
     *   revision); a clean turn end settles the warnings left. Any other exit keeps them, since
     *   the incident didn't recover.
     */
    private fun applyConnectionState(event: AcpEvent) {
        when (event) {
            is AcpEvent.ContentDelta, is AcpEvent.Thinking, is AcpEvent.ToolCall -> _ui.update {
                val failures = SessionFailures.settle(it.sessionFailures, SessionFailures.SettleScope.RETRY_INCIDENTS)
                if (it.apiRetry == null && failures === it.sessionFailures) it else it.copy(apiRetry = null, sessionFailures = failures)
            }
            is AcpEvent.ToolCallUpdate, is AcpEvent.PlanUpdate, is AcpEvent.Error -> _ui.update {
                if (it.apiRetry == null) it else it.copy(apiRetry = null)
            }
            // The predicted prompt was for this prompt's place, so it is spent whatever was sent.
            is AcpEvent.StatusChanged -> when (event.status) {
                ConnectionStatus.PROMPTING -> _ui.update {
                    val failures = SessionFailures.settle(it.sessionFailures, SessionFailures.SettleScope.ALL)
                    if (it.apiRetry == null && failures === it.sessionFailures && it.promptSuggestion == null) {
                        it
                    } else {
                        it.copy(apiRetry = null, sessionFailures = failures, promptSuggestion = null)
                    }
                }
                // Remote Control lives in the agent process, and this is that process ending.
                ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR ->
                    _ui.update { if (it.remoteControl == null) it else it.copy(remoteControl = null) }
                else -> Unit
            }
            is AcpEvent.RemoteControl -> _ui.update { if (it.remoteControl == event.info) it else it.copy(remoteControl = event.info) }
            is AcpEvent.ForkSupported -> _ui.update { if (it.forkSupported == event.supported) it else it.copy(forkSupported = event.supported) }
            is AcpEvent.PromptSuggestion -> _ui.update { if (it.promptSuggestion == event.text) it else it.copy(promptSuggestion = event.text) }
            is AcpEvent.TurnComplete -> _ui.update {
                val failures = if (event.stopReason == "end_turn") {
                    SessionFailures.settle(it.sessionFailures, SessionFailures.SettleScope.WARNINGS)
                } else {
                    it.sessionFailures
                }
                if (it.apiRetry == null && failures === it.sessionFailures) it else it.copy(apiRetry = null, sessionFailures = failures)
            }
            // The agent's session for this conversation: what a later connect resumes. A draft
            // has none to resume (its options connection may be for another agent), so only once
            // the conversation exists.
            is AcpEvent.SessionStarted -> {
                if (conversationId != null && event.sessionId.isNotEmpty()) externalId = event.sessionId
                // A CHANGED session on the same connection (a fork) strands its AIR tasks: their
                // terminal frames go out on the session left behind, and the server drops its
                // table — which an empty snapshot can't clear here. Its prediction goes too. A
                // replayed announcement of the same session changes nothing.
                // Remote Control was on the session left behind, so it goes as well.
                if (event.sessionId != connectionSessionId) {
                    connectionSessionId = event.sessionId
                    _ui.update {
                        if (it.asyncTasks.isEmpty() && it.promptSuggestion == null && it.remoteControl == null) it
                        else it.copy(asyncTasks = emptyList(), promptSuggestion = null, remoteControl = null)
                    }
                }
            }
            is AcpEvent.ApiRetry -> _ui.update { it.copy(apiRetry = event) }
            is AcpEvent.SessionFailure -> onSessionFailure(event.record)
            // Reporting only: the saved preference keeps what was asked for, since a refusal is
            // often about this session rather than the pick (grok's switch works in a new one).
            is AcpEvent.ConfigOptionRejected -> tell(
                appContext.getString(R.string.config_option_adjusted, agent.displayName, event.optionName, event.actual, event.requested),
            )
            is AcpEvent.SessionNotice -> SessionNotices.sessionNotice(event.severity, event.title, event.description)
                ?.let { tell(appContext.getString(R.string.failure_notice_title, agent.displayName, it.title), it.description) }
            is AcpEvent.PluginLoadFailures -> SessionNotices.pluginFailures(event.failures)?.let {
                tell(appContext.resources.getQuantityString(R.plurals.plugin_load_failed_title, it.count, agent.displayName, it.count), it.description)
            }
            // A fresh drift shows again even after a dismissed one; a reverted one clears.
            is AcpEvent.SessionConfigStale -> _ui.update { it.copy(configStale = if (event.stale) ConfigStaleUi(event.kind) else null) }
            is AcpEvent.AsyncTask -> _ui.update {
                val tasks = AsyncTasks.upsert(it.asyncTasks, event.delta)
                if (tasks === it.asyncTasks) it else it.copy(asyncTasks = tasks)
            }
            is AcpEvent.BackgroundActivity -> onBackgroundActivity(event)
            // A completion carries the identity again, for a client that missed the start.
            is AcpEvent.DelegationStarted -> _ui.update {
                it.copy(delegations = it.delegations + (event.delegation.parentToolUseId to event.delegation))
            }
            is AcpEvent.DelegationCompleted -> _ui.update {
                val known = it.delegations[event.delegation.parentToolUseId]
                val done = event.delegation.copy(
                    task = event.delegation.task ?: known?.task,
                    taskId = event.delegation.taskId ?: known?.taskId,
                    assistantName = event.delegation.assistantName ?: known?.assistantName,
                )
                it.copy(delegations = it.delegations + (done.parentToolUseId to done))
            }
            is AcpEvent.DelegationChildRunning -> _ui.update {
                val running = event.childConnectionId in it.delegatedChildren
                when {
                    event.running && !running -> it.copy(delegatedChildren = it.delegatedChildren + event.childConnectionId)
                    !event.running && running -> it.copy(delegatedChildren = it.delegatedChildren - event.childConnectionId)
                    else -> it
                }
            }
            is AcpEvent.SessionLoadFailed -> _ui.update {
                it.copy(
                    sessionLoadError = SessionLoadErrorUi(
                        message = sessionLoadMessage(event),
                        command = SessionNotices.recoveryCommand(event.code, agent, event.sessionId),
                        folderId = folder?.id,
                    ),
                )
            }
            // The agent re-advertised its commands: the composer's `/` menu takes the new list.
            is AcpEvent.AvailableCommands -> composer.adoptLiveCommands(event.commands)
            else -> Unit
        }
    }

    /**
     * What the server's transcript watcher saw happen out of turn. The outstanding set replaces
     * what was held (it is the producer's whole state); the turns join the overlay; a settled
     * sub-agent's launch card flips to its settle in place, and the batch is told once.
     */
    private fun onBackgroundActivity(event: AcpEvent.BackgroundActivity) {
        _ui.update { st ->
            var next = st.copy(
                backgroundTasks = event.outstandingTasks,
                backgroundTurns = BackgroundOverlays.upsert(st.backgroundTurns, event.turns, event.watermark),
            )
            for (settled in event.settled) {
                next = next.copy(
                    turns = BackgroundTask.settle(next.turns, settled),
                    localTurns = BackgroundTask.settle(next.localTurns, settled),
                    backgroundTurns = next.backgroundTurns.map { o ->
                        val flipped = BackgroundTask.settle(listOf(o.turn), settled)
                        if (flipped[0] === o.turn) o else o.copy(turn = flipped[0])
                    },
                )
            }
            next
        }
        // An interim notification is no task's end: the sub-agent will report again.
        val finished = event.settled.filterNot { it.interim }
        if (finished.isEmpty()) return
        val name = agent.displayName
        tell(
            if (finished.size == 1) {
                val one = finished[0]
                appContext.getString(
                    R.string.failure_notice_title,
                    name,
                    one.summary?.trim()?.takeIf { it.isNotEmpty() } ?: appContext.getString(R.string.background_settled_fallback, one.status),
                )
            } else {
                appContext.resources.getQuantityString(R.plurals.background_settled_many, finished.size, name, finished.size)
            },
        )
    }

    /**
     * Stop one async task. The adapter's own verdict comes back: false for a task it declines to
     * stop (unknown, already finished, a stop already in flight). The task's end arrives on the
     * stream either way.
     */
    fun stopAsyncTask(taskId: String, done: (Boolean) -> Unit = {}) {
        val c = client ?: return done(false)
        val conn = connectionId ?: return done(false)
        viewModelScope.launch {
            val stopped = try {
                c.stopAsyncTask(conn, taskId).also { ok ->
                    if (!ok) _ui.update { it.copy(notice = appContext.getString(R.string.async_task_stop_declined)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(notice = appContext.getString(R.string.async_task_stop_failed, e.displayMessage())) }
                false
            }
            done(stopped)
        }
    }

    private var goalActionsJob: Job? = null

    /**
     * Read [conn]'s goal-action vocabulary again, backing off, until it is decided (web
     * `useAdvertisedGoalActions`: a few tries, then give up — the controls just stay away).
     */
    private fun rereadGoalActions(conn: String) {
        if (goalActionsJob?.isActive == true) return
        val c = client ?: return
        goalActionsJob = viewModelScope.launch {
            repeat(GOAL_ACTIONS_RETRIES) { attempt ->
                delay(GOAL_ACTIONS_RETRY_BASE_MS shl attempt)
                if (connectionId != conn) return@launch
                val actions = try {
                    c.liveSnapshot(conn)?.goalActions
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (actions != null) {
                    if (connectionId == conn) _ui.update { it.copy(goalActions = actions) }
                    return@launch
                }
            }
        }
    }

    /** Pause or clear the session's active goal. Its new state arrives on the stream. */
    fun goalControl(action: String) {
        val c = client ?: return
        val conn = connectionId ?: return
        viewModelScope.launch {
            try {
                c.goalControl(conn, action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(notice = e.displayMessage()) }
            }
        }
    }

    /** One advisory, told once: its headline, and what follows it below. */
    private fun tell(headline: String, more: String? = null) {
        _ui.update { it.copy(notice = if (more.isNullOrBlank()) headline else "$headline\n$more") }
    }

    /** A `session_load_failed`, localized by its code; an unknown code says the agent's own words. */
    private fun sessionLoadMessage(event: AcpEvent.SessionLoadFailed): String {
        val name = agent.displayName
        return when (event.code) {
            "resource_not_found" -> appContext.getString(R.string.session_load_not_found, name)
            "session_unavailable" -> appContext.getString(R.string.session_load_unavailable, name)
            "session_busy" -> appContext.getString(R.string.session_load_busy, name)
            "provider_mismatch" -> appContext.getString(R.string.session_load_provider_mismatch, name, event.message)
            "session_archived" -> SessionNotices.recoveryCommand(event.code, agent, event.sessionId)
                ?.let { appContext.getString(R.string.session_load_archived, name, it) }
                ?: event.message
            else -> event.message
        }.ifBlank { appContext.getString(R.string.live_agent_connection_errored) }
    }

    /**
     * Restart the agent so a settings change made after it was spawned applies: end this
     * connection; the next message connects afresh and resumes the conversation. Not mid-turn —
     * that would kill the turn with the process.
     */
    fun reapplyConfig() {
        if (_ui.value.isInFlight) return
        val c = client ?: return
        val conn = connectionId
        _ui.update { it.copy(configStale = null) }
        if (conn == null) return
        // Off the stream first, so the connection's own teardown doesn't read as a failure here.
        forgetConnection()
        val conversation = conversationId
        viewModelScope.launch {
            // The next connect must resume THIS session, or the conversation would go on in a
            // new one. A session this screen never learned the id of is read off the row.
            if (externalId == null && conversation != null) {
                externalId = runCatching { c.conversationDetail(conversation, TurnWindow.Tail(1)).summary.externalId }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            }
            runCatching { c.disconnect(conn) }
                .onSuccess { _ui.update { it.copy(notice = appContext.getString(R.string.config_stale_applies_next)) } }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e else _ui.update { it.copy(notice = e.displayMessage()) } }
        }
    }

    /**
     * Turn Claude Code's Remote Control off. It is the agent's own command, so it goes out as a
     * prompt (as the web sends it), and the reply it answers with is what turns the strip off.
     */
    fun turnOffRemoteControl() {
        if (_ui.value.isInFlight || connectionId == null || _ui.value.remoteControl == null) return
        send(REMOTE_CONTROL_OFF, emptyList(), SendOrigin.GENERATED)
    }

    fun dismissConfigStale() = _ui.update { s -> s.configStale?.let { s.copy(configStale = it.copy(dismissed = true)) } ?: s }

    /** Try the session again after a load failure: drop the dead connection and read it anew. */
    fun reloadAfterLoadFailure() {
        _ui.update { it.copy(sessionLoadError = null) }
        forgetConnection()
        load()
    }

    /**
     * An AIR failure upsert. A retry incident is progress, drawn live above the composer; an
     * advisory or a terminal failure is news, told once. A terminal one also closes the
     * running reply with the agent's own account of it — the line its transcript keeps too.
     */
    private fun onSessionFailure(record: app.codeg.android.core.model.SessionFailureRecord) {
        val stored = _ui.value.sessionFailures.firstOrNull { it.id == record.id }
        _ui.update {
            val merged = SessionFailures.merge(it.sessionFailures, listOf(record))
            if (merged === it.sessionFailures) it else it.copy(sessionFailures = merged)
        }
        val kind = SessionFailures.notice(stored, record) ?: return
        val own = record.title.trim()
        val label = appContext.getString(failureCategoryLabel(record.category))
        if (kind == SessionFailures.Notice.TERMINAL) {
            val builder = liveBuilder?.takeIf { ownTurnStarted && it.isStreaming }
            val current = builder?.error
            // One account per turn: the agent's own words win over codeg's verdict.
            if (builder != null && !(current?.fromAgent == true && own.isEmpty())) {
                builder.error = LiveTurnError(own.ifEmpty { label }, record.details?.trim()?.ifEmpty { null }, fromAgent = own.isNotEmpty())
                emitLiveNow()
            }
        }
        val headline = own.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: label
        _ui.update { it.copy(notice = appContext.getString(R.string.failure_notice_title, agent.displayName, headline)) }
    }

    /** The user closed a failure strip: every record it stood for (client-local). */
    fun dismissSessionFailures(ids: List<String>) = _ui.update {
        val dismissed = SessionFailures.dismiss(it.sessionFailures, ids)
        if (dismissed === it.sessionFailures) it else it.copy(sessionFailures = dismissed)
    }

    /**
     * A blocked permission / question / plan-approval can't outlive its turn: clear any
     * pending card when the turn finalizes, fails, or is cancelled, and return the
     * composer to Send.
     *
     * Parked plan-revision notes are dropped here too — they are meaningful only as the
     * immediate follow-up to the keep-planning turn that produced them. A turn that
     * failed or was cancelled never gets that follow-up, and leaving them parked would
     * let a LATER, unrelated turn's completion send them. [finalizeTurn] — the one path
     * that legitimately delivers them — reads them before calling this.
     */
    private fun clearInteractivePrompts() {
        pendingPlanFollowUp = null
        _ui.update {
            it.copy(
                isInFlight = false,
                sendStatus = null,
                pendingPermission = null,
                pendingQuestion = null,
                pendingPlanApproval = null,
                // Nothing can be retrying once the turn is over.
                apiRetry = null,
                // Nothing left to steer. The notes themselves stay until the next turn
                // clears them; this only closes the channel.
                noteTurnOpen = false,
            )
        }
    }

    /** The status-line copy for the current live state: the running tool's title, else
     *  "Thinking…". Recomputed on tool start/finish so it never sticks on a done tool.
     *  Hardcoded English to match the send-flow literals (transient status micro-copy is
     *  intentionally not localized — see the app's i18n notes). */
    private fun runningStatus(): String =
        liveBuilder?.activeToolTitle()?.let { "Running ${it.ifBlank { "tool" }}…" } ?: STATUS_THINKING

    /** A new task's first prompt links a server-side conversation. Android creates that
     *  row up front (so [conversationId] is already set), but has no summary for it yet —
     *  background-fetch the identity (summary + stats) so the header shows the real title
     *  and usage DURING the first turn instead of only after [reconcileAfterTurn]. Never
     *  touches the live turn or transcript. Gated on a missing summary, so it's a no-op for
     *  an existing session (which already carries one). Mirrors iOS `adoptLinkedConversation`. */
    private fun adoptLinkedConversation(id: Int) {
        if (conversationId == null) conversationId = id
        if (_ui.value.summary != null) return
        val c = client ?: return
        val target = conversationId ?: id
        viewModelScope.launch {
            // Only `summary` and `sessionStats` are read below, and both describe the
            // full transcript regardless of the window — so ask for the smallest one
            // the server will cut rather than pull a transcript to throw it away.
            val detail = runCatching { c.conversationDetail(target, TurnWindow.Tail(1)) }
                .getOrNull() ?: return@launch
            // Bail if the conversation changed, or a summary arrived, while we fetched.
            if (conversationId != target) return@launch
            _ui.update {
                if (it.summary != null) it
                else it.copy(summary = detail.summary, sessionStats = detail.sessionStats ?: it.sessionStats)
            }
        }
    }

    fun respondPermission(optionId: String) {
        val pending = _ui.value.pendingPermission ?: return
        val conn = connectionId ?: return
        _ui.update { it.copy(pendingPermission = null) }
        viewModelScope.launch {
            runCatching { client?.respondPermission(conn, pending.requestId, optionId) }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingPermission = pending) } }
        }
    }

    fun answerQuestion(answer: app.codeg.android.core.model.QuestionAnswer) {
        val pending = _ui.value.pendingQuestion ?: return
        val conn = connectionId ?: return
        _ui.update { it.copy(pendingQuestion = null) }
        viewModelScope.launch {
            runCatching { client?.answerQuestion(conn, pending.questionId, answer) }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingQuestion = pending) } }
        }
    }

    fun dismissQuestion() = answerQuestion(app.codeg.android.core.model.QuestionAnswer.dismissed)

    /**
     * Resolve Grok's blocked `exit_plan_mode`. Optimistic clear on success; the card
     * comes back (with a notice) if the post fails.
     *
     * "Request changes" needs one extra step: Grok DISCARDS the reply's `feedback` on
     * the keep-planning path (only approve/abandon consume it), and its own TUI instead
     * delivers the revision notes as a follow-up user turn. Mirror that — otherwise the
     * notes vanish and Grok re-presents the same plan. The keep-planning turn is usually
     * still winding down at this point, so the follow-up is parked and flushed when the
     * turn completes.
     */
    fun answerPlanApproval(decision: PlanApprovalDecision, feedback: String?) {
        val pending = _ui.value.pendingPlanApproval ?: return
        val conn = connectionId ?: return
        val notes = feedback?.trim().orEmpty()
        // The turn that produced this approval. The post below is a suspension point, so
        // that turn can finalize, fail, be cancelled, or be replaced by an unrelated new
        // one before we get to act on the notes — the identity check below is what keeps
        // the follow-up bound to the turn it belongs to.
        val origin = liveBuilder
        _ui.update { it.copy(pendingPlanApproval = null) }
        // The revision notes have to be the next prompt: hold the queue until they are sent
        // (or known not to be), or a queued message would go out first and take that slot.
        planAnswersInFlight += 1
        viewModelScope.launch {
            runCatching { client?.answerPlanApproval(conn, pending.approvalId, decision, notes.ifEmpty { null }) }
                .onSuccess {
                    if (decision != PlanApprovalDecision.REQUEST_CHANGES || notes.isEmpty()) return@onSuccess
                    when (
                        planFollowUpAction(
                            sameTurn = planOriginCurrent(origin, live = liveBuilder, handedOff = handedOffBuilder),
                            inFlight = _ui.value.isInFlight,
                            stopReason = origin?.stopReason,
                            errorMessage = origin?.error?.message,
                        )
                    ) {
                        PlanFollowUp.PARK -> pendingPlanFollowUp = origin?.let { ParkedPlanNotes(it.id, notes) }
                        PlanFollowUp.SEND_NOW -> send(notes, emptyList(), SendOrigin.GENERATED)
                        PlanFollowUp.DROP ->
                            _ui.update { it.copy(notice = appContext.getString(R.string.plan_approval_notes_dropped)) }
                    }
                }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingPlanApproval = pending) } }
            planAnswersInFlight -= 1
            queue.schedule()
        }
    }

    /** Seed the live builder from a reattach snapshot, returning true when the session is
     *  actually in flight (a live message, active tools, a pending card, or actively
     *  prompting) and false when the connection is idle — the caller then aborts the
     *  reattach. Flags the turn reattach-owned so the transcript hides the partial copy
     *  the server has persisted. Mirrors the iOS `buildLiveTurn(from:)` nil check. */
    private fun seedLiveFromSnapshot(snap: app.codeg.android.core.model.LiveSessionSnapshot): Boolean {
        val builder = liveBuilder ?: return false
        if (!snapshotShowsTurn(snap)) return false
        pendingReattachSeed = false
        if (builder.isEmpty) builder.seedFrom(snap)
        // Read BEFORE the reply is claimed below: `liveFromReattach` keys off `builder.isEmpty`,
        // and an error stamped first would make an otherwise-empty seed look like content and
        // hide a persisted reply that has no live copy to stand in for it.
        val claimsReply = !builder.isEmpty
        applySnapshotError(snap, builder)
        // Only claim the reply (hiding the server's persisted partial copy) when the seed
        // actually produced content. A pending-only snapshot (a permission/question with no
        // assistant message yet) seeds nothing, so hiding the transcript there could drop a
        // persisted reply with no live copy to show in its place.
        _ui.update { it.copy(live = builder.snapshot(), liveFromReattach = claimsReply) }
        return true
    }

    /** Stamp onto [builder] a failure this client was not around for, when — and only when —
     *  the snapshot proves it belongs to the turn being seeded. See [snapshotTurnError]. */
    private fun applySnapshotError(
        snap: app.codeg.android.core.model.LiveSessionSnapshot,
        builder: LiveTurnBuilder,
    ) {
        builder.error = snapshotTurnError(
            status = snap.status,
            lastError = snap.lastError,
            current = builder.error,
            fallbackMessage = appContext.getString(R.string.timeline_error_generic),
        ) ?: builder.error
    }

    /** The reattach snapshot showed an idle connection — nothing is in flight. Clear the
     *  placeholder live turn, drop the connection, tear the stream down, and reconcile in
     *  case the turn just finished (leaving the loaded transcript a beat stale). */
    /**
     * The reattach found nothing running. [attached] says whether the stream did attach (an
     * idle connection, kept for observing) or not (the connection vanished first).
     */
    private fun abortIdleReattach(attached: Boolean) {
        pendingReattachSeed = false
        liveBuilder = null
        _ui.update { it.copy(isInFlight = false, sendStatus = null, live = null, liveFromReattach = false) }
        if (!attached) {
            connectionId = null
            closeStream()
        }
        reconcileAfterMissedLive()
    }

    /** Server reported a live turn at load but no in-flight state surfaced — refetch so
     *  the transcript shows the final reply rather than the beat-stale load. */
    private fun reconcileAfterMissedLive() {
        val c = client ?: return
        val id = conversationId ?: return
        viewModelScope.launch {
            val generation = bumpFetchGeneration()
            refetchWindow(c, id)?.let { (d, reanchored) ->
                if (!isLatestFetch(generation)) return@launch
                val state = _ui.value
                val adoption = refreshAdoption(
                    d,
                    reanchored,
                    roundOnScreen = liveBuilder != null || state.pendingUserTurns.isNotEmpty(),
                    localTurns = state.localTurns,
                    batchStart = batchStart ?: state.loadedEnd,
                )
                if (adoption == RefreshAdoption.SKIP) return@launch
                val takeLocal = adoption == RefreshAdoption.TAKE_ALL
                _ui.update {
                    it.copy(
                        turns = d.turns,
                        backgroundTurns = BackgroundOverlays.retire(it.backgroundTurns, d.transcriptWatermark, d.uncoveredPrefixMaxTs),
                        window = d.window,
                        inFlightUserTurnId = d.inFlightUserTurnId,
                        sessionStats = d.sessionStats ?: it.sessionStats,
                        summary = d.summary,
                        localTurns = if (takeLocal) emptyList() else it.localTurns,
                    )
                }
                if (takeLocal) clearBatch() else reanchorBatch()
            }
        }
    }

    /**
     * Re-fetch exactly the loaded window.
     *
     * [TurnWindow.FromIndex] is what keeps the callers' wholesale `turns = …`
     * replacement correct: the response covers the same coordinates the UI
     * already holds, so it is that region, just newer — no merging needed.
     *
     * Unless the prefix moved. A compaction (or any rewrite) shifts every index,
     * and the response's prefix fingerprint is what witnesses it: on a mismatch
     * the offset now names a different turn, so the window is abandoned and
     * re-taken from the tail rather than spliced into nonsense. Paged-in history
     * is dropped with it — those coordinates are gone too.
     *
     * Against a pre-0.24.0 server there is no window and this is the plain
     * full-transcript fetch it always was.
     */
    private suspend fun refetchWindow(c: CodegClient, id: Int): WindowFetch? {
        val loaded = _ui.value.window
            ?: return runCatching { c.conversationDetail(id) }.getOrNull()
                ?.let { WindowFetch(it, reanchored = false) }
        val fresh = runCatching { c.conversationDetail(id, TurnWindow.FromIndex(loaded.offset)) }
            .getOrNull() ?: return null
        if (windowRefreshMatches(fresh, loaded)) return WindowFetch(fresh, reanchored = false)
        return runCatching { c.conversationDetail(id, TurnWindow.Tail(INITIAL_TAIL_TURNS)) }
            .getOrNull()?.let { WindowFetch(it, reanchored = true) }
    }

    /**
     * Load one page of older history above the window and prepend it. No-op
     * without a window (a legacy server already sent everything), at the top of
     * the transcript, or while a page is already in flight.
     *
     * [anchor] holds the transcript's own coordinates for the row the user is
     * reading; they are carried back verbatim on [SessionDetailUiState.prepend]
     * so the viewport can be restored once the page is in.
     */
    fun loadOlderTurns(anchor: PrependAnchor) {
        val c = client ?: return
        val id = conversationId ?: return
        val loaded = _ui.value.window ?: return
        // The navigator paging toward a message owns the seam meanwhile.
        if (!loaded.hasOlder || pagingJob?.isActive == true || revealJob?.isActive == true) return
        // A list too short to scroll cannot produce another trigger on its own, so a
        // page that never adds anything would retry forever. Give up after a few and
        // leave the row idle; reopening the session starts over.
        if (pageStalls >= MAX_PAGE_STALLS) return

        _ui.update { it.copy(loadingOlder = true) }
        // Same total order as every other detail fetch: issuing a page invalidates
        // an in-flight window refresh, whose response predates the page and would
        // otherwise clobber the prepend with a narrower window.
        val generation = bumpFetchGeneration()
        pagingJob = viewModelScope.launch {
            try {
                // Anything short of an actual prepend counts as a stall, not just a
                // network failure. The trigger re-fires whenever a page finishes, so a
                // request that keeps coming back with nothing to add — a page the
                // server has no more of, a window that keeps moving underneath — would
                // otherwise retry forever on a list too short to produce a new scroll.
                pageStalls += 1
                val page = runCatching { c.conversationTurnsPage(id, loaded.offset, OLDER_TURNS_PAGE_SIZE) }
                    .getOrNull() ?: return@launch
                if (!isLatestFetch(generation)) return@launch

                when (val merge = mergeOlderTurns(page, loaded, _ui.value.window, _ui.value.turns)) {
                    is PageMerge.Drop -> Unit
                    // The prefix was rewritten between the two requests, so the loaded
                    // coordinates name different turns now. Re-take the window from the
                    // tail rather than joining two different transcripts.
                    is PageMerge.Resync -> reconcileAfterMissedLive()
                    is PageMerge.Prepend -> {
                        pageStalls = 0 // real progress: the budget starts over
                        _ui.update {
                            it.copy(
                                turns = merge.turns + it.turns,
                                window = merge.window,
                                prepend = anchor.copy(epoch = (it.prepend?.epoch ?: 0) + 1),
                            )
                        }
                    }
                }
            } finally {
                _ui.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private var outlineJob: Job? = null
    private var revealJob: Job? = null

    /**
     * Read the history above the window for the message navigator — once, and again only when
     * what was read no longer proves to be the window's prefix (the history was rewritten).
     * Pages prepended since only shrink what it has to cover, so it stays good.
     */
    fun loadOutline() {
        val c = client ?: return
        val id = conversationId ?: return
        val state = _ui.value
        val window = state.window ?: return
        if (!window.hasOlder || !state.outlineSupported || outlineJob?.isActive == true) return
        (state.outline as? OutlineState.Ready)?.let { if (MessageNav.outlinePrefix(it.outline, window) != null) return }
        _ui.update { it.copy(outline = OutlineState.Loading) }
        outlineJob = viewModelScope.launch {
            val outline = orNull { c.conversationOutline(id, window.offset) }
            _ui.update {
                val now = it.window
                it.copy(
                    outline = when {
                        outline == null -> OutlineState.Failed
                        // A read that doesn't join the window it was asked for is no use.
                        now != null && MessageNav.outlinePrefix(outline, now) == null -> OutlineState.Failed
                        else -> OutlineState.Ready(outline)
                    },
                )
            }
        }
    }

    /**
     * Take the transcript to [turnId] (the navigator's jump): page the history in through
     * [turnIndex] first when it sits above the window, then scroll there. The latest jump
     * wins — an earlier one still paging is dropped.
     */
    fun revealTurn(turnId: String, turnIndex: Int?) {
        revealJob?.cancel()
        if (turnIndex == null || _ui.value.turns.any { it.id == turnId } || _ui.value.localTurns.any { it.id == turnId }) {
            _ui.update { it.copy(scrollToTurn = TurnScroll(turnId, (it.scrollToTurn?.epoch ?: 0) + 1), revealingTurnId = null) }
            return
        }
        val c = client ?: return
        val id = conversationId ?: return
        _ui.update { it.copy(revealingTurnId = turnId) }
        revealJob = viewModelScope.launch {
            try {
                // An ordinary page in flight would race this one for the same seam.
                pagingJob?.join()
                while (true) {
                    val loaded = _ui.value.window ?: break
                    if (loaded.offset <= turnIndex) break
                    val generation = bumpFetchGeneration()
                    _ui.update { it.copy(loadingOlder = true) }
                    val page = orNull { c.conversationTurnsPage(id, loaded.offset, maxOf(loaded.offset - turnIndex, OLDER_TURNS_PAGE_SIZE)) }
                    _ui.update { it.copy(loadingOlder = false) }
                    if (page == null) break
                    if (!isLatestFetch(generation)) break
                    when (val merge = mergeOlderTurns(page, loaded, _ui.value.window, _ui.value.turns)) {
                        is PageMerge.Prepend -> _ui.update { it.copy(turns = merge.turns + it.turns, window = merge.window) }
                        is PageMerge.Resync -> {
                            reconcileAfterMissedLive()
                            break
                        }
                        is PageMerge.Drop -> break
                    }
                }
                if (_ui.value.turns.any { it.id == turnId }) {
                    _ui.update { it.copy(scrollToTurn = TurnScroll(turnId, (it.scrollToTurn?.epoch ?: 0) + 1)) }
                }
            } finally {
                _ui.update { st -> (if (st.revealingTurnId == turnId) st.copy(revealingTurnId = null) else st).copy(loadingOlder = false) }
            }
        }
    }

    /** The screen has scrolled to the navigator's target. */
    fun consumeScrollToTurn() = _ui.update { if (it.scrollToTurn == null) it else it.copy(scrollToTurn = null) }

    /**
     * Clear the pending scroll restore once the transcript has acted on it. A
     * one-shot: left set, it would be replayed by a fresh composition (a rotation,
     * a navigation return) and scroll the user back to a position they left long
     * ago.
     */
    fun consumePrepend() {
        _ui.update { if (it.prepend == null) it else it.copy(prepend = null) }
    }

    /** @see fetchGeneration */
    private fun bumpFetchGeneration(): Int = ++fetchGeneration

    /** False once a newer fetch superseded [generation]; its response must be dropped. */
    private fun isLatestFetch(generation: Int): Boolean = fetchGeneration == generation

    /**
     * A permission request as its card shows it: merged with the live call it is about, each
     * filling what the other left out ([PermissionMerge]). The live turn learns of the call
     * too, so the step the user is asked about stands on the rail, waiting on them.
     */
    private fun permissionUi(
        requestId: String,
        toolCall: kotlinx.serialization.json.JsonElement,
        options: List<app.codeg.android.core.model.PermissionOption>,
        queued: Int,
    ): PendingPermissionUi {
        val callId = permissionToolCallId(toolCall)
        val builder = liveBuilder
        val merged = PermissionMerge.merge(toolCall, callId?.let { builder?.toolCall(it) })
        if (callId != null && builder != null && builder.isStreaming) {
            val announced = builder.noteAwaitedCall(
                callId,
                title = PermissionMerge.requestTitle(toolCall).orEmpty(),
                kind = PermissionMerge.requestKind(toolCall).orEmpty(),
                rawInput = PermissionMerge.requestInput(merged),
            )
            if (announced) scheduleLiveEmit()
        }
        return PendingPermissionUi(requestId, ParsedPermission.parse(merged), options, queued, merged)
    }

    /**
     * Call [callId] moved after the card about it went up: fill what the request still lacks
     * from it (web `mergePendingPermissionWithLiveInfo`, on every call and call update) — a
     * bare request then shows the command, the edit or the network host the call brings later.
     */
    private fun refreshPendingPermission(callId: String) {
        val pending = _ui.value.pendingPermission ?: return
        if (pending.parsed.toolCallId != callId) return
        val live = liveBuilder?.toolCall(callId) ?: return
        val merged = PermissionMerge.merge(pending.toolCall, live)
        if (merged === pending.toolCall) return
        val refreshed = pending.copy(toolCall = merged, parsed = ParsedPermission.parse(merged))
        _ui.update { if (it.pendingPermission?.requestId == pending.requestId) it.copy(pendingPermission = refreshed) else it }
    }

    /**
     * What a snapshot says about the connection beyond the turn — whichever branch the attach
     * takes next. Answered approvals only ever fold in, so an older snapshot can't walk a
     * newer one back.
     */
    private fun adoptSnapshotConnectionState(snapshot: LiveSessionSnapshot) {
        // The commands the connection holds feed the composer's `/` menu (an empty list is a
        // connection whose agent hasn't advertised yet, not "none").
        composer.adoptSnapshotCommands(snapshot.availableCommands)
        // Goal actions not decided yet (`initialize` still in flight): read them again shortly.
        if (snapshot.goalActions == null) snapshot.connectionId?.let(::rereadGoalActions)
        // The session this conversation's connection runs, for a later connect to resume.
        val linked = snapshot.conversationId == null || snapshot.conversationId == conversationId
        if (conversationId != null && linked && snapshot.connectionId == connectionId) {
            snapshot.externalId?.takeIf { it.isNotEmpty() }?.let { externalId = it }
        }
        // Another connection's tables (a restarted agent) describe work that died with it.
        val sameConnection = snapshot.connectionId != null && snapshot.connectionId == connectionStateOwner
        if (!sameConnection) {
            connectionStateOwner = snapshot.connectionId
            appliedSeq = 0
        }
        connectionSessionId = snapshot.externalId
        // Rows carry no revision, so only a snapshot newer than every event applied may replace
        // them; an overtaken one may only add tasks never seen.
        val overtaken = sameConnection && (snapshot.eventSeq ?: Long.MAX_VALUE) < appliedSeq
        _ui.update {
            val approvals = ToolApprovals.merge(it.toolApprovals, snapshot.toolApprovals)
            val failures = if (sameConnection) SessionFailures.merge(it.sessionFailures, snapshot.sessionFailures) else snapshot.sessionFailures
            val tasks = when {
                !sameConnection -> snapshot.asyncTasks
                overtaken -> AsyncTasks.adoptUnknown(it.asyncTasks, snapshot.asyncTasks)
                else -> AsyncTasks.merge(it.asyncTasks, snapshot.asyncTasks)
            }
            // The drift the one-shot event announced; a dismissal holds while it's the same one.
            val stale = if (snapshot.configStale) {
                it.configStale?.takeIf { s -> s.kind == snapshot.configStaleKind } ?: ConfigStaleUi(snapshot.configStaleKind)
            } else {
                null
            }
            // Current state, like the selectors: the snapshot's prediction (or its absence) is
            // the one that stands.
            val suggestion = snapshot.promptSuggestion
            // A retry is never on a snapshot: whatever ended or outran it fell in the gap the
            // snapshot stands for, so the line it left would only go stale.
            it.copy(
                toolApprovals = approvals,
                sessionFailures = failures,
                configStale = stale,
                promptSuggestion = suggestion,
                apiRetry = null,
                asyncTasks = tasks,
                // Unknown until `initialize` answers: offer nothing meanwhile (a wrong button is
                // worse than none), and take the list once a later snapshot has it.
                goalActions = snapshot.goalActions ?: emptyList(),
                // Current state; only an event this snapshot didn't see can say otherwise.
                remoteControl = if (overtaken) it.remoteControl else snapshot.remoteControl,
                // Latched once `initialize` answers, so a snapshot only ever adds it.
                forkSupported = snapshot.forkSupported || (sameConnection && it.forkSupported),
                // Whole sets, the server's current state.
                backgroundTasks = snapshot.backgroundOutstandingTasks,
                delegatedChildren = snapshot.delegatedChildren,
                // The running ones it holds. A snapshot newer than every event applied here is the
                // current word — a delegation it shows running after this client saw it end was
                // resumed (under the same call id). Only an overtaken one can't revive an end.
                delegations = snapshot.activeDelegations.fold(it.delegations) { acc, d ->
                    val ended = acc[d.parentToolUseId]?.status?.let { s -> s != app.codeg.android.core.model.DelegationBinding.Status.RUNNING } == true
                    if (overtaken && ended) acc else acc + (d.parentToolUseId to d)
                },
            )
        }
    }

    private fun restorePending(snap: app.codeg.android.core.model.LiveSessionSnapshot) {
        snap.pendingPermission?.let { p ->
            val pending = permissionUi(p.requestId, p.toolCall, p.options, p.queued)
            _ui.update { it.copy(pendingPermission = pending) }
        }
        snap.pendingQuestion?.let { q ->
            _ui.update { it.copy(pendingQuestion = PendingQuestionUi(q.questionId, q.questions)) }
        }
        // Set OR clear: the attach snapshot is the connection's authoritative pending
        // state, so an approval another client resolved while we were reconnecting must
        // not leave a stale, still-actionable card behind (answering it would post a
        // decision for an approval that no longer exists). The permission/question
        // restores above deliberately keep their existing set-only behaviour.
        _ui.update {
            it.copy(
                pendingPlanApproval = snap.pendingPlanApproval
                    ?.let { p -> PendingPlanApprovalUi(p.approvalId, p.toolCallId, p.planMarkdown) },
            )
        }
        notes.restore(snap)
    }


    /**
     * End the live turn. One this client watched to its end ([unseen] false) is handed off
     * locally: what streamed becomes its transcript ([promoteLocally]). One whose end was
     * inferred — its `turn_complete` lost in a reconnect gap, or never sent while this send
     * was still waiting to start — streamed only partly, so the transcript is read for it
     * instead ([reconcileAfterTurn]).
     */
    private fun finalizeTurn(stopReason: String, unseen: Boolean = false) {
        // Once per turn: a cancel is answered by the server's own `turn_complete`, and the
        // agent may send a second one afterwards.
        val builder = liveBuilder?.takeIf { it.stopReason == null } ?: return
        builder.isStreaming = false
        builder.stopReason = stopReason
        emitLiveNow()
        // Return the composer to Send and clear the status line immediately — the turn is done.
        // Read before clearing: this is the one transition that delivers parked
        // plan-revision notes (every other terminal path drops them). Notes parked by a
        // DIFFERENT turn — one whose own completion never came, so a reconcile ended it
        // instead — are dropped here rather than riding out on this unrelated turn.
        val parked = pendingPlanFollowUp
        val planFollowUp = parked?.notesFor(builder.id)
        clearInteractivePrompts()
        if (parked != null && planFollowUp == null) {
            _ui.update { it.copy(notice = appContext.getString(R.string.plan_approval_notes_dropped)) }
        }
        val state = _ui.value
        if (!endsByHandOff(unseen, observedTurn, state.liveFromReattach, promptKnown = state.pendingUserTurns.isNotEmpty())) {
            reconcileAfterTurn()
        } else {
            reconcileJob?.cancel()
            promoteLocally()
            syncTurnMetadata()
        }
        // The Chats list keeps its own copy of each row's status, read when it last loaded:
        // without a word from here, a turn ended while the user watched stays "running" there.
        repository.notifyConversationsChanged()
        // The keep-planning turn just ended — deliver the revision notes as the follow-up
        // prompt Grok expects (it discards them on the reply itself). After an unseen end
        // `send` supersedes the reconcile just started and folds the finished reply in
        // locally, exactly as a user typing the instant a turn ends would.
        if (planFollowUp != null) send(planFollowUp, emptyList(), SendOrigin.GENERATED)
    }

    /** [details] is the server's redacted evidence, carried only by errors codeg inferred.
     *  It stays off `sendStatus` and every notice — those are one-line surfaces, and this is
     *  a multi-line stderr tail that belongs in the card's disclosure alone. */
    private fun failLive(message: String, details: String? = null) {
        // No turn running: an error between turns (the stream now stays attached for them)
        // belongs to no reply. Say it once instead of stamping it on a finished one.
        val builder = liveBuilder?.takeIf { it.isStreaming }
        if (builder == null) {
            _ui.update { it.copy(notice = message) }
            return
        }
        // The agent's own account of the failure (an AIR record) outranks codeg's verdict.
        builder.error = builder.error?.takeIf { it.fromAgent } ?: LiveTurnError(message, details?.trim()?.ifEmpty { null })
        builder.isStreaming = false
        emitLiveNow()
        // The turn died rather than ended: sending the queue into the same failure one message
        // at a time helps nobody, so it waits for the user. Held before the composer is
        // released below, which would otherwise flush it.
        _ui.update { it.pausedAfterFailure(requeue = null) }
        clearInteractivePrompts()
        repository.notifyConversationsChanged()
    }

    /**
     * Read the transcript for a turn whose end this client didn't see whole, and take the
     * server's copy once a read holds every round shown locally ([holdsBatch]); failing that,
     * keep what streamed ([promoteLocally]). The finished live reply stays on screen meanwhile.
     */
    private fun reconcileAfterTurn() {
        val c = client ?: return
        val id = conversationId ?: return
        // Capture the turn this reconcile belongs to. If a new send / cancel supersedes the
        // live builder while we poll, bail rather than wiping the newer turn's state (its
        // pending user turn, live reply, in-flight flag). Mirrors iOS's `liveTurn === live`.
        val builder = liveBuilder
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            var attempt = 0
            var polls = 0
            // `attempt` counts answers this poll actually got to judge; `polls` bounds
            // the loop regardless. A response superseded by a history page taught us
            // nothing, so spending a budgeted attempt on it would let a user scrolling
            // through history starve the reconcile and strand the turn in-flight.
            while (attempt < TURN_RECONCILE_ATTEMPTS && polls < TURN_RECONCILE_ATTEMPTS * 2) {
                delay(if (polls == 0) 300 else 600)
                polls += 1
                if (liveBuilder !== builder) return@launch
                val generation = bumpFetchGeneration()
                val fetched = refetchWindow(c, id)
                if (fetched == null) { attempt += 1; continue }
                if (!isLatestFetch(generation)) continue
                attempt += 1
                val detail = fetched.detail
                // Getting this response cost a re-anchor: the transcript was REWRITTEN
                // (a compaction) while the turn was ending. Every index moved, so the
                // baseline counts turns that no longer exist — and there is no
                // client-visible way to recover the correlation. Parser turn ids are
                // derived from an index the rewrite renumbers, the in-flight user turn
                // id reverts to a parser id once the turn ends, and prompt text is not
                // an identity: the same prompt can be sent twice, and an image-only
                // prompt has no text at all.
                //
                // So the failure mode is chosen rather than inferred. Adopt the
                // response — that is what keeps the window from going permanently
                // stale and re-anchoring on every later turn — then stop judging and
                // let the local promotion below keep the streamed reply.
                //
                // The cost is that a round the rewrite had ALREADY persisted is shown
                // twice, and the doubled count keeps this session's later turns on
                // local promotion. Both clear on reopen. That is the right way round:
                // the alternative is accepting a trailing reply that may belong to an
                // older round, which discards the user's own message along with the
                // reply — and no reopen brings those back.
                if (fetched.reanchored) {
                    if (liveBuilder !== builder) return@launch
                    _ui.update {
                        it.copy(
                            turns = detail.turns,
                            backgroundTurns = BackgroundOverlays.retire(it.backgroundTurns, detail.transcriptWatermark, detail.uncoveredPrefixMaxTs),
                            window = detail.window,
                            inFlightUserTurnId = detail.inFlightUserTurnId,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
                        )
                    }
                    // Local turns already shown stay, after the new window, for the same
                    // reason; the batch start they had names a turn that no longer exists.
                    reanchorBatch()
                    break
                }

                // Taken only when it holds every round shown locally, this one included: the
                // server may split a reply into more turns than streamed here, so no count of
                // turns can say so — see [holdsBatch]. A failed round ends on its
                // `turn_error` record, not on a reply, and that counts as ended too.
                val state = _ui.value
                val prompts = promptCount(state.localTurns) + state.pendingUserTurns.size
                if (holdsBatch(detail, batchStart ?: state.loadedEnd, prompts)) {
                    if (liveBuilder !== builder) return@launch
                    // This turn ended without a `turn_complete` of its own (a dead
                    // connection, an exhausted reconnect), so `finalizeTurn` never ran:
                    // resolve its parked plan-revision notes here instead of leaving them
                    // for whatever turn finalizes next.
                    val orphanedNotes = builder?.let { pendingPlanFollowUp?.notesFor(it.id) }
                    if (orphanedNotes != null) pendingPlanFollowUp = null
                    // While the stream is attached it owns the cards: the turn's own were
                    // cleared when it ended, and one shown since is a live, out-of-turn
                    // request the server is still waiting on. Only with no stream left to
                    // correct them are leftovers cleared here.
                    val streamOwnsCards = stream.isActive
                    _ui.update {
                        it.copy(
                            turns = detail.turns,
                            backgroundTurns = BackgroundOverlays.retire(it.backgroundTurns, detail.transcriptWatermark, detail.uncoveredPrefixMaxTs),
                            window = detail.window,
                            inFlightUserTurnId = detail.inFlightUserTurnId,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
                            localTurns = emptyList(),
                            pendingUserTurns = emptyList(),
                            live = null,
                            liveFromReattach = false,
                            isInFlight = false,
                            sendStatus = null,
                            pendingPermission = if (streamOwnsCards) it.pendingPermission else null,
                            pendingQuestion = if (streamOwnsCards) it.pendingQuestion else null,
                            pendingPlanApproval = if (streamOwnsCards) it.pendingPlanApproval else null,
                            notice = if (orphanedNotes != null) {
                                appContext.getString(R.string.plan_approval_notes_dropped)
                            } else {
                                it.notice
                            },
                        )
                    }
                    clearBatch()
                    liveBuilder = null
                    return@launch
                }
            }
            // Couldn't confirm — keep what streamed, so nothing is lost, and fill in what the
            // server records about it as a watched turn's hand-off would.
            if (liveBuilder === builder) {
                promoteLocally()
                syncTurnMetadata()
            }
        }
        // Held while this ran: a send would have cut it short ([SendQueueController]).
        reconcileJob?.invokeOnCompletion { queue.schedule() }
    }

    /**
     * Hand the finished live turn off to the transcript as local turns: its prompts, the reply
     * as it streamed, its failure record ([promotedTurns]). The first hand-off of a batch pins
     * where the batch sits in the full transcript ([batchStart]).
     */
    private fun promoteLocally() {
        val builder = liveBuilder
        var start: Int? = null
        var prefixHash: String? = null
        _ui.update { state ->
            // On a reattach-owned reply, the tail of `turns` is the server's partial copy
            // of the same reply — drop it so the local one doesn't double it.
            val base = if (state.liveFromReattach) {
                state.turns.hideInFlightReply(state.inFlightUserTurnId)
            } else {
                state.turns
            }
            val promoted = promotedTurns(state.pendingUserTurns, builder)
            start = if (promoted.isEmpty()) null else loadedEnd(state.window, base)
            prefixHash = batchPrefixHash(state.window, base)
            state.copy(
                turns = base,
                localTurns = state.localTurns + promoted,
                // The round is over as far as this client knows; nothing shown is still running.
                inFlightUserTurnId = null,
                pendingUserTurns = emptyList(),
                live = null,
                liveFromReattach = false,
                isInFlight = false,
                sendStatus = null,
            )
        }
        if (batchStart == null && start != null) pinBatch(start, prefixHash)
        handedOffBuilder = builder
        liveBuilder = null
    }

    private fun pinBatch(start: Int, prefixHash: String?) {
        batchStart = start
        batchPrefixHash = prefixHash
    }

    private fun clearBatch() {
        batchStart = null
        batchPrefixHash = null
    }

    /** After a rewrite moved every index, local turns sit after whatever window was adopted. */
    private fun reanchorBatch() {
        val state = _ui.value
        if (state.localTurns.isEmpty()) {
            clearBatch()
        } else {
            pinBatch(state.loadedEnd, batchPrefixHash(state.window, state.turns))
        }
    }

    /** Where the next round starts, for the "did it run / has it landed" reads. See [turnBaseline]. */
    private fun captureTurnBaseline() {
        val state = _ui.value
        turnBaseline = batchStart ?: state.globalTurnCount
        turnPriorPrompts = promptCount(state.localTurns)
    }

    /**
     * After a local hand-off, read back what the server records about the batch's turns —
     * usage, duration, model, completion time, all shown in the reply footer — and fill them
     * in, never touching what streamed (codeg web 0.44 `syncTurnMetadata`, TurnMetadata.kt).
     * Each read also refreshes the conversation's summary and stats.
     *
     * Reads from the batch start and trusts a read only when its prefix fingerprint proves it
     * opens there; the transcript can lag the turn's end by seconds, so it backs off and
     * retries ([metadataSyncStep]). The next hand-off restarts it for the whole batch, and a
     * read taking the local turns' place ends it.
     */
    private fun syncTurnMetadata() {
        val c = client ?: return
        val id = conversationId ?: return
        val start = batchStart ?: return
        val prefixHash = batchPrefixHash
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            var usageOnlyRetries = 0
            for (wait in TURN_METADATA_SYNC_DELAYS_MS) {
                delay(wait)
                // A send in flight will hand off again, and restart this for the whole batch.
                if (batchStart != start || _ui.value.isInFlight) return@launch
                val read = orNull { c.conversationDetail(id, TurnWindow.FromIndex(start)) } ?: continue
                if (batchStart != start) return@launch
                _ui.update { it.copy(summary = read.summary, sessionStats = read.sessionStats ?: it.sessionStats) }
                // A pre-0.24.0 server answers in full with nothing to verify a cut against.
                val window = read.window ?: return@launch
                val verified = prefixHash != null && window.offset == start && window.prefixHash == prefixHash
                if (verified) {
                    _ui.update { it.copy(localTurns = applyMetadataPatches(it.localTurns, turnMetadataPatches(it.localTurns, read.turns))) }
                }
                val step = metadataSyncStep(_ui.value.localTurns, verified, window.offset, start, usageOnlyRetries)
                if (step == MetadataSyncStep.STOP) return@launch
                if (_ui.value.localTurns.lastOrNull { it.role == TurnRole.ASSISTANT }?.completedAt != null) usageOnlyRetries += 1
            }
        }
    }

    // region Send queue

    /** Drop a queued message. */
    fun removeQueued(id: String) = queue.remove(id)

    /** "Send now" on a queue paused by a failure. */
    fun resumeQueue() = queue.resume()

    /**
     * Take a queued message back out to edit it: its images return to the composer, its
     * text is handed to the caller (which owns the draft). Null when it already went out.
     */
    fun takeQueued(id: String): QueuedPrompt? {
        val item = _ui.value.queue.firstOrNull { it.id == id } ?: return null
        _ui.update {
            it.withQueue(it.queue.filterNot { q -> q.id == id })
                .copy(attachments = (it.attachments + item.attachments).distinctBy { a -> a.id })
        }
        return item
    }

    /**
     * The send bounced off a turn this client didn't know was running: undo its optimistic
     * turn and put it back in the queue — at the head when it was already next in line (or
     * must be), at the tail when it came straight from the composer. Unlike a failed send it
     * keeps the stream: the busy turn is exactly what it should now show.
     */
    private fun bounceToQueue(userTurn: MessageTurn, item: QueuedPrompt, origin: SendOrigin) {
        queue.noteBounce()
        liveBuilder = null
        ownPendingTurn = null
        _ui.update {
            it.copy(
                pendingUserTurns = it.pendingUserTurns.filterNot { t -> t.id == userTurn.id },
                live = null,
                isInFlight = false,
                sendStatus = null,
                queue = SendQueueController.queued(it.queue, item, front = origin != SendOrigin.COMPOSER),
            )
        }
    }

    // endregion

    /**
     * A send failed (not a bounce: [bounceToQueue]). Undo its optimistic turn and give the
     * message back — a queued one to the head of the queue, anything else to the composer —
     * and pause whatever is queued until the user says go on ([pausedAfterFailure]).
     */
    private fun discardOptimistic(
        userTurn: MessageTurn,
        text: String,
        attachments: List<Attachment>,
        origin: SendOrigin,
        createdConversation: Boolean,
        createdConversationId: Int?,
        /** Null when a banner already says what went wrong. */
        notice: String?,
    ) {
        // A queued message is the queue's to give back: into the composer it would either be
        // dropped (the screen keeps a draft the user is writing) or overtaken by those behind.
        val requeue = if (origin == SendOrigin.QUEUE) QueuedPrompt(UUID.randomUUID().toString(), text, attachments) else null
        liveBuilder = null
        closeStream()
        // A failed FIRST send may have created a conversation row up front; delete THAT exact
        // orphan (by the id this send created, not the current conversationId) so it doesn't
        // linger, and never touch a conversation a later send may already be using.
        if (createdConversationId != null) {
            viewModelScope.launch {
                runCatching { client?.deleteConversation(createdConversationId) }
                repository.notifyConversationsChanged()
            }
        }
        // Reopen the draft whenever this was a first send that failed before the prompt landed —
        // whether the row was created-then-rolled-back OR creation itself failed (id == null) — so
        // the agent/folder pickers are editable for the retry. iOS resets first-send state whenever
        // no conversation exists.
        if (createdConversation) {
            conversationId = null
            externalId = null
            connectionId = null
            hasStartedFirstSend = false
        }
        _ui.update {
            // Paused in the same write that clears `isInFlight`, so the flush never sees the
            // queue idle and unheld in between.
            it.pausedAfterFailure(requeue).copy(
                pendingUserTurns = it.pendingUserTurns.filterNot { t -> t.id == userTurn.id },
                live = null,
                isInFlight = false,
                sendStatus = null,
                notice = notice ?: it.notice,
                // Restore the composer so a failed send never silently loses the user's work —
                // but don't clobber fresh input the user added during the in-flight window (the
                // draft guard lives in the screen; attachments are owned here). iOS parity.
                restoreDraft = if (requeue != null) it.restoreDraft else text.ifEmpty { null },
                attachments = if (requeue != null || it.attachments.isNotEmpty()) it.attachments else attachments,
                summary = if (createdConversation) null else it.summary,
                isNew = if (createdConversation) true else it.isNew,
                isDraftEditable = if (createdConversation) true else it.isDraftEditable,
            )
        }
    }

    /** The screen consumes [SessionDetailUiState.restoreDraft] back into the composer once,
     *  then calls this so a recomposition doesn't re-apply it. */
    fun consumeRestoredDraft() {
        if (_ui.value.restoreDraft != null) _ui.update { it.copy(restoreDraft = null) }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    // endregion

    // region Live emit coalescing (~50ms, matching iOS LiveTextRun)

    private fun scheduleLiveEmit() {
        if (liveFlushScheduled) return
        liveFlushScheduled = true
        viewModelScope.launch {
            delay(50)
            liveFlushScheduled = false
            emitLiveNow()
        }
    }

    private fun emitLiveNow() {
        val snapshot = liveBuilder?.snapshot()
        _ui.update { it.copy(live = snapshot) }
    }

    private fun closeStream() {
        stream.stop()
    }

    /** Forget [connectionId]. The stream goes with it: kept attached between turns now, it
     *  would otherwise go on reporting a connection this session no longer uses. */
    private fun forgetConnection() {
        connectionId = null
        closeStream()
        _ui.update { it.withoutConnectionState() }
    }

    /** What only a live connection has: a goal control and a fork need one to go out on, and
     *  Remote Control ran in its agent process. */
    private fun SessionDetailUiState.withoutConnectionState(): SessionDetailUiState =
        if (goalActions.isEmpty() && remoteControl == null && !forkSupported) this
        else copy(goalActions = emptyList(), remoteControl = null, forkSupported = false)

    override fun onCleared() {
        sendJob?.cancel()
        closeStream()
        // Stops a file search the server may still be running for the `@` panel.
        composer.teardown()
        super.onCleared()
    }

    // endregion

    // region Conversation actions (rename / pin / status / delete)

    /** True once the session is server-linked and its summary has loaded. */
    val canManage: Boolean get() = conversationId != null && _ui.value.summary != null
    val isPinned: Boolean get() = _ui.value.summary?.pinnedAt != null

    fun rename(title: String) {
        val c = client ?: return
        val id = conversationId ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val previous = _ui.value.summary
        _ui.update { it.copy(summary = it.summary?.copy(title = trimmed)) }
        viewModelScope.launch {
            runCatching { c.renameConversation(id, trimmed) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = previous, notice = e.displayMessage()) } }
        }
    }

    fun togglePin() {
        val c = client ?: return
        val id = conversationId ?: return
        val summary = _ui.value.summary ?: return
        val pin = summary.pinnedAt == null
        // Optimistic: only `pinnedAt != null` drives the visual state, so any
        // non-null instant works as a placeholder until the list refetches.
        _ui.update { it.copy(summary = it.summary?.copy(pinnedAt = if (pin) summary.updatedAt else null)) }
        viewModelScope.launch {
            runCatching { c.setPinned(id, pin) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = summary, notice = e.displayMessage()) } }
        }
    }

    fun setStatus(status: ConversationStatus) {
        val c = client ?: return
        val id = conversationId ?: return
        val previous = _ui.value.summary
        _ui.update { it.copy(summary = it.summary?.copy(status = status)) }
        viewModelScope.launch {
            runCatching { c.setStatus(id, status.wire) }
                .onSuccess { repository.notifyConversationsChanged() }
                .onFailure { e -> _ui.update { it.copy(summary = previous, notice = e.displayMessage()) } }
        }
    }

    /** Read the open folders afresh for a move: one closed since the screen loaded is no target. */
    fun loadMoveTargets() {
        val c = client ?: return
        _ui.update { it.copy(moveTargets = null) }
        viewModelScope.launch {
            try {
                val folders = c.listOpenFolders()
                _ui.update { it.copy(moveTargets = folders) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(moveTargets = emptyList(), notice = appContext.getString(R.string.move_conversation_failed, e.displayMessage())) }
            }
        }
    }

    /**
     * Move this conversation to [target]. The server tears down the idle connection, so the next
     * send connects afresh, in the new folder, and resumes the session there; a busy one is
     * skipped instead (the outcome says why). Nothing optimistic: the server decides.
     */
    fun move(target: FolderDetail) {
        val c = client ?: return
        val id = conversationId ?: return
        if (_ui.value.isInFlight) return
        val label = FolderVisibility.displayName(target, allFolders.ifEmpty { _ui.value.moveTargets.orEmpty() })
        // Sends wait until the folder is re-pointed: one going out meanwhile would connect in
        // whichever folder it caught, and the teardown below would cut its stream.
        connectionOps += 1
        viewModelScope.launch {
            try {
                val result = try {
                    c.moveConversations(listOf(id), target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.move_conversation_failed, e.displayMessage())) }
                    return@launch
                }
                if (id in result.moved) {
                    // Off the stream first, so the connection's teardown doesn't read as a failure.
                    forgetConnection()
                    repointFolder(c, target.id)
                    _ui.update { it.copy(summary = it.summary?.copy(folderId = target.id)) }
                    repository.notifyConversationsChanged()
                }
                _ui.update { it.copy(notice = moveOutcomeText(appContext, result, label)) }
            } finally {
                releaseConnectionOp()
            }
        }
    }

    /** Moves and forks in flight: each re-points this conversation's connection or session,
     *  so sends (and the queue) wait for them. */
    private var connectionOps = 0

    private fun releaseConnectionOp() {
        connectionOps = (connectionOps - 1).coerceAtLeast(0)
        if (connectionOps == 0) queue.schedule()
    }

    /** A fork in flight: one at a time, since each re-points this conversation. */
    private var forking = false

    /**
     * Fork the session at [turnId], a reply's last transcript turn, or at the tail when null
     * ([ReplyFork.offer]). This conversation goes on in the fork — on the same connection,
     * retitled `[Fork] …`, its transcript cut back to that reply — and a new row keeps the whole
     * history (web `handleForkFromTurn`). What this screen holds of the old session goes, and
     * the forked transcript is read anew ([reloadAfterFork]).
     */
    fun forkFrom(turnId: String?) {
        val c = client ?: return
        val conn = connectionId ?: return
        val id = conversationId ?: return
        if (_ui.value.isInFlight) {
            _ui.update { it.copy(notice = appContext.getString(R.string.fork_busy)) }
            return
        }
        if (forking) return
        forking = true
        // Sends wait for the fork AND the read after it: the reload replaces what this screen
        // holds, so a turn sent in between would be wiped from view.
        connectionOps += 1
        val title = _ui.value.summary?.trimmedTitle?.removePrefix(FORK_TITLE_PREFIX)?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            try {
                val result = c.fork(conn, id, folder?.id, turnId)
                val forkedSessionId = result.forkedSessionId.takeIf { it.isNotEmpty() }
                // The row points at the fork now: take that on at once, whether its transcript
                // lands or not — a reconnect resumes [externalId], and the old one is the
                // sibling's session. (Its `session_started` says so too.)
                forkedSessionId?.let { externalId = it }
                // Finished work of the old session: none of it is this one's any more.
                if (liveBuilder?.isStreaming != true) liveBuilder = null
                _ui.update {
                    it.copy(
                        live = if (liveBuilder == null) null else it.live,
                        backgroundTurns = emptyList(),
                        summary = forkedSessionId?.let { sid -> it.summary?.copy(externalId = sid) } ?: it.summary,
                        notice = if (title != null) appContext.getString(R.string.fork_done, title) else appContext.getString(R.string.fork_done_untitled),
                    )
                }
                repository.notifyConversationsChanged()
                if (forkedSessionId == null) {
                    // A server that names no forked session can't be checked against: read once.
                    load().join()
                } else if (!reloadAfterFork(c, id, forkedSessionId)) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.fork_history_pending)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.TurnInProgress) {
                _ui.update { it.copy(notice = appContext.getString(R.string.fork_session_busy)) }
            } catch (e: Exception) {
                // Fixed for the agent: the affordance goes.
                val unsupported = ReplyFork.isUnsupported(e)
                _ui.update {
                    it.copy(
                        forkSupported = if (unsupported) false else it.forkSupported,
                        notice = appContext.getString(R.string.fork_failed, e.displayMessage()),
                    )
                }
            } finally {
                forking = false
                releaseConnectionOp()
            }
        }
    }

    /**
     * Read the forked transcript and take it wholesale — a fresh tail, not a refresh of the held
     * window: the fork cut the history back, so the window's coordinates describe a transcript
     * this row no longer has.
     *
     * Only a read that shows the fork is taken ([ReplyFork.isApplied]): the agent writes the
     * forked transcript itself, so a read can beat the file and come back empty, and taking that
     * would replace the history with nothing. Tried again for a few seconds; false when none
     * showed it in time — the screen then keeps what it has (the fork's title and session are
     * taken on already), and reopening reads it afresh.
     */
    private suspend fun reloadAfterFork(c: CodegClient, id: Int, forkedSessionId: String): Boolean {
        repeat(ReplyFork.RELOAD_ATTEMPTS) { attempt ->
            val detail = try {
                c.conversationDetail(id, TurnWindow.Tail(INITIAL_TAIL_TURNS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (conversationId != id) return false
            if (detail != null) {
                if (ReplyFork.isApplied(detail, forkedSessionId)) {
                    adoptDetail(detail)
                    return true
                }
                // The row is right even before its transcript is there.
                if (detail.summary.externalId == forkedSessionId) _ui.update { it.copy(summary = detail.summary) }
            }
            if (attempt < ReplyFork.RELOAD_ATTEMPTS - 1) delay(ReplyFork.reloadDelayMs(attempt))
        }
        return false
    }

    /** A one-line notice from a string resource (a fork the footer can't send yet says why). */
    fun showNotice(@androidx.annotation.StringRes message: Int) = _ui.update { it.copy(notice = appContext.getString(message)) }

    /** Delete the conversation; [onDeleted] runs (on the main thread) only after the server confirms. */
    fun deleteConversation(onDeleted: () -> Unit) {
        val c = client ?: return
        val id = conversationId ?: return
        viewModelScope.launch {
            runCatching { c.deleteConversation(id) }
                .onSuccess { repository.notifyConversationsChanged(); onDeleted() }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage()) } }
        }
    }

    // endregion

    // region Compose insert (quick messages / experts / slash commands)

    /** The session's agent — drives the expert mention prefix (`$` for codex, `/` otherwise). */
    val agentForInsert: AgentType get() = agent

    suspend fun loadQuickMessages(): List<QuickMessage> =
        client?.let { runCatching { it.quickMessagesList() }.getOrDefault(emptyList()) } ?: emptyList()

    /** Experts linked to this session's agent (the insertable set). */
    suspend fun loadExpertsForInsert(): List<ExpertListItem> {
        val c = client ?: return emptyList()
        return runCatching { c.expertsListForAgent(agent) }.getOrDefault(emptyList())
    }

    /** The commands the agent advertises, all of them, as the web lists them — the same list
     *  the composer's inline `/` menu shows ([ComposerSuggestions.loadCommands]). */
    suspend fun loadSlashCommands(): List<AvailableCommandInfo> = composer.loadCommands()

    /**
     * The agent's commands, cheapest source first: the live connection this screen holds (works
     * before it is bound to the conversation), the conversation's bound one, and — only when
     * [allowProbe] — a throwaway probe agent (`acp_describe_agent_options`), which is how a
     * draft or an idle session with no live connection gets a list at all. Null when none had
     * any.
     */
    private suspend fun fetchComposerCommands(allowProbe: Boolean): List<AvailableCommandInfo>? {
        val c = client ?: return null
        connectionId?.let { conn ->
            orNull { c.sessionSnapshot(conn) }?.availableCommands?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        conversationId?.let { id ->
            orNull { c.sessionSnapshotByConversation(id) }?.availableCommands?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (!allowProbe) return null
        return orNull { c.describeAgentOptions(agent, folder?.path) }?.availableCommands
    }

    suspend fun knownExpertIds(): Set<String> =
        client?.let { runCatching { it.expertsList().map { e -> e.id }.toSet() }.getOrDefault(emptySet()) } ?: emptySet()

    // endregion

    // region Attachments

    /** Prepare + add picked images (downscaled/encoded off-thread), enforcing the count + total-byte caps. */
    fun addAttachments(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var skipped = 0
            for (uri in uris) {
                if (_ui.value.attachments.size >= AttachmentPrep.MAX_COUNT) { skipped++; continue }
                val att = AttachmentPrep.fromUri(uri, resolver)
                if (att == null) { skipped++; continue }
                val currentBytes = _ui.value.attachments.sumOf { it.base64.length }
                // base64 length ≈ 4/3 of raw bytes; cap on the aggregate raw estimate.
                if (currentBytes + att.base64.length > AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3) { skipped++; continue }
                _ui.update { it.copy(attachments = it.attachments + att) }
                added++
            }
            if (skipped > 0) _ui.update { it.copy(notice = "Added $added image(s); $skipped skipped (too large or over the limit).") }
        }
    }

    fun removeAttachment(id: String) {
        _ui.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }

    // endregion

    // region Workspace (draft agent/folder pickers + git branch)

    /** Change the draft's agent before the first send. Drops any resolved
     *  connection so the next apply/send re-resolves against the new agent. */
    fun selectAgent(newAgent: AgentType) {
        if (!_ui.value.isDraftEditable || newAgent == agent) return
        agent = newAgent
        forgetConnection()
        // Drop any pushed selector state with the connection it described — an option id the
        // old agent reported very likely exists on the new one too, meaning something else
        // entirely.
        _ui.update { it.copy(agent = newAgent, liveSelectors = null) }
        composer.screenDidLoad()
    }

    /** Change the draft's folder before the first send (the agent's working dir),
     *  refreshing the branch label and dropping any resolved connection. */
    fun selectFolder(newFolder: FolderDetail) {
        if (!_ui.value.isDraftEditable || newFolder.id == folder?.id) return
        folder = newFolder
        currentBranch = newFolder.gitBranch
        forgetConnection()
        _ui.update {
            it.copy(
                folderName = FolderVisibility.displayName(newFolder, allFolders),
                folderPath = newFolder.path,
                selectedFolderId = newFolder.id,
                currentBranch = newFolder.gitBranch,
                // As in [selectAgent]: the pushed state belongs to the connection this switch
                // just abandoned.
                liveSelectors = null,
            )
        }
        composer.screenDidLoad()
    }

    /** List the folder's branches for the picker; refreshes the current-branch
     *  label opportunistically. Null when there's no git context / on failure. */
    suspend fun loadBranches(): GitBranchList? {
        val c = client ?: return null
        val path = folder?.path ?: return null
        return try {
            val list = c.gitListAllBranches(path)
            runCatching { c.getGitBranch(path) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cur ->
                currentBranch = cur
                _ui.update { it.copy(currentBranch = cur) }
            }
            list
        } catch (e: Exception) {
            _ui.update { it.copy(notice = e.displayMessage()) }
            null
        }
    }

    /** Switch to [branch] (worktree-aware, web/iOS parity). Returns the navigation
     *  intent so the picker can dismiss in place or open a session in another
     *  worktree. Surfaces failures via [SessionDetailUiState.notice]. */
    suspend fun switchBranch(branch: String, isRemote: Boolean): BranchSwitchOutcome {
        val c = client ?: return BranchSwitchOutcome.Failed
        val active = folder ?: return BranchSwitchOutcome.Failed
        if (branch == currentBranch) return BranchSwitchOutcome.Noop

        // Find where the branch is checked out (skip for a remote pick — those
        // always check out fresh in the root).
        val resolution = if (isRemote) null
        else runCatching { c.resolveWorktreeFolder(active.path, branch) }.getOrNull()
        val plan = FolderVisibility.planBranchSwitch(active, resolution, allFolders, isRemote)

        return when (plan) {
            FolderVisibility.BranchSwitchPlan.Noop -> BranchSwitchOutcome.Noop
            is FolderVisibility.BranchSwitchPlan.NavigateRegistered -> {
                // Already a registered folder — ensure it's open, then navigate.
                allFolders.firstOrNull { it.id == plan.folderId }?.let { runCatching { c.openFolder(it.path) } }
                repository.notifyFoldersChanged()
                BranchSwitchOutcome.OpenSession(plan.folderId)
            }
            is FolderVisibility.BranchSwitchPlan.NavigateExternal -> {
                // Worktree dir not registered yet — register it (parented to the root).
                val detail = runCatching { c.openWorktreeFolder(plan.path, plan.rootId) }.getOrNull()
                if (detail == null) {
                    _ui.update { it.copy(notice = appContext.getString(R.string.branch_open_worktree_failed)) }
                    BranchSwitchOutcome.Failed
                } else {
                    repository.notifyFoldersChanged()
                    BranchSwitchOutcome.OpenSession(detail.id)
                }
            }
            is FolderVisibility.BranchSwitchPlan.CheckoutInRoot -> checkoutInRoot(c, active, plan.root, branch)
        }
    }

    private suspend fun checkoutInRoot(c: CodegClient, active: FolderDetail, root: FolderDetail, branch: String): BranchSwitchOutcome {
        try {
            c.gitCheckout(root.path, branch)
        } catch (e: Exception) {
            // git refuses a branch already checked out by another worktree. Recover
            // the way the web's resolve step would — locate it and open a session there.
            recoverFromWorktreeCheckout(c, branch, root, e)?.let { return it }
            _ui.update { it.copy(notice = e.displayMessage()) }
            return BranchSwitchOutcome.Failed
        }
        return if (root.id == active.id) {
            // In place — reflect the ACTUAL resulting HEAD (a remote ref lands on its local branch).
            val actual = runCatching { c.getGitBranch(root.path) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: branch
            currentBranch = actual
            _ui.update { it.copy(currentBranch = actual) }
            BranchSwitchOutcome.SwitchedInPlace
        } else {
            // We were in a worktree; the checkout happened in the root → open a session there.
            repository.notifyFoldersChanged()
            BranchSwitchOutcome.OpenSession(root.id)
        }
    }

    private suspend fun recoverFromWorktreeCheckout(c: CodegClient, branch: String, root: FolderDetail, error: Exception): BranchSwitchOutcome? {
        runCatching { c.resolveWorktreeFolder(root.path, branch) }.getOrNull()?.path?.let { path ->
            return openWorktreeSession(c, path, root)
        }
        worktreePathFromError(error.displayMessage())?.let { path ->
            return openWorktreeSession(c, path, root)
        }
        return null
    }

    private suspend fun openWorktreeSession(c: CodegClient, path: String, root: FolderDetail): BranchSwitchOutcome? {
        val detail = runCatching { c.openWorktreeFolder(path, root.id) }.getOrNull() ?: return null
        repository.notifyFoldersChanged()
        return BranchSwitchOutcome.OpenSession(detail.id)
    }

    /** Extract the worktree path from git's "already used by worktree at '<path>'"
     *  checkout error, or null when the message isn't that collision. */
    private fun worktreePathFromError(message: String): String? {
        if (!message.contains("already used by worktree")) return null
        val start = message.indexOf("at '").takeIf { it >= 0 }?.plus(4) ?: return null
        val end = message.indexOf('\'', start).takeIf { it >= 0 } ?: return null
        return message.substring(start, end).takeIf { it.isNotEmpty() }
    }

    /** Create [name] off [from] (null = current HEAD) and check it out. */
    suspend fun createBranch(name: String, from: String?): Boolean {
        val c = client ?: return false
        val path = folder?.path ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return try {
            c.gitNewBranch(path, trimmed, from)
            currentBranch = trimmed
            _ui.update { it.copy(currentBranch = trimmed) }
            true
        } catch (e: Exception) {
            _ui.update { it.copy(notice = e.displayMessage()) }
            false
        }
    }

    // endregion

    // region Agent options (mode / config) — see [AgentOptionsController]

    /** See [AgentOptionsController.loadAgentOptions]. */
    suspend fun loadAgentOptions(): AgentOptionsData = options.loadAgentOptions()

    /** See [AgentOptionsController.markSelectorsObserved]. */
    internal fun markSelectorsObserved(selectors: Collection<SelectorKey>) = options.markSelectorsObserved(selectors)

    /** See [AgentOptionsController.applyMode]. */
    fun applyMode(modeId: String, previous: String?): Deferred<ApplyOutcome> = options.applyMode(modeId, previous)

    /** See [AgentOptionsController.applyConfig]. */
    fun applyConfig(configId: String, valueId: String, previous: String?): Deferred<ApplyOutcome> =
        options.applyConfig(configId, valueId, previous)

    /** See [AgentOptionsController.refreshLiveSelectors]. */
    suspend fun refreshLiveSelectors() = options.refreshLiveSelectors()

    /** What the options button summarizes. See [OptionsSummary]. */
    internal val optionsSummary: StateFlow<OptionsSummary?> get() = options.summary.state

    // endregion
}

/**
 * A refreshed transcript, and whether getting it cost a re-anchor — i.e. the
 * window the caller asked to refresh was gone and the tail was taken instead.
 *
 * [reanchored] is not bookkeeping: it says the transcript was REWRITTEN, so every
 * turn index moved and any count captured before it describes turns that no
 * longer exist. Anything comparing against such a count has to stand down rather
 * than compare across the rewrite.
 */
private data class WindowFetch(val detail: ConversationDetail, val reanchored: Boolean)
