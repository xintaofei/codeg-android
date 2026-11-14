package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode code-mode delegations — ported from the web's `delegation-card.test.ts`
 * ("opencode code-mode execute payloads", "assistant identity") and the code-mode cases of
 * `describe-tool-call.test.ts`. The scanner mirrors the backend's (`acp/lifecycle.rs`), so these
 * pin the predicate both sides share.
 */
class CodeModeDelegationTest {

    /** JS `JSON.stringify({ code })`. */
    private fun raw(code: String): String = buildJsonObject { put("code", code) }.toString()

    private fun parse(code: String) = DelegationParse.args(raw(code))

    private fun isInput(code: String) = CodeModeDelegation.isInput(raw(code))

    private val empty = DelegationParse.Args()

    private val d = "tools[\"codeg-mcp\"].delegate_to_agent"

    // region opencode code-mode execute payloads

    @Test
    fun `recognizes and parses the opencode emission`() {
        // A real emission: bracketed accessor, working_dir, a template task whose `\n` / `\t`
        // are JS source escapes the runtime cooks, CJK text.
        val code = """await tools["codeg-mcp"].delegate_to_agent({ agent_type: "codex", working_dir: "/tmp/对拍", task: `review 对拍\nsecond\tline ✓` })"""
        assertTrue(isInput(code))
        val parsed = parse(code)
        assertEquals("codex", parsed.agentType)
        assertEquals("review 对拍\nsecond\tline ✓", parsed.task)
        assertEquals("/tmp/对拍", parsed.workingDir)
        assertNull(parsed.assistantId)
    }

    @Test
    fun `parses an LS-PS-bearing emission verbatim like the MCP side`() {
        val code = "$d({ agent_type: \"codex\", task: `seg\u2028a\u2029b` })"
        assertTrue(isInput(code))
        val parsed = parse(code)
        assertEquals("codex", parsed.agentType)
        assertEquals("seg\u2028a\u2029b", parsed.task)
    }

    @Test
    fun `parses the namespaced accessor form with working_dir`() {
        val code = """await tools.codeg_mcp.delegate_to_agent({ agent_type: 'codex', task: 'ship it', working_dir: "/tmp/proj" })"""
        assertTrue(isInput(code))
        val parsed = parse(code)
        assertEquals("codex", parsed.agentType)
        assertEquals("ship it", parsed.task)
        assertEquals("/tmp/proj", parsed.workingDir)
    }

    @Test
    fun `unescapes the minimal JS escape set in template tasks`() {
        val code = """$d({ agent_type: "zcode", task: `a\nb\tc\u4e2d\u{1F600}` })"""
        assertEquals("a\nb\tc中\uD83D\uDE00", parse(code).task)
    }

    @Test
    fun `rejects shapes that are not delegation calls`() {
        val negatives = listOf(
            "not json at all",
            """{"code":42}""",
            "{}",
            """{"command":"ls"}""",
            // A different method on the same accessor is an execute call, not ours.
            raw("""tools["codeg-mcp"].list_agents({})"""),
            // `tools.search` is a plain method call, not an accessor chain.
            raw("""tools.search({ query: "delegate_to_agent" })"""),
            // The call shape inside a string / template / comment is no call site.
            raw("const s = \"tools[\\\"codeg-mcp\\\"].delegate_to_agent(\""),
            raw("const t = `tools[\"codeg-mcp\"].delegate_to_agent()`"),
            raw("""// tools["codeg-mcp"].delegate_to_agent({ agent_type: "zcode" })"""),
        )
        for (r in negatives) {
            assertFalse(r, CodeModeDelegation.isInput(r))
            val parsed = DelegationParse.args(r)
            assertNull(r, parsed.agentType)
            assertNull(r, parsed.task)
        }
    }

    @Test
    fun `keeps the card renderable when a value is unreadable`() {
        // `${…}` evaluates differently than the source reads.
        val interp = "$d({ agent_type: \"zcode\", task: `hi \${name}` })"
        assertTrue(isInput(interp))
        assertEquals(DelegationParse.Args(agentType = "zcode"), parse(interp))

        // An escape outside the minimal set bails the same way.
        val unknownEscape = """$d({ agent_type: "zcode", task: `a\qb` })"""
        assertTrue(isInput(unknownEscape))
        assertNull(parse(unknownEscape).task)

        // An escaped quoted task could decode differently than it reads.
        val escapedQuoted = """$d({ agent_type: "zcode", task: "a\nb" })"""
        assertTrue(isInput(escapedQuoted))
        assertNull(parse(escapedQuoted).task)

        // No agent_type, or an unknown slug: still a call, with a generic card.
        val noAgent = "$d({ task: `t` })"
        assertTrue(isInput(noAgent))
        assertNull(parse(noAgent).agentType)
        assertNull(parse("$d({ agent_type: \"nope\", task: `t` })").agentType)
    }

