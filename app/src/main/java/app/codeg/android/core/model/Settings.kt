package app.codeg.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A reusable message template (`quick_messages_*`). */
@Serializable
data class QuickMessage(
    val id: Int,
    val title: String,
    val content: String,
    val sortOrder: Int = 0,
)

/** `{ "enabled": Bool }` — feedback / ask-question toggle settings. */
@Serializable
data class EnabledFlag(val enabled: Boolean)

/** `{ "settings": { "enabled": Bool } }` — request wrapper for the toggle setters. */
@Serializable
data class EnabledSettingsBody(val settings: EnabledFlag)

/** A custom OpenAI-compatible model endpoint (`*_model_provider`). */
@Serializable
data class ModelProviderInfo(
    val id: Int,
    val name: String,
    val apiUrl: String,
    val apiKeyMasked: String = "",
    val agentType: AgentType,
    val model: String? = null,
)

/** Server proxy settings (`get_system_proxy_settings`; decoded snake_case → camelCase). */
@Serializable
data class SystemProxySettings(
    val enabled: Boolean = false,
    val proxyUrl: String? = null,
)

/** A pending app update (`check_app_update`; camelCase wire). */
@Serializable
data class AppUpdateInfo(
    val version: String,
    val body: String = "",
    val date: String? = null,
)

/** `check_app_update` result (camelCase wire — decode with the camelCase codec). */
@Serializable
data class AppUpdateCheckResult(
    val currentVersion: String = "",
    val update: AppUpdateInfo? = null,
    val selfUpdateSupported: Boolean = false,
)

/** A locally-configured MCP server (`mcp_scan_local`). [spec] is a free-form JSON object. */
@Serializable
data class LocalMcpServer(
    val id: String,
    val spec: JsonElement,
    val apps: List<String> = emptyList(),
)

/** One agent skill (`acp_list_agent_skills`). */
@Serializable
data class AgentSkillItem(
    val id: String,
    val name: String = "",
    val scope: String = "global",
    val layout: String = "markdown_file",
    val path: String = "",
    val description: String? = null,
    val readOnly: Boolean = false,
)

/** `acp_list_agent_skills` result. */
@Serializable
data class AgentSkillsListResult(
    val supported: Boolean = true,
    val message: String? = null,
    val skills: List<AgentSkillItem> = emptyList(),
)

/** `acp_read_agent_skill` result. */
@Serializable
data class AgentSkillContent(
    val skill: AgentSkillItem,
    val content: String = "",
)

/** One catalog expert (`experts_list`). */
@Serializable
data class ExpertListItem(val metadata: ExpertMetadata) {
    val id: String get() = metadata.id
}

@Serializable
data class ExpertMetadata(
    val id: String,
    val category: String = "",
    val icon: String? = null,
    val sortOrder: Int = 0,
    val displayName: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
) {
    /** English (or first) display name. */
    fun title(): String = displayName["en"] ?: displayName.values.firstOrNull() ?: id
    fun desc(): String = description["en"] ?: description.values.firstOrNull() ?: ""
}

/** Per-agent link state of a built-in expert (`experts_get_install_status`).
 *  String enum *value* — unaffected by the snake_case key decoder. */
@Serializable
enum class ExpertLinkState {
    @kotlinx.serialization.SerialName("not_linked") NOT_LINKED,
    @kotlinx.serialization.SerialName("linked_to_codeg") LINKED_TO_CODEG,
    @kotlinx.serialization.SerialName("linked_elsewhere") LINKED_ELSEWHERE,
    @kotlinx.serialization.SerialName("blocked_by_real_directory") BLOCKED_BY_REAL_DIRECTORY,
    @kotlinx.serialization.SerialName("broken") BROKEN;

    /** Whether the expert is currently linked to this agent (toggle "on" state). */
    val isLinked: Boolean get() = this == LINKED_TO_CODEG
}

/** `experts_get_install_status` / `experts_link_to_agent` result.
 *  The server emits these fields in camelCase — decode with the camelCase codec. */
@Serializable
data class ExpertInstallStatus(
    val expertId: String = "",
    val agentType: AgentType,
    val state: ExpertLinkState = ExpertLinkState.NOT_LINKED,
    val linkPath: String = "",
    val targetPath: String? = null,
    val expectedTargetPath: String = "",
    val copyMode: Boolean = false,
)

