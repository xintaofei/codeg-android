package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.feature.sessiondetail.rendering.BackgroundTaskPolls.Badge
import app.codeg.android.feature.sessiondetail.rendering.BackgroundTaskPolls.Envelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the web's `background-task.test.ts` and `background-task-grouping.test.ts` (codeg 0.44). */
class BackgroundTaskPollsTest {

    private val completed = """<retrieval_status>success</retrieval_status>

<task_id>bfb5xnq1t</task_id>

<task_type>local_bash</task_type>

<status>completed</status>

<exit_code>0</exit_code>

<output>
running 0 tests
test result: ok. 0 passed
</output>"""

    private val running = """<retrieval_status>timeout</retrieval_status>

<task_id>bfb5xnq1t</task_id>

<task_type>local_bash</task_type>

<status>running</status>"""

    private val failed = """<retrieval_status>success</retrieval_status>

<task_id>bxx</task_id>

<task_type>local_bash</task_type>

<status>completed</status>

<exit_code>1</exit_code>

<output>error: build failed</output>"""

    private val stopJson = buildJsonObject {
        put("message", "Successfully stopped task: bebna5yf2 (cd /x; echo hi)")
        put("task_id", "bebna5yf2")
        put("task_type", "local_bash")
        put("command", "cd /x; echo hi")
    }.toString()

    private val launch =
        "Command running in background with ID: be7lh91re. Output is being written to: /private/tmp/x/tasks/be7lh91re.output. You will be notified when it completes."

    private val grokLaunch = "Background task term_b0d9512484964551a5bac4f82a805ae2 started"

    private val grokFailed = buildJsonObject {
        put("type", "TaskOutput")
        putJsonObject("Result") {
            put("task_id", "term_b0d")
            put("command", "/bin/bash -lc 'pnpm dev -- --port 3001'")
            put("status", "failed")
            put("exit_code", 1)
            put("started", "2026-07-30T12:52:48Z")
            put("ended", "2026-07-30T12:52:49Z")
            put("duration_secs", 0.787718)
            put("output", "\$ next dev --turbopack -- --port 3001\nInvalid project directory provided, no such directory: /Users/me/proj/--port\n")
            put("truncated", false)
        }
    }.toString()

    private val grokRunning = buildJsonObject {
        put("type", "TaskOutput")
        putJsonObject("Result") {
            put("task_id", "term_273")
            put("command", "/bin/bash -lc 'pnpm exec next dev -p 3001'")
            put("status", "running")
            put("exit_code", JsonNull)
            put("output", "▲ Next.js 16.0.0\n- Local: http://localhost:3001\n✓ Ready in 1.2s\n")
        }
    }.toString()

    private val pollInput = """{"task_id":"bfb5xnq1t","block":true,"timeout":240000}"""

    /** The web's `poll()`: a settled `TaskOutput` of the completed task. */
    private fun poll(
        id: String = "c1",
        name: String = "TaskOutput",
        input: String? = pollInput,
        output: String? = completed,
        state: ToolCallState = ToolCallState.DONE,
    ) = ToolCallVM.of(id, name, "", state, input, output, null, state == ToolCallState.ERROR)

    /** A Grok `get_command_or_subagent_output` poll of the failed dev-server task. */
    private fun grokPoll(
        id: String = "c1",
        output: String? = grokFailed,
        state: ToolCallState = ToolCallState.DONE,
    ) = poll(id, "get_command_or_subagent_output", """{"task_ids":["term_b0d"],"timeout_ms":15000}""", output, state)

    // region parseEnvelope

    @Test
    fun `parses a completed poll envelope`() {
        val env = BackgroundTaskPolls.parseEnvelope(completed)!!
        assertEquals(Envelope.Kind.POLL, env.kind)
        assertEquals("bfb5xnq1t", env.taskId)
        assertEquals("local_bash", env.taskType)
        assertEquals("completed", env.status)
        assertEquals(0, env.exitCode)
        assertTrue(env.output!!.contains("test result: ok"))
    }

    @Test
    fun `parses a still-running poll, no exit code or output`() {
        val env = BackgroundTaskPolls.parseEnvelope(running)!!
        assertEquals(Envelope.Kind.POLL, env.kind)
        assertEquals("running", env.status)
        assertEquals("timeout", env.retrievalStatus)
        assertNull(env.exitCode)
        assertNull(env.output)
    }

    @Test
    fun `parses a non-zero exit code`() {
        assertEquals(1, BackgroundTaskPolls.parseEnvelope(failed)!!.exitCode)
    }

