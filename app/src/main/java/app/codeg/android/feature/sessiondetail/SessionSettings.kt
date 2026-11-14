package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectOption
import app.codeg.android.core.model.SessionModeInfo
import app.codeg.android.core.model.SessionModeState

// Pure ports of the web's `src/lib/model-config-groups.ts` and `src/lib/session-settings.ts`
// (codeg 0.44): the agent's config options — or the legacy mode list, for an agent that
// publishes no config options — normalized into one ordered list that the options button
// summarizes and the options sheet edits. No UI and no I/O; commits stay the sheet's
// ([AgentOptionsController.applyMode] / [AgentOptionsController.applyConfig]).

/**
 * A visual group of select values. `name == null` draws the values with no header: the leading
 * "floating" bucket of values that carry no `provider/` prefix, or the one group of a flat list.
 */
internal data class ModelOptionGroup(
    val key: String,
    val name: String?,
    val options: List<SessionConfigSelectOption>,
)

/** One row of a flattened group list: a group header, or a value under [groupKey]. */
internal sealed interface ModelOptionRow {
    val key: String

    data class Header(override val key: String, val name: String) : ModelOptionRow
    data class Option(override val key: String, val groupKey: String, val option: SessionConfigSelectOption) : ModelOptionRow
}

internal object ModelOptionGroups {
    /** The id every agent codeg ships publishes its model selector under — a convention, not an
     *  ACP guarantee, hence the category check beside it. */
    const val MODEL_OPTION_ID = "model"

    /** Only the model picker groups by `/` prefix. The backend ships it with `id == "model"` and
     *  no category; Codex's approval preset uses `category == "mode"`. Either signal counts. */
    fun isModelOption(option: SessionConfigOption): Boolean =
        option.id == MODEL_OPTION_ID || option.category == "model"

    /** The namespace before the FIRST "/", or null when there is no usable prefix (no slash, a
     *  leading slash, or a trailing slash with an empty suffix). */
    private fun prefixOf(value: String): String? {
        val idx = value.indexOf('/')
        if (idx <= 0 || idx >= value.length - 1) return null
        return value.substring(0, idx)
    }

    /** A display name split on its first "/", both sides trimmed; null when either is empty. */
    private fun splitNamePrefix(name: String): Pair<String, String>? {
        val idx = name.indexOf('/')
        if (idx < 0) return null
        val head = name.substring(0, idx).trim()
        val tail = name.substring(idx + 1).trim()
        if (head.isEmpty() || tail.isEmpty()) return null
        return head to tail
    }

    /** The leading display-name segment EVERY row repeats, or null. */
    private fun sharedNamePrefix(items: List<SessionConfigSelectOption>): String? {
        var shared: String? = null
        for (item in items) {
            val (head, _) = splitNamePrefix(item.name) ?: return null
            if (shared == null) shared = head else if (shared != head) return null
        }
        return shared
    }

    /** The shared prefix worth stripping. A lone row is only stripped when its name's head IS the
     *  value prefix (`anthropic/claude-opus`), so a slashed display name like `GPT-4o/preview`
     *  is not mistaken for a provider. */
    private fun strippablePrefix(valuePrefix: String, items: List<SessionConfigSelectOption>): String? {
        val shared = sharedNamePrefix(items) ?: return null
        if (items.size < 2 && shared != valuePrefix) return null
        return shared
    }

    private fun buildGroup(valuePrefix: String, items: List<SessionConfigSelectOption>): ModelOptionGroup {
        val shared = strippablePrefix(valuePrefix, items)
            ?: return ModelOptionGroup(valuePrefix, valuePrefix, items)
        return ModelOptionGroup(
            key = valuePrefix,
            name = shared,
            options = items.map { it.copy(name = splitNamePrefix(it.name)?.second ?: it.name) },
        )
    }

