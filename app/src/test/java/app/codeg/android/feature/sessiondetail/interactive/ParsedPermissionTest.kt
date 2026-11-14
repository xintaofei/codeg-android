package app.codeg.android.feature.sessiondetail.interactive

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the ported permission tool-call parser. */
class ParsedPermissionTest {

    private fun parse(json: String) = ParsedPermission.parse(Json.parseToJsonElement(json))

    @Test
    fun `extracts a bash command from rawInput`() {
        val p = parse("""{"kind":"bash","rawInput":{"command":"rm -rf /tmp/x"}}""")
        assertEquals("rm -rf /tmp/x", p.command)
        assertTrue(p.hasStructuredBody)
        assertFalse(p.isPlan)
    }

    @Test
    fun `extracts a command from a rawInput that is itself a JSON string`() {
        val p = parse("""{"kind":"execute","rawInput":"{\"command\":\"ls -la\"}"}""")
        assertEquals("ls -la", p.command)
    }

    @Test
    fun `synthesizes a diff from old and new strings`() {
        val p = parse(
            """{"kind":"edit","rawInput":{"file_path":"a.kt","old_string":"val x = 1","new_string":"val x = 2"}}""",
        )
        assertEquals(1, p.diffFiles.size)
        assertEquals("a.kt", p.diffFiles[0].path)
        assertTrue(p.diffFiles[0].additions >= 1)
    }

    @Test
    fun `parses plan entries and flags isPlan`() {
        val p = parse(
            """{"kind":"ExitPlanMode","rawInput":{"plan":[{"content":"Step one"},{"step":"Step two"}]}}""",
        )
        assertEquals(2, p.planEntries.size)
        assertEquals("Step one", p.planEntries[0].text)
        assertTrue(p.isPlan)
    }

    @Test
    fun `parses allowed prompts`() {
        val p = parse(
            """{"kind":"tool","rawInput":{"allowedPrompts":[{"prompt":"run tests","tool":"Bash"}]}}""",
        )
        assertEquals(1, p.allowedPrompts.size)
        assertEquals("run tests", p.allowedPrompts[0].prompt)
        assertEquals("Bash", p.allowedPrompts[0].tool)
    }

    @Test
    fun `falls back to a titleized kind when no title present`() {
        val p = parse("""{"kind":"web_fetch","rawInput":{"url":"https://x.com"}}""")
        assertEquals("Web Fetch", p.title)
        assertEquals("https://x.com", p.url)
        assertNull(p.command)
    }
}

/** What 0.44 reads off a request's `_meta` (web `parsePermissionToolCall`). */
class ParsedPermissionMetaTest {

    private fun parse(json: String) = ParsedPermission.parse(Json.parseToJsonElement(json))

    @Test
    fun `the heading prefers the agent's description, but not one that is the command`() {
        assertEquals("List files", parse("""{"title":"ls","rawInput":{"command":"ls"},"_meta":{"claudeCode":{"title":"List files"}}}""").description)
        assertEquals("Run command?", parse("""{"title":"Run","rawInput":{"command":"ls"},"_meta":{"permission":{"title":"Run command?"}}}""").description)
        // claude-agent-acp 0.79.0: the heading IS the command; the model's own label replaces it.
        assertEquals(
            "Lists the folder",
            parse("""{"title":"ls -la","rawInput":{"command":"ls -la","description":"Lists the folder"},"_meta":{"permission":{"title":"ls -la"}}}""").description,
        )
        assertNull(parse("""{"title":"ls","rawInput":{"command":"ls"}}""").description)
    }

    @Test
    fun `reason and defaultToNo read only a version 1 block`() {
        val p = parse("""{"rawInput":{"command":"rm x"},"_meta":{"permission":{"version":1,"description":"Needs write access","defaultToNo":true}}}""")
        assertEquals("Needs write access", p.reason)
        assertTrue(p.defaultToNo)
        val reshaped = parse("""{"rawInput":{"command":"rm x"},"_meta":{"permission":{"version":2,"description":"x","defaultToNo":true}}}""")
        assertNull(reshaped.reason)
        assertFalse(reshaped.defaultToNo)
        assertFalse(parse("""{"_meta":{"permission":{"version":1,"defaultToNo":"true"}}}""").defaultToNo)
    }

    @Test
    fun `a network ask is read only off Claude Code's sandbox tool`() {
        assertEquals("api.x.com", parse("""{"rawInput":{"host":"api.x.com"},"_meta":{"claudeCode":{"toolName":"SandboxNetworkAccess"}}}""").networkHost)
        assertNull(parse("""{"rawInput":{"host":"api.x.com"}}""").networkHost)
    }

    @Test
    fun `a command leaf is the command even when it looks like JSON or a patch`() {
        assertEquals("[ -f package.json ] && pnpm test", parse("""{"rawInput":{"command":"[ -f package.json ] && pnpm test"}}""").command)
        assertEquals("ls", parse("""{"rawInput":{"command":"[\"ls\"]"}}""").command)
        // A wrapper below a command key reads conservatively again.
        assertNull(parse("""{"rawInput":{"args":{"payload":"{\"query\":"}}}""").command)
    }

    @Test
    fun `the request names its call`() {
        assertEquals("c-1", parse("""{"toolCallId":"c-1"}""").toolCallId)
        assertEquals("c-2", parse("""{"call_id":"c-2","id":"other"}""").toolCallId)
        assertNull(parse("""{"toolCallId":"  "}""").toolCallId)
    }
}