    @Test
    fun `reads a stringified assistant_id without mistaking the target`() {
        val code = "$d({ assistant_id: \"3\", agent_type: \"codex\", task: `t` })"
        assertTrue(isInput(code))
        val parsed = parse(code)
        assertEquals(3, parsed.assistantId)
        assertEquals("codex", parsed.agentType)
        assertEquals("t", parsed.task)
    }

    @Test
    fun `reads a bare numeric assistant_id in every spelling`() {
        val bare = parse("$d({ assistant_id: 3, agent_type: \"codex\", task: `t` })")
        assertEquals(3, bare.assistantId)
        assertEquals("codex", bare.agentType)
        assertEquals(3, parse("$d({ \"assistant_id\": 3, task: `t` })").assistantId)
        assertEquals(3, parse("$d({ \"assistant_id\": \"3\", task: `t` })").assistantId)
        // A bare number NOT ending its property is not the value.
        assertNull(parse("$d({ assistant_id: 3 + 1, task: `t` })").assistantId)
    }

    @Test
    fun `degrades the parse when working_dir is present but unreadable`() {
        val cases = listOf(
            """$d({ agent_type: "codex", working_dir: "C:\\proj", task: `t` })""",
            "$d({ agent_type: \"codex\", working_dir: `\${cwd}/proj`, task: `t` })",
            "$d({ agent_type: \"codex\", working_dir: projectDir, task: `t` })",
            "$d({ agent_type: \"codex\", working_dir: \"/tmp\" + \"/w\", task: `t` })",
        )
        for (code in cases) {
            assertTrue(code, isInput(code))
            val parsed = parse(code)
            assertNull(code, parsed.workingDir)
            assertEquals(code, "codex", parsed.agentType)
            assertEquals(code, "t", parsed.task)
        }
    }

    @Test
    fun `does not read a value that continues past its literal`() {
        val taskConcat = parse("$d({ agent_type: \"codex\", task: \"a\" + b })")
        assertNull(taskConcat.task)
        assertEquals("codex", taskConcat.agentType)

        assertNull(parse("$d({ agent_type: \"codex\", task: `a`.trim() })").task)

        val agentConcat = parse("$d({ agent_type: \"co\" + \"dex\", task: `t` })")
        assertNull(agentConcat.agentType)
        assertEquals("t", agentConcat.task)

        val dirConcat = parse("$d({ agent_type: \"codex\", working_dir: \"/a\" + suffix, task: `t` })")
        assertNull(dirConcat.workingDir)
        assertEquals("t", dirConcat.task)

        // Duplicate keys take the LAST spelling — the one the runtime uses…
        assertEquals("fresh", parse("$d({ agent_type: \"codex\", task: `stale`, task: `fresh` })").task)
        // …and a later unreadable spelling degrades the read instead of keying the earlier one.
        assertNull(parse("$d({ agent_type: \"codex\", task: `stale`, task: \"a\" + b })").task)
    }

    @Test
    fun `accepts comments between values and their property terminators`() {
        val code = """$d({
  // choose the agent
  agent_type: "codex", /* trailing */ task: `t` /* last */, // done
})"""
        assertTrue(isInput(code))
        val parsed = parse(code)
        assertEquals("codex", parsed.agentType)
        assertEquals("t", parsed.task)
    }

    @Test
    fun `reads quoted property keys and ignores nested same-named keys`() {
        val quoted = """$d({ "agent_type": "codex", 'task': "plain" })"""
        assertTrue(isInput(quoted))
        assertEquals("codex", parse(quoted).agentType)
        assertEquals("plain", parse(quoted).task)

        // The backend reads FLAT args: a same-named key in a sub-object never surfaces.
        val nested = parse(
            """$d({
  meta: { agent_type: "zzz", task: `decoy` },
  agent_type: "codex",
  task: `real`,
})""",
        )
        assertEquals("codex", nested.agentType)
        assertEquals("real", nested.task)

        val nestedRev = parse("$d({ agent_type: \"codex\", task: `real`, meta: { task: `decoy`, agent_type: \"zzz\" } })")
        assertEquals("codex", nestedRev.agentType)
        assertEquals("real", nestedRev.task)
    }

