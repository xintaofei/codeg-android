package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectOption
import app.codeg.android.core.model.SessionModeInfo
import app.codeg.android.core.model.SessionModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the web's `src/lib/session-settings.test.ts` (codeg 0.44), plus this client's
 *  own gates and its summary text. */
class SessionSettingsTest {

    private fun select(
        id: String,
        name: String,
        category: String?,
        current: String,
        options: List<Any>,
        recommended: String? = null,
    ) = SessionConfigOption(
        id = id,
        name = name,
        category = category,
        kind = SessionConfigKind(
            type = "select",
            currentValue = current,
            options = options.map { if (it is String) SessionConfigSelectOption(it, it, null) else it as SessionConfigSelectOption },
        ),
        recommendedValue = recommended,
    )

    private fun boolean(id: String, name: String, on: Boolean) = SessionConfigOption(
        id = id,
        name = name,
        kind = SessionConfigKind(type = "boolean", currentValue = on.toString()),
    )

    private fun build(
        configOptions: List<SessionConfigOption>,
        modes: List<SessionModeInfo> = emptyList(),
        currentModeId: String? = null,
    ) = SessionSettings.build(configOptions, modes, currentModeId, modeTitle = "Mode")

    private fun choice(setting: SessionSetting?): SessionChoice = checkNotNull(setting?.choice) { "not a choice" }

    private fun toggle(setting: SessionSetting?): SettingControl.Toggle =
        checkNotNull(setting?.control as? SettingControl.Toggle) { "not a switch" }

    // What claude-agent-acp 0.88 advertises, in its own order.
    private val claude = listOf(
        select(
            "mode", "Mode", "mode", "default",
            listOf(SessionConfigSelectOption("default", "Manual", "Always ask"), SessionConfigSelectOption("plan", "Plan", "Plan first")),
        ),
        select(
            "model", "Model", "model", "opus",
            listOf(SessionConfigSelectOption("opus", "Opus 5.5", "Best for everyday"), SessionConfigSelectOption("haiku", "Haiku 5.5", null)),
            recommended = "opus",
        ),
        select("effort", "Effort", "thought_level", "high", listOf("low", "medium", "high"), recommended = "medium"),
        select("fast", "Fast mode", "model_config", "off", listOf("on", "off")),
    )

    // order

    @Test
    fun `puts the model first, then effort, then mode, other selects, switches`() {
        // codex-acp 2.2.2's order: mode, collaboration mode, model, effort, fast.
        val settings = build(
            listOf(
                select("mode", "Mode", "mode", "agent", listOf("read-only", "agent")),
                select("collaboration_mode", "Collaboration mode", "collaboration_mode", "default", listOf("default", "plan")),
                select("fast_mode", "Fast mode", "model_config", "on", listOf("off", "on")),
                select("model", "Model", "model", "gpt-6", listOf("gpt-6", "gpt-5")),
                select("reasoning_effort", "Reasoning effort", "thought_level", "low", listOf("low", "high")),
            ),
        )
        assertEquals(
            listOf("config:model", "config:reasoning_effort", "config:mode", "config:collaboration_mode", "config:fast_mode"),
            settings.map { it.key },
        )
    }

    @Test
    fun `keeps the agent's order within a rank`() {
        // Cline 3.0.62: both Provider and Model are `category: "model"`.
        val settings = build(
            listOf(
                select("provider", "Provider", "model", "cline", listOf("cline", "anthropic")),
                select("model", "Model", "model", "a", listOf("a", "b")),
                boolean("auto_approve", "Auto-approve tools", true),
                select("mode", "Session Mode", "mode", "act", listOf("plan", "act")),
            ),
        )
        assertEquals(listOf("config:provider", "config:model", "config:mode", "config:auto_approve"), settings.map { it.key })
    }

    @Test
    fun `ranks the legacy mode list as a mode`() {
        val settings = build(
            emptyList(),
            listOf(SessionModeInfo("default", "Default", "Prompts"), SessionModeInfo("yolo", "YOLO", null)),
            "yolo",
        )
        val mode = settings[0]
        assertEquals(SelectorKey.Mode, mode.target)
        assertEquals("Mode", mode.title)
        assertEquals("YOLO", choice(mode).currentLabel)
        assertEquals("YOLO", mode.summary)
    }

    // summary

    @Test
    fun `leaves out a default value and a switch that is off`() {
        assertEquals(listOf("Opus 5.5", "high", null, null), build(claude).map { it.summary })
    }

