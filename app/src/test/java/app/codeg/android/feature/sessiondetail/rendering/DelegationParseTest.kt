package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.feature.sessiondetail.rendering.DelegationParse.Badge
import app.codeg.android.feature.sessiondetail.rendering.DelegationParse.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading codeg's delegation tools off their wire shapes (web `delegation-card.ts` / `delegation-status.ts`). */
class DelegationParseTest {

    private val ackText = "Delegation successful. task_id=050a6227-bcb8. Call get_delegation_status with this id."

    @Test
    fun `arguments are read through host wrappers and double encoding`() {
        val plain = DelegationParse.args("""{"agent_type":"codex","working_dir":"/w","task":"Review it"}""")
        assertEquals("codex", plain.agentType)
        assertEquals("/w", plain.workingDir)
        assertEquals("Review it", plain.task)
        assertEquals("deepseek", DelegationParse.args("""{"arguments":{"agent_type":"deepseek","task":"t"}}""").agentType)
        assertEquals("t2", DelegationParse.args("\"{\\\"task\\\":\\\"t2\\\"}\"").task)
        val resume = DelegationParse.args("""{"task_id":"task-7","reason":"go on"}""")
        assertEquals("task-7", resume.taskId)
        assertEquals("go on", resume.reason)
        assertNull(DelegationParse.args("{}").agentType)
        assertNull(DelegationParse.args("not json").task)
    }

    @Test
    fun `a running ack is an ack in every shape it arrives in`() {
        val bare = DelegationParse.callResult("""{"task_id":"t1","status":"running","child_conversation_id":4519,"agent_type":"codex","message":"$ackText"}""")!!
        assertTrue(bare.ack)
        assertEquals(4519, bare.childConversationId)
        assertEquals("codex", bare.agentType)

        // codex code-mode's Promise.allSettled entry around an MCP CallToolResult.
        val settled = DelegationParse.callResult(
            """{"index":2,"status":"fulfilled","value":{"content":[{"type":"text","text":"$ackText"}],""" +
                """"structuredContent":{"task_id":"t2","status":"running","child_conversation_id":4450,"agent_type":"codex"},"isError":false}}""",
        )!!
        assertTrue(settled.ack)
        assertEquals(4450, settled.childConversationId)

        // A host that keeps only the content text.
        val text = DelegationParse.callResult(ackText)!!
        assertTrue("bare ack text is still an ack", text.ack)
        assertFalse(text.structured)
        assertEquals("050a6227-bcb8", text.taskId)
    }

    @Test
    fun `a finished or failed report is the outcome`() {
        val done = DelegationParse.callResult("""{"task_id":"t","status":"completed","text":"All good"}""")!!
        assertFalse(done.ack)
        assertEquals("All good", done.text)
        val failed = DelegationParse.callResult("""{"task_id":"t","status":"failed","error_code":"timeout","message":"Took too long"}""")!!
        assertTrue(failed.isError)
        assertEquals("timeout", failed.errorCode)
        assertEquals("Took too long", failed.text)
        // codex-acp's live `{result, error}` envelope.
        val enveloped = DelegationParse.callResult(
            """{"result":{"content":[{"type":"text","text":"x"}],"structuredContent":{"status":"completed","text":"Done here"}},"error":null}""",
        )!!
        assertEquals("Done here", enveloped.text)
    }

    @Test
    fun `the task id is read from the report, the settled entry, or the ack text`() {
        assertEquals("t1", DelegationParse.taskId("""{"task_id":"t1","status":"running"}"""))
        assertEquals(
            "t2",
            DelegationParse.taskId("""{"index":0,"status":"fulfilled","value":{"content":[],"structuredContent":{"task_id":"t2"}}}"""),
        )
        assertEquals("050a6227-bcb8", DelegationParse.taskId(ackText))
        assertNull(DelegationParse.taskId(""))
    }

    @Test
    fun `a refused resume is told by its code or its opening words`() {
        assertTrue(DelegationParse.refusedResume("""{"task_id":"t","status":"completed","error_code":"not_resumable","message":"Not resumed: done"}"""))
        assertFalse(DelegationParse.refusedResume("""{"task_id":"t","status":"running","child_conversation_id":9}"""))
        assertTrue(DelegationParse.refusedResume("Not resumed: the task already completed."))
        assertFalse(DelegationParse.refusedResume("Delegation resumed: task-7"))
    }

