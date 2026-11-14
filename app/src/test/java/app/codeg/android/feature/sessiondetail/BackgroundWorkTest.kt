package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AsyncTaskDelta
import app.codeg.android.core.model.AsyncTaskRecord
import app.codeg.android.core.model.BackgroundOutstandingTask
import app.codeg.android.core.model.BackgroundSettledInfo
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.DelegationBinding
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Async tasks, the out-of-turn overlay, launch cards and the running list (web `async-tasks`, `live-runs`, runtime store). */
class BackgroundWorkTest {

    @Test
    fun `a task is announced once, then revised field by field`() {
        assertTrue("never announced", AsyncTasks.upsert(emptyList(), AsyncTaskDelta("t", state = "running")).isEmpty())
        val one = AsyncTasks.upsert(emptyList(), AsyncTaskDelta("t", spawned = true, name = "npm run dev", taskType = "shell", canStop = true))
        assertEquals("running", one.single().state)
        val done = AsyncTasks.upsert(one, AsyncTaskDelta("t", state = "completed", summary = "exit 0"))
        assertEquals(AsyncTaskRecord("t", "npm run dev", "shell", canStop = true, state = "completed", summary = "exit 0"), done.single())
        assertTrue(AsyncTasks.live(done).isEmpty())
        assertSame(done, AsyncTasks.upsert(done, AsyncTaskDelta("t")))
    }

    @Test
    fun `a fresh snapshot replaces rows, an overtaken one only adds`() {
        val finished = listOf(AsyncTaskRecord("t", state = "completed"))
        val older = listOf(AsyncTaskRecord("t", state = "running"), AsyncTaskRecord("u"))
        assertEquals(listOf("running", "running"), AsyncTasks.merge(finished, older).map { it.state })
        val adopted = AsyncTasks.adoptUnknown(finished, older)
        assertEquals("the finished task stays finished", listOf("completed", "running"), adopted.map { it.state })
        assertSame(finished, AsyncTasks.adoptUnknown(finished, listOf(AsyncTaskRecord("t"))))
    }

    @Test
    fun `an autonomous turn says what started it`() {
        assertEquals(AsyncTasks.TurnCause.Unknown, AsyncTasks.turnCause("Claude Code started a turn without a prompt."))
        assertEquals(AsyncTasks.TurnCause.ScheduledWakeup(null), AsyncTasks.turnCause("A scheduled wakeup fired."))
        assertEquals(
            AsyncTasks.TurnCause.ScheduledWakeup(Instant.parse("2026-11-14T09:00:00Z")),
            AsyncTasks.turnCause("A scheduled wakeup fired (set for 2026-11-14T09:00:00Z)."),
        )
        assertEquals(AsyncTasks.TurnCause.ScheduledTask, AsyncTasks.turnCause("A scheduled task fired."))
        assertEquals(AsyncTasks.TurnCause.Text("Background command finished"), AsyncTasks.turnCause(" Background command finished "))
    }

    private fun turn(id: String, at: Long, role: TurnRole = TurnRole.ASSISTANT, blocks: List<ContentBlock> = listOf(ContentBlock.Text(id))) =
        MessageTurn(id, role, blocks, Instant.ofEpochSecond(at))

    @Test
    fun `overlay turns upsert by id, and a detail read retires what it covers`() {
        val first = BackgroundOverlays.upsert(emptyList(), listOf(turn("bg-1", 10)), watermark = 100)
        val grown = BackgroundOverlays.upsert(first, listOf(turn("bg-1", 10, blocks = listOf(ContentBlock.Text("more"))), turn("bg-2", 20)), watermark = 200)
        assertEquals(listOf("bg-1", "bg-2"), grown.map { it.turn.id })
        assertEquals(200L, grown[0].watermark)
        // A read through 150 has neither of them (both were read at 200).
        assertSame(grown, BackgroundOverlays.retire(grown, 150, null))
        assertTrue(BackgroundOverlays.retire(grown, 200, null).isEmpty())
        assertSame(grown, BackgroundOverlays.retire(grown, null, null))
        // A windowed read covers only what is newer than the turns before its window.
        val kept = BackgroundOverlays.retire(grown, 300, Instant.ofEpochSecond(15))
        assertEquals(listOf("bg-1"), kept.map { it.turn.id })
    }

