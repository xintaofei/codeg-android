package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex `/goal` runs: the web's `groupGoalRuns` suite (`ai-elements-adapter.test.ts`, codeg 0.44),
 * the iOS port's stale / closing-run cases, and the goal row's facts (`goal-tool-call.test.tsx`).
 */
class GoalRunsTest {

    /** The web's `poll(toolName)`: a settled call with no output, its id `<name>:`. */
    private fun poll(name: String, state: ToolCallState = ToolCallState.DONE) =
        ToolCallVM.of("$name:", name, "", state, null, null, null, false)

    private fun call(name: String, state: ToolCallState = ToolCallState.DONE) = RenderPart.Tool(poll(name, state))

    private fun goalCall(id: String, name: String, input: String? = null, output: String? = null, error: Boolean = false) =
        ToolCallVM.of(id, name, "", if (error) ToolCallState.ERROR else ToolCallState.DONE, input, output, null, error)

    private val text = RenderPart.Text("checking again")

    /** A part's shape, for comparing lists of parts. */
    private fun shape(part: RenderPart): String = when (part) {
        is RenderPart.Text -> "text:${part.text}"
        is RenderPart.Tool -> "tool:${part.vm.id}"
        is RenderPart.Reasoning -> "reasoning"
        is RenderPart.ProposedPlan -> "proposed-plan"
        is RenderPart.Image -> "image"
        is RenderPart.GoalRun -> "goal-run(${part.start.id}…${part.end?.id})${part.items.map(::shape)}"
        else -> part::class.simpleName.orEmpty()
    }

    private fun types(parts: List<RenderPart>) = parts.map {
        when (it) {
            is RenderPart.GoalRun -> "goal-run"
            is RenderPart.Tool -> "tool-call"
            is RenderPart.Text -> "text"
            is RenderPart.ProposedPlan -> "proposed-plan"
            is RenderPart.Image -> "image"
            is RenderPart.Reasoning -> "reasoning"
            else -> it::class.simpleName.orEmpty()
        }
    }

    private fun run(part: RenderPart?) = part as RenderPart.GoalRun

    // region groupGoalRuns (web)

    @Test
    fun `wraps create_goal through update_goal with intervening process parts`() {
        val out = GoalRuns.group(listOf(call("create_goal"), text, call("exec_command"), call("update_goal"), RenderPart.Text("final answer")))
        assertEquals(listOf("goal-run", "text"), types(out))
        val goal = run(out[0])
        assertEquals("create_goal", goal.start.rawName)
        assertEquals("update_goal", goal.end?.rawName)
        assertEquals(listOf("text", "tool-call"), types(goal.items))
        assertFalse(goal.isRunning)
    }

    @Test
    fun `wraps an unfinished goal run as running while streaming`() {
        val out = GoalRuns.group(listOf(call("create_goal"), text), isStreaming = true)
        assertEquals(1, out.size)
        val goal = run(out[0])
        assertNull(goal.end)
        assertEquals(listOf(text), goal.items)
        assertTrue(goal.isRunning)
    }

    @Test
    fun `settles an unfinished goal run when not streaming, its trailing prose lifted out`() {
        // codex leaves a /goal active without a closing update_goal: a stopped turn or a reloaded
        // conversation must not spin the row forever, nor hide the answer in the folded row.
        val out = GoalRuns.group(listOf(call("create_goal"), text), isStreaming = false)
        assertEquals(listOf("goal-run", "text"), types(out))
        val goal = run(out[0])
        assertNull(goal.end)
        assertTrue(goal.items.isEmpty())
        assertFalse(goal.isRunning)
        assertEquals(text, out[1])
    }

    @Test
    fun `lifts trailing prose out of a settled unfinished goal after tools`() {
        val out = GoalRuns.group(listOf(call("create_goal"), call("exec_command"), text))
        assertEquals(listOf("goal-run", "text"), types(out))
        assertEquals(listOf("tool-call"), types(run(out[0]).items))
        assertEquals(text, out[1])
    }

