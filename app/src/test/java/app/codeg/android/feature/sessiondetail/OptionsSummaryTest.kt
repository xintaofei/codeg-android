package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectOption
import app.codeg.android.core.model.SessionModeInfo
import app.codeg.android.core.model.SessionModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The options button's summary state ([OptionsSummaryState]): how a load, the live pushes and
 *  the applies combine — the same rules the open sheet follows. */
class OptionsSummaryTest {

    private val agent = AgentType.CLAUDE_CODE

    private fun option(id: String, current: String, vararg values: String) = SessionConfigOption(
        id = id,
        name = id,
        category = if (id == "model") "model" else null,
        kind = SessionConfigKind(type = "select", currentValue = current, options = values.map { SessionConfigSelectOption(it, it) }),
    )

    private val catalog = listOf(option("model", "opus", "opus", "haiku"), option("effort", "high", "low", "high"))

    private fun loaded(state: OptionsSummaryState, live: LiveSelectors? = null, since: SelectorRevisions = SelectorRevisions()) =
        state.loaded(agent, AgentOptionsData(configOptions = catalog, initialConfig = mapOf("model" to "opus", "effort" to "high")), live, since)

    private fun summary(state: OptionsSummaryState) = state.state.value?.settings("Mode")?.let(SessionSettings::summaryParts)

    @Test
    fun `nothing is summarized until a catalog exists`() {
        val state = OptionsSummaryState()
        assertNull(state.state.value)
        // A load that came back empty (a failed read looks the same) is not adopted either.
        state.loaded(agent, AgentOptionsData(), null, SelectorRevisions())
        assertNull(state.state.value)
    }

    @Test
    fun `a load is shown, with the pushes that landed while it ran folded over it`() {
        val state = OptionsSummaryState()
        val pushed = LiveSelectors(agent).foldSelectorPush(
            AcpEvent.SessionConfigOptions(listOf(option("model", "haiku", "opus", "haiku"), option("effort", "high", "low", "high"))),
            revision = 3,
        )
        // Captured before the push: it is newer than the load.
        loaded(state, pushed, since = SelectorRevisions(config = 2))
        assertEquals(listOf("haiku", "high"), summary(state))
        // Captured after it: the load already covers it.
        loaded(state, pushed, since = SelectorRevisions.of(pushed))
        assertEquals(listOf("opus", "high"), summary(state))
    }

    @Test
    fun `a live push alone is enough to summarize a session nobody opened the sheet on`() {
        val state = OptionsSummaryState()
        val live = LiveSelectors(agent).foldSelectorPush(AcpEvent.SessionConfigOptions(catalog), revision = 1)
        state.pushed(agent, live, revision = 1)
        assertEquals(listOf("opus", "high"), summary(state))
    }

    @Test
    fun `a bare mode change moves the mode within the catalog already held`() {
        val state = OptionsSummaryState()
        val modes = SessionModeState("default", listOf(SessionModeInfo("default", "Default"), SessionModeInfo("plan", "Plan")))
        var live = LiveSelectors(agent).foldSelectorPush(AcpEvent.SessionModes(modes), revision = 1)
        state.pushed(agent, live, 1)
        assertEquals(listOf("Default"), summary(state))
        live = live.foldSelectorPush(AcpEvent.ModeChanged("plan"), revision = 2)
        state.pushed(agent, live, 2)
        assertEquals(listOf("Plan"), summary(state))
    }

    @Test
    fun `an apply shows at once and settles on what the agent reported`() {
        val state = OptionsSummaryState()
        loaded(state)
        val previous = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        assertEquals("opus", previous.previous)
        assertEquals(listOf("haiku", "high"), summary(state))
        // The agent normalized the pick.
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", previous, ApplyOutcome.Applied("haiku-latest", remembered = true))
        assertEquals("haiku-latest", state.state.value?.config?.get("model"))
    }

    @Test
    fun `a failed apply puts the previous value back, unless something newer replaced it`() {
        val state = OptionsSummaryState()
        loaded(state)
        val previous = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", previous, ApplyOutcome.Failed(rolledBack = true))
        assertEquals(listOf("opus", "high"), summary(state))

        val again = state.beginApply(agent, SelectorKey.Config("effort"), "low")
        // A later load moved the value on meanwhile: the failure doesn't drag it back.
        loaded(state)
        state.settleApply(agent, SelectorKey.Config("effort"), "low", again, ApplyOutcome.Failed(rolledBack = true))
        assertEquals(listOf("opus", "high"), summary(state))
    }

