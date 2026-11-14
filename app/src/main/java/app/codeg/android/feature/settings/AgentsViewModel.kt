package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.AcpAgentInfo
import app.codeg.android.core.model.AdapterInfo
import app.codeg.android.core.model.AgentConfig
import app.codeg.android.core.model.AgentDraft
import app.codeg.android.core.model.AgentToml
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ClaudeAuthMode
import app.codeg.android.core.model.CodexAuthMode
import app.codeg.android.core.model.CursorAuthStatus
import app.codeg.android.core.model.CursorModelsResult
import app.codeg.android.core.model.CursorStructuredConfig
import app.codeg.android.core.model.EnvText
import app.codeg.android.core.model.FieldEdit
import app.codeg.android.core.model.GeminiAuthMode
import app.codeg.android.core.model.HermesProviderKind
import app.codeg.android.core.model.JsonConfig
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.core.model.PiCommandValidation
import app.codeg.android.core.model.PiConfigProjection
import app.codeg.android.core.model.UpdateKimiCodeConfigBody
import app.codeg.android.core.model.UpdatePiConfigBody
import app.codeg.android.core.model.hermesProvider
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Drives both the agent list (enable toggle) and the per-agent detail/config
 * editor. The list screen and the detail screen obtain this same instance (it is
 * scoped to the Settings nav-route), so a save made in the detail is reflected in
 * the list without any cross-screen plumbing.
 */
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AgentsUiState())
    val ui: StateFlow<AgentsUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null

    /**
     * Serializes every agent *write* (enable toggle + config save) so they cannot
     * interleave — `acp_update_agent_env` replaces the whole env/provider payload,
     * so an overlapping toggle and save must not clobber each other. Mirrors the
     * iOS `opTail` serial chain.
     */
    private val opMutex = Mutex()

    /**
     * Bumped on every [openDetail]. Each async fill that targets the detail carries the
     * generation it was issued for, so a slow answer to a question the user has moved on from
     * is dropped rather than written onto whatever panel happens to be open by then.
     *
     * Only [openDetail] bumps it, because only it changes what the detail IS. Closing is the
     * screen's own local state (`SettingsScreen` drops back to the agent list), which the
     * generation does not need to model: whatever is still in flight can at worst re-fill a
     * detail nothing is rendering, and the next open supersedes it.
     */
    private var detailGeneration = 0L

    /** The in-flight adapter preflight, cancelled on the next open so a stack of panel opens
     *  can't leave a stack of `node`/`npm` probes running on the server. Correctness rides on
     *  [detailGeneration], not on this — a cancel can always lose the race. */
    private var adapterJob: Job? = null

    init { load() }

    // region List

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val profile = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
            val c = repository.client(profile) ?: throw IllegalStateException("Missing token")
            client = c
            val agents = c.acpListAgents().sortedBy { a -> a.sortOrder }
            _ui.update { st ->
                st.copy(
                    loading = false,
                    agents = agents,
                    error = null,
                    // Keep the open detail's agent in sync with the fresh list so a
                    // self-contained panel's gate banner (Kimi/Pi) reflects the just-saved
                    // backend state. The draft/original are untouched (no surprise reset).
                    detail = st.detail?.let { d ->
                        agents.firstOrNull { it.agentType == d.agentType }?.let { d.copy(agent = it) } ?: d
                    },
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.displayMessage()) }
        }
    }

    /** Instant enable/disable toggle (from the list row or the detail header). */
    fun setEnabled(agent: AcpAgentInfo, enabled: Boolean) {
        val c = client ?: return
        val wasEnabled = agent.enabled
        applyEnabled(agent.agentType, enabled) // optimistic, covers list + detail
        viewModelScope.launch {
            opMutex.withLock {
                // Seed env/provider from the live row at execution time so a stale snapshot
                // can't re-send (and relink) a provider the user just unlinked — falling back
                // to the captured snapshot only when there is no live row.
                val src = toggleWriteSource(_ui.value.agents, agent)
                runCatching {
                    c.acpUpdateAgentEnv(agent.agentType, enabled, src.env, src.modelProviderId)
                }.onSuccess {
                    // Re-assert in case a concurrent reload landed the pre-toggle value.
                    applyEnabled(agent.agentType, enabled)
                }.onFailure { e ->
                    applyEnabled(agent.agentType, wasEnabled)
                    _ui.update { it.copy(error = e.displayMessage()) }
                }
            }
        }
    }

    private fun applyEnabled(type: AgentType, enabled: Boolean) = _ui.update { state ->
        state.copy(
            agents = state.agents.map { if (it.agentType == type) it.copy(enabled = enabled) else it },
            detail = state.detail?.takeIf { it.agentType == type }?.let {
                it.copy(
                    agent = it.agent.copy(enabled = enabled),
                    draft = it.draft.copy(enabled = enabled),
                    original = it.original.copy(enabled = enabled),
                )
            } ?: state.detail,
        )
    }

    // endregion

    // region Detail

    /**
     * Open the config editor for [agentType], building a fresh draft + provider list.
     *
     * Gated on [detailGeneration], because both fetches suspend and the user can open another
     * agent meanwhile: a slow open of A would otherwise land A's row and providers on B's
     * panel.
     *
     * The publish is then WHOLESALE, deliberately. Reopening the same agent renders the cached
     * panel while this load is in flight, so anything typed in that window is lost — a real
     * (narrow) bug, filed rather than patched here. Splicing the loaded half onto the retained
     * draft was tried and reverted: [AgentDetailState] is three things at once — the editor's
     * baseline, the server snapshot that `save()` diffs raw config against, and async
     * operation state — so a spliced value mixes revisions and turns a lost keystroke into
     * wrong bytes on the wire (a raw Cursor/Grok config classified as user-edited and written
     * back wholesale, merge keys deleted because the baseline predates them, a real difference
     * reading as clean and disabling Save). One revision at a time is the only consistent
     * thing this type can hold; fixing it properly means splitting those three roles apart.
     */
    fun openDetail(agentType: AgentType) {
        val generation = ++detailGeneration
        // Whatever the previous panel was still probing is now nobody's answer.
        adapterJob?.cancel()
        adapterJob = null
        viewModelScope.launch {
            try {
                val profile = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                val c = client ?: repository.client(profile)?.also { client = it }
                    ?: throw IllegalStateException("Missing token")
                val listed = _ui.value.agents.firstOrNull { it.agentType == agentType }
                    ?: c.acpListAgents().firstOrNull { it.agentType == agentType }
                    ?: throw IllegalStateException("Agent not found")
                val providers = if (agentSupportsProviders(agentType)) {
                    runCatching { c.listModelProviders() }.getOrDefault(emptyList())
                        .filter { it.agentType == agentType }
                } else {
                    emptyList()
                }
                if (generation != detailGeneration) return@launch
                // Re-read the row at publish time: the provider fetch above suspends, and an
                // enable toggle in that window writes the new value into the list. Publishing
                // the pre-suspend copy would visibly flip the header switch back.
                val agent = _ui.value.agents.firstOrNull { it.agentType == agentType } ?: listed
                val draft = AgentDraft.fromAgent(agent)
                _ui.update {
                    it.copy(detail = AgentDetailState(agentType, agent, draft = draft, original = draft, providers = providers))
                }
                if (agent.isAcpAdapter) loadAdapterInfo(c, agentType, generation)
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    /**
     * Fill in the adapter explainer after the detail is already on screen. Caller checks the
     * agent is an adapter — that flag comes from the same backend table
     * `PreflightResult.adapter` is derived from, so the skipped calls are exactly the ones
     * that would have answered null.
     *
     * Deliberately not awaited by [openDetail]: preflight can spawn `node`/`npm` on the
     * server, and an explainer card is never worth holding the config editor closed for. A
     * failure is silent for the same reason — the card is context, not a control, and an error
     * banner over a panel the user opened to edit their API key would be noise.
     */
    private fun loadAdapterInfo(c: CodegClient, agentType: AgentType, generation: Long) {
        adapterJob = viewModelScope.launch {
            val adapter = try {
                c.acpPreflight(agentType).adapter
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (generation != detailGeneration) return@launch
            _ui.update { st -> st.copy(detail = st.detail?.copy(adapter = adapter) ?: st.detail) }
        }
    }

    /** Edit a structured field, then re-bake the raw config/env so they stay in lockstep. */
    fun editDraft(transform: (AgentDraft) -> AgentDraft) = updateDetail { d ->
        d.copy(draft = transform(d.draft).reapplied(d.agentType), saveError = null)
    }

    /**
     * Re-bake the current draft without changing a field. A panel whose UI shows a
     * default the saved env doesn't carry yet (Cursor's Run Everything, ON for a fresh
     * agent) calls this on open, so a save that touched nothing else still persists
     * what's displayed. `reapplied` is idempotent — a no-op for an already-baked agent.
     */
    fun rebakeDraft() = editDraft { it }

    /** Edit the raw config text (Advanced editor) verbatim — no re-bake, so it overrides the fields above. */
    fun editRawConfig(text: String) = updateDetail { d ->
        val draft = when (d.agentType) {
            AgentType.CODEX -> d.draft.copy(codexConfigTomlText = text)
            AgentType.GROK -> d.draft.copy(grokConfigTomlText = text)
            AgentType.CURSOR -> d.draft.copy(cursorCliConfigText = text)
            else -> d.draft.copy(configText = text)
        }
        d.copy(draft = draft, saveError = null)
    }

    fun setModelProvider(id: Int?) = editDraft { it.copy(modelProviderId = id) }

    fun setClaudeAuthMode(mode: ClaudeAuthMode) = updateDetail { d ->
        var draft = d.draft.copy(claudeAuthMode = mode)
        draft = when (mode) {
            ClaudeAuthMode.OFFICIAL_SUBSCRIPTION -> {
                val (cfg, env) = AgentConfig.clearClaudeCredentialAliases(draft.configText, draft.envText)
                draft.copy(modelProviderId = null, apiBaseUrl = "", apiKey = "", configText = cfg, envText = env)
            }
            ClaudeAuthMode.CUSTOM -> draft.copy(modelProviderId = null)
            ClaudeAuthMode.MODEL_PROVIDER -> draft.copy(apiBaseUrl = "", apiKey = "")
        }
        d.copy(draft = draft.reapplied(AgentType.CLAUDE_CODE), saveError = null)
    }

    fun setCodexAuthMode(mode: CodexAuthMode) = updateDetail { d ->
        val draft = when (mode) {
            CodexAuthMode.MODEL_PROVIDER -> d.draft.copy(codexAuthMode = mode)
            else -> d.draft.copy(codexAuthMode = mode, modelProviderId = null)
        }
        d.copy(draft = draft.reapplied(AgentType.CODEX), saveError = null)
    }

    fun setGeminiAuthMode(mode: GeminiAuthMode) = updateDetail { d ->
        // Preserve the fields the target mode still uses; clear only the incompatible ones.
        d.copy(draft = AgentConfig.applyGeminiAuthMode(d.draft, mode).reapplied(AgentType.GEMINI), saveError = null)
    }

    fun setHermesProvider(id: String) = updateDetail { d ->
        // Restore key/url only when returning to the configured provider; else clear.
        val projected = AgentConfig.parseHermes(d.draft.configText)
        val same = id == projected.provider
        val draft = d.draft.copy(
            hermesProvider = id,
            apiKey = if (same) projected.apiKey else "",
            apiBaseUrl = if (same) projected.baseUrl else "",
        )
        d.copy(draft = draft, saveError = null)
    }

    fun save(onSuccess: () -> Unit) {
        val state = _ui.value.detail ?: return
        val c = client ?: return
        if (state.missingProvider) {
            updateDetail { it.copy(saveError = "Select a model provider first.") }
            return
        }
        // Reject an unparseable config.json BEFORE any write (a merge agent would otherwise
        // diff against {} and emit a destructive delete-all). config.json applies to every
        // non-Hermes agent — Codex included (its TOML is validated separately on save).
        val parseError = JsonConfig.parse(state.draft.configText).error
        if (state.agentType != AgentType.HERMES && parseError != null) {
            updateDetail { it.copy(saveError = parseError) }
            return
        }
        updateDetail { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                opMutex.withLock {
                    if (state.agentType == AgentType.HERMES) {
                        val body = hermesBody(state.draft)
                        c.acpUpdateHermesConfig(body.provider, body.model, body.apiKey, body.baseUrl)
                    } else {
                        // Enforce the linked-provider scrub at SAVE time too.
                        val payload = if (state.draft.modelProviderId != null) state.draft.reapplied(state.agentType) else state.draft
                        // A structured Grok save patches the LIVE config.toml (never the
                        // possibly-stale panel snapshot). Read it BEFORE any write so a read
                        // failure aborts the whole save with nothing persisted — mirroring the
                        // server, which propagates read errors rather than clobber a concurrent
                        // edit with stale TOML. `""` (no config file yet) is a valid empty base,
                        // distinct from `null` = "not Grok".
                        val grokFreshToml = if (state.agentType == AgentType.GROK) {
                            c.acpListAgents().firstOrNull { it.agentType == AgentType.GROK }?.grokConfigToml ?: ""
                        } else {
                            null
                        }
                        // Use the live enabled flag (a concurrent toggle may have changed it).
                        val liveEnabled = _ui.value.agents.firstOrNull { it.agentType == state.agentType }?.enabled ?: state.agent.enabled
                        val envMap = EnvText.parse(payload.envText)
                        c.acpUpdateAgentEnv(state.agentType, liveEnabled, envMap, payload.modelProviderId)
                        val cfg = if (grokFreshToml != null) {
                            ConfigPayload(null, null, null, null, grokConfigTomlForSave(payload, state.agent, grokFreshToml))
                        } else {
                            makeConfigBody(state.agentType, payload, state.agent)
                        }
                        try {
                            c.acpUpdateAgentConfig(
                                state.agentType, cfg.configJson, cfg.opencodeAuthJson, cfg.codexAuthJson,
                                cfg.codexConfigToml, cfg.grokConfigToml,
                                cfg.cursorCliConfigJson, cfg.cursorStructured,
                            )
                        } catch (e: Exception) {
                            // The two writes are not one transaction. For an agent whose env
                            // and native config are two halves of ONE permission decision, a
                            // half-applied save is worse than a failed one: Cursor's "Run
                            // Everything" rides the env while its deny rules live in
                            // cli-config.json, so a rejected config write (e.g. hand-edited
                            // invalid JSON under Advanced) would otherwise leave commands
                            // auto-approved with the new rules never applied. Put the env back
                            // exactly as it was, then report the original failure.
                            if (state.agentType == AgentType.CURSOR) {
                                runCatching {
                                    c.acpUpdateAgentEnv(
                                        state.agentType, liveEnabled, state.agent.env, state.agent.modelProviderId,
                                    )
                                }
                            }
                            throw e
                        }
                    }
                    reload()
                }
                updateDetail { it.copy(saving = false) }
                onSuccess()
            } catch (e: Exception) {
                updateDetail { it.copy(saving = false, saveError = e.displayMessage()) }
            }
        }
    }

    // region Kimi Code (self-contained panel — dedicated backend + own save)

    /**
     * Kimi Code's dedicated save (apikey/login/raw). Serialized on [opMutex] like the
     * other agent writes, then reloads so the gate-status banner + `configJson`
     * projection reflect the fresh backend state. Suspends on the caller's scope
     * (the panel) and throws on failure — the panel surfaces the banner.
     */
    suspend fun saveKimiCodeConfig(body: UpdateKimiCodeConfigBody) {
        val c = client ?: throw IllegalStateException("Not connected")
        opMutex.withLock {
            c.acpUpdateKimiCodeConfig(body)
            reload()
        }
    }

    /** Probe a Kimi provider's models (also validates the key). Not serialized — a read. */
    suspend fun fetchKimiModels(baseUrl: String, apiKey: String): List<String> {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.acpFetchKimiModels(baseUrl, apiKey)
    }

    // endregion

    // region Pi (self-contained panel — native config + runtime env + trust)

    /**
     * Persist Pi's runtime / workspace-trust env (via `acp_update_agent_env`, like the
     * enable toggle). Serialized on [opMutex] and reloads so the open detail's agent
     * (which the panel re-reads) reflects the fresh env.
     */
    suspend fun updatePiEnv(agent: AcpAgentInfo, env: Map<String, String>) {
        val c = client ?: throw IllegalStateException("Not connected")
        opMutex.withLock {
            c.acpUpdateAgentEnv(agent.agentType, agent.enabled, env, agent.modelProviderId)
            reload()
        }
    }

    /** Read pi's native config projection (defaults + linked/custom providers). */
    suspend fun loadPiConfig(): PiConfigProjection {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.acpLoadPiConfig()
    }

    /**
     * Save pi's native credentials/model (~/.pi/agent/{settings,auth,models}.json).
     * Not serialized on [opMutex] — it touches pi's own files, not the agent env/config.
     */
    suspend fun savePiConfig(body: UpdatePiConfigBody) {
        val c = client ?: throw IllegalStateException("Not connected")
        c.acpUpdatePiConfig(body)
    }

    /** Validate a BYO-pi command/binary path (also used to detect the global `pi`). */
    suspend fun validatePiCommand(command: String): PiCommandValidation {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.acpValidatePiCommand(command)
    }

    /** Install / uninstall the global `pi` binary pi-acp spawns (long-running). */
    suspend fun installPiBinary() {
        val c = client ?: throw IllegalStateException("Not connected")
        c.acpInstallPiBinary(java.util.UUID.randomUUID().toString())
    }

    suspend fun uninstallPiBinary() {
        val c = client ?: throw IllegalStateException("Not connected")
        c.acpUninstallPiBinary(java.util.UUID.randomUUID().toString())
    }

    // endregion

    // region Cursor probes (reads — NOT serialized on [opMutex])

    /**
     * Probe `cursor-agent status` for the panel's account card. [apiKey] is the key
     * currently on screen (empty ⇒ test the browser login), so the card reflects what
     * the user is editing rather than what was last saved.
     */
    suspend fun cursorAuthStatus(apiKey: String): CursorAuthStatus {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.acpCursorAuthStatus(apiKey)
    }

    /** Probe `cursor-agent models` for the panel's model picker. */
    suspend fun cursorListModels(apiKey: String): CursorModelsResult {
        val c = client ?: throw IllegalStateException("Not connected")
        return c.acpCursorListModels(apiKey)
    }

    // endregion

    private fun updateDetail(block: (AgentDetailState) -> AgentDetailState) =
        _ui.update { it.copy(detail = it.detail?.let(block)) }

    private fun agentSupportsProviders(agent: AgentType): Boolean =
        agent == AgentType.CLAUDE_CODE || agent == AgentType.CODEX || agent == AgentType.GEMINI

    private data class ConfigPayload(
        val configJson: String?,
        val opencodeAuthJson: String?,
        val codexAuthJson: String?,
        val codexConfigToml: String?,
        val grokConfigToml: String? = null,
        val cursorCliConfigJson: String? = null,
        val cursorStructured: CursorStructuredConfig? = null,
    )

    /** Port of iOS `makeConfigBody` — per-agent native-file payload for `acp_update_agent_config`. */
    private fun makeConfigBody(agentType: AgentType, draft: AgentDraft, original: AcpAgentInfo): ConfigPayload =
        when (agentType) {
            AgentType.CODEX -> ConfigPayload(
                configJson = JsonConfig.normalize(draft.configText).takeIf { it.isNotEmpty() },
                opencodeAuthJson = null,
                codexAuthJson = draft.codexAuthJsonText,
                // Enforce the disable_response_storage invariant even if edited out in the raw TOML.
                codexConfigToml = AgentToml.setRootBool(draft.codexConfigTomlText, "disable_response_storage", true),
            )
            AgentType.OPEN_CODE -> {
                val normalized = JsonConfig.normalize(AgentConfig.ensureOpenCodeProviderNpm(draft.configText))
                ConfigPayload(
                    configJson = normalized.ifEmpty { "{}" },
                    // Empty auth → "{}" so cleared provider secrets are actually removed.
                    opencodeAuthJson = draft.openCodeAuthJsonText.ifEmpty { "{}" },
                    codexAuthJson = null,
                    codexConfigToml = null,
                )
            }
            // Cursor has no config.json. Normally only the structured patch goes and the
            // backend merges it onto the FRESH on-disk cli-config.json, so a concurrent
            // edit from the Cursor CLI's own `/config` UI survives. Once the user edited
            // the raw file under Advanced, that text is sent instead and the patch is
            // suppressed — see [cursorStructuredForSave].
            AgentType.CURSOR -> ConfigPayload(
                configJson = null,
                opencodeAuthJson = null,
                codexAuthJson = null,
                codexConfigToml = null,
                cursorCliConfigJson = cursorCliConfigForSave(draft, original),
                cursorStructured = cursorStructuredForSave(draft, original),
            )
            // Grok is handled separately in save() via grokConfigTomlForSave() (it needs a
            // fresh re-read of the live config.toml as the patch base), so it never
            // reaches makeConfigBody. The else-branch below would mis-handle it.
            else -> {
                var configForPersist = JsonConfig.normalize(draft.configText)
                if (AgentConfig.usesMerge(agentType)) {
                    val originalConfig = original.configJson?.let { JsonConfig.parse(it).config } ?: emptyMap()
                    if (originalConfig.isNotEmpty()) {
                        val current = JsonConfig.parse(draft.configText).config
                        configForPersist = JsonConfig.serialize(JsonConfig.markRemovedKeysNull(originalConfig, current))
                    }
                }
                ConfigPayload(configForPersist.takeIf { it.isNotEmpty() }, null, null, null)
            }
        }

    private data class HermesBody(val provider: String, val model: String?, val apiKey: FieldEdit, val baseUrl: FieldEdit)

    private fun hermesBody(d: AgentDraft): HermesBody {
        val opt = hermesProvider(d.hermesProvider)
        val apiKey = if (opt?.kind == HermesProviderKind.API_KEY && d.apiKey.trim().isNotEmpty()) {
            FieldEdit.Set(d.apiKey)
        } else {
            // Explicit clear → the backend keeps the stored ~/.hermes/.env secret.
            FieldEdit.Clear
        }
        val baseUrl = if (opt?.needsBaseUrl == true) FieldEdit.Set(d.apiBaseUrl) else FieldEdit.Clear
        return HermesBody(d.hermesProvider, d.model, apiKey, baseUrl)
    }

    // endregion
}

/**
 * The agent whose env/provider should seed an enable-toggle write: the live list
 * row if present (even when its `modelProviderId` is null), else the captured
 * snapshot. Using the live row prevents a stale snapshot from re-linking a
 * provider the user just unlinked. Extracted for unit testing.
 */
internal fun toggleWriteSource(agents: List<AcpAgentInfo>, captured: AcpAgentInfo): AcpAgentInfo =
    agents.firstOrNull { it.agentType == captured.agentType } ?: captured

/**
 * The `grok_config_toml` to persist for a Grok save (null ⇒ leave config.toml
 * untouched). We patch the two controls into the FULL toml and send it verbatim —
 * never `grokStructured` — so the server preserves the web-only custom-model /
 * `[session]` keys it would otherwise treat as "delete". [snapshot] is the
 * panel-open agent (detects whether the USER changed a control); [freshToml] is the
 * live on-disk config re-read at save time (the patch base, so edits made elsewhere
 * since the panel opened survive). An Advanced raw edit wins verbatim; an API-key-only
 * save returns null (no config write). Extracted for unit testing.
 */
internal fun grokConfigTomlForSave(draft: AgentDraft, snapshot: AcpAgentInfo, freshToml: String): String? {
    if (draft.grokConfigTomlText != (snapshot.grokConfigToml ?: "")) return draft.grokConfigTomlText
    val pmChanged = draft.grokPermissionMode != (snapshot.grokSettings?.permissionMode ?: "")
    val reChanged = draft.grokReasoningEffort != (snapshot.grokSettings?.defaultReasoningEffort ?: "")
    return if (pmChanged || reChanged) {
        AgentToml.patchGrok(freshToml, draft.grokPermissionMode, draft.grokReasoningEffort)
    } else {
        null
    }
}

/**
 * Agents whose Advanced editor holds a NATIVE config file (TOML / cli-config.json)
 * rather than the shared `config.json`, so a `configText` JSON parse must not gate
 * their save — those drafts keep `configText` untouched.
 */
private val nativeRawConfigAgents = setOf(AgentType.CODEX, AgentType.GROK, AgentType.CURSOR)

/**
 * The `cursor_cli_config_json` to persist (null ⇒ don't touch the raw file). Sent ONLY
 * when the user edited it under Advanced — otherwise the backend merges
 * [cursorStructuredForSave] onto the FRESH on-disk cli-config.json instead of a panel
 * snapshot the Cursor CLI's own `/config` UI may have moved past. Extracted for testing.
 */
internal fun cursorCliConfigForSave(draft: AgentDraft, original: AcpAgentInfo): String? =
    draft.cursorCliConfigText.takeIf { it != (original.cursorCliConfigJson ?: "") }

/**
 * Cursor's structured controls (null ⇒ send no patch). Unlike Grok's payload an absent
 * field here means "leave that key alone" server-side, so this is a safe patch: `""`
 * sandbox means "leave sandbox.mode alone", while the rule lists are replaced
 * wholesale, so an emptied list is sent as `[]` — a real "no rules", not an omission.
 * Blank rows (an "add rule" the user never filled in) are dropped.
 *
 * Suppressed entirely once the user edited the raw cli-config.json: the server applies
 * the patch ON TOP of the raw text it was sent, so the form's values — seeded from the
 * file BEFORE the edit — would overwrite hand-edited sandbox/rule keys. An Advanced
 * edit wins verbatim, the same precedence [grokConfigTomlForSave] gives Grok's raw
 * TOML. (The web avoids the conflict by having two separate save buttons; this panel,
 * like iOS, has one.) Extracted for testing.
 */
internal fun cursorStructuredForSave(draft: AgentDraft, original: AcpAgentInfo): CursorStructuredConfig? {
    if (cursorCliConfigForSave(draft, original) != null) return null
    return CursorStructuredConfig(
        sandboxMode = draft.cursorSandboxMode.ifEmpty { null },
        permissionsAllow = draft.cursorAllowRules.map { it.trim() }.filter { it.isNotEmpty() },
        permissionsDeny = draft.cursorDenyRules.map { it.trim() }.filter { it.isNotEmpty() },
    )
}

data class AgentsUiState(
    val agents: List<AcpAgentInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val detail: AgentDetailState? = null,
)

data class AgentDetailState(
    val agentType: AgentType,
    val agent: AcpAgentInfo,
    val draft: AgentDraft,
    val original: AgentDraft,
    val providers: List<ModelProviderInfo> = emptyList(),
    /** The adapter-vs-vendor-CLI split, for the two agents codeg launches through an ACP
     *  adapter. Null for every other agent, and until the preflight behind it resolves. */
    val adapter: AdapterInfo? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val dirty: Boolean get() = draft != original
    val missingProvider: Boolean get() = AgentConfig.missingModelProvider(agentType, draft)
    /** A self-hosted CodeBuddy whose Base URL isn't a valid http(s) URL blocks save. */
    val missingCodeBuddyBaseUrl: Boolean get() = AgentConfig.missingCodeBuddyBaseUrl(agentType, draft)
    /** Cursor in API-key mode with no key blocks save (it would write a credential-less mode). */
    val missingCursorApiKey: Boolean get() = AgentConfig.missingCursorApiKey(agentType, draft)
    val canSave: Boolean
        get() = dirty && !saving && !missingProvider && !missingCodeBuddyBaseUrl && !missingCursorApiKey
    val configError: String?
        get() = if (agentType in nativeRawConfigAgents) null
        else JsonConfig.parse(draft.configText).error
    val provider: ModelProviderInfo? get() = providers.firstOrNull { it.id == draft.modelProviderId }
}
