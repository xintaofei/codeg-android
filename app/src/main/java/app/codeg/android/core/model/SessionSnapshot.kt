package app.codeg.android.core.model

import kotlinx.serialization.Serializable

/**
 * A live session's available slash command (`SessionSnapshot.availableCommands`).
 * Slash commands are not a dedicated endpoint — they come from the session
 * snapshot. Mirrors iOS `AvailableCommandInfo`.
 */
@Serializable
data class AvailableCommandInfo(
    val name: String,
    val description: String? = null,
    val inputHint: String? = null,
)

/**
 * Authoritative live session state (`acp_get_session_snapshot_by_conversation` /
 * `acp_get_session_snapshot`). Modeled with the fields the compose-insert sheet
 * and the agent-options sheet need; unknown fields are ignored. Mirrors iOS
 * `SessionSnapshot` (a subset).
 */
@Serializable
data class SessionSnapshot(
    val selectorsReady: Boolean = false,
    val availableCommands: List<AvailableCommandInfo> = emptyList(),
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
    /**
     * Mid-turn steering notes for the turn running right now, in submit order. Present so a
     * client attaching mid-turn recovers notes whose one-shot `feedback_submitted` event it
     * missed — including notes another client sent.
     */
    val feedback: List<FeedbackNote> = emptyList(),
    /**
     * Whether THIS agent process was launched with the `check_user_feedback` MCP tool. Fixed
     * at spawn: enabling the live-feedback setting mid-session cannot retrofit a tool onto a
     * running agent, so this — not the global setting — is what says a note can be pulled.
     */
    val feedbackToolAvailable: Boolean = false,
    /**
     * Whether notes ride the native ACP `_session/steering` push instead, landing in the
     * running turn immediately. Server-synthesized from three gates (advertisement, registry
     * policy, the running binary's version); never re-derive it from the agent type.
     *
     * Can go from true to false mid-session when an adapter mishandles the push — see
     * `SessionDetailViewModel.sendNote`.
     */
    val nativeSteeringAvailable: Boolean = false,
)

/** The current + available session modes. */
@Serializable
data class SessionModeState(
    val currentModeId: String? = null,
    val availableModes: List<SessionModeInfo> = emptyList(),
)

@Serializable
data class SessionModeInfo(
    val id: String,
    val name: String = "",
    val description: String? = null,
)

/** A configurable session option — a `select` of values (e.g. reasoning effort)
 *  or a `boolean` on/off toggle (cline 3.0.50's `auto_approve`). */
@Serializable
data class SessionConfigOption(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val category: String? = null,
    val kind: SessionConfigKind = SessionConfigKind(),
    /**
     * The value the AGENT recommends (`recommended_value`: JetBrains AIR's `recommendedValue`,
     * which codex-acp 1.11.0+ and claude-agent-acp 0.76.0+ use to name their default model and
     * that model's default effort). A hint only: [SessionConfigKind.currentValue] still says
     * what is selected, and a recommendation that names no option marks nothing. Absent on
     * servers that predate the field.
     */
    val recommendedValue: String? = null,
)

@Serializable
data class SessionConfigKind(
    val type: String = "select",
    /**
     * The value the agent currently holds.
     *
     * A `boolean` option reports a real JSON `true`/`false` here, which lands as
     * the string `"true"`/`"false"` because
     * [app.codeg.android.core.network.CodegJson.response] is lenient. The read
     * and write paths are asymmetric on purpose: codeg's *inputs* (the set call,
     * the connect preferences, the preference store) are opaque strings, and
     * only its ACP egress turns `"true"` back into a JSON boolean — so a boolean
     * arrives typed and leaves as text. Locked in by `SessionConfigKindTest`.
     */
    val currentValue: String? = null,
    val options: List<SessionConfigSelectOption> = emptyList(),
    val groups: List<SessionConfigSelectGroup> = emptyList(),
) {
    /** ACP's boolean config kind, rendered as a toggle rather than a value list. */
    val isBoolean: Boolean get() = type == BOOLEAN_TYPE

    /**
     * Every value this option accepts — flat + grouped select values, or a boolean's two
     * positions. Used to validate a remembered preference against the live catalog before
     * pre-selecting it in the options sheet.
     *
     * A boolean advertises no `options`, so omitting it here would reject every remembered
     * toggle and open the sheet on the agent's default — while `connectWithPreferences`
     * still hands that same remembered value to the server as a connect preference. The
     * sheet would then contradict what the session actually runs with.
     */
    val acceptedValues: List<String>
        get() = if (isBoolean) listOf(TRUE, FALSE)
        else options.map { it.value } + groups.flatMap { g -> g.options.map { it.value } }

    companion object {
        const val BOOLEAN_TYPE = "boolean"
        const val TRUE = "true"
        const val FALSE = "false"

        /** The value to send for a toggle position (codeg's HTTP inputs are strings). */
        fun booleanValue(on: Boolean): String = if (on) TRUE else FALSE

        /**
         * Read a toggle position out of a config value, or `null` when the value isn't one
         * this build can interpret.
         *
         * Takes the value rather than reading [currentValue] because the options sheet
         * shows the *optimistic* value while an apply is in flight, and that is the string
         * whose position has to be rendered.
         *
         * `null` is not "off": `current_value` is a required field on the server's
         * `SessionConfigBooleanInfo`, so an absent or unrecognized value means a payload
         * this build can't read — and for a permission-shaped option like cline's
         * `auto_approve`, guessing "off" would show the agent as more restricted than it
         * may actually be.
         */
        fun booleanState(value: String?): Boolean? = when (value) {
            TRUE -> true
            FALSE -> false
            else -> null
        }
    }
}

@Serializable
data class SessionConfigSelectOption(
    val value: String,
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class SessionConfigSelectGroup(
    val group: String = "",
    val name: String = "",
    val options: List<SessionConfigSelectOption> = emptyList(),
)

/** `acp_describe_agent_options` result (the per-agent catalog of modes + config). */
@Serializable
data class AgentOptionsSnapshot(
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
)