    @Test
    fun `a push during an apply leaves that selector's pick alone`() {
        val state = OptionsSummaryState()
        loaded(state)
        state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        // Emitted before the agent processed the change: still the old model, a new effort.
        val live = LiveSelectors(agent).foldSelectorPush(
            AcpEvent.SessionConfigOptions(listOf(option("model", "opus", "opus", "haiku"), option("effort", "low", "low", "high"))),
            revision = 5,
        )
        state.pushed(agent, live, 5)
        assertEquals(listOf("haiku", "low"), summary(state))
    }

    @Test
    fun `a cancelled apply leaves the pick shown`() {
        val state = OptionsSummaryState()
        loaded(state)
        val previous = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", previous, outcome = null)
        assertEquals(listOf("haiku", "high"), summary(state))
    }

    @Test
    fun `an older apply settling never overrides a newer one still running`() {
        val state = OptionsSummaryState()
        loaded(state)
        val first = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        val second = state.beginApply(agent, SelectorKey.Config("model"), "opus")
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", first, ApplyOutcome.Applied("haiku", remembered = true))
        assertEquals("opus", state.state.value?.config?.get("model"))
        state.settleApply(agent, SelectorKey.Config("model"), "opus", second, ApplyOutcome.Applied("opus", remembered = true))
        assertEquals("opus", state.state.value?.config?.get("model"))
    }

    @Test
    fun `an older apply finishing after the newer one changes nothing`() {
        // A slow pick made before a draft's folder switch, then a newer one after it that
        // finishes first (normalized by the agent).
        val state = OptionsSummaryState()
        loaded(state)
        val older = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        val newer = state.beginApply(agent, SelectorKey.Config("model"), "opus")
        state.settleApply(agent, SelectorKey.Config("model"), "opus", newer, ApplyOutcome.Applied("opus-latest", remembered = true))
        assertEquals("opus-latest", state.state.value?.config?.get("model"))
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", older, ApplyOutcome.Applied("haiku", remembered = true))
        assertEquals("opus-latest", state.state.value?.config?.get("model"))
        // Nor does its failure put back the value it replaced.
        val olderStill = state.beginApply(agent, SelectorKey.Config("effort"), "low")
        val newerStill = state.beginApply(agent, SelectorKey.Config("effort"), "high")
        state.settleApply(agent, SelectorKey.Config("effort"), "high", newerStill, ApplyOutcome.Failed(rolledBack = true))
        // The newer one's failure restores what it replaced: the older pick still on screen.
        assertEquals("low", state.state.value?.config?.get("effort"))
        state.settleApply(agent, SelectorKey.Config("effort"), "low", olderStill, ApplyOutcome.Failed(rolledBack = true))
        assertEquals("low", state.state.value?.config?.get("effort"))
    }

    @Test
    fun `pushes are held only while the selector's newest apply runs`() {
        val state = OptionsSummaryState()
        loaded(state)
        val older = state.beginApply(agent, SelectorKey.Config("model"), "haiku")
        val newer = state.beginApply(agent, SelectorKey.Config("model"), "opus")
        state.settleApply(agent, SelectorKey.Config("model"), "opus", newer, ApplyOutcome.Applied("opus", remembered = true))
        // The older one is still running, but it no longer owns the selector.
        val live = LiveSelectors(agent).foldSelectorPush(AcpEvent.SessionConfigOptions(listOf(option("model", "sonnet", "opus", "sonnet"))), revision = 9)
        state.pushed(agent, live, 9)
        assertEquals("sonnet", state.state.value?.config?.get("model"))
        state.settleApply(agent, SelectorKey.Config("model"), "haiku", older, ApplyOutcome.Applied("haiku", remembered = true))
        assertEquals("sonnet", state.state.value?.config?.get("model"))
    }

    @Test
    fun `another agent's pushes replace the summary rather than merge into it`() {
        val state = OptionsSummaryState()
        loaded(state)
        val other = AgentType.CODEX
        val live = LiveSelectors(other).foldSelectorPush(AcpEvent.SessionConfigOptions(listOf(option("model", "gpt", "gpt"))), revision = 1)
        state.pushed(other, live, 1)
        assertEquals(other, state.state.value?.agent)
        assertEquals(listOf("gpt"), summary(state))
    }
}
