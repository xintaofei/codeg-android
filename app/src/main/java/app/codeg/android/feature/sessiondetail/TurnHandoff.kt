package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindowState
import app.codeg.android.feature.sessiondetail.timeline.endsRound
import java.time.Instant

// The turn-end hand-off (codeg web 0.44 `completeTurn`): a turn this client watched to its end
// keeps what streamed as its final content, instead of being swapped for a transcript re-read.
// The re-read raced the server's last transcript write, and losing it lost the reply's tail.
//
// What a hand-off produces are LOCAL turns ([SessionDetailUiState.localTurns]): the transcript
// as this client saw it, after the loaded server window, until a server read is proven to hold
// them ([holdsBatch]). They form one "batch" from the index where the first of them would sit
// in the full transcript — the batch start.

/**
 * Where the loaded server window ends in the full transcript: the index a local turn appended
 * now would take there. Paging older history moves the offset and the size together, so this
 * stays put.
 */
internal fun loadedEnd(window: TurnWindowState?, turns: List<MessageTurn>): Int =
    (window?.offset ?: 0) + turns.size

/** How many prompts (user turns) [turns] holds. */
internal fun promptCount(turns: List<MessageTurn>): Int = turns.count { it.role == TurnRole.USER }

/**
 * The turns a finished round adds to the transcript, in order: its prompts, the reply as it
 * streamed, and — when the round failed — its failure record, shaped the way the server
 * persists one (a system turn of [ContentBlock.TurnError]) so it draws as the same error row.
 *
 * A reply with nothing renderable is left out rather than shown as an empty turn. A cancel
 * marker the client wrote itself ([LiveTurnBuilder.cancelledLocally]) is not a failure and
 * isn't kept: the transcript records none.
 */
internal fun promotedTurns(prompts: List<MessageTurn>, builder: LiveTurnBuilder?): List<MessageTurn> = buildList {
    addAll(prompts)
    if (builder == null || builder.isEmpty) return@buildList
    builder.snapshotAsMessageTurn().takeIf { it.blocks.isNotEmpty() }?.let(::add)
    val error = builder.error?.takeIf { !builder.cancelledLocally } ?: return@buildList
    add(MessageTurn("${builder.id}#failed", TurnRole.SYSTEM, listOf(ContentBlock.TurnError(error.message)), Instant.now()))
}

/**
 * Whether [detail] — a read starting at or before [batchStart] — holds every round of the
 * local batch, so it can replace the local turns without losing any of them.
 *
 * Read off the transcript's shape, as the web's unseen-turn resync does, because nothing
 * else correlates the two: parser turn ids aren't the client's, and the server may split a
 * reply into several turns the client streamed as one. So: the read must reach the end, no
 * round may still be running, the last round must have ended, and there must be at least
 * as many prompts past the batch start as the batch holds ([prompts]). At least, not exactly:
 * a prompt steered into a turn is persisted as a user turn of its own.
 */
internal fun holdsBatch(detail: ConversationDetail, batchStart: Int, prompts: Int): Boolean {
    val offset = detail.window?.offset ?: 0
    // A read starting past the batch can't show its first rounds.
    if (offset > batchStart) return false
    if (!reachesEnd(detail) || detail.inFlightUserTurnId != null || !endsRound(detail.turns)) return false
    return promptCount(detail.turns.drop(batchStart - offset)) >= prompts
}
