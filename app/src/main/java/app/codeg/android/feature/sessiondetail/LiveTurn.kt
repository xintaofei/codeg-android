package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.LiveContentBlockSnapshot
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.core.model.ToolCallStateSnapshot
import app.codeg.android.core.model.TurnRole
import app.codeg.android.feature.sessiondetail.rendering.PlanParse
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Immutable, render-ready snapshot of one in-flight tool call. */
data class LiveToolCallState(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val rawInput: String?,
    val rawOutput: String,
    val content: String?,
    /** The agent's opaque ACP metadata for this call (context compaction, Grok's
     *  `x.ai/tool` identity, …). Null for most tools and hosts. */
    val meta: JsonObject? = null,
) {
    val isFinished: Boolean
        get() = status in setOf("completed", "failed", "error", "cancelled", "canceled")
    val isError: Boolean get() = status == "failed" || status == "error"
}

/** One ordered piece of an in-flight assistant turn (immutable snapshot). */
sealed interface LiveSegment {
    val key: String

    data class Text(val id: String, val text: String) : LiveSegment {
        override val key: String get() = "t-$id"
    }
    data class Thinking(val id: String, val text: String) : LiveSegment {
        override val key: String get() = "k-$id"
    }
    data class Tool(val call: LiveToolCallState) : LiveSegment {
        override val key: String get() = "x-${call.id}"
    }

    /**
     * A note steered into the turn while it ran ([LiveTurnBuilder.appendSteer]): the point
     * where the agent took it in. Drawn as the user's message, splitting the reply around it.
     */
    data class Steer(val noteId: String, val text: String) : LiveSegment {
        override val key: String get() = "s-$noteId"
    }

    /** The agent's plan, where it last updated it ([LiveTurnBuilder.updatePlan]). */
    data class Plan(val entries: List<PlanEntry>) : LiveSegment {
        override val key: String get() = "p"
    }
}

/** Id prefix of the user turn a steered note becomes. See [promptCount]. */
internal const val STEER_TURN_PREFIX = "steer-"

/**
 * A turn's terminal failure: the line the error card states, plus the server's diagnostic
 * evidence behind it.
 *
 * One value rather than two fields because [details] is meaningless alone — it renders as a
 * disclosure hanging under the message, so a details-without-message state would draw an
 * unlabelled expander. Only errors codeg *inferred* (the `turn_failed_empty*` family) carry
 * evidence; every other failure leaves it null.
 */
/** [fromAgent]: the agent's own account of the failure (an AIR record's title), which a later
 *  verdict of codeg's doesn't replace. */
data class LiveTurnError(val message: String, val details: String? = null, val fromAgent: Boolean = false)

/** Immutable snapshot of the streaming assistant reply, emitted to the UI. */
data class LiveTurnState(
    val id: String,
    val segments: List<LiveSegment>,
    val isStreaming: Boolean,
    val stopReason: String?,
    val error: LiveTurnError?,
) {
    val isEmpty: Boolean get() = segments.isEmpty() && error == null
}

/**
 * Mutable, VM-owned builder for the assistant reply currently streaming. Built
 * optimistically on send, mutated as ACP events arrive, then [snapshot]'d to an
 * immutable [LiveTurnState] for rendering (the VM coalesces snapshots to ~50ms).
 * Faithful port of the iOS `LiveTurn` (coalescing moved to the VM emit cadence).
 */
class LiveTurnBuilder(val id: String = "live-${java.util.UUID.randomUUID()}") {

    private sealed class Seg {
        class Text(val id: String, val text: StringBuilder) : Seg()
        class Thinking(val id: String, val text: StringBuilder) : Seg()
        class Tool(val call: ToolBuilder) : Seg()
        class Steer(val noteId: String, val text: String, val at: Instant) : Seg()
        class Plan(val entries: List<PlanEntry>) : Seg()
    }

    private class ToolBuilder(
        val id: String,
        var title: String,
        var kind: String,
        var status: String,
        var rawInput: String?,
        val rawOutput: StringBuilder,
        var content: String?,
        var meta: JsonObject?,
    )

    private val segments = mutableListOf<Seg>()
    private val toolIndex = mutableMapOf<String, ToolBuilder>()
    private var seq = 0

    var isStreaming: Boolean = true
    var stopReason: String? = null
    var error: LiveTurnError? = null
    /** [error] is the client's own "Cancelled" marker for a reply cancelled before it said
     *  anything, not a failure the server reported — see [promotedTurns]. */
    var cancelledLocally: Boolean = false

