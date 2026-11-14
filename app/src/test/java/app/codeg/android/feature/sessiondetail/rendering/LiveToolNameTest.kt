package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from the web's `tool-call-normalization.test.ts` (`inferLiveToolName`). */
class LiveToolNameTest {

    private fun meta(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun infer(title: String?, kind: String?, rawInput: String?, meta: String? = null) =
        LiveToolName.infer(title, kind, rawInput, meta?.let(::meta))

    @Test
    fun `the SDK Agent tool is an agent before its input streams in`() {
        assertEquals("agent", infer("Explore the codebase", "other", null, """{"claudeCode":{"toolName":"Agent"}}"""))
    }

    @Test
    fun `subagent_type keeps a Task an agent, its meta name notwithstanding`() {
        assertEquals(
            "agent",
            infer("Implement feature X", "other", """{"subagent_type":"general-purpose","prompt":"Do the thing"}""", """{"claudeCode":{"toolName":"Task"}}"""),
        )
    }

    @Test
    fun `the subagent marker classifies ahead of any input shape`() {
        assertEquals("agent", infer("Implement feature X", "other", null, """{"claudeCode":{"toolName":"Task","subagent":true}}"""))
        assertEquals("agent", infer("Run checks", "other", """{"command":"pnpm test"}""", """{"claudeCode":{"toolName":"Agent","subagent":true}}"""))
        // Strictly `true`.
        assertEquals("bash", infer("bash", "execute", """{"command":"ls"}""", """{"claudeCode":{"toolName":"Bash","subagent":"yes"}}"""))
    }

    @Test
    fun `the meta name fallback is lower-cased, never rewritten`() {
        assertEquals("memory_recall", infer("Recalled 3 memories", "read", null, """{"claudeCode":{"toolName":"memory_recall"}}"""))
    }

    @Test
    fun `delegation companions resolve from meta over their input shape`() {
        assertEquals(
            "get_delegation_status",
            infer("mcp__codeg-delegate__get_delegation_status", "other", """{"task_ids":["t1"],"wait_ms":1000}""", """{"claudeCode":{"toolName":"mcp__codeg-delegate__get_delegation_status"}}"""),
        )
        assertEquals(
            "cancel_delegation",
            infer("mcp__codeg-delegate__cancel_delegation", "other", """{"task_id":"t1"}""", """{"claudeCode":{"toolName":"mcp__codeg-delegate__cancel_delegation"}}"""),
        )
        // Qoder's frame, verbatim: the title is a sentence no rule collapses.
        assertEquals(
            "get_delegation_status",
            infer("get_delegation_status (codeg-mcp MCP Server)", "other", """{"task_ids":["081139e7"]}""", """{"qoder":{"toolName":"mcp__codeg-mcp__get_delegation_status"}}"""),
        )
    }

    @Test
    fun `a Grok companion resolves from its unwrapped title`() {
        assertEquals("cancel_delegation", infer("codeg-mcp__cancel_delegation", "other", """{"task_id":"t1"}"""))
        // Without a prefix-free end, the sentence is not one.
        assertEquals("task", infer("cancel_delegation (codeg-mcp MCP Server)", "other", """{"task_id":"t1"}"""))
    }

    @Test
    fun `the broker's marker names a delegation on an identity-less wire`() {
        assertEquals("delegate_to_agent", infer("MCP: tool", "other", "{}", """{"codeg.delegation":{"status":"running"}}"""))
        assertNotEquals("delegate_to_agent", infer("MCP: tool", "other", "{}"))
    }

    @Test
    fun `a live Monitor is a monitor, not the command its input carries`() {
        assertEquals("monitor", infer("tail -f log", "other", """{"command":"tail -f log"}""", """{"claudeCode":{"toolName":"Monitor"}}"""))
    }

    @Test
    fun `a task_id without Claude Code meta is still a task`() {
        assertEquals("task", infer("Some task", "other", """{"task_id":"t1"}"""))
    }

    @Test
    fun `the backend's agent sentinel wins, a title that only mentions it doesn't`() {
        assertEquals("agent", infer(" Agent ", "other", """{"command":"x"}"""))
        assertEquals("bash", infer("Inspect agent config", "other", """{"command":"x"}"""))
    }

    @Test
    fun `cursor's task tool is an agent from its bare stamp`() {
        assertEquals("agent", infer("Task: Subagent task", "other", """{"_toolName":"task"}"""))
        assertEquals("agent", infer("Task: run the build", "other", """{"_toolName":"task","subagentType":{"case":"generalPurpose"}}"""))
        assertEquals("create_plan", infer("Plan", "other", """{"_toolName":"createPlan"}"""))
    }

    @Test
    fun `input-shape keeps priority over the Qoder and Grok names`() {
        assertEquals("bash", infer("pnpm build", "execute", """{"command":"pnpm build"}""", """{"qoder":{"toolName":"Bash"}}"""))
        assertEquals("edit", infer("Edit", "other", """{"old_string":"a","new_string":"b"}""", """{"x.ai/tool":{"name":"search_replace"}}"""))
        assertEquals("get_command_or_subagent_output", infer("Get task output", "other", null, """{"x.ai/tool":{"name":"get_command_or_subagent_output"}}"""))
        // Grok's generic MCP envelope names nothing.
        assertNull(infer("Some MCP call", "other", null, """{"x.ai/tool":{"name":"use_tool"}}"""))
    }

    @Test
    fun `OpenCode and Kiro stamps outrank their ambiguous input shapes`() {
        assertEquals("glob", infer("Glob", "other", """{"pattern":"*.kt"}""", """{"opencode":{"toolName":"glob"}}"""))
        assertEquals("mcp__fs__list", infer("Running: @fs/list", "other", """{"path":"."}""", """{"kiro":{"toolName":"x","canonicalToolName":"mcp__fs__list"}}"""))
    }

    @Test
    fun `query is a web search only when the wire also says so`() {
        assertEquals("websearch", infer("Web search: kotlin", "search", """{"query":"kotlin"}"""))
        assertEquals("websearch", infer("Searching the web for: \"kotlin\"", "search", """{"query":"kotlin"}"""))
        assertEquals("websearch", infer("x", "search", """{"query":"kotlin","type":"webSearch"}"""))
        assertNull(infer("codegraph_explore", "other", """{"query":"auth flow"}"""))
    }

    @Test
    fun `a path-bearing input reads, writes or follows its kind`() {
        assertEquals("write", infer("x", "edit", """{"file_path":"a.kt","content":"x"}"""))
        assertEquals("edit", infer("x", "edit", """{"file_path":"a.kt"}"""))
        assertEquals("edit", infer("Edit a.kt", "other", """{"path":"a.kt"}"""))
        assertEquals("read", infer("x", "other", """{"filePath":"a.kt"}"""))
    }

    @Test
    fun `a bare command line is a command only where kind or title says shell`() {
        assertEquals("bash", infer("x", "execute", "ls -la"))
        assertEquals("bash", infer("x", "", "ls -la"))
        assertNull(infer("x", "other", "ls -la"))
        assertEquals("apply_patch", infer("x", "other", "*** Begin Patch\n*** End Patch"))
    }

    @Test
    fun `nothing stated leaves the title to stand`() {
        assertNull(infer("Explore the codebase", "think", null))
        assertNull(infer("Read file", "read", "{}"))
    }

    // How the live card is bucketed from it ([ToolCallVM.of]).

    private fun liveVm(title: String, kind: String, rawInput: String?, meta: String?): ToolCallVM {
        val m = meta?.let(::meta)
        return ToolCallVM.of("c1", title, kind, ToolCallState.RUNNING, rawInput, null, null, false, m, LiveToolName.infer(title, kind, rawInput, m))
    }

    @Test
    fun `a live Agent launch is a running task, as its persisted copy is`() {
        val vm = liveVm("Explore the codebase", "think", null, """{"claudeCode":{"toolName":"Agent"}}""")
        assertEquals(ToolKindBucket.TASK, vm.bucket)
        // The title is not rewritten by the identity behind it.
        assertEquals("Explore the codebase", vm.rawName)
        val persisted = ToolCallVM.of("c1", "Agent", "", ToolCallState.DONE, """{"description":"Explore the codebase","subagent_type":"Explore"}""", null, null, false)
        assertEquals(persisted.bucket, vm.bucket)
    }

    @Test
    fun `a title that classifies keeps its bucket`() {
        // The ACP kind and a title in the canonical sets decide as before, whatever the input.
        assertEquals(ToolKindBucket.EXECUTE, liveVm("Run tests", "execute", """{"subagent_type":"x"}""", null).bucket)
        assertEquals(ToolKindBucket.READ, liveVm("read_file", "other", """{"command":"x"}""", null).bucket)
        // A codex command action is read from its title alone.
        assertEquals(ToolKindBucket.SEARCH, liveVm("Search for 'TODO'", "search", null, null).bucket)
    }
}
