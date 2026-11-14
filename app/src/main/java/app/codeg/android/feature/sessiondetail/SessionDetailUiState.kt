package app.codeg.android.feature.sessiondetail

import androidx.lifecycle.ViewModel
import app.codeg.android.core.model.AgentChoice
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FeedbackNote
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.SessionStats
import app.codeg.android.core.model.TurnWindowState
import app.codeg.android.feature.sessiondetail.interactive.ParsedPermission
import kotlinx.coroutines.flow.first

// The session-detail screen's UI state.

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
    val summary: ConversationSummary? = null,
    val folderName: String? = null,
    val folderPath: String? = null,
    val selectedFolderId: Int? = null,
    val currentBranch: String? = null,
    val isDraftEditable: Boolean = false,
    val availableFolders: List<FolderDetail> = emptyList(),
    val availableAgents: List<AgentChoice> = emptyList(),
    /** The loaded window of the server's transcript. */
    val turns: List<MessageTurn> = emptyList(),
    /**
     * Finished rounds this client saw stream to their end, kept as they streamed rather than
     * re-read from the transcript (see TurnHandoff.kt). They follow [turns], and give way to
     * the server's copy only once a read is proven to hold all of them.
     */
    val localTurns: List<MessageTurn> = emptyList(),
    val pendingUserTurns: List<MessageTurn> = emptyList(),
    /** Messages waiting for the running turn to end, oldest first. See SendQueue.kt. */
    val queue: List<QueuedPrompt> = emptyList(),
    /**
     * Auto-sending is on hold: the last send failed, or the turn died, for a reason the next
     * message would most likely hit too. Never true with an empty [queue]; the user's next
     * send, or "send now" on the strip, resumes it. See SendQueue.kt.
     */
    val queuePaused: Boolean = false,
    /** Bumped by every prompt this client sends: folds every reply above it (ReplyFold.kt). */
    val sendSignal: Int = 0,
    val live: LiveTurnState? = null,
    /** The live turn was rebuilt from a reattach snapshot (owns the in-flight reply). */
    val liveFromReattach: Boolean = false,
    /** The server's `in_flight_user_turn_id` from the last adopted detail: the prompt of the
     *  round still running, if any. Scopes which persisted turns are that round's (hidden
     *  behind a reattached live reply; their result-less tools still running). */
    val inFlightUserTurnId: String? = null,
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
    /**
     * How this session's answered permission requests went, by tool call id — for the chips on
     * their rows. Live records from `tool_approval` and the attach snapshot, folded by
     * `ToolApprovals.merge`; a reopened transcript's calls carry their own stamps besides.
     */
    val toolApprovals: Map<String, app.codeg.android.core.model.ToolApprovalInfo> = emptyMap(),
    /** The agent is retrying a transient error on its own (`turn_retrying`, Claude's
     *  `api_retry`). Cleared as soon as the turn makes progress again, and at its end. */
    val apiRetry: app.codeg.android.core.model.AcpEvent.ApiRetry? = null,
    /** The connection's AIR session-failure table, watermarks included ([SessionFailures]). */
    val sessionFailures: List<app.codeg.android.core.model.SessionFailureRecord> = emptyList(),
    /** The prompt the agent predicts comes next (`prompt_suggestion`), offered in the empty
     *  composer. Spent when a prompt starts. */
    val promptSuggestion: String? = null,
    /** The connection's AIR async-task table, terminal rows kept (later deltas revise them). */
    val asyncTasks: List<app.codeg.android.core.model.AsyncTaskRecord> = emptyList(),
    /** Background tasks the server's watcher counts as launched and unresolved (whole set). */
    val backgroundTasks: List<app.codeg.android.core.model.BackgroundOutstandingTask> = emptyList(),
    /** Child connections of the delegations this connection still has running. */
    val delegatedChildren: List<String> = emptyList(),
    /** The goal-control actions this connection's adapter offers; empty while unknown. */
    val goalActions: List<String> = emptyList(),
    /** Claude Code's Remote Control on this connection, while it is on. It keeps the agent
     *  running for claude.ai/code after this screen is gone. */
    val remoteControl: app.codeg.android.core.model.RemoteControlInfo? = null,
    /** Whether this connection's agent can fork its session (`fork_supported`). */
    val forkSupported: Boolean = false,
    /** The open folders this conversation may move to; null while they load. */
    val moveTargets: List<FolderDetail>? = null,
    /** Whether the server has the outline route (its windowed detail says how many user turns
     *  precede the window). */
    val outlineSupported: Boolean = false,
    /** The history above the window, for the message navigator. */
    val outline: OutlineState = OutlineState.Idle,
    /** A one-shot: scroll the transcript to this user turn (the navigator's jump). */
    val scrollToTurn: TurnScroll? = null,
    /** The navigator's jump paging history in to reach this turn. */
    val revealingTurnId: String? = null,
    /** This connection's delegations by the call that started each, finished ones kept. */
    val delegations: Map<String, app.codeg.android.core.model.DelegationBinding> = emptyMap(),
    /** Out-of-turn turns read off the agent's transcript, shown until a detail load has them. */
    val backgroundTurns: List<BackgroundOverlay> = emptyList(),
    /** The running agent is on its launch-time config after a settings save; null when not. */
    val configStale: ConfigStaleUi? = null,
    /** `session/load` failed for good: the banner that offers reloading or a new session
     *  stands where the composer was. */
    val sessionLoadError: SessionLoadErrorUi? = null,
    /** Steering notes sent during the turn running now, oldest first. Turn-scoped — a new
     *  turn empties this. */
    val feedbackNotes: List<FeedbackNote> = emptyList(),
    /** Whether this session can take a mid-turn note at all (see `syncNoteChannel`). Says
     *  nothing about timing — pair it with [noteTurnOpen] for "can send one right now". */
    val supportsNotes: Boolean = false,
    /**
     * Whether the SERVER has a turn running that a note would reach.
     *
     * Narrower than [isInFlight], which is set optimistically the moment Send is tapped and
     * so also covers connect and attach — a note sent then either bounces as "no active
     * turn" or, in the window after the server arms the turn but before it emits
     * `user_message`, is accepted and immediately wiped unread.
     */
    val noteTurnOpen: Boolean = false,
    /** Whether a note is pushed into the running turn immediately (native steering) rather
     *  than waiting for the agent to check for it. Only changes the copy shown to the user. */
    val notesLandInstantly: Boolean = false,
    val sendingNote: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /**
     * Where [turns] sits inside the full transcript, or null against a
     * pre-0.24.0 server that only answers with whole transcripts (in which case
     * [turns] IS everything and there is nothing to page).
     */
    val window: TurnWindowState? = null,
    /** A history page is in flight — the loader row spins. */
    val loadingOlder: Boolean = false,
    /**
     * Set once per successful prepend, carrying back the row the transcript asked
     * to keep in place. Inserting above the visible range shifts what the user is
     * reading down the screen, so it has to be scrolled back.
     *
     * The anchor rides the state rather than living in the composable because it
     * has to arrive in the SAME emission as the turns it belongs to: a
     * `remember`ed anchor is re-derived from the list whenever the rows change,
     * and the rows change here — leaving it describing the newly inserted history
     * instead of where the user was.
     */
    val prepend: PrependAnchor? = null,
    /** Mode / config-option state observed on the connection, for the options sheet to fold into
     *  what it loaded. Null until a stream attaches or a value is read; non-null with every
     *  field empty means a connection is being watched that has reported nothing yet. */
    val liveSelectors: LiveSelectors? = null,
) {
    /** Whether older history exists above what is loaded. */
    val hasOlderTurns: Boolean get() = window?.hasOlder == true

    /** Where the loaded server window ends in the full transcript. See [loadedEnd]. */
    val loadedEnd: Int get() = loadedEnd(window, turns)

    /**
     * Turns in the FULL transcript, not just the loaded window — the coordinate a "did this
     * prompt run?" read has to start from, since the loaded length also moves when a window
     * slides or a page is prepended. The larger of the server's count and [loadedEnd]: the
     * window can sit short of the total (a reattach drops the server's partial reply from it).
     */
    val globalTurnCount: Int
        get() = window?.let { maxOf(it.total, loadedEnd) } ?: turns.size
}