    @Test
    fun `parses a TaskStop ack`() {
        val env = BackgroundTaskPolls.parseEnvelope(stopJson)!!
        assertEquals(Envelope.Kind.STOP, env.kind)
        assertEquals("bebna5yf2", env.taskId)
        assertEquals("cd /x; echo hi", env.command)
        assertEquals("stopped", env.status)
    }

    @Test
    fun `returns null for unrelated text and for the launch notice`() {
        assertNull(BackgroundTaskPolls.parseEnvelope("just some text"))
        assertNull(BackgroundTaskPolls.parseEnvelope(launch))
        assertNull(BackgroundTaskPolls.parseEnvelope("""{"tasks":[]}"""))
        assertNull(BackgroundTaskPolls.parseEnvelope(null))
    }

    @Test
    fun `keeps output holding angle brackets intact, to the last close`() {
        val env = BackgroundTaskPolls.parseEnvelope(
            "<retrieval_status>success</retrieval_status>\n<task_id>z</task_id>\n<status>completed</status>\n<exit_code>0</exit_code>\n<output>a <b> c </output>",
        )!!
        assertEquals("a <b> c ", env.output)
    }

    // endregion

    // region parseLaunch

    @Test
    fun `extracts the task id from a background launch result`() {
        assertEquals("be7lh91re", BackgroundTaskPolls.parseLaunch(launch))
    }

    @Test
    fun `recognizes every wording Claude Code uses for a command moved to the background`() {
        val steered =
            "Command was moved to the background (ID: bj0x9uqsf) so that a message that arrived while it was running can reach you; it was not interrupted. Output is being written to: /private/tmp/x/tasks/bj0x9uqsf.output. You will be notified when it completes. If it is still running after 30m in the background, it will be stopped and you will be notified. To check interim output, use Read on that file path."
        assertEquals("bj0x9uqsf", BackgroundTaskPolls.parseLaunch(steered))
        assertEquals("bj0x9uqsf", BackgroundTaskPolls.parseLaunch("```console\n$steered\n```"))
        assertEquals(
            "b3k9x2m1q",
            BackgroundTaskPolls.parseLaunch(
                "Command did not complete within its 120s timeout and was moved to the background (ID: b3k9x2m1q). Output is being written to: /tmp/tasks/b3k9x2m1q.output. You will be notified when it completes.",
            ),
        )
        assertEquals(
            "bz81ab3cd",
            BackgroundTaskPolls.parseLaunch("Command was manually backgrounded by user with ID: bz81ab3cd. Output is being written to: /tmp/tasks/bz81ab3cd.output."),
        )
    }

    @Test
    fun `finds the timed-out acknowledgement after the output so far`() {
        val timedOut =
            "tick-1\ntick-2\nCommand did not complete within its 2s timeout and was moved to the background (ID: bekp4cb00). Output is being written to: /private/tmp/claude-501/w/56c31d82/tasks/bekp4cb00.output. You will be notified when it completes."
        assertEquals("bekp4cb00", BackgroundTaskPolls.parseLaunch(timedOut))
        assertEquals("bekp4cb00", BackgroundTaskPolls.parseLaunch("```console\n$timedOut\n```"))
    }

    @Test
    fun `recognizes OpenCode 2's background shell acknowledgement`() {
        assertEquals(
            "sh_17d9d0e2d001XnyG98WPjd9Z7G",
            BackgroundTaskPolls.parseLaunch(
                "Command moved to the background (shell ID: sh_17d9d0e2d001XnyG98WPjd9Z7G).\nOutput is streaming to: /w/xdg/data/opencode/shell/sh_17d9d0e2d001XnyG98WPjd9Z7G.log",
            ),
        )
    }

    @Test
    fun `leaves an MCP tool that was moved to the background alone`() {
        assertNull(
            BackgroundTaskPolls.parseLaunch(
                "MCP tool \"blocker/wait_for_answer\" was moved to the background as task kjgwltsdi so that a message that arrived while it was running can reach you; it was not interrupted and keeps running",
            ),
        )
    }

    @Test
    fun `extracts the task id from Grok's launch acknowledgement and nothing from other text`() {
        assertEquals("term_b0d9512484964551a5bac4f82a805ae2", BackgroundTaskPolls.parseLaunch(grokLaunch))
        assertNull(BackgroundTaskPolls.parseLaunch(completed))
        assertNull(BackgroundTaskPolls.parseLaunch(null))
    }

    // endregion

    // region Grok

    @Test
    fun `parses a failed Grok poll with its command, exit code and output`() {
        val env = BackgroundTaskPolls.parseEnvelope(grokFailed)!!
        assertEquals(Envelope.Kind.POLL, env.kind)
        assertEquals("term_b0d", env.taskId)
        assertEquals("/bin/bash -lc 'pnpm dev -- --port 3001'", env.command)
        assertEquals("failed", env.status)
        assertEquals(1, env.exitCode)
        assertTrue(env.output!!.contains("Invalid project directory"))
    }

