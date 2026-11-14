package app.codeg.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The twelve agents codeg ships hand-written support for: a launch recipe, a
 * transcript parser, and (for most) a dedicated settings panel. Wire value is
 * snake_case (Rust serde `rename_all = "snake_case"`).
 *
 * Kept free of any Compose/UI types so the model layer stays pure Kotlin — the
 * per-agent accent colours and brand marks live in the design system
 * (`AgentVisuals`), keyed off this enum.
 */
enum class BuiltinAgent(val wire: String) {
    CLAUDE_CODE("claude_code"),
    CODEX("codex"),
    OPEN_CODE("open_code"),
    GEMINI("gemini"),
    OPEN_CLAW("open_claw"),
    CLINE("cline"),
    HERMES("hermes"),
    CODE_BUDDY("code_buddy"),
    KIMI_CODE("kimi_code"),
    PI("pi"),
    GROK("grok"),
    CURSOR("cursor"),

    // Built in since codeg 0.4x. None has a hand-written settings panel in this app yet
    // ([managedOnDesktop]).
    DEEPSEEK("deepseek"),
    QODER("qoder"),
    ANTIGRAVITY("antigravity"),
    KIRO("kiro"),
    ZCODE("zcode"),
    TRAE("trae"),
    COMMAND_CODE("command_code"),
    MINIMAX_CODE("minimax_code");

