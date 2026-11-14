package app.codeg.android.feature.settings

import app.codeg.android.core.model.AcpAgentInfo
import app.codeg.android.core.model.AgentDraft
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.GrokSettings
import app.codeg.android.core.model.UpdateKimiCodeConfigBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the enable-toggle write-payload selection (provider-clobber race). */
class AgentsViewModelTest {

    private fun agent(type: AgentType, env: Map<String, String> = emptyMap(), providerId: Int? = null) =
        AcpAgentInfo(agentType = type, env = env, modelProviderId = providerId)

    @Test
    fun `toggle uses the live row even when its provider id is null`() {
        // Captured snapshot is stale-linked to provider 5; the live row was just unlinked.
        val captured = agent(AgentType.CLAUDE_CODE, env = mapOf("OLD" to "1"), providerId = 5)
        val liveRow = agent(AgentType.CLAUDE_CODE, env = mapOf("NEW" to "2"), providerId = null)

        val src = toggleWriteSource(listOf(liveRow), captured)

        // Must NOT fall back to the stale provider 5 / old env.
        assertNull(src.modelProviderId)
        assertEquals(mapOf("NEW" to "2"), src.env)
    }

    @Test
    fun `toggle keeps a live provider link`() {
        val captured = agent(AgentType.GEMINI, providerId = null)
        val liveRow = agent(AgentType.GEMINI, providerId = 9)
        assertEquals(9, toggleWriteSource(listOf(liveRow), captured).modelProviderId)
    }

    @Test
    fun `toggle falls back to the captured snapshot when no live row exists`() {
        val captured = agent(AgentType.CODEX, env = mapOf("K" to "v"), providerId = 3)
        val src = toggleWriteSource(emptyList(), captured)
        assertEquals(3, src.modelProviderId)
        assertEquals(mapOf("K" to "v"), src.env)
    }

    @Test
    fun `grok dropdown save patches the fresh config, preserving edits since the panel opened`() {
        // Panel opened showing permission_mode=ask; the user flips it to always-approve.
        val snapshot = AcpAgentInfo(
            agentType = AgentType.GROK,
            grokConfigToml = "[ui]\npermission_mode = \"ask\"\n",
            grokSettings = GrokSettings(permissionMode = "ask"),
        )
        val draft = AgentDraft(
            grokConfigTomlText = "[ui]\npermission_mode = \"ask\"\n",
            grokPermissionMode = "always-approve",
        )
        // Meanwhile another window/MCP added a custom model to the LIVE file.
        val fresh = "[ui]\npermission_mode = \"ask\"\n\n[model.corp]\nbase_url = \"https://x\"\n"
        val out = grokConfigTomlForSave(draft, snapshot, fresh)!!
        assertTrue(out.contains("permission_mode = \"always-approve\"")) // user's edit applied
        assertTrue(out.contains("[model.corp]"))                         // fresh external edit preserved
    }

    @Test
    fun `grok api-key-only save writes nothing to config`() {
        val snapshot = AcpAgentInfo(
            agentType = AgentType.GROK,
            grokConfigToml = "[ui]\npermission_mode = \"ask\"\n",
            grokSettings = GrokSettings(permissionMode = "ask"),
        )
        // Dropdowns/raw match the snapshot (only the API key changed, on the env path).
        val draft = AgentDraft(grokConfigTomlText = "[ui]\npermission_mode = \"ask\"\n", grokPermissionMode = "ask")
        assertNull(grokConfigTomlForSave(draft, snapshot, "ignored fresh"))
    }

    @Test
    fun `grok raw edit is sent verbatim and ignores the fresh base`() {
        val snapshot = AcpAgentInfo(agentType = AgentType.GROK, grokConfigToml = "[ui]\npermission_mode = \"ask\"\n")
        val draft = AgentDraft(grokConfigTomlText = "[ui]\npermission_mode = \"always-approve\"\n# mine\n")
        assertEquals(draft.grokConfigTomlText, grokConfigTomlForSave(draft, snapshot, "[different]\n"))
    }

    @Test
    fun `cursor sends the raw cli-config only when the user edited it`() {
        val original = AcpAgentInfo(agentType = AgentType.CURSOR, cursorCliConfigJson = """{"version":1}""")
        val untouched = AgentDraft(cursorCliConfigText = """{"version":1}""")
        // Unedited: the backend merges the structured patch onto the FRESH on-disk file.
        assertNull(cursorCliConfigForSave(untouched, original))
        // Edited under Advanced: the text wins verbatim.
        assertEquals("""{"version":2}""", cursorCliConfigForSave(untouched.copy(cursorCliConfigText = """{"version":2}"""), original))
    }