    @Test
    fun `screens unknowable argument syntax out of the parse entirely`() {
        val cases = listOf(
            "$d({ ...base, task: `t` })",
            "$d({ [\"assistant_id\"]: 3, agent_type: \"codex\", task: `t` })",
            "$d({ [\"agent_type\"]: \"codex\", task: `t` })",
        )
        for (code in cases) {
            assertTrue(code, isInput(code))
            assertEquals(code, empty, parse(code))
        }
    }

    @Test
    fun `normalizes template line terminators the way the JS runtime cooks them`() {
        // Bare CR and CRLF collapse to one LF.
        assertEquals("a\nb\nc", parse("$d({ agent_type: \"codex\", task: `a\r\nb\rc` })").task)
        // U+2028 / U+2029 are JS line terminators but NOT normalized: they pass verbatim.
        assertEquals("a\u2028b", parse("$d({ agent_type: \"codex\", task: `a\u2028b` })").task)
        assertEquals("a\u2029b", parse("$d({ agent_type: \"codex\", task: `a\u2029b` })").task)
        // A backslash line continuation contributes nothing.
        assertEquals("ab", parse("$d({ agent_type: \"codex\", task: `a\\\r\nb` })").task)
    }

    @Test
    fun `bails to the generic row when args syntax is unreadable`() {
        val cases = listOf(
            "$d({ agent_type: \"codex\", task: `t`, working_dir })",
            "$d({ agent_type: \"codex\", task: `t`, assistant_id })",
            "$d({ agent_type: pick(\"codex\"), task: `t` })",
            "$d({ agent_type: \"codex\", task: `t`, flag: /}/ })",
        )
        for (code in cases) {
            assertTrue(code, isInput(code))
            assertEquals(code, empty, parse(code))
        }
    }

    @Test
    fun `reads nothing unless the argument is exactly one object literal`() {
        val cases = listOf(
            "$d(c ? { agent_type: \"codex\", task: `a` } : { agent_type: \"codex\", task: `b` })",
            "$d(x || { agent_type: \"codex\", task: `decoy` })",
            "$d(x ?? { agent_type: \"codex\", task: `decoy` })",
            "$d({ agent_type: \"codex\", task: `t` }.y)",
            "$d({ agent_type: \"codex\", task: `t` }, { agent_type: \"goose\", task: `u` })",
            "$d(args)",
        )
        for (code in cases) {
            assertTrue(code, isInput(code))
            assertEquals(code, empty, parse(code))
        }
        // A trailing comma still leaves exactly one argument.
        assertEquals("t", parse("$d({ agent_type: \"codex\", task: `t` }, /* end */ )").task)
    }

    @Test
    fun `reads nothing when later syntax can override or hide a key`() {
        val cases = listOf(
            "$d({ agent_type: \"codex\", task: \"decoy\", task })",
            "$d({ agent_type: \"codex\", task: \"t\", agent_type })",
            "$d({ agent_type: \"codex\", task: \"decoy\", \\u0074ask: \"real\" })",
            "$d({ agent_type: \"codex\", task: c ? \"task\" : \"real\" })",
            "$d({ agent_type: c ? \"agent_type\" : \"codex\", task: \"t\" })",
            "$d({ agent_type: \"codex\", task: obj?.task })",
            "$d({ agent_type: \"codex\", task: \"decoy\",\u00a0task: \"real\" })",
            "$d({ agent_type: \"codex\", task: \"decoy\",\ufefftask: \"real\" })",
        )
        for (code in cases) {
            assertTrue(code, isInput(code))
            val parsed = parse(code)
            assertNull(code, parsed.task)
            assertNull(code, parsed.agentType)
        }
    }

    @Test
    fun `classifies only opencode's execute tool by its code`() {
        // Another tool's `code` — a Playwright script that mocks the call — is no delegation.
        val r = raw(
            "async page => { const tools = {'codeg-mcp': {delegate_to_agent: async x => x}}; " +
                "await tools['codeg-mcp'].delegate_to_agent({agent_type:'codex',task:'review'}); }",
        )
        assertTrue(CodeModeDelegation.isInput(r))
        // `execute` normalizes to `bash`, live and in history.
        assertTrue(CodeModeDelegation.isCall("bash", r))
        assertTrue(CodeModeDelegation.isCall("execute", r))
        assertTrue(CodeModeDelegation.isCall("Execute", r))
        assertFalse(CodeModeDelegation.isCall("mcp__playwright__browser_run_code", r))
        assertFalse(CodeModeDelegation.isCall("exec_command", r))
    }

