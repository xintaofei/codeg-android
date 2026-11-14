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

    private fun failed(id: String, name: String, input: String, output: String = "exit 1") =
        tool(id, name, input, ToolCallState.ERROR, output)

    @Test
    fun `a failure redone successfully later is recovered`() {
        val parts = listOf(
            failed("b1", "Bash", """{"command":"npm  test"}"""),
            tool("r", "Read", """{"file_path":"a"}"""),
            // The same command line, however it is spaced.
            tool("b2", "Bash", """{"command":"npm test"}"""),
        )
        val summary = summarizeActivity(parts)
        assertEquals(3, summary.steps)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.unrecovered)
        assertEquals(null, summary.firstUnrecoveredId)
    }

    @Test
    fun `a failure nothing redid names the first one`() {
        val parts = listOf(
            tool("b0", "Bash", """{"command":"make"}"""),
            failed("e1", "Edit", """{"file_path":"a.kt"}"""),
            failed("b1", "Bash", """{"command":"make"}"""),
            // Another command is not a retry.
            tool("b2", "Bash", """{"command":"make clean"}"""),
        )
        val summary = summarizeActivity(parts)
        assertEquals(2, summary.unrecovered)
        assertEquals("e1", summary.firstUnrecoveredId)
    }

    @Test
    fun `a stopped call is interrupted, a todo list no step`() {
        val parts = listOf(
            tool("t", "TodoWrite", """{"todos":[]}"""),
            failed("x", "Bash", "{}", "[Request interrupted by user for tool use]"),
            tool("b", "Bash", """{"command":"ls"}"""),
        )
        val summary = summarizeActivity(parts)
        // Counted though grouping drops it: it is still something the reply did.
        assertEquals(2, summary.steps)
        assertEquals(1, summary.interrupted)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `the answer is what follows the last step`() {
        val text = RenderPart.Text("done")
        assertEquals(2, answerStart(listOf(RenderPart.Text("looking"), tool("r", "Read"), text)))
        // Prose between steps stays with them.
        assertEquals(3, answerStart(listOf(RenderPart.Text("a"), tool("r", "Read"), RenderPart.Reasoning("hm"))))
        // Words alone are all answer.
        assertEquals(0, answerStart(listOf(text)))
    }

    @Test
    fun `an MCP tool stands alone, whatever its bare name says`() {
        val parts = listOf(tool("r1", "Read"), tool("m", "mcp__filesystem__read_file"), tool("r2", "Read"))
        assertEquals(listOf("r1", "m", "r2"), rows(parts))
        assertEquals(listOf("g", "m2", "g2"), rows(listOf(tool("g", "Grep"), tool("m2", "github/search_code"), tool("g2", "Grep"))))
        // A host's own namespaced built-in is not an MCP server's.
        assertFalse(isMcpCall(vm("f", "functions.exec_command")))
        // Nor a live call its host titled by a file name, having said what kind of call it is.
        assertFalse(isMcpCall(ToolCallVM.of("t", "README.md", "read", ToolCallState.DONE, null, null, null, false)))
    }

    @Test
    fun `only the same call redone recovers a failure`() {
        // An MCP read of the same path with other arguments is another call.
        val mcp = listOf(
            failed("m1", "mcp__fs__read_file", """{"path":"a","line":1}"""),
            tool("m2", "mcp__fs__read_file", """{"path":"a","line":9}"""),
        )
        assertEquals(1, summarizeActivity(mcp).unrecovered)
        // Two scripts of different source share the title "Script", not a signature.
        val scripts = listOf(
            failed("s1", "codex_script", """{"source":"await tools.a()"}"""),
            tool("s2", "codex_script", """{"source":"await tools.b()"}"""),
        )
        assertEquals("s1", summarizeActivity(scripts).firstUnrecoveredId)
        val rerun = scripts.take(1) + tool("s3", "codex_script", """{"source":"await  tools.a()"}""")
        assertEquals(0, summarizeActivity(rerun).unrecovered)
        // A command persisted as its bare text is retried by that text.
        val bare = listOf(failed("b1", "exec_command", "make  test"), tool("b2", "exec_command", "make test"))
        assertEquals(0, summarizeActivity(bare).unrecovered)
    }

    @Test
    fun `a search that found nothing is no failure in the header`() {
        val parts = listOf(
            RenderPart.Tool(ToolCallVM.of("g", "grep", "", ToolCallState.ERROR, """{"pattern":"x"}""", """{"formatted_output":"","exit_code":1}""", null, true)),
        )
        assertEquals(0, summarizeActivity(parts).failed)
    }
}

