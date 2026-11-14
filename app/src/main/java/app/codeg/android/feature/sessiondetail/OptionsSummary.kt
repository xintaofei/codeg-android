package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the options button summarizes: the catalog this client last held for [agent], with the
 * selection laid over it.
 *
 * The sheet keeps its own copy while it is open (and is what edits it), but it is gone the moment
 * the sheet closes, and the button must still read right afterwards — after a pick whose apply
 * outlives the sheet, a push from the agent, or a turn that moved its own mode. So this copy is
 * kept by the controller, fed by the same three sources the sheet folds: its load
 * ([OptionsSummaryState.loaded]), the live selector pushes ([OptionsSummaryState.pushed]), and the
 * applies ([OptionsSummaryState.beginApply] / [OptionsSummaryState.settleApply]).
 *
 * Never fetched for its own sake beyond a live snapshot read: until a catalog exists (a live
 * session published one, or the sheet loaded it) the button is just the agent's avatar. Nothing
 * here ever starts a probe agent.
 */
internal data class OptionsSummary(
    val agent: AgentType,
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val modeId: String? = null,
    /** Values by option id, over each option's own current value. */
    val config: Map<String, String> = emptyMap(),
) {
    fun settings(modeTitle: String): List<SessionSetting> =
        SessionSettings.forCatalog(modes, configOptions, modeId, config, modeTitle)

    /** This summary with [value] as [selector]'s value, or without one when [value] is null. */
    fun withValue(selector: SelectorKey, value: String?): OptionsSummary = when (selector) {
        SelectorKey.Mode -> copy(modeId = value)
        is SelectorKey.Config -> copy(config = if (value != null) config + (selector.id to value) else config - selector.id)
    }

    fun valueOf(selector: SelectorKey): String? = when (selector) {
        SelectorKey.Mode -> modeId
        is SelectorKey.Config -> config[selector.id]
    }
}

/** The revision each half of a [LiveSelectors] stood at, to fold in only what came after. */
internal data class SelectorRevisions(val config: Int = 0, val modes: Int = 0, val currentMode: Int = 0) {
    companion object {
        /** Everything [live] holds counts as already covered. */
        fun of(live: LiveSelectors?): SelectorRevisions = SelectorRevisions(
            config = live?.configOptions?.revision ?: 0,
            modes = live?.modes?.revision ?: 0,
            currentMode = live?.currentModeId?.revision ?: 0,
        )

        /** Only what was stamped [revision] or later is new. */
        fun before(revision: Int): SelectorRevisions = SelectorRevisions(revision - 1, revision - 1, revision - 1)
    }
}

/**
 * Fold the pushed values newer than [since] in, exactly as the open sheet folds them: a selector
 * with an apply in flight ([applying]) keeps its optimistic value — the push most likely predates
 * the agent processing the change — and a replaced config catalog drops the values of options it
 * no longer lists ([mergeLiveConfigValues]).
 */
internal fun OptionsSummary.foldLive(live: LiveSelectors, since: SelectorRevisions, applying: Set<SelectorKey>): OptionsSummary {
    if (live.agent != agent) return this
    var next = this
    live.configOptions?.takeIf { it.revision > since.config }?.let { pushed ->
        val held = applying.mapNotNullTo(mutableSetOf()) { (it as? SelectorKey.Config)?.id }
        next = next.copy(configOptions = pushed.value, config = mergeLiveConfigValues(next.config, pushed.value, held))
    }
    live.modes?.takeIf { it.revision > since.modes }?.let { next = next.copy(modes = it.value) }
    live.currentModeId?.takeIf { it.revision > since.currentMode && SelectorKey.Mode !in applying }?.let {
        next = next.copy(modeId = it.value)
    }
    return next
}

/**
 * The controller's [OptionsSummary] and the bookkeeping that keeps it honest. Main-thread
 * confined, like the rest of [AgentOptionsController].
 */
