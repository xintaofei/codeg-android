package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.DelegationBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A delegation's card model, and the thread-wide index that hands it the checks on its task. */
class DelegationModelTest {

    private val delegateName = "mcp__codeg-mcp__delegate_to_agent"
    private val pollName = "mcp__codeg-mcp__get_delegation_status"

    private fun delegate(id: String, output: String?, meta: String? = null, input: String = """{"agent_type":"codex","task":"Review it","working_dir":"/w"}""") =
        ToolCallVM.of(id, delegateName, "", ToolCallState.DONE, input, output, null, false, meta?.let { Json.parseToJsonElement(it).jsonObject })

    private fun poll(id: String, taskId: String, output: String) =
        ToolCallVM.of(id, pollName, "", ToolCallState.DONE, """{"task_ids":["$taskId"]}""", output, null, false)

    private fun index(vararg calls: ToolCallVM, bindings: Map<String, DelegationBinding> = emptyMap()): DelegationTaskIndex =
        DelegationTaskFold().also { fold -> calls.forEach { fold.add(it, bindings) } }.index()

    @Test
    fun `a delegation reads how it ended off the newest check on its task`() {
        val call = delegate("d1", """{"task_id":"t1","status":"running","child_conversation_id":42,"agent_type":"codex"}""")
        val idx = index(
            call,
            poll("p1", "t1", """{"tasks":[{"task_id":"t1","status":"running","progress":{"steps":3,"last_step":"ls"}}]}"""),
            poll("p2", "t1", """{"tasks":[{"task_id":"t1","status":"completed","text":"Verdict: APPROVED","duration_ms":90000,"progress":{"steps":9}}]}"""),
        )
        val model = Delegations.model(call, null, idx.byCall["d1"])!!
        assertEquals(DelegationModel.Status.OK, model.status)
        assertEquals("Verdict: APPROVED", model.outcome)
        assertEquals(90000L, model.durationMs)
        assertEquals(9, model.progress?.steps)
        assertEquals("t1", model.taskId)
        assertEquals(42, model.childConversationId)
        assertEquals("codex", model.agentType)
        assertEquals("/w", model.workingDir)
        assertEquals("codex", idx.agentByTask["t1"])
    }

    @Test
    fun `with nothing newer than the ack it is still running, and the ack is no result`() {
        val call = delegate("d1", "Delegation successful. task_id=t1. Call get_delegation_status.")
        val model = Delegations.model(call, null, null)!!
        assertEquals(DelegationModel.Status.RUNNING, model.status)
        assertNull(model.outcome)
        assertEquals("t1", model.taskId)
    }

    @Test
    fun `the stamp's status wins, but bare ack text is never its result`() {
        val call = delegate(
            "d1",
            "Delegation successful. task_id=t1.",
            meta = """{"codeg.delegation":{"status":"failed","child_conversation_id":7,"agent_type":"deepseek","task_id":"t1"}}""",
        )
        val model = Delegations.model(call, null, null)!!
        assertEquals(DelegationModel.Status.ERR, model.status)
        assertNull(model.outcome)
        assertEquals(7, model.childConversationId)
    }

    @Test
    fun `the live binding outranks everything, and a later check parked on the user waits`() {
        val call = delegate("d1", """{"task_id":"t1","status":"running"}""")
        val binding = DelegationBinding("d1", "child", 5, "claude_code", DelegationBinding.Status.RUNNING, "task", "t1", "Reviewer")
        val later = index(call, poll("p1", "t1", """{"tasks":[{"task_id":"t1","status":"running","blocked_on":{"kind":"permission"}}]}""")).byCall["d1"]
        val model = Delegations.model(call, binding, later)!!
        assertEquals(DelegationModel.Status.WAITING, model.status)
        assertEquals("claude_code", model.agentType)
        assertEquals("Reviewer", model.assistantName)
    }

    @Test
    fun `a resume takes over the checks that follow it`() {
        val first = delegate("d1", """{"task_id":"t1","status":"running"}""")
        val resume = ToolCallVM.of(
            "r1", "mcp__codeg-mcp__resume_delegation", "", ToolCallState.DONE,
            """{"task_id":"t1","reason":"interrupted"}""", """{"task_id":"t1","status":"running","child_conversation_id":9,"agent_type":"codex"}""", null, false,
        )
        val idx = index(
            first,
            poll("p1", "t1", """{"tasks":[{"task_id":"t1","status":"failed","error_code":"canceled"}]}"""),
            resume,
            poll("p2", "t1", """{"tasks":[{"task_id":"t1","status":"completed","text":"Back and done"}]}"""),
        )
        assertEquals(DelegationParse.Badge.ERR, idx.byCall["d1"]?.badge?.badge)
        val model = Delegations.model(resume, null, idx.byCall["r1"])!!
        assertTrue(model.resumed)
        assertEquals("interrupted", model.reason)
        assertEquals(DelegationModel.Status.OK, model.status)
        assertEquals("Back and done", model.outcome)
        assertEquals("codex", model.agentType)
    }

    @Test
    fun `a refused resume and a call with nothing to go by draw no delegation`() {
        val refused = ToolCallVM.of(
            "r1", "mcp__codeg-mcp__resume_delegation", "", ToolCallState.DONE,
            """{"task_id":"t1"}""", "Not resumed: the task already completed.", null, false,
        )
        assertNull(Delegations.model(refused, null, null))
        assertNull(Delegations.model(delegate("d", null, input = "{}"), null, null))
    }

    @Test
    fun `a check on a task handed over before the thread began belongs to no call`() {
        val idx = index(poll("p0", "t9", """{"tasks":[{"task_id":"t9","status":"completed","agent_type":"gemini"}]}"""))
        assertTrue(idx.byCall.isEmpty())
        assertEquals("gemini", idx.agentByTask["t9"])
    }

    @Test
    fun `the live stage folds onto a copy, leaving the persisted fold as it was`() {
        val call = delegate("d1", """{"task_id":"t1","status":"running"}""")
        val persisted = DelegationTaskFold().also { it.add(call, emptyMap()) }
        val live = persisted.copy().also { it.add(poll("p1", "t1", """{"tasks":[{"task_id":"t1","status":"completed"}]}"""), emptyMap()) }
        assertEquals(DelegationParse.Badge.OK, live.index().byCall["d1"]?.badge?.badge)
        assertNull(persisted.index().byCall["d1"])
    }
}