    fun appendText(delta: String) {
        if (delta.isEmpty()) return
        (segments.lastOrNull() as? Seg.Text)?.let { it.text.append(delta); return }
        segments.add(Seg.Text("text-${seq++}", StringBuilder(delta)))
    }

    /**
     * Mark where a note was steered into this turn (codeg web 0.44 `STEERING_MESSAGE`): only a
     * note the server delivered straight into the running turn, which the agent takes in at
     * this point of its reply. Whatever streams next starts a new part after it. Once per
     * note; returns whether it was added.
     */
    fun appendSteer(noteId: String, text: String): Boolean {
        if (text.isBlank() || segments.any { it is Seg.Steer && it.noteId == noteId }) return false
        segments.add(Seg.Steer(noteId, text.trim(), Instant.now()))
        return true
    }

    fun appendThinking(delta: String) {
        if (delta.isEmpty()) return
        (segments.lastOrNull() as? Seg.Thinking)?.let { it.text.append(delta); return }
        segments.add(Seg.Thinking("think-${seq++}", StringBuilder(delta)))
    }

    /**
     * Fold a streamed text chunk into this turn. Returns false when the chunk was
     * DROPPED because it belongs to a subagent, so the caller can skip a pointless
     * UI emit.
     *
     * A chunk carrying [AcpEvent.ContentDelta.parentToolUseId] came from a Claude
     * subagent and belongs to the Agent tool call that launched it, not to the
     * assistant's own message — appending it here would splice two voices together
     * mid-sentence. It is live-only (reloaded history rebuilds the Agent capsule from
     * the tool result, never from these chunks), so dropping it makes the live turn
     * match the settled one. The rule lives here, not at the call sites, so every path
     * that feeds the turn — live events, replay, and [seedFrom] — obeys it.
     */
    fun append(chunk: AcpEvent.ContentDelta): Boolean {
        if (chunk.isSubagent) return false
        appendText(chunk.text)
        return true
    }

    /** Fold a streamed reasoning chunk into this turn; see [append]. */
    fun append(chunk: AcpEvent.Thinking): Boolean {
        if (chunk.isSubagent) return false
        appendThinking(chunk.text)
        return true
    }

    fun upsertToolCall(
        id: String,
        title: String,
        kind: String,
        status: String,
        rawInput: String?,
        rawOutput: String?,
        content: String?,
        meta: JsonObject? = null,
    ) {
        val existing = toolIndex[id]
        if (existing != null) {
            if (title.isNotEmpty()) existing.title = title
            if (kind.isNotEmpty()) existing.kind = kind
            if (status.isNotEmpty()) existing.status = status
            if (rawInput != null) existing.rawInput = rawInput
            if (rawOutput != null) { existing.rawOutput.setLength(0); existing.rawOutput.append(rawOutput) }
            if (content != null) existing.content = content
            // Replace-on-update, matching the server: a payload carrying no `meta` at
            // all preserves what the call was announced with (codex sends the
            // compaction tag once, on the opening `tool_call`).
            if (meta != null) existing.meta = meta
        } else {
            val call = ToolBuilder(
                id = id,
                title = title.ifEmpty { "Tool" },
                kind = kind,
                status = status.ifEmpty { "in_progress" },
                rawInput = rawInput,
                rawOutput = StringBuilder(rawOutput ?: ""),
                content = content,
                meta = meta,
            )
            toolIndex[id] = call
            segments.add(Seg.Tool(call))
        }
    }

    fun updateToolCall(
        id: String,
        title: String?,
        status: String?,
        rawInput: String?,
        rawOutput: String?,
        content: String?,
        append: Boolean,
        meta: JsonObject? = null,
    ) {
        val call = toolIndex[id]
        if (call == null) {
            upsertToolCall(id, title ?: "", "", status ?: "", rawInput, rawOutput, content, meta)
            return
        }
        if (!title.isNullOrEmpty()) call.title = title
        if (!status.isNullOrEmpty()) call.status = status
        // Some hosts deliver a tool's arguments only on a later update (iOS parity), so adopt a
        // non-empty raw_input even when the initial event carried none — but never let an empty
        // update erase arguments we already have.
        if (!rawInput.isNullOrEmpty()) call.rawInput = rawInput
        if (content != null) call.content = content
        if (rawOutput != null) {
            if (append) call.rawOutput.append(rawOutput) else { call.rawOutput.setLength(0); call.rawOutput.append(rawOutput) }
        }
        if (meta != null) call.meta = meta
    }

