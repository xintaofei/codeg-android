package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply's activity: which calls fold into one row, and what each call's outcome was. */
class ActivityTest {

    private fun vm(
        id: String,
        name: String,
        input: String? = """{"x":"$id"}""",
        state: ToolCallState = ToolCallState.DONE,
        output: String? = null,
    ) = ToolCallVM.of(id, name, "", state, input, output, null, state == ToolCallState.ERROR)

    private fun tool(id: String, name: String, input: String? = """{"x":"$id"}""", state: ToolCallState = ToolCallState.DONE, output: String? = null) =
        RenderPart.Tool(vm(id, name, input, state, output))

    /** Each row: a group's member ids joined with `+`, a lone call's id, or `text`. */
    private fun rows(parts: List<RenderPart>): List<String> = MessageRender.groupConsecutiveTools(parts).map {
        when (it) {
            is RenderPart.ToolGroup -> it.items.joinToString("+") { vm -> vm.id }
            is RenderPart.Tool -> it.vm.id
            else -> "text"
        }
    }

    @Test
    fun `explorations fold together, and so do commands, each on their own`() {
        val parts = listOf(
            tool("r", "Read"), tool("g", "Grep"), tool("l", "ls"), tool("w", "WebFetch"),
            tool("b1", "Bash"), tool("b2", "Bash"),
        )
        assertEquals(listOf("r+g+l+w", "b1+b2"), rows(parts))
    }

    @Test
    fun `an edit, an MCP call, a sub-agent or prose ends a run`() {
        val parts = listOf(
            tool("r1", "Read"), tool("e", "Edit"), tool("r2", "Read"),
            tool("b1", "Bash"), tool("m", "mcp__db__query"), tool("b2", "Bash"),
            tool("g1", "Grep"), tool("t", "Task"), tool("g2", "Grep"),
            RenderPart.Text("looking"), tool("r3", "Read"),
        )
        assertEquals(listOf("r1", "e", "r2", "b1", "m", "b2", "g1", "t", "g2", "text", "r3"), rows(parts))
    }

    @Test
    fun `a lone call stays a plain row`() {
        assertEquals(listOf("r", "b"), rows(listOf(tool("r", "Read"), tool("b", "Bash"))))
    }

    @Test
    fun `a failed call stays inside its run`() {
        val parts = listOf(tool("b1", "Bash", state = ToolCallState.ERROR, output = "exit 1"), tool("b2", "Bash"))
        val group = MessageRender.groupConsecutiveTools(parts).single() as RenderPart.ToolGroup
        assertEquals(listOf("b1", "b2"), group.items.map { it.id })
    }

    @Test
    fun `a call interrupted before it carried anything is left out`() {
        val parts = listOf(
            tool("b1", "Bash"),
            tool("x", "Bash", input = "{}", state = ToolCallState.ERROR, output = "[Request interrupted by user for tool use]"),
            tool("b2", "Bash"),
        )
        // It doesn't split the run either.
        assertEquals(listOf("b1+b2"), rows(parts))
        // One that had arguments stays.
        val kept = tool("y", "Bash", state = ToolCallState.ERROR, output = "[Request interrupted by user for tool use]")
        assertEquals(listOf("y"), rows(listOf(kept)))
    }

    @Test
    fun `a declined or stopped call is not a failure of the agent's`() {
        assertEquals(StepStatus.CANCELLED, stepStatus(vm("a", "Bash", state = ToolCallState.ERROR, output = "[Request interrupted by user]")))
        assertEquals(
            StepStatus.REJECTED,
            stepStatus(vm("b", "Bash", state = ToolCallState.ERROR, output = "The user doesn't want to proceed with this tool use.")),
        )
        assertEquals(StepStatus.REJECTED, stepStatus(vm("c", "Edit", state = ToolCallState.ERROR, output = "Edit denied by the user")))
        assertEquals(StepStatus.FAILED, stepStatus(vm("d", "Bash", state = ToolCallState.ERROR, output = "exit status 2")))
        assertEquals(StepStatus.RUNNING, stepStatus(vm("e", "Bash", state = ToolCallState.RUNNING)))
        assertEquals(StepStatus.SUCCEEDED, stepStatus(vm("f", "Bash")))
    }

    @Test
    fun `families come from what the call was`() {
        assertEquals(StepFamily.EXPLORE, stepFamily(vm("a", "Glob")))
        assertEquals(StepFamily.EXPLORE, stepFamily(vm("b", "WebSearch")))
        assertEquals(StepFamily.COMMAND, stepFamily(vm("c", "exec_command")))
        assertEquals(null, stepFamily(vm("d", "browser_action")))
        assertEquals(null, stepFamily(vm("e", "TodoWrite")))
        assertTrue(stepFamily(vm("f", "Read")) != stepFamily(vm("g", "Bash")))
        assertFalse(isBlankInterruption(vm("h", "Bash", input = null)))
    }
}
