package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AcpAgentInfo
import app.codeg.android.core.model.AgentSkillItem
import app.codeg.android.core.model.AgentSkillsListResult
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.WorkspaceFileEntry
import app.codeg.android.core.model.WorkspaceSearchRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suggestion controller end to end, without a server: what each trigger lists, what a pick
 *  writes, and the mention tokens it registers (iOS `ComposerSuggestionModelTests`). */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerSuggestionsTest {

    private fun cmd(name: String) = AvailableCommandInfo(name, "$name command")

    private fun skill(id: String) = AgentSkillItem(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        scope = "project",
        layout = "skill_directory",
        path = "/s/$id",
    )

    private fun agent(type: AgentType, name: String, sortOrder: Int, enabled: Boolean = true) =
        AcpAgentInfo(agentType = type, name = name, description = "$name agent", enabled = enabled, sortOrder = sortOrder)

    private class FakeSource(var agent: AgentType, var root: String?) : ComposerSuggestions.Source {
        var commands: (suspend (Boolean) -> List<AvailableCommandInfo>?)? = null
        var agents: List<AcpAgentInfo> = emptyList()
        val searches = mutableListOf<Pair<String, WorkspaceSearchRequest>>()
        val cancels = mutableListOf<WorkspaceSearchRequest>()
        var files: suspend (String) -> List<WorkspaceFileEntry> = { emptyList() }
        override fun context() = ComposerSuggestions.Context("http://server", agent, root)
        override suspend fun fetchCommands(allowProbe: Boolean) = commands?.invoke(allowProbe)
        override suspend fun runtimeSkills(agent: AgentType, workspaceRoot: String?) = AgentSkillsListResult(skills = emptyList())
        override suspend fun agents() = agents
        override suspend fun searchFiles(root: String, query: String, limit: Int, request: WorkspaceSearchRequest): List<WorkspaceFileEntry> {
            searches += query to request
            return files(query)
        }
        override suspend fun cancelFileSearch(request: WorkspaceSearchRequest) {
            cancels += request
        }
    }

    private fun TestScope.model(agent: AgentType = AgentType.CLAUDE_CODE, root: String? = null): Pair<ComposerSuggestions, FakeSource> {
        val source = FakeSource(agent, root)
        // A catalog of its own: the shared one is process-wide.
        val model = ComposerSuggestions(backgroundScope, source, ComposerCatalog(), detachedScope = backgroundScope, consumerId = "c")
        return model to source
    }

    private fun ComposerSuggestions.type(text: String, caret: Int = text.length) = update(text, caret, focused = true)

    private val ComposerSuggestions.rows get() = panel.value?.rows.orEmpty()

    // region `/`

    @Test
    fun `slash lists the agent's commands ranked and picks the token`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review"), cmd("compact"), cmd("\$deploy")))
        model.type("/re")
        assertEquals(listOf(ComposerSuggestionRow.Command(cmd("review"), skill = false)), model.rows)
        val picked = model.pick(model.rows[0], "/re")
        assertEquals(ComposerPick("/review ", 8), picked)
        assertNull("a pick closes the menu", model.panel.value)
    }

    @Test
    fun `a codex skill command is inserted as its bare dollar token`() = runTest {
        val (model, _) = model(AgentType.CODEX)
        model.adoptLiveCommands(listOf(cmd("\$deploy")))
        model.type("please /\$d")
        assertEquals("please \$deploy ", model.pick(model.rows.first(), "please /\$d")?.text)
    }

    @Test
    fun `no space is added when whitespace already follows`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review")))
        model.type("/re now", caret = 3)
        assertEquals(ComposerPick("/review now", 7), model.pick(model.rows.first(), "/re now"))
    }

    @Test
    fun `an empty list closes the menu`() = runTest {
        val (model, _) = model()
        model.type("/")
        runCurrent()
        assertNull(model.panel.value)
    }

    @Test
    fun `a list on its way shows a loading row that owns the keys`() = runTest {
        val (model, source) = model()
        val gate = CompletableDeferred<List<AvailableCommandInfo>?>()
        source.commands = { gate.await() }
        model.type("/")
        runCurrent()
        assertEquals(ComposerPanelState.Commands(emptyList(), loading = true, skillColumn = false, highlighted = 0), model.panel.value)
        assertEquals(ComposerKeyOutcome.Handled, model.handleKey(ComposerKey.ENTER, "/"))
        gate.complete(listOf(cmd("review")))
        runCurrent()
        assertEquals(listOf("cmd:review"), model.rows.map { it.key })
    }

    @Test
    fun `a live list beats a fetch that was already out`() = runTest {
        val (model, source) = model()
        val gate = CompletableDeferred<List<AvailableCommandInfo>?>()
        source.commands = { gate.await() }
        model.type("/")
        runCurrent()
        assertTrue(model.commandsLoading)
        model.adoptLiveCommands(listOf(cmd("fresh")))
        gate.complete(listOf(cmd("stale")))
        runCurrent()
        assertTrue(!model.commandsLoading)
        assertEquals(listOf("fresh"), model.commands.map { it.name })
    }

    @Test
    fun `the cheap read finding nothing goes on to the probe while a slash waits`() = runTest {
        val (model, source) = model()
        val asked = mutableListOf<Boolean>()
        source.commands = { probe -> asked += probe; if (probe) listOf(cmd("init")) else null }
        model.screenDidLoad()
        runCurrent()
        assertEquals(listOf(false), asked)
        model.type("/")
        runCurrent()
        assertEquals(listOf(false, true), asked)
        assertEquals(listOf("cmd:init"), model.rows.map { it.key })
    }

    @Test
    fun `arrows move the highlight and enter picks it`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review"), cmd("rebase"), cmd("reset")))
        model.type("/re")
        // Prefix ties: shorter first, then input order.
        assertEquals(listOf("cmd:reset", "cmd:review", "cmd:rebase"), model.rows.map { it.key })
        assertEquals(ComposerKeyOutcome.Handled, model.handleKey(ComposerKey.DOWN, "/re"))
        assertEquals(1, model.panel.value?.highlighted)
        assertEquals(ComposerKeyOutcome.Replace("/review ", 8), model.handleKey(ComposerKey.ENTER, "/re"))
    }

    @Test
    fun `keys mid-composition are the input method's`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review"), cmd("rebase"), cmd("reset")))
        model.type("/re")
        for (key in ComposerKey.entries) {
            assertEquals("$key", ComposerKeyOutcome.Ignored, model.handleKey(key, "/re", composing = true))
        }
        assertEquals(0, model.panel.value?.highlighted)
        assertNotNull("Escape mid-composition leaves the menu open", model.panel.value)
    }

    @Test
    fun `escape closes until the text changes`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review")))
        model.type("/re")
        assertNotNull(model.panel.value)
        model.handleKey(ComposerKey.ESCAPE, "/re")
        assertNull(model.panel.value)
        model.type("/re", caret = 2)
        assertNull("moving the caret does not reopen it", model.panel.value)
        model.type("/rev")
        assertNotNull(model.panel.value)
    }

    @Test
    fun `a lost focus or a ranged selection closes it`() = runTest {
        val (model, _) = model()
        model.adoptLiveCommands(listOf(cmd("review")))
        model.type("/re")
        model.update("/re", 3, focused = false)
        assertNull(model.panel.value)
        model.type("/re")
        model.update("/re", null, focused = true)
        assertNull(model.panel.value)
    }

    @Test
    fun `disk skills share the slash menu without shadowed ones`() = runTest {
        val catalog = ComposerCatalog()
        val source = FakeSource(AgentType.OPEN_CODE, null)
        val model = ComposerSuggestions(backgroundScope, source, catalog, detachedScope = backgroundScope)
        model.adoptLiveCommands(listOf(cmd("plan"), cmd("usage")))
        catalog.skills[model.catalogKey()] = ComposerCatalog.SkillsEntry(skills = listOf(skill("plan"), skill("ship")))
        model.type("/")
        assertEquals(listOf("cmd:plan", "cmd:usage", "skill:project:ship"), model.rows.map { it.key })
    }

    @Test
    fun `codex lists its disk skills under dollar only`() = runTest {
        val catalog = ComposerCatalog()
        val source = FakeSource(AgentType.CODEX, null)
        val model = ComposerSuggestions(backgroundScope, source, catalog, detachedScope = backgroundScope)
        model.adoptLiveCommands(listOf(cmd("review")))
        catalog.skills[model.catalogKey()] = ComposerCatalog.SkillsEntry(skills = listOf(skill("ship")))
        model.type("\$sh")
        assertEquals(1, model.rows.size)
        assertEquals("\$ship ", model.pick(model.rows.first(), "\$sh")?.text)
        model.type("/")
        assertEquals(listOf("cmd:review"), model.rows.map { it.key })
    }

    @Test
    fun `the disk scan lands into a dollar menu already open`() = runTest {
        val source = object : ComposerSuggestions.Source by FakeSource(AgentType.CODEX, "/repo") {
            override suspend fun runtimeSkills(agent: AgentType, workspaceRoot: String?) =
                AgentSkillsListResult(skills = listOf(skill("deploy")))
        }
        val model = ComposerSuggestions(backgroundScope, source, ComposerCatalog(), detachedScope = backgroundScope)
        model.type("\$")
        assertNull("nothing yet: the panel stays armed but hidden", model.panel.value)
        runCurrent()
        assertEquals(listOf("skill:project:deploy"), model.rows.map { it.key })
    }

    // endregion

    // region `@`

    @Test
    fun `at lists enabled agents and picks a mention token`() = runTest {
        val (model, source) = model()
        source.agents = listOf(
            agent(AgentType.CODEX, "Codex", sortOrder = 2),
            agent(AgentType.CLAUDE_CODE, "Claude Code", sortOrder = 1),
            agent(AgentType.GEMINI, "Gemini", sortOrder = 0, enabled = false),
        )
        model.type("ask @")
        runCurrent()
        val panel = model.panel.value as ComposerPanelState.Mention
        assertEquals(MentionTab.AGENT, panel.tab)
        assertEquals(listOf("agent:claude_code", "agent:codex"), panel.rows.map { it.key })

        model.type("ask @codex")
        val picked = model.pick(model.rows.first(), "ask @codex")
        assertEquals(ComposerPick("ask @Codex ", 11), picked)
        assertEquals("ask [@Codex](codeg://agent/codex) ", model.encode(picked!!.text))
    }

    @Test
    fun `the caret inside a picked mention does not reopen the panel`() = runTest {
        val (model, source) = model()
        source.agents = listOf(agent(AgentType.CODEX, "Codex", sortOrder = 0))
        model.type("@co")
        runCurrent()
        val picked = model.pick(model.rows.first(), "@co")!!
        model.type(picked.text + "x")
        model.type(picked.text + "x", caret = 3)
        assertNull(model.panel.value)
    }

    @Test
    fun `tab switches the mention tab, and an empty tab declines enter`() = runTest {
        val (model, source) = model()
        source.agents = listOf(agent(AgentType.CODEX, "Codex", sortOrder = 0))
        model.type("@")
        runCurrent()
        assertEquals(ComposerKeyOutcome.Handled, model.handleKey(ComposerKey.TAB, "@"))
        val panel = model.panel.value as ComposerPanelState.Mention
        assertEquals(MentionTab.FILE, panel.tab)
        assertTrue(panel.rows.isEmpty())
        assertEquals(ComposerKeyOutcome.Ignored, model.handleKey(ComposerKey.ENTER, "@"))
    }

    @Test
    fun `files are searched after a debounce, and a stale answer never lands`() = runTest {
        val (model, source) = model(root = "/repo")
        val gates = mutableMapOf<String, CompletableDeferred<List<WorkspaceFileEntry>>>()
        source.files = { q -> gates.getOrPut(q) { CompletableDeferred() }.await() }
        model.type("@ap")
        advanceTimeBy(100)
        model.type("@app")
        advanceTimeBy(200)
        runCurrent()
        // The first query never went out: the second superseded it inside the debounce.
        assertEquals(listOf("app"), source.searches.map { it.first })
        assertEquals(1L, source.searches.single().second.generation)
        val loading = model.panel.value as ComposerPanelState.Mention
        assertEquals(MentionTab.FILE, loading.tab)
        assertTrue(loading.loading && loading.stale)

        gates.getValue("app").complete(listOf(WorkspaceFileEntry("app.ts", "src/app.ts", "file")))
        runCurrent()
        val shown = model.panel.value as ComposerPanelState.Mention
        assertEquals(listOf("file:src/app.ts"), shown.rows.map { it.key })
        val picked = model.pick(shown.rows.first(), "@app")!!
        assertEquals("@src/app.ts ", picked.text)
        assertEquals("[app.ts](file:///repo/src/app.ts) ", model.encode(picked.text))
    }

    @Test
    fun `closing the panel cancels the search the server may still be running`() = runTest {
        val (model, source) = model(root = "/repo")
        source.files = { CompletableDeferred<List<WorkspaceFileEntry>>().await() }
        model.type("@x")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, source.searches.size)
        model.type("@x ")
        runCurrent()
        assertEquals(listOf(source.searches.single().second), source.cancels)
    }

    // endregion

    // region Restore

    @Test
    fun `a restored message reads as tokens and sends as it was`() = runTest {
        val (model, _) = model(root = "/repo")
        val wire = "see [app.ts](file:///repo/src/app.ts) and [@Codex](codeg://agent/codex)"
        val shown = model.restore(wire)
        assertEquals("see @src/app.ts and @Codex", shown)
        assertEquals(2, model.references.size)
        assertEquals(wire, model.encode(shown))
        model.update("", 0, focused = true)
        assertTrue("a cleared draft takes its mentions with it", model.references.isEmpty())
    }

    // endregion
}
