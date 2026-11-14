package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kimi reasoning declaration (`capabilities` / `support_efforts` /
 * `default_effort`) is written by the desktop panel only, but an `apikey` save from
 * here rebuilds the whole managed model block — so it has to survive a round-trip
 * through this client or the composer's Thinking picker silently disappears.
 */
class AgentConfigKimiTest {

    @Test
    fun `projection carries the reasoning declaration`() {
        val config = KimiManagedConfig.parse(
            """{"interfaceType":"kimi","modelId":"kimi-k2.7-code","maxContextSize":262144,
               "capabilities":["thinking","image_in","video_in","tool_use"],
               "supportEfforts":["low","medium","high"],"defaultEffort":"medium",
               "hasManagedBlock":true}""",
        )
        assertEquals(listOf("thinking", "image_in", "video_in", "tool_use"), config.capabilities)
        assertEquals(listOf("low", "medium", "high"), config.supportEfforts)
        assertEquals("medium", config.defaultEffort)
        assertTrue(config.reasoningEnabled)
        assertFalse(config.alwaysThinking)
    }

    @Test
    fun `always_thinking is the always-on flavour`() {
        val config = KimiManagedConfig.parse("""{"capabilities":["always_thinking","tool_use"]}""")
        assertTrue(config.reasoningEnabled)
        assertTrue(config.alwaysThinking)
    }

    @Test
    fun `a block without reasoning keys reads as off`() {
        val config = KimiManagedConfig.parse(
            """{"interfaceType":"kimi","modelId":"kimi-k2.7-code","hasManagedBlock":true}""",
        )
        assertTrue(config.capabilities.isEmpty())
        assertTrue(config.supportEfforts.isEmpty())
        assertNull(config.defaultEffort)
        assertFalse(config.reasoningEnabled)
        assertFalse(config.alwaysThinking)
    }

    @Test
    fun `blank and non-string effort entries are dropped`() {
        val config = KimiManagedConfig.parse(
            """{"supportEfforts":["low","  ","",7,"  high  "],"capabilities":["thinking"]}""",
        )
        assertEquals(listOf("low", "high"), config.supportEfforts)
    }

    @Test
    fun `the declaration reaches the wire camelCase`() {
        val json = CodegJson.request.encodeToString(
            UpdateKimiCodeConfigBody(
                mode = "apikey",
                model = "kimi-k2.7-code",
                reasoningEnabled = true,
                alwaysThinking = false,
                supportEfforts = listOf("low", "high"),
                defaultEffort = "high",
            ),
        )
        assertTrue(json.contains("\"reasoningEnabled\":true"))
        assertTrue(json.contains("\"alwaysThinking\":false"))
        assertTrue(json.contains("\"supportEfforts\":[\"low\",\"high\"]"))
        assertTrue(json.contains("\"defaultEffort\":\"high\""))
    }

    @Test
    fun `a body with no declaration omits the fields entirely`() {
        // `login` clears the managed block and `raw` writes verbatim toml, so neither
        // sends these — and an omitted field must stay omitted rather than encode null.
        val json = CodegJson.request.encodeToString(UpdateKimiCodeConfigBody(mode = "login"))
        assertFalse(json, json.contains("reasoningEnabled"))
        assertFalse(json, json.contains("supportEfforts"))
    }
}