/**
 * Where to put the viewport back after a page of older history landed above it.
 * Every field is opaque to the ViewModel — the transcript supplies them when it
 * asks for the page and reads them back when the page lands.
 *
 * Two ways to find the row again, because neither alone is enough. [rowId] is
 * exact and survives new content arriving at the tail, but a row id is not always
 * stable across a prepend: the transcript merges a run of consecutive replies
 * under the id of the turn that STARTS the run, so a window that opened mid-reply
 * (which the server's alignment cap permits) re-keys that first run once the
 * earlier replies are prepended above it. [rowIndex] and [rowCount] then place it
 * by how far the list grew instead.
 *
 * [epoch] distinguishes consecutive prepends, which may name the same row.
 */
data class PrependAnchor(
    val epoch: Int,
    val rowId: String?,
    val rowIndex: Int,
    val rowCount: Int,
    val offset: Int,
)

/** A `permission_request` awaiting the user's choice (parsed for the card). */
data class PendingPermissionUi(
    val requestId: String,
    val parsed: app.codeg.android.feature.sessiondetail.interactive.ParsedPermission,
    val options: List<app.codeg.android.core.model.PermissionOption>,
    /** Requests queued behind this card (`queued`, then `permission_queue_depth`). */
    val queued: Int = 0,
    /** The request's `tool_call` as merged so far ([parsed] is read from it), so a call
     *  update arriving after the card can still fill what the request left out. */
    val toolCall: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull,
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

/** A "restart to apply" drift. [kind]: `agent_config` or `model_provider`. Dismissal lasts
 *  until a fresh drift. */
data class ConfigStaleUi(val kind: String?, val dismissed: Boolean = false)

/**
 * A session that couldn't be loaded, localized by its code. [command] is the exact shell line
 * that undoes it, when there is one (`codex unarchive <id>`); [folderId] is where a new session
 * opens instead.
 */
data class SessionLoadErrorUi(val message: String, val command: String?, val folderId: Int?)

/** The message navigator's read of the history above the window. */
sealed interface OutlineState {
    data object Idle : OutlineState
    data object Loading : OutlineState
    data class Ready(val outline: app.codeg.android.core.model.ConversationOutline) : OutlineState
    data object Failed : OutlineState
}

/** Scroll to [turnId]; [epoch] tells a repeat apart. */
data class TurnScroll(val turnId: String, val epoch: Int)
