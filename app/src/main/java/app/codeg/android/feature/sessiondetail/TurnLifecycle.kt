package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.SessionLastError
import app.codeg.android.core.model.TurnRole
import kotlinx.coroutines.flow.first
import java.time.Instant

// Pure rules for a live turn's lifecycle: whose turn an event or snapshot is, how a reconnect snapshot is reconciled, and plan follow-ups.

/**
 * Whether [detail] holds a user turn matching the prompt [sent] at or past [baseline] (where
 * the transcript stood when it was sent), skipping the first [priorPrompts] there — prompts
 * of earlier rounds this client already shows locally, which sit past the same baseline
 * while their batch is unconfirmed ([holdsBatch]).
 *
 * Matched on content, as the web does while it waits for a batch to persist: parser turn ids
 * aren't the client's. Text must contain what was sent (agents may wrap it); an image-only
 * prompt needs as many images. Not an identity, but scoped past the baseline and the batch's
 * earlier prompts it would take the same prompt sent again by another client in the same gap
 * to fool it.
 */
internal fun sentPromptAt(detail: ConversationDetail, baseline: Int, sent: MessageTurn, priorPrompts: Int = 0): Boolean {
    val offset = detail.window?.offset ?: 0
    val sentText = sent.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }.joinToString("\n").trim()
    val sentImages = sent.blocks.count { it is ContentBlock.Image }
    return detail.turns
        .filterIndexed { i, turn -> offset + i >= baseline && turn.role == TurnRole.USER }
        .drop(priorPrompts)
        .any { turn ->
            val text = turn.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }.joinToString("\n")
            val images = turn.blocks.count { it is ContentBlock.Image }
            if (sentText.isNotEmpty()) text.contains(sentText) else images >= sentImages && sentImages > 0
        }
}

/** Whether [detail]'s turns run to the end of the transcript (a legacy full response does). */
internal fun reachesEnd(detail: ConversationDetail): Boolean =
    detail.window?.let { it.offset + detail.turns.size >= it.total } ?: true

/** What a reconnect snapshot means for the live turn. See [reconnectRecovery]. */
internal enum class ReconnectRecovery {
    /** The turn still runs: rebuild it from the snapshot, which holds what the gap lost. */
    RESEED,

    /** The turn ended inside the gap: finalize it. */
    ENDED_UNSEEN,

    /** A send's turn not yet seen to start, on an idle connection: ask the transcript
     *  whether it already ran ([sentPromptAt]). */
    VERIFY,

    /** A turn started inside the gap with none running here: observe it. */
    OBSERVE,

    /** Nothing runs either side, but events were missed: refresh the transcript. */
    REFRESH,

    /** Nothing to recover. */
    NONE,
}

/**
 * Classify a reconnect snapshot against the local turn.
 *
 * [ownTurnStarted] false means a send whose turn this client hasn't seen start. A running
 * turn is then the one before it. An idle connection is ambiguous: the prompt may still be
 * on its way or queued, or its whole turn may have passed in the gap; only the transcript
 * can tell, so that case is VERIFY.
 *
 * [missedEvents] is the stream's report that the snapshot skipped events.
 */
internal fun reconnectRecovery(
    snapshot: LiveSessionSnapshot,
    turnRunning: Boolean,
    ownTurnStarted: Boolean,
    missedEvents: Boolean = false,
): ReconnectRecovery {
    val running = snapshotTurnRunning(snapshot)
    return when {
        turnRunning && !ownTurnStarted ->
            if (running) ReconnectRecovery.NONE else ReconnectRecovery.VERIFY
        turnRunning && running -> ReconnectRecovery.RESEED
        turnRunning -> ReconnectRecovery.ENDED_UNSEEN
        running -> ReconnectRecovery.OBSERVE
        missedEvents -> ReconnectRecovery.REFRESH
        else -> ReconnectRecovery.NONE
    }
}

/**
 * Whether the snapshot's connection is running a turn right now. Only `prompting` says so: a
 * pending card or a leftover live message can outlast the turn (a permission the server
 * publishes after `turn_complete` as the out-of-turn request it then is).
 */
internal fun snapshotTurnRunning(snap: LiveSessionSnapshot): Boolean =
    snap.status == ConnectionStatus.PROMPTING

/**
 * Whether a snapshot shows anything of a turn: a live message, running tools, a pending card,
 * or the connection prompting. Looser than [snapshotTurnRunning], for a reattach the server
 * has already reported live. a live message, running tools, a pending card,
 * or the connection prompting.
 */
internal fun snapshotShowsTurn(snap: LiveSessionSnapshot): Boolean =
    snapshotTurnRunning(snap) ||
        !snap.liveMessage?.content.isNullOrEmpty() ||
        snap.activeToolCalls.isNotEmpty() ||
        snap.pendingPermission != null || snap.pendingQuestion != null ||
        snap.pendingPlanApproval != null

