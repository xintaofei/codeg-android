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
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.rendering.ContextCompaction
import app.codeg.android.feature.sessiondetail.LiveToolCallState
import app.codeg.android.feature.sessiondetail.LiveTurnState
import app.codeg.android.feature.sessiondetail.rendering.MessageRender
import app.codeg.android.feature.sessiondetail.rendering.RenderPart
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import app.codeg.android.feature.sessiondetail.rendering.ToolCallVM

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
    /** A context-compaction boundary — rendered as a chrome-less divider, not a card.
     *  Token counts are Grok-only (codex sends none). */
    data class Compaction(val before: Int?, val after: Int?, val running: Boolean) : NodeContent
    data class Footer(val turn: MessageTurn, val questionId: String?) : NodeContent
    data class Plan(val entries: List<PlanEntry>) : NodeContent
    data object Thinking : NodeContent
    data class Error(val message: String) : NodeContent
    data class Unsupported(val type: String) : NodeContent
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
            is NodeContent.AssistantBlock -> MarkerKind.Assistant(agent)
            is NodeContent.Reasoning -> MarkerKind.Reasoning
            is NodeContent.Tool -> MarkerKind.Tool(c.vm.bucket, c.vm.state)
            is NodeContent.ToolGroup -> MarkerKind.ToolGroup(c.items.any { it.state == ToolCallState.ERROR }, c.streaming)
            is NodeContent.Image -> MarkerKind.Image
            is NodeContent.Compaction -> MarkerKind.Compaction
            is NodeContent.Footer -> MarkerKind.Footer
            is NodeContent.Plan -> MarkerKind.Plan
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
            is NodeContent.Thinking -> "thinking"
            is NodeContent.Error -> "error"
            is NodeContent.Unsupported -> "unsupported"
        }
}

/** A turn's text blocks, joined — the renderable system-note text (and the empty-skip check). */
internal fun systemText(turn: MessageTurn): String =
    turn.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }.joinToString("\n").trim()

/** Drop the trailing run of assistant turns (those after the last non-assistant turn).
 *  On reattach the live turn rebuilt from the snapshot owns the in-flight reply, so the
 *  partial copy the server has persisted at the tail must be hidden to avoid doubling it. */
internal fun List<MessageTurn>.dropTrailingAssistantRun(): List<MessageTurn> {
    var end = size
    while (end > 0 && this[end - 1].role == TurnRole.ASSISTANT) end--
    return if (end == size) this else subList(0, end)
}

/**
 * Flattens a conversation into an ordered `[TimelineNode]`. User/system turns map
 * 1:1; runs of consecutive assistant turns merge into one reply (codeg persists a
 * logical reply as several turns) that expands into part nodes + a single footer.
 * Faithful port of the iOS `TranscriptTimeline`.
 */
object TranscriptTimeline {

    fun build(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        live: LiveTurnState?,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
    ): List<TimelineNode> =
        withLive(buildPersisted(turns, pending, agent, hideTrailingAssistantReply), live, agent)

