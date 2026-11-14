package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.designsystem.diff.DiffFile
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import kotlinx.serialization.json.JsonObject

/** The visual lifecycle of a tool call. */
enum class ToolCallState { INPUT_STREAMING, RUNNING, DONE, ERROR }

/**
 * A unified, view-ready model for one tool call. Both the persisted path
 * (`tool_use` + `tool_result` paired) and the live path adapt into this, so the
 * card UI is shared. Derived fields are computed once via [of]. Port of iOS
 * `ToolCallVM`.
 */
data class ToolCallVM(
    val id: String,
    val rawName: String,
    val kind: String,
    val state: ToolCallState,
    val input: String?,
    val output: String?,
    val content: String?,
    val isError: Boolean,
    val displayTitle: String,
    val bucket: ToolKindBucket,
    val diffFiles: List<DiffFile>?,
    /** The agent's opaque ACP metadata for this call (Grok's `x.ai/tool` identity,
     *  the context-compaction tag, …). Null for most tools and hosts. */
    val meta: JsonObject? = null,
    /** How the call's permission request was answered, as a detail load stamped it on [meta]
     *  ([ToolApprovals.fromMeta]). The live connection's own record outranks it ([liveToolStep]). */
    val approval: ToolApproval? = null,
) {
    val trimmedOutput: String get() = (output ?: "").trim()
    val hasOutput: Boolean get() = trimmedOutput.isNotEmpty()
    val isCommand: Boolean get() = bucket == ToolKindBucket.EXECUTE

    /**
     * A plan-*mode* transition (Claude's `EnterPlanMode`/`ExitPlanMode`, Grok's
     * `enter_plan_mode`/`exit_plan_mode`, Cline's `switch_mode`). A mode signal, not a
     * work tool, so it renders standalone instead of folding into a group.
     */
    val isPlanMode: Boolean get() = ToolDerive.isPlanModeName(rawName)

    companion object {
        fun of(
            id: String,
            rawName: String,
            kind: String,
            state: ToolCallState,
            input: String?,
            output: String?,
            content: String?,
            isError: Boolean,
            meta: JsonObject? = null,
        ): ToolCallVM {
            // Grok's plan-mode tools carry their authoritative identity in
            // `meta["x.ai/tool"].kind`, while the `title` we're handed as [rawName]
            // MUTATES across the lifecycle (`enter_plan_mode` -> "Plan: Enter" -> "Plan
            // mode entered"). Resolve them to the canonical name up front so the live
            // stream lands on the same identity the persisted path reads — and so the
            // card's title stops changing mid-stream.
            val planName = ToolDerive.grokPlanModeName(meta)
            // A codex-acp command action announces no tool name and no input at all,
            // only a human title, so both have to be read back out of it.
            val action = if (planName == null) CodexCommandAction.resolve(rawName) else null
            val resolvedName = planName ?: action?.name ?: rawName
            // The NAME is recovered from the title whichever way this falls — as the
            // web's `normalizeToolName` does — because a tool genuinely called
            // "Search for 'x'" does not exist, and without the rename the card loses
            // its search icon and its tool-group tally. Only the INPUT is conditional:
            // arguments the call actually sent are never replaced by a recovered guess.
            val effectiveInput = when {
                sentArguments(input) -> input
                // A bare "Search" / "List files" recovers an identity but no
                // fields. Keeping the `{}` the host sent in its place would give
                // the card a body to expand that has nothing in it.
                action != null -> action.input.takeIf { it.isNotEmpty() }?.toString()
                else -> input
            }
            val parsed = ToolDerive.parseJson(effectiveInput)
            // A recovered command action outranks the ACP `kind` hint, which codex
            // sends as "read" for a directory listing — kind-first bucketing would
            // file it as a file read and route the envelope through the Read-file
            // body. Every other call keeps the hint's precedence.
            val bucket = ToolDerive.bucket(resolvedName, if (action != null) "" else kind)
            // A search that found nothing, which codex reports as failed, settles as a success
            // (web `isGrepNoMatchResult`) — for its marker, its group and its reply's header
            // alike. [isError] stays as sent: the card body still reads it to say "No matches".
            val settled = if (
                state == ToolCallState.ERROR &&
                GrepNoMatch.settles(resolvedName, bucket, effectiveInput, output?.takeIf { it.isNotBlank() } ?: content, meta)
            ) {
                ToolCallState.DONE
            } else {
                state
            }
            return ToolCallVM(
                id = id,
                rawName = resolvedName,
                kind = kind,
                state = settled,
                input = effectiveInput,
                output = output,
                content = content,
                isError = isError,
                displayTitle = ToolDerive.title(resolvedName, parsed),
                bucket = bucket,
                diffFiles = ToolDerive.diff(bucket, effectiveInput, output, content, parsed),
                meta = meta,
                approval = ToolApprovals.fromMeta(meta),
            )
        }
    }
}

