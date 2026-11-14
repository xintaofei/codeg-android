package app.codeg.android.feature.sessiondetail

import androidx.lifecycle.ViewModel
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

// Agent-options (mode / config selector) state: live selector pushes, apply ownership and outcomes.

/**
 * How an options apply ended.
 *
 * Applying to the live session and remembering the pick for the next one are separate writes
 * that can fail separately, so "it worked" and "it will still be set next time" are separate
 * facts. Collapsing them to a single value/null is what let a failed change come back on the
 * next connect with nothing on screen having said so.
 */
sealed interface ApplyOutcome {
    /** The agent took it (or the reconcile budget elapsed with it still optimistically shown).
     *  [remembered] is false when the preference store refused the write. */
    data class Applied(val value: String, val remembered: Boolean) : ApplyOutcome

    /** The apply reported failure and the sheet should revert. [rolledBack] is false when the
     *  preference could not be put back, so the store still holds the value that just failed. */
    data class Failed(val rolledBack: Boolean) : ApplyOutcome
}

/**
 * Mode / config-option state pushed by the live event stream.
 *
 * Two sources, because the stream is only open during a turn or a reattach — the ViewModel
 * closes it when the turn ends. Live events fill this while a turn runs, which is what it
 * exists for (an option moving under an open sheet: the agent flipping its own, as cline does
 * with `auto_approve`, or another client setting one mid-turn). Outside a turn,
 * [SessionDetailViewModel.refreshLiveSelectors] reads the same values over HTTP and folds them
 * in the same way, so an option that moves just as a turn ends is not stranded by the closing
 * stream. A change made while nothing at all is watching still waits for the sheet's next load.
 *
 * The revisions increase on every push and never reset. They are what lets a sheet adopt only
 * pushes that arrived AFTER its load: the state outlives the stream that produced it, so
 * without them a sheet opened a turn later would fold last turn's values back over the fresher
 * snapshot it just loaded.
 *
 * Each half is stamped separately, drawing from one counter. A shared revision would mean every
 * `mode_changed` re-published the config payload under a fresh number — and a config value the
 * sheet's load had already corrected would be overwritten by the copy held here.
 *
 * [agent] is the agent the values were observed for. A draft's pickers can be retargeted, and
 * the same option id means something else on another agent.
 */
data class LiveSelectors(
    val agent: AgentType,
    /** The connection these values describe. Dropped wholesale when the stream attaches to a
     *  different one — see [SessionDetailViewModel.resetSelectorsForConnection]. */
    val connectionId: String? = null,
    /**
     * The mode catalog, only ever set by a `session_modes` push or a snapshot. Held apart from
     * [currentModeId] because `mode_changed` carries the id and nothing else — folding it into
     * a catalog-less state here would make a bare id indistinguishable from an agent that
     * genuinely advertises no modes, and the sheet would have to guess which it was looking at.
     */
    val modes: Pushed<SessionModeState>? = null,
    /** The mode in force, from either kind of push. */
    val currentModeId: Pushed<String>? = null,
    /** The config catalog + its current values, always the agent's whole list. Empty is a real
     *  answer ("nothing to configure"); null means nothing has been pushed. */
    val configOptions: Pushed<List<SessionConfigOption>>? = null,
    /** Whether this connection has finished publishing its initial selectors. See
     *  [AcpEvent.SelectorsReady] — it is what makes an empty [configOptions] readable. */
    val selectorsReady: Boolean = false,
    /** An empty catalog arrived before the latch and is waiting for it to say which it was. See
     *  [SessionDetailViewModel.offerConfigOptions]. */
    val emptyConfigHeld: Boolean = false,
)

/**
 * A value the live stream pushed, paired with the revision at which it last CHANGED.
 *
 * Pairing them is what keeps a push of one selector from re-publishing another's stale payload
 * under a fresh number: the sheet compares the revision it is about to apply against the one it
 * already applied FOR THAT VALUE, and a value nobody touched carries an unchanged revision.
 * Revisions come from one counter and never reset, so they also order a push against the sheet's
 * own load.
 */