/** `detect_git` / `test_git_path` result. */
@Serializable
data class GitDetectResult(
    val installed: Boolean = false,
    val version: String? = null,
    val path: String? = null,
)

/** `get_git_settings`. */
@Serializable
data class GitSettings(val customPath: String? = null)

/**
 * A linked GitHub account (`get_github_accounts` → `{accounts:[…]}`).
 *
 * [scopes]/[avatarUrl]/[createdAt] carry no meaning to the phone UI, but the
 * credential-retry flow appends a new account and sends the **whole** list back via
 * `update_github_accounts`; keeping these fields makes that round-trip lossless for
 * the accounts the user already had (they'd otherwise be stripped on re-serialize).
 */
@Serializable
data class GitHubAccount(
    val id: String,
    val serverUrl: String = "github.com",
    val username: String = "",
    val isDefault: Boolean = false,
    val scopes: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class GitHubAccountList(val accounts: List<GitHubAccount> = emptyList())

/**
 * Result of `validate_github_token`. Note the flag is `success` (not `valid`), and
 * [message] carries the error text on failure.
 */
@Serializable
data class GitHubTokenValidation(
    val success: Boolean = false,
    val username: String? = null,
    val scopes: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val message: String? = null,
)

/**
 * A registered agent (`acp_list_agents`). Carries both the list-row fields and the
 * per-agent config payloads the detail/config screen edits — the server sends all
 * of these and [CodegJson] ignores unknown keys, so adding them is non-breaking.
 *
 * The config payloads round-trip CLEARTEXT secrets (unlike model providers, the
 * server returns agent keys unmasked); they are held in-memory only.
 */
@Serializable
data class AcpAgentInfo(
    val agentType: AgentType,
    val name: String = "",
    val description: String = "",
    val available: Boolean = true,
    val enabled: Boolean = false,
    val sortOrder: Int = 0,
    val installedVersion: String? = null,
    val env: Map<String, String> = emptyMap(),
    val modelProviderId: Int? = null,
    /** Latest version from the npm/GitHub registry, when known. */
    val registryVersion: String? = null,
    /** `"binary" | "npx" | "uvx" | "system"` — how the agent is distributed. */
    val distributionType: String? = null,
    /** Display-only path of the on-disk config file (e.g. `~/.claude/settings.json`). */
    val configFilePath: String? = null,
    /** Raw `config.json` (or, for Hermes, a read-only projection). */
    val configJson: String? = null,
    /** `~/.config/opencode/auth.json` (OpenCode only). */
    val opencodeAuthJson: String? = null,
    /** `~/.codex/auth.json` (Codex only; OAuth tokens are read-only). */
    val codexAuthJson: String? = null,
    /** `~/.codex/config.toml` (Codex only). */
    val codexConfigToml: String? = null,
    /** `~/.hermes/config.yaml` (Hermes only). */
    val hermesConfigYaml: String? = null,
    /** Raw `~/.grok/config.toml` (Grok only; the Advanced escape-hatch editor source). */
    val grokConfigToml: String? = null,
    /** `grok` parsed scalar settings backing the structured controls — derived
     * server-side from [grokConfigToml] (`null` fields ⇒ the key is absent). */
    val grokSettings: GrokSettings? = null,
    /** Raw `~/.cursor/cli-config.json` (Cursor only; the Advanced escape-hatch editor
     * source, shared with the Cursor CLI's own `/config` UI). */
    val cursorCliConfigJson: String? = null,
    /** `cursor` parsed scalar settings backing the structured controls — derived
     * server-side from [cursorCliConfigJson]. */
    val cursorSettings: CursorSettings? = null,
    /**
     * A custom agent's own display icon, inlined as a `data:` URL. Always null for
     * built-ins, which ship hand-drawn marks in `AgentVisuals`.
     *
     * NOT rendered here: ACP-registry marks are SVGs, which Android cannot rasterize
     * without an image library this app deliberately does not carry (its only images
     * are compile-time vector drawables). A custom agent shows the neutral plug mark
     * tinted by a colour derived from its id instead. Kept on the model because it is
     * part of the wire contract and is what a future SVG-capable loader would read.
     */
    val iconUrl: String? = null,
    /**
     * For a custom agent, where its definition came from (`registry` | `manual`); null
     * for built-ins. A manual definition's [registryVersion] is user-typed, so only the
     * installed version is worth showing for those.
     */
    val customSource: String? = null,
    /**
     * Whether this agent has a codeg-known skill store — every built-in, plus custom
     * agents that declared the shared `.agents/skills` directory. Gates the Skills
     * screen's per-agent rows.
     */
    val skillsCapable: Boolean = true,
)

/**
 * One entry of an agent picker: the wire identity plus the label to show for it.
 *
 * A built-in names itself from its [AgentType]; a custom agent cannot, because its type
 * carries only the registry id (`goose`) and the display name lives on the server's
 * [AcpAgentInfo] row. So the label is carried rather than re-derived. The mark is not: every
 * agent draws from [AgentType] alone (see `AgentVisuals.icon`), so there is nothing else here.
 */
data class AgentChoice(
    val type: AgentType,
    val label: String,
) {
    companion object {
        fun of(agent: AcpAgentInfo): AgentChoice = AgentChoice(
            type = agent.agentType,
            // Built-ins keep their curated short label; a custom agent prefers the name
            // it registered under, falling back to its id.
            label = agent.agentType.builtin?.shortName
                ?: agent.name.ifBlank { agent.agentType.shortName },
        )

        /** For the offline fallback list, where no server row is available. */
        fun of(type: AgentType): AgentChoice = AgentChoice(type, type.shortName)
    }
}

/**
 * Parsed scalar keys from `~/.grok/config.toml` backing the Grok panel's structured
 * controls (`null` = the key is absent). The server serializes these snake_case
 * (`permission_mode` / `default_reasoning_effort`); [CodegJson]'s response
 * SnakeCase strategy maps them to these camelCase properties.
 */
@Serializable
data class GrokSettings(
    val permissionMode: String? = null,
    val defaultReasoningEffort: String? = null,
)

/**
 * The subset of `~/.cursor/cli-config.json` codeg manages, projected by the backend
 * (`sandbox_mode` / `permissions_allow` / `permissions_deny` → these camelCase
 * properties via [CodegJson]'s response SnakeCase strategy). Everything else in the
 * file is preserved verbatim on write, so this is a view, not the whole document.
 * The rule lists default to empty rather than failing when the key is absent.
 */
@Serializable
data class CursorSettings(
    val sandboxMode: String? = null,
    val permissionsAllow: List<String> = emptyList(),
    val permissionsDeny: List<String> = emptyList(),
)

/**
 * `acp_cursor_auth_status` — the result of probing `cursor-agent status`. Parsed
 * defensively server-side, so an unknown shape still yields something showable via
 * [rawStatus] / [error].
 */
@Serializable
data class CursorAuthStatus(
    /** A launchable cursor-agent binary was found (cache or system install). */
    val installed: Boolean = false,
    val isAuthenticated: Boolean = false,
    /** The CLI's own status string (e.g. `"unauthenticated"`). */
    val rawStatus: String? = null,
    /** Account email when signed in. */
    val email: String? = null,
    /** Membership/plan label when the CLI reports one (usually absent). */
    val membership: String? = null,
    /** Probe failure detail (spawn error / timeout / non-JSON output). */
    val error: String? = null,
    /** Absolute path of the binary codeg would launch — the source for
     * [CursorConfig.loginCommand]. Null when nothing is installed. */
    val binaryPath: String? = null,
)

/** One entry from `cursor-agent models`. [label] is the human name (empty when the
 *  CLI emitted a bare id); [id] is what goes to `--model`. */
@Serializable
data class CursorModelInfo(
    val id: String = "",
    val label: String = "",
    val isDefault: Boolean = false,
) {
    /** What the picker shows — the CLI's label, falling back to the raw id. */
    val displayLabel: String get() = label.ifEmpty { id }
}

/** `acp_cursor_list_models` — best-effort parsed CLI output. [error] carries the reason
 *  the probe couldn't run (e.g. not signed in) while [models] stays empty. */
@Serializable
data class CursorModelsResult(
    val models: List<CursorModelInfo> = emptyList(),
    val defaultModel: String? = null,
    val error: String? = null,
)
