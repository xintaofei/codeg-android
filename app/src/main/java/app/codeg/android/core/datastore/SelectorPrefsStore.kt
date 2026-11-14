package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.codeg.android.core.model.AgentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One agent's remembered "selector" choices: its last-used mode + per-option config
 *  values. Mirrors the codeg web/iOS `SelectorPrefs`. */
@Serializable
data class SelectorPrefs(
    val modeId: String? = null,
    val configValues: Map<String, String>? = null,
)

/**
 * Local cache of agent mode/config selections, keyed by [AgentType.wire] — a port of
 * the web client's `codeg:selector-prefs` (and iOS `SelectorPrefsStore`).
 *
 * Why: the agent-options sheet's mode (plan/default/…) and config options (e.g. model)
 * otherwise reset to the server's fresh-session defaults every time. We persist the
 * user's pick per agent and re-apply it: passed to `acp_connect` as
 * `preferredModeId`/`preferredConfigValues` (the server applies them before reporting
 * state) and used to pre-select the draft options sheet.
 *
 * Keyed purely by agent (not per-server) like the web — modes/config are intrinsic to the
 * agent. What happens to a remembered value the current agent build no longer advertises is
 * decided server-side and is not uniform: a generic ACP config id is forwarded to the agent
 * unfiltered and the agent decides, Grok's own path validates against what it offers and skips
 * the rest, and a remembered mode isn't sent at all when the agent exposes no modes. Either way
 * a rejected preference is skipped rather than failing the connect.
 */
@Singleton
class SelectorPrefsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The remembered choices for [agent] (empty if none yet). */
    suspend fun prefs(agent: AgentType): SelectorPrefs = all()[agent.wire] ?: SelectorPrefs()

    /**
     * Remember the agent's mode selection, returning what it replaced.
     *
     * The caller needs the *stored* value, not the one it was displaying: the two can differ
     * (the sheet shows the live session, which may have been normalized or changed elsewhere),
     * and restoring the displayed one after a failure would quietly rewrite the remembered
     * preference to something the user never picked.
     */
    suspend fun saveMode(agent: AgentType, modeId: String): String? =
        update(agent) { it.copy(modeId = modeId) }.modeId

    /** Remember one config option's value, preserving the agent's other values. @see saveMode */
    suspend fun saveConfig(agent: AgentType, configId: String, valueId: String): String? =
        update(agent) { it.copy(configValues = (it.configValues ?: emptyMap()) + (configId to valueId)) }
            .configValues?.get(configId)

    /**
     * Put back what a [saveMode] preceding a failed apply wrote.
     *
     * The save has to happen *before* the apply (the apply may open the connection, and
     * `acp_connect` reads this store), so a failed apply otherwise leaves behind a value
     * the next connect would silently apply — while the sheet, which reverts on failure,
     * shows the old one. For a toggle like cline's `auto_approve` that divergence means
     * the next session comes up granting more than the UI ever showed.
     *
     * The check and the write share one [DataStore.edit], so nothing can land between them.
     */
    suspend fun restoreMode(agent: AgentType, expected: String, previous: String?) =
        update(agent) { it.restoringMode(expected, previous) }

    /** @see restoreMode */
    suspend fun restoreConfig(agent: AgentType, configId: String, expected: String, previous: String?) =
        update(agent) { it.restoringConfig(configId, expected, previous) }

    private suspend fun all(): Map<String, SelectorPrefs> = decode(dataStore.data.first()[KEY])

    /** Applies [mutate] to [agent]'s entry and returns the entry as it was BEFORE — read inside
     *  the same [DataStore.edit] as the write, so it is the value this call actually replaced. */
    private suspend fun update(agent: AgentType, mutate: (SelectorPrefs) -> SelectorPrefs): SelectorPrefs {
        var before = SelectorPrefs()
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY]).toMutableMap()
            before = map[agent.wire] ?: SelectorPrefs()
            map[agent.wire] = mutate(before)
            prefs[KEY] = json.encodeToString(SERIALIZER, map)
        }
        return before
    }

    private fun decode(raw: String?): Map<String, SelectorPrefs> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("selector_prefs")
        val SERIALIZER = MapSerializer(String.serializer(), SelectorPrefs.serializer())
    }
}

/**
 * Undo a mode save, but only while [expected] — the value that save wrote — is still the one
 * stored.
 *
 * Applies are not serialized: a second pick for the same selector can be saved while the first
 * is still in flight. An unconditional restore would then undo the *newer* pick, which may well
 * be succeeding, and the store would end up on a value the sheet never shows.
 */
internal fun SelectorPrefs.restoringMode(expected: String, previous: String?): SelectorPrefs =
    if (modeId != expected) this else copy(modeId = previous)

/** @see restoringMode — dropping the entry entirely when nothing was remembered before, so the
 *  next connect sends no preference for it rather than an empty one. */
internal fun SelectorPrefs.restoringConfig(configId: String, expected: String, previous: String?): SelectorPrefs {
    val values = configValues ?: emptyMap()
    if (values[configId] != expected) return this
    return copy(configValues = if (previous == null) values - configId else values + (configId to previous))
}
