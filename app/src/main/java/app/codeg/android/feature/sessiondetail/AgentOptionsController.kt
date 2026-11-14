package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.datastore.SelectorPrefs
import app.codeg.android.core.datastore.SelectorPrefsStore
import app.codeg.android.core.model.AcpEvent
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionModeState
import app.codeg.android.core.model.SessionSnapshot
import app.codeg.android.core.network.CodegClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded snapshot poll after an options apply (~2.3s total) — `set_*` only enqueues
 *  the change server-side, so we reconcile against the authoritative snapshot. */
private const val RECONCILE_ATTEMPTS = 6

/** Wall-clock cap on that poll. The delays alone sum to 2.35s, but a snapshot request can block
 *  far longer on the HTTP timeout, so the attempt count bounds nothing on its own. */
private const val RECONCILE_BUDGET_MS = 3_000L

/** How long a reopening options sheet waits for applies started by a previous one. Long enough
 *  for one full [RECONCILE_BUDGET_MS] plus its send, so the common case (one apply, still
 *  polling) resolves rather than times out — but bounded, because the sheet shows a spinner
 *  until it returns. */
private const val APPLY_SETTLE_BUDGET_MS = 5_000L

/** Attempts a selector refresh gets before giving up. A read that overlapped an apply is
 *  abandoned rather than folded (see `refreshLiveSelectors`), and abandoning without retrying
 *  would silently drop the correction it was sent to fetch. */
private const val SELECTOR_REFRESH_ATTEMPTS = 3

/** Gap between those attempts. An overlapping apply is only worth waiting out briefly: past
 *  that, the user is actively driving the values and each apply reconciles its own. */
private const val SELECTOR_REFRESH_RETRY_MS = 400L

/**
 * The agent-options sheet's model: what it loads ([loadAgentOptions]), how a pick reaches the
 * agent ([applyMode] / [applyConfig]: remember, resolve or open a connection, enqueue, reconcile,
 * roll back on failure), and the live selector state pushes and re-reads fold into
 * [SessionDetailUiState.liveSelectors].
 *
 * The session's context — client, agent, folder, connection — stays the ViewModel's and is read
 * through [host] at every use, never cached: a draft's agent/folder switch has to be visible to
 * the very next check.
 */