    @Test
    fun `declines a script with a slash before the call`() {
        // `/'/` opens a "string" that skips the real call and closes in a later comment.
        val desync = "const re = /'/;\nawait $d({agent_type:\"codex\",task:\"real\"});\n" +
            "// here's an example: $d({agent_type:\"codex\",task:\"decoy\"})"
        assertFalse(isInput(desync))
        // After a call that runs first, a slash changes nothing…
        assertEquals("t", parse("const r = await $d({ agent_type: \"codex\", task: \"t\" }); return r.ms / 1000").task)
        // …after one that may not run, it hides whether another site exists.
        val unsure = "if (bad) { await $d({ agent_type: \"codex\", task: \"t\" }) } const n = 4 / 2"
        assertTrue(isInput(unsure))
        assertNull(parse(unsure).task)
    }

    @Test
    fun `reads the first of several calls only when it runs first`() {
        fun task(code: String): String? {
            assertTrue(code, isInput(code))
            return parse(code).task
        }
        val a = "$d({ agent_type: \"codex\", task: \"A\" })"
        val b = "$d({ agent_type: \"zcode\", task: \"B\" })"
        assertEquals("A", task("await $a; await $b"))
        assertEquals("A", task("const [a, b] = await Promise.allSettled([$a, $b])"))
        // A lone call reads wherever it sits: if it runs, it is the call.
        assertEquals("fix", task("if (failed) { await $d({ agent_type: \"codex\", task: \"fix\" }) }"))
        assertEquals("A", task("const r = await $a; await $b; return `\${r.task_id}`"))
        assertEquals("A", task("await Promise.allSettled([$a, $b]).then(rs => rs)"))
        assertEquals("A", task("return await $a"))

        val decoy = "$d({ agent_type: \"codex\", task: \"decoy\" })"
        val real = "$d({ agent_type: \"codex\", task: \"real\" })"
        // The first site may not run while a later one does: one case per construct.
        for (code in listOf(
            "const later = (x = $decoy) => x; await $real",
            "const later = async (x = $decoy) => x; await $real",
            "function later(x = $decoy) { return x } await $real",
            "const [a = $decoy] = [1]; await $real",
            "if (false) { await $decoy } const text = `\${await $real}`",
            "const text = `\${await $real}`; if (c) { await $decoy }",
            "c && await $a; await $b",
            "c || await $a; await $b",
            "switch (c) {} await $a; await $b",
            "for (const x of xs) {} await $a; await $b",
            "while (false) {} await $a; await $b",
            "do {} while (false); await $a; await $b",
            "try {} catch (e) {} await $a; await $b",
            "const t = `\${await $real}`; return t; await $a; await $b",
            "const f = (x = $decoy, y = 1 / 2) => x; await $real",
            "if (false) { await $decoy } await $real",
            "c ? $a : $b",
            "if (c) await $a; else await $b",
            "const later = () => $a; await $b",
            "async function go() { await $a } await $b",
        )) {
            assertNull(code, task(code))
        }
    }

    @Test
    fun `caps the script in UTF-8 bytes, as the backend does`() {
        val call = "tools.codeg_mcp.delegate_to_agent({agent_type:\"codex\",task:\"t\"})"
        // 350,000 CJK characters are 1,050,000 bytes: past the 1 MiB cap, though under it in units.
        assertFalse(isInput("/*" + "中".repeat(350_000) + "*/" + call))
        assertTrue(isInput("/*" + "a".repeat((1 shl 20) - 200) + "*/" + call))
    }

    @Test
    fun `ends line comments at every JS line terminator`() {
        assertEquals("t", parse("$d({ // pick\r agent_type: \"codex\", task: `t` })").task)
        val ls = "// delegate\u2028await $d({ agent_type: \"codex\", task: `t` })"
        assertTrue(isInput(ls))
        assertEquals("t", parse(ls).task)
        // Inside the argument object the terminator is non-ASCII the word matcher can't delimit.
        val lsInArgs = "$d({ // pick\u2028 agent_type: \"codex\", task: `t` })"
        assertTrue(isInput(lsInArgs))
        assertNull(parse(lsInArgs).task)
    }

