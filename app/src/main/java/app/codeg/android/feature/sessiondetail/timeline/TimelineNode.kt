package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.designsystem.markdown.LiveBlockParser
import app.codeg.android.core.designsystem.markdown.MarkdownBlock
import app.codeg.android.core.designsystem.markdown.MarkdownCache
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.core.model.isTurnErrorRecord
import app.codeg.android.core.model.turnErrors
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.LiveToolCallState
import app.codeg.android.feature.sessiondetail.LiveTurnState
import app.codeg.android.feature.sessiondetail.STEER_TURN_PREFIX
import app.codeg.android.feature.sessiondetail.rendering.ActivitySummary
import app.codeg.android.feature.sessiondetail.rendering.ContextCompaction
import app.codeg.android.feature.sessiondetail.rendering.MessageRender
import app.codeg.android.feature.sessiondetail.rendering.ProposedPlan
import app.codeg.android.feature.sessiondetail.rendering.RenderPart
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import app.codeg.android.feature.sessiondetail.rendering.ToolCallVM
import app.codeg.android.feature.sessiondetail.rendering.ToolKindBucket
import app.codeg.android.feature.sessiondetail.rendering.answerStart
import app.codeg.android.feature.sessiondetail.rendering.summarizeActivity

/**
 * The payload of one timeline row. The whole transcript (persisted turns +
 * optimistic pending turns + the live streaming reply) flattens into a single
 * ordered list of [TimelineNode]s, each drawn against the rail with a gutter
 * marker. Port of the iOS `TimelineNode.Content`.
 */
sealed interface NodeContent {
    data class User(val turn: MessageTurn) : NodeContent
    data class System(val turn: MessageTurn) : NodeContent

    /** One Markdown block of an assistant reply. A long message is split into these so
     *  the LazyColumn virtualizes within it; [streaming] (last block only) draws the caret. */
    data class AssistantBlock(val block: MarkdownBlock, val streaming: Boolean = false) : NodeContent
    data class Reasoning(val text: String, val streaming: Boolean = false) : NodeContent
    data class Tool(val vm: ToolCallVM) : NodeContent
    data class ToolGroup(val items: List<ToolCallVM>, val streaming: Boolean) : NodeContent
    data class Image(val image: ImageData, val caption: String?) : NodeContent
    /** A context-compaction boundary — rendered as a chrome-less divider, not a card. */
    data class Compaction(val part: RenderPart.Compaction) : NodeContent
    data class Footer(val turn: MessageTurn, val questionId: String?) : NodeContent
    data class Plan(val entries: List<PlanEntry>) : NodeContent

    /** codex's Plan-mode plan document: part of the reply's answer. */
    data class ProposedPlan(val markdown: String, val streaming: Boolean) : NodeContent
    data object Thinking : NodeContent
    /** [details] is the server's redacted diagnostic evidence, shown behind a disclosure (see
     *  [LiveTurnError]). Present only for failures codeg *inferred* — an ordinary error, and a
     *  cancellation, carry none. */
    data class Error(val message: String, val details: String? = null) : NodeContent
    data class Unsupported(val type: String) : NodeContent

    /**
     * The head of an assistant reply (web `ActivityHeader`): what it did — its steps, its
     * failures, the time it took — and, when [foldable], the control folding its steps away.
     * [failureRowId] is the row "show the failure" opens the reply at.
     */
    data class ReplyHeader(
        val replyId: String,
        val summary: ActivitySummary,
        val completed: Boolean,
        val hasSteps: Boolean,
        val durationMs: Int?,
        val foldable: Boolean,
        val open: Boolean,
        val failureRowId: String?,
    ) : NodeContent

    /** The foot of an open reply's steps: what they were, and the control folding them back. */
    data class ReplyFoot(val replyId: String, val steps: Int, val durationMs: Int?) : NodeContent
}

/**
 * One row on the timeline. [connectTop]/[connectBottom] are suppressed only at the
 * very first/last node so the rail terminates at its endpoints; [startsGroup]
 * marks a reply boundary (user/system) that gets extra breathing room above.
 * Port of the iOS `TimelineNode`.
 */
