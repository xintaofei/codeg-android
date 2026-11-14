package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wraps Codex `/goal` lifecycles into goal runs: port of the web adapter's `groupGoalRuns` and
 * its helpers (`ai-elements-adapter.ts`, codeg 0.44).
 *
 * `create_goal` starts a run, every part until the closing `update_goal` belongs to it. Runs
 * after the background-task lane is grouped, so a run's items can hold it. The web groups each
 * persisted turn and then again across the turns of a reply (`mergeRunParts`); here a reply's
 * turns are merged before they are adapted ([app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline.merge]),
 * so one pass over the merged reply covers runs that cross turns. The goal-run arms below — a
 * run handed in already grouped — are kept so the grouping stays the web's, whole.
 *
 * Not ported: the web's `Goal updated (<status>): …` markers in assistant PROSE
 * (`expandGoalUpdateText`) and the objective hints that feed it. A live run's prose is not read
 * while the timeline is assembled; the structured goal update it stands for arrives as a call
 * ([Goals.nameFromTitle]).
 */
object GoalRuns {

    private fun bareName(name: String) = name.trim().lowercase().substringAfterLast("__").substringAfterLast('/').substringAfterLast('.')

    /** `create_goal`, under any wrapper (`functions.`, `mcp__…__`, a live title already resolved). */
    fun startCall(part: RenderPart): ToolCallVM? =
        (part as? RenderPart.Tool)?.vm?.takeIf { bareName(it.rawName) == "create_goal" }

    fun endCall(part: RenderPart): ToolCallVM? =
        (part as? RenderPart.Tool)?.vm?.takeIf { bareName(it.rawName) == "update_goal" }

    /** A call still being made (web `isRunningToolCall`). */
    fun isRunningCall(vm: ToolCallVM): Boolean = vm.state == ToolCallState.INPUT_STREAMING || vm.state == ToolCallState.RUNNING

    /** The key a goal's objective is written under, as it reads in JSON. */
    private const val OBJECTIVE_KEY = "\"objective\""