internal class AgentOptionsController(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<SessionDetailUiState>,
    private val selectorPrefs: SelectorPrefsStore,
    private val host: Host,
) {

    /** The session context this reads, owned by the ViewModel. */
    interface Host {
        val client: CodegClient?
        val agent: AgentType
        val folder: FolderDetail?
        val conversationId: Int?
        val externalId: String?
        /** The shared connection. An options connect adopts it when nothing else has. */
        var connectionId: String?
        /** Release [connectionId] (and the stream following it). */
        fun forgetConnection()
        /** `acp_connect` for [agent]/[folder], carrying the remembered selector preferences. */
        suspend fun connect(c: CodegClient, agent: AgentType, folder: FolderDetail?): String
    }

    private val client get() = host.client
    private val agent get() = host.agent
    private val folder get() = host.folder
    private val conversationId get() = host.conversationId
    private val externalId get() = host.externalId
    private var connectionId: String?
        get() = host.connectionId
        set(value) {
            host.connectionId = value
        }

    private fun forgetConnection() = host.forgetConnection()

    /** Fold a pushed selector event (`session_config_options` / `selectors_ready` /
     *  `session_modes` / `mode_changed`) into the live state. */
    fun onSelectorEvent(event: AcpEvent) = pushSelectors { held, revision -> held.foldSelectorPush(event, revision) }

    /** In-flight options-connection resolve plus the agent/folder it targets. Shared across
     *  concurrent applies so a rapid mode+config change spawns ONE connection, but keyed by
     *  agent/folder so a draft's agent/folder switch doesn't reuse a now-stale-context connect.
     *  Mirrors the iOS `AgentOptionsModel.sharedConnection()`. Cleared when the connect settles. */
    private var optionsConnect: OptionsConnect? = null
    /** Selector ownership + value provenance shared across in-flight applies. See [startApply]. */
    private val applyRegistry = ApplyRegistry()
    /** Applies still running, keyed by apply token, so a reopened sheet can wait for them instead
     *  of loading a snapshot the in-flight change hasn't reached yet. */
    private val inFlightApplies = mutableMapOf<Long, InFlightApply>()
    /** Monotonic counter stamped onto every selector push. Never reset — see [LiveSelectors]. */
    private var selectorRevision = 0

    /** What the options button summarizes, kept past the sheet's own copy. See [OptionsSummary]. */
    val summary = OptionsSummaryState()

    /**
     * Drop pushed selector state belonging to a connection other than the one about to be
     * attached.
     *
     * Every field of it — both catalogs, the current values, the readiness latch — is a report
     * about one agent process, and a second connection for the same agent can advertise a
     * different catalog. It cannot be corrected by the new stream either: `event_seq` is counted
     * per connection server-side, so an attach carrying the previous connection's cursor replays
     * only what lies beyond that number and skips the new one's opening selectors entirely.
     * Clearing leaves the sheet's own load — which reads an authoritative snapshot — as the
     * source, which is the right fallback.
     */
    fun resetSelectorsForConnection(conn: String) {
        ui.update { state ->
            val held = state.liveSelectors
            if (held?.connectionId == conn && held.agent == agent) state
            else state.copy(liveSelectors = LiveSelectors(agent = agent, connectionId = conn))
        }
    }

    /**
     * Revise [SessionDetailUiState.liveSelectors] without claiming anything moved — for state
     * the sheet doesn't display, like the readiness latch.
     */
    private fun updateSelectors(update: (LiveSelectors) -> LiveSelectors) {
        ui.update { state ->
            // A state left over from a context we have since switched away from is replaced,
            // not merged into: the same option id on another agent means something else.
            val base = state.liveSelectors?.takeIf { it.agent == agent } ?: LiveSelectors(agent)
            state.copy(liveSelectors = update(base))
        }
    }

    /**
     * [updateSelectors] for a push the sheet should pick up, handing the transform a fresh
     * revision to stamp onto the values it actually changed.
     *
     * Stamping per value rather than per push is the point: the pushes arrive independently, and
     * one shared revision would re-publish an untouched neighbour's payload under a new number
     * every time — enough to overwrite a value the sheet's load had already corrected.
     */
    private fun pushSelectors(update: (LiveSelectors, Int) -> LiveSelectors) {
        selectorRevision += 1
        val revision = selectorRevision
        updateSelectors { update(it, revision) }
        ui.value.liveSelectors?.let { summary.pushed(agent, it, revision) }
    }

    /**
     * Adopt the selectors an attach snapshot carries.
     *
     * This is the ONLY recovery path for selector pushes missed while the socket was down: a
     * reconnect whose gap is too wide to replay is answered with a snapshot instead, the missed
     * `session_config_options` / `session_modes` / `mode_changed` events are never re-sent, and
     * the attach advances the event cursor past them regardless. Same reasoning as the
     * snapshot's `feedback` list.
     *
     * A half the snapshot doesn't carry leaves what was held. Absent is not the same as empty:
     * the server only fills these in once the agent has published, so absent means "nothing to
     * adopt" while empty means "published, and there is nothing to configure" — subject to the
     * same setup-transient exception [offerConfigOptions] makes.
     */
    fun adoptSnapshotSelectors(snapshot: app.codeg.android.core.model.LiveSessionSnapshot) =
        foldSelectorSnapshot(snapshot.modes, snapshot.configOptions, snapshot.selectorsReady)

    /**
     * Fold a snapshot's selectors — from either snapshot shape — into the pushed state.
     *
     * A null half is one the snapshot doesn't carry, and leaves what was held.
     */
    private fun foldSelectorSnapshot(
        modes: SessionModeState?,
        configOptions: List<SessionConfigOption>?,
        selectorsReady: Boolean,
    ) {
        pushSelectors { held, revision ->
            // The latch first: it decides how the config half is read, and a reconnect has no
            // other way to learn it (the `selectors_ready` event it replaces is never re-sent).
            // Assigned rather than latched — the snapshot describes whichever connection this
            // is about, including a fresh one still starting up.
            var next = held.copy(selectorsReady = selectorsReady)
            configOptions?.let { next = next.offerConfigOptions(it, revision) }
            modes?.let { m ->
                next = next.copy(
                    modes = Pushed(m, revision),
                    currentModeId = m.currentModeId?.let { Pushed(it, revision) } ?: next.currentModeId,
                )
            }
            next
        }
    }

    /**
     * A live connection's snapshot, paired with the connection it describes.
     *
     * Two lookups, as in [loadAgentOptions] and for the same reasons — plus one this path needs
     * on its own: a cached id can OUTLIVE its connection, because the server idle-sweeps
     * connections and nothing tells this client. Preferring the cache and giving up when its
     * read fails would let one dead id hide a replacement another client has since resumed, for
     * as long as the sheet stays open. So a failed read re-resolves instead of returning.
     *
     * Resolved to an id rather than read by conversation directly, because these values are only
     * safe to keep while the connection they describe is the one being watched.
     */
    private suspend fun readConnectionSnapshot(c: CodegClient): Pair<String, SessionSnapshot>? {
        val cached = connectionId
        if (cached != null) orNull { c.sessionSnapshot(cached) }?.let { return cached to it }
        val found = conversationId
            ?.let { orNull { c.findConnection(it, externalId, agent)?.connectionId } }
            ?: return null
        val snapshot = orNull { c.sessionSnapshot(found) } ?: return null
        // A replacement answered, so the cached id must stop being the apply target: showing one
        // connection's values while every tap reaches another is worse than either alone —
        // against a dead id the first change fails and reverts, and against a live duplicate it
        // succeeds somewhere the user isn't looking. Dropping it is the conclusion
        // `dropStaleOptionsConnection` already draws from a failed apply, and leaves the next one
        // to re-resolve. Guarded on the turn for the same reason it is there: a live turn's Stop
        // and stream reconnect still need the id.
        //
        // Only here, never on the way past a failed read. Without a replacement to show there is
        // no disagreement to fix, and clearing would orphan a connection over a network blip —
        // and for a draft, whose lookup has no conversation to go on, that is EVERY blip.
        //
        // The caller reads this as a connection change and abandons the attempt it is on; its
        // retry is what folds the replacement in.
        if (found != cached && connectionId == cached && !ui.value.isInFlight) forgetConnection()
        return found to snapshot
    }

    /**
     * Re-read the connection's selectors over HTTP and fold them in as if they had been pushed.
     *
     * The one thing the event stream cannot cover: it is closed at the end of a turn, and the
     * server can still change an option after that. A turn that ends in an *error* is the sharp
     * case — the failure event is emitted before `turn_complete`, and the connection may then
     * spend up to two seconds finalising its transcript before it drains a config update the
     * agent had already queued. This client is long gone by then.
     *
     * Deliberately folded through [LiveSelectors] rather than surfaced as a sheet reload: the
     * push path already holds back an option whose apply is in flight, and it disturbs neither
     * the apply locks nor the sheet's context epoch.
     *
     * That holding-back covers an apply still running when the result lands, but not one that
     * starts or finishes inside this read: the read describes the session as it was BEFORE such
     * a pick, so folding it in would roll back a change the user watched take effect. Any apply
     * activity across the read therefore abandons it — [ApplyRegistry.generation] counts both
     * ends, because a completion moves the value just as a start does.
     *
     * Abandoning is deliberately coarse: an `effort` apply discards a read that was recovering
     * `auto_approve`. Retrying makes that cost latency rather than the correction — up to a
     * point. Sustained churn across all [SELECTOR_REFRESH_ATTEMPTS] attempts, and across the
     * second read the caller schedules, does lose it until the sheet's next load. Fixing that
     * properly means publishing each apply's own reconciled value instead of re-reading around
     * it, which is what the apply-outcome retention work is for; the VM cannot substitute a
     * value here, because for a selector an apply just moved, the only right answer is that
     * apply's outcome, and that lives in the sheet.
     */
    suspend fun refreshLiveSelectors() {
        val c = client ?: return
        repeat(SELECTOR_REFRESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SELECTOR_REFRESH_RETRY_MS)
            val cached = connectionId
            val applies = applyRegistry.generation
            // Retry a failed read too: nothing has been decided yet, and the usual cause is a
            // blip rather than a connection that is really gone.
            val (conn, snapshot) = readConnectionSnapshot(c) ?: return@repeat
            // Retry rather than return: the read is still wanted, it just can't be trusted to
            // describe the values as they stand now. Compared against what was CACHED, not
            // against `conn` — a resolved-but-uncached connection leaves the field null, and
            // that is not a change.
            if (connectionId != cached || applyRegistry.generation != applies) return@repeat
            resetSelectorsForConnection(conn)
            // Unlike the WS snapshot, this shape can't say "published nothing" — its list is
            // non-null with an empty default — so an empty one is only meaningful once the latch
            // says the agent has finished publishing. Below that, offer nothing rather than park
            // an emptiness that was never reported.
            val options = snapshot.configOptions.takeIf { it.isNotEmpty() || snapshot.selectorsReady }
            foldSelectorSnapshot(snapshot.modes, options, snapshot.selectorsReady)
            return
        }
    }

    /** Load the session's modes + config plus the selection to highlight. Any live
     *  connection's snapshot is authoritative *once it has published*; failing that it
     *  probes the per-agent catalog (slower) and pre-selects the user's remembered pick
     *  (validated against the catalog), falling back to the catalog's fresh-session defaults.
     *  A live session that advertises neither modes nor config options therefore takes the
     *  probe path too. iOS parity. */
    suspend fun loadAgentOptions(): AgentOptionsData {
        // Where the pushes stood as the load began, as the sheet captures it: the ones landing
        // while it runs are newer than what it reads, and are folded over it.
        val forAgent = agent
        val since = SelectorRevisions.of(ui.value.liveSelectors?.takeIf { it.agent == forAgent })
        val data = readAgentOptions()
        if (agent == forAgent) summary.loaded(forAgent, data, ui.value.liveSelectors, since)
        return data
    }

    /** [loadAgentOptions]' read. */
    private suspend fun readAgentOptions(): AgentOptionsData {
        val c = client ?: return AgentOptionsData()
        // Let any apply still running for THIS agent finish first. Its change is on its way to
        // the agent, so a snapshot taken now would show the pre-apply value and the sheet would
        // sit on it — the apply's own awaiter is gone (it belonged to the sheet that started it)
        // and nothing else consumes the server's config-option events yet. Applies for other
        // agents can't affect this load, and waiting on them would just stall the sheet.
        val settling = inFlightApplies.values.filter { it.agent == agent }.map { it.deferred }
        if (settling.isNotEmpty()) {
            withTimeoutOrNull(APPLY_SETTLE_BUDGET_MS) { settling.forEach { orNull { it.await() } } }
        }
        // Two ways to reach the same session, and neither covers the other. A draft has no
        // conversation id but can still hold a connection an earlier apply opened; and a
        // conversation's resumed connection isn't registered under its id until the first
        // prompt, so the by-conversation lookup can miss a connection that exists. Trying only
        // the one that matches the current shape would show the remembered preference as if it
        // were the session's state. `connectionId` is nulled on every agent/folder switch, so a
        // non-null one always belongs to the current context.
        val snapshot = conversationId?.let { orNull { c.sessionSnapshotByConversation(it) } }
            ?: connectionId?.let { conn -> orNull { c.sessionSnapshot(conn) } }
        // `selectorsReady` alone is enough: a session that has finished publishing and advertises
        // NEITHER is answering "nothing to configure", and that answer is authoritative. Probing
        // a throwaway process instead would put its controls on screen while every apply targets
        // this connection — which does not expose them. Without the flag the two are
        // indistinguishable, which is why the pre-0.24.0 shape had to guess from emptiness.
        //
        // A read landing inside the backend's 500ms post-latch grace can still catch a straggling
        // update mid-flight and report nothing. That window is bounded and belongs to a
        // connection that has just come up; the stale-probe alternative it replaces had no bound.
        if (snapshot != null && (snapshot.selectorsReady || snapshot.modes != null || snapshot.configOptions.isNotEmpty())) {
            // Live session — its current mode/config is authoritative, and every value here is
            // one a session just reported, so all of them can serve as reconcile baselines.
            applyRegistry.setObserved(agent, snapshot.selectorKeys(), observedValue = true)
            return AgentOptionsData(
                modes = snapshot.modes,
                configOptions = snapshot.configOptions,
                initialModeId = snapshot.modes?.currentModeId,
                initialConfig = snapshot.configOptions.currentValues(),
            )
        }
        // No live session with selectors — probe the catalog and pre-select the remembered pick
        // so the sheet opens on the user's last-used mode/config, not the server default.
        // Nothing here has been observed on a session, so none of it can serve as a reconcile
        // baseline ([ApplyContext.baseline]).
        val described = orNull { c.describeAgentOptions(agent, folder?.path) }
        val modes = described?.modes
        val options = described?.configOptions.orEmpty()
        val prefs = orNull { selectorPrefs.prefs(agent) } ?: SelectorPrefs()
        val modeId = prefs.modeId?.takeIf { saved -> modes?.availableModes?.any { it.id == saved } == true }
            ?: modes?.currentModeId
        val config = options.associate { opt ->
            val saved = prefs.configValues?.get(opt.id)?.takeIf { it in opt.kind.acceptedValues }
            opt.id to (saved ?: opt.kind.currentValue ?: "")
        }.filterValues { it.isNotEmpty() }
        applyRegistry.setObserved(
            agent,
            options.map { SelectorKey.Config(it.id) } + SelectorKey.Mode,
            observedValue = false,
        )
        return AgentOptionsData(modes, options, modeId, config)
    }

    /**
     * Record that the sheet adopted values a live push reported, so the next apply on those
     * selectors may use what is on screen as its reconcile baseline ([ApplyContext.baseline]).
     *
     * The sheet reports this rather than the event handler doing it, because only the sheet
     * knows which pushed values it actually took: it skips any selector with an apply in
     * flight, and that one is still showing an optimistic value no session has confirmed.
     */
    internal fun markSelectorsObserved(selectors: Collection<SelectorKey>) {
        if (selectors.isNotEmpty()) applyRegistry.setObserved(agent, selectors, observedValue = true)
    }

    /**
     * The shared connection, but only when it still belongs to the caller's *pinned*
     * agent/folder.
     *
     * `connectionId` is nulled on every agent/folder switch, so a non-null value always belongs
     * to the current context; if the current context no longer matches the pinned one, the
     * caller raced a switch and must not be handed the other agent's connection.
     */
    private fun reusableConnection(forAgent: AgentType, forFolderId: Int?): String? =
        connectionId?.takeIf { agent == forAgent && folder?.id == forFolderId }

    /** A live connection to apply options to. Reuses a cached/live connection, and — for a
     *  draft or idle session with none — establishes one on demand (carrying the remembered
     *  mode/config), so the pickers apply instead of silently no-op'ing. Mirrors iOS
     *  `resolveConnectionForOptions`. Concurrent applies share one in-flight resolve
     *  ([optionsConnect]) so a rapid mode+config change can't spawn two orphaned
     *  connections. Returns null only when there's no client or the connect fails. */
    private suspend fun optionsConnection(forAgent: AgentType, forFolder: FolderDetail?): String? {
        val forFolderId = forFolder?.id
        reusableConnection(forAgent, forFolderId)?.let { return it }
        val c = client ?: return null
        // Reuse an in-flight connect only when it targets the SAME agent/folder the caller pinned —
        // after a draft agent/folder switch, a reused stale-context connect would apply to the
        // wrong agent.
        val cached = optionsConnect
        val deferred = if (cached != null && cached.agent == forAgent && cached.folderId == forFolderId) {
            cached.deferred
        } else {
            // A connect for a now-abandoned agent/folder context is obsolete — cancel it so it
            // can't complete and win adoption with a preference snapshot taken before a later
            // setting was saved (a same-context connect started afterward carries the newer prefs;
            // if the stale one were adopted, the next send would reuse it and drop that setting).
            cached?.deferred?.cancel()
            val d = scope.async {
                val convId = conversationId
                val found = if (convId != null) {
                    runCatching { c.findConnection(convId, externalId, forAgent)?.connectionId }.getOrNull()
                } else null
                // Pin the connect to the captured agent/folder so a switch mid-connect can't
                // retarget it to a different agent/workspace.
                val conn = found ?: host.connect(c, forAgent, forFolder)
                // Adopt as the shared connection only if nothing else has claimed it and the
                // draft's agent/folder is still the captured one — never clobber a connection the
                // send path already established (which Stop / stream reconnect target).
                if (connectionId == null && agent == forAgent && folder?.id == forFolderId) connectionId = conn
                conn
            }
            optionsConnect = OptionsConnect(forAgent, forFolderId, d)
            // Clear the memo when the connect itself settles — not when a single awaiter is
            // cancelled — so a dismissed sheet / cancelled apply can't drop the shared reference
            // mid-flight and let the next apply spawn a second (orphaned) connection.
            d.invokeOnCompletion { if (optionsConnect?.deferred === d) optionsConnect = null }
            d
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply a mode pick. Attempts to remember it per agent (so it carries to the server on the
     * next `acp_connect` — [ApplyOutcome.Applied.remembered] says whether that write landed),
     * resolves/establishes a connection ([optionsConnection]), enqueues `set_mode`, and
     * reconciles against the authoritative snapshot.
     *
     * The value in [ApplyOutcome.Applied] is what the sheet should display, not a value the
     * agent has necessarily confirmed: when the reconcile budget elapses without the agent
     * reporting the pick, this hands back the optimistic one (see [reconcileSelection]).
     *
     * [previous] is the value the sheet was showing. It is [reconcileSelection]'s baseline for
     * telling a slow agent from a normalizing one — but only when the registry says that value
     * was observed on a live session. What a failed apply un-remembers is NOT this: it is the
     * value the save actually replaced in the store, which the sheet may never have shown.
     */
    fun applyMode(modeId: String, previous: String?): Deferred<ApplyOutcome> =
        startApply(SelectorKey.Mode, modeId) { ctx ->
            val stored = saveForApply(ctx) { selectorPrefs.saveMode(ctx.agent, modeId) }
            val undo: suspend () -> Unit = {
                selectorPrefs.restoreMode(ctx.agent, expected = modeId, previous = stored.replaced)
            }
            val conn = connectionForApply(ctx) ?: return@startApply failedApply(ctx, stored, undo)
            try {
                requireClient().acpSetMode(conn, modeId)
                reconciled(ctx, stored, reconcileSelection(conn, ctx.selector, modeId, ctx.baseline(previous)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropStaleOptionsConnection(conn)
                failedApply(ctx, stored, undo)
            }
        }

    /** Apply a config-option pick. Same contract as [applyMode]. */
    fun applyConfig(configId: String, valueId: String, previous: String?): Deferred<ApplyOutcome> =
        startApply(SelectorKey.Config(configId), valueId) { ctx ->
            val stored = saveForApply(ctx) { selectorPrefs.saveConfig(ctx.agent, configId, valueId) }
            val undo: suspend () -> Unit = {
                selectorPrefs.restoreConfig(ctx.agent, configId, expected = valueId, previous = stored.replaced)
            }
            val conn = connectionForApply(ctx) ?: return@startApply failedApply(ctx, stored, undo)
            try {
                requireClient().acpSetConfigOption(conn, configId, valueId)
                reconciled(ctx, stored, reconcileSelection(conn, ctx.selector, valueId, ctx.baseline(previous)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropStaleOptionsConnection(conn)
                failedApply(ctx, stored, undo)
            }
        }

    /** What a pre-apply save managed to do: the value it replaced, and whether it wrote at all. */
    private class SavedPreference(val replaced: String?, val remembered: Boolean)

    /**
     * Write the preference the apply is about to send, before sending it.
     *
     * The order matters: the apply may open the connection, and `acp_connect` reads the store,
     * so saving first is what lets a fresh connection come up already holding the pick. The
     * price is that a failure afterwards has to undo it — see [failedApply].
     */
    private suspend inline fun saveForApply(ctx: ApplyContext, save: () -> String?): SavedPreference = try {
        val replaced = save()
        // Ownership follows the write, not the tap: an apply whose save fails must not take
        // responsibility for a stored value an earlier apply wrote and still owes a rollback on.
        applyRegistry.claimWrite(ctx.key, ctx.token)
        SavedPreference(replaced = replaced, remembered = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The store is unwritable (full disk, corruption). Still worth applying to the live
        // session — the user asked for it — but the sheet is told it won't be remembered, and
        // there is nothing to roll back afterwards.
        SavedPreference(replaced = null, remembered = false)
    }

    /**
     * Resolve the connection to apply against, or null when there is none to be had.
     *
     * Cancellation is deliberately NOT treated as "the agent never saw it". Resolving may mean
     * *opening* the connection, and `acp_connect` carries the preferences this apply just saved
     * — the server applies them during session init, before the connect call returns. So a
     * cancellation here can land on either side of the change having already happened, and
     * undoing the preference would risk leaving the live session switched while the remembered
     * value says otherwise. Ambiguous cancellations keep the value; see [failedApply].
     */
    private suspend fun connectionForApply(ctx: ApplyContext): String? =
        if (client == null) null else optionsConnection(ctx.agent, ctx.folder)

    private fun requireClient(): CodegClient = checkNotNull(client) { "client resolved before connect" }

    /** Everything an apply has to decide before it suspends, captured at the tap. */
    private class ApplyContext(
        val agent: AgentType,
        val folder: FolderDetail?,
        val selector: SelectorKey,
        val key: ApplyRegistry.Key,
        val token: Long,
        /** Whether the value this apply replaces was read off a live session. */
        val hadObservedValue: Boolean,
    ) {
        /**
         * [previous] as a reconcile baseline, or null when it isn't one.
         *
         * A baseline has to be a value somebody actually saw a session report. When the sheet
         * pre-selected a remembered preference against a catalog probe — or kept an optimistic
         * value a previous apply never got confirmed — the agent's starting value is unknown,
         * and guessing would let [reconcileTimeout] read the agent's untouched default as a
         * normalization.
         */
        fun baseline(previous: String?): String? = previous.takeIf { hadObservedValue }
    }

    /**
     * Run an apply on [scope], not the caller's.
     *
     * The caller is the options sheet, and dismissing it cancels its scope. An apply cancelled
     * between saving the preference and reaching the agent is the worst outcome available: the
     * value is remembered, the server never got it, and the *next* connect applies a change the
     * user watched fail to happen. Owning the coroutine here means dismissal cancels only the
     * sheet's await.
     *
     * Registers the apply so a sheet opened while it is still running can wait for it
     * ([loadAgentOptions]) instead of reading a snapshot the change hasn't reached.
     */
    private fun startApply(
        selector: SelectorKey,
        value: String,
        body: suspend (ApplyContext) -> ApplyOutcome,
    ): Deferred<ApplyOutcome> {
        val key = ApplyRegistry.Key(agent, selector)
        val ctx = ApplyContext(
            agent = agent,
            folder = folder,
            selector = selector,
            key = key,
            token = applyRegistry.begin(key),
            hadObservedValue = applyRegistry.isObserved(key),
        )
        // The button shows the pick at once too, and learns how it ended here rather than from
        // the sheet, whose await a dismissal cancels.
        val summaryTicket = summary.beginApply(ctx.agent, selector, value)
        val deferred = scope.async {
            var outcome: ApplyOutcome? = null
            try {
                body(ctx).also { outcome = it }
            } finally {
                summary.settleApply(ctx.agent, selector, value, summaryTicket, outcome)
            }
        }
        // Keyed by token, not selector: two applies for the same selector can genuinely overlap
        // once a context switch has released the row lock, and a map keyed by selector would let
        // whichever finished first deregister the other.
        inFlightApplies[ctx.token] = InFlightApply(ctx.agent, deferred)
        deferred.invokeOnCompletion {
            inFlightApplies.remove(ctx.token)
            // A finished apply has moved the value as surely as a starting one — see
            // [ApplyRegistry.generation] and its reader, `refreshLiveSelectors`.
            applyRegistry.settle()
        }
        return deferred
    }

    /** Record what the reconcile settled on, and carry its confirmation into the registry so the
     *  NEXT apply on this selector knows whether the value it replaces was ever observed. */
    private fun reconciled(ctx: ApplyContext, stored: SavedPreference, result: Reconciled): ApplyOutcome {
        // Only the newest apply may publish provenance. An older one finishing later describes a
        // value that has since been replaced, and marking it observed would hand the next apply
        // a baseline nobody is showing.
        if (applyRegistry.isLatest(ctx.key, ctx.token)) applyRegistry.setObserved(ctx.key, result.confirmed)
        return ApplyOutcome.Applied(result.value, remembered = stored.remembered)
    }

    /**
     * Roll back the preference an apply optimistically saved and report the failure.
     *
     * Reached when the apply *reported* failure, which is not the same as knowing it didn't
     * land: `acp_set_mode`/`acp_set_config_option` only push a command onto the connection's
     * queue and return, so a response lost after the server enqueued is indistinguishable here
     * from one rejected before it. Rolling back is the right side to err on anyway — the
     * alternative leaves a value the next connect applies for real, silently, with the sheet
     * showing the old one and no trace the change ever failed. If the change did land after all,
     * the next sheet open corrects the display *provided the connection is still reachable* —
     * which is not guaranteed on this path, since [dropStaleOptionsConnection] may have just
     * released a draft's only handle to it.
     *
     * Reports whether the store was actually put back, because a rollback that fails to write is
     * the original divergence again: the sheet reverts while the store keeps the new value, and
     * the next connect applies it for real. The sheet says so rather than claiming a clean
     * revert.
     */
    private suspend fun failedApply(
        ctx: ApplyContext,
        stored: SavedPreference,
        rollback: suspend () -> Unit,
    ): ApplyOutcome = ApplyOutcome.Failed(rolledBack = !stored.remembered || rollBack(ctx, rollback))

    /**
     * Undo the preference this apply saved. Returns whether the store is back in a state that
     * matches what the sheet is about to show.
     *
     * Skipped — and reported as done — when a later apply has claimed the same selector for the
     * same agent: that apply's value is the one in the store now, and undoing it would replace a
     * pick that may well be succeeding. Value equality can't decide this on its own; a later
     * OFF → ON → OFF leaves the store holding exactly what a stalled older OFF apply wrote.
     *
     * [NonCancellable] so the write still completes when the surrounding apply is being torn
     * down, which is when it matters most.
     */
    private suspend fun rollBack(ctx: ApplyContext, rollback: suspend () -> Unit): Boolean {
        if (!applyRegistry.owns(ctx.key, ctx.token)) return true
        return withContext(NonCancellable) { runCatching { rollback() }.isSuccess }
    }

    /** An apply failed against [conn] — if it's still the cached connection AND no turn is now
     *  running on it, drop it so the next tap re-resolves (findConnection → connect) instead of
     *  reusing a possibly-dead id forever (which would leave the picker permanently non-functional
     *  once the server GCs an idle connection). The in-flight guard keeps a reattach that adopted
     *  this connection after the apply began from having its live turn's connection nulled (Stop /
     *  stream reconnect need it). Mirrors iOS re-validating the connection between applies. */
    private fun dropStaleOptionsConnection(conn: String) {
        if (connectionId == conn && !ui.value.isInFlight) forgetConnection()
    }

    /**
     * Poll the authoritative snapshot of [conn] until it reports [desired] for [key] (the mode
     * or a config-option id), or the ~2.3s budget elapses. Returns the value to highlight.
     *
     * [previous] is what [key] held before the apply, and it's what makes the timeout
     * interpretable. `acp_set_mode`/`acp_set_config_option` only *enqueue* the change, so a
     * budget that runs out while every poll still reports [previous] is not evidence the agent
     * rejected the pick — it is equally consistent with an agent slower than 2.3s, which is by
     * far the likelier of the two. Reverting there would show cline's `auto_approve` OFF for a
     * toggle the agent is about to turn ON. Only a value that actually MOVED — to something
     * that is neither [previous] nor [desired] — is an observed normalization worth adopting.
     *
     * So this cannot detect a silent rejection (an agent that accepts the request and ignores
     * it). That state clears the next time the sheet opens, which reads the live connection
     * back ([loadAgentOptions]) rather than the remembered pick. Mirrors the iOS reconcile,
     * with the no-movement case decided the other way.
     */
    private suspend fun reconcileSelection(
        conn: String,
        key: SelectorKey,
        desired: String,
        previous: String?,
    ): Reconciled {
        val c = client ?: return Reconciled(desired, confirmed = false)
        var lastSeen: String? = null
        // Wall-clock, not just the sum of the delays: each snapshot request can sit on the
        // client's own (much longer) HTTP timeout, so counting attempts alone bounds nothing.
        // Anything still outstanding when the budget runs out is abandoned, not awaited.
        withTimeoutOrNull(RECONCILE_BUDGET_MS) {
            repeat(RECONCILE_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 350L else 400L)
                val snap = runCatching { c.sessionSnapshot(conn) }.getOrNull() ?: return@repeat
                lastSeen = snap.selectionFor(key) ?: return@repeat
            if (lastSeen == desired) return@withTimeoutOrNull
            }
        }
        if (lastSeen == desired) return Reconciled(desired, confirmed = true)
        val settled = reconcileTimeout(lastSeen, desired, previous)
        // Falling back to `desired` means nothing was observed; anything else is a value a
        // snapshot actually reported.
        return Reconciled(settled, confirmed = settled != desired)
    }

    /** A reconcile's answer, plus whether a session actually reported it (as opposed to it being
     *  the optimistic pick handed back after the budget elapsed). */
    private class Reconciled(val value: String, val confirmed: Boolean)
}

/**
 * `runCatching`, but cancellation still cancels.
 *
 * The stdlib one catches `CancellationException` along with everything else, which turns a
 * superseded caller into one that keeps running: it stops at no further suspension point, so it
 * reaches its assignments and publishes a result the caller has already moved past. Used where a
 * failed call should degrade to null but a cancelled one must unwind.
 */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** A running options apply, tagged with the agent it targets so a sheet reload only waits for
 *  applies that could affect what it is about to show. */
private class InFlightApply(val agent: AgentType, val deferred: Deferred<ApplyOutcome>)

/** An in-flight options-connection resolve tagged with the agent/folder it targets, so the
 *  memo is only reused for a matching context (see [AgentOptionsController.optionsConnection]). */
private class OptionsConnect(val agent: AgentType, val folderId: Int?, val deferred: Deferred<String>)

/** The current value for each config option, dropping empties. */
private fun List<SessionConfigOption>.currentValues(): Map<String, String> =
    associate { it.id to (it.kind.currentValue ?: "") }.filterValues { it.isNotEmpty() }