    val displayName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude Code"
            CODEX -> "Codex CLI"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini CLI"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi Code"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek Harness"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Google Antigravity"
            KIRO -> "Kiro CLI"
            ZCODE -> "ZCode"
            TRAE -> "TRAE CLI"
            COMMAND_CODE -> "Command Code"
            MINIMAX_CODE -> "MiniMax Code"
        }

    /** Short label for dense badges. */
    val shortName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude"
            CODEX -> "Codex"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Antigravity"
            KIRO -> "Kiro"
            ZCODE -> "ZCode"
            TRAE -> "TRAE"
            COMMAND_CODE -> "Command Code"
            MINIMAX_CODE -> "MiniMax"
        }

    /**
     * Configured only in the codeg desktop app: this app has no panel for it. The server
     * keeps no local config file for most of these (`agent_local_config_path` is None), so
     * the generic config editor would report a save and drop it — the detail screen shows
     * a note instead.
     */
    val managedOnDesktop: Boolean
        get() = when (this) {
            DEEPSEEK, QODER, ANTIGRAVITY, KIRO, ZCODE, TRAE, COMMAND_CODE, MINIMAX_CODE -> true
            else -> false
        }

    companion object {
        fun fromWire(raw: String): BuiltinAgent? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * Which agent backs a conversation.
 *
 * Open-ended on purpose (mirrors the Rust `AgentType`): besides the twelve
 * [BuiltinAgent]s, a user can register any ACP agent with the desktop app, and it
 * then travels as `custom:<registry-id>` on every wire — on session rows, on the
 * agents list, and on `create_conversation`. codeg records those agents' history
 * itself, so their sessions show up here like any other.
 *
 * [Unknown] is the forward-compat arm: a wire value this build recognises as
 * neither. It keeps the raw string VERBATIM, so an agent added to a newer server
 * still round-trips correctly instead of masquerading as Claude Code — and two
 * different unknown agents keep two different identities, which list keys depend on.
 */
@Serializable(with = AgentType.AgentTypeSerializer::class)
sealed interface AgentType {
    /** The value the server persists and expects back. */
    val wire: String

    /** Full label. For a custom/unknown agent this is only a fallback — prefer
     *  `AcpAgentInfo.name`, which carries the agent's own display name. */
    val displayName: String

    /** Short label for dense badges. */
    val shortName: String

    /** One of the twelve compiled-in agents. */
    data class Builtin(val agent: BuiltinAgent) : AgentType {
        override val wire: String get() = agent.wire
        override val displayName: String get() = agent.displayName
        override val shortName: String get() = agent.shortName
    }

    /** A user-registered ACP agent, identified by its ACP-registry id. */
    data class Custom(val id: String) : AgentType {
        override val wire: String get() = CUSTOM_WIRE_PREFIX + id
        override val displayName: String get() = id
        override val shortName: String get() = id
    }

    /** A wire value from a newer server, kept verbatim so it round-trips. */
    data class Unknown(override val wire: String) : AgentType {
        override val displayName: String get() = wire
        override val shortName: String get() = wire
    }

    /** True for a user-registered agent (no per-agent settings panel exists). */
    val isCustom: Boolean get() = this is Custom

    /** The built-in behind this type, or null for a custom/unknown agent. Switch on
     *  this (rather than on [AgentType]) wherever all twelve need distinct handling —
     *  the `when` stays exhaustive and a `null` branch names the fallback. */
    val builtin: BuiltinAgent? get() = (this as? Builtin)?.agent

    companion object {
        /** Wire prefix marking a custom (user-registered) ACP agent. */
        const val CUSTOM_WIRE_PREFIX = "custom:"

        val CLAUDE_CODE = Builtin(BuiltinAgent.CLAUDE_CODE)
        val CODEX = Builtin(BuiltinAgent.CODEX)
        val OPEN_CODE = Builtin(BuiltinAgent.OPEN_CODE)
        val GEMINI = Builtin(BuiltinAgent.GEMINI)
        val OPEN_CLAW = Builtin(BuiltinAgent.OPEN_CLAW)
        val CLINE = Builtin(BuiltinAgent.CLINE)
        val HERMES = Builtin(BuiltinAgent.HERMES)
        val CODE_BUDDY = Builtin(BuiltinAgent.CODE_BUDDY)
        val KIMI_CODE = Builtin(BuiltinAgent.KIMI_CODE)
        val PI = Builtin(BuiltinAgent.PI)
        val GROK = Builtin(BuiltinAgent.GROK)
        val CURSOR = Builtin(BuiltinAgent.CURSOR)
        val DEEPSEEK = Builtin(BuiltinAgent.DEEPSEEK)
        val QODER = Builtin(BuiltinAgent.QODER)
        val ANTIGRAVITY = Builtin(BuiltinAgent.ANTIGRAVITY)
        val KIRO = Builtin(BuiltinAgent.KIRO)
        val ZCODE = Builtin(BuiltinAgent.ZCODE)
        val TRAE = Builtin(BuiltinAgent.TRAE)
        val COMMAND_CODE = Builtin(BuiltinAgent.COMMAND_CODE)
        val MINIMAX_CODE = Builtin(BuiltinAgent.MINIMAX_CODE)

        /** The built-ins, in declaration order. Does NOT include custom agents —
         *  use the live `acp_list_agents` result for the set the server actually has. */
        val BUILTINS: List<Builtin> = BuiltinAgent.entries.map(::Builtin)

        /**
         * The agent for [raw]. Never fails: a `custom:<id>` with a valid slug becomes
         * [Custom], anything else this build doesn't know becomes [Unknown] carrying the
         * raw value. Nothing is collapsed onto another agent's identity.
         */
        fun fromWire(raw: String): AgentType {
            BuiltinAgent.fromWire(raw)?.let { return Builtin(it) }
            raw.removePrefix(CUSTOM_WIRE_PREFIX)
                .takeIf { it != raw && isValidCustomId(it) }
                ?.let { return Custom(it) }
            return Unknown(raw)
        }

        /**
         * Whether [id] is usable as a custom-agent registry id — mirrors the Rust
         * `is_valid_custom_agent_id`. The server uses it as a filesystem path component,
         * so it stays a conservative slug: it may not be empty, exceed 64 chars, contain
         * anything but ASCII alphanumerics / `-` / `_` / `.`, start with a dot (which
         * would mean a hidden directory, and `.`/`..` would escape one), or be one of
         * [RESERVED_CUSTOM_IDS].
         */
        fun isValidCustomId(id: String): Boolean =
            id.isNotEmpty() &&
                id.length <= 64 &&
                id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' } &&
                !id.startsWith('.') &&
                id !in RESERVED_CUSTOM_IDS

        /**
         * Built-in wire names a custom id may not take — the server's list, verbatim. It is
         * NOT every built-in: zcode, trae, command_code and minimax_code were runnable only as
         * hand-registered custom agents before they were built in, so `custom:zcode` etc. stay
         * valid, distinct identities (their built-ins use other registry ids). Deriving this
         * from [BuiltinAgent] would turn those sessions into [Unknown].
         */
        private val RESERVED_CUSTOM_IDS: Set<String> = setOf(
            "claude_code", "codex", "open_code", "gemini", "open_claw", "cline", "hermes",
            "code_buddy", "kimi_code", "pi", "grok", "cursor",
            "deepseek", "qoder", "antigravity", "kiro",
        )
    }

    object AgentTypeSerializer : KSerializer<AgentType> {
        override val descriptor =
            PrimitiveSerialDescriptor("app.codeg.AgentType", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: AgentType) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): AgentType =
            fromWire(decoder.decodeString())
    }
}