    @Test
    fun `lifts a trailing proposed plan and image too, keeping reasoning inside`() {
        val plan = RenderPart.ProposedPlan("## Plan\n1. do the thing", streaming = false)
        val image = RenderPart.Image(app.codeg.android.core.model.ImageData("AA=="), null)
        val reasoning = RenderPart.Reasoning("thinking about it")
        val out = GoalRuns.group(listOf(call("create_goal"), reasoning, text, plan, image))
        assertEquals(listOf("goal-run", "text", "proposed-plan", "image"), types(out))
        assertEquals(listOf(reasoning), run(out[0]).items)
        assertEquals(listOf(text, plan, image), out.drop(1))
    }

    @Test
    fun `keeps mid-run prose inside a settled unfinished goal`() {
        val note = RenderPart.Text("checking")
        val out = GoalRuns.group(listOf(call("create_goal"), note, call("exec_command")))
        assertEquals(listOf("goal-run"), types(out))
        assertEquals(listOf("text", "tool-call"), types(run(out[0]).items))
    }

    @Test
    fun `keeps the answer inside a live unfinished goal`() {
        val plan = RenderPart.ProposedPlan("## Plan", streaming = false)
        val out = GoalRuns.group(listOf(call("create_goal"), text, plan), isStreaming = true)
        assertEquals(listOf("goal-run"), types(out))
        assertEquals(listOf(text, plan), run(out[0]).items)
        assertTrue(run(out[0]).isRunning)
    }

    @Test
    fun `closing a reopened unfinished run across turns twice gives the same run`() {
        val first = RenderPart.Text("started goal")
        val next = RenderPart.Text("continued goal")
        val unfinished = RenderPart.GoalRun(poll("create_goal"), null, listOf(first), isRunning = true)
        val once = GoalRuns.group(listOf(unfinished, next, call("update_goal")))
        assertEquals(listOf(first, next), run(once[0]).items)
        assertEquals(listOf(first), unfinished.items)
        val twice = GoalRuns.group(listOf(unfinished, next, call("update_goal")))
        assertEquals(listOf(first, next), run(twice[0]).items)
    }

    @Test
    fun `merges repeated unfinished goal runs into one cross-turn run`() {
        val first = RenderPart.Text("started goal")
        val next = RenderPart.Text("continued goal")
        val out = GoalRuns.group(
            listOf(
                RenderPart.GoalRun(poll("create_goal"), null, listOf(first), isRunning = true),
                RenderPart.GoalRun(poll("create_goal"), null, emptyList(), isRunning = true),
                next,
            ),
        )
        assertEquals(listOf("goal-run", "text", "text"), types(out))
        assertTrue(run(out[0]).items.isEmpty())
        assertEquals(first, out[1])
        assertEquals(next, out[2])
    }

    @Test
    fun `closes an active cross-turn goal when the next turn already has a completed run`() {
        val first = RenderPart.Text("started goal")
        val command = call("exec_command")
        val final = RenderPart.Text("final answer")
        val out = GoalRuns.group(
            listOf(
                RenderPart.GoalRun(poll("create_goal"), null, listOf(first), isRunning = true),
                RenderPart.GoalRun(poll("create_goal"), poll("update_goal"), listOf(command), isRunning = false),
                final,
            ),
        )
        assertEquals(listOf("goal-run", "text"), types(out))
        assertEquals(listOf<RenderPart>(first, command), run(out[0]).items)
        assertEquals(final, out[1])
    }

    // endregion

    // region Stale and closing runs (iOS)

    private val objective = "分析 README 文件"

    @Test
    fun `drops a create_goal for an objective a run already completed`() {
        val out = GoalRuns.group(
            listOf(
                RenderPart.Tool(goalCall("g1", "create_goal", input = """{"objective":"$objective"}""")),
                call("exec_command"),
                RenderPart.Tool(
                    goalCall("g2", "update_goal", input = """{"status":"complete"}""", output = """{"goal":{"objective":"$objective","status":"complete"}}"""),
                ),
                RenderPart.Tool(goalCall("g3", "create_goal", input = """{"objective":"$objective"}""")),
                RenderPart.Text("已完成 README 分析。"),
            ),
            isStreaming = true,
        )
        assertEquals(listOf("goal-run", "text"), types(out))
        assertEquals("update_goal", run(out[0]).end?.rawName)
        assertFalse(run(out[0]).isRunning)
        assertFalse(out.joinToString { shape(it) }.contains("g3"))
    }

