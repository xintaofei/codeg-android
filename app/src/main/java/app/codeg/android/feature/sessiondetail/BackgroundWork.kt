package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AsyncTaskDelta
import app.codeg.android.core.model.AsyncTaskRecord
import app.codeg.android.core.model.BackgroundOutstandingTask
import app.codeg.android.core.model.BackgroundSettledInfo
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.DelegationBinding
import app.codeg.android.core.model.MessageTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The AIR async-task table (codeg web 0.44 `lib/async-tasks.ts`), merged the same way the
 * server's `SessionState::apply_event` merges it, so a client that saw every delta and one that
 * seeded from a snapshot agree.
 */
object AsyncTasks {

    private val TERMINAL = setOf("completed", "failed", "stopped")

    /** A task the adapter announces Claude Code started a turn of its own with (0.97.0). */
    const val AUTONOMOUS_TURN = "turn"

    fun isTerminal(task: AsyncTaskRecord): Boolean = task.state in TERMINAL

    fun live(tasks: List<AsyncTaskRecord>): List<AsyncTaskRecord> = tasks.filterNot(::isTerminal)

    private fun recordOf(d: AsyncTaskDelta) = AsyncTaskRecord(
        taskId = d.taskId,
        name = d.name ?: "Background task",
        taskType = d.taskType ?: "task",
        description = d.description.orEmpty(),
        showInTranscript = d.showInTranscript ?: true,
        canStop = d.canStop ?: false,
        state = d.state ?: "running",
        summary = d.summary,
        lastToolName = d.lastToolName,
        usage = d.usage,
        outputFilePath = d.outputFilePath,
        toolCallId = d.toolCallId,
        phase = d.phase,
        currentAgent = d.currentAgent,
    )

    private fun AsyncTaskRecord.revisedBy(d: AsyncTaskDelta) = copy(
        name = d.name ?: name,
        taskType = d.taskType ?: taskType,
        description = d.description ?: description,
        showInTranscript = d.showInTranscript ?: showInTranscript,
        canStop = d.canStop ?: canStop,
        state = d.state ?: state,
        summary = d.summary ?: summary,
        lastToolName = d.lastToolName ?: lastToolName,
        usage = d.usage ?: usage,
        outputFilePath = d.outputFilePath ?: outputFilePath,
        toolCallId = d.toolCallId ?: toolCallId,
        phase = d.phase ?: phase,
        currentAgent = d.currentAgent ?: currentAgent,
    )

    /** One delta folded in. A task never announced changes nothing; [current] then comes back. */
    fun upsert(current: List<AsyncTaskRecord>, delta: AsyncTaskDelta): List<AsyncTaskRecord> {
        if (delta.taskId.isEmpty()) return current
        val index = current.indexOfFirst { it.taskId == delta.taskId }
        if (index < 0) return if (delta.spawned) current + recordOf(delta) else current
        val revised = current[index].revisedBy(delta)
        if (revised == current[index]) return current
        return current.toMutableList().also { it[index] = revised }
    }

    /**
     * A snapshot's merged table, for a snapshot newer than everything applied here: whole rows,
     * replacing by id and appending the unknown ones.
     */
    fun merge(current: List<AsyncTaskRecord>, incoming: List<AsyncTaskRecord>): List<AsyncTaskRecord> {
        var next: MutableList<AsyncTaskRecord>? = null
        for (record in incoming) {
            if (record.taskId.isEmpty()) continue
            val target = next ?: current
            val index = target.indexOfFirst { it.taskId == record.taskId }
            if (index >= 0 && target[index] == record) continue
            val into = next ?: current.toMutableList().also { next = it }
            if (index >= 0) into[index] = record else into.add(record)
        }
        return next ?: current
    }

    /**
     * A snapshot this client has already overtaken: its rows may be older than deltas applied
     * here (and the terminal one won't be replayed), so it may only ADD tasks never seen —
     * still the only way an attach mid-episode learns of work already running.
     */
    fun adoptUnknown(current: List<AsyncTaskRecord>, incoming: List<AsyncTaskRecord>): List<AsyncTaskRecord> {
        val known = current.mapTo(HashSet()) { it.taskId }
        val fresh = incoming.filter { it.taskId.isNotEmpty() && known.add(it.taskId) }
        return if (fresh.isEmpty()) current else current + fresh
    }

    /** What started an autonomous turn, by the adapter's fixed sentences (web `autonomousTurnCause`). */
    sealed interface TurnCause {
        data object Unknown : TurnCause
        data class ScheduledWakeup(val at: Instant?) : TurnCause
        data object ScheduledTask : TurnCause
        data class Text(val text: String) : TurnCause
    }

    private val WAKEUP_AT = Regex("^A scheduled wakeup fired \\(set for (.+)\\)\\.$")