    @Test
    fun `names a switch by its title while it is on`() {
        val settings = build(
            claude.take(3) + select("fast", "Fast mode", "model_config", "on", listOf("on", "off")) +
                boolean("auto_approve", "Auto-approve tools", true),
        )
        assertEquals(listOf("Opus 5.5", "high", null, "Fast mode", "Auto-approve tools"), settings.map { it.summary })
    }

    @Test
    fun `always shows the model, even one whose value is default`() {
        // claude-agent-acp before 0.76 keeps a `default` model row.
        val settings = build(
            listOf(
                select(
                    "model", "Model", null, "default",
                    listOf(SessionConfigSelectOption("default", "Default (recommended)", null), SessionConfigSelectOption("opus", "Opus", null)),
                ),
                select("effort", "Effort", "thought_level", "default", listOf("default", "max")),
                // A shown value elsewhere, so the all-omitted fallback stays out of it.
                select("mode", "Mode", "mode", "plan", listOf("default", "plan")),
            ),
        )
        assertEquals(listOf("Default (recommended)", null, "plan"), settings.map { it.summary })
    }

    @Test
    fun `falls back to the first value when everything was left out`() {
        // Gemini CLI: only its legacy modes, on `default`.
        val settings = build(
            emptyList(),
            listOf(SessionModeInfo("default", "Default", "Prompts for approval"), SessionModeInfo("plan", "Plan", "Read-only mode")),
            "default",
        )
        assertEquals(listOf("Default"), settings.map { it.summary })
    }

    @Test
    fun `never falls back onto a switch`() {
        assertEquals(listOf<String?>(null), build(listOf(boolean("auto_approve", "Auto-approve tools", false))).map { it.summary })
    }

    // controls

    @Test
    fun `makes the model a list and a short select a segmented control`() {
        val (model, effort, mode) = build(claude)
        assertTrue(choice(model).isList)
        assertFalse(choice(model).searchable)
        assertFalse(choice(effort).isList)
        assertFalse(choice(mode).isList)
    }

    @Test
    fun `searches a model list longer than eight`() {
        val model = build(listOf(select("model", "Model", "model", "m0", (0 until 9).map { "m$it" })))[0]
        assertTrue(choice(model).isList)
        assertTrue(choice(model).searchable)
    }

    @Test
    fun `turns any select longer than eight into a searchable list`() {
        val eight = (0 until 8).map { "v$it" }
        assertFalse(choice(build(listOf(select("a", "A", null, "v0", eight)))[0]).isList)
        val long = choice(build(listOf(select("a", "A", null, "v0", eight + "v8")))[0])
        assertTrue(long.isList)
        assertTrue(long.searchable)
    }

    @Test
    fun `reads a boolean option as a switch that sends true and false`() {
        assertEquals(SettingControl.Toggle(false, "true", "false"), toggle(build(listOf(boolean("auto_approve", "Auto", false)))[0]))
    }

    @Test
    fun `reads an on-off select as a switch, keeping the agent's own values`() {
        assertEquals(SettingControl.Toggle(true, "On", "Off"), toggle(build(listOf(select("fast", "Fast", null, "On", listOf("Off", "On"))))[0]))
        assertEquals(
            SettingControl.Toggle(false, "true", "false"),
            toggle(build(listOf(select("flag", "Flag", null, "false", listOf("true", "false"))))[0]),
        )
    }

    @Test
    fun `keeps an on-off select segmented while its value is neither`() {
        // Kiro's thinking toggle reports `default` until a state is set; a switch would claim
        // "off" for it.
        val thinking = build(
            listOf(
                select("model", "Model", "model", "k", listOf("k")),
                select("thinking", "Thinking", "thought_level", "default", listOf("on", "off")),
            ),
        )[1]
        assertFalse(choice(thinking).isList)
        assertNull(thinking.summary)
    }

    @Test
    fun `does not read a three-value select as a switch`() {
        assertTrue(build(listOf(select("fast", "Fast", null, "on", listOf("on", "off", "auto"))))[0].control is SettingControl.Choice)
    }

    // values

    @Test
    fun `labels a grouped model by its prefix-stripped name`() {
        val model = build(listOf(select("model", "Model", "model", "anthropic/claude-opus", listOf("anthropic/claude-opus", "openai/gpt-4o"))))[0]
        val setting = choice(model)
        assertEquals(listOf("anthropic", "openai"), setting.groups.map { it.name })
        assertEquals("claude-opus", setting.currentLabel)
        assertEquals("claude-opus", model.summary)
        // The value itself is never rewritten.
        assertEquals("anthropic/claude-opus", setting.currentValue)
    }

