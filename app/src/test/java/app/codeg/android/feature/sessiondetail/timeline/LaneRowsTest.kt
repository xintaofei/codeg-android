package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.feature.sessiondetail.LiveTurnBuilder
import app.codeg.android.feature.sessiondetail.rendering.BackgroundTaskPolls
import app.codeg.android.feature.sessiondetail.rendering.Goals
import app.codeg.android.feature.sessiondetail.rendering.StepStatus
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** How background-task lanes and Codex `/goal` runs reach the timeline: their rows, the fold, the header. */
class LaneRowsTest {

    private val agent = AgentType.CODEX

    private fun user(id: String, text: String) = MessageTurn(id, TurnRole.USER, listOf(ContentBlock.Text(text)), Instant.EPOCH)

    private fun assistant(id: String, blocks: List<ContentBlock>) = MessageTurn(id, TurnRole.ASSISTANT, blocks, Instant.EPOCH)

    private fun call(id: String, name: String, input: String?, output: String?, error: Boolean = false): List<ContentBlock> =
        listOfNotNull(ContentBlock.ToolUse(id, name, input), output?.let { ContentBlock.ToolResult(id, it, error) })

    private fun pollOutput(taskId: String, status: String, exit: Int? = null, output: String? = null) = buildString {
        append("<retrieval_status>success</retrieval_status>\n<task_id>$taskId</task_id>\n<task_type>local_bash</task_type>\n<status>$status</status>")
        exit?.let { append("\n<exit_code>$it</exit_code>") }
        output?.let { append("\n<output>$it</output>") }
    }

    private fun taskOutput(id: String, taskId: String, output: String?) =
        call(id, "TaskOutput", """{"task_id":"$taskId","block":true,"timeout":30000}""", output)

    private val createBlocks = call("g1", "create_goal", """{"objective":"Tidy the README"}""", "ok")
    private val bashBlocks = call("c1", "Bash", """{"command":"ls"}""", "README.md")

    // region Goal runs

    @Test
    fun `a settled reply's run is one goal row holding its calls, its answer kept in view`() {
        val entries = TranscriptTimeline.persistedEntries(
            listOf(user("u", "/goal tidy the README"), assistant("a", createBlocks + bashBlocks + ContentBlock.Text("Tidied."))),
            emptyList(),
            agent,
        )
        val reply = (entries.last() as ThreadEntry.Reply).rows
        val goal = reply.process.single { it.id == "goal-g1" }
        val content = goal.content as NodeContent.GoalRun
        assertFalse(content.run.isRunning)
        assertEquals(MarkerKind.Goal(ToolCallState.DONE), goal.marker)
        assertEquals("Tidy the README", Goals.parseRun(content.run.start, content.run.end).objective)
        // The run's calls are drawn inside its row, on a rail of their own.
        assertEquals(listOf("tool-c1"), content.steps.map { it.id })
        assertFalse(content.steps.first().connectTop)
        assertFalse(content.steps.last().connectBottom)
        assertTrue(reply.process.none { it.id == "tool-c1" })
        // Settled, the run hands its trailing answer back to the reply.
        assertEquals(1, reply.answer.size)
        assertTrue(reply.answer.single().content is NodeContent.AssistantBlock)
        // The run's work counts, its own goal call doesn't.
        assertEquals(1, reply.summary.steps)
        val folded = TranscriptTimeline.flatten(entries) { false }.map { it.id }
        assertFalse("goal-g1" in folded)
    }

    @Test
    fun `a run in the round still running is running, and keeps its answer`() {
        val turns = listOf(user("u", "/goal tidy"), assistant("a", createBlocks + bashBlocks + ContentBlock.Text("Reading.")))
        val entries = TranscriptTimeline.persistedEntries(turns, emptyList(), agent, inFlightUserTurnId = "u")
        val reply = (entries.last() as ThreadEntry.Reply).rows
        val content = reply.process.single().content as NodeContent.GoalRun
        assertTrue(content.run.isRunning)
        assertEquals(listOf("tool-c1", "goal-g1#1"), content.steps.map { it.id })
        assertTrue(reply.answer.isEmpty())
    }

