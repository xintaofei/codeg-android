package app.codeg.android.feature.sessiondetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.feature.downloads.DownloadRepository
import app.codeg.android.core.datastore.SelectorPrefs
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.PlanApprovalDecision
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.QuickMessage
import android.content.ContentResolver
import android.net.Uri
import app.codeg.android.core.model.ConversationConnectionInfo
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PromptInputBlock
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import app.codeg.android.core.model.SessionStats
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.network.StreamFrame
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isStaleConnection
import app.codeg.android.feature.sessiondetail.interactive.ParsedPermission
import app.codeg.android.feature.sessiondetail.timeline.dropTrailingAssistantRun
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** WS `detached` reason meaning the ACP connection itself is gone (terminal — a
 *  socket reopen can't recover it). Any other reason is a transient socket drop. */
private const val REASON_CONNECTION_GONE = "connection_gone"

/** Max consecutive silent re-attaches before falling back to a transcript reconcile. */
private const val MAX_STREAM_RECONNECTS = 6

/** The selector key used for the session mode (config options key by their own id). */
private const val MODE_KEY = "mode"

/** Bounded snapshot poll after an options apply (~2.3s total) — `set_*` only enqueues
 *  the change server-side, so we reconcile against the authoritative snapshot. */
private const val RECONCILE_ATTEMPTS = 6

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
class SessionDetailViewModel @Inject internal constructor(
    private val repository: ServerRepository,
    private val selectorPrefs: SelectorPrefsStore,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val downloads: DownloadRepository,
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
    private var agent: AgentType = AgentType.CLAUDE_CODE
    private var externalId: String? = null
    private var lastSeq: Long = 0
    /** True once a draft's first send begins — locks the agent/folder pickers. */
    private var hasStartedFirstSend = false
    /** The working tree's current branch, shown + checkmarked in the branch picker. */
    private var currentBranch: String? = null

    private var stream: EventStream? = null
    private var consumerJob: Job? = null
    private var sendJob: Job? = null
    private var streamGeneration = 0
    private var reconnectJob: Job? = null
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
    /** Stable across reconnects so the server tracks one logical subscription. */
    private val subscriptionId = UUID.randomUUID().toString()
    /** Consecutive silent re-attaches for the current turn; reset on a confirmed attach. */
    private var streamReconnects = 0
    /** True while a reattach still owes its first snapshot seed. If the socket drops
     *  mid-handshake, the reconnect re-requests the seed instead of streaming blind. */
    private var pendingReattachSeed = false
    private var liveBuilder: LiveTurnBuilder? = null
    private var liveFlushScheduled = false
    private var turnBaseline = 0
    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var historyCacheSnapshot: String? = null
    private var olderJob: Job? = null
    private var historyGeneration = 0

    private val _ui = MutableStateFlow(SessionDetailUiState(isNew = conversationIdArg == null))
    val ui: StateFlow<SessionDetailUiState> = _ui.asStateFlow()

    private val activeDownloadKey = MutableStateFlow<String?>(savedStateHandle["active_download_key"])
    internal val fileDownload = combine(downloads.records, activeDownloadKey) { records, key -> records.firstOrNull { it.key == key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    internal fun downloadFile(link: String): String? {
        val selected = profile ?: return null
        val activeFolderId = _ui.value.summary?.folderId ?: _ui.value.selectedFolderId
        val currentPath = _ui.value.folderPath ?: allFolders.firstOrNull { it.id == activeFolderId }?.path
        val key = downloads.start(selected, conversationId, _ui.value.summary?.trimmedTitle.orEmpty(), currentPath, link)
        activeDownloadKey.value = key
        savedStateHandle["active_download_key"] = key
        return key
    }

    internal fun previewFilePath(link: String): String? {
        val current = _ui.value.folderPath
        val target = workspaceFileLink(link, allFolders.map { it.path }, current) ?: return null
        if (target.path.endsWith(".apk", ignoreCase = true)) return null
        return target.rootPath.trimEnd('/', '\\') + "/" + target.path
    }

    internal fun retryFileDownload() { activeDownloadKey.value?.let(downloads::retry) }
    internal fun dismissFileDownload() {
        activeDownloadKey.value = null
        savedStateHandle.remove<String>("active_download_key")
    }

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        refreshJob?.cancel()
        reconcileJob?.cancel()
        invalidateOlderRequest()
        _ui.update { it.copy(loading = it.summary == null, refreshing = false, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val p = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                profile = p
                val c = repository.client(p) ?: throw IllegalStateException("Missing token for this server")
                client = c

                val folders = async {
                    val known = c.cachedSessionList()?.value?.folders ?: runCatching { c.listFolders() }.getOrDefault(emptyList())
                    if (conversationId != null) {
                        allFolders = known
                        _ui.value.summary?.let { summary ->
                            folder = known.firstOrNull { it.id == summary.folderId }
                            currentBranch = summary.gitBranch ?: folder?.gitBranch
                            _ui.update {
                                it.copy(
                                    folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                                    folderPath = folder?.path, selectedFolderId = folder?.id, currentBranch = currentBranch,
                                )
                            }
                        }
                    }
                    known
                }

                val id = conversationId
                if (id != null) {
                    val cached = c.cachedConversation(id)
                    if (cached != null) {
                        historyCacheSnapshot = cached.snapshot
                        showLoadedDetail(cached.value, cached.savedAt)
                    }
                    val needsNetwork = cached == null || !cached.isFresh() ||
                        cached.value.summary.status.isLive || cached.value.inFlightUserTurnId != null
                    if (needsNetwork && cached != null) _ui.update { it.copy(refreshing = true) }
                    val detail = if (needsNetwork) c.conversationDetail(id).also {
                        historyCacheSnapshot = null
                        showLoadedDetail(it)
                    } else cached!!.value
                    // Reattach when the server reports the turn live — via an explicit
                    // in-flight user turn id or the conversation status (iOS parity).
                    if (needsNetwork && (detail.inFlightUserTurnId != null || detail.summary.status.isLive)) reattachIfLive()
                    allFolders = folders.await()
                    folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
                    currentBranch = detail.summary.gitBranch ?: folder?.gitBranch
                    _ui.update {
                        it.copy(
                            folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                            folderPath = folder?.path,
                            selectedFolderId = folder?.id,
                            currentBranch = currentBranch,
                        )
                    }
                } else {
                    allFolders = folders.await()
                    // New task: load the draft pickers' sources (top-level open folders +
                    // installed/enabled agents) and pick sensible defaults — honoring a
                    // preselected folder (e.g. a worktree a branch switch opened).
                    val open = runCatching { c.listOpenFolders() }.getOrDefault(emptyList())
                    val topLevel = FolderVisibility.filterTopLevel(open)
                        .sortedByDescending { it.lastOpenedAt ?: Instant.MIN }
                    val agents = runCatching { c.acpListAgents() }.getOrDefault(emptyList())
                        .filter { it.available && it.enabled }
                        .sortedBy { it.sortOrder }
                        .map { it.agentType }
                        .distinct()
                        .ifEmpty { AgentType.entries.toList() }
                    folder = allFolders.firstOrNull { it.id == folderIdArg }
                        ?: topLevel.firstOrNull()
                        ?: allFolders.maxByOrNull { it.lastOpenedAt ?: Instant.MIN }
                        ?: allFolders.firstOrNull()
                    agent = folder?.defaultAgentType?.takeIf { it in agents } ?: agents.first()
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
                _ui.update { it.copy(loading = false, refreshing = false, error = e.displayMessage()) }
            }
        }
    }

    private fun showLoadedDetail(detail: ConversationDetail, cachedAt: Long? = null) {
        agent = detail.summary.agentType
        externalId = detail.summary.externalId
        currentBranch = detail.summary.gitBranch
        folder = allFolders.firstOrNull { it.id == detail.summary.folderId }
        _ui.update {
            it.copy(
                loading = false, refreshing = false, summary = detail.summary, turns = detail.turns,
                historyWindow = TranscriptWindow.from(detail), historyChunkStarts = emptySet(), olderError = null,
                sessionStats = detail.sessionStats, agent = agent,
                folderName = folder?.let { f -> FolderVisibility.displayName(f, allFolders) },
                folderPath = folder?.path, selectedFolderId = folder?.id, currentBranch = currentBranch,
                isDraftEditable = false, error = null,
                lastUpdatedAt = cachedAt ?: System.currentTimeMillis(), showingCached = cachedAt != null,
            )
        }
    }

    /** Explicit refresh always reaches the server, preserving the live builder and composer. */
    fun refresh() {
        if (_ui.value.refreshing || _ui.value.loading) return
        if (conversationId == null || client == null) { load(); return }
        _ui.update { it.copy(refreshing = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                loadJob?.join()
                reconcileJob?.join()
                val c = client ?: return@launch
                val id = conversationId ?: return@launch
                invalidateOlderRequest()
                val builder = liveBuilder
                val generation = historyGeneration
                val detail = fetchDetailWindowed(c, id)
                if (builder !== liveBuilder || generation != historyGeneration) return@launch
                historyCacheSnapshot = null
                _ui.update {
                    // The live builder owns unconfirmed user/assistant records until reconcile.
                    val replaceTranscript = it.live == null && it.pendingUserTurns.isEmpty()
                    it.copy(
                        turns = if (replaceTranscript) detail.turns else it.turns,
                        historyWindow = if (replaceTranscript) TranscriptWindow.from(detail) else it.historyWindow,
                        historyChunkStarts = if (!replaceTranscript || it.historyWindow?.acceptsRefresh(TranscriptWindow.from(detail)) == true) it.historyChunkStarts else emptySet(),
                        summary = detail.summary, sessionStats = detail.sessionStats ?: it.sessionStats,
                        olderError = null, lastUpdatedAt = System.currentTimeMillis(), showingCached = false,
                    )
                }
                if (_ui.value.live == null && (detail.inFlightUserTurnId != null || detail.summary.status.isLive)) reattachIfLive()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(error = e.displayMessage()) } }
            finally { _ui.update { it.copy(refreshing = false) } }
        }
    }

    private fun invalidateOlderRequest() {
        historyGeneration++
        olderJob?.cancel()
        olderJob = null
        _ui.update { it.copy(loadingOlder = false) }
    }

    fun loadOlderTurns() {
        val c = client ?: return
        val id = conversationId ?: return
        val state = _ui.value
        val window = state.historyWindow ?: return
        if (window.offset == 0 || state.loading || state.refreshing || state.loadingOlder || reconcileJob?.isActive == true) return
        val generation = historyGeneration
        _ui.update { it.copy(loadingOlder = true, olderError = null) }
        olderJob = viewModelScope.launch {
            try {
                val cachedPage = historyCacheSnapshot?.let { c.cachedConversationTurns(id, window.offset, it) }
                val page = if (cachedPage != null && window.prepend(cachedPage, state.turns) != null) cachedPage
                    else c.conversationTurns(id, window.offset)
                if (generation != historyGeneration || conversationId != id) return@launch
                val current = _ui.value
                val next = window.prepend(page, current.turns)
                if (current.historyWindow != window || next == null) {
                    _ui.update { it.copy(loadingOlder = false) }
                    // History was rewritten; the old index seam is no longer usable.
                    reconcileAfterMissedLive(resetWindow = true)
                    return@launch
                }
                _ui.update {
                    it.copy(
                        turns = page.turns + it.turns,
                        historyWindow = next,
                        loadingOlder = false,
                        // Keep partial replies as separate chunks so prepending cannot re-key
                        // the Markdown/tool rows the user is currently reading.
                        historyChunkStarts = it.historyChunkStarts +
                            listOfNotNull(it.turns.firstOrNull()?.takeIf { t -> t.role == TurnRole.ASSISTANT }?.id),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == historyGeneration) {
                    _ui.update { it.copy(loadingOlder = false, olderError = e.displayMessage()) }
                }
            }
        }
    }

    private suspend fun fetchDetailWindowed(c: CodegClient, id: Int): ConversationDetail {
        val window = _ui.value.historyWindow ?: return c.conversationDetail(id)
        val detail = c.conversationDetail(id, fromIndex = window.offset)
        // Legacy servers return the full history without window metadata.
        if (detail.turnsOffset == null || window.acceptsRefresh(TranscriptWindow.from(detail))) return detail
        return c.conversationDetail(id)
    }

    /** Used by an in-thread jump whose target is above the loaded window. */
    suspend fun loadThroughTurn(turnId: String): Boolean {
        while (_ui.value.turns.none { it.id == turnId }) {
            val offset = _ui.value.historyWindow?.offset ?: return false
            if (offset == 0 || _ui.value.loading || reconcileJob?.isActive == true) return false
            if (!_ui.value.loadingOlder) loadOlderTurns()
            olderJob?.join()
            val after = _ui.value.historyWindow?.offset ?: return false
            if (_ui.value.olderError != null || after >= offset) return false
        }
        return true
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
        turnBaseline = _ui.value.historyWindow?.countIncludingLocal(_ui.value.turns.size) ?: _ui.value.turns.size
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
        // The EXACT conversation this send created up front (a first send), so a rollback deletes
        // that row and not whatever conversationId happens to be by the time the failure lands.
        var createdId: Int? = null
        try {
            loadJob?.join()
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
                if (e is StreamAttachFailed || (e is ApiError && e.isStaleConnection)) {
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
        lastSeq = 0
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
                lastSeq = info.eventSeq
                return info.connectionId
            }
        }
        return connectWithPreferences(c)
    }

    /** Open the WS stream for a fresh turn: reset the reconnect budget, attach, and
     *  suspend until the server confirms the subscription (or a 12s safety timeout).
     *  [seedOnAttach] rebuilds an in-flight turn from the attach snapshot (reattach). */
    private suspend fun openStream(conn: String, seedOnAttach: Boolean = false) {
        streamReconnects = 0
        pendingReattachSeed = seedOnAttach
        reconnectJob?.cancel()
        reconnectJob = null
        connectStream(conn, awaitAttach = true, seedOnAttach = seedOnAttach)
    }

    /** Launch a WS consumer for [conn], attaching with the current [lastSeq] so a
     *  re-attach resumes via the server's replay. When [awaitAttach], suspend until
     *  the server confirms the subscription (snapshot/replay) or a 12s safety timeout;
     *  a silent reconnect passes false and lets events flow in as they arrive.
     *  [seedOnAttach] seeds the live turn from the first snapshot (reattach only). */
    private suspend fun connectStream(conn: String, awaitAttach: Boolean, seedOnAttach: Boolean = false) {
        val p = profile ?: return
        streamGeneration += 1
        val gen = streamGeneration
        val attached = CompletableDeferred<Unit>()
        consumerJob?.cancel()
        consumerJob = viewModelScope.launch {
            val s = repository.eventStream(p) ?: run {
                if (!attached.isCompleted) attached.complete(Unit)
                return@launch
            }
            stream = s
            s.frames().collect { frame ->
                if (gen != streamGeneration) return@collect
                when (frame) {
                    StreamFrame.Ready -> s.attach(subscriptionId, conn, lastSeq.takeIf { it > 0 })
                    is StreamFrame.Snapshot -> {
                        streamReconnects = 0
                        frame.snapshot.eventSeq?.let { lastSeq = it }
                        if (seedOnAttach && !seedLiveFromSnapshot(frame.snapshot)) {
                            // Idle connection — nothing in flight; abort the reattach.
                            if (!attached.isCompleted) attached.complete(Unit)
                            abortIdleReattach()
                            return@collect
                        }
                        // Only adopt a snapshot's pending permission/question on a reattach or a
                        // mid-turn reconnect — the initial send handshake snapshot is pre-prompt
                        // state and could surface a stale card (iOS skips it there).
                        if (seedOnAttach || !awaitAttach) restorePending(frame.snapshot)
                        if (!attached.isCompleted) attached.complete(Unit)
                    }
                    is StreamFrame.Replay -> {
                        streamReconnects = 0
                        pendingReattachSeed = false
                        frame.events.forEach { applyEvent(it.event, it.seq) }
                        if (!attached.isCompleted) attached.complete(Unit)
                    }
                    is StreamFrame.Event -> applyEvent(frame.envelope.event, frame.envelope.seq)
                    is StreamFrame.Detached -> {
                        // A detach before the attach is confirmed means the server rejected this
                        // connection (stale/unknown). Fail the awaited handshake so the send path
                        // retries with a fresh connect, rather than reconnecting to a dead id. A
                        // silent reconnect (awaitAttach=false) still drives the reconnect loop.
                        if (awaitAttach && !attached.isCompleted) {
                            attached.completeExceptionally(StreamAttachFailed(frame.reason))
                        } else {
                            handleStreamEnded(gen, frame.reason)
                        }
                    }
                    StreamFrame.Pong -> Unit
                    is StreamFrame.Closed -> {
                        if (awaitAttach && !attached.isCompleted) {
                            attached.completeExceptionally(StreamAttachFailed(frame.reason))
                        } else {
                            if (!attached.isCompleted) attached.complete(Unit)
                            handleStreamEnded(gen, frame.reason)
                        }
                    }
                }
            }
        }
        // Fail the send if the subscription isn't confirmed in time, instead of prompting
        // blind against an unconfirmed stream (whose first events could be lost). The send
        // path treats this like a stale connection and retries once with a fresh connect.
        if (awaitAttach) withTimeoutOrNull(12_000) { attached.await() } ?: throw StreamAttachFailed("attach timeout")
    }

    fun cancel() {
        val builder = liveBuilder
        if (builder != null) {
            builder.isStreaming = false
            if (builder.isEmpty) builder.errorMessage = appContext.getString(R.string.live_cancelled)
            emitLiveNow()
        }
        sendJob?.cancel()
        reconcileJob?.cancel()
        closeStream()
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
            lastSeq = 0
            // Rebuild the in-flight turn from the attach snapshot (seedOnAttach) and keep
            // streaming; the snapshot's live message + active tools are seeded on arrival.
            val builder = LiveTurnBuilder()
            liveBuilder = builder
            turnBaseline = _ui.value.historyWindow?.countIncludingLocal(_ui.value.turns.size) ?: _ui.value.turns.size
            _ui.update { it.copy(isInFlight = true, sendStatus = "Reattaching…", live = builder.snapshot()) }
            try {
                openStream(found.connectionId, seedOnAttach = true)
            } catch (e: StreamAttachFailed) {
                // The live connection vanished before we could attach — the turn has ended;
                // clear the placeholder and refetch the final transcript.
                abortIdleReattach()
            }
        }
    }

    private fun applyEvent(event: AcpEvent, seq: Long) {
        if (seq > 0) lastSeq = seq
        val builder = liveBuilder
        when (event) {
            is AcpEvent.ContentDelta -> { builder?.appendText(event.text); scheduleLiveEmit() }
            is AcpEvent.Thinking -> { builder?.appendThinking(event.text); scheduleLiveEmit() }
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
                ConnectionStatus.PROMPTING -> _ui.update {
                    // The agent is actively prompting — reflect it as "Thinking…" unless a tool is
                    // currently running (that status is more specific). Mirrors the iOS .prompting.
                    if (it.isInFlight && liveBuilder?.activeToolTitle() == null) it.copy(sendStatus = STATUS_THINKING) else it
                }
                // .connecting is a no-op here: the status line only renders while in-flight, and
                // by then we're already past connecting (iOS shows it only from its idle state).
                else -> Unit
            }
            is AcpEvent.TurnComplete -> finalizeTurn(event.stopReason)
            is AcpEvent.Error -> failLive(event.message)
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
            else -> Unit // session_started / user_message / etc. are informational
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
            val detail = runCatching { c.conversationDetail(target) }.getOrNull() ?: return@launch
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
                            errorMessage = origin?.errorMessage,
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
        val inFlight = !snap.liveMessage?.content.isNullOrEmpty() ||
            snap.activeToolCalls.isNotEmpty() ||
            snap.pendingPermission != null || snap.pendingQuestion != null ||
            snap.pendingPlanApproval != null ||
            snap.status == app.codeg.android.core.model.ConnectionStatus.PROMPTING
        if (!inFlight) return false
        pendingReattachSeed = false
        if (builder.isEmpty) builder.seedFrom(snap)
        // Only claim the reply (hiding the server's persisted partial copy) when the seed
        // actually produced content. A pending-only snapshot (a permission/question with no
        // assistant message yet) seeds nothing, so hiding the transcript there could drop a
        // persisted reply with no live copy to show in its place.
        _ui.update { it.copy(live = builder.snapshot(), liveFromReattach = !builder.isEmpty) }
        return true
    }

    /** The reattach snapshot showed an idle connection — nothing is in flight. Clear the
     *  placeholder live turn, drop the connection, tear the stream down, and reconcile in
     *  case the turn just finished (leaving the loaded transcript a beat stale). */
    private fun abortIdleReattach() {
        pendingReattachSeed = false
        connectionId = null
        liveBuilder = null
        _ui.update { it.copy(isInFlight = false, sendStatus = null, live = null, liveFromReattach = false) }
        closeStream()
        reconcileAfterMissedLive()
    }

    /** Server reported a live turn at load but no in-flight state surfaced — refetch so
     *  the transcript shows the final reply rather than the beat-stale load. */
    private fun reconcileAfterMissedLive(resetWindow: Boolean = false) {
        val c = client ?: return
        val id = conversationId ?: return
        val builder = liveBuilder
        invalidateOlderRequest()
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            try {
                val d = if (resetWindow) c.conversationDetail(id) else fetchDetailWindowed(c, id)
                if (liveBuilder !== builder || conversationId != id) return@launch
                historyCacheSnapshot = null
                _ui.update {
                    it.copy(
                        turns = d.turns,
                        historyWindow = TranscriptWindow.from(d),
                        historyChunkStarts = if (it.historyWindow?.acceptsRefresh(TranscriptWindow.from(d)) == true) {
                            it.historyChunkStarts
                        } else emptySet(),
                        olderError = null,
                        sessionStats = d.sessionStats ?: it.sessionStats,
                        summary = d.summary,
                        lastUpdatedAt = System.currentTimeMillis(), showingCached = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resetWindow && liveBuilder === builder) {
                    _ui.update { it.copy(olderError = e.displayMessage()) }
                }
            }
        }
    }

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
    }

    private fun finalizeTurn(stopReason: String) {
        val builder = liveBuilder ?: return
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
        closeStream()
        reconcileAfterTurn()
        // The keep-planning turn just ended — deliver the revision notes as the follow-up
        // prompt Grok expects (it discards them on the reply itself). `send` supersedes
        // the reconcile it just started and folds the finished reply in locally, exactly
        // as a user typing the instant a turn ends would.
        if (planFollowUp != null) send(planFollowUp, withAttachments = false)
    }

    private fun failLive(message: String) {
        val builder = liveBuilder
        if (builder != null) {
            builder.errorMessage = message
            builder.isStreaming = false
            emitLiveNow()
        }
        closeStream()
        clearInteractivePrompts()
    }

    private fun handleStreamEnded(gen: Int, reason: String?) {
        if (gen != streamGeneration) return
        // If the turn already finished, this is a normal close.
        if (!_ui.value.isInFlight) return
        // `connection_gone` is terminal — the ACP connection itself is gone, so a
        // socket reopen can't recover it; reconcile against the server transcript.
        if (reason == REASON_CONNECTION_GONE) {
            // Terminal: the ACP connection itself is gone, so reopening the socket can't
            // recover it. Supersede the consumer first so the socket's own trailing close
            // can't fall through to a doomed reconnect against the dead connection.
            closeStream()
            reconcileAfterTurn()
            return
        }
        // Any other drop (socket blip, `lagged`, `server_shutdown`) is transient: the
        // ACP connection outlives the socket, so re-attach with `since_seq` and let
        // the server replay whatever we missed instead of ending the live turn.
        reconnectStream()
    }

    /** Silently re-attach a fresh stream to the same connection after a transient
     *  drop, resuming from [lastSeq]. Backs off exponentially (0.5s→8s) and, after
     *  [MAX_STREAM_RECONNECTS] tries, gives up to a one-shot transcript reconcile so
     *  the final reply is never lost. Mirrors the iOS `reconnectStream`. */
    private fun reconnectStream() {
        streamReconnects += 1
        if (streamReconnects > MAX_STREAM_RECONNECTS) {
            reconcileAfterTurn()
            return
        }
        val conn = connectionId ?: run { reconcileAfterTurn(); return }
        // Supersede the dying consumer immediately so its trailing frames can't slip
        // through the reconnect budget during the backoff window.
        streamGeneration += 1
        consumerJob?.cancel()
        consumerJob = null
        val gen = streamGeneration
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val backoffMs = (500L shl (streamReconnects - 1)).coerceAtMost(8_000L)
            _ui.update { if (it.isInFlight) it.copy(sendStatus = "Reconnecting…") else it }
            delay(backoffMs)
            // Bail if the turn ended or was superseded (cancel / finalize) while we waited.
            if (gen != streamGeneration || !_ui.value.isInFlight) return@launch
            connectStream(conn, awaitAttach = false, seedOnAttach = pendingReattachSeed)
        }
    }

    private fun reconcileAfterTurn() {
        val c = client ?: return
        val id = conversationId ?: return
        // Capture the turn this reconcile belongs to. If a new send / cancel supersedes the
        // live builder while we poll, bail rather than wiping the newer turn's state (its
        // pending user turn, live reply, in-flight flag). Mirrors iOS's `liveTurn === live`.
        val builder = liveBuilder
        invalidateOlderRequest()
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            repeat(5) { attempt ->
                delay(if (attempt == 0) 300 else 600)
                if (liveBuilder !== builder) return@launch
                val detail = runCatching { fetchDetailWindowed(c, id) }.getOrNull() ?: return@repeat
                if (liveBuilder !== builder) return@launch
                val last = detail.turns.lastOrNull()
                val advanced = (detail.turnsTotal ?: detail.turns.size) > turnBaseline &&
                    last?.role == TurnRole.ASSISTANT && last.blocks.isNotEmpty()
                if (advanced) {
                    if (liveBuilder !== builder) return@launch
                    historyCacheSnapshot = null
                    // This turn ended without a `turn_complete` of its own (a dead
                    // connection, an exhausted reconnect), so `finalizeTurn` never ran:
                    // resolve its parked plan-revision notes here instead of leaving them
                    // for whatever turn finalizes next.
                    val orphanedNotes = builder?.let { pendingPlanFollowUp?.notesFor(it.id) }
                    if (orphanedNotes != null) pendingPlanFollowUp = null
                    _ui.update {
                        it.copy(
                            turns = detail.turns,
                            historyWindow = TranscriptWindow.from(detail),
                            historyChunkStarts = if (it.historyWindow?.acceptsRefresh(TranscriptWindow.from(detail)) == true) {
                                it.historyChunkStarts
                            } else emptySet(),
                            olderError = null,
                            sessionStats = detail.sessionStats ?: it.sessionStats,
                            summary = detail.summary,
                            lastUpdatedAt = System.currentTimeMillis(), showingCached = false,
                            pendingUserTurns = emptyList(),
                            live = null,
                            liveFromReattach = false,
                            isInFlight = false,
                            sendStatus = null,
                            pendingPermission = null,
                            pendingQuestion = null,
                            pendingPlanApproval = null,
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
            val base = if (state.liveFromReattach) state.turns.dropTrailingAssistantRun() else state.turns
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
        streamGeneration += 1 // supersede the current consumer so its Closed is ignored
        reconnectJob?.cancel()
        reconnectJob = null
        consumerJob?.cancel()
        consumerJob = null
        stream = null
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
        connectionId = null
        _ui.update { it.copy(agent = newAgent) }
    }

    /** Change the draft's folder before the first send (the agent's working dir),
     *  refreshing the branch label and dropping any resolved connection. */
    fun selectFolder(newFolder: FolderDetail) {
        if (!_ui.value.isDraftEditable || newFolder.id == folder?.id) return
        folder = newFolder
        currentBranch = newFolder.gitBranch
        connectionId = null
        _ui.update {
            it.copy(
                folderName = FolderVisibility.displayName(newFolder, allFolders),
                folderPath = newFolder.path,
                selectedFolderId = newFolder.id,
                currentBranch = newFolder.gitBranch,
            )
        }
    }

    /** List the folder's branches for the picker; refreshes the current-branch
     *  label opportunistically. Null when there's no git context / on failure. */
    suspend fun loadBranches(): GitBranchList? {
        loadJob?.join()
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

    /** Load the session's modes + config plus the selection to highlight. When a live
     *  session exists its snapshot is authoritative; otherwise it probes the per-agent
     *  catalog (slower) and pre-selects the user's remembered pick (validated against the
     *  catalog), falling back to the catalog's fresh-session defaults. iOS parity. */
    suspend fun loadAgentOptions(): AgentOptionsData {
        loadJob?.join()
        val c = client ?: return AgentOptionsData()
        val convId = conversationId
        val snapshot = if (convId != null) runCatching { c.sessionSnapshotByConversation(convId) }.getOrNull() else null
        if (snapshot != null && (snapshot.modes != null || snapshot.configOptions.isNotEmpty())) {
            // Live session — its current mode/config is authoritative.
            return AgentOptionsData(
                modes = snapshot.modes,
                configOptions = snapshot.configOptions,
                initialModeId = snapshot.modes?.currentModeId,
                initialConfig = snapshot.configOptions.currentValues(),
            )
        }
        // No live session (draft / idle) — probe the catalog and pre-select the remembered
        // pick so the sheet opens on the user's last-used mode/config, not the server default.
        val described = runCatching { c.describeAgentOptions(agent, folder?.path) }.getOrNull()
        val modes = described?.modes
        val options = described?.configOptions.orEmpty()
        val prefs = runCatching { selectorPrefs.prefs(agent) }.getOrNull() ?: SelectorPrefs()
        val modeId = prefs.modeId?.takeIf { saved -> modes?.availableModes?.any { it.id == saved } == true }
            ?: modes?.currentModeId
        val config = options.associate { opt ->
            val saved = prefs.configValues?.get(opt.id)?.takeIf { it in opt.kind.allSelectValues() }
            opt.id to (saved ?: opt.kind.currentValue ?: "")
        }.filterValues { it.isNotEmpty() }
        return AgentOptionsData(modes, options, modeId, config)
    }

    /** A live connection to apply options to. Reuses a cached/live connection, and — for a
     *  draft or idle session with none — establishes one on demand (carrying the remembered
     *  mode/config), so the pickers apply instead of silently no-op'ing. Mirrors iOS
     *  `resolveConnectionForOptions`. Concurrent applies share one in-flight resolve
     *  (`optionsConnectDeferred`) so a rapid mode+config change can't spawn two orphaned
     *  connections. Returns null only when there's no client or the connect fails. */
    private suspend fun optionsConnection(forAgent: AgentType, forFolder: FolderDetail?): String? {
        val forFolderId = forFolder?.id
        // Reuse the shared connection only when it still matches THIS apply's pinned agent/folder.
        // connectionId is nulled on every agent/folder switch, so a non-null value always belongs
        // to the current context; if the current context no longer matches the pinned one, this
        // apply raced a switch and must resolve a connection for its own (pinned) agent instead of
        // returning the other agent's connection.
        connectionId?.takeIf { agent == forAgent && folder?.id == forFolderId }?.let { return it }
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

    /** Apply a mode pick. Always remembers it per agent (so it carries to the server on the
     *  next `acp_connect`), resolves/establishes a connection ([optionsConnection]), enqueues
     *  `set_mode`, and reconciles against the authoritative snapshot. Returns the
     *  confirmed/normalized mode to highlight, or null when the connect/apply failed (the sheet
     *  reverts the optimistic pick). */
    suspend fun applyMode(modeId: String): String? {
        // Pin the agent/folder BEFORE the first suspension (saveMode), so a draft agent/folder
        // switch mid-apply can't save the pref under the wrong agent or retarget the connect.
        val forAgent = agent
        val forFolder = folder
        runCatching { selectorPrefs.saveMode(forAgent, modeId) }
        val c = client ?: return null
        val conn = optionsConnection(forAgent, forFolder) ?: return null
        return try {
            c.acpSetMode(conn, modeId)
            reconcileSelection(conn, key = MODE_KEY, desired = modeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropStaleOptionsConnection(conn)
            null
        }
    }

    /** Apply a config-option pick. Same contract as [applyMode] (remember, connect, apply,
     *  reconcile); returns the confirmed/normalized value or null. */
    suspend fun applyConfig(configId: String, valueId: String): String? {
        val forAgent = agent
        val forFolder = folder
        runCatching { selectorPrefs.saveConfig(forAgent, configId, valueId) }
        val c = client ?: return null
        val conn = optionsConnection(forAgent, forFolder) ?: return null
        return try {
            c.acpSetConfigOption(conn, configId, valueId)
            reconcileSelection(conn, key = configId, desired = valueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropStaleOptionsConnection(conn)
            null
        }
    }

    /** An apply failed against [conn] — if it's still the cached connection AND no turn is now
     *  running on it, drop it so the next tap re-resolves (findConnection → connect) instead of
     *  reusing a possibly-dead id forever (which would leave the picker permanently non-functional
     *  once the server GCs an idle connection). The in-flight guard keeps a reattach that adopted
     *  this connection after the apply began from having its live turn's connection nulled (Stop /
     *  stream reconnect need it). Mirrors iOS re-validating the connection between applies. */
    private fun dropStaleOptionsConnection(conn: String) {
        if (connectionId == conn && !_ui.value.isInFlight) connectionId = null
    }

    /** Poll the authoritative snapshot of [conn] until it reports [desired] for [key]
     *  (the mode or a config-option id), or it settles on the agent's normalized value, or
     *  the budget (~2.3s) elapses. Adopts the authoritative value when seen; otherwise
     *  keeps the optimistic [desired] as a best effort. Mirrors the iOS reconcile. */
    private suspend fun reconcileSelection(conn: String, key: String, desired: String): String {
        val c = client ?: return desired
        var lastSeen: String? = null
        repeat(RECONCILE_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) 350L else 400L)
            val snap = runCatching { c.sessionSnapshot(conn) }.getOrNull() ?: return@repeat
            val current = snap.selectionFor(key) ?: return@repeat
            lastSeen = current
            if (current == desired) return current
        }
        return lastSeen ?: desired
    }

    // endregion
}

/** Thrown when the event-stream handshake ends (detached/closed) before the server
 *  confirms the attach — i.e. the connection was rejected. The send path treats this like
 *  a stale connection and retries once with a fresh connect. */
private class StreamAttachFailed(val reason: String?) : Exception("stream attach failed: ${reason ?: "closed"}")

/** An in-flight options-connection resolve tagged with the agent/folder it targets, so the
 *  memo is only reused for a matching context (see [SessionDetailViewModel.optionsConnection]). */
private class OptionsConnect(val agent: AgentType, val folderId: Int?, val deferred: Deferred<String>)

/** The modes + config a session exposes plus the selection to highlight — authoritative
 *  when sourced from a live snapshot, or the remembered/last-used pick for a draft. */
data class AgentOptionsData(
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val initialModeId: String? = null,
    val initialConfig: Map<String, String> = emptyMap(),
)

/** The authoritative current value the snapshot reports for a selector key. */
private fun SessionSnapshot.selectionFor(key: String): String? =
    if (key == MODE_KEY) modes?.currentModeId
    else configOptions.firstOrNull { it.id == key }?.kind?.currentValue

/** The current value for each config option, dropping empties. */
private fun List<SessionConfigOption>.currentValues(): Map<String, String> =
    associate { it.id to (it.kind.currentValue ?: "") }.filterValues { it.isNotEmpty() }

/** Every selectable value (flat + grouped) of a config option — to validate a cached
 *  preference against the live catalog before pre-selecting it. */
private fun SessionConfigKind.allSelectValues(): List<String> =
    options.map { it.value } + groups.flatMap { g -> g.options.map { it.value } }

/** The navigation intent a branch switch resolved to (mirrors iOS `BranchSwitchOutcome`). */
sealed interface BranchSwitchOutcome {
    /** Already on the branch — nothing changed. */
    data object Noop : BranchSwitchOutcome
    /** Checked out in the current folder's working tree; the row's checkmark moves. */
    data object SwitchedInPlace : BranchSwitchOutcome
    /** The branch lives in another worktree folder — open a new session there. */
    data class OpenSession(val folderId: Int) : BranchSwitchOutcome
    /** The switch failed (a notice was surfaced). */
    data object Failed : BranchSwitchOutcome
}

data class SessionDetailUiState(
    val isNew: Boolean,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val showingCached: Boolean = false,
    val summary: ConversationSummary? = null,
    val folderName: String? = null,
    val folderPath: String? = null,
    val selectedFolderId: Int? = null,
    val currentBranch: String? = null,
    val isDraftEditable: Boolean = false,
    val availableFolders: List<FolderDetail> = emptyList(),
    val availableAgents: List<AgentType> = emptyList(),
    val turns: List<MessageTurn> = emptyList(),
    val historyWindow: TranscriptWindow? = null,
    val historyChunkStarts: Set<String> = emptySet(),
    val loadingOlder: Boolean = false,
    val olderError: String? = null,
    val pendingUserTurns: List<MessageTurn> = emptyList(),
    val live: LiveTurnState? = null,
    /** The live turn was rebuilt from a reattach snapshot (owns the in-flight reply). */
    val liveFromReattach: Boolean = false,
    val sessionStats: SessionStats? = null,
    val agent: AgentType = AgentType.CLAUDE_CODE,
    val isInFlight: Boolean = false,
    val sendStatus: String? = null,
    val notice: String? = null,
    /** Set when a failed send returns the user's typed text to the composer (consumed once). */
    val restoreDraft: String? = null,
    val error: String? = null,
    val pendingPermission: PendingPermissionUi? = null,
    val pendingQuestion: PendingQuestionUi? = null,
    val pendingPlanApproval: PendingPlanApprovalUi? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** What to do with a plan approval's revision notes — see [planFollowUpAction]. */
internal enum class PlanFollowUp { PARK, SEND_NOW, DROP }

/**
 * Plan-revision notes parked for the turn that produced the approval, keyed by that
 * turn's id.
 *
 * The key is what makes the parking safe: the originating turn may end through a path
 * that never reaches `finalizeTurn` (a dead connection reconciled against the server
 * transcript, an exhausted reconnect), and without the key the next unrelated turn's
 * completion would pick the notes up and send them.
 */
internal data class ParkedPlanNotes(val turnId: String, val notes: String) {
    /** The notes, but only for the turn that parked them; null for any other. */
    fun notesFor(turnId: String): String? = notes.takeIf { this.turnId == turnId }
}

/**
 * Where a "request changes" decision's revision notes should go, decided once the
 * answer post comes back. That post is a suspension point, so by then the turn that
 * produced the approval may have finalized, failed, been cancelled, or been replaced
 * by an unrelated new one — and Grok reads these notes from a follow-up user turn, so
 * sending them to the wrong turn starts work nobody asked for.
 *
 * - [PlanFollowUp.PARK] — the originating turn is still winding down; `finalizeTurn`
 *   flushes the notes when it completes.
 * - [PlanFollowUp.SEND_NOW] — it finished cleanly meanwhile (a stop reason, no error),
 *   so the follow-up is due immediately.
 * - [PlanFollowUp.DROP] — it was cancelled or failed, or a different turn is live now.
 *
 * Extracted for testing (the branch is otherwise only reachable through a real
 * in-flight network round trip).
 */
internal fun planFollowUpAction(
    sameTurn: Boolean,
    inFlight: Boolean,
    stopReason: String?,
    errorMessage: String?,
): PlanFollowUp = when {
    !sameTurn -> PlanFollowUp.DROP
    inFlight -> PlanFollowUp.PARK
    stopReason != null && errorMessage == null -> PlanFollowUp.SEND_NOW
    else -> PlanFollowUp.DROP
}

/** A `permission_request` awaiting the user's choice (parsed for the card). */
data class PendingPermissionUi(
    val requestId: String,
    val parsed: app.codeg.android.feature.sessiondetail.interactive.ParsedPermission,
    val options: List<app.codeg.android.core.model.PermissionOption>,
)

/** A `question_request` awaiting answers. */
data class PendingQuestionUi(
    val questionId: String,
    val questions: List<app.codeg.android.core.model.QuestionSpec>,
)

/**
 * A Grok `exit_plan_mode` awaiting the user's decision. Distinct from
 * [PendingPermissionUi]: Grok's plan approval is its own blocking ext request with three
 * outcomes, not a permission option list. (Claude's ExitPlanMode still arrives as a
 * permission and keeps that path.)
 */
data class PendingPlanApprovalUi(
    val approvalId: String,
    /** Grok's `toolCallId` for the `exit_plan_mode` call. Not used for the answer (which
     *  keys on [approvalId]) — kept so the card can correlate with the in-stream call. */
    val toolCallId: String,
    /** The plan, read from Grok's `plan.md`. May be empty — the card then shows an
     *  empty-state notice rather than hiding, since the turn is blocked either way. */
    val planMarkdown: String,
)
