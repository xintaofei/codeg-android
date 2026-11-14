package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.DelegationBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A delegation's card and its status checks (web `delegation-card.ts`). */
class DelegationTest {

    private fun meta(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun call(id: String, name: String, meta: JsonObject? = null, state: ToolCallState = ToolCallState.DONE) =
        ToolCallVM.of(id, name, "", state, """{"agent_type":"codex","task":"t"}""", null, null, false, meta)

    @Test
    fun `delegate calls and status checks are told by their bare name`() {
        assertTrue(Delegations.isDelegateCall("mcp__codeg-mcp__delegate_to_agent"))
        assertTrue(Delegations.isDelegateCall("codeg-mcp/resume_delegation"))
        assertTrue(Delegations.isStatusPoll("mcp__codeg_mcp__get_delegation_status"))
        assertFalse(Delegations.isDelegateCall("Task"))
    }

    @Test
    fun `a reopened card reads the stamp a detail load put on it`() {
        val view = Delegations.fromMeta(
            meta("""{"codeg.delegation":{"status":"completed","child_conversation_id":42,"agent_type":"codex","task_preview":"Port it","assistant_name":"Reviewer"}}"""),
        )!!
        assertEquals(DelegationBinding.Status.OK, view.status)
        assertEquals(42, view.childConversationId)
        assertEquals("Reviewer", view.assistantName)
        assertNull(Delegations.fromMeta(meta("""{"codeg.delegation":{"status":"weird"}}""")))
        assertNull(Delegations.fromMeta(meta("""{"other":1}""")))
    }

    @Test
    fun `the live binding outranks the stamp and fills from it`() {
        val stamped = call("c", "delegate_to_agent", meta("""{"codeg.delegation":{"status":"running","task_preview":"From the stamp"}}"""))
        val binding = DelegationBinding("c", "child", 9, "claude_code", DelegationBinding.Status.ERR, null, "task", null, errorCode = "timeout")
        val view = Delegations.view(stamped, binding)!!
        assertEquals(DelegationBinding.Status.ERR, view.status)
        assertEquals("claude_code", view.agentType)
        assertEquals("From the stamp", view.task)
        assertEquals("timeout", view.errorCode)
        assertNull(Delegations.view(call("d", "delegate_to_agent"), null))
    }

    @Test
    fun `checks on a delegation fold into one row`() {
        val polls = (1..3).map { call("p$it", "mcp__codeg-mcp__get_delegation_status") }
        val grouped = MessageRender.groupConsecutiveTools(polls.map { RenderPart.Tool(it) })
        assertEquals(1, grouped.size)
        assertEquals(3, (grouped.single() as RenderPart.ToolGroup).items.size)
        assertEquals(StepFamily.DELEGATION_POLL, stepFamily(polls[0]))
        assertNull("the delegation itself stands alone", stepFamily(call("x", "mcp__codeg-mcp__delegate_to_agent")))
    }

    @Test
    fun `a resume card finds its task's binding only on its own evidence`() {
        val original = DelegationBinding("call-orig", "child", 9, "codex", DelegationBinding.Status.RUNNING, "Port it", "task-7", null)
        fun resume(output: String?) = ToolCallVM.of("call-resume", "mcp__codeg-mcp__resume_delegation", "", ToolCallState.DONE, """{"task_id":"task-7","reason":"go on"}""", output, null, false)
        assertEquals(original, Delegations.resumedBinding(resume("""{"child_conversation_id":9,"status":"running"}"""), listOf(original)))
        assertNull("another child", Delegations.resumedBinding(resume("""{"child_conversation_id":10}"""), listOf(original)))
        assertEquals(original, Delegations.resumedBinding(resume("Delegation resumed: task-7"), listOf(original)))
        assertNull("refused", Delegations.resumedBinding(resume("Unknown task id: task-7"), listOf(original)))
        assertNull("not a resume", Delegations.resumedBinding(call("x", "delegate_to_agent"), listOf(original)))
    }
}