    /**
     * The persisted + optimistic-pending nodes, with the rail NOT yet terminated.
     * Memoize this (it re-merges/adapts every turn) separately from [withLive] so the
     * ~50ms live stream only re-runs the cheap live append, not the whole transcript.
     *
     * [hideTrailingAssistantReply] drops the trailing run of persisted assistant
     * turns — used on reattach, where the live turn rebuilt from the snapshot is the
     * authoritative in-flight reply while the agent concurrently persists a partial
     * copy into [turns]; showing both would double the reply. Port of the iOS
     * `liveOwnsInFlightReply` de-dup.
     */
    fun buildPersisted(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
        chunkStarts: Set<String> = emptySet(),
    ): List<TimelineNode> {
        val source = if (hideTrailingAssistantReply) turns.dropTrailingAssistantRun() else turns
        val nodes = mutableListOf<TimelineNode>()
        var lastUserId: String? = null
        var i = 0
        while (i < source.size) {
            val turn = source[i]
            when (turn.role) {
                TurnRole.USER -> {
                    lastUserId = turn.id
                    nodes.add(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true))
                    i++
                }
                TurnRole.SYSTEM -> {
                    if (systemText(turn).isNotEmpty()) {
                        nodes.add(TimelineNode(turn.id, NodeContent.System(turn), agent, startsGroup = true))
                    }
                    i++
                }
                TurnRole.ASSISTANT -> {
                    var j = i + 1
                    while (j < source.size && source[j].role == TurnRole.ASSISTANT && source[j].id !in chunkStarts) j++
                    val merged = merge(source.subList(i, j))
                    nodes.addAll(assistantNodes(merged, lastUserId, agent))
                    i = j
                }
            }
        }
        for (turn in pending) {
            nodes.add(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true))
        }
        return nodes
    }

    /** Append the in-flight reply to the persisted nodes and terminate the rail's endpoints. */
    fun withLive(persisted: List<TimelineNode>, live: LiveTurnState?, agent: AgentType): List<TimelineNode> {
        val nodes = persisted.toMutableList()
        if (live != null) nodes.addAll(liveNodes(live, agent))
        if (nodes.isNotEmpty()) {
            nodes[0] = nodes[0].copy(connectTop = false)
            nodes[nodes.lastIndex] = nodes[nodes.lastIndex].copy(connectBottom = false)
        }
        return nodes
    }

    /** A merged reply → ordered part nodes + a footer (always emitted, even tool-only).
     *  Text parts split into per-block nodes; persisted text is parse-memoized. */
    private fun assistantNodes(merged: MessageTurn, questionId: String?, agent: AgentType): List<TimelineNode> {
        val out = MessageRender.adaptTurn(merged)
            .flatMapIndexed { idx, part -> nodesFor(part, merged.id, idx, agent, cached = true) }
            .toMutableList()
        out.add(TimelineNode("${merged.id}#footer", NodeContent.Footer(merged, questionId), agent))
        return out
    }

    /** The in-flight reply → plan? + thinking? + grouped parts + error?. */
    private fun liveNodes(live: LiveTurnState, agent: AgentType): List<TimelineNode> {
        val out = mutableListOf<TimelineNode>()
        if (live.livePlan.isNotEmpty()) {
            out.add(TimelineNode("${live.id}#plan", NodeContent.Plan(live.livePlan), agent))
        }
        if (live.isEmpty && live.isStreaming) {
            out.add(TimelineNode("${live.id}#thinking", NodeContent.Thinking, agent))
        }
        // Same grouping as the persisted path: consecutive non-task tools collapse.
        // Live text is parsed uncached (each flush is a new key → cache churn).
        val parts = MessageRender.groupConsecutiveTools(liveParts(live))
        val partNodes = parts.flatMapIndexed { idx, part -> nodesFor(part, live.id, idx, agent, cached = false) }.toMutableList()
        // Caret only when the *final* block is prose — a trailing tool/group shows its
        // own pulsing marker instead (matches iOS `adaptLive` streaming-on-last-segment).
        if (live.isStreaming && partNodes.isNotEmpty()) {
            val lastIdx = partNodes.lastIndex
            when (val c = partNodes[lastIdx].content) {
                is NodeContent.AssistantBlock -> partNodes[lastIdx] = partNodes[lastIdx].copy(content = c.copy(streaming = true))
                is NodeContent.Reasoning -> partNodes[lastIdx] = partNodes[lastIdx].copy(content = c.copy(streaming = true))
                else -> {}
            }
        }
        out.addAll(partNodes)
        live.errorMessage?.let { out.add(TimelineNode("${live.id}#error", NodeContent.Error(it), agent)) }
        return out
    }

    private fun liveParts(live: LiveTurnState): List<RenderPart> {
        val parts = mutableListOf<RenderPart>()
        for (seg in live.segments) {
            when (seg) {
                is LiveSegment.Text -> if (seg.text.isNotBlank()) parts.add(RenderPart.Text(seg.text))
                is LiveSegment.Thinking -> if (seg.text.isNotBlank()) parts.add(RenderPart.Reasoning(seg.text))
                // Same boundary-marker treatment as the persisted path: codex emits the
                // `Context compacting` → `Context compacted` pair on one id, so the
                // divider flips from running to settled in place.
                is LiveSegment.Tool -> if (ContextCompaction.matches(seg.call.meta)) {
                    val (before, after) = ContextCompaction.tokens(seg.call.meta)
                    parts.add(RenderPart.Compaction(before, after, !seg.call.isFinished))
                } else {
                    parts.add(RenderPart.Tool(seg.call.toVM()))
                }
            }
        }
        return parts
    }

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
            is RenderPart.Tool -> listOf(TimelineNode("tool-${part.vm.id}", NodeContent.Tool(part.vm), agent))
            is RenderPart.ToolGroup -> {
                val gid = part.items.firstOrNull()?.let { "toolgroup-${it.id}" } ?: base
                listOf(TimelineNode(gid, NodeContent.ToolGroup(part.items, part.streaming), agent))
            }
            is RenderPart.Image -> listOf(TimelineNode(base, NodeContent.Image(part.image, part.caption), agent))
            is RenderPart.Compaction ->
                listOf(TimelineNode(base, NodeContent.Compaction(part.before, part.after, part.running), agent))
            is RenderPart.Unknown -> listOf(TimelineNode(base, NodeContent.Unsupported(part.type), agent))
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
    return ToolCallVM.of(id, title, kind, state, rawInput, rawOutput, content, isError)
}
