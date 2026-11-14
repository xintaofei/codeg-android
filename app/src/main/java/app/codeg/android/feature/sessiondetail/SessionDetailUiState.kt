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
    val turns: List<MessageTurn> = emptyList(),
    val pendingUserTurns: List<MessageTurn> = emptyList(),
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

    /**
     * Turns in the FULL transcript, not just the loaded window — the coordinate
     * any "did the transcript advance?" comparison has to use, since the loaded
     * length also moves when a window slides or a page is prepended.
     *
     * Takes the larger of the server's count and where the loaded window now
     * ends, because turns can be appended locally: `promoteUnreconciled` folds a
     * finished live turn into [turns] without a refetch, so the server's total
     * is a turn or two behind until the next one. Reading it raw there hands the
     * next send a baseline the transcript has ALREADY passed, and the reconcile
     * poll would then accept the previous reply as the new one.
     */
    val globalTurnCount: Int
        get() = window?.let { maxOf(it.total, it.offset + turns.size) } ?: turns.size
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