/**
 * Whether a call carried arguments of its own. Not just "is [input] non-blank":
 * hosts encode "no arguments" as `{}` as readily as they omit the field, and
 * reading that as real input strands a codex command action with no recovered
 * query — the wrench icon and the raw envelope the recovery exists to prevent.
 * Text that is not JSON at all counts as real.
 */
internal fun sentArguments(input: String?): Boolean {
    if (input.isNullOrBlank()) return false
    return ToolDerive.effectiveArgs(ToolDerive.parseJson(input))?.isEmpty() != true
}

/** One ordered piece of a rendered assistant turn (static subset; live variants added in M4). */
sealed interface RenderPart {
    data class Text(val text: String) : RenderPart
    data class Reasoning(val text: String) : RenderPart
    data class Tool(val vm: ToolCallVM) : RenderPart
    data class ToolGroup(val items: List<ToolCallVM>, val streaming: Boolean) : RenderPart
    data class Image(val image: ImageData, val caption: String?) : RenderPart

    /** A plan checklist: the live plan update, or a persisted plan-like call ([PlanParse]). */
    data class Plan(val entries: List<PlanEntry>) : RenderPart

    /** codex's Plan-mode `<proposed_plan>` document ([ProposedPlan]); [streaming] while unclosed. */
    data class ProposedPlan(val markdown: String, val streaming: Boolean) : RenderPart
    /**
     * A context compaction (`meta.contextCompaction`) — a conversation boundary
     * marker, not a tool call. Rendered as a chrome-less centred divider; the token
     * counts are Grok-only (codex sends none).
     */
    data class Compaction(
        val before: Int?,
        val after: Int?,
        val running: Boolean,
        val failed: Boolean = false,
        val durationMs: Int? = null,
        /** The retained summary, when the server claims the call's output is one. */
        val summary: String? = null,
        /** The compaction call's id: the event's own name where the live and history copies agree on one. */
        val callId: String? = null,
        /** [ContextCompaction.eventKey]: the event's identity by its figures. */
        val eventKey: String? = null,
    ) : RenderPart
    /** A turn failure ([ContentBlock.TurnError]). The server only emits one inside a system
     *  turn, which the timeline handles itself; this arm keeps a stray one visible. */
    data class Error(val message: String) : RenderPart
    data class Unknown(val type: String) : RenderPart
}

/** Adapts persisted [MessageTurn]s into `[RenderPart]`. Port of iOS `MessageRender`. */
object MessageRender {

    /**
     * [runningToolIds] are the calls the server affirms are still running (the in-flight
     * round's, see `inFlightToolCallIds`). Any other `tool_use` without a result is settled
     * history: the round it belonged to has ended.
     */
    fun adaptTurn(turn: MessageTurn, runningToolIds: Set<String> = emptySet()): List<RenderPart> =
        groupConsecutiveTools(adaptParts(turn, runningToolIds))