    @Test
    fun `spills a stale unfinished run's parts into the reply`() {
        val completed = RenderPart.GoalRun(
            goalCall("g1", "create_goal", input = """{"objective":"$objective"}"""),
            goalCall("g2", "update_goal", output = """{"goal":{"objective":"$objective"}}"""),
            emptyList(),
            isRunning = false,
        )
        val stale = RenderPart.GoalRun(goalCall("g3", "create_goal", input = """{"objective":"$objective"}"""), null, listOf(RenderPart.Text("again")), isRunning = true)
        assertEquals(listOf("goal-run(g1…g2)[]", "text:again"), GoalRuns.group(listOf(completed, stale)).map(::shape))
    }

    @Test
    fun `keeps a run whose closing update_goal is still running, running`() {
        val out = GoalRuns.group(listOf(call("create_goal"), call("exec_command"), call("update_goal", ToolCallState.RUNNING)))
        assertTrue(run(out[0]).isRunning)
        assertEquals("update_goal:", run(out[0]).end?.id)
    }

    @Test
    fun `starts and closes runs under any wrapper of the goal tools`() {
        val out = GoalRuns.group(listOf(call("functions.create_goal"), text, call("mcp__codeg__update_goal")))
        assertEquals(listOf("goal-run"), types(out))
        assertEquals("mcp__codeg__update_goal:", run(out[0]).end?.id)
    }

    @Test
    fun `leaves a closing call with no run open as a plain call`() {
        assertEquals(listOf("text", "tool-call"), types(GoalRuns.group(listOf(text, call("update_goal")))))
    }

    @Test
    fun `names a goal by its result's objective, else its argument`() {
        assertEquals(
            "from output",
            GoalRuns.objectiveKey(goalCall("a", "update_goal", input = """{"objective":"from input"}""", output = """{"goal":{"objective":"  from output  "}}""")),
        )
        assertEquals("from input", GoalRuns.objectiveKey(goalCall("b", "create_goal", input = """{"objective":"from input"}""", output = "ok")))
        assertNull(GoalRuns.objectiveKey(goalCall("c", "create_goal", input = """{"objective":"   "}""")))
        assertNull(GoalRuns.objectiveKey(goalCall("d", "update_goal", input = """{"status":"complete"}""")))
    }

    @Test
    fun `a live goal update titled by the server opens a run`() {
        // A structured goal update arrives live as `Goal updated (active): …` (Goals.nameFromTitle).
        val live = ToolCallVM.of("g1", "Goal updated (active): $objective", "other", ToolCallState.DONE, """{"objective":"$objective"}""", null, null, false)
        val streaming = GoalRuns.group(listOf(RenderPart.Tool(live), RenderPart.Text("Reading README.md")), isStreaming = true)
        assertEquals(listOf("goal-run"), types(streaming))
        assertTrue(run(streaming[0]).isRunning)
        val stopped = GoalRuns.group(listOf(RenderPart.Tool(live), RenderPart.Text("Reading README.md")), isStreaming = false)
        assertEquals(listOf("goal-run", "text"), types(stopped))
    }

    // endregion

    // region Answer split and summary

    @Test
    fun `a goal run is process, never the answer`() {
        val goal = RenderPart.GoalRun(poll("create_goal"), null, listOf(RenderPart.Text("inside")), isRunning = false)
        assertFalse(isAnswerPart(goal))
        assertEquals(2, answerStart(listOf(RenderPart.Text("before"), goal, RenderPart.Text("after"))))
    }