    /**
     * The agent's plan, as of now. It stands where it was last updated, as the web's
     * `PLAN_UPDATE` keeps it: the old one is taken out and the new one goes at the end, so it
     * moves to the point in the reply where the agent revised it. An empty one removes it.
     */
    fun updatePlan(entries: List<PlanEntry>) {
        val at = segments.indexOfFirst { it is Seg.Plan }
        val current = (segments.getOrNull(at) as? Seg.Plan)?.entries
        if (at == segments.lastIndex && current == entries) return
        if (at >= 0) {
            segments.removeAt(at)
            // What streamed either side of it was one run of prose (or of thought) the plan
            // cut in two — appends join a run otherwise — so it is joined again, or a sentence
            // split mid-word would draw as two paragraphs.
            val before = segments.getOrNull(at - 1)
            val after = segments.getOrNull(at)
            when {
                before is Seg.Text && after is Seg.Text -> {
                    before.text.append(after.text)
                    segments.removeAt(at)
                }
                before is Seg.Thinking && after is Seg.Thinking -> {
                    before.text.append(after.text)
                    segments.removeAt(at)
                }
            }
        }
        if (entries.isNotEmpty()) segments.add(Seg.Plan(entries))
    }

    /**
     * Seed this (empty) builder from a reattach snapshot: rebuild the in-flight
     * assistant message's ordered segments + plan from `live_message`, resolving
     * each `tool_call_ref` against `active_tool_calls`. Mirrors the iOS
     * `buildLiveTurn(from:)`. A tool present in `active_tool_calls` but not
     * referenced in the message stream is still appended so it isn't lost.
     */
    fun seedFrom(snapshot: LiveSessionSnapshot) {
        val byId = snapshot.activeToolCalls.associateBy { it.id }
        for (block in snapshot.liveMessage?.content.orEmpty()) {
            when (block) {
                // Subagent-attributed blocks are the Agent tool call's, not the main
                // thread's — same rule the live event path applies, so a mid-turn
                // reattach rebuilds exactly the turn a client that never dropped saw.
                is LiveContentBlockSnapshot.Text -> if (!block.isSubagent) appendText(block.text)
                is LiveContentBlockSnapshot.Thinking -> if (!block.isSubagent) appendThinking(block.text)
                is LiveContentBlockSnapshot.ToolCallRef -> byId[block.toolCallId]?.let(::seedTool)
                is LiveContentBlockSnapshot.Plan -> updatePlan(block.entries)
                LiveContentBlockSnapshot.Unknown -> Unit
            }
        }
        for (call in snapshot.activeToolCalls) if (call.id !in toolIndex) seedTool(call)
    }

    /**
     * Rebuild this turn from [snapshot], replacing what it held: the snapshot answers a
     * re-attach whose gap was too wide to replay, so the events in between are lost and only
     * the server's copy is whole.
     *
     * One thing is kept: the server trims older FINISHED calls' input/output out of a large
     * snapshot (a 2 MiB budget), so a call it sent bare keeps the payload this turn already
     * had for it. The builder's identity is kept too ([id], [isStreaming]); a parked
     * plan follow-up is bound to it.
     */
    fun reseedFrom(snapshot: LiveSessionSnapshot) {
        val previous = toolIndex.toMap()
        segments.clear()
        toolIndex.clear()
        seedFrom(snapshot)
        for ((id, call) in toolIndex) {
            val held = previous[id] ?: continue
            if (call.rawInput.isNullOrEmpty() && !held.rawInput.isNullOrEmpty()) call.rawInput = held.rawInput
            if (call.rawOutput.isEmpty() && held.rawOutput.isNotEmpty()) call.rawOutput.append(held.rawOutput)
            if (call.content.isNullOrEmpty() && !held.content.isNullOrEmpty()) call.content = held.content
        }
    }

    private fun seedTool(call: ToolCallStateSnapshot) {
        upsertToolCall(
            id = call.id,
            title = call.label,
            kind = call.kind,
            status = call.normalizedStatus,
            rawInput = call.inputPreview,
            rawOutput = call.outputText.ifEmpty { null },
            content = call.content,
            meta = call.meta,
        )
    }

    val isEmpty: Boolean get() = segments.isEmpty() && error == null

    /** Call [id] as this turn holds it, or null when the turn hasn't seen it. */
    fun toolCall(id: String): LiveToolCallState? = toolIndex[id]?.let {
        LiveToolCallState(it.id, it.title, it.kind, it.status, it.rawInput, it.rawOutput.toString(), it.content, it.meta)
    }