data class Pushed<out T>(val value: T, val revision: Int)

/**
 * Fold one selector push into the state held for a connection, stamping [revision] onto exactly
 * the values it changes and leaving every other revision where it was.
 *
 * Pure so the sequence can be tested without a ViewModel — the ordering rules here (a parked
 * empty catalog, a bare `mode_changed` that must not republish the catalog) are the part that
 * two review rounds got wrong. Events other than the four selector pushes are no-ops.
 */
internal fun LiveSelectors.foldSelectorPush(event: AcpEvent, revision: Int): LiveSelectors = when (event) {
    is AcpEvent.SessionConfigOptions -> offerConfigOptions(event.configOptions, revision)
    AcpEvent.SelectorsReady -> copy(selectorsReady = true)
        // The latch is also the answer for a catalog parked below: an agent with nothing to
        // configure emits exactly `[] → selectors_ready`, so dropping that empty list and never
        // revisiting it would leave a previous connection's controls on screen for good.
        .let { if (emptyConfigHeld) it.offerConfigOptions(emptyList(), revision) else it }
    is AcpEvent.SessionModes -> copy(
        modes = Pushed(event.modes, revision),
        currentModeId = event.modes.currentModeId?.let { Pushed(it, revision) } ?: currentModeId,
    )
    // Carries the id alone, which is why the two are held apart: the catalog it indexes into is
    // whatever the last `session_modes` (or the sheet's own load) established, and restamping it
    // here would republish a catalog the sheet may have already corrected.
    is AcpEvent.ModeChanged -> copy(currentModeId = Pushed(event.modeId, revision))
    else -> this
}

/**
 * Publish a config catalog — or park an empty one until the readiness latch can say what it
 * meant.
 *
 * An empty catalog IS an answer ("nothing to configure"), and second-guessing it would leave the
 * sheet offering a control the session no longer exposes. But during the setup window some
 * agents publish an empty list and push the real one right after, which the backend's own probe
 * has to wait out (`manager.rs::wait_for_session_options`) — and the sheet IS reachable inside
 * that window, because the options button stays live through a send.
 *
 * So an empty list arriving before the latch is held rather than dropped: a real catalog
 * following it clears the hold, and a latch arriving first proves the empty list was the truth
 * and publishes it then.
 *
 * The latch is proof, not a settling point — the backend's own probe still waits 500ms past it
 * for a straggling update an agent emits in the same tick. Publishing immediately is right here
 * anyway: unlike the probe, which reads once and disconnects, this state keeps receiving, so a
 * catalog arriving after the latch simply pushes and wins.
 */
internal fun LiveSelectors.offerConfigOptions(options: List<SessionConfigOption>, revision: Int): LiveSelectors =
    if (options.isNotEmpty() || selectorsReady) {
        copy(configOptions = Pushed(options, revision), emptyConfigHeld = false)
    } else {
        copy(emptyConfigHeld = true)
    }

/** The modes + config a session exposes plus the selection to highlight — authoritative
 *  when sourced from a live snapshot, or the remembered/last-used pick for a draft. */
data class AgentOptionsData(
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    val initialModeId: String? = null,
    val initialConfig: Map<String, String> = emptyMap(),
)

internal fun reconcileTimeout(lastSeen: String?, desired: String, previous: String?): String {
    val seen = lastSeen ?: return desired
    // No baseline (the option had no value on screen) means no way to tell a value the agent
    // moved to from one it was already holding — so it isn't evidence of a normalization.
    val baseline = previous ?: return desired
    return if (seen == baseline) desired else seen
}

/**
 * Which selector an apply is reconciling.
 *
 * A type rather than a magic string because `"mode"` is a real config-option id, not just the
 * mode sentinel: codeg synthesizes an "Approval Preset" option with exactly that id for Codex
 * (`ensure_codex_mode_option`), and its guard implies an agent may advertise one too. Keyed by
 * string, reconciling that option would read the session's *mode* instead of the option's own
 * value and could adopt an unrelated one.
 */
