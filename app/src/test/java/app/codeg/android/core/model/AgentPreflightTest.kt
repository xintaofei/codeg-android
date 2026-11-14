package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `acp_preflight` payload, narrowed to the adapter explainer.
 *
 * These fields are the ONLY thing `AdapterNote` says, and the wrong answer is worse than none:
 * telling someone who has `claude` on their PATH that Claude Code isn't installed reads as a
 * bug in codeg, which is the confusion this data exists to end. The server ships facts and no
 * prose, so the client is the only place they can go wrong.
 */
class AgentPreflightTest {

    private val json: Json = CodegJson.response

    private fun preflight(raw: String): AgentPreflight =
        json.decodeFromString(AgentPreflight.serializer(), raw)

    @Test
    fun `an adapter agent carries the full split`() {
        val result = preflight(
            """
            {
              "agent_type": "claude_code",
              "agent_name": "Claude Code",
              "passed": true,
              "checks": [
                {"check_id":"node_available","label":"Node.js","status":"pass","message":"Node.js v22 available","fixes":[]}
              ],
              "adapter": {
                "adapter_package": "@zed-industries/claude-code-acp@0.65.0",
                "adapter_cmd": "claude-agent-acp",
                "adapter_installed": false,
                "native_cmd": "claude",
                "native_label": "Claude Code CLI",
                "native_path": "/opt/homebrew/bin/claude",
                "shared_config_dir": "~/.claude",
                "docs_url": "https://example.invalid/adapters"
              }
            }
            """.trimIndent(),
        )

        assertEquals(AgentType.CLAUDE_CODE, result.agentType)
        assertTrue(result.passed)
        val adapter = requireNotNull(result.adapter)
        assertEquals("@zed-industries/claude-code-acp@0.65.0", adapter.adapterPackage)
        assertEquals("claude-agent-acp", adapter.adapterCmd)
        assertFalse(adapter.adapterInstalled)
        assertEquals("claude", adapter.nativeCmd)
        assertEquals("Claude Code CLI", adapter.nativeLabel)
        assertEquals("/opt/homebrew/bin/claude", adapter.nativePath)
        assertEquals("~/.claude", adapter.sharedConfigDir)
    }

    @Test
    fun `the checks array is ignored, not a decode failure`() {
        // Deliberately unmodelled — its rows are backend English about the server's own
        // toolchain. Confirming they are skipped rather than tolerated by luck: if a future
        // check shape threw, it would take the adapter card down with it.
        val result = preflight(
            """
            {"agent_type":"codex","agent_name":"Codex","passed":false,
             "checks":[{"check_id":"npm_available","label":"npm","status":"fail","message":"npm not found",
                        "fixes":[{"label":"Install Node","kind":"open_url","payload":"https://nodejs.org"}]}],
             "adapter":null}
            """.trimIndent(),
        )

        assertFalse(result.passed)
        assertNull(result.adapter)
    }

    @Test
    fun `a non-adapter agent has no adapter`() {
        // Ten of the twelve built-ins install the vendor CLI itself. Their card must not
        // render at all — an explainer about a package that doesn't exist for them would be
        // worse than the plain "Not installed" it replaced.
        val result = preflight("""{"agent_type":"gemini","agent_name":"Gemini","passed":true,"checks":[]}""")

        assertNull(result.adapter)
    }

    @Test
    fun `an adapter whose vendor CLI was not found reports no path`() {
        // The two "we looked and found yours at X" strings hinge on this being null rather
        // than blank — a blank path would print "Found your own Claude Code CLI at ."
        val adapter = requireNotNull(
            preflight(
                """
                {"agent_type":"claude_code","agent_name":"Claude Code","passed":true,"checks":[],
                 "adapter":{"adapter_package":"p","adapter_cmd":"c","adapter_installed":true,
                            "native_cmd":"claude","native_label":"Claude Code CLI","native_path":null,
                            "shared_config_dir":"~/.claude","docs_url":"https://example.invalid"}}
                """.trimIndent(),
            ).adapter,
        )

        assertNull(adapter.nativePath)
        assertTrue(adapter.adapterInstalled)
    }
}