data class TimelineNode(
    val id: String,
    val content: NodeContent,
    val agent: AgentType,
    val connectTop: Boolean = true,
    val connectBottom: Boolean = true,
    val startsGroup: Boolean = false,
    val rail: RailStyle = RailStyle.Standalone,
) {
    val marker: MarkerKind
        get() = when (val c = content) {
            is NodeContent.User -> MarkerKind.User
            is NodeContent.System -> MarkerKind.System
            // The reply's header carries the agent; its prose is a point on the rail.
            is NodeContent.AssistantBlock -> MarkerKind.Prose
            is NodeContent.ReplyHeader -> MarkerKind.Assistant(agent)
            is NodeContent.ReplyFoot -> MarkerKind.Fold
            is NodeContent.Reasoning -> MarkerKind.Reasoning
            is NodeContent.Tool -> MarkerKind.Tool(c.vm.bucket, c.vm.state, c.vm.id)
            is NodeContent.ToolGroup -> MarkerKind.ToolGroup(
                c.items.any { it.state == ToolCallState.ERROR },
                c.streaming,
                c.items.filter { it.state == ToolCallState.RUNNING || it.state == ToolCallState.INPUT_STREAMING }.map { it.id },
            )
            is NodeContent.Image -> MarkerKind.Image
            is NodeContent.Compaction -> MarkerKind.Compaction
            is NodeContent.Footer -> MarkerKind.Footer
            is NodeContent.Plan, is NodeContent.ProposedPlan -> MarkerKind.Plan
            is NodeContent.Thinking -> MarkerKind.Thinking
            is NodeContent.Error -> MarkerKind.Error
            is NodeContent.Unsupported -> MarkerKind.System // reuse the dim system tick
        }

    /** LazyColumn `contentType` — recycled slots only reuse a subtree of the same shape,
     *  so assistant blocks split by their Markdown kind (a Paragraph slot can't serve Code). */
    val contentTypeKey: Any
        get() = when (val c = content) {
            is NodeContent.AssistantBlock -> when (c.block) {
                is MarkdownBlock.Paragraph -> "ab.paragraph"
                is MarkdownBlock.Heading -> "ab.heading"
                is MarkdownBlock.BulletList -> "ab.bullet"
                is MarkdownBlock.NumberedList -> "ab.numbered"
                is MarkdownBlock.Quote -> "ab.quote"
                is MarkdownBlock.Code -> "ab.code"
                MarkdownBlock.Rule -> "ab.rule"
                is MarkdownBlock.Table -> "ab.table"
            }
            is NodeContent.User -> "user"
            is NodeContent.System -> "system"
            is NodeContent.Reasoning -> "reasoning"
            is NodeContent.Tool -> "tool"
            is NodeContent.ToolGroup -> "toolgroup"
            is NodeContent.Image -> "image"
            is NodeContent.Compaction -> "compaction"
            is NodeContent.Footer -> "footer"
            is NodeContent.Plan -> "plan"
            is NodeContent.ProposedPlan -> "proposedplan"
            is NodeContent.Thinking -> "thinking"
            is NodeContent.Error -> "error"
            is NodeContent.Unsupported -> "unsupported"
            is NodeContent.ReplyHeader -> "reply.header"
            is NodeContent.ReplyFoot -> "reply.foot"
        }
}

/** A turn's text blocks, joined — the renderable system-note text (and the empty-skip check). */
internal fun systemText(turn: MessageTurn): String =
    turn.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }.joinToString("\n").trim()

/**
 * Whether [turns] end on a finished round: an assistant reply with content, or a failed
 * round's closing [isTurnErrorRecord] (a failure persists no assistant reply of its own).
 */
internal fun endsRound(turns: List<MessageTurn>): Boolean {
    val last = turns.lastOrNull() ?: return false
    return (last.role == TurnRole.ASSISTANT && last.blocks.isNotEmpty()) || last.isTurnErrorRecord
}

/** Drop the trailing run of assistant turns (those after the last non-assistant turn).
 *  The fallback for [hideInFlightReply] when the server names no in-flight prompt. */
internal fun List<MessageTurn>.dropTrailingAssistantRun(): List<MessageTurn> {
    var end = size
    while (end > 0 && this[end - 1].role == TurnRole.ASSISTANT) end--
    return if (end == size) this else subList(0, end)
}

/** Index of the in-flight round's prompt ([inFlightUserTurnId]) in these turns, or -1 when
 *  none is named or this window doesn't contain it. */
internal fun List<MessageTurn>.inFlightRoundStart(inFlightUserTurnId: String?): Int =
    if (inFlightUserTurnId == null) -1
    else indexOfFirst { it.role == TurnRole.USER && it.id == inFlightUserTurnId }