    private fun objectiveOf(obj: JsonObject?): String? =
        (obj?.get("objective") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** The objective a goal call names: its result's `goal.objective`, else its own `objective`
     *  argument (web `goalObjectiveKeyFromTool`). */
    fun objectiveKey(call: ToolCallVM): String? {
        // Every call of a reply can pass through here while it streams: parse only what can name
        // an objective at all.
        fun parsed(raw: String?) = raw?.takeIf { it.contains(OBJECTIVE_KEY) }?.let(ToolDerive::parseJson)
        val result = call.output?.takeIf { it.isNotBlank() } ?: call.content
        val goal = parsed(result)?.get("goal") as? JsonObject
        return objectiveOf(goal) ?: objectiveOf(parsed(call.input))
    }

    /** A run's objective: its closing call's, else its opening call's. */
    fun objectiveKey(run: RenderPart.GoalRun): String? = run.end?.let(::objectiveKey) ?: objectiveKey(run.start)

    private class Active(var start: ToolCallVM, val items: MutableList<RenderPart>)

    /**
     * Wrap each `create_goal` … `update_goal` lifecycle into one [RenderPart.GoalRun].
     *
     * codex keeps a `/goal` active across turns and does NOT emit a closing `update_goal` when a
     * turn ends or is interrupted, so an unfinished run is running only while [isStreaming] — a
     * stopped or reloaded one settles. A settled run folds to its row, so one that never closed
     * hands the answer after its last process part back to the reply ([answerStart]); a live run
     * keeps it, and opens.
     *
     * A run that closed remembers its objective: a later `create_goal` (or an unfinished run)
     * for the same objective is stale — codex re-announcing a goal it already finished — and is
     * not drawn as a run again.
     */
    fun group(parts: List<RenderPart>, isStreaming: Boolean = false): List<RenderPart> {
        if (parts.none { it is RenderPart.GoalRun || startCall(it) != null }) return parts
        val result = mutableListOf<RenderPart>()
        var active: Active? = null
        val completedObjectives = HashSet<String>()

        fun rememberCompleted(start: ToolCallVM, end: ToolCallVM?) {
            (end?.let(::objectiveKey) ?: objectiveKey(start))?.let(completedObjectives::add)
        }

        fun isStaleStart(call: ToolCallVM) = objectiveKey(call)?.let { it in completedObjectives } == true

        fun push(start: ToolCallVM, end: ToolCallVM?, items: List<RenderPart>, isRunning: Boolean) {
            // Only a run that never closed lifts its trailing answer: after a closing
            // `update_goal` later prose is already outside the run, and mid-run notes stay
            // where they were written.
            if (isRunning || end != null) {
                result += RenderPart.GoalRun(start, end, items.toList(), isRunning)
                return
            }
            val split = answerStart(items)
            result += RenderPart.GoalRun(start, null, items.subList(0, split).toList(), isRunning = false)
            result += items.subList(split, items.size)
        }

        fun flushActive() {
            val current = active ?: return
            push(current.start, null, current.items, isStreaming)
            active = null
        }

        for (part in parts) {
            if (part is RenderPart.GoalRun) {
                val staleUnfinished = part.end == null && objectiveKey(part)?.let { it in completedObjectives } == true
                val current = active
                if (current == null) {
                    when {
                        staleUnfinished -> result += part.items
                        part.end == null -> active = Active(part.start, part.items.toMutableList())
                        else -> {
                            push(part.start, part.end, part.items, part.isRunning)
                            rememberCompleted(part.start, part.end)
                        }
                    }
                    continue
                }
                if (staleUnfinished) {
                    current.items += part.items
                    continue
                }
                current.start = part.start
                if (part.end == null) {
                    current.items += part.items
                } else {
                    push(current.start, part.end, current.items + part.items, part.isRunning)
                    rememberCompleted(current.start, part.end)
                    active = null
                }
                continue
            }

            val start = startCall(part)
            if (start != null) {
                if (active == null && isStaleStart(start)) continue
                val current = active
                if (current != null) {
                    // A goal set again mid-run: the run goes on under it.
                    current.start = start
                    continue
                }
                active = Active(start, mutableListOf())
                continue
            }

            val current = active
            val end = if (current != null) endCall(part) else null
            if (current != null && end != null) {
                push(current.start, end, current.items, isRunningCall(end))
                rememberCompleted(current.start, end)
                active = null
                continue
            }

            if (current != null) current.items += part else result += part
        }

        flushActive()
        return result
    }

    /**
     * [parts] with each goal run opened up into the parts it holds, recursively: a goal's work
     * counts as the reply's, its own two calls not at all (web `summarize.ts` `flatten`).
     */
    fun flatten(parts: List<RenderPart>): List<RenderPart> {
        if (parts.none { it is RenderPart.GoalRun }) return parts
        val out = mutableListOf<RenderPart>()
        for (part in parts) {
            if (part is RenderPart.GoalRun) out += flatten(part.items) else out += part
        }
        return out
    }

    /** Either goal call failed. */
    fun isError(run: RenderPart.GoalRun): Boolean =
        run.start.isError || run.start.state == ToolCallState.ERROR || run.end?.let { it.isError || it.state == ToolCallState.ERROR } == true

    /** The run's mark on the rail: working while the run is, else settled — failed when a goal
     *  call did, as a lone goal call's mark says too. */
    fun markerState(run: RenderPart.GoalRun): ToolCallState = when {
        run.isRunning -> ToolCallState.RUNNING
        isError(run) -> ToolCallState.ERROR
        else -> ToolCallState.DONE
    }

    /** Every call a run stands for — its two goal calls and those it holds — for the rows that
     *  point at a call ("show the failure"). */
    fun callIds(run: RenderPart.GoalRun): List<String> = buildList {
        add(run.start.id)
        fun walk(items: List<RenderPart>) {
            for (item in items) {
                when (item) {
                    is RenderPart.Tool -> add(item.vm.id)
                    is RenderPart.ToolGroup -> item.items.forEach { add(it.id) }
                    is RenderPart.BackgroundTasks -> {
                        item.polls.forEach { add(it.id) }
                        item.rows.forEach { add(BackgroundTaskPolls.failureKey(it)) }
                    }
                    is RenderPart.GoalRun -> {
                        add(item.start.id)
                        walk(item.items)
                        item.end?.let { add(it.id) }
                    }
                    else -> Unit
                }
            }
        }
        walk(run.items)
        run.end?.let { add(it.id) }
    }
}