internal sealed interface SelectorKey {
    data object Mode : SelectorKey
    data class Config(val id: String) : SelectorKey
}

/**
 * Per-selector bookkeeping shared by every in-flight apply: who owns a selector's stored value,
 * and whether the value currently on screen for it was ever observed on a live session.
 *
 * Keyed by agent as well as selector because [SelectorPrefsStore] is: an apply for agent A can
 * only ever have overwritten A's entry, so B claiming the same selector must not make A think
 * someone else owns its rollback.
 *
 * Main-thread confined — every mutation happens before an apply suspends, or from the options
 * sheet — so plain collections are enough.
 */
internal class ApplyRegistry {

    data class Key(val agent: AgentType, val selector: SelectorKey)

    /** The last apply to have *successfully written* each key's stored value. */
    private val owners = mutableMapOf<Key, Long>()

    /** The last apply started for each key, whether or not its write landed. Ordering only —
     *  it decides which completion is allowed to publish provenance. */
    private val latest = mutableMapOf<Key, Long>()

    private val observed = mutableSetOf<Key>()
    private var lastToken = 0L
    private var activity = 0L

    /**
     * How many times an apply has started or finished, ever.
     *
     * A caller that captures this before suspending can tell whether a pick it never saw has
     * moved a value under it. Both ends count: an apply that COMPLETES mid-read changes the
     * answer exactly as much as one that starts.
     */
    val generation: Long get() = activity

    /** Start an apply on [key] and return its token. Does NOT confer ownership of the stored
     *  value — see [claimWrite]. */
    fun begin(key: Key): Long {
        activity += 1
        val token = ++lastToken
        latest[key] = token
        return token
    }

    /** Record that an apply finished, whatever its outcome. See [generation]. */
    fun settle() {
        activity += 1
    }

    /**
     * Record that the apply holding [token] wrote [key]'s stored value, making it responsible
     * for undoing that write.
     *
     * Separate from [begin] because a save can fail: an apply that never wrote must not take
     * ownership away from an earlier one that did and still owes a rollback.
     */
    fun claimWrite(key: Key, token: Long) {
        owners[key] = token
    }

    /**
     * Whether the apply holding [token] is still the last one to have written [key].
     *
     * A rollback has to prove this rather than compare values: OFF → ON → OFF leaves the store
     * holding exactly what a stalled older OFF apply wrote, so undoing on value equality alone
     * would revert a newer, possibly-succeeding pick.
     */
    fun owns(key: Key, token: Long): Boolean = owners[key] == token

    /** Whether [token] is the most recently started apply for [key]. */
    fun isLatest(key: Key, token: Long): Boolean = latest[key] == token

    /**
     * Record whether [key]'s displayed value came from somewhere authoritative.
     *
     * Only an observed value can serve as [reconcileTimeout]'s baseline. An apply that timed out
     * leaves an *optimistic* value on screen, and treating that as observed on the next apply
     * would let the agent's untouched default be misread as a normalization.
     */
    fun setObserved(key: Key, observedValue: Boolean) {
        if (observedValue) observed += key else observed -= key
    }

    /** [setObserved] for a whole sheet load, replacing whatever the previous load left. */
    fun setObserved(agent: AgentType, selectors: Collection<SelectorKey>, observedValue: Boolean) {
        selectors.forEach { setObserved(Key(agent, it), observedValue) }
    }

    fun isObserved(key: Key): Boolean = key in observed
}

/** Every selector this snapshot advertises. A selector can appear here with no current value —
 *  what matters to the caller is that this load covered it. */
internal fun SessionSnapshot.selectorKeys(): List<SelectorKey> =
    configOptions.map { SelectorKey.Config(it.id) } + listOfNotNull(SelectorKey.Mode.takeIf { modes != null })

/** The authoritative current value the snapshot reports for a selector. */
internal fun SessionSnapshot.selectionFor(key: SelectorKey): String? = when (key) {
    SelectorKey.Mode -> modes?.currentModeId
    is SelectorKey.Config -> configOptions.firstOrNull { it.id == key.id }?.kind?.currentValue
}