/**
 * Hide the persisted partial reply of the round that is still running. On reattach the
 * live turn rebuilt from the snapshot owns that reply, so the copy the server persists
 * meanwhile must not render too.
 *
 * The server names the round by its prompt (`in_flight_user_turn_id`, which since 0.4x is
 * the round's EARLIEST matching user turn), and the round can run past that prompt with
 * mid-turn user messages and several assistant turns. So every assistant turn after it is
 * hidden; the user/system turns stay. Without that id, fall back to the trailing assistant
 * run. Mirrors the web's `visiblePersistedTurns` (`conversation-runtime-store.ts`).
 */
internal fun List<MessageTurn>.hideInFlightReply(inFlightUserTurnId: String?): List<MessageTurn> {
    val start = inFlightRoundStart(inFlightUserTurnId)
    if (start < 0) return dropTrailingAssistantRun()
    return filterIndexed { i, turn -> i <= start || turn.role != TurnRole.ASSISTANT }
}

/**
 * Tool calls of the round that is STILL RUNNING: a `tool_use` with no `tool_result` in an
 * assistant turn after the in-flight prompt. Anywhere else a missing result is settled
 * history (the round ended), never "running", or a call orphaned by an interrupted round
 * would spin forever. Port of the web's `collectInFlightPersistedToolCalls`.
 */
internal fun List<MessageTurn>.inFlightToolCallIds(inFlightUserTurnId: String?): Set<String> {
    val start = inFlightRoundStart(inFlightUserTurnId)
    if (start < 0) return emptySet()
    val out = mutableSetOf<String>()
    for (i in (start + 1) until size) {
        val turn = this[i]
        if (turn.role != TurnRole.ASSISTANT) continue
        val settled = turn.blocks.mapNotNullTo(mutableSetOf()) { (it as? ContentBlock.ToolResult)?.id }
        turn.blocks.forEach { b ->
            if (b is ContentBlock.ToolUse && b.id != null && b.id !in settled) out.add(b.id)
        }
    }
    return out
}

/**
 * One assistant reply's rows, before its fold is applied ([TranscriptTimeline.flatten]).
 * Its parts split at the last step: [process] up to it, [answer] after it.
 */
data class ReplyRows(
    val id: String,
    val agent: AgentType,
    /** The reply's work: its steps, and the prose and thoughts between them. */
    val process: List<TimelineNode>,
    /** The reply's closing words, in view whether its steps are folded or not. */
    val answer: List<TimelineNode>,
    /** What closes the reply whatever its fold: the agent working, a failure, its footer. */
    val trailer: List<TimelineNode>,
    val summary: ActivitySummary,
    val completed: Boolean,
    val durationMs: Int?,
    /** The row holding [ActivitySummary.firstUnrecoveredId]'s call. */
    val failureRowId: String?,
    /** Process rows that stay in view folded: a sub-agent still running outlives its turn. */
    val pinned: Set<String> = emptySet(),
) {
    /**
     * Whether folding has anything to trade away. Folding keeps the answer, so a settled
     * reply with none — stopped mid-call, or ended on a card that IS its answer — stays
     * open; a reply being written hasn't written its answer yet, and is foldable as soon as
     * it has a step.
     */
    val foldable: Boolean get() = process.isNotEmpty() && (!completed || answer.isNotEmpty())

    /** Nothing to head: an empty placeholder reply keeps only its [trailer]. */
    val blank: Boolean get() = completed && process.isEmpty() && answer.isEmpty()
}

/** One entry of the thread: a row always drawn, or a reply whose steps fold. */
sealed interface ThreadEntry {
    data class Row(val node: TimelineNode) : ThreadEntry
    data class Reply(val rows: ReplyRows) : ThreadEntry
}

/**
 * Flattens a conversation into an ordered `[TimelineNode]`. User/system turns map 1:1; runs
 * of consecutive assistant turns merge into one reply (codeg persists a logical reply as
 * several turns), headed by its activity header, its steps folding away under it.
 *
 * Three stages, so each re-runs only when its own input moves: [persistedEntries] (the
 * transcript; memoize it), [liveEntries] (the reply streaming in, every flush), and
 * [flatten] (the fold, cheap).
 */
object TranscriptTimeline {

