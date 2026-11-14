package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two synthetic identities codex code mode sends, plus the plan document.
 *
 * The server recovers the inner tool calls; what reaches the client is the
 * residue it could not decompose (a JS program) and the session tools that
 * address a background shell by id. Both look enough like a shell command to be
 * collapsed into `bash` by the generic rules, which renders a JavaScript
 * program as a shell line and titles a poll "wait: 7106".
 */
class CodexCodeModeTest {

    private fun vm(name: String, input: String?) = ToolCallVM.of(
        id = "t",
        rawName = name,
        kind = "",
        state = ToolCallState.DONE,
        input = input,
        output = null,
        content = null,
        isError = false,
    )

    @Test
    fun `an undecomposable script keeps its own identity`() {
        val call = vm(
            CodexCodeMode.SCRIPT_TOOL_NAME,
            """{"source":"const r = await tools.exec_command({cmd})","call_count":3}""",
        )

        assertEquals(CodexCodeMode.SCRIPT_TOOL_NAME, call.rawName)
        // A program counts with the commands in a group's tally.
        assertEquals(ToolKindBucket.EXECUTE, call.bucket)
        assertEquals("Script (3 tool calls)", call.displayTitle)
    }

    @Test
    fun `a script the parser could read is titled by that command`() {
        val call = vm(
            CodexCodeMode.SCRIPT_TOOL_NAME,
            """{"source":"…","title":"npm run build","call_count":1}""",
        )

        assertEquals("npm run build", call.displayTitle)
    }

    @Test
    fun `a wait is titled by the command whose session it addresses`() {
        assertEquals(
            "Wait npm run dev",
            vm(CodexCodeMode.WAIT_TOOL_NAME, """{"cell_id":7106,"session_command":"npm run dev"}""").displayTitle,
        )
        // Unresolved id: the card names it, and so does the command that
        // started the session, so the two can be matched up by eye.
        assertEquals(
            "Wait cell 7106",
            vm(CodexCodeMode.WAIT_TOOL_NAME, """{"cell_id":7106,"yield_time_ms":30000}""").displayTitle,
        )
        assertEquals(
            "Terminate npm run dev",
            vm(
                CodexCodeMode.WAIT_TOOL_NAME,
                """{"cell_id":7106,"session_command":"npm run dev","terminate":true}""",
            ).displayTitle,
        )
    }

    @Test
    fun `keystrokes are named rather than left invisible`() {
        // The payload is usually a control character, invisible in a title.
        assertEquals("^C", CodexCodeMode.describeStdinChars("\u0003"))
        assertEquals("\u23CE", CodexCodeMode.describeStdinChars("\n"))
        assertEquals("y\u23CE", CodexCodeMode.describeStdinChars("y\n"))
        assertEquals(
            "Stdin ^C",
            vm(CodexCodeMode.WRITE_STDIN_TOOL_NAME, """{"session_id":"7106","chars":"\u0003"}""").displayTitle,
        )
        // A write_stdin that only collects more output sends nothing to name.
        assertEquals(
            "Wait cell 7106",
            vm(CodexCodeMode.WRITE_STDIN_TOOL_NAME, """{"session_id":"7106","chars":""}""").displayTitle,
        )
    }

    @Test
    fun `terminate must be a boolean, not the string`() {
        // `{"terminate":"true"}` is a value some other tool could plausibly send;
        // reading it as the flag would mislabel a poll as a kill.
        val call = CodexCodeMode.parseSessionCall(ToolDerive.parseJson("""{"cell_id":"1","terminate":"true"}"""))
        assertEquals(false, requireNotNull(call).terminate)
    }

    @Test
    fun `a plan document is found wherever the host put it`() {
        assertEquals("# Plan\n1. do it", planMarkdown("""{"plan":"# Plan\n1. do it"}"""))
        assertEquals("nested", planMarkdown("""{"rawInput":{"plan":"nested"}}"""))
        assertEquals("snake", planMarkdown("""{"raw_input":{"Plan":"snake"}}"""))
        // A bare mode signal has no document, and must not get a plan card.
        assertNull(planMarkdown("""{"mode":"plan"}"""))
        assertNull(planMarkdown("""{"plan":"   "}"""))
    }

    @Test
    fun `a script payload is only a script when the tool says so`() {
        // Gated on the identity, not the shape: `{"source": …}` is an ordinary
        // argument name, and another execute tool using it would otherwise
        // render as JavaScript with its remaining arguments dropped.
        val other = vm("run_sql", """{"source":"SELECT 1","timeout":30}""")

        assertEquals("run_sql", other.rawName)
        assertNull(CodexCodeMode.parseScript(ToolDerive.parseJson("""{"command":"ls"}""")))
    }
    @Test
    fun `an MCP tool that happens to be called wait is not a codex shell session`() {
        // `wait` and `write_stdin` are ordinary words. Every other tool name is
        // matched after the `mcp__<server>__` prefix is stripped, and doing that
        // here hands a database tool codex's session card: EXECUTE bucket, and
        // a "Wait cell db-7" title read off its own `session_id`.
        val args = ToolDerive.parseJson("""{"session_id":"db-7","query":"select 1"}""")

        assertFalse(CodexCodeMode.isShellSessionName("mcp__database__wait"))
        assertFalse(CodexCodeMode.isScriptName("mcp__sandbox__codex_script"))
        assertEquals(ToolKindBucket.OTHER, ToolDerive.bucket("mcp__database__wait", ""))
        assertNotEquals("Wait cell db-7", ToolDerive.title("mcp__database__wait", args))

        // codex's own, unprefixed, still resolves.
        assertTrue(CodexCodeMode.isShellSessionName("wait"))
        assertEquals(ToolKindBucket.EXECUTE, ToolDerive.bucket("wait", ""))
        assertEquals("Wait cell db-7", ToolDerive.title("wait", args))
    }
}