internal class OptionsSummaryState {
    private val _state = MutableStateFlow<OptionsSummary?>(null)
    val state: StateFlow<OptionsSummary?> = _state.asStateFlow()

    private var nextTicket = 0L

    /** The newest apply started on each agent's selector. Only it may settle what is shown:
     *  several can overlap once a context switch has released the sheet's row lock, and they can
     *  finish in either order. */
    private val newest = mutableMapOf<ApplyRegistry.Key, Long>()

    /** The applies still running. */
    private val running = mutableSetOf<Long>()

    /** The selectors whose newest apply is still running: a push leaves their pick alone. */
    private fun applyingFor(agent: AgentType): Set<SelectorKey> =
        newest.filter { (key, ticket) -> key.agent == agent && ticket in running }.keys.mapTo(mutableSetOf()) { it.selector }

    /**
     * The sheet's load for [agent] settled. It replaces the summary wholesale — it is the newest
     * full read — then takes the pushes that landed while it ran ([since] is where [live] stood
     * when it began), as the sheet itself does.
     *
     * A load that came back with nothing at all is not adopted: a failed read looks the same, and
     * replacing a catalog the button was showing with no catalog would only lose it.
     */
    fun loaded(agent: AgentType, data: AgentOptionsData, live: LiveSelectors?, since: SelectorRevisions) {
        if (data.modes == null && data.configOptions.isEmpty()) return
        var next = OptionsSummary(agent, data.modes, data.configOptions, data.initialModeId, data.initialConfig)
        if (live != null) next = next.foldLive(live, since, applyingFor(agent))
        _state.value = next
    }

    /** A selector push stamped [revision] was folded into [live]. */
    fun pushed(agent: AgentType, live: LiveSelectors, revision: Int) {
        if (live.agent != agent) return
        // A summary left over from another agent is replaced, not merged into: the same option id
        // there means something else.
        val base = _state.value?.takeIf { it.agent == agent } ?: OptionsSummary(agent)
        val next = base.foldLive(live, SelectorRevisions.before(revision), applyingFor(agent))
        if (next != _state.value) _state.value = next
    }

    /** An apply in flight, as [beginApply] handed it out: its place in line, and the value it
     *  replaced on screen (what a failure puts back). */
    class Ticket internal constructor(internal val id: Long, val previous: String?)

    /** An apply of [value] to [selector] started: show it at once, as the sheet does. */
    fun beginApply(agent: AgentType, selector: SelectorKey, value: String): Ticket {
        val ticket = ++nextTicket
        newest[ApplyRegistry.Key(agent, selector)] = ticket
        running += ticket
        val current = _state.value?.takeIf { it.agent == agent } ?: return Ticket(ticket, null)
        val previous = current.valueOf(selector)
        _state.value = current.withValue(selector, value)
        return Ticket(ticket, previous)
    }

    /**
     * That apply ended: [outcome] is null when it was cancelled, which leaves the optimistic value
     * standing (an apply is only cancelled with the screen). A success shows the value the agent
     * settled on; a failure puts the replaced value back, but only over this apply's own value.
     *
     * Only the newest apply on the selector settles anything — whichever order they finish in.
     * An older one describes a pick since replaced (the sheet drops its result by epoch for the
     * same reason), so it can neither overwrite the newer pick nor its outcome.
     */
    fun settleApply(agent: AgentType, selector: SelectorKey, value: String, ticket: Ticket, outcome: ApplyOutcome?) {
        running -= ticket.id
        val key = ApplyRegistry.Key(agent, selector)
        if (newest[key] != ticket.id) return
        newest.remove(key)
        val current = _state.value?.takeIf { it.agent == agent } ?: return
        _state.value = when (outcome) {
            is ApplyOutcome.Applied -> current.withValue(selector, outcome.value)
            is ApplyOutcome.Failed -> if (current.valueOf(selector) == value) current.withValue(selector, ticket.previous) else current
            null -> current
        }
    }
}
