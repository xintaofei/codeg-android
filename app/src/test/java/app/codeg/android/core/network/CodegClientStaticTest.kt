package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.CursorAuthStatus
import app.codeg.android.core.model.CursorModelsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bare-value / null / URL helpers on [CodegClient]. */
class CodegClientStaticTest {

    @Test
    fun `decodeConnectionId reads a bare JSON string`() {
        assertEquals("conn-123", CodegClient.decodeConnectionId("\"conn-123\""))
    }

    @Test
    fun `decodeConnectionId rejects null and empty`() {
        assertThrows(ApiError.Decoding::class.java) { CodegClient.decodeConnectionId("null") }
        assertThrows(ApiError.Decoding::class.java) { CodegClient.decodeConnectionId("\"\"") }
    }

    @Test
    fun `isJsonNull recognises null and empty bodies only`() {
        assertTrue(CodegClient.isJsonNull("null"))
        assertTrue(CodegClient.isJsonNull("   "))
        assertTrue(CodegClient.isJsonNull(""))
        assertFalse(CodegClient.isJsonNull("{}"))
        assertFalse(CodegClient.isJsonNull("\"x\""))
    }

    @Test
    fun `normalizeBaseUrl trims trailing slash and whitespace`() {
        assertEquals("http://host:3080", CodegClient.normalizeBaseUrl("  http://host:3080/  "))
        assertEquals("https://example.com", CodegClient.normalizeBaseUrl("https://example.com"))
    }

    @Test
    fun `decodeAgentList keeps built-in, custom and still-unknown agents`() {
        // Custom (user-registered) ACP agents arrive as `custom:<id>` and must survive
        // the decode — dropping them makes them unselectable for a new session. A wire
        // value from a newer server survives too, keeping its own identity.
        val json = """
            [
              {"agent_type":"claude_code","name":"Claude Code"},
              {"agent_type":"code_buddy","name":"CodeBuddy"},
              {"agent_type":"codex","name":"Codex"},
              {"agent_type":"kimi_code","name":"Kimi Code"},
              {"agent_type":"pi","name":"Pi"},
              {"agent_type":"grok","name":"Grok","grok_config_toml":"[ui]\n","grok_settings":{"permission_mode":"ask","default_reasoning_effort":"high"}},
              {"agent_type":"cursor","name":"Cursor","cursor_cli_config_json":"{}","cursor_settings":{"sandbox_mode":"enabled","permissions_allow":["Shell(ls)"],"permissions_deny":["Shell(rm)"]}},
              {"agent_type":"custom:goose","name":"Goose","icon_url":"data:image/png;base64,AAA","custom_source":"registry"},
              {"agent_type":"future_agent_9000","name":"Future"}
            ]
        """.trimIndent()
        val agents = CodegClient.decodeAgentList(json)
        assertEquals(
            listOf(
                AgentType.CLAUDE_CODE, AgentType.CODE_BUDDY, AgentType.CODEX,
                AgentType.KIMI_CODE, AgentType.PI, AgentType.GROK, AgentType.CURSOR,
                AgentType.Custom("goose"), AgentType.Unknown("future_agent_9000"),
            ),
            agents.map { it.agentType },
        )
        // The grok row's snake_case config payload maps onto the camelCase fields.
        val grok = agents.first { it.agentType == AgentType.GROK }
        assertEquals("ask", grok.grokSettings?.permissionMode)
        assertEquals("high", grok.grokSettings?.defaultReasoningEffort)
        // Same for the cursor row's projection (snake_case list keys included).
        val cursor = agents.first { it.agentType == AgentType.CURSOR }
        assertEquals("enabled", cursor.cursorSettings?.sandboxMode)
        assertEquals(listOf("Shell(ls)"), cursor.cursorSettings?.permissionsAllow)
        assertEquals(listOf("Shell(rm)"), cursor.cursorSettings?.permissionsDeny)
        // The custom row keeps the fields only it has.
        val goose = agents.first { it.agentType == AgentType.Custom("goose") }
        assertEquals("data:image/png;base64,AAA", goose.iconUrl)
        assertEquals("registry", goose.customSource)
        // …and the wire form round-trips, so a session can actually be created with it.
        assertEquals("custom:goose", goose.agentType.wire)
    }