/** Another client's prompt as a user turn, or null when it carries nothing renderable. */
internal fun observedUserTurn(event: AcpEvent.UserMessage, now: Instant = Instant.now()): MessageTurn? {
    val blocks = event.blocks.mapNotNull {
        when (it) {
            is app.codeg.android.core.model.UserMessageBlock.Text ->
                it.text.takeIf(String::isNotBlank)?.let(ContentBlock::Text)
            is app.codeg.android.core.model.UserMessageBlock.Image -> ContentBlock.Image(it.image)
            app.codeg.android.core.model.UserMessageBlock.Unknown -> null
        }
    }
    if (blocks.isEmpty()) return null
    return MessageTurn(id = "observed-${event.messageId}", role = TurnRole.USER, blocks = blocks, timestamp = now)
}

/**
 * Whether [event] shows the send's own turn has started: `prompting`, or the turn's
 * `user_message` carrying the send's [ownClientMessageId]. The second case matters when a
 * reconnect snapshot lands between the two, since the server emits them separately. An
 * agent-initiated turn (Grok's) has no `user_message`, and another prompt's carries a
 * different id.
 */
internal fun opensOwnTurn(event: AcpEvent, ownClientMessageId: String?): Boolean = when (event) {
    is AcpEvent.StatusChanged -> event.status == ConnectionStatus.PROMPTING
    is AcpEvent.UserMessage -> ownClientMessageId != null && event.messageId == ownClientMessageId
    else -> false
}

/**
 * Whether a stream snapshot shows that the send waiting on its own turn ([ownTurnStarted]
 * still false) has started: the connection is prompting on behalf of a user message. An
 * agent-initiated turn (Grok's) prompts with no user message, so it never qualifies. The
 * first attach of a send is excluded ([isReconnect] false): it precedes the prompt, so any
 * turn it shows is someone else's.
 */
internal fun snapshotStartsOwnTurn(
    snap: app.codeg.android.core.model.LiveSessionSnapshot,
    isReconnect: Boolean,
): Boolean = isReconnect &&
    snap.status == app.codeg.android.core.model.ConnectionStatus.PROMPTING &&
    snap.hasPendingUserMessage

/**
 * The failure a reattach snapshot is allowed to attribute to the turn being seeded from it —
 * null when it is not allowed to attribute one, which is most of the time.
 *
 * The attach snapshot is the only channel for an error that fired before this client arrived
 * (the live `error` event is never re-sent), but `SessionLastError` carries **no turn
 * identity**. Exactly one fact dates it: the server clears `last_error` inside the
 * `StatusChanged(Prompting)` arm that also writes `status = Prompting` (`session_state.rs`).
 * So a snapshot still reporting [ConnectionStatus.PROMPTING] proves the clear ran for the turn
 * we are looking at, and anything recorded since is that turn's.
 *
 * Nothing weaker works, and the weaker gates are the tempting ones:
 * - A conversation row saying `in_progress` doesn't: `manager.rs` writes that BEFORE the
 *   prompt is even enqueued, while the clear waits for the loop to emit `Prompting`.
 * - "Something is in flight" doesn't either. `TurnComplete` clears the live message, the
 *   active tool calls and all three pending cards, but a permission held behind the queue lock
 *   is deliberately published AFTER it, "as the out-of-turn request it now is"
 *   (`drain_permissions_then_emit`) — so a pending card can outlive the turn whose error is
 *   still sitting in `last_error`.
 * - A fork doesn't reset it: `SessionStarted` sets `status = Connected` and leaves
 *   `last_error` alone.
 *
 * Getting this wrong is not a cosmetic miss — it prints the previous turn's failure, with its
 * stderr, at the tail of the turn the user is currently watching.
 *
 * [current] wins on the message: a message already on the turn came from an event we DID see,
 * so the snapshot's copy only fills a gap. Evidence-only, because the `turn_failed_empty*`
 * family is the sole producer of `details` and it is the one failure a late client cannot
 * reconstruct from the transcript; without that scope, every attach to a connection carrying
 * an older ordinary error would re-raise it.
 */
internal fun snapshotTurnError(
    status: ConnectionStatus?,
    lastError: SessionLastError?,
    current: LiveTurnError?,
    fallbackMessage: String,
): LiveTurnError? {
    if (status != ConnectionStatus.PROMPTING) return null
    val evidence = lastError?.details?.trim()?.ifEmpty { null } ?: return null
    return LiveTurnError(
        message = current?.message ?: lastError.message.trim().ifEmpty { fallbackMessage },
        details = evidence,
    )
}

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
