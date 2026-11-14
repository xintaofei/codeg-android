package app.codeg.android.feature.sessiondetail.timeline

// Which replies show their steps (codeg web 0.44 `ReplyFoldState` in `message-list-view.tsx`
// and the manual overrides in `completed-turn-content.tsx`). A settled reply shows its header
// and its answer, its steps folded away under the header; the reply being written shows them;
// sending a message folds every reply above it.

/**
 * Fold state for a thread's replies, owned by the screen rather than by the replies.
 *
 * - [armed]: the agent has started replying since the last send, so the newest reply is "the
 *   current round" and shows open. Within an [epoch] it only latches true: a round must not
 *   fold itself up the moment it finishes.
 * - [roundOpen]: that round's toggle, so folding it by hand sticks while it goes from the
 *   stream into history.
 * - [epoch]: bumped on every send and every new round. A reply folded or opened by hand
 *   ([ManualFold]) is stamped with it, so one bump folds everything above without a walk.
 *
 * Positional (the newest reply) on purpose: a reply's id changes on its way into history (a
 * server read replacing the turns handed off locally), and an id-keyed flag would drop the
 * expansion mid-read.
 */
data class ReplyFoldState(
    val signal: Int = 0,
    val epoch: Int = 0,
    val armed: Boolean = false,
    /** Last observed running flag: a new round is an EDGE, which [armed] alone can't see. */
    val running: Boolean = false,
    /** The live reply this round was armed on, to tell a new reply from the same one resuming. */
    val runId: String? = null,
    val roundOpen: Boolean = true,
)

/** One composition's observation of the thread. */
data class ReplyFoldInput(
    /** Bumped by every send of this client's ([SessionDetailUiState.sendSignal]). */
    val sendSignal: Int,
    /** The newest reply is still being written. */
    val running: Boolean,
    /**
     * The live reply's id, or null when nothing streams here (a round the server flags in
     * flight, read from the transcript). Deliberately not the reply's row id: replies merge,
     * so a new one could share the id of the one before it.
     */
    val runId: String?,
)

/** Advance [prev] for one observation; [prev] itself when nothing moved. Web `advanceReplyFold`. */
fun advanceReplyFold(prev: ReplyFoldState, next: ReplyFoldInput): ReplyFoldState {
    val (sendSignal, running, runId) = next
    if (prev.signal != sendSignal) {
        // Normally the reply above has settled, so the thread folds shut behind the new
        // message; armed at once only when the send lands mid-reply.
        return ReplyFoldState(sendSignal, prev.epoch + 1, armed = running, running = running, runId = runId, roundOpen = true)
    }
    if (running && !prev.running) {
        // The same reply resuming (it was settled early, then re-bridged while still
        // streaming) — not a new round, which would fold what the reader opened.
        if (runId != null && runId == prev.runId) return prev.copy(running = true)
        // A reply just started without a send of this client's (another client's, or the
        // agent's own): the new round, as a send would make it.
        return prev.copy(epoch = prev.epoch + 1, armed = true, running = true, runId = runId, roundOpen = true)
    }
    if (running && prev.running && runId != prev.runId) {
        // The id moved while one reply kept running: it was re-identified (a reattach naming
        // it late, a reconnect re-seeding it), never a new round — that dips `running` first.
        return prev.copy(runId = runId)
    }
    if (!running && prev.running) {
        // The round settled. Only `running` moves: the reply must not fold itself up.
        return prev.copy(running = false)
    }
    return prev
}

/** A reader's own fold or unfold of a reply outside the current round, under [epoch]. */
data class ManualFold(val epoch: Int, val open: Boolean)

/**
 * Whether a reply shows its steps. The current round follows [ReplyFoldState.roundOpen]; any
 * other the reader's own choice made under this epoch, else open only while it is written.
 */
fun replyOpen(fold: ReplyFoldState, currentRound: Boolean, manual: ManualFold?, completed: Boolean): Boolean = when {
    currentRound -> fold.roundOpen
    manual != null && manual.epoch == fold.epoch -> manual.open
    else -> !completed
}

/**
 * The override carrying a round's expansion when a newer reply takes its place without a
 * send or a new round — a reply landing whole from a read — so it doesn't snap shut under
 * the reader. Null when there's nothing to carry.
 */
fun carriedFold(
    fold: ReplyFoldState,
    previousRound: String?,
    previousEpoch: Int,
    currentRound: String?,
): Pair<String, ManualFold>? {
    if (previousRound == null || previousRound == currentRound || previousEpoch != fold.epoch) return null
    return previousRound to ManualFold(fold.epoch, fold.roundOpen)
}