    /**
     * `provider/` prefix groups for the model picker's flat value list, or null for "render
     * as-is": not the model picker, not a select, server-provided groups (respected verbatim), or
     * grouping would add nothing. Membership is by the VALUE's first segment; header and row
     * labels come from the display name. Values are never rewritten.
     */
    fun derive(option: SessionConfigOption): List<ModelOptionGroup>? {
        if (!isModelOption(option) || !option.kind.isSelect || option.kind.groups.isNotEmpty()) return null
        val options = option.kind.options
        val prefixes = options.map { prefixOf(it.value) }
        if (prefixes.none { it != null }) return null

        val floating = mutableListOf<SessionConfigSelectOption>()
        val byPrefix = LinkedHashMap<String, MutableList<SessionConfigSelectOption>>()
        options.forEachIndexed { i, opt ->
            val prefix = prefixes[i]
            if (prefix == null) floating += opt else byPrefix.getOrPut(prefix) { mutableListOf() } += opt
        }

        // A lone provider with nothing beside it is only worth a header when its rows share a
        // redundant display prefix to strip.
        if (byPrefix.size == 1 && floating.isEmpty()) {
            val (prefix, items) = byPrefix.entries.first()
            strippablePrefix(prefix, items) ?: return null
            return listOf(buildGroup(prefix, items))
        }

        val result = mutableListOf<ModelOptionGroup>()
        if (floating.isNotEmpty()) result += ModelOptionGroup(UNGROUPED_KEY, null, floating)
        byPrefix.forEach { (prefix, items) -> result += buildGroup(prefix, items) }
        return result
    }

    /** The groups to render: derived `provider/` groups when applicable, else the agent's own
     *  groups verbatim, else one headerless group. Empty for a non-select. */
    fun listGroups(option: SessionConfigOption): List<ModelOptionGroup> {
        if (!option.kind.isSelect) return emptyList()
        derive(option)?.let { return it }
        if (option.kind.groups.isNotEmpty()) {
            // A blank server group name would be an empty header row.
            return option.kind.groups.map { ModelOptionGroup(it.group, it.name.ifBlank { null }, it.options) }
        }
        return listOf(ModelOptionGroup(ALL_KEY, null, option.kind.options))
    }

    /** Filter by a search query: a case-insensitive substring of the display name OR the value
     *  id. Groups left empty drop out; a blank query changes nothing. */
    fun filter(groups: List<ModelOptionGroup>, query: String): List<ModelOptionGroup> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val options = group.options.filter { it.name.lowercase().contains(q) || it.value.lowercase().contains(q) }
            if (options.isEmpty()) null else group.copy(options = options)
        }
    }

    /** A header row per named group, then one row per value. Keys are namespaced by group, so
     *  the same value under two groups never collides. */
    fun flatten(groups: List<ModelOptionGroup>): List<ModelOptionRow> = buildList {
        for (group in groups) {
            group.name?.let { add(ModelOptionRow.Header("header:${group.key}", it)) }
            for (option in group.options) {
                add(ModelOptionRow.Option("option:${group.key}:${option.value}", group.key, option))
            }
        }
    }

    private const val UNGROUPED_KEY = "__ungrouped__"
    private const val ALL_KEY = "__all__"
}

/** A select setting. */
internal data class SessionChoice(
    /** The model picker, or a select with more than [SessionSettings.SEGMENTED_MAX] values. The
     *  web draws anything shorter as a segmented control; this client keeps radio rows. */
    val isList: Boolean,
    /** The `id == "model"` picker, whose search says "models". */
    val isModel: Boolean,
    val currentValue: String,
    /** The current value's display name (prefix-stripped when the model list is grouped by
     *  provider), or the raw value when it names no option. */
    val currentLabel: String,
    val currentDescription: String?,
    /** At least one group; a flat list is one headerless group. */
    val groups: List<ModelOptionGroup>,
    /** The agent's recommendation; may name no option, in which case nothing is marked. */
    val recommendedValue: String?,
    /** A list past [SessionSettings.SEARCH_THRESHOLD] values: a searchable page of its own. */
    val searchable: Boolean,
)

internal sealed interface SettingControl {
    data class Choice(val choice: SessionChoice) : SettingControl

    /** A boolean option, or a two-value on/off select. The values are the agent's own, sent
     *  verbatim (`"true"`/`"false"` for a boolean). */
    data class Toggle(val isOn: Boolean, val onValue: String, val offValue: String) : SettingControl

    /**
     * An option this build can't offer a control for — a kind newer than it, a select with no
     * values, or a boolean whose value it can't read. Android keeps the read-only row it always
     * drew for these (the web drops an unknown kind outright).
     */
    data class Unsupported(val currentValue: String?, val isBoolean: Boolean = false) : SettingControl
}

/** One of the agent's settings, as the options button reads it and the sheet edits it. */
internal data class SessionSetting(
    /** Unique across the list: `config:<id>`, or `mode` for the legacy list. */
    val key: String,
    val target: SelectorKey,
    val title: String,
    val description: String?,
    /**
     * What this setting adds to the button's merged label, or null. A switch shows its title
     * only while on; a value whose id is `default` shows nothing — except on the model picker,
     * whose value always shows.
     */
    val summary: String?,
    val control: SettingControl,
) {
    val choice: SessionChoice? get() = (control as? SettingControl.Choice)?.choice
}