    @Test
    fun `parses a still-running Grok poll`() {
        val env = BackgroundTaskPolls.parseEnvelope(grokRunning)!!
        assertEquals("running", env.status)
        assertNull(env.exitCode)
        assertTrue(env.output!!.contains("Ready in"))
    }

    @Test
    fun `expands a MultiResult into one envelope per task`() {
        val envs = BackgroundTaskPolls.parseEnvelopes(
            buildJsonObject {
                put("type", "TaskOutput")
                putJsonObject("MultiResult") {
                    put("result_count", 2)
                    putJsonArray("results") {
                        addJsonObject { put("task_id", "term_a"); put("status", "completed"); put("exit_code", 0) }
                        addJsonObject { put("task_id", "term_b"); put("status", "running") }
                    }
                }
            }.toString(),
        )
        assertEquals(listOf("term_a", "term_b"), envs.map { it.taskId })
    }

    @Test
    fun `maps a killed Grok task to the stopped kind`() {
        val env = BackgroundTaskPolls.parseEnvelope("""{"type":"TaskOutput","Result":{"task_id":"term_a","status":"killed","command":"sleep 900"}}""")!!
        assertEquals(Envelope.Kind.STOP, env.kind)
    }

    @Test
    fun `ignores non-TaskOutput JSON, TaskNotFound, and truncated envelopes`() {
        assertNull(BackgroundTaskPolls.parseEnvelope("""{"type":"SubagentCompleted","Result":{"subagent_id":"s1","turns":3}}"""))
        assertNull(BackgroundTaskPolls.parseEnvelope("""{"type":"TaskOutput","TaskNotFound":{"task_id":"x"}}"""))
        assertNull(BackgroundTaskPolls.parseEnvelope(grokFailed.dropLast(20)))
    }

    @Test
    fun `routes Grok polls into the lane, unknown sub-agent payloads out of it`() {
        // Settled: by its envelope.
        assertTrue(BackgroundTaskPolls.isToolCall(grokPoll()))
        // In flight: by `{task_ids, timeout_ms}`, before any output.
        assertTrue(BackgroundTaskPolls.isToolCall(grokPoll(output = null, state = ToolCallState.RUNNING)))
        // The same tool polling a sub-agent whose payload is an unknown shape: out of the lane.
        assertFalse(
            BackgroundTaskPolls.isToolCall(grokPoll(output = """{"type":"SubagentCompleted","Result":{"subagent_id":"s1","turns":3}}""")),
        )
        // And a settled poll with no output at all (an older backend that dropped the envelope).
        assertFalse(BackgroundTaskPolls.isToolCall(grokPoll(output = null)))
        assertFalse(BackgroundTaskPolls.isToolCall(grokPoll(output = "")))
    }