    @Test
    fun `overlay turns interleave with the finished rounds by time`() {
        val local = listOf(turn("u1", 5, TurnRole.USER), turn("a1", 30))
        val overlay = BackgroundOverlays.upsert(emptyList(), listOf(turn("bg-1", 20)), 1)
        assertEquals(listOf("u1", "bg-1", "a1"), BackgroundOverlays.interleave(local, overlay).map { it.id })
        assertSame(local, BackgroundOverlays.interleave(local, emptyList()))
    }

    @Test
    fun `a launch card reads its lifecycle, and a settle flips it in place`() {
        val ack = "Async agent launched successfully.\nagentId: a1"
        val turns = listOf(
            turn("t1", 1, blocks = listOf(ContentBlock.ToolUse("call-1", "Agent", "{}"), ContentBlock.ToolResult("call-1", ack, false))),
            turn("t2", 2, blocks = listOf(ContentBlock.ToolUse("call-2", "Bash", "{}"), ContentBlock.ToolResult("call-2", "done", false))),
        )
        assertTrue(BackgroundTask.isLaunchAck(ack))
        val settled = BackgroundTask.settle(turns, BackgroundSettledInfo("a1", "completed", "Audit written", "call-1", "## Report"))
        val output = (settled[0].blocks[1] as ContentBlock.ToolResult).outputPreview
        assertEquals(BackgroundTask.Lifecycle("a1", "completed", "Audit written", "## Report", false), BackgroundTask.parse(output))
        assertSame("the other turn is untouched", turns[1], settled[1])
        // A shell's notification names its command call: that card stays.
        assertSame(turns, BackgroundTask.settle(turns, BackgroundSettledInfo("s1", "completed", toolUseId = "call-2")))
        assertSame(turns, BackgroundTask.settle(turns, BackgroundSettledInfo("a1", "completed")))
        assertNull(BackgroundTask.parse("plain output"))
        assertTrue(BackgroundTask.parse(BackgroundTask.MARKER + """{"task_id":"x","interim":true}""")!!.interim)
    }

    @Test
    fun `the running list counts tasks, sub-agents and delegations once each`() {
        val runs = LiveRuns.collect(
            asyncTasks = listOf(AsyncTaskRecord("t"), AsyncTaskRecord("done", state = "stopped")),
            foreground = listOf("call-1" to "Explore the repo"),
            background = listOf(
                BackgroundOutstandingTask("a1", "agent", toolUseId = "call-1"),
                BackgroundOutstandingTask("a2", "agent", toolUseId = "call-9", description = "Write docs", startedAtMs = 5),
                BackgroundOutstandingTask("s1", "shell"),
            ),
            delegatedChildren = listOf("child", "child"),
            launchTitle = { if (it == "call-9") "Docs agent" else null },
        )
        assertEquals(listOf("t"), runs.tasks.map { it.taskId })
        assertEquals(listOf("call:call-1", "bg:a2"), runs.subagents.map { it.key })
        assertEquals("Docs agent", runs.subagents[1].title)
        assertEquals(listOf("child"), runs.delegations.map { it.childConnectionId })
        assertEquals(4, runs.count)
        assertFalse(runs.subagents[0].background)
    }

    private fun binding(child: String, status: DelegationBinding.Status) =
        DelegationBinding("call-$child", child, 7, "codex", status, "Port the parser", "task-1", null)

    @Test
    fun `a delegation is named by its binding, and one its binding saw finish is gone`() {
        val runs = LiveRuns.collect(
            asyncTasks = emptyList(),
            foreground = emptyList(),
            background = emptyList(),
            delegatedChildren = listOf("a", "b", "c"),
            delegationBindings = listOf(binding("a", DelegationBinding.Status.RUNNING), binding("b", DelegationBinding.Status.OK)),
        )
        assertEquals(listOf("a", "c"), runs.delegations.map { it.childConnectionId })
        assertEquals("codex", runs.delegations[0].binding?.agentType)
        assertNull("no binding seen", runs.delegations[1].binding)
    }
}