internal object SessionSettings {
    /** A list with more values than this is searchable. */
    const val SEARCH_THRESHOLD = 8

    /** A select with at most this many values is "segmented", unless it is the model picker,
     *  which is always a list. */
    const val SEGMENTED_MAX = 8

    /** A summary part this short (in code points, like the web's `[...summary].length`) never
     *  truncates on the button; longer ones give way first. */
    const val SHORT_SUMMARY_LENGTH = 8

    fun isShortSummary(summary: String): Boolean =
        summary.codePointCount(0, summary.length) <= SHORT_SUMMARY_LENGTH

    private val ON_OFF_PAIRS = listOf("on" to "off", "true" to "false")

    /**
     * A two-option select whose values are on/off reads as a switch (Claude's and Codex's "Fast
     * mode" arrive as selects) — but only while the current value is one of the two: Kiro
     * reports `default` for an unset thinking state, and a switch would claim "off" for it.
     */
    private fun onOffValues(options: List<SessionConfigSelectOption>, current: String): Pair<String, String>? {
        if (options.size != 2) return null
        for ((on, off) in ON_OFF_PAIRS) {
            val onOption = options.firstOrNull { it.value.lowercase() == on } ?: continue
            val offOption = options.firstOrNull { it.value.lowercase() == off } ?: continue
            if (current != onOption.value && current != offOption.value) return null
            return onOption.value to offOption.value
        }
        return null
    }

    private fun isDefaultValue(value: String): Boolean = value.lowercase() == "default"

    /** Model first, then effort, then mode, then everything else in the agent's own order, then
     *  the switches: "Opus 5.5 / High / Plan". [option] is null for the legacy mode list. */
    private fun rank(setting: SessionSetting, option: SessionConfigOption?): Int {
        if (setting.control is SettingControl.Toggle) return 4
        if (option != null && ModelOptionGroups.isModelOption(option)) return 0
        return when (if (option != null) option.category else "mode") {
            "thought_level" -> 1
            "mode" -> 2
            else -> 3
        }
    }

    private fun title(option: SessionConfigOption) = option.name.ifEmpty { option.id }

    /** [selected] overrides the catalog's own current value: the sheet's (possibly optimistic)
     *  pick, or a pushed value. */
    private fun setting(option: SessionConfigOption, selected: String?): SessionSetting {
        val key = "config:${option.id}"
        val target = SelectorKey.Config(option.id)
        val title = title(option)
        val description = option.description?.takeIf { it.isNotEmpty() }
        val current = selected ?: option.kind.currentValue
        if (option.kind.isBoolean) {
            val on = SessionConfigKind.booleanState(current)
                ?: return SessionSetting(key, target, title, description, null, SettingControl.Unsupported(current, isBoolean = true))
            return SessionSetting(
                key, target, title, description,
                summary = if (on) title else null,
                control = SettingControl.Toggle(on, SessionConfigKind.TRUE, SessionConfigKind.FALSE),
            )
        }
        if (!option.kind.isSelect) {
            return SessionSetting(key, target, title, description, null, SettingControl.Unsupported(current))
        }
        val currentValue = current.orEmpty()
        onOffValues(option.kind.options, currentValue)?.let { (onValue, offValue) ->
            val on = currentValue == onValue
            return SessionSetting(
                key, target, title, description,
                summary = if (on) title else null,
                control = SettingControl.Toggle(on, onValue, offValue),
            )
        }
        // Derived `provider/` groups for the model, else the agent's own groups, else one flat
        // group — the rendered names are what every surface shows.
        val groups = ModelOptionGroups.listGroups(option)
        val match = groups.asSequence().flatMap { it.options }.firstOrNull { it.value == currentValue }
        val currentLabel = match?.name?.ifEmpty { null } ?: currentValue
        val count = option.kind.options.size
        val isModel = option.id == ModelOptionGroups.MODEL_OPTION_ID
        val isList = isModel || count > SEGMENTED_MAX
        val shows = ModelOptionGroups.isModelOption(option) || !isDefaultValue(currentValue)
        val summary = currentLabel.takeIf { shows && it.isNotEmpty() }
        // A select advertised with no values has nothing to tap.
        if (groups.all { it.options.isEmpty() }) {
            return SessionSetting(key, target, title, description, summary, SettingControl.Unsupported(current))
        }
        val choice = SessionChoice(
            isList = isList,
            isModel = isModel,
            currentValue = currentValue,
            currentLabel = currentLabel,
            currentDescription = match?.description?.takeIf { it.isNotEmpty() },
            groups = groups,
            recommendedValue = option.recommendedValue,
            searchable = isList && count > SEARCH_THRESHOLD,
        )
        return SessionSetting(key, target, title, description, summary, SettingControl.Choice(choice))
    }

