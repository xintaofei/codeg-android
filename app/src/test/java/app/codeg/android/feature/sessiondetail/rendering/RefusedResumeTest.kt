package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `resume_delegation` the broker refused — ported from the web's `delegation-card.test.ts`
 * (`isRefusedResume` and the resume-verdict suites) and the refused-resume case of
 * `build-activity.test.ts`.
 */
class RefusedResumeTest {

    private val resumeName = "mcp__codeg-mcp__resume_delegation"

    private fun resume(output: String?, input: String = """{"task_id":"a1b2c3d4e5"}""", isError: Boolean = false) =
        ToolCallVM.of("r1", resumeName, "", if (isError) ToolCallState.ERROR else ToolCallState.DONE, input, output, null, isError)

    // region isRefusedResume

    @Test
    fun `a refusal is told by its code whatever status it reports`() {
        // `not_resumable_report` reports the task's REAL status: only the code tells.
        for (status in listOf("completed", "running", "canceled")) {
            assertTrue(
                status,
                DelegationParse.refusedResume(
                    """{"task_id":"t-1","status":"$status","error_code":"not_resumable","agent_type":"codex","child_conversation_id":9,"message":"Not resumed: the task already completed."}""",
                ),
            )
        }
    }

    @Test
    fun `a genuine resume ack and an unknown task are no refusal`() {
        assertFalse(
            DelegationParse.refusedResume(
                """{"task_id":"t-1","status":"running","agent_type":"codex","child_conversation_id":9,"message":"Delegation resumed."}""",
            ),
        )
        assertFalse(DelegationParse.refusedResume("""{"task_id":"t-1","status":"unknown","message":"Unknown task id"}"""))
        assertFalse(DelegationParse.refusedResume(null))
    }

    @Test
    fun `a refusal delivered as an error is one`() {
        assertTrue(DelegationParse.refusedResume("""{"task_id":"t-1","status":"failed","error_code":"not_resumable"}"""))
    }

    @Test
    fun `on a host that drops structuredContent the opening words tell`() {
        assertTrue(DelegationParse.refusedResume("Not resumed: the task already completed — resume only applies to a canceled task."))
        // The structured verdict stays authoritative where it survived.
        assertFalse(
            DelegationParse.refusedResume(
                """{"task_id":"t-1","status":"running","agent_type":"codex","child_conversation_id":9,"message":"Not resumed: never mind"}""",
            ),
        )
    }

    @Test
    fun `a child's prose is never read as a verdict`() {
        val prose = "I reviewed the resume path. The broker answers `Not resumed: <why>` when it refuses."
        assertFalse(DelegationParse.refusedResume("""{"task_id":"t-1","status":"completed","agent_type":"codex","child_conversation_id":9,"text":"$prose"}"""))
        assertFalse(DelegationParse.refusedResume(prose))
        // The legacy `{kind}` outcome is a report too: its text never falls through to the prefix.
        assertFalse(DelegationParse.refusedResume("""{"kind":"ok","child_conversation_id":9,"text":"Not resumed: is the phrase the broker uses."}"""))
        assertFalse(DelegationParse.refusedResume("""{"kind":"err","code":"spawn_failed","message":"Not resumed: no."}"""))
    }

    // endregion

    // region the refusal's card

    @Test
    fun `a refusal names its task and the broker's words, wherever the host put them`() {
        val message = "Not resumed: the task already completed."
        // The MCP result `render_task_report` builds: the report in structuredContent.
        val mcp = """{"content":[{"type":"text","text":"$message"}],"isError":false,""" +
            """"structuredContent":{"task_id":"a1b2c3d4e5","status":"completed","error_code":"not_resumable","child_conversation_id":9,"message":"$message"}}"""
        assertEquals(Delegations.ResumeRefusal("a1b2c3d4e5", message), Delegations.resumeRefusal(resume(mcp)))
        // The bare report, and the text a structure-dropping host keeps.
        assertEquals(message, Delegations.resumeRefusal(resume("""{"task_id":"t","status":"running","error_code":"not_resumable","message":"$message"}"""))?.message)
        assertEquals(message, Delegations.resumeRefusal(resume(message))?.message)
        // Through an error, too.
        assertEquals(message, Delegations.resumeRefusal(resume(message, isError = true))?.message)
    }

    @Test
    fun `a resume that went ahead, one in flight, and other calls have no refusal`() {
        assertNull(Delegations.resumeRefusal(resume("""{"task_id":"t","status":"running","child_conversation_id":9}""")))
        assertNull(Delegations.resumeRefusal(resume(null)))
        val delegate = ToolCallVM.of("d1", "mcp__codeg-mcp__delegate_to_agent", "", ToolCallState.DONE, "{}", "Not resumed: x", null, false)
        assertNull(Delegations.resumeRefusal(delegate))
    }

    @Test
    fun `a refused resume draws no sub-agent`() {
        // It still names the agent and the child, which would pass for one that came back.
        val refused = resume("""{"task_id":"a1b2c3d4e5","status":"running","error_code":"not_resumable","agent_type":"codex","child_conversation_id":9,"message":"Not resumed: still running."}""")
        assertNull(Delegations.model(refused, null, null))
        assertTrue(Delegations.drawsCard(refused))
    }

    // endregion

    // region its place in the thread

    private fun delegate(id: String, output: String) =
        ToolCallVM.of(id, "mcp__codeg-mcp__delegate_to_agent", "", ToolCallState.DONE, """{"agent_type":"codex","task":"Review the patch."}""", output, null, false)

    private fun poll(id: String, output: String) =
        ToolCallVM.of(id, "mcp__codeg-mcp__get_delegation_status", "", ToolCallState.DONE, """{"task_ids":["a1b2c3d4"]}""", output, null, false)

    @Test
    fun `a refused resume leaves the checks to the call before it`() {
        for ((ending, badge) in listOf("completed" to DelegationParse.Badge.OK, "failed" to DelegationParse.Badge.ERR)) {
            val refused = ToolCallVM.of(
                "r1", resumeName, "", ToolCallState.DONE, """{"task_id":"a1b2c3d4"}""",
                """{"task_id":"a1b2c3d4","status":"running","error_code":"not_resumable","agent_type":"codex","child_conversation_id":9,"message":"Not resumed: the task is still running."}""",
                null, false,
            )
            val fold = DelegationTaskFold()
            listOf(
                delegate("d1", """{"task_id":"a1b2c3d4","status":"started"}"""),
                refused,
                poll("d2", """{"task_id":"a1b2c3d4","status":"$ending","text":"Done."}"""),
            ).forEach { fold.add(it, emptyMap()) }
            val index = fold.index()
            assertEquals(ending, badge, index.byCall["d1"]?.badge?.badge)
            assertNull(ending, index.byCall["r1"])
        }
    }

    // endregion
}