    @Test
    fun `a batch check reads one report per task wherever it sits`() {
        val settled = DelegationParse.statusReports(
            """{"index":0,"status":"fulfilled","value":{"content":[{"type":"text","text":"{}"}],""" +
                """"structuredContent":{"tasks":[{"task_id":"a","status":"unknown","message":"Unknown task id"}]},"isError":false}}""",
        )
        assertEquals(TaskStatus.UNKNOWN, settled.single().status)
        assertEquals("a", settled.single().taskId)

        val embedded = DelegationParse.statusReports(
            """{"content":[{"type":"text","text":"{\"tasks\":[{\"task_id\":\"b\",\"status\":\"completed\",\"agent_type\":\"codex\",""" +
                """\"text\":\"Verdict: APPROVED\",\"duration_ms\":639284,\"progress\":{\"steps\":45,\"marks\":[\"ok\"],\"last_step\":\"nl -ba\\nx\"}}]}"}]}""",
        ).single()
        assertEquals(TaskStatus.COMPLETED, embedded.status)
        assertEquals("Verdict: APPROVED", embedded.text)
        assertEquals(639284L, embedded.durationMs)
        assertEquals("codex", embedded.agentType)
        assertEquals(45, embedded.progress?.steps)
        assertEquals("nl -ba x", embedded.progress?.lastStep)

        val two = DelegationParse.statusReports("""{"tasks":[{"task_id":"a","status":"running"},{"task_id":"b","status":"completed","text":"ok"}]}""")
        assertEquals(listOf("a", "b"), two.map { it.taskId })
    }

    @Test
    fun `the running sentinel is recognised in bare text, and its blocked note`() {
        val running = DelegationParse.statusReport("Running.\nLatest sub-agent reply: thinking")
        assertEquals(TaskStatus.RUNNING, running.status)
        assertFalse(running.blocked)
        val blocked = DelegationParse.statusReport("Running.\nAwaiting your decision: permission")
        assertTrue(blocked.blocked)
        // A completed result that merely opens with "Running." is not a running report.
        assertNull(DelegationParse.statusReport("Running. Then it finished fine.").status)
    }

    @Test
    fun `a check's word follows its report, else its lifecycle`() {
        fun report(status: TaskStatus?, blocked: Boolean = false) = DelegationParse.StatusReport(status = status, blocked = blocked)
        assertEquals(Badge.CHECKED, DelegationParse.badge(false, report(TaskStatus.RUNNING), ToolCallState.DONE, false).badge)
        assertEquals(Badge.WAITING, DelegationParse.badge(false, report(TaskStatus.RUNNING, blocked = true), ToolCallState.DONE, false).badge)
        assertEquals("unknown", DelegationParse.badge(false, report(TaskStatus.UNKNOWN), ToolCallState.DONE, false).errorCode)
        assertEquals(Badge.OK, DelegationParse.badge(true, report(TaskStatus.CANCELED), ToolCallState.DONE, false).badge)
        assertEquals(Badge.ERR, DelegationParse.badge(false, report(TaskStatus.CANCELED), ToolCallState.DONE, false).badge)
        assertEquals(Badge.RUNNING, DelegationParse.badge(false, report(null), ToolCallState.RUNNING, false).badge)
        assertEquals(Badge.ERR, DelegationParse.badge(false, report(null), ToolCallState.DONE, true).badge)
        assertNull("still running", DelegationParse.ending(report(TaskStatus.RUNNING)))
        assertEquals(Badge.OK, DelegationParse.ending(report(TaskStatus.COMPLETED))?.badge)
    }

    private fun poll(id: String, input: String, output: String?, state: ToolCallState = ToolCallState.DONE) =
        ToolCallVM.of(id, "mcp__codeg-mcp__get_delegation_status", "", state, input, output, null, false)

    @Test
    fun `checks fold into one row per task, its newest answer on top`() {
        val rows = DelegationParse.taskRows(
            listOf(
                poll("p1", """{"task_ids":["a"]}""", """{"tasks":[{"task_id":"a","status":"running"}]}"""),
                poll("p2", """{"task_ids":["a","b"]}""", """{"tasks":[{"task_id":"a","status":"completed","text":"A done"},{"task_id":"b","status":"running"}]}"""),
                // Still in flight and naming nothing yet: no anonymous row.
                poll("p3", "{}", null, ToolCallState.RUNNING),
            ).map(DelegationParse::parseCheck),
        )
        assertEquals(listOf("a", "b"), rows.map { it.taskId })
        assertEquals(2, rows[0].checks)
        assertEquals(Badge.OK, rows[0].badge.badge)
        assertEquals("A done", rows[0].report.text)
        assertEquals("p2", rows[0].latestCallId)
        assertEquals(Badge.CHECKED, rows[1].badge.badge)
    }
}