    private fun legacyModeSetting(modes: List<SessionModeInfo>, currentModeId: String, title: String): SessionSetting {
        val options = modes.map { SessionConfigSelectOption(it.id, it.name.ifEmpty { it.id }, it.description) }
        val match = options.firstOrNull { it.value == currentModeId }
        val currentLabel = match?.name ?: currentModeId
        val isList = options.size > SEGMENTED_MAX
        val choice = SessionChoice(
            isList = isList,
            isModel = false,
            currentValue = currentModeId,
            currentLabel = currentLabel,
            currentDescription = match?.description?.takeIf { it.isNotEmpty() },
            groups = listOf(ModelOptionGroup("__modes__", null, options)),
            recommendedValue = null,
            searchable = isList && options.size > SEARCH_THRESHOLD,
        )
        return SessionSetting(
            key = "mode",
            target = SelectorKey.Mode,
            title = title,
            description = null,
            summary = if (isDefaultValue(currentModeId)) null else currentLabel.ifEmpty { null },
            control = SettingControl.Choice(choice),
        )
    }

    /**
     * The web's `buildSessionSettings`. Pass [modes] only when the legacy mode list IS the
     * agent's selector (it publishes no config options). [selectedConfig] lays a value over an
     * option's own current value, by option id.
     */
    fun build(
        configOptions: List<SessionConfigOption>,
        modes: List<SessionModeInfo>,
        currentModeId: String?,
        modeTitle: String,
        selectedConfig: Map<String, String> = emptyMap(),
    ): List<SessionSetting> {
        data class Ranked(val setting: SessionSetting, val rank: Int, val index: Int)
        val ranked = mutableListOf<Ranked>()
        configOptions.forEach { option ->
            val setting = setting(option, selectedConfig[option.id])
            ranked += Ranked(setting, rank(setting, option), ranked.size)
        }
        // The web needs a current mode to offer the list at all; the sheet always offered it,
        // with nothing checked, so a mode the agent hasn't reported yet stays settable here.
        if (modes.isNotEmpty()) {
            val setting = legacyModeSetting(modes, currentModeId.orEmpty(), modeTitle)
            ranked += Ranked(setting, rank(setting, null), ranked.size)
        }
        val settings = ranked.sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.index }).map { it.setting }.toMutableList()
        // Everything left out (Gemini, on its `default` mode only): a bare icon would say
        // nothing, so the first non-switch value shows.
        if (settings.none { it.summary != null }) {
            val index = settings.indexOfFirst { it.choice != null }
            if (index >= 0) {
                val label = settings[index].choice!!.currentLabel
                if (label.isNotEmpty()) settings[index] = settings[index].copy(summary = label)
            }
        }
        return settings
    }

    /**
     * The settings as this client holds them — the catalog on screen with the selection laid
     * over it — behind the same gate the sheet always had: the legacy modes only when the agent
     * publishes no config options at all (the web's `hasModes && !hasConfigOptions`).
     */
    fun forCatalog(
        modes: SessionModeState?,
        configOptions: List<SessionConfigOption>,
        selectedModeId: String?,
        selectedConfig: Map<String, String>,
        modeTitle: String,
    ): List<SessionSetting> = build(
        configOptions = configOptions,
        modes = if (configOptions.isEmpty()) modes?.availableModes.orEmpty() else emptyList(),
        currentModeId = selectedModeId ?: modes?.currentModeId,
        modeTitle = modeTitle,
        selectedConfig = selectedConfig,
    )

    /** The parts the button shows, in order. */
    fun summaryParts(settings: List<SessionSetting>): List<String> = settings.mapNotNull { it.summary }

    /**
     * The button's spoken value: every setting, including the ones the visible label leaves out
     * (a `default` value, a switch that is off), as "Model Opus 5.5, Fast mode Off".
     */
    fun accessibilityValue(settings: List<SessionSetting>, on: String, off: String): String =
        settings.mapNotNull { setting ->
            val value = when (val control = setting.control) {
                is SettingControl.Choice -> control.choice.currentLabel
                is SettingControl.Toggle -> if (control.isOn) on else off
                is SettingControl.Unsupported -> control.currentValue?.takeIf { it.isNotEmpty() }
            } ?: return@mapNotNull null
            "${setting.title} $value"
        }.joinToString(", ")
}

/** ACP's select kind — the default, so a payload that names no type reads as one. */
internal val SessionConfigKind.isSelect: Boolean get() = type == "select"