    fun turnCause(description: String): TurnCause {
        val text = description.trim()
        return when {
            text.isEmpty() || text == "Claude Code started a turn without a prompt." -> TurnCause.Unknown
            text == "A scheduled wakeup fired." -> TurnCause.ScheduledWakeup(null)
            text == "A scheduled task fired." -> TurnCause.ScheduledTask
            else -> WAKEUP_AT.find(text)?.let { m ->
                TurnCause.ScheduledWakeup(runCatching { Instant.parse(m.groupValues[1]) }.getOrNull())
            } ?: TurnCause.Text(text)
        }
    }
}

/** An out-of-turn turn shown ahead of the next detail load, and how far the transcript had been read. */
data class BackgroundOverlay(val turn: MessageTurn, val watermark: Long)

/**
 * The out-of-turn transcript overlay (codeg web 0.44 `conversation-runtime-store.ts`,
 * `APPLY_BACKGROUND_ACTIVITY` and `retireCoveredBackgroundTurns`): already-persisted facts shown
 * before a detail load brings them, then retired so the two copies never stand together.
 */
object BackgroundOverlays {

    /** The most overlay turns kept: cron / loop turns never settle, so nothing else bounds it. */
    const val HARD_CAP = 200

    fun upsert(current: List<BackgroundOverlay>, turns: List<MessageTurn>, watermark: Long): List<BackgroundOverlay> {
        if (turns.isEmpty()) return current
        val next = current.toMutableList()
        for (turn in turns) {
            val entry = BackgroundOverlay(turn, watermark)
            val at = next.indexOfFirst { it.turn.id == turn.id }
            if (at >= 0) next[at] = entry else next.add(entry)
        }
        return if (next.size > HARD_CAP) next.takeLast(HARD_CAP) else next
    }

    /**
     * Retire what a detail read covers: an entry read at or before its [watermark]. A windowed
     * read only covers its window, so with [uncoveredMaxTs] (the newest timestamp before the
     * window) an entry is retired only when it is provably inside the window — strictly newer.
     */
    fun retire(current: List<BackgroundOverlay>, watermark: Long?, uncoveredMaxTs: Instant?): List<BackgroundOverlay> {
        if (watermark == null || current.isEmpty()) return current
        val kept = current.filter { e ->
            when {
                e.watermark > watermark -> true
                uncoveredMaxTs == null -> false
                else -> !e.turn.timestamp.isAfter(uncoveredMaxTs)
            }
        }
        return if (kept.size == current.size) current else kept
    }

    /** The tool-call ids the overlay's turns name. */
    fun callIds(overlay: List<BackgroundOverlay>): Set<String> = overlay.flatMapTo(HashSet()) { o ->
        o.turn.blocks.mapNotNull {
            when (it) {
                is ContentBlock.ToolUse -> it.id
                is ContentBlock.ToolResult -> it.id
                else -> null
            }
        }
    }

    /**
     * [turns] less those an overlay turn re-tells: a detail read can hold a background reply
     * still growing, which the watcher then re-sends whole under its own id. The overlay copy is
     * the newer one (a read after it would have retired it), and the two share their calls —
     * drawn together they would show every step twice. [turns] itself when nothing overlaps.
     */
    fun withoutRetold(turns: List<MessageTurn>, overlayCallIds: Set<String>): List<MessageTurn> {
        if (overlayCallIds.isEmpty()) return turns
        val kept = turns.filterNot { turn ->
            turn.blocks.any {
                (it is ContentBlock.ToolUse && it.id in overlayCallIds) || (it is ContentBlock.ToolResult && it.id in overlayCallIds)
            }
        }
        return if (kept.size == turns.size) turns else kept
    }

    /** [local] and [overlay] in wall-clock order: a foreground exchange between background turns keeps its place. */
    fun interleave(local: List<MessageTurn>, overlay: List<BackgroundOverlay>): List<MessageTurn> {
        if (overlay.isEmpty()) return local
        val out = ArrayList<MessageTurn>(local.size + overlay.size)
        var i = 0
        var j = 0
        while (i < local.size || j < overlay.size) {
            val takeLocal = j >= overlay.size || (i < local.size && !local[i].timestamp.isAfter(overlay[j].turn.timestamp))
            if (takeLocal) out.add(local[i++]) else out.add(overlay[j++].turn)
        }
        return out
    }
}

/**
 * An async sub-agent's launch card (codeg web 0.44 `lib/background-agent.ts`). The server's
 * history parser writes the task's lifecycle into the launch's result after a marker; a live
 * `<task-notification>` settle rewrites it the same way in memory.
 */
object BackgroundTask {

    const val MARKER = "[[codeg-background-task]]"