    @Test
    fun `an adapter agent with nothing installed still reports available`() {
        // Pins the two fields that LOOK like they say the adapter is missing and don't, which
        // is why the header states nothing about it and `AdapterNote` — one preflight, all the
        // facts — says it all instead.
        //
        // `available` is hardcoded true for every npx-distributed agent (commands/acp.rs), and
        // both ACP adapters are npx: it is the platform gate, never the install one.
        // `installed_version` isn't it either — the server resolves the command first and
        // probes the version separately, and that probe fails on its own. Only
        // `AdapterInfo.adapter_installed` answers the question.
        val agents = CodegClient.decodeAgentList(
            """
            [{"agent_type":"claude_code","name":"Claude Code","distribution_type":"npx",
              "available":true,"installed_version":null,"is_acp_adapter":true}]
            """.trimIndent(),
        )

        val claude = agents.single()
        assertTrue(claude.isAcpAdapter)
        assertTrue("npx agents are always `available`", claude.available)
        assertNull(claude.installedVersion)
    }

    @Test
    fun `a non-adapter agent is not flagged as one`() {
        // Absent must read as false: the flag is what asks for the adapter probe, and probing
        // an agent codeg installs directly would either answer null (a wasted round trip on
        // every detail open) or, if it ever didn't, describe a package that does not exist.
        val agents = CodegClient.decodeAgentList("""[{"agent_type":"gemini","name":"Gemini"}]""")
        assertFalse(agents.single().isAcpAdapter)
    }

    @Test
    fun `decodeAgentList never yields duplicate agent-type keys`() {
        // Two unrecognised types alongside the real claude_code must stay three DISTINCT
        // rows: collapsing them onto claude_code produced a duplicate `agentType` key,
        // which crashes LazyColumn.
        val json = """
            [
              {"agent_type":"future_a","name":"A"},
              {"agent_type":"claude_code","name":"Claude Code"},
              {"agent_type":"future_b","name":"B"},
              {"agent_type":"custom:goose","name":"Goose"},
              {"agent_type":"custom:goose","name":"Goose again"}
            ]
        """.trimIndent()
        val agents = CodegClient.decodeAgentList(json)
        assertEquals(
            listOf(
                AgentType.Unknown("future_a"), AgentType.CLAUDE_CODE,
                AgentType.Unknown("future_b"), AgentType.Custom("goose"),
            ),
            agents.map { it.agentType },
        )
        val keys = agents.map { it.agentType.wire }
        assertEquals("keys must be unique", keys.distinct().size, keys.size)
    }

    @Test
    fun `a malformed custom id is kept verbatim rather than parsed`() {
        // The server rejects these slugs outright, so they should never arrive — but if
        // one does, it must not become a Custom whose wire form differs from what came in.
        val json = """
            [
              {"agent_type":"custom:","name":"Empty"},
              {"agent_type":"custom:../escape","name":"Escape"},
              {"agent_type":"custom:.hidden","name":"Hidden"}
            ]
        """.trimIndent()
        val agents = CodegClient.decodeAgentList(json)
        assertEquals(
            listOf("custom:", "custom:../escape", "custom:.hidden"),
            agents.map { it.agentType.wire },
        )
        assertTrue(agents.all { it.agentType is AgentType.Unknown })
    }

    @Test
    fun `cursor probe responses map their snake_case wire onto the models`() {
        // Verbatim shapes from a live `acp_cursor_auth_status` / `acp_cursor_list_models`.
        val status = CodegJson.response.decodeFromString(
            CursorAuthStatus.serializer(),
            """{"installed":true,"is_authenticated":true,"raw_status":"authenticated",
               "email":"dev@example.com","membership":null,"error":null,
               "binary_path":"/cache/cursor-agent"}""",
        )
        assertTrue(status.installed)
        assertTrue(status.isAuthenticated)
        assertEquals("authenticated", status.rawStatus)
        assertEquals("dev@example.com", status.email)
        assertEquals("/cache/cursor-agent", status.binaryPath)

        val models = CodegJson.response.decodeFromString(
            CursorModelsResult.serializer(),
            """{"models":[{"id":"auto","label":"Auto (current, default)","is_default":true},
                {"id":"gpt-5.3-codex","label":"","is_default":false}],
                "default_model":"auto","error":null}""",
        )
        assertEquals(2, models.models.size)
        assertEquals("auto", models.defaultModel)
        assertTrue(models.models[0].isDefault)
        assertEquals("Auto (current, default)", models.models[0].displayLabel)
        // A bare id with no label falls back to the id in the picker.
        assertEquals("gpt-5.3-codex", models.models[1].displayLabel)
    }
}