    @Test
    fun `a live run blinks only while its reply streams`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("g1", "Goal updated (active): Tidy the README", "other", "completed", """{"objective":"Tidy the README"}""", "", null)
        b.appendText("Reading README.md")
        val streaming = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        val run = streaming.process.single()
        assertEquals(MarkerKind.Goal(ToolCallState.RUNNING), run.marker)
        assertTrue(streaming.answer.isEmpty())
        b.isStreaming = false
        val stopped = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        assertEquals(MarkerKind.Goal(ToolCallState.DONE), stopped.process.single().marker)
        assertEquals(1, stopped.answer.size)
    }

    @Test
    fun `a thought being written inside a live run reads as thinking`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("g1", "Goal updated (active): Tidy the README", "other", "completed", """{"objective":"Tidy the README"}""", "", null)
        b.appendThinking("**Reading the README**\nIt is long.")
        val streaming = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        val steps = (streaming.process.single().content as NodeContent.GoalRun).steps
        assertEquals(true, (steps.last().content as NodeContent.Reasoning).streaming)
        b.isStreaming = false
        val stopped = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        val settled = stopped.process.flatMap { (it.content as? NodeContent.GoalRun)?.steps ?: listOf(it) }
        assertTrue(settled.none { (it.content as? NodeContent.Reasoning)?.streaming == true })
        // A run the goal closed is done thinking, though the reply streams on.
        b.isStreaming = true
        b.upsertToolCall("u1", "Goal updated (complete): Tidy the README", "other", "completed", """{"status":"complete"}""", "", null)
        val closed = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        val closedSteps = closed.process.flatMap { (it.content as? NodeContent.GoalRun)?.steps ?: listOf(it) }
        assertTrue(closedSteps.any { it.content is NodeContent.Reasoning })
        assertTrue(closedSteps.none { (it.content as? NodeContent.Reasoning)?.streaming == true })
    }

    @Test
    fun `a goal call outside any run draws as the goal row too`() {
        val entries = TranscriptTimeline.persistedEntries(
            listOf(
                user("u", "carry on"),
                assistant(
                    "a",
                    bashBlocks + call("u1", "update_goal", """{"status":"complete"}""", """{"goal":{"objective":"Tidy the README","status":"complete"}}""") +
                        ContentBlock.Text("Done."),
                ),
            ),
            emptyList(),
            agent,
        )
        val reply = (entries.last() as ThreadEntry.Reply).rows
        val lone = reply.process.single { it.id == "tool-u1" }
        assertTrue(lone.content is NodeContent.Tool)
        assertEquals(MarkerKind.Goal(ToolCallState.DONE), lone.marker)
        assertEquals("tool-u1", TranscriptTimeline.latestGoalCallId(entries)?.let { "tool-$it" })
    }

    @Test
    fun `a failure inside a run is shown at the run's row`() {
        val entries = TranscriptTimeline.persistedEntries(
            listOf(
                user("u", "/goal"),
                assistant(
                    "a",
                    createBlocks + call("c9", "Bash", """{"command":"make"}""", "Exit code 1", error = true) +
                        call("u1", "update_goal", """{"status":"complete"}""", "ok") + ContentBlock.Text("Gave up."),
                ),
            ),
            emptyList(),
            agent,
        )
        val reply = (entries.last() as ThreadEntry.Reply).rows
        assertEquals(1, reply.summary.unrecovered)
        assertEquals("goal-g1", reply.failureRowId)
    }

    // endregion

    // region Background tasks

    @Test
    fun `a background lane is one row per task, the launching command naming it`() {
        val launch = call(
            "b1", "Bash", """{"command":"cargo test","description":"Run the tests","run_in_background":true}""",
            "Command running in background with ID: task1. Output is being written to: /tmp/task1.output.",
        )
        val entries = TranscriptTimeline.persistedEntries(
            listOf(
                user("u", "test it"),
                assistant(
                    "a",
                    launch + taskOutput("p1", "task1", pollOutput("task1", "running")) +
                        taskOutput("p2", "task2", pollOutput("task2", "completed", exit = 2, output = "boom")) +
                        taskOutput("p3", "task1", pollOutput("task1", "completed", exit = 0, output = "ok")) +
                        ContentBlock.Text("All done."),
                ),
            ),
            emptyList(),
            agent,
        )
        val reply = (entries.last() as ThreadEntry.Reply).rows
        val rows = reply.process.filter { it.content is NodeContent.BackgroundTask }
        assertEquals(listOf("bg:p1:task1", "bg:p1:task2"), rows.map { it.id })
        val first = rows[0].content as NodeContent.BackgroundTask
        assertEquals(BackgroundTaskPolls.Badge.COMPLETED, first.row.badge)
        assertEquals(2, first.row.pollCount)
        assertEquals("Run the tests", first.launch?.description)
        assertEquals(MarkerKind.BackgroundTask(false, StepStatus.FAILED), rows[1].marker)
        // The command, plus one step per task however often it was polled.
        assertEquals(3, reply.summary.steps)
        assertEquals(1, reply.summary.unrecovered)
        assertEquals("bg:p1:task2", reply.failureRowId)
        // Settled, nothing is pinned.
        assertTrue(reply.pinned.isEmpty())
    }

    @Test
    fun `folded, a live reply keeps in view only a task whose poll is still in flight`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("p1", "TaskOutput", "other", "completed", """{"task_id":"t1","block":true,"timeout":1}""", pollOutput("t1", "completed", 0, "ok"), null)
        b.upsertToolCall("p2", "TaskOutput", "other", "in_progress", """{"task_id":"t2","block":true,"timeout":1}""", "", null)
        val reply = (TranscriptTimeline.liveEntries(b.snapshot(), agent).single() as ThreadEntry.Reply).rows
        assertEquals(listOf("bg:p1:t1", "bg:p1:t2"), reply.process.map { it.id })
        assertEquals(setOf("bg:p1:t2"), reply.pinned)
        val folded = TranscriptTimeline.flatten(listOf(ThreadEntry.Reply(reply))) { false }.map { it.id }
        assertEquals(listOf("live-1#header", "bg:p1:t2"), folded)
    }

    @Test
    fun `a lane inside a goal run is drawn inside its row`() {
        val entries = TranscriptTimeline.persistedEntries(
            listOf(
                user("u", "/goal"),
                assistant("a", createBlocks + taskOutput("p1", "t1", pollOutput("t1", "completed", 0, "ok")) + call("u1", "update_goal", "{}", "ok")),
            ),
            emptyList(),
            agent,
        )
        val reply = (entries.last() as ThreadEntry.Reply).rows
        val content = reply.process.single().content as NodeContent.GoalRun
        assertEquals(listOf("bg:p1:t1"), content.steps.map { it.id })
        assertEquals(1, reply.summary.steps)
    }

    // endregion

    // region A live reply joined onto the persisted one before it

    private fun joined(prevBlocks: List<ContentBlock>, live: LiveTurnBuilder): ReplyRows {
        val persisted = TranscriptTimeline.persistedEntries(listOf(user("u", "/goal tidy"), assistant("a1", prevBlocks)), emptyList(), agent)
        val entries = TranscriptTimeline.joinLive(persisted, TranscriptTimeline.liveEntries(live.snapshot(), agent))
        return (entries.last() as ThreadEntry.Reply).rows
    }

    @Test
    fun `a goal left open goes on and closes in the live reply after it`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("c2", "Bash", "execute", "completed", """{"command":"cat README.md"}""", "# Readme", null)
        b.upsertToolCall("u1", "Goal updated (complete): Tidy the README", "other", "completed", """{"status":"complete"}""", "", null)
        b.appendText("Tidied.")
        val reply = joined(createBlocks + bashBlocks, b)
        val goal = reply.process.single()
        val content = goal.content as NodeContent.GoalRun
        assertEquals("goal-g1", goal.id)
        assertEquals("u1", content.run.end?.id)
        // The two commands, one each side of the seam, are one run of commands inside it.
        assertEquals(listOf("tool-c1"), content.steps.map { it.id })
        assertEquals(1, reply.answer.size)
        assertEquals(2, reply.summary.steps)
        assertEquals("a1", reply.id)
    }

    @Test
    fun `polls on either side of the seam are one task's row`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("p2", "TaskOutput", "other", "completed", """{"task_id":"t1","block":true,"timeout":1}""", pollOutput("t1", "completed", 0, "ok"), null)
        val reply = joined(taskOutput("p1", "t1", pollOutput("t1", "running")), b)
        val rows = reply.process.filter { it.content is NodeContent.BackgroundTask }
        assertEquals(listOf("bg:p1:t1"), rows.map { it.id })
        assertEquals(2, (rows.single().content as NodeContent.BackgroundTask).row.pollCount)
        assertEquals(1, reply.summary.steps)
    }

    @Test
    fun `the joined rows are the ones the persisted merge draws once the reply is handed off`() {
        val b = LiveTurnBuilder("live-1")
        b.upsertToolCall("c2", "Bash", "execute", "completed", """{"command":"cat README.md"}""", "# Readme", null)
        b.upsertToolCall("u1", "Goal updated (complete): Tidy the README", "other", "completed", """{"status":"complete"}""", "", null)
        b.appendText("Tidied.")
        b.isStreaming = false
        val live = joined(createBlocks + bashBlocks, b)
        val handedOff = TranscriptTimeline.persistedEntries(
            listOf(user("u", "/goal tidy"), assistant("a1", createBlocks + bashBlocks), b.snapshotAsMessageTurn()),
            emptyList(),
            agent,
        )
        val persisted = (handedOff.last() as ThreadEntry.Reply).rows
        assertEquals(persisted.process.map { it.id }, live.process.map { it.id })
        assertEquals(persisted.answer.map { it.id }, live.answer.map { it.id })
        assertEquals(
            (persisted.process.single().content as NodeContent.GoalRun).steps.map { it.id },
            (live.process.single().content as NodeContent.GoalRun).steps.map { it.id },
        )
    }

    // endregion
}