    // endregion

    // region assistant identity (the JSON path)

    @Test
    fun `reads assistant_id off the call, a numeric string included`() {
        val parsed = DelegationParse.args("""{"assistant_id":3,"task":"review","agent_type":"codex"}""")
        assertEquals(3, parsed.assistantId)
        assertEquals("codex", parsed.agentType)
        assertEquals("review", parsed.task)
        assertEquals(12, DelegationParse.args("""{"assistant_id":"12"}""").assistantId)
    }

    @Test
    fun `finds the arguments by assistant_id alone, wrapped or not`() {
        assertEquals(4, DelegationParse.args("""{"toolName":"delegate_to_agent","args":{"assistant_id":4}}""").assistantId)
    }

    @Test
    fun `ignores an id the backend would refuse`() {
        for (value in listOf("0", "-1", "1.5", "\"03\"", "\"abc\"", "\"\"", "2147483648", "null", "true")) {
            assertNull(value, CodeModeDelegation.coerceAssistantId(Json.parseToJsonElement(value)))
        }
        assertEquals(2_147_483_647, CodeModeDelegation.coerceAssistantId(Json.parseToJsonElement("2147483647")))
    }

    @Test
    fun `an agent slug is narrowed to one codeg knows`() {
        assertEquals("custom:goose", CodeModeDelegation.coerceAgentType(" custom:goose "))
        assertEquals("minimax_code", CodeModeDelegation.coerceAgentType("minimax_code"))
        assertNull(CodeModeDelegation.coerceAgentType("nope"))
        assertNull(DelegationParse.args("""{"agent_type":"nope","task":"t"}""").agentType)
    }

    // endregion

    // region the card (web `describe-tool-call.test.ts`, code-mode cases)

    private fun vm(name: String, input: String, meta: String? = null, output: String? = null) =
        ToolCallVM.of("c1", name, "execute", ToolCallState.DONE, input, output, null, false, meta?.let { Json.parseToJsonElement(it).jsonObject })

    @Test
    fun `a code-mode execute delegating draws the delegation card, any other stays a command`() {
        val delegating = vm("execute", raw("await $d({ agent_type: \"codex\", task: `t` })"))
        assertTrue(Delegations.drawsCard(delegating))
        assertNull("it stands alone, never folded into a run of commands", stepFamily(delegating))
        val model = Delegations.model(delegating, null, null)!!
        assertEquals("codex", model.agentType)
        assertEquals("t", model.task)

        val script = vm("execute", raw("return 1 + 1"))
        assertFalse(Delegations.drawsCard(script))
        assertEquals(StepFamily.COMMAND, stepFamily(script))
        // A `{code}` input on a real command tool stays a command.
        assertFalse(Delegations.drawsCard(vm("bash", """{"command":"node -e x","code":"x"}""")))
    }

    @Test
    fun `a title that merely names delegate_to_agent is no delegation`() {
        // Evidence or nothing: a live command quoting the tool keeps its command and output.
        assertFalse(Delegations.drawsCard(vm("rg 'delegate_to_agent' src", """{"command":"rg 'delegate_to_agent' src"}""")))
    }

    @Test
    fun `an unreadable code-mode call draws by the broker's stamp, and takes its task id from it`() {
        val stamped = vm(
            "execute",
            raw("$d({ ...base, task: `t` })"),
            meta = """{"codeg.delegation":{"status":"running","task_id":"t9","task_preview":"From the broker"}}""",
            output = "42",
        )
        val model = Delegations.model(stamped, null, null)!!
        assertNull(model.agentType)
        assertEquals("From the broker", model.task)
        assertEquals("t9", model.taskId)
        assertEquals(DelegationModel.Status.RUNNING, model.status)
        // With nothing at all to go by it is the plain card.
        assertNull(Delegations.model(vm("execute", raw("$d({ ...base, task: `t` })")), null, null))
    }

    @Test
    fun `a call that names an assistant does not wear its agent_type`() {
        val call = vm("execute", raw("$d({ assistant_id: 3, agent_type: \"codex\", task: `t` })"))
        val model = Delegations.model(call, null, null)!!
        assertNull(model.agentType)
        assertEquals("t", model.task)
    }

    // endregion
}