    @Test
    fun `a raw cursor edit suppresses the structured patch so it can't overwrite it`() {
        // The server applies the patch ON TOP of the raw text it was sent, so a form
        // seeded before the edit would clobber hand-edited sandbox / rule keys.
        val original = AcpAgentInfo(agentType = AgentType.CURSOR, cursorCliConfigJson = """{"version":1}""")
        val edited = AgentDraft(
            cursorCliConfigText = """{"version":1,"permissions":{"allow":["Shell(rg)"]}}""",
            cursorSandboxMode = "enabled",
            cursorAllowRules = listOf("Shell(ls)"),
        )
        assertNull(cursorStructuredForSave(edited, original))
        // Untouched raw text ⇒ the patch is the only thing sent, merged onto the fresh file.
        assertEquals(
            "enabled",
            cursorStructuredForSave(edited.copy(cursorCliConfigText = """{"version":1}"""), original)?.sandboxMode,
        )
    }

    @Test
    fun `cursor structured patch omits an unset sandbox and drops blank rules`() {
        val original = AcpAgentInfo(agentType = AgentType.CURSOR)
        val draft = AgentDraft(
            cursorSandboxMode = "",
            cursorAllowRules = listOf("Shell(ls)", "  ", ""),
            cursorDenyRules = emptyList(),
        )
        val patch = cursorStructuredForSave(draft, original)!!
        // "" sandbox means "leave sandbox.mode alone" — omitted, not sent as a value.
        assertNull(patch.sandboxMode)
        // An "add rule" row the user never filled in must not reach the file.
        assertEquals(listOf("Shell(ls)"), patch.permissionsAllow)
        // An emptied list is a real "no rules": sent as [], NOT omitted.
        assertEquals(emptyList<String>(), patch.permissionsDeny)
        assertFalse(patch.permissionsDeny == null)

        assertEquals("enabled", cursorStructuredForSave(draft.copy(cursorSandboxMode = "enabled"), original)?.sandboxMode)
    }

    // region Kimi reasoning declaration
    //
    // An `apikey` save rebuilds the managed model block server-side and reads an absent
    // `reasoningEnabled` as "off", so a body that omits these four fields DELETES the
    // Thinking picker's declaration from config.toml. This panel has no reasoning editor,
    // so the save must hand back whatever the live projection reports.

    @Test
    fun `kimi apikey save carries the live reasoning declaration`() {
        val fresh = """{"interfaceType":"kimi","modelId":"kimi-k2.7-code",
            "capabilities":["thinking","image_in","video_in","tool_use"],
            "supportEfforts":["low","medium","high"],"defaultEffort":"medium",
            "hasManagedBlock":true}"""
        val body = UpdateKimiCodeConfigBody(mode = "apikey", model = "kimi-k2.7-code")

        val out = kimiBodyForSave(body, fresh)

        assertEquals(true, out.reasoningEnabled)
        assertEquals(false, out.alwaysThinking)
        assertEquals(listOf("low", "medium", "high"), out.supportEfforts)
        assertEquals("medium", out.defaultEffort)
        // Everything the panel itself edits is untouched.
        assertEquals("kimi-k2.7-code", out.model)
    }

    @Test
    fun `kimi apikey save preserves the always-on reasoning flavour`() {
        val out = kimiBodyForSave(
            UpdateKimiCodeConfigBody(mode = "apikey"),
            """{"capabilities":["always_thinking","tool_use"],"supportEfforts":["high"]}""",
        )
        assertEquals(true, out.reasoningEnabled)
        assertEquals(true, out.alwaysThinking)
    }

    @Test
    fun `kimi apikey save declares reasoning off when the disk has none`() {
        val out = kimiBodyForSave(
            UpdateKimiCodeConfigBody(mode = "apikey"),
            """{"interfaceType":"kimi","modelId":"m","hasManagedBlock":true}""",
        )
        assertEquals(false, out.reasoningEnabled)
        assertEquals(emptyList<String>(), out.supportEfforts)
        assertNull(out.defaultEffort)
    }

    @Test
    fun `kimi save survives a missing or unparseable projection`() {
        // No Kimi row in the list, or a config.toml that could not be projected: there is
        // nothing to preserve, and it must not throw on the way to the write.
        for (raw in listOf(null, "", "not json")) {
            val out = kimiBodyForSave(UpdateKimiCodeConfigBody(mode = "apikey"), raw)
            assertEquals(false, out.reasoningEnabled)
            assertTrue(out.supportEfforts.isNullOrEmpty())
        }
    }

    @Test
    fun `kimi login and raw saves are passed through untouched`() {
        // Neither rebuilds the block from a spec, so injecting a declaration would be
        // noise at best — login clears the block, raw writes verbatim toml.
        val declared = """{"capabilities":["thinking"],"supportEfforts":["high"]}"""
        for (mode in listOf("login", "raw")) {
            val out = kimiBodyForSave(UpdateKimiCodeConfigBody(mode = mode), declared)
            assertNull(out.reasoningEnabled)
            assertNull(out.alwaysThinking)
            assertNull(out.supportEfforts)
            assertNull(out.defaultEffort)
        }
    }
    // endregion
}