    /** [adaptTurn] before its calls are grouped: one part per call, for [summarizeActivity]. */
    fun adaptParts(turn: MessageTurn, runningToolIds: Set<String> = emptySet()): List<RenderPart> {
        val blocks = turn.blocks
        val consumed = mutableSetOf<Int>()
        val parts = mutableListOf<RenderPart>()

        blocks.forEachIndexed { idx, block ->
            if (idx in consumed) return@forEachIndexed
            when (block) {
                is ContentBlock.Text ->
                    if (block.text.isNotBlank()) {
                        ProposedPlan.expand(block.text, streaming = false)?.let(parts::addAll) ?: parts.add(RenderPart.Text(block.text))
                    }
                is ContentBlock.Thinking ->
                    if (block.text.isNotBlank()) parts.add(RenderPart.Reasoning(block.text))
                is ContentBlock.Image ->
                    parts.add(RenderPart.Image(block.image, null))
                is ContentBlock.ImageGeneration -> {
                    if (block.image != null) parts.add(RenderPart.Image(block.image, block.revisedPrompt))
                    else if (!block.revisedPrompt.isNullOrEmpty()) parts.add(RenderPart.Text(block.revisedPrompt))
                }
                is ContentBlock.ToolUse -> {
                    val resultIdx = findResult(blocks, idx, block.id, consumed)
                    // A plan-like call that carries a checklist draws as the plan the live
                    // stream showed, its result ("Todos modified") consumed with it. Only one
                    // that yields entries: anything else stays a tool call.
                    val planEntries = if (PlanParse.isPlanLikeToolName(block.name)) PlanParse.parseTodos(block.inputPreview) else emptyList()
                    if (planEntries.isNotEmpty()) {
                        resultIdx?.let(consumed::add)
                        parts.add(RenderPart.Plan(planEntries))
                        return@forEachIndexed
                    }
                    var output: String? = null
                    var isErr = false
                    if (resultIdx != null) {
                        (blocks[resultIdx] as? ContentBlock.ToolResult)?.let {
                            output = it.outputPreview; isErr = it.isError; consumed.add(resultIdx)
                        }
                    }
                    val state = when {
                        resultIdx == null ->
                            if (block.id != null && block.id in runningToolIds) ToolCallState.RUNNING
                            else ToolCallState.DONE
                        isErr -> ToolCallState.ERROR
                        else -> ToolCallState.DONE
                    }
                    // A compaction is a boundary marker, not a call: it renders as a
                    // divider between turns, so it never becomes a ToolCallVM (and so
                    // can't be swept into a tool group).
                    if (ContextCompaction.matches(block.meta)) {
                        parts.add(ContextCompaction.part(block.id, block.meta, state == ToolCallState.RUNNING, isErr, output))
                        return@forEachIndexed
                    }
                    parts.add(
                        RenderPart.Tool(
                            // Qualify the synthetic fallback with the turn id: a bare positional
                            // "tool-$idx" would collide across replies once it becomes a node key.
                            ToolCallVM.of(
                                block.id ?: "${turn.id}-tool-$idx", block.name, "", state,
                                block.inputPreview, output, null, isErr, block.meta,
                            ),
                        ),
                    )
                }
                is ContentBlock.ToolResult -> {
                    parts.add(
                        RenderPart.Tool(
                            ToolCallVM.of(
                                block.id ?: "${turn.id}-result-$idx", "result", "",
                                if (block.isError) ToolCallState.ERROR else ToolCallState.DONE,
                                null, block.outputPreview, null, block.isError,
                            ),
                        ),
                    )
                }
                is ContentBlock.TurnError ->
                    if (block.message.isNotBlank()) parts.add(RenderPart.Error(block.message.trim()))
                is ContentBlock.Unknown -> parts.add(RenderPart.Unknown(block.type))
            }
        }
        return parts
    }

    /**
     * Fold a reply's parts into the rows the timeline draws (web `groupActivitySteps`).
     *
     * Consecutive calls of one [StepFamily] — explorations, or commands — become one
     * [RenderPart.ToolGroup]; a run needs two members, so a lone call stays a plain row.
     * Anything else ends a run: prose, thoughts, edits, MCP and other tools, sub-agents,
     * plan-mode transitions. A failed call does NOT end one; it stays inside, and the group
     * shows the failure. A call interrupted before it carried anything is dropped
     * ([isBlankInterruption]).
     */
    fun groupConsecutiveTools(parts: List<RenderPart>): List<RenderPart> {
        val out = mutableListOf<RenderPart>()
        var run = mutableListOf<ToolCallVM>()
        var family: StepFamily? = null

        fun flush() {
            if (run.size >= 2) {
                val streaming = run.any { it.state == ToolCallState.RUNNING || it.state == ToolCallState.INPUT_STREAMING }
                out.add(RenderPart.ToolGroup(run.toList(), streaming))
            } else {
                run.firstOrNull()?.let { out.add(RenderPart.Tool(it)) }
            }
            run = mutableListOf()
            family = null
        }

        for (part in parts) {
            if (part is RenderPart.Tool && isBlankInterruption(part.vm)) continue
            val joins = (part as? RenderPart.Tool)?.vm?.let(::stepFamily)
            if (part is RenderPart.Tool && joins != null) {
                if (joins != family) flush()
                family = joins
                run.add(part.vm)
            } else {
                flush()
                out.add(part)
            }
        }
        flush()
        return out
    }

    private fun findResult(blocks: List<ContentBlock>, after: Int, toolID: String?, consumed: Set<Int>): Int? {
        if (toolID != null) {
            for (j in (after + 1) until blocks.size) {
                if (j in consumed) continue
                val b = blocks[j]
                if (b is ContentBlock.ToolResult && b.id == toolID) return j
            }
        }
        for (j in (after + 1) until blocks.size) {
            if (j in consumed) continue
            val b = blocks[j]
            if (b is ContentBlock.ToolUse) break
            if (b is ContentBlock.ToolResult) return j
        }
        return null
    }
}