    /** Every reply open, the rail terminated — the whole transcript as rows. */
    fun build(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        live: LiveTurnState?,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
        inFlightUserTurnId: String? = null,
        localTurns: List<MessageTurn> = emptyList(),
    ): List<TimelineNode> = terminate(
        flatten(
            dedupeCompactions(
                persistedEntries(turns, pending, agent, hideTrailingAssistantReply, inFlightUserTurnId, localTurns) + liveEntries(live, agent),
            ),
        ) { true },
    )

    /**
     * The persisted + optimistic-pending entries.
     *
     * [hideTrailingAssistantReply] drops the in-flight round's persisted assistant turns
     * ([hideInFlightReply], keyed by [inFlightUserTurnId]) — used on reattach, where the live
     * turn rebuilt from the snapshot is the authoritative in-flight reply while the agent
     * concurrently persists a partial copy into [turns]; showing both would double the reply.
     * [localTurns] (finished rounds handed off locally) follow the server window.
     */
    fun persistedEntries(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
        inFlightUserTurnId: String? = null,
        localTurns: List<MessageTurn> = emptyList(),
    ): List<ThreadEntry> {
        // Only the server's window can hold a partial copy of the running reply; the local
        // turns after it are finished rounds.
        val server = if (hideTrailingAssistantReply) turns.hideInFlightReply(inFlightUserTurnId) else turns
        val source = server + localTurns
        val runningToolIds = source.inFlightToolCallIds(inFlightUserTurnId)
        // A reply after the in-flight prompt is still being written, read from the transcript.
        val inFlightStart = server.inFlightRoundStart(inFlightUserTurnId)
        val entries = mutableListOf<ThreadEntry>()
        var lastUserId: String? = null
        var i = 0
        while (i < source.size) {
            val turn = source[i]
            when (turn.role) {
                TurnRole.USER -> {
                    lastUserId = turn.id
                    entries.add(ThreadEntry.Row(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true)))
                    i++
                }
                TurnRole.SYSTEM -> {
                    if (systemText(turn).isNotEmpty()) {
                        entries.add(ThreadEntry.Row(TimelineNode(turn.id, NodeContent.System(turn), agent, startsGroup = true)))
                    }
                    // A failed round's closing record: drawn as the same error row the live
                    // turn showed, attached to the reply it ends (no group gap above it).
                    turn.turnErrors.forEachIndexed { k, message ->
                        val id = if (k == 0) "${turn.id}#error" else "${turn.id}#error$k"
                        entries.add(ThreadEntry.Row(TimelineNode(id, NodeContent.Error(message), agent)))
                    }
                    i++
                }
                TurnRole.ASSISTANT -> {
                    if (hoistsCompaction(source, i)) {
                        compactionRow(turn, agent, runningToolIds)?.let { entries.add(ThreadEntry.Row(it)) }
                        i++
                        continue
                    }
                    // The reply runs on through its assistant turns, and through the empty
                    // placeholders parsers leave between them — but not into a compaction it
                    // doesn't go on after, which stands between turns.
                    var j = i + 1
                    var end = j
                    while (j < source.size) {
                        val next = source[j]
                        if (next.role == TurnRole.ASSISTANT) {
                            if (hoistsCompaction(source, j)) break
                            end = ++j
                        } else if (rendersNothing(next)) {
                            j++
                        } else {
                            break
                        }
                    }
                    val merged = merge(source.subList(i, end).filter { it.role == TurnRole.ASSISTANT })
                    val completed = !(inFlightStart >= 0 && i > inFlightStart && i < server.size)
                    entries.add(ThreadEntry.Reply(persistedReply(merged, lastUserId, agent, runningToolIds, completed)))
                    i = end
                }
            }
        }
        for (turn in pending) {
            entries.add(ThreadEntry.Row(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true)))
        }
        return entries
    }

    /**
     * A turn with nothing to draw: the empty placeholders parsers leave between a reply's
     * turns, which don't break it (web `isEmptyTurnItem`). A failed round's record is not one.
     */
    private fun rendersNothing(turn: MessageTurn): Boolean = when (turn.role) {
        TurnRole.SYSTEM -> systemText(turn).isEmpty() && turn.turnErrors.isEmpty()
        TurnRole.USER, TurnRole.ASSISTANT -> turn.blocks.all { it is ContentBlock.Text && it.text.isBlank() }
    }

    /** A turn holding one compaction call (and its result) and nothing else. */
    private fun isCompactionOnly(turn: MessageTurn): Boolean {
        if (turn.role != TurnRole.ASSISTANT) return false
        var compaction: ContentBlock.ToolUse? = null
        for (block in turn.blocks) {
            when (block) {
                is ContentBlock.Text -> if (block.text.isNotBlank()) return false
                is ContentBlock.ToolUse -> {
                    if (compaction != null || !ContextCompaction.matches(block.meta)) return false
                    compaction = block
                }
                is ContentBlock.ToolResult -> if (compaction == null || (block.id != null && block.id != compaction.id)) return false
                else -> return false
            }
        }
        return compaction != null
    }

    /**
     * Whether the turn at [index] is a compaction drawn as a divider between turns rather than
     * as a step of a reply (web `hoistedCompaction`): it holds nothing else, and no reply goes
     * on after it — the next turn with anything in it, past empty placeholders and other
     * compactions, is not the agent's. An agent compacting by itself mid-turn goes on with the
     * same reply, and live the compaction is one of that reply's steps; hoisted out, it would
     * cut the reply in two. A `/compact` between replies, or one at the very end, divides.
     */
    private fun hoistsCompaction(source: List<MessageTurn>, index: Int): Boolean {
        if (!isCompactionOnly(source[index])) return false
        for (k in index + 1 until source.size) {
            val next = source[k]
            if (rendersNothing(next)) continue
            if (next.role != TurnRole.ASSISTANT) return true
            if (!isCompactionOnly(next)) return false
        }
        return true
    }

    /** A compaction-only turn → its divider row, under the id its part would have in a reply. */
    private fun compactionRow(turn: MessageTurn, agent: AgentType, runningToolIds: Set<String>): TimelineNode? {
        val parts = MessageRender.adaptParts(turn, runningToolIds)
        val index = parts.indexOfFirst { it is RenderPart.Compaction }
        val part = parts.getOrNull(index) as? RenderPart.Compaction ?: return null
        return TimelineNode("${turn.id}#$index", NodeContent.Compaction(part), agent, startsGroup = true)
    }

    /**
     * Drop repeat renderings of one compaction divider, keeping the first (web
     * `dedupeCompactionItems`). A compaction reaches the thread twice: the live call, and the
     * agent's transcript under the parser's id — and mid-turn both can be in hand, with no
     * in-flight prompt to hide the transcript's copy by (`/compact` is persisted after the
     * boundary, or never). Two identities, either enough: the call id where both copies name
     * the event alike (codex), the figures where they don't ([ContextCompaction.eventKey]).
     */
    fun dedupeCompactions(entries: List<ThreadEntry>): List<ThreadEntry> {
        val seen = mutableSetOf<String>()
        var dropped = false
        val kept = entries.filter { entry ->
            val part = ((entry as? ThreadEntry.Row)?.node?.content as? NodeContent.Compaction)?.part ?: return@filter true
            val keys = listOfNotNull(part.eventKey, part.callId?.let { "call:$it" })
            val repeat = keys.any { it in seen }
            // A repeat's other identity names the same event too.
            seen.addAll(keys)
            if (repeat) dropped = true
            !repeat
        }
        return if (dropped) kept else entries
    }

    /** A merged reply → its rows, and a footer (always, even tool-only). Text is parse-memoized. */
    private fun persistedReply(
        merged: MessageTurn,
        questionId: String?,
        agent: AgentType,
        runningToolIds: Set<String>,
        completed: Boolean,
    ): ReplyRows {
        val calls = MessageRender.adaptParts(merged, runningToolIds)
        val footer = TimelineNode("${merged.id}#footer", NodeContent.Footer(merged, questionId), agent)
        return replyRows(merged.id, agent, calls, cached = true, completed, merged.durationMs, trailer = listOf(footer))
    }

    /**
     * The in-flight reply → its entries: the reply (its parts, the plan where the agent last
     * revised it), the agent working (before its first word), its failure.
     *
     * A note steered into the turn ([LiveSegment.Steer]) splits it the way the transcript
     * will: the reply so far, the note as the user's message, then the reply that follows —
     * each reply of its own. Each part after the first takes the id its settled turn will
     * have (`<id>-<n>`, see [LiveTurnBuilder.snapshotAsMessageTurns]).
     */
    fun liveEntries(live: LiveTurnState?, agent: AgentType): List<ThreadEntry> {
        if (live == null) return emptyList()
        val chunks = mutableListOf(mutableListOf<LiveSegment>())
        val steers = mutableListOf<LiveSegment.Steer>()
        for (seg in live.segments) {
            if (seg is LiveSegment.Steer) {
                steers += seg
                chunks += mutableListOf<LiveSegment>()
            } else {
                chunks.last() += seg
            }
        }
        val entries = mutableListOf<ThreadEntry>()
        chunks.forEachIndexed { k, chunk ->
            if (k > 0) {
                val steer = steers[k - 1]
                val turn = MessageTurn(
                    "$STEER_TURN_PREFIX${steer.noteId}",
                    TurnRole.USER,
                    listOf(ContentBlock.Text(steer.text)),
                    java.time.Instant.EPOCH,
                )
                entries.add(ThreadEntry.Row(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true)))
            }
            val ownerId = if (k == 0) live.id else "${live.id}-$k"
            val last = k == chunks.lastIndex
            // The parts before a steered note are done: the agent went on past them.
            val streaming = last && live.isStreaming
            val trailer = buildList {
                // Nothing streamed yet (or nothing since a note was steered in): the agent is
                // working on it.
                if (streaming && chunk.isEmpty()) {
                    add(TimelineNode("$ownerId#thinking", NodeContent.Thinking, agent))
                }
                if (last) live.error?.let { add(TimelineNode("${live.id}#error", NodeContent.Error(it.message, it.details), agent)) }
            }
            val calls = liveParts(chunk, streaming)
            // A compaction with nothing else said (yet) is a divider between turns, as in
            // history ([hoistsCompaction]); once the reply goes on, it is one of its steps.
            val compaction = calls.singleOrNull() as? RenderPart.Compaction
            if (compaction != null) {
                entries.add(ThreadEntry.Row(TimelineNode("$ownerId#0", NodeContent.Compaction(compaction), agent, startsGroup = true)))
                trailer.forEach { entries.add(ThreadEntry.Row(it)) }
                return@forEachIndexed
            }
            // Live text is parsed uncached (each flush is a new key → cache churn).
            entries.add(
                ThreadEntry.Reply(
                    replyRows(ownerId, agent, calls, cached = false, completed = !streaming, durationMs = null, trailer, caret = streaming),
                ),
            )
        }
        return entries
    }

    /**
     * A reply's parts → its rows. [calls] ungrouped, so the summary counts what grouping
     * leaves out. [caret] marks the final block of prose being written — only when the reply
     * ends on prose: a trailing tool or group shows its own pulsing marker instead (matches
     * iOS `adaptLive` streaming-on-last-segment).
     */
    private fun replyRows(
        id: String,
        agent: AgentType,
        calls: List<RenderPart>,
        cached: Boolean,
        completed: Boolean,
        durationMs: Int?,
        trailer: List<TimelineNode>,
        caret: Boolean = false,
    ): ReplyRows {
        val parts = MessageRender.groupConsecutiveTools(calls)
        val rows = parts.mapIndexed { idx, part -> nodesFor(part, id, idx, agent, cached) }.toMutableList()
        val lastRows = rows.lastOrNull()
        val tail = lastRows?.lastOrNull()
        if (caret && tail != null) {
            val marked = when (val c = tail.content) {
                is NodeContent.AssistantBlock -> tail.copy(content = c.copy(streaming = true))
                is NodeContent.Reasoning -> tail.copy(content = c.copy(streaming = true))
                else -> null
            }
            if (marked != null) rows[rows.lastIndex] = lastRows.dropLast(1) + marked
        }
        val split = answerStart(parts)
        val summary = summarizeActivity(calls)
        val process = rows.take(split).flatten()
        return ReplyRows(
            id = id,
            agent = agent,
            process = process,
            answer = rows.drop(split).flatten(),
            trailer = trailer,
            summary = summary,
            completed = completed,
            durationMs = durationMs,
            failureRowId = summary.firstUnrecoveredId?.let { failed -> rowOfCall(parts, id, failed) },
            pinned = process.filter { node ->
                val vm = (node.content as? NodeContent.Tool)?.vm
                vm != null && vm.bucket == ToolKindBucket.TASK && vm.state == ToolCallState.RUNNING
            }.mapTo(mutableSetOf()) { it.id },
        )
    }

    /** The id of the row drawing call [callId]: its own, or the group it is folded into. */
    private fun rowOfCall(parts: List<RenderPart>, ownerId: String, callId: String): String? {
        parts.forEachIndexed { idx, part ->
            when (part) {
                is RenderPart.Tool -> if (part.vm.id == callId) return toolRowId(part.vm)
                is RenderPart.ToolGroup -> if (part.items.any { it.id == callId }) return groupRowId(part, "$ownerId#$idx")
                else -> Unit
            }
        }
        return null
    }

    /**
     * Thread entries → rows, each reply folded or not as [open] says (asked only of a reply
     * that can fold). A reply's header leads it; folded, its process gives way to its answer
     * (bar what [ReplyRows.pinned] keeps); open and settled, a foot between its steps and its
     * answer folds them back.
     */
    fun flatten(entries: List<ThreadEntry>, open: (ReplyRows) -> Boolean): List<TimelineNode> {
        val out = ArrayList<TimelineNode>(entries.size * 2)
        for (entry in entries) {
            when (entry) {
                is ThreadEntry.Row -> out.add(entry.node)
                is ThreadEntry.Reply -> {
                    val r = entry.rows
                    if (r.blank) {
                        out.addAll(r.trailer)
                        continue
                    }
                    val isOpen = !r.foldable || open(r)
                    out.add(
                        TimelineNode(
                            "${r.id}#header",
                            NodeContent.ReplyHeader(
                                replyId = r.id,
                                summary = r.summary,
                                completed = r.completed,
                                hasSteps = r.process.isNotEmpty(),
                                durationMs = r.durationMs,
                                foldable = r.foldable,
                                open = isOpen,
                                failureRowId = r.failureRowId,
                            ),
                            r.agent,
                        ),
                    )
                    if (isOpen) out.addAll(r.process) else r.process.filterTo(out) { it.id in r.pinned }
                    // The foot closes the steps, above the answer they fold away from.
                    if (isOpen && r.foldable && r.completed) {
                        out.add(TimelineNode("${r.id}#foot", NodeContent.ReplyFoot(r.id, r.summary.steps, r.durationMs), r.agent))
                    }
                    out.addAll(r.answer)
                    out.addAll(r.trailer)
                }
            }
        }
        return out
    }

    /** End the rail at its endpoints. */
    fun terminate(nodes: List<TimelineNode>): List<TimelineNode> {
        if (nodes.isEmpty()) return nodes
        val out = nodes.toMutableList()
        out[0] = out[0].copy(connectTop = false)
        out[out.lastIndex] = out[out.lastIndex].copy(connectBottom = false)
        return out
    }

    /** [streaming]: the reply is still written, so an unclosed `<proposed_plan>` is a card being written. */
    private fun liveParts(segments: List<LiveSegment>, streaming: Boolean): List<RenderPart> {
        val parts = mutableListOf<RenderPart>()
        for (seg in segments) {
            when (seg) {
                is LiveSegment.Text -> if (seg.text.isNotBlank()) {
                    ProposedPlan.expand(seg.text, streaming)?.let(parts::addAll) ?: parts.add(RenderPart.Text(seg.text))
                }
                // Where the agent last revised it — the same place, and the same card, as
                // the turn handed off keeps it ([LiveTurnBuilder.snapshotAsMessageTurns]).
                is LiveSegment.Plan -> if (seg.entries.isNotEmpty()) parts.add(RenderPart.Plan(seg.entries))
                is LiveSegment.Thinking -> if (seg.text.isNotBlank()) parts.add(RenderPart.Reasoning(seg.text))
                // Same boundary-marker treatment as the persisted path: codex emits the
                // `Context compacting` → `Context compacted` pair on one id, so the
                // divider flips from running to settled in place.
                is LiveSegment.Tool -> if (ContextCompaction.matches(seg.call.meta)) {
                    val call = seg.call
                    val output = call.rawOutput.takeIf { it.isNotBlank() } ?: call.content
                    parts.add(ContextCompaction.part(call.id, call.meta, !call.isFinished, call.isError, output))
                } else {
                    parts.add(RenderPart.Tool(seg.call.toVM()))
                }
                // Split on by [liveEntries]; never reaches a part list.
                is LiveSegment.Steer -> Unit
            }
        }
        return parts
    }

    private fun toolRowId(vm: ToolCallVM) = "tool-${vm.id}"

    private fun groupRowId(group: RenderPart.ToolGroup, base: String) =
        group.items.firstOrNull()?.let { "toolgroup-${it.id}" } ?: base

    /**
     * One render part → node(s). Text splits into per-block nodes (below); everything
     * else is a single node. The ID scheme is load-bearing: a tool keeps the same row
     * identity across the live→persisted handoff (keyed by its ACP tool id), so the
     * card's expand state and marker survive without flashing.
     */
    private fun nodesFor(part: RenderPart, ownerId: String, index: Int, agent: AgentType, cached: Boolean): List<TimelineNode> {
        val base = "$ownerId#$index"
        return when (part) {
            is RenderPart.Text -> textBlockNodes(part.text, base, agent, cached)
            is RenderPart.Reasoning -> listOf(TimelineNode(base, NodeContent.Reasoning(part.text), agent))
            is RenderPart.Tool -> listOf(TimelineNode(toolRowId(part.vm), NodeContent.Tool(part.vm), agent))
            is RenderPart.ToolGroup ->
                listOf(TimelineNode(groupRowId(part, base), NodeContent.ToolGroup(part.items, part.streaming), agent))
            is RenderPart.Image -> listOf(TimelineNode(base, NodeContent.Image(part.image, part.caption), agent))
            is RenderPart.Compaction -> listOf(TimelineNode(base, NodeContent.Compaction(part), agent))
            is RenderPart.Error -> listOf(TimelineNode(base, NodeContent.Error(part.message), agent))
            is RenderPart.Unknown -> listOf(TimelineNode(base, NodeContent.Unsupported(part.type), agent))
            is RenderPart.Plan -> listOf(TimelineNode(base, NodeContent.Plan(part.entries), agent))
            is RenderPart.ProposedPlan -> listOf(TimelineNode(base, NodeContent.ProposedPlan(part.markdown, part.streaming), agent))
        }
    }

    /**
     * Split an assistant prose part into one node per Markdown block so a long message
     * virtualizes in the LazyColumn (and streaming re-renders only the tail block). The
     * first block keeps the base id `$owner#$index` (stable across the live→persisted
     * handoff); later blocks get `#b$k`. Persisted text is parse-memoized ([MarkdownCache]);
     * the growing live tail uses [LiveBlockParser], which re-parses only the tail (not the
     * whole segment) each flush. [RailStyle] marks the head (avatar) vs the markerless,
     * tight-spaced continuation rows.
     */
    private fun textBlockNodes(text: String, base: String, agent: AgentType, cached: Boolean): List<TimelineNode> {
        val blocks = if (cached) MarkdownCache.blocks(text) else LiveBlockParser.parse(base, text)
        if (blocks.isEmpty()) return emptyList()
        return blocks.mapIndexed { k, block ->
            val id = if (k == 0) base else "$base#b$k"
            val rail = when {
                blocks.size == 1 -> RailStyle.Standalone
                k == 0 -> RailStyle.Head
                k == blocks.lastIndex -> RailStyle.Tail
                else -> RailStyle.Middle
            }
            TimelineNode(id, NodeContent.AssistantBlock(block), agent, rail = rail)
        }
    }

    /** Collapse a run of consecutive assistant turns into one synthetic reply. */
    fun merge(group: List<MessageTurn>): MessageTurn {
        if (group.size <= 1) return group[0]
        val usages = group.mapNotNull { it.usage }
        val mergedUsage = if (usages.isEmpty()) null else TurnUsage(
            inputTokens = usages.sumOf { it.inputTokens },
            outputTokens = usages.sumOf { it.outputTokens },
            cacheCreationInputTokens = usages.sumOf { it.cacheCreationInputTokens },
            cacheReadInputTokens = usages.sumOf { it.cacheReadInputTokens },
        )
        val durations = group.mapNotNull { it.durationMs }
        return MessageTurn(
            id = group.first().id,
            role = TurnRole.ASSISTANT,
            blocks = group.flatMap { it.blocks },
            timestamp = group.first().timestamp,
            usage = mergedUsage,
            durationMs = if (durations.isEmpty()) null else durations.sum(),
            model = group.lastOrNull { !it.model.isNullOrEmpty() }?.model ?: group.first().model,
            completedAt = group.last().completedAt ?: group.last().timestamp,
        )
    }
}

/** Live tool call → the shared view-ready [ToolCallVM] (mirrors the persisted path). */
private fun LiveToolCallState.toVM(): ToolCallVM {
    val state = when {
        !isFinished -> ToolCallState.RUNNING
        isError -> ToolCallState.ERROR
        else -> ToolCallState.DONE
    }
    // [meta] too, as the persisted path passes it: Grok's plan-mode identity and codex's
    // search-action mark are read off it.
    return ToolCallVM.of(id, title, kind, state, rawInput, rawOutput, content, isError, meta)
}
