package app.codeg.android.feature.sessiondetail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import app.codeg.android.core.model.FeedbackNote
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanApprovalDecision
import app.codeg.android.core.model.PromptInputBlock
import app.codeg.android.core.model.QuickMessage
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import app.codeg.android.core.model.SessionStats
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindow
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.conversationMoved
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isFeedbackDisabled
import app.codeg.android.core.network.isNoActiveTurn
import app.codeg.android.core.network.isStaleConnection
import app.codeg.android.feature.sessiondetail.interactive.ParsedPermission
import app.codeg.android.feature.sessiondetail.live.SessionStream
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

/** [verifyUnseenTurn]: transcript checks before leaving the turn to its live events. */
private const val VERIFY_UNSEEN_ATTEMPTS = 5

private const val INITIAL_TAIL_TURNS = 120
private const val OLDER_TURNS_PAGE_SIZE = 120

/** Transcript polls a turn that ended without a `turn_complete` gets to judge. */
private const val TURN_RECONCILE_ATTEMPTS = 5

/** Consecutive history pages that add nothing before the loader row stops trying. */
private const val MAX_PAGE_STALLS = 3

/** Bounded snapshot poll after an options apply (~2.3s total) — `set_*` only enqueues
 *  the change server-side, so we reconcile against the authoritative snapshot. */
private const val RECONCILE_ATTEMPTS = 6

/** Wall-clock cap on that poll. The delays alone sum to 2.35s, but a snapshot request can block
 *  far longer on the HTTP timeout, so the attempt count bounds nothing on its own. */
private const val RECONCILE_BUDGET_MS = 3_000L

/** How long a reopening options sheet waits for applies started by a previous one. Long enough
 *  for one full [RECONCILE_BUDGET_MS] plus its send, so the common case (one apply, still
 *  polling) resolves rather than times out — but bounded, because the sheet shows a spinner
 *  until it returns. */
private const val APPLY_SETTLE_BUDGET_MS = 5_000L

/** Attempts a selector refresh gets before giving up. A read that overlapped an apply is
 *  abandoned rather than folded (see `refreshLiveSelectors`), and abandoning without retrying
 *  would silently drop the correction it was sent to fetch. */
private const val SELECTOR_REFRESH_ATTEMPTS = 3

/** Gap between those attempts. An overlapping apply is only worth waiting out briefly: past
 *  that, the user is actively driving the values and each apply reconciles its own. */