    @Test
    fun `carries the current description and the recommendation`() {
        val (model, effort) = build(claude)
        assertEquals("Best for everyday", choice(model).currentDescription)
        assertEquals("opus", choice(model).recommendedValue)
        assertEquals("medium", choice(effort).recommendedValue)
    }

    @Test
    fun `falls back to the raw value when it names no option`() {
        val model = build(listOf(select("model", "Model", "model", "gone", listOf("a"))))[0]
        assertEquals("gone", choice(model).currentLabel)
        assertEquals("gone", model.summary)
    }

    @Test
    fun `targets config options by id`() {
        assertEquals(SelectorKey.Config("model"), build(claude)[0].target)
    }

    @Test
    fun `uses MiniMax Code's native model config option`() {
        val model = build(
            listOf(select("model", "Model", "model", "m:minimax:MiniMax-M2.7:u", listOf("m:minimax:MiniMax-M2.7:u", "m:custom%3Aprovider:vendor%2Fmodel:v:fast"))),
        )[0]
        assertTrue(choice(model).isModel)
        assertEquals("m:minimax:MiniMax-M2.7:u", choice(model).currentValue)
        assertEquals(SelectorKey.Config("model"), model.target)
        assertEquals(listOf("m:minimax:MiniMax-M2.7:u", "m:custom%3Aprovider:vendor%2Fmodel:v:fast"), choice(model).groups[0].options.map { it.value })
    }

    @Test
    fun `shows a filtered current model without offering it again`() {
        val setting = choice(build(listOf(select("model", "Model", "model", "relay/disabled", listOf("relay/allowed"))))[0])
        assertEquals("relay/disabled", setting.currentLabel)
        assertEquals(listOf("relay/allowed"), setting.groups.flatMap { g -> g.options.map { it.value } })
    }

    // This client's own rules.

    @Test
    fun `a selection laid over the catalog is what the settings read`() {
        val settings = SessionSettings.build(claude, emptyList(), null, "Mode", selectedConfig = mapOf("model" to "haiku", "fast" to "on"))
        assertEquals(listOf("Haiku 5.5", "high", null, "Fast mode"), settings.map { it.summary })
    }

    @Test
    fun `the legacy modes show only for an agent with no config options`() {
        val modes = SessionModeState("plan", listOf(SessionModeInfo("default", "Default"), SessionModeInfo("plan", "Plan")))
        assertEquals(listOf("mode"), SessionSettings.forCatalog(modes, emptyList(), null, emptyMap(), "Mode").map { it.key })
        assertEquals(
            listOf("config:model", "config:effort", "config:mode", "config:fast"),
            SessionSettings.forCatalog(modes, claude, null, emptyMap(), "Mode").map { it.key },
        )
        // The sheet's own pick wins over the catalog's current mode.
        assertEquals("Default", SessionSettings.forCatalog(modes, emptyList(), "default", emptyMap(), "Mode")[0].choice?.currentLabel)
    }

    @Test
    fun `a mode list with no current mode is still offered, with nothing checked`() {
        val modes = listOf(SessionModeInfo("ask", "Ask"), SessionModeInfo("auto", "Auto"))
        val mode = build(emptyList(), modes, null).single()
        assertEquals("", choice(mode).currentValue)
        assertNull(mode.summary)
    }

    @Test
    fun `an option this build can't draw stays as a read-only row and says nothing on the button`() {
        val unreadable = SessionConfigOption("auto_approve", "Auto", kind = SessionConfigKind(type = "boolean", currentValue = "maybe"))
        val newer = SessionConfigOption("x", "X", kind = SessionConfigKind(type = "slider", currentValue = "3"))
        val (a, b) = build(listOf(unreadable, newer))
        assertEquals(SettingControl.Unsupported("maybe", isBoolean = true), a.control)
        assertEquals(SettingControl.Unsupported("3"), b.control)
        assertEquals(listOf<String?>(null, null), listOf(a.summary, b.summary))
    }

    @Test
    fun `summary parts and the spoken value`() {
        val settings = build(claude)
        assertEquals(listOf("Opus 5.5", "high"), SessionSettings.summaryParts(settings))
        assertEquals("Model Opus 5.5, Effort high, Mode Manual, Fast mode Off", SessionSettings.accessibilityValue(settings, "On", "Off"))
    }

    @Test
    fun `a short summary is counted in code points`() {
        assertTrue(SessionSettings.isShortSummary("Opus 5.5"))
        assertFalse(SessionSettings.isShortSummary("Opus 5.5 x"))
        // Eight emoji are eight, not sixteen UTF-16 units.
        assertTrue(SessionSettings.isShortSummary("😀".repeat(8)))
    }
}