    /**
     * A permission request is about call [id] (web `PERMISSION_REQUEST`): the arguments it
     * names fill a call that hasn't sent its own yet, and a call the request comes ahead of is
     * announced here, pending, so the step the user is asked about stands on the rail. Returns
     * whether anything changed.
     */
    fun noteAwaitedCall(id: String, title: String, kind: String, rawInput: String?): Boolean {
        val existing = toolIndex[id]
        if (existing != null) {
            if (!existing.rawInput.isNullOrEmpty() || rawInput.isNullOrEmpty()) return false
            existing.rawInput = rawInput
            return true
        }
        upsertToolCall(id, title, kind, "pending", rawInput, null, null)
        return true
    }

    /** The active (unfinished) tool's title, for the compose status line. */
    fun activeToolTitle(): String? {
        for (s in segments.asReversed()) {
            if (s is Seg.Tool && !LiveToolCallState(s.call.id, s.call.title, s.call.kind, s.call.status, null, "", null).isFinished) {
                return s.call.title
            }
        }
        return null
    }

    fun snapshot(): LiveTurnState = LiveTurnState(
        id = id,
        segments = segments.map { seg ->
            when (seg) {
                is Seg.Text -> LiveSegment.Text(seg.id, seg.text.toString())
                is Seg.Thinking -> LiveSegment.Thinking(seg.id, seg.text.toString())
                is Seg.Steer -> LiveSegment.Steer(seg.noteId, seg.text)
                is Seg.Plan -> LiveSegment.Plan(seg.entries)
                is Seg.Tool -> LiveSegment.Tool(
                    LiveToolCallState(
                        id = seg.call.id,
                        title = seg.call.title,
                        kind = seg.call.kind,
                        status = seg.call.status,
                        rawInput = seg.call.rawInput,
                        rawOutput = seg.call.rawOutput.toString(),
                        content = seg.call.content,
                        meta = seg.call.meta,
                    ),
                )
            }
        },
        isStreaming = isStreaming,
        stopReason = stopReason,
        error = error,
    )

    /**
     * Fold this (finished) turn into the transcript turns it stands for: one assistant turn,
     * or — when notes were steered into it — the reply split at each, with the note as the
     * user turn between, the way the server persists a steer. Parts with nothing to render
     * are dropped. The first part keeps [id], so a reply never steered keeps its identity.
     */
    fun snapshotAsMessageTurns(): List<MessageTurn> {
        val out = mutableListOf<MessageTurn>()
        var blocks = mutableListOf<ContentBlock>()
        var part = 0
        fun closePart() {
            if (blocks.isNotEmpty()) {
                out.add(MessageTurn(if (part == 0) id else "$id-$part", TurnRole.ASSISTANT, blocks, Instant.now()))
            }
            part += 1
            blocks = mutableListOf()
        }
        for (seg in segments) {
            when (seg) {
                is Seg.Text -> if (seg.text.toString().isNotBlank()) blocks.add(ContentBlock.Text(seg.text.toString()))
                is Seg.Thinking -> if (seg.text.toString().isNotBlank()) blocks.add(ContentBlock.Thinking(seg.text.toString()))
                is Seg.Tool -> {
                    blocks.add(ContentBlock.ToolUse(seg.call.id, seg.call.title, seg.call.rawInput, seg.call.meta, liveKind = seg.call.kind))
                    val output = seg.call.rawOutput.toString().ifEmpty { seg.call.content }
                    blocks.add(ContentBlock.ToolResult(seg.call.id, output, LiveToolCallState(seg.call.id, "", "", seg.call.status, null, "", null).isError))
                }
                is Seg.Steer -> {
                    closePart()
                    out.add(MessageTurn("$STEER_TURN_PREFIX${seg.noteId}", TurnRole.USER, listOf(ContentBlock.Text(seg.text)), seg.at))
                }
                // Kept where it stood, as the call the transcript records a plan by, which
                // draws as the same plan card ([PlanParse]).
                is Seg.Plan -> blocks.add(ContentBlock.ToolUse("$id-plan", PlanParse.HANDOFF_TOOL_NAME, PlanParse.handoffInput(seg.entries)))
            }
        }
        closePart()
        return out
    }

    /** [snapshotAsMessageTurns] for a reply never steered: its one assistant turn. */
    fun snapshotAsMessageTurn(): MessageTurn =
        snapshotAsMessageTurns().firstOrNull { it.role == TurnRole.ASSISTANT }
            ?: MessageTurn(id = id, role = TurnRole.ASSISTANT, blocks = emptyList(), timestamp = Instant.now())
}