    @Test
    fun `a goal's steps count as the reply's, and not its own calls`() {
        val read = ToolCallVM.of("r1", "Read", "", ToolCallState.DONE, """{"file_path":"/r/a.ts"}""", "x", null, false)
        val failing = ToolCallVM.of("b1", "Bash", "", ToolCallState.ERROR, """{"command":"make"}""", "Exit code 1", null, true)
        val retried = ToolCallVM.of("b2", "Bash", "", ToolCallState.DONE, """{"command":"make"}""", "ok", null, false)
        val parts = listOf(
            RenderPart.GoalRun(poll("create_goal"), poll("update_goal"), listOf(RenderPart.Tool(read), RenderPart.Tool(failing)), isRunning = false),
            RenderPart.Tool(retried),
            RenderPart.Text("Built."),
        )
        val summary = summarizeActivity(parts)
        assertEquals(3, summary.steps)
        // The retry outside the run recovers the failure inside it.
        assertEquals(1, summary.failed)
        assertEquals(0, summary.unrecovered)
    }

    // endregion

    // region Facts (web `goal-tool-call.test.tsx`, its pure half)

    @Test
    fun `reads a Codex goal completion's objective, usage and time`() {
        val end = goalCall(
            "call-goal", "update_goal", input = """{"status":"complete"}""",
            output = """{"goal":{"objective":"Analyze README file","status":"complete","tokensUsed":5184,"timeUsedSeconds":19},"completionBudgetReport":"Goal achieved."}""",
        )
        val facts = Goals.parseRun(end, null)
        assertEquals("Analyze README file", facts.objective)
        assertEquals("complete", facts.normalizedStatus)
        assertEquals("5.2K", Goals.tokens(facts.tokensUsed!!))
        assertEquals("19s", Goals.duration(facts.timeUsedSeconds!!))
    }

    @Test
    fun `reads a create_goal under a wrapper as active`() {
        val facts = Goals.parseRun(goalCall("c", "functions.create_goal", input = """{"objective":"Analyze README file"}"""), null)
        assertEquals("Analyze README file", facts.objective)
        assertEquals("active", facts.normalizedStatus)
        assertTrue(Goals.isGoalCall("functions.create_goal"))
    }

    @Test
    fun `prefers the closing call's word, then the opening call's`() {
        val start = goalCall("s", "create_goal", input = """{"objective":"Old","token_budget":50000}""", output = """{"goal":{"tokens_used":100},"remaining_tokens":49900}""")
        val end = goalCall("e", "update_goal", input = """{"status":"paused"}""", output = """{"goal":{"objective":"New","tokensUsed":2000}}""")
        val facts = Goals.parseRun(start, end)
        assertEquals("New", facts.objective)
        assertEquals("paused", facts.status)
        assertEquals(2000.0, facts.tokensUsed!!, 0.0)
        assertEquals(50000.0, facts.tokenBudget!!, 0.0)
        assertEquals(49900.0, facts.remainingTokens!!, 0.0)
    }

    @Test
    fun `shows a fenced error without its fence`() {
        val start = goalCall("c", "create_goal", input = """{"objective":"Analyze README file"}""", output = "```\nGoal already active.\n```", error = true)
        val goal = RenderPart.GoalRun(start, null, emptyList(), isRunning = false)
        assertTrue(GoalRuns.isError(goal))
        assertEquals(ToolCallState.ERROR, GoalRuns.markerState(goal))
        assertEquals("Goal already active.", Goals.errorText(start, null))
        assertEquals("```\na\n```\nmore\n```", Goals.unwrapWholeFence("```\na\n```\nmore\n```"))
        assertEquals("plain", Goals.unwrapWholeFence("plain"))
        assertNull(Goals.errorText(poll("create_goal"), null))
    }

    @Test
    fun `a run's mark works while it runs, else says how it settled`() {
        assertEquals(ToolCallState.RUNNING, GoalRuns.markerState(RenderPart.GoalRun(poll("create_goal"), null, emptyList(), isRunning = true)))
        assertEquals(ToolCallState.DONE, GoalRuns.markerState(RenderPart.GoalRun(poll("create_goal"), poll("update_goal"), emptyList(), isRunning = false)))
    }

    // endregion
}