    data class Lifecycle(val taskId: String, val status: String?, val summary: String?, val result: String?, val interim: Boolean)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The lifecycle a launch's [output] carries after the marker, or null. */
    fun parse(output: String?): Lifecycle? {
        val trimmed = output?.trimStart() ?: return null
        if (!trimmed.startsWith(MARKER)) return null
        val payload = runCatching { Json.parseToJsonElement(trimmed.substring(MARKER.length)) as? JsonObject }.getOrNull() ?: return null
        val taskId = payload.str("task_id") ?: return null
        val interim = (payload["interim"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        return Lifecycle(taskId, payload.str("status"), payload.str("summary"), payload.str("result"), interim)
    }

    /** The acks the agents answer a background launch with, before anything settles it. */
    fun isLaunchAck(output: String?): Boolean {
        if (output == null) return false
        val text = output.trimStart()
        return output.contains("Async agent launched successfully") ||
            text.startsWith("Subagent started in background") ||
            text.startsWith("The subagent is working in the background (sessionID:")
    }

    private fun markerOf(s: BackgroundSettledInfo): String = MARKER + buildJsonObject {
        put("task_id", s.taskId)
        put("status", s.status)
        s.summary?.let { put("summary", it) }
        s.result?.let { put("result", it) }
        if (s.interim) put("interim", true)
    }.toString()

    /**
     * [turns] with [settled]'s launch card flipped to its settle: the launch's result rewritten to
     * the marker the history parser would write. Only a launch card — a background shell's
     * notification names its command call too, and that card stays as it is. [turns] itself
     * comes back when nothing matched.
     */
    fun settle(turns: List<MessageTurn>, settled: BackgroundSettledInfo): List<MessageTurn> {
        val callId = settled.toolUseId ?: return turns
        val marker = markerOf(settled)
        var changed = false
        val next = turns.map { turn ->
            var turnChanged = false
            val blocks = turn.blocks.map { block ->
                if (block is ContentBlock.ToolResult && block.id == callId) {
                    val launch = isLaunchAck(block.outputPreview) || parse(block.outputPreview) != null
                    if (launch && block.outputPreview != marker) {
                        turnChanged = true
                        return@map block.copy(outputPreview = marker)
                    }
                }
                block
            }
            if (turnChanged) {
                changed = true
                turn.copy(blocks = blocks)
            } else {
                turn
            }
        }
        return if (changed) next else turns
    }
}

/**
 * What the connection has running beyond the reply on screen, for the "N running" list above the
 * composer (codeg web 0.44 `lib/live-runs.ts`).
 */
data class LiveRuns(
    /** Async tasks still going. */
    val tasks: List<AsyncTaskRecord>,
    val subagents: List<SubAgentRun>,
    /** Delegated children still running, with their binding when one was seen. */
    val delegations: List<DelegationRun>,
) {
    val count: Int get() = tasks.size + subagents.size + delegations.size

    /** A delegation still running: the connection's own word ([childConnectionId]), its
     *  identity from the binding. */
    data class DelegationRun(val childConnectionId: String, val binding: DelegationBinding?)

    /** A sub-agent at work: a launch running in the reply, or one running in the background. */
    data class SubAgentRun(val key: String, val title: String?, val background: Boolean, val description: String?, val startedAtMs: Long?)

    companion object {
        val NONE = LiveRuns(emptyList(), emptyList(), emptyList())

        /**
         * [foreground]: the sub-agent calls running in the live reply (id to title). A background
         * shell is skipped here: the async-task channel lists it, with its stop. [launchTitle]
         * names a background sub-agent after its launch card, when one is on screen.
         */
        fun collect(
            asyncTasks: List<AsyncTaskRecord>,
            foreground: List<Pair<String, String>>,
            background: List<BackgroundOutstandingTask>,
            delegatedChildren: List<String>,
            launchTitle: (String) -> String? = { null },
            delegationBindings: Collection<DelegationBinding> = emptyList(),
        ): LiveRuns {
            val subagents = mutableListOf<SubAgentRun>()
            val seen = HashSet<String>()
            for ((id, title) in foreground) {
                if (seen.add(id)) subagents += SubAgentRun("call:$id", title, background = false, description = null, startedAtMs = null)
            }
            for (task in background) {
                if (task.kind != "agent") continue
                val callId = task.toolUseId
                if (callId != null && !seen.add(callId)) continue
                subagents += SubAgentRun(
                    "bg:${task.taskId}",
                    callId?.let(launchTitle),
                    background = true,
                    description = task.description,
                    startedAtMs = task.startedAtMs.takeIf { it > 0 },
                )
            }
            // Which delegations run is the parent's word; a binding only names one. A child whose
            // binding says it finished is done, whatever a stale running set says.
            val byChild = HashMap<String, DelegationBinding>()
            for (b in delegationBindings) {
                val known = byChild[b.childConnectionId]
                if (known == null || b.status == DelegationBinding.Status.RUNNING) byChild[b.childConnectionId] = b
            }
            val delegations = delegatedChildren.distinct().mapNotNull { child ->
                val binding = byChild[child]
                if (binding != null && binding.status != DelegationBinding.Status.RUNNING) null else DelegationRun(child, binding)
            }
            return LiveRuns(AsyncTasks.live(asyncTasks), subagents, delegations)
        }
    }
}