private const val SELECTOR_REFRESH_RETRY_MS = 400L

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
    /** In-flight options-connection resolve plus the agent/folder it targets. Shared across
     *  concurrent applies so a rapid mode+config change spawns ONE connection, but keyed by
     *  agent/folder so a draft's agent/folder switch doesn't reuse a now-stale-context connect.
     *  Mirrors the iOS `AgentOptionsModel.sharedConnection()`. Cleared when the connect settles. */
    private var optionsConnect: OptionsConnect? = null
    /** Selector ownership + value provenance shared across in-flight applies. See [startApply]. */
    private val applyRegistry = ApplyRegistry()
    /** Applies still running, keyed by apply token, so a reopened sheet can wait for them instead
     *  of loading a snapshot the in-flight change hasn't reached yet. */
    private val inFlightApplies = mutableMapOf<Long, InFlightApply>()
    /** Monotonic counter stamped onto every selector push. Never reset — see [LiveSelectors]. */
    private var selectorRevision = 0
    /** True while a reattach still owes its first snapshot seed. If the socket drops
     *  mid-handshake, the reconnect re-requests the seed instead of streaming blind. */
    private var pendingReattachSeed = false
    private var liveBuilder: LiveTurnBuilder? = null
    private var liveFlushScheduled = false
    /**
     * How many turns the FULL transcript held when the current turn started —
     * global, not window-relative. `reconcileAfterTurn` compares the server's
     * total against it to decide whether the reply landed; comparing loaded
     * lengths instead would misfire the moment a window slides or a page is
     * prepended.
     */
    private var turnBaseline = 0
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
                if (_ui.value.isInFlight) reconcileAfterTurn()
            }

            // Reconnecting kept failing: fall back to the transcript so the reply isn't lost.
            override fun onGaveUp() {
                if (_ui.value.isInFlight) reconcileAfterTurn()
            }
        },
    )

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
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
                    val detail = c.conversationDetail(id, TurnWindow.Tail(INITIAL_TAIL_TURNS))
                    agent = detail.summary.agentType
                    externalId = detail.summary.externalId
                    folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
                    currentBranch = detail.summary.gitBranch ?: folder?.gitBranch
                    _ui.update {
                        it.copy(
                            loading = false,
                            summary = detail.summary,
                            turns = detail.turns,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage()) }
            }
        }
    }

    // region Send / cancel

    fun send(text: String) = send(text, withAttachments = true)

    /**
     * Send the composer's draft — or, with [withAttachments] `false`, a prompt the app
     * itself generated (today: the revision notes from a plan-approval "request
     * changes", which Grok expects as a follow-up turn). A generated send never
     * consumes the composer's pending attachments, so images the user was staging
     * survive.
     */
    private fun send(text: String, withAttachments: Boolean) {
        val trimmed = text.trim()
        val attachments = if (withAttachments) _ui.value.attachments else emptyList()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || _ui.value.isInFlight) return

        // Supersede any post-turn reconcile still running for the previous turn so it can't
        // wipe the turn we're about to start (its identity guard is the backstop).
        reconcileJob?.cancel()
        // A previous turn that finished / cancelled / errored but was never reconciled into
        // `turns` still lives in `liveBuilder`; fold it in before we reuse the live slot so
        // its (partial) reply isn't dropped by this send. Mirrors iOS `send()`.
        liveBuilder?.takeIf { !it.isStreaming && !it.isEmpty }?.let { promoteUnreconciled() }

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
        turnBaseline = _ui.value.globalTurnCount
        // Steering notes belong to the turn that was running when they were written; the
        // server drops them on this send's `user_message` and so do we. `isInFlight` above
        // is optimistic — it covers connect and attach, during which the server has no turn
        // to steer — so the note affordance stays shut until the server says otherwise.
        notes.clear()
        _ui.update { it.copy(noteTurnOpen = false) }
        _ui.update {
            it.copy(
                pendingUserTurns = it.pendingUserTurns + userTurn,
                live = builder.snapshot(),
                liveFromReattach = false,
                isInFlight = true,
                sendStatus = "Connecting…",
                notice = null,
                restoreDraft = null,
                attachments = if (withAttachments) emptyList() else it.attachments,
                isDraftEditable = false,
            )
        }
        sendJob = viewModelScope.launch { runSend(trimmed, userTurn, attachments, wasNew) }
    }

    private suspend fun runSend(
        text: String,
        userTurn: MessageTurn,
        attachments: List<Attachment>,
        createdConversation: Boolean,
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
            discardOptimistic(userTurn, text, attachments, createdConversation, createdId, notice = "A turn is already running on this session.")
        } catch (e: Exception) {
            discardOptimistic(userTurn, text, attachments, createdConversation, createdId, notice = e.displayMessage())
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
        val id = c.createConversation(f.id, agent, title = firstPrompt.take(60))
        conversationId = id
        _ui.update { it.copy(isNew = false) }
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
        resetSelectorsForConnection(conn)
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
        adoptSnapshotSelectors(snapshot)
        if (pendingObserve && cause == SessionStream.AttachCause.ATTACH) {
            pendingObserve = false
            // A turn that started between the transcript load and this attach is shown as it
            // runs, like any other observed turn.
            if (liveBuilder?.isStreaming != true && snapshotTurnRunning(snapshot)) {
                beginObservedTurn()
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
            ReconnectRecovery.ENDED_UNSEEN -> finalizeTurn(STOP_REASON_UNSEEN)
            ReconnectRecovery.VERIFY -> verifyUnseenTurn()
            ReconnectRecovery.OBSERVE -> {
                beginObservedTurn()
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
            if (builder.isEmpty) builder.error = LiveTurnError(appContext.getString(R.string.live_cancelled))
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
            turnBaseline = _ui.value.globalTurnCount
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
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            repeat(VERIFY_UNSEEN_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 300L else 1_000L)
                if (liveBuilder !== builder || ownTurnStarted || !builder.isStreaming) return@launch
                // Read from the baseline on: a tail could miss the prompt entirely behind one
                // long round (the server's window alignment walks back only so far).
                val fromBaseline = orNull { c.conversationDetail(id, TurnWindow.FromIndex(baseline)) } ?: return@repeat
                if (!sentPromptAt(fromBaseline, baseline, sent)) return@repeat
                // The window is capped, so it may stop short of the end; the round's end is
                // then read off the tail.
                val ended = if (reachesEnd(fromBaseline)) {
                    endsRound(fromBaseline.turns)
                } else {
                    orNull { c.conversationDetail(id, TurnWindow.Tail(1)) }?.let { endsRound(it.turns) } == true
                }
                if (liveBuilder !== builder || ownTurnStarted || !builder.isStreaming) return@launch
                if (ended) {
                    finalizeTurn(STOP_REASON_UNSEEN)
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
        liveBuilder?.takeIf { !it.isStreaming && !it.isEmpty }?.let { promoteUnreconciled() }
        val builder = LiveTurnBuilder()
        liveBuilder = builder
        ownTurnStarted = true
        observedTurn = true
        turnBaseline = _ui.value.globalTurnCount
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

    /** Show or hide the session screen: a hidden one keeps no socket open, and picks the
     *  stream back up from its cursor when shown again. */
    fun setScreenVisible(visible: Boolean) {
        if (visible == screenVisible) return
        screenVisible = visible
        if (visible) stream.resume() else stream.pause()
    }

    private fun applyEvent(event: AcpEvent, @Suppress("UNUSED_PARAMETER") seq: Long) {
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
                _ui.update { if (it.isInFlight) it.copy(sendStatus = runningStatus()) else it }
                scheduleLiveEmit()
            }
            is AcpEvent.ToolCallUpdate -> {
                builder?.updateToolCall(event.id, event.title, event.status, event.rawInput, event.rawOutput, event.content, event.append, event.meta)
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
                ConnectionStatus.ERROR -> failLive(appContext.getString(R.string.live_agent_connection_errored))
                ConnectionStatus.PROMPTING -> {
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
            is AcpEvent.Error -> failLive(event.message, event.details)
            is AcpEvent.PermissionRequest -> _ui.update {
                it.copy(pendingPermission = PendingPermissionUi(event.requestId, ParsedPermission.parse(event.toolCall), event.options))
            }
            is AcpEvent.PermissionResolved -> _ui.update {
                if (it.pendingPermission?.requestId == event.requestId) it.copy(pendingPermission = null) else it
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
            is AcpEvent.FeedbackSubmitted -> notes.onSubmitted(event.note)
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
            -> pushSelectors { held, revision -> held.foldSelectorPush(event, revision) }
            else -> Unit // session_started / user_prompt_sent / etc. are informational
        }
    }

    /**
     * Drop pushed selector state belonging to a connection other than the one about to be
     * attached.
     *
     * Every field of it — both catalogs, the current values, the readiness latch — is a report
     * about one agent process, and a second connection for the same agent can advertise a
     * different catalog. It cannot be corrected by the new stream either: `event_seq` is counted
     * per connection server-side, so an attach carrying the previous connection's cursor replays
     * only what lies beyond that number and skips the new one's opening selectors entirely.
     * Clearing leaves the sheet's own load — which reads an authoritative snapshot — as the
     * source, which is the right fallback.
     */
    private fun resetSelectorsForConnection(conn: String) {
        _ui.update { state ->
            val held = state.liveSelectors
            if (held?.connectionId == conn && held.agent == agent) state
            else state.copy(liveSelectors = LiveSelectors(agent = agent, connectionId = conn))
        }
    }

    /**
     * Revise [SessionDetailUiState.liveSelectors] without claiming anything moved — for state
     * the sheet doesn't display, like the readiness latch.
     */
    private fun updateSelectors(update: (LiveSelectors) -> LiveSelectors) {
        _ui.update { state ->
            // A state left over from a context we have since switched away from is replaced,
            // not merged into: the same option id on another agent means something else.
            val base = state.liveSelectors?.takeIf { it.agent == agent } ?: LiveSelectors(agent)
            state.copy(liveSelectors = update(base))
        }
    }

    /**
     * [updateSelectors] for a push the sheet should pick up, handing the transform a fresh
     * revision to stamp onto the values it actually changed.
     *
     * Stamping per value rather than per push is the point: the pushes arrive independently, and
     * one shared revision would re-publish an untouched neighbour's payload under a new number
     * every time — enough to overwrite a value the sheet's load had already corrected.
     */
    private fun pushSelectors(update: (LiveSelectors, Int) -> LiveSelectors) {
        selectorRevision += 1
        val revision = selectorRevision
        updateSelectors { update(it, revision) }
    }

    /**
     * Adopt the selectors an attach snapshot carries.
     *
     * This is the ONLY recovery path for selector pushes missed while the socket was down: a
     * reconnect whose gap is too wide to replay is answered with a snapshot instead, the missed
     * `session_config_options` / `session_modes` / `mode_changed` events are never re-sent, and
     * the attach advances the event cursor past them regardless. Same reasoning as the
     * snapshot's `feedback` list.
     *
     * A half the snapshot doesn't carry leaves what was held. Absent is not the same as empty:
     * the server only fills these in once the agent has published, so absent means "nothing to
     * adopt" while empty means "published, and there is nothing to configure" — subject to the
     * same setup-transient exception [offerConfigOptions] makes.
     */
    private fun adoptSnapshotSelectors(snapshot: app.codeg.android.core.model.LiveSessionSnapshot) =
        foldSelectorSnapshot(snapshot.modes, snapshot.configOptions, snapshot.selectorsReady)

    /**
     * Fold a snapshot's selectors — from either snapshot shape — into the pushed state.
     *
     * A null half is one the snapshot doesn't carry, and leaves what was held.
     */
    private fun foldSelectorSnapshot(
        modes: SessionModeState?,
        configOptions: List<SessionConfigOption>?,
        selectorsReady: Boolean,
    ) {
        pushSelectors { held, revision ->
            // The latch first: it decides how the config half is read, and a reconnect has no
            // other way to learn it (the `selectors_ready` event it replaces is never re-sent).
            // Assigned rather than latched — the snapshot describes whichever connection this
            // is about, including a fresh one still starting up.
            var next = held.copy(selectorsReady = selectorsReady)
            configOptions?.let { next = next.offerConfigOptions(it, revision) }
            modes?.let { m ->
                next = next.copy(
                    modes = Pushed(m, revision),
                    currentModeId = m.currentModeId?.let { Pushed(it, revision) } ?: next.currentModeId,
                )
            }
            next
        }
    }

    /**
     * A live connection's snapshot, paired with the connection it describes.
     *
     * Two lookups, as in [loadAgentOptions] and for the same reasons — plus one this path needs
     * on its own: a cached id can OUTLIVE its connection, because the server idle-sweeps
     * connections and nothing tells this client. Preferring the cache and giving up when its
     * read fails would let one dead id hide a replacement another client has since resumed, for
     * as long as the sheet stays open. So a failed read re-resolves instead of returning.
     *
     * Resolved to an id rather than read by conversation directly, because these values are only
     * safe to keep while the connection they describe is the one being watched.
     */
    private suspend fun readConnectionSnapshot(c: CodegClient): Pair<String, SessionSnapshot>? {
        val cached = connectionId
        if (cached != null) orNull { c.sessionSnapshot(cached) }?.let { return cached to it }
        val found = conversationId
            ?.let { orNull { c.findConnection(it, externalId, agent)?.connectionId } }
            ?: return null
        val snapshot = orNull { c.sessionSnapshot(found) } ?: return null
        // A replacement answered, so the cached id must stop being the apply target: showing one
        // connection's values while every tap reaches another is worse than either alone —
        // against a dead id the first change fails and reverts, and against a live duplicate it
        // succeeds somewhere the user isn't looking. Dropping it is the conclusion
        // `dropStaleOptionsConnection` already draws from a failed apply, and leaves the next one
        // to re-resolve. Guarded on the turn for the same reason it is there: a live turn's Stop
        // and stream reconnect still need the id.
        //
        // Only here, never on the way past a failed read. Without a replacement to show there is
        // no disagreement to fix, and clearing would orphan a connection over a network blip —
        // and for a draft, whose lookup has no conversation to go on, that is EVERY blip.
        //
        // The caller reads this as a connection change and abandons the attempt it is on; its
        // retry is what folds the replacement in.
        if (found != cached && connectionId == cached && !_ui.value.isInFlight) forgetConnection()
        return found to snapshot
    }

    /**
     * Re-read the connection's selectors over HTTP and fold them in as if they had been pushed.
     *
     * The one thing the event stream cannot cover: it is closed at the end of a turn, and the
     * server can still change an option after that. A turn that ends in an *error* is the sharp
     * case — the failure event is emitted before `turn_complete`, and the connection may then
     * spend up to two seconds finalising its transcript before it drains a config update the
     * agent had already queued. This client is long gone by then.
     *
     * Deliberately folded through [LiveSelectors] rather than surfaced as a sheet reload: the
     * push path already holds back an option whose apply is in flight, and it disturbs neither
     * the apply locks nor the sheet's context epoch.
     *
     * That holding-back covers an apply still running when the result lands, but not one that
     * starts or finishes inside this read: the read describes the session as it was BEFORE such
     * a pick, so folding it in would roll back a change the user watched take effect. Any apply
     * activity across the read therefore abandons it — [ApplyRegistry.generation] counts both
     * ends, because a completion moves the value just as a start does.
     *
     * Abandoning is deliberately coarse: an `effort` apply discards a read that was recovering
     * `auto_approve`. Retrying makes that cost latency rather than the correction — up to a
     * point. Sustained churn across all [SELECTOR_REFRESH_ATTEMPTS] attempts, and across the
     * second read the caller schedules, does lose it until the sheet's next load. Fixing that
     * properly means publishing each apply's own reconciled value instead of re-reading around
     * it, which is what the apply-outcome retention work is for; the VM cannot substitute a
     * value here, because for a selector an apply just moved, the only right answer is that
     * apply's outcome, and that lives in the sheet.
     */
    suspend fun refreshLiveSelectors() {
        val c = client ?: return
        repeat(SELECTOR_REFRESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SELECTOR_REFRESH_RETRY_MS)
            val cached = connectionId
            val applies = applyRegistry.generation
            // Retry a failed read too: nothing has been decided yet, and the usual cause is a
            // blip rather than a connection that is really gone.
            val (conn, snapshot) = readConnectionSnapshot(c) ?: return@repeat
            // Retry rather than return: the read is still wanted, it just can't be trusted to
            // describe the values as they stand now. Compared against what was CACHED, not
            // against `conn` — a resolved-but-uncached connection leaves the field null, and
            // that is not a change.
            if (connectionId != cached || applyRegistry.generation != applies) return@repeat
            resetSelectorsForConnection(conn)
            // Unlike the WS snapshot, this shape can't say "published nothing" — its list is
            // non-null with an empty default — so an empty one is only meaningful once the latch
            // says the agent has finished publishing. Below that, offer nothing rather than park
            // an emptiness that was never reported.
            val options = snapshot.configOptions.takeIf { it.isNotEmpty() || snapshot.selectorsReady }
            foldSelectorSnapshot(snapshot.modes, options, snapshot.selectorsReady)
            return
        }
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
        viewModelScope.launch {
            runCatching { client?.answerPlanApproval(conn, pending.approvalId, decision, notes.ifEmpty { null }) }
                .onSuccess {
                    if (decision != PlanApprovalDecision.REQUEST_CHANGES || notes.isEmpty()) return@onSuccess
                    when (
                        planFollowUpAction(
                            sameTurn = liveBuilder === origin,
                            inFlight = _ui.value.isInFlight,
                            stopReason = origin?.stopReason,
                            errorMessage = origin?.error?.message,
                        )
                    ) {
                        PlanFollowUp.PARK -> pendingPlanFollowUp = origin?.let { ParkedPlanNotes(it.id, notes) }
                        PlanFollowUp.SEND_NOW -> send(notes, withAttachments = false)
                        PlanFollowUp.DROP ->
                            _ui.update { it.copy(notice = appContext.getString(R.string.plan_approval_notes_dropped)) }
                    }
                }
                .onFailure { e -> _ui.update { it.copy(notice = e.displayMessage(), pendingPlanApproval = pending) } }
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
            refetchWindow(c, id)?.let { (d, _) ->
                if (!isLatestFetch(generation)) return@launch
                _ui.update {
                    it.copy(
                        turns = d.turns,
                        window = d.window,
                        inFlightUserTurnId = d.inFlightUserTurnId,
                        sessionStats = d.sessionStats ?: it.sessionStats,
                        summary = d.summary,
                    )
                }
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
        if (!loaded.hasOlder || pagingJob?.isActive == true) return
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

    private fun restorePending(snap: app.codeg.android.core.model.LiveSessionSnapshot) {
        snap.pendingPermission?.let { p ->
            _ui.update { it.copy(pendingPermission = PendingPermissionUi(p.requestId, ParsedPermission.parse(p.toolCall), p.options)) }
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


    private fun finalizeTurn(stopReason: String) {
        // Once per turn: a cancel is answered by the server's own `turn_complete`, and the
        // agent may send a second one afterwards.
        val builder = liveBuilder?.takeIf { it.stopReason == null } ?: return
        builder.isStreaming = false
        builder.stopReason = stopReason
        emitLiveNow()
        // Return the composer to Send and clear the status line immediately — the turn is done.
        // The transcript keeps showing the finished live reply until `reconcileAfterTurn` swaps
        // in the server copy in the background. Mirrors iOS's computed `isInFlight`, which flips
        // the instant `live.isStreaming` goes false rather than waiting on the reconcile.
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
        reconcileAfterTurn()
        // The keep-planning turn just ended — deliver the revision notes as the follow-up
        // prompt Grok expects (it discards them on the reply itself). `send` supersedes
        // the reconcile it just started and folds the finished reply in locally, exactly
        // as a user typing the instant a turn ends would.
        if (planFollowUp != null) send(planFollowUp, withAttachments = false)
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
        builder.error = LiveTurnError(message, details?.trim()?.ifEmpty { null })
        builder.isStreaming = false
        emitLiveNow()
        clearInteractivePrompts()
    }

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
                // A failed round ends on its `turn_error` record, not on a reply — without
                // that arm a failed turn never reads as landed and falls to local promotion.
                val endsInReply = endsRound(detail.turns)

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
                            window = detail.window,
                            inFlightUserTurnId = detail.inFlightUserTurnId,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
                        )
                    }
                    break
                }

                // Counted against the FULL transcript, not the loaded window: a window
                // that slid (or a page that was prepended) moves the loaded length
                // without a turn being added, which would read as the reply landing.
                val advanced = endsInReply && detail.effectiveTotal > turnBaseline
                if (advanced) {
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
                            window = detail.window,
                            inFlightUserTurnId = detail.inFlightUserTurnId,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
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
                    liveBuilder = null
                    return@launch
                }
            }
            // Couldn't confirm — fold the streamed reply in locally so nothing is lost.
            if (liveBuilder === builder) promoteUnreconciled()
        }
    }

    private fun promoteUnreconciled() {
        val builder = liveBuilder
        _ui.update { state ->
            // On a reattach-owned reply, the tail of `turns` is the server's partial copy
            // of the same reply — drop it so folding the live turn doesn't double it.
            val base = if (state.liveFromReattach) {
                state.turns.hideInFlightReply(state.inFlightUserTurnId)
            } else {
                state.turns
            }
            val folded = buildList {
                addAll(base)
                addAll(state.pendingUserTurns)
                // Only fold in an assistant turn that has renderable blocks. A finalized
                // builder can be non-empty solely because of an inline error / "Cancelled."
                // message (which snapshotAsMessageTurn can't represent as a block), and folding
                // its zero-block snapshot would render as an empty "No content" turn (iOS parity).
                if (builder != null && !builder.isEmpty) {
                    val snapshot = builder.snapshotAsMessageTurn()
                    if (snapshot.blocks.isNotEmpty()) add(snapshot)
                }
            }
            state.copy(
                turns = folded,
                // The round is over as far as this client knows; nothing in `folded` is
                // still running.
                inFlightUserTurnId = null,
                pendingUserTurns = emptyList(),
                live = null,
                liveFromReattach = false,
                isInFlight = false,
                sendStatus = null,
            )
        }
        liveBuilder = null
    }

    private fun discardOptimistic(
        userTurn: MessageTurn,
        text: String,
        attachments: List<Attachment>,
        createdConversation: Boolean,
        createdConversationId: Int?,
        notice: String,
    ) {
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
            it.copy(
                pendingUserTurns = it.pendingUserTurns.filterNot { t -> t.id == userTurn.id },
                live = null,
                isInFlight = false,
                sendStatus = null,
                notice = notice,
                // Restore the composer so a failed send never silently loses the user's work —
                // but don't clobber fresh input the user added during the in-flight window (the
                // draft guard lives in the screen; attachments are owned here). iOS parity.
                restoreDraft = text.ifEmpty { null },
                attachments = if (it.attachments.isEmpty()) attachments else it.attachments,
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
    }

    override fun onCleared() {
        sendJob?.cancel()
        closeStream()
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

    /** Slash commands from the live snapshot, excluding expert-backed names. Returns
     *  the commands plus the known-expert id set (so the UI can strip an existing
     *  expert mention when inserting another). */
    suspend fun loadSlashCommands(): List<AvailableCommandInfo> {
        val c = client ?: return emptyList()
        val id = conversationId ?: return emptyList()
        val snapshot = runCatching { c.sessionSnapshotByConversation(id) }.getOrNull() ?: return emptyList()
        val knownExperts = runCatching { c.expertsList().map { it.id }.toSet() }.getOrDefault(emptySet())
        return snapshot.availableCommands.filter { it.name !in knownExperts }
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

    // region Agent options (mode / config)

    /** Load the session's modes + config plus the selection to highlight. Any live
     *  connection's snapshot is authoritative *once it has published*; failing that it
     *  probes the per-agent catalog (slower) and pre-selects the user's remembered pick
     *  (validated against the catalog), falling back to the catalog's fresh-session defaults.
     *  A live session that advertises neither modes nor config options therefore takes the
     *  probe path too. iOS parity. */
    suspend fun loadAgentOptions(): AgentOptionsData {
        val c = client ?: return AgentOptionsData()
        // Let any apply still running for THIS agent finish first. Its change is on its way to
        // the agent, so a snapshot taken now would show the pre-apply value and the sheet would
        // sit on it — the apply's own awaiter is gone (it belonged to the sheet that started it)
        // and nothing else consumes the server's config-option events yet. Applies for other
        // agents can't affect this load, and waiting on them would just stall the sheet.
        val settling = inFlightApplies.values.filter { it.agent == agent }.map { it.deferred }
        if (settling.isNotEmpty()) {
            withTimeoutOrNull(APPLY_SETTLE_BUDGET_MS) { settling.forEach { orNull { it.await() } } }
        }
        // Two ways to reach the same session, and neither covers the other. A draft has no
        // conversation id but can still hold a connection an earlier apply opened; and a
        // conversation's resumed connection isn't registered under its id until the first
        // prompt, so the by-conversation lookup can miss a connection that exists. Trying only
        // the one that matches the current shape would show the remembered preference as if it
        // were the session's state. `connectionId` is nulled on every agent/folder switch, so a
        // non-null one always belongs to the current context.
        val snapshot = conversationId?.let { orNull { c.sessionSnapshotByConversation(it) } }
            ?: connectionId?.let { conn -> orNull { c.sessionSnapshot(conn) } }
        // `selectorsReady` alone is enough: a session that has finished publishing and advertises
        // NEITHER is answering "nothing to configure", and that answer is authoritative. Probing
        // a throwaway process instead would put its controls on screen while every apply targets
        // this connection — which does not expose them. Without the flag the two are
        // indistinguishable, which is why the pre-0.24.0 shape had to guess from emptiness.
        //
        // A read landing inside the backend's 500ms post-latch grace can still catch a straggling
        // update mid-flight and report nothing. That window is bounded and belongs to a
        // connection that has just come up; the stale-probe alternative it replaces had no bound.
        if (snapshot != null && (snapshot.selectorsReady || snapshot.modes != null || snapshot.configOptions.isNotEmpty())) {
            // Live session — its current mode/config is authoritative, and every value here is
            // one a session just reported, so all of them can serve as reconcile baselines.
            applyRegistry.setObserved(agent, snapshot.selectorKeys(), observedValue = true)
            return AgentOptionsData(
                modes = snapshot.modes,
                configOptions = snapshot.configOptions,
                initialModeId = snapshot.modes?.currentModeId,
                initialConfig = snapshot.configOptions.currentValues(),
            )
        }
        // No live session with selectors — probe the catalog and pre-select the remembered pick
        // so the sheet opens on the user's last-used mode/config, not the server default.
        // Nothing here has been observed on a session, so none of it can serve as a reconcile
        // baseline ([ApplyContext.baseline]).
        val described = orNull { c.describeAgentOptions(agent, folder?.path) }
        val modes = described?.modes
        val options = described?.configOptions.orEmpty()
        val prefs = orNull { selectorPrefs.prefs(agent) } ?: SelectorPrefs()
        val modeId = prefs.modeId?.takeIf { saved -> modes?.availableModes?.any { it.id == saved } == true }
            ?: modes?.currentModeId
        val config = options.associate { opt ->
            val saved = prefs.configValues?.get(opt.id)?.takeIf { it in opt.kind.acceptedValues }
            opt.id to (saved ?: opt.kind.currentValue ?: "")
        }.filterValues { it.isNotEmpty() }
        applyRegistry.setObserved(
            agent,
            options.map { SelectorKey.Config(it.id) } + SelectorKey.Mode,
            observedValue = false,
        )
        return AgentOptionsData(modes, options, modeId, config)
    }

    /**
     * Record that the sheet adopted values a live push reported, so the next apply on those
     * selectors may use what is on screen as its reconcile baseline ([ApplyContext.baseline]).
     *
     * The sheet reports this rather than the event handler doing it, because only the sheet
     * knows which pushed values it actually took: it skips any selector with an apply in
     * flight, and that one is still showing an optimistic value no session has confirmed.
     */
    internal fun markSelectorsObserved(selectors: Collection<SelectorKey>) {
        if (selectors.isNotEmpty()) applyRegistry.setObserved(agent, selectors, observedValue = true)
    }

    /**
     * The shared connection, but only when it still belongs to the caller's *pinned*
     * agent/folder.
     *
     * `connectionId` is nulled on every agent/folder switch, so a non-null value always belongs
     * to the current context; if the current context no longer matches the pinned one, the
     * caller raced a switch and must not be handed the other agent's connection.
     */
    private fun reusableConnection(forAgent: AgentType, forFolderId: Int?): String? =
        connectionId?.takeIf { agent == forAgent && folder?.id == forFolderId }

    /** A live connection to apply options to. Reuses a cached/live connection, and — for a
     *  draft or idle session with none — establishes one on demand (carrying the remembered
     *  mode/config), so the pickers apply instead of silently no-op'ing. Mirrors iOS
     *  `resolveConnectionForOptions`. Concurrent applies share one in-flight resolve
     *  ([optionsConnect]) so a rapid mode+config change can't spawn two orphaned
     *  connections. Returns null only when there's no client or the connect fails. */
    private suspend fun optionsConnection(forAgent: AgentType, forFolder: FolderDetail?): String? {
        val forFolderId = forFolder?.id
        reusableConnection(forAgent, forFolderId)?.let { return it }
        val c = client ?: return null
        // Reuse an in-flight connect only when it targets the SAME agent/folder the caller pinned —
        // after a draft agent/folder switch, a reused stale-context connect would apply to the
        // wrong agent.
        val cached = optionsConnect
        val deferred = if (cached != null && cached.agent == forAgent && cached.folderId == forFolderId) {
            cached.deferred
        } else {
            // A connect for a now-abandoned agent/folder context is obsolete — cancel it so it
            // can't complete and win adoption with a preference snapshot taken before a later
            // setting was saved (a same-context connect started afterward carries the newer prefs;
            // if the stale one were adopted, the next send would reuse it and drop that setting).
            cached?.deferred?.cancel()
            val d = viewModelScope.async {
                val convId = conversationId
                val found = if (convId != null) {
                    runCatching { c.findConnection(convId, externalId, forAgent)?.connectionId }.getOrNull()
                } else null
                // Pin the connect to the captured agent/folder so a switch mid-connect can't
                // retarget it to a different agent/workspace.
                val conn = found ?: connectWithPreferences(c, forAgent, forFolder)
                // Adopt as the shared connection only if nothing else has claimed it and the
                // draft's agent/folder is still the captured one — never clobber a connection the
                // send path already established (which Stop / stream reconnect target).
                if (connectionId == null && agent == forAgent && folder?.id == forFolderId) connectionId = conn
                conn
            }
            optionsConnect = OptionsConnect(forAgent, forFolderId, d)
            // Clear the memo when the connect itself settles — not when a single awaiter is
            // cancelled — so a dismissed sheet / cancelled apply can't drop the shared reference
            // mid-flight and let the next apply spawn a second (orphaned) connection.
            d.invokeOnCompletion { if (optionsConnect?.deferred === d) optionsConnect = null }
            d
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply a mode pick. Attempts to remember it per agent (so it carries to the server on the
     * next `acp_connect` — [ApplyOutcome.Applied.remembered] says whether that write landed),
     * resolves/establishes a connection ([optionsConnection]), enqueues `set_mode`, and
     * reconciles against the authoritative snapshot.
     *
     * The value in [ApplyOutcome.Applied] is what the sheet should display, not a value the
     * agent has necessarily confirmed: when the reconcile budget elapses without the agent
     * reporting the pick, this hands back the optimistic one (see [reconcileSelection]).
     *
     * [previous] is the value the sheet was showing. It is [reconcileSelection]'s baseline for
     * telling a slow agent from a normalizing one — but only when the registry says that value
     * was observed on a live session. What a failed apply un-remembers is NOT this: it is the
     * value the save actually replaced in the store, which the sheet may never have shown.
     */
    fun applyMode(modeId: String, previous: String?): Deferred<ApplyOutcome> =
        startApply(SelectorKey.Mode) { ctx ->
            val stored = saveForApply(ctx) { selectorPrefs.saveMode(ctx.agent, modeId) }
            val undo: suspend () -> Unit = {
                selectorPrefs.restoreMode(ctx.agent, expected = modeId, previous = stored.replaced)
            }
            val conn = connectionForApply(ctx) ?: return@startApply failedApply(ctx, stored, undo)
            try {
                requireClient().acpSetMode(conn, modeId)
                reconciled(ctx, stored, reconcileSelection(conn, ctx.selector, modeId, ctx.baseline(previous)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropStaleOptionsConnection(conn)
                failedApply(ctx, stored, undo)
            }
        }

    /** Apply a config-option pick. Same contract as [applyMode]. */
    fun applyConfig(configId: String, valueId: String, previous: String?): Deferred<ApplyOutcome> =
        startApply(SelectorKey.Config(configId)) { ctx ->
            val stored = saveForApply(ctx) { selectorPrefs.saveConfig(ctx.agent, configId, valueId) }
            val undo: suspend () -> Unit = {
                selectorPrefs.restoreConfig(ctx.agent, configId, expected = valueId, previous = stored.replaced)
            }
            val conn = connectionForApply(ctx) ?: return@startApply failedApply(ctx, stored, undo)
            try {
                requireClient().acpSetConfigOption(conn, configId, valueId)
                reconciled(ctx, stored, reconcileSelection(conn, ctx.selector, valueId, ctx.baseline(previous)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropStaleOptionsConnection(conn)
                failedApply(ctx, stored, undo)
            }
        }

    /** What a pre-apply save managed to do: the value it replaced, and whether it wrote at all. */
    private class SavedPreference(val replaced: String?, val remembered: Boolean)

    /**
     * Write the preference the apply is about to send, before sending it.
     *
     * The order matters: the apply may open the connection, and `acp_connect` reads the store,
     * so saving first is what lets a fresh connection come up already holding the pick. The
     * price is that a failure afterwards has to undo it — see [failedApply].
     */
    private suspend inline fun saveForApply(ctx: ApplyContext, save: () -> String?): SavedPreference = try {
        val replaced = save()
        // Ownership follows the write, not the tap: an apply whose save fails must not take
        // responsibility for a stored value an earlier apply wrote and still owes a rollback on.
        applyRegistry.claimWrite(ctx.key, ctx.token)
        SavedPreference(replaced = replaced, remembered = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The store is unwritable (full disk, corruption). Still worth applying to the live
        // session — the user asked for it — but the sheet is told it won't be remembered, and
        // there is nothing to roll back afterwards.
        SavedPreference(replaced = null, remembered = false)
    }

    /**
     * Resolve the connection to apply against, or null when there is none to be had.
     *
     * Cancellation is deliberately NOT treated as "the agent never saw it". Resolving may mean
     * *opening* the connection, and `acp_connect` carries the preferences this apply just saved
     * — the server applies them during session init, before the connect call returns. So a
     * cancellation here can land on either side of the change having already happened, and
     * undoing the preference would risk leaving the live session switched while the remembered
     * value says otherwise. Ambiguous cancellations keep the value; see [failedApply].
     */
    private suspend fun connectionForApply(ctx: ApplyContext): String? =
        if (client == null) null else optionsConnection(ctx.agent, ctx.folder)

    private fun requireClient(): CodegClient = checkNotNull(client) { "client resolved before connect" }

    /** Everything an apply has to decide before it suspends, captured at the tap. */
    private class ApplyContext(
        val agent: AgentType,
        val folder: FolderDetail?,
        val selector: SelectorKey,
        val key: ApplyRegistry.Key,
        val token: Long,
        /** Whether the value this apply replaces was read off a live session. */
        val hadObservedValue: Boolean,
    ) {
        /**
         * [previous] as a reconcile baseline, or null when it isn't one.
         *
         * A baseline has to be a value somebody actually saw a session report. When the sheet
         * pre-selected a remembered preference against a catalog probe — or kept an optimistic
         * value a previous apply never got confirmed — the agent's starting value is unknown,
         * and guessing would let [reconcileTimeout] read the agent's untouched default as a
         * normalization.
         */
        fun baseline(previous: String?): String? = previous.takeIf { hadObservedValue }
    }

    /**
     * Run an apply on [viewModelScope], not the caller's.
     *
     * The caller is the options sheet, and dismissing it cancels its scope. An apply cancelled
     * between saving the preference and reaching the agent is the worst outcome available: the
     * value is remembered, the server never got it, and the *next* connect applies a change the
     * user watched fail to happen. Owning the coroutine here means dismissal cancels only the
     * sheet's await.
     *
     * Registers the apply so a sheet opened while it is still running can wait for it
     * ([loadAgentOptions]) instead of reading a snapshot the change hasn't reached.
     */
    private fun startApply(
        selector: SelectorKey,
        body: suspend (ApplyContext) -> ApplyOutcome,
    ): Deferred<ApplyOutcome> {
        val key = ApplyRegistry.Key(agent, selector)
        val ctx = ApplyContext(
            agent = agent,
            folder = folder,
            selector = selector,
            key = key,
            token = applyRegistry.begin(key),
            hadObservedValue = applyRegistry.isObserved(key),
        )
        val deferred = viewModelScope.async { body(ctx) }
        // Keyed by token, not selector: two applies for the same selector can genuinely overlap
        // once a context switch has released the row lock, and a map keyed by selector would let
        // whichever finished first deregister the other.
        inFlightApplies[ctx.token] = InFlightApply(ctx.agent, deferred)
        deferred.invokeOnCompletion {
            inFlightApplies.remove(ctx.token)
            // A finished apply has moved the value as surely as a starting one — see
            // [ApplyRegistry.generation] and its reader, `refreshLiveSelectors`.
            applyRegistry.settle()
        }
        return deferred
    }

    /** Record what the reconcile settled on, and carry its confirmation into the registry so the
     *  NEXT apply on this selector knows whether the value it replaces was ever observed. */
    private fun reconciled(ctx: ApplyContext, stored: SavedPreference, result: Reconciled): ApplyOutcome {
        // Only the newest apply may publish provenance. An older one finishing later describes a
        // value that has since been replaced, and marking it observed would hand the next apply
        // a baseline nobody is showing.
        if (applyRegistry.isLatest(ctx.key, ctx.token)) applyRegistry.setObserved(ctx.key, result.confirmed)
        return ApplyOutcome.Applied(result.value, remembered = stored.remembered)
    }

    /**
     * Roll back the preference an apply optimistically saved and report the failure.
     *
     * Reached when the apply *reported* failure, which is not the same as knowing it didn't
     * land: `acp_set_mode`/`acp_set_config_option` only push a command onto the connection's
     * queue and return, so a response lost after the server enqueued is indistinguishable here
     * from one rejected before it. Rolling back is the right side to err on anyway — the
     * alternative leaves a value the next connect applies for real, silently, with the sheet
     * showing the old one and no trace the change ever failed. If the change did land after all,
     * the next sheet open corrects the display *provided the connection is still reachable* —
     * which is not guaranteed on this path, since [dropStaleOptionsConnection] may have just
     * released a draft's only handle to it.
     *
     * Reports whether the store was actually put back, because a rollback that fails to write is
     * the original divergence again: the sheet reverts while the store keeps the new value, and
     * the next connect applies it for real. The sheet says so rather than claiming a clean
     * revert.
     */
    private suspend fun failedApply(
        ctx: ApplyContext,
        stored: SavedPreference,
        rollback: suspend () -> Unit,
    ): ApplyOutcome = ApplyOutcome.Failed(rolledBack = !stored.remembered || rollBack(ctx, rollback))

    /**
     * Undo the preference this apply saved. Returns whether the store is back in a state that
     * matches what the sheet is about to show.
     *
     * Skipped — and reported as done — when a later apply has claimed the same selector for the
     * same agent: that apply's value is the one in the store now, and undoing it would replace a
     * pick that may well be succeeding. Value equality can't decide this on its own; a later
     * OFF → ON → OFF leaves the store holding exactly what a stalled older OFF apply wrote.
     *
     * [NonCancellable] so the write still completes when the surrounding apply is being torn
     * down, which is when it matters most.
     */
    private suspend fun rollBack(ctx: ApplyContext, rollback: suspend () -> Unit): Boolean {
        if (!applyRegistry.owns(ctx.key, ctx.token)) return true
        return withContext(NonCancellable) { runCatching { rollback() }.isSuccess }
    }

    /** An apply failed against [conn] — if it's still the cached connection AND no turn is now
     *  running on it, drop it so the next tap re-resolves (findConnection → connect) instead of
     *  reusing a possibly-dead id forever (which would leave the picker permanently non-functional
     *  once the server GCs an idle connection). The in-flight guard keeps a reattach that adopted
     *  this connection after the apply began from having its live turn's connection nulled (Stop /
     *  stream reconnect need it). Mirrors iOS re-validating the connection between applies. */
    private fun dropStaleOptionsConnection(conn: String) {
        if (connectionId == conn && !_ui.value.isInFlight) forgetConnection()
    }

    /**
     * Poll the authoritative snapshot of [conn] until it reports [desired] for [key] (the mode
     * or a config-option id), or the ~2.3s budget elapses. Returns the value to highlight.
     *
     * [previous] is what [key] held before the apply, and it's what makes the timeout
     * interpretable. `acp_set_mode`/`acp_set_config_option` only *enqueue* the change, so a
     * budget that runs out while every poll still reports [previous] is not evidence the agent
     * rejected the pick — it is equally consistent with an agent slower than 2.3s, which is by
     * far the likelier of the two. Reverting there would show cline's `auto_approve` OFF for a
     * toggle the agent is about to turn ON. Only a value that actually MOVED — to something
     * that is neither [previous] nor [desired] — is an observed normalization worth adopting.
     *
     * So this cannot detect a silent rejection (an agent that accepts the request and ignores
     * it). That state clears the next time the sheet opens, which reads the live connection
     * back ([loadAgentOptions]) rather than the remembered pick. Mirrors the iOS reconcile,
     * with the no-movement case decided the other way.
     */
    private suspend fun reconcileSelection(
        conn: String,
        key: SelectorKey,
        desired: String,
        previous: String?,
    ): Reconciled {
        val c = client ?: return Reconciled(desired, confirmed = false)
        var lastSeen: String? = null
        // Wall-clock, not just the sum of the delays: each snapshot request can sit on the
        // client's own (much longer) HTTP timeout, so counting attempts alone bounds nothing.
        // Anything still outstanding when the budget runs out is abandoned, not awaited.
        withTimeoutOrNull(RECONCILE_BUDGET_MS) {
            repeat(RECONCILE_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 350L else 400L)
                val snap = runCatching { c.sessionSnapshot(conn) }.getOrNull() ?: return@repeat
                lastSeen = snap.selectionFor(key) ?: return@repeat
            if (lastSeen == desired) return@withTimeoutOrNull
            }
        }
        if (lastSeen == desired) return Reconciled(desired, confirmed = true)
        val settled = reconcileTimeout(lastSeen, desired, previous)
        // Falling back to `desired` means nothing was observed; anything else is a value a
        // snapshot actually reported.
        return Reconciled(settled, confirmed = settled != desired)
    }

    /** A reconcile's answer, plus whether a session actually reported it (as opposed to it being
     *  the optimistic pick handed back after the budget elapsed). */
    private class Reconciled(val value: String, val confirmed: Boolean)

    // endregion
}

/**
 * `runCatching`, but cancellation still cancels.
 *
 * The stdlib one catches `CancellationException` along with everything else, which turns a
 * superseded caller into one that keeps running: it stops at no further suspension point, so it
 * reaches its assignments and publishes a result the caller has already moved past. Used where a
 * failed call should degrade to null but a cancelled one must unwind.
 */
private suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** A running options apply, tagged with the agent it targets so a sheet reload only waits for
 *  applies that could affect what it is about to show. */
private class InFlightApply(val agent: AgentType, val deferred: Deferred<ApplyOutcome>)

/** An in-flight options-connection resolve tagged with the agent/folder it targets, so the
 *  memo is only reused for a matching context (see [SessionDetailViewModel.optionsConnection]). */
private class OptionsConnect(val agent: AgentType, val folderId: Int?, val deferred: Deferred<String>)

/** The current value for each config option, dropping empties. */
private fun List<SessionConfigOption>.currentValues(): Map<String, String> =
    associate { it.id to (it.kind.currentValue ?: "") }.filterValues { it.isNotEmpty() }

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