    @Test
    fun `builds a failed row carrying the command and exit code`() {
        val rows = BackgroundTaskPolls.buildRows(
            listOf(grokPoll("c1", output = null, state = ToolCallState.RUNNING), grokPoll("c2")),
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("term_b0d", row.taskId)
        assertEquals(Badge.FAILED, row.badge)
        assertEquals(1, row.exitCode)
        assertEquals("/bin/bash -lc 'pnpm dev -- --port 3001'", row.command)
        assertTrue(row.output!!.contains("Invalid project directory"))
        assertEquals(2, row.pollCount)
        assertEquals(listOf("c1", "c2"), row.pollIds)
    }

    @Test
    fun `marks a Grok failure with no exit code as failed, not completed`() {
        val rows = BackgroundTaskPolls.buildRows(
            listOf(grokPoll(output = """{"type":"TaskOutput","Result":{"task_id":"term_z","status":"failed","command":"x"}}""")),
        )
        assertEquals(Badge.FAILED, rows.single().badge)
    }

    // endregion

    // region Grok SubagentCompleted

    private val subagentDone = buildJsonObject {
        put("type", "SubagentCompleted")
        put("subagent_id", "019f9432-e0d8-74c3-bd02-0772c4e04a65")
        put("subagent_type", "explore")
        put("description", "Explore test pages")
        put("status", "completed")
        put("tool_calls", 35)
        put("turns", 1)
        put("duration_ms", 63775)
        put("output", "## Codebase exploration summary")
    }.toString()

    @Test
    fun `parses the flat SubagentCompleted envelope into a sub-agent poll`() {
        val env = BackgroundTaskPolls.parseEnvelope(subagentDone)!!
        assertEquals(Envelope.Kind.POLL, env.kind)
        assertEquals("019f9432-e0d8-74c3-bd02-0772c4e04a65", env.taskId)
        assertEquals("subagent", env.taskType)
        assertEquals("completed", env.status)
        assertEquals("[subagent:explore] Explore test pages", env.command)
        assertEquals("## Codebase exploration summary", env.output)
    }

    @Test
    fun `maps a cancelled sub-agent to the stopped kind and rejects an empty envelope`() {
        assertEquals(
            Envelope.Kind.STOP,
            BackgroundTaskPolls.parseEnvelope("""{"type":"SubagentCompleted","subagent_id":"s1","status":"cancelled"}""")!!.kind,
        )
        assertNull(BackgroundTaskPolls.parseEnvelope("""{"type":"SubagentCompleted"}"""))
    }

    @Test
    fun `routes a settled flat sub-agent poll into the lane`() {
        assertTrue(BackgroundTaskPolls.isToolCall(grokPoll(output = subagentDone)))
        val row = BackgroundTaskPolls.buildRows(listOf(grokPoll(output = subagentDone))).single()
        assertEquals(Badge.COMPLETED, row.badge)
        assertEquals("[subagent:explore] Explore test pages", row.command)
        assertTrue(row.isSubagent)
    }

    // endregion

    // region CodeBuddy

    private val cbAgentDone = """Task ID: agent-5af21a8e9ff04b19
Status: completed
Duration: 19s
Started: 2026-09-15T05:42:08.169Z
Ended: 2026-09-15T05:42:27.996Z
Agent Type: general-purpose
Task ID (for resume): agent-5af21a8e9ff04b19

Prompt:
Run the command `pnpm build` in /Users/xggz/my/my-app.

Response:
Build succeeded (exit code 0).

--- RESULT ---
Build succeeded (exit code 0).

**Summary:** Next.js 16.1.7 production build completed with no errors."""

    private val cbAgentRunning = """Task ID: agent-77
Status: running
Duration: 3s
Task ID (for resume): agent-77

--- RESULT ---
(no new progress since last check)

(Task still running. You will be notified automatically when it completes.)

<system-reminder data-role="tool-hint">
Agent agent-77 is still running. You will be automatically notified via a <task-notification> message when it finishes — do NOT poll TaskOutput in a loop.
</system-reminder>"""

    private val cbShell = """Shell ID: bash-9
Command: pnpm dev
Status: completed
Duration: 2m 4s
Timestamp: 2026-09-15T05:42:27.996Z

Stdout (full):
ready on :3000

Stderr: (no output)"""

    @Test
    fun `parses a completed CodeBuddy agent snapshot into a sub-agent row`() {
        val env = BackgroundTaskPolls.parseEnvelope(cbAgentDone)!!
        assertEquals(Envelope.Kind.POLL, env.kind)
        assertEquals("agent-5af21a8e9ff04b19", env.taskId)
        assertEquals("subagent", env.taskType)
        assertEquals("completed", env.status)
        assertNull(env.exitCode)
        assertTrue(env.output!!.contains("Next.js 16.1.7 production build"))
        // Only the RESULT tail.
        assertFalse(env.output!!.contains("Task ID (for resume)"))
        assertFalse(env.output!!.contains("Response:"))
        assertFalse(env.output!!.contains("Prompt:"))
    }

    @Test
    fun `strips the model-facing tool hint from a running snapshot`() {
        val env = BackgroundTaskPolls.parseEnvelope(cbAgentRunning)!!
        assertEquals("running", env.status)
        assertTrue(env.output!!.contains("no new progress"))
        assertFalse(env.output!!.contains("system-reminder"))
        assertFalse(env.output!!.contains("do NOT poll TaskOutput"))
    }

    @Test
    fun `maps a cancelled worker to the stopped kind, and keeps a team member's note`() {
        assertEquals(
            Envelope.Kind.STOP,
            BackgroundTaskPolls.parseEnvelope("Task ID: agent-9\nStatus: cancelled\n\n--- RESULT ---\n(Task was cancelled)")!!.kind,
        )
        val team = BackgroundTaskPolls.parseEnvelope(
            "Task ID: agent-t\nStatus: running\n\nThis is a team member task (status: running). TaskOutput does not wait for teammates.",
        )!!
        assertEquals("agent-t", team.taskId)
        assertEquals("This is a team member task (status: running). TaskOutput does not wait for teammates.", team.output)
    }

    @Test
    fun `parses a background shell snapshot`() {
        val env = BackgroundTaskPolls.parseEnvelope(cbShell)!!
        assertEquals("bash-9", env.taskId)
        assertEquals("local_bash", env.taskType)
        assertEquals("pnpm dev", env.command)
        assertEquals("completed", env.status)
        assertNull(env.exitCode)
        assertTrue(env.output!!.contains("ready on :3000"))
        assertFalse(env.output!!.contains("Duration:"))
    }

    @Test
    fun `is not fooled by header labels echoed in the captured output`() {
        val snapshot = """Shell ID: bash-7
Command: ./emit-header.sh
Status: completed
Duration: 3s
Timestamp: 2026-09-15T05:42:27.996Z

Stdout (full):
Status: running
Duration: 99s
Timestamp: 2026-01-01T00:00:00.000Z
decoy

Stderr: (no output)"""
        val env = BackgroundTaskPolls.parseEnvelope(snapshot)!!
        assertEquals("./emit-header.sh", env.command)
        assertEquals("completed", env.status)
        assertTrue(env.output!!.contains("Status: running"))
        assertTrue(env.output!!.contains("decoy"))
        assertFalse(env.output!!.contains("./emit-header.sh"))

        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = snapshot, input = null))).single()
        assertEquals(Badge.COMPLETED, row.badge)
        assertEquals("./emit-header.sh", row.command)
        assertTrue(row.output!!.contains("decoy"))
    }

    @Test
    fun `abstains whenever the shell header cannot be located with certainty`() {
        val multiline = """Shell ID: bash-9
Command: pnpm install &&
pnpm build
Status: completed
Duration: 12s
Timestamp: 2026-09-15T05:42:27.996Z

Stdout (full):
done

Stderr: (no output)"""
        assertNull(BackgroundTaskPolls.parseEnvelope(multiline))
        // A heredoc forging the run right after `Command:`: two candidates, nothing claimed.
        assertNull(
            BackgroundTaskPolls.parseEnvelope(
                """Shell ID: bash-3
Command: cat <<'EOF'
Status: completed
Duration: fake
Timestamp: fake

Stdout (full):
EOF
Status: running
Duration: 1s
Timestamp: 2026-09-15T05:42:27.996Z

Stdout (full):
hi""",
            ),
        )
        // Output reproducing the whole run: indistinguishable from a forgery, so it abstains too.
        assertNull(
            BackgroundTaskPolls.parseEnvelope(
                """Shell ID: bash-4
Command: ./emit-header.sh
Status: completed
Duration: 3s
Timestamp: 2026-09-15T05:42:27.996Z

Stdout (full):
Status: running
Duration: 99s
Timestamp: 2026-01-01T00:00:00.000Z

Stdout (full):
decoy""",
            ),
        )
        // Abstaining: never a truncated command or a status lifted out of unrelated text.
        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = multiline, input = null))).single()
        assertNull(row.command)
        assertNull(row.output)
    }

    @Test
    fun `does not hijack ordinary output that merely mentions a task id`() {
        assertNull(BackgroundTaskPolls.parseEnvelope("Task ID: agent-1 was dispatched earlier."))
        assertNull(BackgroundTaskPolls.parseEnvelope("Task ID: agent-1\nsomething else entirely"))
        assertNull(BackgroundTaskPolls.parseEnvelope("Shell ID: s1\nStatus: done"))
        assertNull(BackgroundTaskPolls.parseEnvelope("Shell ID: s1\nCommand: ls\nStatus: running"))
        assertNull(BackgroundTaskPolls.parseEnvelope("Shell ID: s1\nCommand: ls\nStatus: running\nDuration: 1s"))
        assertFalse(BackgroundTaskPolls.isToolCall(poll(name = "Bash", input = null, output = "Shell ID: s1\nCommand: ls\nStatus: running")))
    }

    @Test
    fun `renders a settled snapshot as a completed row carrying its report`() {
        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = cbAgentDone, input = null))).single()
        assertEquals(Badge.COMPLETED, row.badge)
        assertTrue(row.isSubagent)
        assertTrue(row.output!!.contains("Next.js 16.1.7 production build"))
    }

    // endregion

    // region Grok sub-agent rows

    private val report = listOf(
        "## Build Result: **Succeeded**",
        "",
        "Optimized production build completed successfully.",
        "",
        "<subagent_meta>id=019fe6bf-0bcb-70c2-a02d-e5c006dfc32a, type=general-purpose, tool_calls=1, turns=1, duration_ms=18651</subagent_meta>",
        "",
        "<subagent_result>",
        "subagent_id: 019fe6bf-0bcb-70c2-a02d-e5c006dfc32a",
        "subagent_type: general-purpose",
        "To continue this subagent's conversation, use resume_from=\"019fe6bf-0bcb-70c2-a02d-e5c006dfc32a\".",
        "</subagent_result>",
    ).joinToString("\n")

    @Test
    fun `strips the meta and result scaffolding from a polled sub-agent's report`() {
        assertEquals(
            "## Build Result: **Succeeded**\n\nOptimized production build completed successfully.",
            BackgroundTaskPolls.stripGrokSubagentScaffolding(report),
        )
        val plain = "\$ next build\n✓ Compiled successfully\n"
        assertTrue(plain === BackgroundTaskPolls.stripGrokSubagentScaffolding(plain))
        assertNull(BackgroundTaskPolls.stripGrokSubagentScaffolding(null))
    }

    @Test
    fun `flags a 0_2_9x subagent TaskOutput row and cleans its output`() {
        val output = buildJsonObject {
            put("type", "TaskOutput")
            putJsonObject("Result") {
                put("task_id", "019fe6bf-0bcb-70c2-a02d-e5c006dfc32a")
                put("command", "[subagent:general-purpose] Run pnpm build")
                put("status", "completed")
                put("exit_code", 0)
                put("output", report)
            }
        }.toString()
        val row = BackgroundTaskPolls.buildRows(listOf(grokPoll(output = output))).single()
        assertTrue(row.isSubagent)
        assertFalse(row.output!!.contains("<subagent_meta>"))
        assertFalse(row.output!!.contains("resume_from"))
        assertTrue(row.output!!.contains("## Build Result"))
        assertFalse(BackgroundTaskPolls.buildRows(listOf(poll())).single().isSubagent)
    }

    // endregion

    // region isToolCall

    @Test
    fun `matches by tool name, by output envelope, and an in-flight poll by its input`() {
        assertTrue(BackgroundTaskPolls.isToolCall(poll(output = null)))
        assertTrue(BackgroundTaskPolls.isToolCall(poll(name = "TaskStop", input = """{"task_id":"x"}""", output = null)))
        // The live `task` alias: by its envelope.
        assertTrue(BackgroundTaskPolls.isToolCall(poll(name = "task", input = null)))
        // In flight, before its output.
        assertTrue(BackgroundTaskPolls.isToolCall(poll(name = "task", output = null, state = ToolCallState.RUNNING)))
    }

    @Test
    fun `does not match a real sub-agent call, a delegation check or a cancel, or TaskUpdate`() {
        assertFalse(
            BackgroundTaskPolls.isToolCall(
                poll(name = "Agent", input = """{"subagent_type":"Explore","task_id":"x","block":true}""", output = "the agent's final answer"),
            ),
        )
        assertFalse(
            BackgroundTaskPolls.isToolCall(
                poll(
                    name = "mcp__codeg-mcp__get_delegation_status",
                    input = """{"task_ids":["a"]}""",
                    output = """{"tasks":[{"task_id":"a","status":"running"}]}""",
                ),
            ),
        )
        assertFalse(BackgroundTaskPolls.isToolCall(poll(name = "mcp__codeg-mcp__cancel_delegation", input = """{"task_id":"a"}""", output = "Canceled task a")))
        assertFalse(BackgroundTaskPolls.isToolCall(poll(name = "TaskUpdate", input = """{"taskId":"1","status":"completed"}""", output = "ok")))
    }

    // endregion

    // region buildRows

    @Test
    fun `collapses repeated polls of one task into a single completed row`() {
        val rows = BackgroundTaskPolls.buildRows(listOf(poll("p1", output = running), poll("p2", output = completed)))
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(Badge.COMPLETED, row.badge)
        assertEquals(0, row.exitCode)
        assertEquals(2, row.pollCount)
        assertTrue(row.output!!.contains("test result: ok"))
        assertEquals("bfb5xnq1t", row.taskId)
    }

    @Test
    fun `keeps parallel tasks as separate rows`() {
        val rows = BackgroundTaskPolls.buildRows(
            listOf(poll("a", output = completed), poll("b", output = failed, input = """{"task_id":"bxx","block":true}""")),
        )
        assertEquals(listOf("bfb5xnq1t", "bxx"), rows.map { it.taskId })
        assertEquals(Badge.FAILED, rows[1].badge)
        assertEquals(1, rows[1].exitCode)
    }

    @Test
    fun `marks an in-flight poll as running`() {
        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = null, state = ToolCallState.RUNNING))).single()
        assertEquals(Badge.RUNNING, row.badge)
        assertTrue(row.isInFlight)
        assertEquals(StepStatus.RUNNING, BackgroundTaskPolls.rowStatus(row))
        assertTrue(BackgroundTaskPolls.staysInView(row))
    }

    @Test
    fun `a settled poll that said still running is a snapshot, not live work`() {
        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = running))).single()
        assertEquals(Badge.RUNNING, row.badge)
        assertFalse(row.isInFlight)
        assertEquals(StepStatus.SUCCEEDED, BackgroundTaskPolls.rowStatus(row))
        assertFalse(BackgroundTaskPolls.staysInView(row))
        // The lane still reads as running: its task hasn't finished.
        assertEquals(StepStatus.RUNNING, BackgroundTaskPolls.status(listOf(row)))
    }

    @Test
    fun `drops an in-flight poll whose task_id has not streamed in yet`() {
        assertTrue(BackgroundTaskPolls.buildRows(listOf(poll(input = "{}", output = null, state = ToolCallState.RUNNING))).isEmpty())
    }

    @Test
    fun `does not stack repeated id-less in-flight re-polls into duplicate rows`() {
        val rows = BackgroundTaskPolls.buildRows(
            listOf(
                poll("done", output = completed),
                poll("x1", input = "{}", output = null, state = ToolCallState.RUNNING),
                poll("x2", input = null, output = null, state = ToolCallState.RUNNING),
                poll("x3", input = "{}", output = null, state = ToolCallState.INPUT_STREAMING),
            ),
        )
        assertEquals(listOf("bfb5xnq1t"), rows.map { it.taskId })
    }

    @Test
    fun `drops an id-less poll handed off with nothing back`() {
        // ADAPTATION of the web's "drops a promoted orphan": a reply handed off locally settles
        // every call, an arg-less poll that never returned included.
        val rows = BackgroundTaskPolls.buildRows(listOf(poll("done", output = completed), poll("orphan", input = "{}", output = "")))
        assertEquals(listOf("bfb5xnq1t"), rows.map { it.taskId })
    }

    @Test
    fun `still shows an id-less poll once it carries an envelope`() {
        val noId = "<retrieval_status>success</retrieval_status>\n<status>completed</status>\n<exit_code>0</exit_code>\n<output>ok</output>"
        val row = BackgroundTaskPolls.buildRows(listOf(poll(input = null, output = noId))).single()
        assertEquals(Badge.COMPLETED, row.badge)
    }

    @Test
    fun `derives a stopped badge and the command from a TaskStop ack`() {
        val row = BackgroundTaskPolls.buildRows(listOf(poll(name = "TaskStop", input = """{"task_id":"bebna5yf2"}""", output = stopJson))).single()
        assertEquals(Badge.STOPPED, row.badge)
        assertEquals("cd /x; echo hi", row.command)
        assertEquals(StepStatus.CANCELLED, BackgroundTaskPolls.status(listOf(row)))
    }

    @Test
    fun `the row's line reads what started the task, then what it came to`() {
        val done = BackgroundTaskPolls.buildRows(listOf(poll(output = completed))).single()
        val launch = BackgroundTaskPolls.Launch("b1", "cargo test --all", "Run the test suite")
        assertEquals("Run the test suite · running 0 tests", BackgroundTaskPolls.rowDetail(done, launch))
        assertEquals("cargo test --all · running 0 tests", BackgroundTaskPolls.rowDetail(done, launch.copy(description = null)))
        val failedRow = BackgroundTaskPolls.buildRows(listOf(grokPoll())).single()
        // A failure's last line is usually the error itself.
        assertEquals(
            "/bin/bash -lc 'pnpm dev -- --port 3001' · Invalid project directory provided, no such directory: /Users/me/proj/--port",
            BackgroundTaskPolls.rowDetail(failedRow, null),
        )
        assertEquals("bfb5xnq1t", BackgroundTaskPolls.shortId(done))
        assertNull(BackgroundTaskPolls.rowDetail(BackgroundTaskPolls.buildRows(listOf(poll(output = null, state = ToolCallState.RUNNING))).single(), null))
    }

    @Test
    fun `a shell's ANSI escapes are taken out of what the row shows`() {
        val colored = completed.replace("test result: ok", "\u001B[32mtest result: ok\u001B[0m")
        val row = BackgroundTaskPolls.buildRows(listOf(poll(output = colored))).single()
        assertTrue(BackgroundTaskPolls.displayOutput(row)!!.contains("test result: ok"))
        assertFalse(BackgroundTaskPolls.displayOutput(row)!!.contains("\u001B"))
    }

    // endregion

    // region Grouping

    private fun bash(id: String, output: String = "file.txt", input: String = """{"command":"ls"}""") =
        RenderPart.Tool(ToolCallVM.of(id, "Bash", "", ToolCallState.DONE, input, output, null, false))

    private fun text(s: String) = RenderPart.Text(s)

    @Test
    fun `collapses a consecutive run of polls into one lane`() {
        val parts = MessageRender.groupBackgroundTasks(listOf(text("before"), RenderPart.Tool(poll("p1")), RenderPart.Tool(poll("p2")), text("after")))
        assertEquals(listOf("Text", "BackgroundTasks", "Text"), parts.map { it::class.simpleName })
        assertEquals(2, (parts[1] as RenderPart.BackgroundTasks).polls.size)
    }

    @Test
    fun `does not merge polls separated by other content, and leaves a command beside them alone`() {
        val parts = MessageRender.groupBackgroundTasks(listOf(RenderPart.Tool(poll("p1")), text("interruption"), RenderPart.Tool(poll("p2"))))
        assertEquals(listOf("BackgroundTasks", "Text", "BackgroundTasks"), parts.map { it::class.simpleName })
        val mixed = MessageRender.groupBackgroundTasks(listOf(bash("b1"), RenderPart.Tool(poll("p1"))))
        assertEquals(listOf("Tool", "BackgroundTasks"), mixed.map { it::class.simpleName })
    }

    @Test
    fun `a call named get_delegation_status stays a delegation check whatever its result quotes`() {
        // Grok's `{task_ids, timeout_ms}` reads as a poll by shape, and a child's report can carry
        // a background envelope's tags as text: the web takes the delegation lane by name first.
        val check = ToolCallVM.of(
            "d1", "mcp__codeg-mcp__get_delegation_status", "", ToolCallState.RUNNING,
            """{"task_ids":["t1"],"timeout_ms":1000}""", null, null, false,
        )
        val quoting = ToolCallVM.of("d2", "get_delegation_status", "", ToolCallState.DONE, """{"task_ids":["t1"]}""", completed, null, false)
        val parts = MessageRender.groupBackgroundTasks(listOf(RenderPart.Tool(check), RenderPart.Tool(quoting)))
        assertTrue(parts.all { it is RenderPart.Tool })
    }

    @Test
    fun `a lane is handed the commands before it that went on in the background`() {
        val launched = bash("b1", output = "Command running in background with ID: bfb5xnq1t. Output is being written to: /tmp/x.", input = """{"command":"cargo test","description":"Run the tests"}""")
        val parts = MessageRender.groupBackgroundTasks(listOf(launched, RenderPart.Tool(poll("p1"))))
        val lane = parts[1] as RenderPart.BackgroundTasks
        val launch = lane.launches["bfb5xnq1t"]
        assertNotNull(launch)
        assertEquals("cargo test", launch!!.command)
        assertEquals("Run the tests", launch.description)
        assertEquals("b1", launch.callId)
        assertEquals("bfb5xnq1t" to false, BackgroundTaskPolls.launchedTask(launched.vm))
    }

    @Test
    fun `a monitor that started names the task it watches`() {
        val monitor = ToolCallVM.of(
            "m1", "Monitor", "", ToolCallState.DONE, """{"command":"tail -f log","description":"Watch the log"}""",
            "Monitor started (task bm1x, expires in 30m). You will be notified on each event.", null, false,
        )
        assertEquals("bm1x" to true, BackgroundTaskPolls.launchedTask(monitor))
        assertNull(BackgroundTaskPolls.launchedTask(ToolCallVM.of("b", "Bash", "", ToolCallState.DONE, """{"command":"ls"}""", "a\nb", null, false)))
    }

    // endregion

    // region Summary

    @Test
    fun `counts each background task once, and its failure as one nothing recovers`() {
        val lane = MessageRender.groupBackgroundTasks(
            listOf(
                RenderPart.Tool(poll("p1", output = running)),
                RenderPart.Tool(poll("p2", output = completed)),
                RenderPart.Tool(grokPoll("p3")),
            ),
        )
        val summary = summarizeActivity(lane)
        assertEquals(2, summary.steps)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.unrecovered)
        // "Show the failure" points at the failed task's row, by its latest poll.
        assertEquals("bg-row:p3:term_b0d", summary.firstUnrecoveredId)
    }

    @Test
    fun `a MultiResult poll's failed task is the one pointed at, not its first`() {
        val multi = buildJsonObject {
            put("type", "TaskOutput")
            putJsonObject("MultiResult") {
                putJsonArray("results") {
                    addJsonObject { put("task_id", "term_a"); put("status", "completed"); put("exit_code", 0) }
                    addJsonObject { put("task_id", "term_b"); put("status", "failed"); put("exit_code", 3) }
                }
            }
        }.toString()
        val lane = MessageRender.groupBackgroundTasks(listOf(RenderPart.Tool(grokPoll("m1", output = multi))))
        assertEquals(2, (lane.single() as RenderPart.BackgroundTasks).rows.size)
        assertEquals("bg-row:m1:term_b", summarizeActivity(lane).firstUnrecoveredId)
    }

    // endregion
}
