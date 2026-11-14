package app.codeg.android.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-agent-type configuration codec — a faithful Kotlin port of the iOS
 * `AgentConfig.swift` (itself ported from the web `acp-agent-settings.tsx`).
 *
 * The source of truth is the pair of raw strings the server persists:
 *   - `configText`  → `config.json` (`acp_update_agent_config`)
 *   - `envText`     → the flat `KEY=VALUE` env (`acp_update_agent_env`)
 * Structured form fields are *extracted* from these on load and *reapplied* back
 * into them after every edit, so the structured forms produce the exact same
 * payloads the web/iOS clients do. Codex's TOML codec lives in [AgentToml].
 *
 * SECURITY: agent config round-trips CLEARTEXT secrets (the server returns them
 * unmasked, unlike model providers). Everything here is pure string/JSON
 * transformation held in-memory only — nothing is persisted on device.
 */

// region Enums

enum class ClaudeAuthMode(val wire: String) {
    OFFICIAL_SUBSCRIPTION("official_subscription"),
    CUSTOM("custom"),
    MODEL_PROVIDER("model_provider"),
}

enum class CodexAuthMode(val wire: String) {
    API_KEY("api_key"),
    CHATGPT_SUBSCRIPTION("chatgpt_subscription"),
    MODEL_PROVIDER("model_provider"),
}

enum class GeminiAuthMode(val wire: String) {
    CUSTOM("custom"),
    LOGIN_GOOGLE("login_google"),
    GEMINI_API_KEY("gemini_api_key"),
    VERTEX_ADC("vertex_adc"),
    VERTEX_SERVICE_ACCOUNT("vertex_service_account"),
    VERTEX_API_KEY("vertex_api_key"),
    MODEL_PROVIDER("model_provider"),
}

/** `""` is the canonical "default" (the server may also send the sentinel `"default"`). */
enum class ClaudeEffortLevel(val wire: String) {
    DEFAULT(""),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    companion object {
        fun fromWire(raw: String?): ClaudeEffortLevel {
            val n = raw?.trim()?.lowercase().orEmpty()
            if (n.isEmpty() || n == "default") return DEFAULT
            return entries.firstOrNull { it.wire == n } ?: DEFAULT
        }
    }
}

enum class CodexReasoningEffort(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    companion object {
        val FALLBACK = HIGH
        fun fromWire(raw: String?): CodexReasoningEffort =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: FALLBACK
    }
}

/**
 * CodeBuddy's region/deployment selector. `internal`/`ioa` are written verbatim to
 * `CODEBUDDY_INTERNET_ENVIRONMENT`; `overseas` leaves that key UNSET (the overseas
 * build requires absence, not an empty value); `selfHosted` instead writes
 * `CODEBUDDY_BASE_URL` and clears the region key. Mirrors the web/iOS
 * `CodeBuddyEnvironment`.
 */
enum class CodeBuddyEnvironment(val wire: String) {
    OVERSEAS("overseas"),
    INTERNAL("internal"),
    IOA("ioa"),
    SELF_HOSTED("self_hosted");

    companion object {
        fun fromWire(raw: String?): CodeBuddyEnvironment? =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

// endregion

// region Constants (env key maps + option lists, verbatim from web)

object AgentEnvKeys {
    const val CLAUDE_MAIN_MODEL = "ANTHROPIC_MODEL"
    const val CLAUDE_REASONING_MODEL = "ANTHROPIC_REASONING_MODEL"
    const val CLAUDE_DEFAULT_HAIKU_MODEL = "ANTHROPIC_DEFAULT_HAIKU_MODEL"
    const val CLAUDE_DEFAULT_SONNET_MODEL = "ANTHROPIC_DEFAULT_SONNET_MODEL"
    const val CLAUDE_DEFAULT_OPUS_MODEL = "ANTHROPIC_DEFAULT_OPUS_MODEL"
    const val CLAUDE_EFFORT_CONFIG_KEY = "effortLevel"

    object Gemini {
        const val BASE_URL = "GOOGLE_GEMINI_BASE_URL"
        const val LEGACY_BASE_URL = "GEMINI_BASE_URL"
        const val GEMINI_API_KEY = "GEMINI_API_KEY"
        const val LEGACY_GEMINI_API_KEY = "GOOGLE_GEMINI_API_KEY"
        const val GOOGLE_API_KEY = "GOOGLE_API_KEY"
        const val CLOUD_PROJECT = "GOOGLE_CLOUD_PROJECT"
        const val CLOUD_PROJECT_LEGACY = "GOOGLE_CLOUD_PROJECT_ID"
        const val CLOUD_LOCATION = "GOOGLE_CLOUD_LOCATION"
        const val APPLICATION_CREDENTIALS = "GOOGLE_APPLICATION_CREDENTIALS"
        const val MODEL = "GEMINI_MODEL"
    }

    object OpenClaw {
        const val GATEWAY_URL = "OPENCLAW_GATEWAY_URL"
        const val GATEWAY_TOKEN = "OPENCLAW_GATEWAY_TOKEN"
        const val SESSION_KEY = "OPENCLAW_SESSION_KEY"
    }

    object CodeBuddy {
        const val API_KEY = "CODEBUDDY_API_KEY"
        const val ENVIRONMENT = "CODEBUDDY_INTERNET_ENVIRONMENT"
        const val BASE_URL = "CODEBUDDY_BASE_URL"
    }

    object Grok {
        const val API_KEY = "XAI_API_KEY"
    }

    /** Cursor's keys live on [CursorConfig] (the panel's env rules read them too). */

    data class ImportantKeys(val apiBaseUrl: List<String>, val apiKey: List<String>, val model: List<String>)

    /**
     * Priority-ordered env keys per agent for the generic apiBaseUrl/apiKey/model
     * (the FIRST is the canonical write target). Mirrors `importantEnvKeysByAgent`.
     */
    fun important(agent: AgentType): ImportantKeys = when (agent) {
        AgentType.CLAUDE_CODE -> ImportantKeys(
            listOf("ANTHROPIC_BASE_URL", "OPENAI_BASE_URL", "API_BASE_URL"),
            listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY"),
            listOf("ANTHROPIC_MODEL", "OPENAI_MODEL", "MODEL"),
        )
        AgentType.GEMINI -> ImportantKeys(
            listOf("GOOGLE_GEMINI_BASE_URL", "GEMINI_BASE_URL", "API_BASE_URL"),
            listOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GEMINI_API_KEY", "API_KEY"),
            listOf("GEMINI_MODEL", "MODEL"),
        )
        // Grok reads XAI_API_KEY only (the generic API_KEY alias is deliberately
        // excluded — it would falsely report "configured"). Base URL / model have
        // working env overrides but aren't surfaced in the panel; they round-trip.
        AgentType.GROK -> ImportantKeys(
            listOf("GROK_XAI_API_BASE_URL", "XAI_API_BASE_URL", "API_BASE_URL"),
            listOf("XAI_API_KEY"),
            listOf("GROK_DEFAULT_MODEL", "MODEL"),
        )
        // cursor-agent authenticates against Cursor's own backend only: no
        // bring-your-own endpoint (so no base-url keys at all), and the generic
        // API_KEY alias is excluded so the auth card can't false-positive.
        AgentType.CURSOR -> ImportantKeys(
            emptyList(),
            listOf(CursorConfig.API_KEY),
            listOf(CursorConfig.MODEL),
        )
        else -> ImportantKeys(
            listOf("OPENAI_BASE_URL", "API_BASE_URL"),
            listOf("OPENAI_API_KEY", "API_KEY"),
            listOf("OPENAI_MODEL", "MODEL"),
        )
    }
}

/** `value`/`label` for the Cline provider picker (verbatim from web). */
val clineProviders: List<Pair<String, String>> = listOf(
    "anthropic" to "Anthropic",
    "openai-native" to "OpenAI",
    "openai" to "OpenAI Compatible",
    "openrouter" to "OpenRouter",
    "gemini" to "Gemini",
    "deepseek" to "DeepSeek",
    "bedrock" to "AWS Bedrock",
    "vertex" to "GCP Vertex",
    "ollama" to "Ollama",
)

data class CodexEffortOption(val value: CodexReasoningEffort, val label: String, val description: String)

val codexReasoningEffortOptions: List<CodexEffortOption> = listOf(
    CodexEffortOption(CodexReasoningEffort.LOW, "Low", "Fast responses with lighter reasoning"),
    CodexEffortOption(CodexReasoningEffort.MEDIUM, "Medium", "Balances speed and reasoning depth for everyday tasks"),
    CodexEffortOption(CodexReasoningEffort.HIGH, "High", "Greater reasoning depth for complex problems"),
    CodexEffortOption(CodexReasoningEffort.XHIGH, "Extra High", "Extra-high reasoning depth for the hardest problems"),
)

const val CODEX_DEFAULT_MODEL_PROVIDER = "codeg"

/**
 * OpenCode provider npm packages (verbatim from web). The first is the default
 * filled in by [AgentConfig.ensureOpenCodeProviderNpm].
 */
val openCodeNpmOptions: List<String> = listOf(
    "@ai-sdk/openai-compatible", "@ai-sdk/cerebras", "@ai-sdk/azure", "@ai-sdk/xai",
    "@ai-sdk/anthropic", "@ai-sdk/amazon-bedrock", "@ai-sdk/google", "@ai-sdk/google-vertex",
    "@ai-sdk/deepseek",
)

// endregion

// region JSON primitives

object JsonConfig {
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    data class Parsed(val config: Map<String, JsonElement>, val error: String?)

    /**
     * Parse a config.json string. Returns `({}, null)` for empty; `({}, error)`
     * for invalid/non-object; `(dict, null)` otherwise.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed(emptyMap(), null)
        return try {
            when (val el = parser.parseToJsonElement(trimmed)) {
                is JsonObject -> Parsed(el, null)
                else -> Parsed(emptyMap(), "Native JSON config must be an object")
            }
        } catch (_: Exception) {
            Parsed(emptyMap(), "Native JSON config format error")
        }
    }

    /**
     * `JSON.stringify(obj, null, 2)` equivalent. Empty object → "". Keys sorted
     * recursively for deterministic output (ordering is cosmetic since the server
     * re-parses — sorting avoids spurious diffs).
     */
    fun serialize(obj: Map<String, JsonElement>): String {
        if (obj.isEmpty()) return ""
        return pretty.encodeToString(JsonElement.serializer(), sortKeys(JsonObject(obj)))
    }

    private fun sortKeys(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(el.map { sortKeys(it) })
        else -> el
    }

    /**
     * Recursive `markRemovedKeysNull`: any key present in [original] but absent in
     * [current] is set to `null` so the backend's merge deletes it from disk;
     * nested objects recurse. Used by claude/gemini/open_claw (merge agents).
     */
    fun markRemovedKeysNull(
        original: Map<String, JsonElement>,
        current: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val result = LinkedHashMap(current)
        for ((key, origChild) in original) {
            val curChild = result[key]
            if (curChild == null) {
                result[key] = JsonNull
            } else if (origChild is JsonObject && curChild is JsonObject) {
                result[key] = JsonObject(markRemovedKeysNull(origChild, curChild))
            }
        }
        return result
    }

    /** Parse + reserialize (drops to "" when empty); invalid input returns the trimmed original. */
    fun normalize(text: String): String {
        val parsed = parse(text)
        if (parsed.error != null) return text.trim()
        if (parsed.config.isEmpty()) return ""
        return serialize(parsed.config)
    }

    /** Read `config.env` (the nested env object) as a trimmed string map. */
    fun envFromConfig(config: Map<String, JsonElement>): Map<String, String> {
        val raw = config["env"] as? JsonObject ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            val s = value.asStringOrNull() ?: continue
            val k = key.trim()
            val v = s.trim()
            if (k.isEmpty() || v.isEmpty()) continue
            map[k] = v
        }
        return map
    }

    fun pickFirstString(source: Map<String, JsonElement>, keys: List<String>): String? {
        for (key in keys) {
            val t = source[key]?.asStringOrNull()?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return null
    }
}

/** The string content of a JSON string primitive, or null for non-strings. */
internal fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// endregion

// region Env-text primitives

object EnvText {
    fun toText(env: Map<String, String>): String =
        env.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    fun parse(envText: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (rawLine in envText.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty()) continue
            map[key] = value
        }
        return map
    }

    /** Apply a patch: a trimmed-empty (or null) value deletes the key. Mirrors `patchEnvText`. */
    fun patch(envText: String, patch: Map<String, String?>): String {
        val map = LinkedHashMap(parse(envText))
        for ((key, value) in patch) {
            val trimmed = (value ?: "").trim()
            if (trimmed.isEmpty()) map.remove(key) else map[key] = trimmed
        }
        return toText(map)
    }

    fun find(env: Map<String, String>, keys: List<String>): String {
        for (key in keys) {
            val t = env[key]?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return ""
    }
}

// endregion

// region AgentDraft (the editable per-type form state; mirrors web `AgentDraft`)

data class AgentDraft(
    val enabled: Boolean = true,
    /** Raw flat env (`acp_update_agent_env`) — kept in lockstep with structured fields. */
    val envText: String = "",
    /** Raw config.json (`acp_update_agent_config`) — source of truth for structured fields. */
    val configText: String = "",
    val modelProviderId: Int? = null,

    // Shared important
    val apiBaseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",

    // Claude
    val claudeAuthMode: ClaudeAuthMode = ClaudeAuthMode.OFFICIAL_SUBSCRIPTION,
    val claudeMainModel: String = "",
    val claudeReasoningModel: String = "",
    val claudeDefaultHaikuModel: String = "",
    val claudeDefaultSonnetModel: String = "",
    val claudeDefaultOpusModel: String = "",
    val claudeEffortLevel: ClaudeEffortLevel = ClaudeEffortLevel.DEFAULT,

    // Codex
    val codexAuthMode: CodexAuthMode = CodexAuthMode.API_KEY,
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    val codexSupportsWebsockets: Boolean = false,
    val codexSkills: Boolean = false,
    val codexServiceTierFast: Boolean = false,
    val codexAuthJsonText: String = "",
    val codexConfigTomlText: String = "",

    // Gemini
    val geminiAuthMode: GeminiAuthMode = GeminiAuthMode.LOGIN_GOOGLE,
    val geminiApiKey: String = "",
    val googleApiKey: String = "",
    val googleCloudProject: String = "",
    val googleCloudLocation: String = "",
    val googleApplicationCredentials: String = "",

    // OpenClaw
    val openClawGatewayUrl: String = "",
    val openClawGatewayToken: String = "",
    val openClawSessionKey: String = "",

    // CodeBuddy (env-only, like OpenClaw). `apiKey` above is reused for CODEBUDDY_API_KEY.
    val codeBuddyEnvironment: CodeBuddyEnvironment = CodeBuddyEnvironment.OVERSEAS,
    val codeBuddyBaseUrl: String = "",

    // Grok. `apiKey` above is reused for XAI_API_KEY (env). The two dropdowns (`""` =
    // "use default") + the raw config.toml escape hatch persist via
    // `acp_update_agent_config` (grokStructured / grokConfigToml) — the server merges
    // the controls into ~/.grok/config.toml — so they are NOT baked into env/config here.
    val grokPermissionMode: String = "",
    val grokReasoningEffort: String = "",
    val grokConfigTomlText: String = "",

    // Cursor. `apiKey` above is reused for CURSOR_API_KEY. The auth method, model and
    // Run Everything knob ride the env; the sandbox mode + permission rules are a
    // structured patch on cli-config.json, and the raw file is the escape hatch —
    // both persisted via `acp_update_agent_config` (cursorStructured /
    // cursorCliConfigJson).
    val cursorAuthMode: CursorAuthMethod = CursorAuthMethod.SUBSCRIPTION,
    val cursorModel: String = "",
    val cursorForce: Boolean = true,
    val cursorSandboxMode: String = "",
    val cursorAllowRules: List<String> = emptyList(),
    val cursorDenyRules: List<String> = emptyList(),
    val cursorCliConfigText: String = "",

    // Cline
    val clineProvider: String = "anthropic",
    val clineApiKey: String = "",
    val clineModel: String = "",
    val clineBaseUrl: String = "",

    // OpenCode / Hermes
    val openCodeMainModel: String = "",
    val openCodeSmallModel: String = "",
    val openCodeAuthJsonText: String = "",
    val hermesProvider: String = "openrouter",
) {
    /**
     * Re-bake the structured fields into `configText`/`envText` (and codex
     * toml/auth) so the raw editors + the save payload stay in sync. Call after any
     * structured field edit. Idempotent.
     */
    fun reapplied(agentType: AgentType): AgentDraft {
        val applied = AgentConfig.reapply(agentType, this)
        return copy(
            configText = applied.configText,
            envText = applied.envText,
            codexConfigTomlText = applied.codexConfigTomlText,
            codexAuthJsonText = applied.codexAuthJsonText,
        )
    }

    companion object {
        /** Port of `buildAgentDraft`: reconstruct the editable form from a stored agent. */
        fun fromAgent(agent: AcpAgentInfo): AgentDraft {
            val configText = agent.configJson?.takeIf { it.trim().isNotEmpty() } ?: ""
            val env = agent.env

            // codex config.toml always carries disable_response_storage=true (read-side
            // injection keeps a round-trip stable — see AgentToml).
            val codexToml = if (agent.agentType == AgentType.CODEX) {
                AgentToml.setRootBool(agent.codexConfigToml ?: "", "disable_response_storage", true)
            } else {
                agent.codexConfigToml ?: ""
            }
            val codexAuth = agent.codexAuthJson ?: ""

            val important = AgentConfig.extractImportant(agent.agentType, env, configText)
            val gemini = AgentConfig.extractGemini(env, configText)
            val openClaw = AgentConfig.extractOpenClaw(env, configText)
            val cline = AgentConfig.extractCline(configText)
            val codex = AgentToml.extractCodex(codexAuth, codexToml)
            val openCode = AgentConfig.extractOpenCode(configText)

            val apiBaseUrl = when (agent.agentType) {
                AgentType.CODEX -> codex.apiBaseUrl
                AgentType.GEMINI -> gemini.apiBaseUrl
                else -> important.apiBaseUrl
            }
            val apiKey = when (agent.agentType) {
                AgentType.CODEX -> codex.apiKey ?: ""
                AgentType.GEMINI -> gemini.geminiApiKey.ifEmpty { gemini.googleApiKey }
                else -> important.apiKey
            }
            val model = when (agent.agentType) {
                AgentType.CODEX -> codex.model
                AgentType.GEMINI -> gemini.model
                else -> important.model
            }

            var draft = AgentDraft(
                enabled = agent.enabled,
                configText = configText,
                envText = EnvText.toText(env),
                modelProviderId = agent.modelProviderId,
                openCodeAuthJsonText = agent.opencodeAuthJson ?: "",
                codexConfigTomlText = codexToml,
                codexAuthJsonText = codexAuth,
                apiBaseUrl = apiBaseUrl,
                apiKey = apiKey,
                model = model,
            )

            when (agent.agentType) {
                AgentType.CLAUDE_CODE -> draft = draft.copy(
                    claudeAuthMode = when {
                        agent.modelProviderId != null -> ClaudeAuthMode.MODEL_PROVIDER
                        important.apiBaseUrl.isNotEmpty() || important.apiKey.isNotEmpty() -> ClaudeAuthMode.CUSTOM
                        else -> ClaudeAuthMode.OFFICIAL_SUBSCRIPTION
                    },
                    claudeMainModel = important.claudeMainModel,
                    claudeReasoningModel = important.claudeReasoningModel,
                    claudeDefaultHaikuModel = important.claudeDefaultHaikuModel,
                    claudeDefaultSonnetModel = important.claudeDefaultSonnetModel,
                    claudeDefaultOpusModel = important.claudeDefaultOpusModel,
                    claudeEffortLevel = important.claudeEffortLevel,
                )
                AgentType.CODEX -> draft = draft.copy(
                    codexAuthMode = if (agent.modelProviderId != null) CodexAuthMode.MODEL_PROVIDER
                    else AgentToml.inferCodexAuthMode(codexAuth),
                    codexReasoningEffort = codex.reasoningEffort,
                    codexSupportsWebsockets = codex.supportsWebsockets,
                    codexSkills = codex.skills,
                    codexServiceTierFast = codex.serviceTierFast,
                )
                AgentType.GEMINI -> draft = draft.copy(
                    geminiAuthMode = if (agent.modelProviderId != null) GeminiAuthMode.MODEL_PROVIDER else gemini.authMode,
                    geminiApiKey = gemini.geminiApiKey,
                    googleApiKey = gemini.googleApiKey,
                    googleCloudProject = gemini.googleCloudProject,
                    googleCloudLocation = gemini.googleCloudLocation,
                    googleApplicationCredentials = gemini.googleApplicationCredentials,
                )
                AgentType.OPEN_CLAW -> draft = draft.copy(
                    openClawGatewayUrl = openClaw.gatewayUrl,
                    openClawGatewayToken = openClaw.gatewayToken,
                    openClawSessionKey = openClaw.sessionKey,
                )
                AgentType.CLINE -> draft = draft.copy(
                    clineProvider = cline.provider,
                    clineApiKey = cline.apiKey,
                    clineModel = cline.model,
                    clineBaseUrl = cline.baseUrl,
                )
                AgentType.OPEN_CODE -> draft = draft.copy(
                    openCodeMainModel = openCode.mainModel,
                    openCodeSmallModel = openCode.smallModel,
                )
                AgentType.HERMES -> {
                    val h = AgentConfig.parseHermes(configText)
                    draft = draft.copy(
                        hermesProvider = h.provider,
                        apiBaseUrl = h.baseUrl,
                        apiKey = h.apiKey,
                        model = h.model,
                    )
                }
                // CodeBuddy: API key + region/base-url derive from CODEBUDDY_* env (the
                // shared `apiKey` set above reads the wrong keys for this agent — override).
                AgentType.CODE_BUDDY -> {
                    val cb = AgentConfig.extractCodeBuddy(env)
                    draft = draft.copy(
                        apiKey = cb.apiKey,
                        codeBuddyEnvironment = cb.environment,
                        codeBuddyBaseUrl = cb.baseUrl,
                    )
                }
                // Grok: `apiKey` (XAI_API_KEY) is already seeded via the shared
                // `important` path above (its key list is Grok-specific). Seed the two
                // structured controls + raw config.toml from the server's parsed
                // projection (nil → "" = "use default").
                AgentType.GROK -> draft = draft.copy(
                    grokPermissionMode = agent.grokSettings?.permissionMode ?: "",
                    grokReasoningEffort = agent.grokSettings?.defaultReasoningEffort ?: "",
                    grokConfigTomlText = agent.grokConfigToml ?: "",
                )
                // Cursor: `apiKey` (CURSOR_API_KEY) is already seeded via the shared
                // `important` path above. The rest is read straight off the env plus the
                // backend's parsed cli-config.json projection.
                AgentType.CURSOR -> draft = draft.copy(
                    cursorAuthMode = CursorConfig.inferMode(env),
                    cursorModel = env[CursorConfig.MODEL]?.trim().orEmpty(),
                    // A fresh Cursor agent defaults to Run Everything; only an explicitly
                    // written knob (incl. "0" = "ask before running") overrides that.
                    cursorForce = CursorConfig.forceOrDefault(env),
                    cursorSandboxMode = agent.cursorSettings?.sandboxMode ?: "",
                    cursorAllowRules = agent.cursorSettings?.permissionsAllow ?: emptyList(),
                    cursorDenyRules = agent.cursorSettings?.permissionsDeny ?: emptyList(),
                    cursorCliConfigText = agent.cursorCliConfigJson ?: "",
                )
                // Kimi & Pi project their own state from `configJson` inside their
                // self-contained panels.
                AgentType.KIMI_CODE, AgentType.PI -> Unit
                // A custom (user-registered) or unrecognised agent has no hand-written
                // field mapping — it keeps the generic config.json + env draft built above.
                else -> Unit
            }
            return draft
        }
    }
}

// endregion

// region Codec (extract = read; reapply = write)

object AgentConfig {

    /**
     * Agents whose config.json is merged on save (removed keys → null so the
     * backend deletes them). Everyone else replaces wholesale.
     */
    fun usesMerge(agent: AgentType): Boolean =
        agent == AgentType.CLAUDE_CODE || agent == AgentType.GEMINI || agent == AgentType.OPEN_CLAW

    /**
     * True when the agent is in model_provider auth mode but no provider is
     * selected — the save must be blocked.
     */
    fun missingModelProvider(agent: AgentType, draft: AgentDraft): Boolean {
        if (draft.modelProviderId != null) return false
        return when (agent) {
            AgentType.CLAUDE_CODE -> draft.claudeAuthMode == ClaudeAuthMode.MODEL_PROVIDER
            AgentType.CODEX -> draft.codexAuthMode == CodexAuthMode.MODEL_PROVIDER
            AgentType.GEMINI -> draft.geminiAuthMode == GeminiAuthMode.MODEL_PROVIDER
            else -> false
        }
    }

    /** Fill a default npm package for any open_code provider missing one (applied before persist). */
    fun ensureOpenCodeProviderNpm(configText: String): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        val providers = config["provider"] as? JsonObject ?: return configText
        val next = LinkedHashMap<String, JsonElement>()
        var changed = false
        for ((id, raw) in providers) {
            val p = raw as? JsonObject
            if (p == null) { next[id] = raw; continue }
            val npm = p["npm"]?.asStringOrNull()?.trim().orEmpty()
            if (npm.isEmpty()) {
                next[id] = JsonObject(LinkedHashMap(p).apply { put("npm", JsonPrimitive(openCodeNpmOptions[0])) })
                changed = true
            } else {
                next[id] = raw
            }
        }
        if (!changed) return configText
        config["provider"] = JsonObject(next)
        return JsonConfig.serialize(config)
    }

    // ---- Extract (read structured fields from stored config/env) ----

    data class ImportantValues(
        val apiBaseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val claudeMainModel: String = "",
        val claudeReasoningModel: String = "",
        val claudeDefaultHaikuModel: String = "",
        val claudeDefaultSonnetModel: String = "",
        val claudeDefaultOpusModel: String = "",
        val claudeEffortLevel: ClaudeEffortLevel = ClaudeEffortLevel.DEFAULT,
    )

    fun extractImportant(agent: AgentType, env: Map<String, String>, configText: String): ImportantValues {
        val config = JsonConfig.parse(configText).config
        val keys = AgentEnvKeys.important(agent)
        val merged = env + JsonConfig.envFromConfig(config)

        val apiBaseUrl = JsonConfig.pickFirstString(config, listOf("apiBaseUrl", "api_base_url"))
            ?: EnvText.find(merged, keys.apiBaseUrl)
        val apiKey = JsonConfig.pickFirstString(config, listOf("apiKey", "api_key"))
            ?: EnvText.find(merged, keys.apiKey)
        val model = JsonConfig.pickFirstString(config, listOf("model", "model_name"))
            ?: EnvText.find(merged, keys.model)

        if (agent != AgentType.CLAUDE_CODE) {
            return ImportantValues(apiBaseUrl = apiBaseUrl, apiKey = apiKey, model = model)
        }
        return ImportantValues(
            apiBaseUrl = apiBaseUrl,
            apiKey = apiKey,
            model = model,
            claudeMainModel = EnvText.find(merged, listOf(AgentEnvKeys.CLAUDE_MAIN_MODEL)),
            claudeReasoningModel = EnvText.find(merged, listOf(AgentEnvKeys.CLAUDE_REASONING_MODEL)),
            claudeDefaultHaikuModel = EnvText.find(merged, listOf(AgentEnvKeys.CLAUDE_DEFAULT_HAIKU_MODEL)),
            claudeDefaultSonnetModel = EnvText.find(merged, listOf(AgentEnvKeys.CLAUDE_DEFAULT_SONNET_MODEL)),
            claudeDefaultOpusModel = EnvText.find(merged, listOf(AgentEnvKeys.CLAUDE_DEFAULT_OPUS_MODEL)),
            claudeEffortLevel = ClaudeEffortLevel.fromWire(config[AgentEnvKeys.CLAUDE_EFFORT_CONFIG_KEY]?.asStringOrNull()),
        )
    }

    data class GeminiValues(
        val authMode: GeminiAuthMode = GeminiAuthMode.LOGIN_GOOGLE,
        val apiBaseUrl: String = "",
        val geminiApiKey: String = "",
        val googleApiKey: String = "",
        val googleCloudProject: String = "",
        val googleCloudLocation: String = "",
        val googleApplicationCredentials: String = "",
        val model: String = "",
    )

    fun extractGemini(env: Map<String, String>, configText: String): GeminiValues {
        val config = JsonConfig.parse(configText).config
        val merged = env + JsonConfig.envFromConfig(config)
        val k = AgentEnvKeys.Gemini
        val v = GeminiValues(
            apiBaseUrl = EnvText.find(merged, listOf(k.BASE_URL, k.LEGACY_BASE_URL, "API_BASE_URL")),
            geminiApiKey = EnvText.find(merged, listOf(k.GEMINI_API_KEY, k.LEGACY_GEMINI_API_KEY)),
            googleApiKey = EnvText.find(merged, listOf(k.GOOGLE_API_KEY)),
            googleCloudProject = EnvText.find(merged, listOf(k.CLOUD_PROJECT, k.CLOUD_PROJECT_LEGACY)),
            googleCloudLocation = EnvText.find(merged, listOf(k.CLOUD_LOCATION)),
            googleApplicationCredentials = EnvText.find(merged, listOf(k.APPLICATION_CREDENTIALS)),
            model = EnvText.find(merged, listOf(k.MODEL, "MODEL")),
        )
        return v.copy(authMode = inferGeminiAuthMode(v))
    }

    fun inferGeminiAuthMode(v: GeminiValues): GeminiAuthMode = when {
        v.apiBaseUrl.trim().isNotEmpty() -> GeminiAuthMode.CUSTOM
        v.geminiApiKey.trim().isNotEmpty() -> GeminiAuthMode.GEMINI_API_KEY
        v.googleApiKey.trim().isNotEmpty() -> GeminiAuthMode.VERTEX_API_KEY
        v.googleApplicationCredentials.trim().isNotEmpty() -> GeminiAuthMode.VERTEX_SERVICE_ACCOUNT
        v.googleCloudProject.trim().isNotEmpty() || v.googleCloudLocation.trim().isNotEmpty() -> GeminiAuthMode.VERTEX_ADC
        else -> GeminiAuthMode.LOGIN_GOOGLE
    }

    /**
     * Switch a Gemini draft to [mode], preserving the credential fields the target
     * mode still uses and clearing only the incompatible ones (faithful port of the
     * iOS `GeminiConfigSection.setAuth`). Clears the provider link unless switching
     * into model-provider mode.
     */
    fun applyGeminiAuthMode(draft: AgentDraft, mode: GeminiAuthMode): AgentDraft {
        var b = draft.apiBaseUrl
        var k = draft.geminiApiKey
        var g = draft.googleApiKey
        var proj = draft.googleCloudProject
        var loc = draft.googleCloudLocation
        var cred = draft.googleApplicationCredentials
        when (mode) {
            GeminiAuthMode.LOGIN_GOOGLE -> { b = ""; k = ""; g = ""; proj = ""; loc = ""; cred = "" }
            GeminiAuthMode.CUSTOM -> { g = ""; proj = ""; loc = ""; cred = "" }
            GeminiAuthMode.GEMINI_API_KEY -> { b = ""; g = ""; proj = ""; loc = ""; cred = "" }
            GeminiAuthMode.VERTEX_API_KEY -> { b = ""; k = ""; cred = "" }
            GeminiAuthMode.VERTEX_SERVICE_ACCOUNT -> { b = ""; k = ""; g = "" }
            GeminiAuthMode.VERTEX_ADC -> { b = ""; k = ""; g = ""; cred = "" }
            GeminiAuthMode.MODEL_PROVIDER -> { proj = ""; loc = ""; cred = "" }
        }
        return draft.copy(
            geminiAuthMode = mode,
            apiBaseUrl = b, geminiApiKey = k, googleApiKey = g,
            googleCloudProject = proj, googleCloudLocation = loc, googleApplicationCredentials = cred,
            modelProviderId = if (mode == GeminiAuthMode.MODEL_PROVIDER) draft.modelProviderId else null,
        )
    }

    data class OpenClawValues(val gatewayUrl: String = "", val gatewayToken: String = "", val sessionKey: String = "")

    fun extractOpenClaw(env: Map<String, String>, configText: String): OpenClawValues {
        val config = JsonConfig.parse(configText).config
        val merged = env + JsonConfig.envFromConfig(config)
        val k = AgentEnvKeys.OpenClaw
        return OpenClawValues(
            gatewayUrl = EnvText.find(merged, listOf(k.GATEWAY_URL)),
            gatewayToken = EnvText.find(merged, listOf(k.GATEWAY_TOKEN)),
            sessionKey = EnvText.find(merged, listOf(k.SESSION_KEY)),
        )
    }

    data class CodeBuddyValues(
        val apiKey: String = "",
        val environment: CodeBuddyEnvironment = CodeBuddyEnvironment.OVERSEAS,
        val baseUrl: String = "",
    )

    /**
     * Read CodeBuddy's structured fields from the flat env. A non-empty
     * `CODEBUDDY_BASE_URL` implies self-hosted; otherwise the region comes from
     * `CODEBUDDY_INTERNET_ENVIRONMENT` (`internal`/`ioa`), defaulting to overseas.
     * Mirrors the web `codeBuddyEnvironmentFromEnv`.
     */
    fun extractCodeBuddy(env: Map<String, String>): CodeBuddyValues {
        val k = AgentEnvKeys.CodeBuddy
        val apiKey = EnvText.find(env, listOf(k.API_KEY))
        val baseUrl = EnvText.find(env, listOf(k.BASE_URL))
        val environment = if (baseUrl.trim().isNotEmpty()) {
            CodeBuddyEnvironment.SELF_HOSTED
        } else {
            // self_hosted is BASE_URL-derived only — never taken from the region key.
            CodeBuddyEnvironment.fromWire(EnvText.find(env, listOf(k.ENVIRONMENT)))
                ?.takeUnless { it == CodeBuddyEnvironment.SELF_HOSTED }
                ?: CodeBuddyEnvironment.OVERSEAS
        }
        return CodeBuddyValues(apiKey = apiKey, environment = environment, baseUrl = baseUrl)
    }

    data class ClineValues(
        val provider: String = "anthropic",
        val apiKey: String = "",
        val model: String = "",
        val baseUrl: String = "",
    )

    fun extractCline(configText: String): ClineValues {
        val config = JsonConfig.parse(configText).config
        return ClineValues(
            provider = config["apiProvider"]?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: "anthropic",
            apiKey = config["apiKey"]?.asStringOrNull() ?: "",
            model = config["model"]?.asStringOrNull() ?: "",
            baseUrl = config["apiBaseUrl"]?.asStringOrNull() ?: "",
        )
    }

    data class OpenCodeValues(val mainModel: String = "", val smallModel: String = "")

    fun extractOpenCode(configText: String): OpenCodeValues {
        val config = JsonConfig.parse(configText).config
        return OpenCodeValues(
            mainModel = config["model"]?.asStringOrNull() ?: "",
            smallModel = config["small_model"]?.asStringOrNull() ?: "",
        )
    }

    // ---- Reapply (write structured fields back into config/env strings) ----

    /** The recomputed raw payloads for an agent type after a structured edit. */
    data class Applied(
        val configText: String,
        val envText: String,
        val codexConfigTomlText: String,
        val codexAuthJsonText: String,
    )

    /**
     * Recompute config/env (and codex toml/auth) from the draft's structured
     * fields, preserving unknown keys in the existing `configText`. In
     * model_provider mode the typed url/key are NOT written (the server resolves
     * the provider link from `modelProviderId`; the client only holds the
     * provider's MASKED key). Idempotent.
     */
    fun reapply(agent: AgentType, draft: AgentDraft): Applied {
        var configText = draft.configText
        var envText = draft.envText
        var codexConfigTomlText = draft.codexConfigTomlText
        var codexAuthJsonText = draft.codexAuthJsonText
        val linked = draft.modelProviderId != null

        when (agent) {
            AgentType.CLAUDE_CODE -> {
                val d = if (linked) draft.copy(
                    apiBaseUrl = "", apiKey = "",
                    claudeMainModel = "", claudeReasoningModel = "",
                    claudeDefaultHaikuModel = "", claudeDefaultSonnetModel = "", claudeDefaultOpusModel = "",
                ) else draft
                configText = applyClaudeConfig(draft.configText, d)
                envText = applyClaudeEnv(draft.envText, d)
                configText = applyClaudeEffort(configText, draft.claudeEffortLevel)
            }
            AgentType.GEMINI -> {
                val d = if (linked) draft.copy(
                    apiBaseUrl = "", geminiApiKey = "", googleApiKey = "",
                    googleCloudProject = "", googleCloudLocation = "", googleApplicationCredentials = "",
                    model = "",
                ) else draft
                configText = applyGeminiConfig(draft.configText, d)
                envText = applyGeminiEnv(draft.envText, d)
            }
            AgentType.OPEN_CLAW -> {
                envText = applyOpenClawEnv(draft.envText, draft)
                // Flat env is canonical for OpenClaw, but extract merges config.env (which
                // wins). Strip the gateway keys from config.env so a stale value there can't
                // shadow the edited flat-env value after save/reload.
                val k = AgentEnvKeys.OpenClaw
                configText = stripKeysFromConfigEnv(draft.configText, listOf(k.GATEWAY_URL, k.GATEWAY_TOKEN, k.SESSION_KEY))
            }
            AgentType.CLINE -> configText = buildClineConfig(draft)
            AgentType.OPEN_CODE -> configText = applyOpenCodeConfig(draft.configText, draft)
            AgentType.CODEX -> {
                val d = if (linked) draft.copy(apiBaseUrl = "", model = "") else draft
                codexConfigTomlText = AgentToml.patchCodex(draft.codexConfigTomlText, d)
                codexAuthJsonText = when {
                    linked -> AgentToml.patchCodexAuth(draft.codexAuthJsonText, "")
                    draft.codexAuthMode == CodexAuthMode.API_KEY ->
                        AgentToml.patchCodexAuth(draft.codexAuthJsonText, draft.apiKey)
                    else -> codexAuthJsonText
                }
            }
            AgentType.HERMES -> Unit // saved via the dedicated hermes endpoint
            AgentType.CODE_BUDDY -> envText = applyCodeBuddyEnv(draft.envText, draft)
            // Grok: only XAI_API_KEY rides the env; the structured controls + raw
            // config.toml are persisted via acp_update_agent_config (server-merged),
            // not baked into env/config here.
            AgentType.GROK -> envText = applyGrokEnv(draft.envText, draft)
            // Cursor: auth method / credential / model / Run Everything ride the env;
            // the sandbox + permission rules are persisted via acp_update_agent_config.
            AgentType.CURSOR -> envText = CursorConfig.applyEnv(
                draft.envText,
                mode = draft.cursorAuthMode,
                apiKey = draft.apiKey,
                model = draft.cursorModel,
                force = draft.cursorForce,
            )
            // Kimi & Pi are self-contained (saved via acp_update_kimi_code_config /
            // acp_update_pi_config), not the shared draft.
            AgentType.KIMI_CODE, AgentType.PI -> Unit
            // A custom (user-registered) or unrecognised agent has no structured fields
            // to re-apply — its config.json and env are edited verbatim.
            else -> Unit
        }
        return Applied(configText, envText, codexConfigTomlText, codexAuthJsonText)
    }

    // Claude: url/key + 5 models → config.env; effortLevel → config root.
    private fun applyClaudeConfig(configText: String, d: AgentDraft): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        val env = LinkedHashMap<String, JsonElement>((config["env"] as? JsonObject) ?: emptyMap())
        fun assignEnv(key: String, value: String) {
            val t = value.trim()
            if (t.isEmpty()) env.remove(key) else env[key] = JsonPrimitive(t)
        }
        // Legacy root cleanup — Claude values belong under config.env.
        for (key in listOf("apiBaseUrl", "apiKey", "api_base_url", "api_key", "model", "model_name")) config.remove(key)
        assignEnv("ANTHROPIC_BASE_URL", d.apiBaseUrl)
        assignEnv("ANTHROPIC_AUTH_TOKEN", d.apiKey)
        assignEnv("ANTHROPIC_MODEL", d.claudeMainModel)
        assignEnv("ANTHROPIC_REASONING_MODEL", d.claudeReasoningModel)
        assignEnv("ANTHROPIC_DEFAULT_HAIKU_MODEL", d.claudeDefaultHaikuModel)
        assignEnv("ANTHROPIC_DEFAULT_SONNET_MODEL", d.claudeDefaultSonnetModel)
        assignEnv("ANTHROPIC_DEFAULT_OPUS_MODEL", d.claudeDefaultOpusModel)
        if (env.isEmpty()) config.remove("env") else config["env"] = JsonObject(env)
        return JsonConfig.serialize(config)
    }

    private fun applyClaudeEnv(envText: String, d: AgentDraft): String = EnvText.patch(
        envText,
        mapOf(
            "ANTHROPIC_BASE_URL" to d.apiBaseUrl, "ANTHROPIC_AUTH_TOKEN" to d.apiKey,
            "ANTHROPIC_MODEL" to d.claudeMainModel, "ANTHROPIC_REASONING_MODEL" to d.claudeReasoningModel,
            "ANTHROPIC_DEFAULT_HAIKU_MODEL" to d.claudeDefaultHaikuModel,
            "ANTHROPIC_DEFAULT_SONNET_MODEL" to d.claudeDefaultSonnetModel,
            "ANTHROPIC_DEFAULT_OPUS_MODEL" to d.claudeDefaultOpusModel,
        ),
    )

    private fun applyClaudeEffort(configText: String, level: ClaudeEffortLevel): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        if (level == ClaudeEffortLevel.DEFAULT) config.remove(AgentEnvKeys.CLAUDE_EFFORT_CONFIG_KEY)
        else config[AgentEnvKeys.CLAUDE_EFFORT_CONFIG_KEY] = JsonPrimitive(level.wire)
        return JsonConfig.serialize(config)
    }

    /** Every Claude url/key env alias (not just the canonical pair). */
    val claudeCredentialAliasKeys = listOf(
        "ANTHROPIC_BASE_URL", "OPENAI_BASE_URL", "API_BASE_URL",
        "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
    )

    /**
     * Strip every Claude url/key alias from BOTH the flat env and `config.env` —
     * switching to the official subscription must not leave a stale alias (e.g.
     * `OPENAI_BASE_URL`) that would flip the mode back to "custom" on next load.
     */
    fun clearClaudeCredentialAliases(configText: String, envText: String): Pair<String, String> {
        val env = EnvText.patch(envText, claudeCredentialAliasKeys.associateWith { "" })
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        (config["env"] as? JsonObject)?.let { cfgEnv ->
            val next = LinkedHashMap(cfgEnv)
            for (key in claudeCredentialAliasKeys) next.remove(key)
            if (next.isEmpty()) config.remove("env") else config["env"] = JsonObject(next)
        }
        return JsonConfig.serialize(config) to env
    }

    // Gemini: write into config.env AND flat env (lockstep), clearing legacy keys.
    private fun applyGeminiConfig(configText: String, d: AgentDraft): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        val env = LinkedHashMap<String, JsonElement>((config["env"] as? JsonObject) ?: emptyMap())
        val k = AgentEnvKeys.Gemini
        fun assign(key: String, value: String) {
            val t = value.trim()
            if (t.isEmpty()) env.remove(key) else env[key] = JsonPrimitive(t)
        }
        config.remove("model")
        config.remove("model_name")
        assign(k.MODEL, d.model)
        assign(k.BASE_URL, d.apiBaseUrl); env.remove(k.LEGACY_BASE_URL)
        assign(k.GEMINI_API_KEY, d.geminiApiKey); env.remove(k.LEGACY_GEMINI_API_KEY)
        assign(k.GOOGLE_API_KEY, d.googleApiKey)
        assign(k.CLOUD_PROJECT, d.googleCloudProject); env.remove(k.CLOUD_PROJECT_LEGACY)
        assign(k.CLOUD_LOCATION, d.googleCloudLocation)
        assign(k.APPLICATION_CREDENTIALS, d.googleApplicationCredentials)
        if (env.isEmpty()) config.remove("env") else config["env"] = JsonObject(env)
        return JsonConfig.serialize(config)
    }

    private fun applyGeminiEnv(envText: String, d: AgentDraft): String {
        val k = AgentEnvKeys.Gemini
        return EnvText.patch(
            envText,
            mapOf(
                k.MODEL to d.model,
                k.BASE_URL to d.apiBaseUrl, k.LEGACY_BASE_URL to "",
                k.GEMINI_API_KEY to d.geminiApiKey, k.LEGACY_GEMINI_API_KEY to "",
                k.GOOGLE_API_KEY to d.googleApiKey,
                k.CLOUD_PROJECT to d.googleCloudProject, k.CLOUD_PROJECT_LEGACY to "",
                k.CLOUD_LOCATION to d.googleCloudLocation,
                k.APPLICATION_CREDENTIALS to d.googleApplicationCredentials,
            ),
        )
    }

    /** Remove [keys] from the nested `config.env` (pruning an emptied `env`), leaving the rest intact. */
    private fun stripKeysFromConfigEnv(configText: String, keys: List<String>): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        val env = config["env"] as? JsonObject ?: return configText
        val next = LinkedHashMap(env)
        var changed = false
        for (k in keys) if (next.remove(k) != null) changed = true
        if (!changed) return configText
        if (next.isEmpty()) config.remove("env") else config["env"] = JsonObject(next)
        return JsonConfig.serialize(config)
    }

    private fun applyOpenClawEnv(envText: String, d: AgentDraft): String {
        val k = AgentEnvKeys.OpenClaw
        return EnvText.patch(
            envText,
            mapOf(
                k.GATEWAY_URL to d.openClawGatewayUrl,
                k.GATEWAY_TOKEN to d.openClawGatewayToken,
                k.SESSION_KEY to d.openClawSessionKey,
            ),
        )
    }

    /**
     * CodeBuddy env write (mirrors the web `buildCodeBuddyEnv`): API key set/cleared,
     * then routed by environment — self-hosted writes a slash-stripped BASE_URL and
     * clears the region key; overseas clears both region and BASE_URL; internal/ioa
     * set the region and clear BASE_URL. `EnvText.patch` deletes any empty value.
     */
    private fun applyCodeBuddyEnv(envText: String, d: AgentDraft): String {
        val k = AgentEnvKeys.CodeBuddy
        val patch = linkedMapOf<String, String>(k.API_KEY to d.apiKey)
        when (d.codeBuddyEnvironment) {
            CodeBuddyEnvironment.SELF_HOSTED -> {
                patch[k.ENVIRONMENT] = ""
                patch[k.BASE_URL] = normalizeCodeBuddyBaseUrl(d.codeBuddyBaseUrl)
            }
            CodeBuddyEnvironment.OVERSEAS -> {
                patch[k.ENVIRONMENT] = ""
                patch[k.BASE_URL] = ""
            }
            CodeBuddyEnvironment.INTERNAL, CodeBuddyEnvironment.IOA -> {
                patch[k.ENVIRONMENT] = d.codeBuddyEnvironment.wire
                patch[k.BASE_URL] = ""
            }
        }
        return EnvText.patch(envText, patch)
    }

    /**
     * Grok env write (mirrors iOS `applyGrokEnv`): only XAI_API_KEY is set/cleared.
     * Base URL + model have working env overrides but aren't surfaced in the panel,
     * so they round-trip untouched. `EnvText.patch` deletes the key when empty.
     */
    private fun applyGrokEnv(envText: String, d: AgentDraft): String =
        EnvText.patch(envText, linkedMapOf(AgentEnvKeys.Grok.API_KEY to d.apiKey))

    /** Trim + strip trailing slashes (matches the web's `.replace(/\/+$/, "")`). */
    fun normalizeCodeBuddyBaseUrl(value: String): String =
        value.trim().trimEnd('/')

    /** http(s)-URL check for the self-hosted endpoint (web `isValidCodeBuddyBaseUrl`). */
    fun isValidCodeBuddyBaseUrl(value: String): Boolean {
        val t = value.trim()
        if (t.isEmpty()) return false
        return try {
            val uri = java.net.URI(t)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** Block a self-hosted CodeBuddy save whose Base URL isn't a valid http(s) URL. */
    fun missingCodeBuddyBaseUrl(agent: AgentType, draft: AgentDraft): Boolean =
        agent == AgentType.CODE_BUDDY &&
            draft.codeBuddyEnvironment == CodeBuddyEnvironment.SELF_HOSTED &&
            !isValidCodeBuddyBaseUrl(draft.codeBuddyBaseUrl)

    /**
     * Cursor in API-key mode with nothing typed: saving would write an auth mode that
     * has no credential to go with it, so the launch would silently fall back to the
     * browser login. Mirrors the web's `cursor.customApiKeyRequired` guard.
     */
    fun missingCursorApiKey(agent: AgentType, draft: AgentDraft): Boolean =
        agent == AgentType.CURSOR &&
            draft.cursorAuthMode == CursorAuthMethod.CUSTOM &&
            draft.apiKey.trim().isEmpty()

    private fun buildClineConfig(d: AgentDraft): String {
        val config = LinkedHashMap<String, JsonElement>()
        config["apiProvider"] = JsonPrimitive(d.clineProvider)
        d.clineApiKey.trim().takeIf { it.isNotEmpty() }?.let { config["apiKey"] = JsonPrimitive(it) }
        d.clineModel.trim().takeIf { it.isNotEmpty() }?.let { config["model"] = JsonPrimitive(it) }
        d.clineBaseUrl.trim().takeIf { it.isNotEmpty() }?.let { config["apiBaseUrl"] = JsonPrimitive(it) }
        return JsonConfig.serialize(config)
    }

    // OpenCode: main/small model live at the config root; provider editing is left
    // to the native (raw JSON) editor.
    private fun applyOpenCodeConfig(configText: String, d: AgentDraft): String {
        val config = LinkedHashMap(JsonConfig.parse(configText).config)
        fun assign(key: String, value: String) {
            val t = value.trim()
            if (t.isEmpty()) config.remove(key) else config[key] = JsonPrimitive(t)
        }
        assign("model", d.openCodeMainModel)
        assign("small_model", d.openCodeSmallModel)
        return JsonConfig.serialize(config)
    }

    // ---- Hermes (projection carried in config_json; saved via a dedicated endpoint) ----

    data class HermesValues(
        val provider: String = "openrouter",
        val model: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
    )

    /** Parse the hermes projection carried in `config_json` (camelCase keys). */
    fun parseHermes(configText: String): HermesValues {
        val c = JsonConfig.parse(configText).config
        return HermesValues(
            provider = c["provider"]?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: "openrouter",
            model = c["model"]?.asStringOrNull() ?: "",
            baseUrl = c["baseUrl"]?.asStringOrNull() ?: "",
            apiKey = c["apiKey"]?.asStringOrNull() ?: "",
        )
    }
}

// endregion

// region Hermes provider catalog

enum class HermesProviderKind { API_KEY, OAUTH, AWS }

data class HermesProviderOption(
    val id: String,
    val label: String,
    val needsBaseUrl: Boolean,
    val kind: HermesProviderKind,
)

/** Verbatim from the web `HERMES_PROVIDERS` (types.ts). */
val hermesProviders: List<HermesProviderOption> = listOf(
    // API key
    HermesProviderOption("openrouter", "OpenRouter", false, HermesProviderKind.API_KEY),
    HermesProviderOption("openai-api", "OpenAI / Compatible", true, HermesProviderKind.API_KEY),
    HermesProviderOption("custom", "Custom (OpenAI-compatible)", true, HermesProviderKind.API_KEY),
    HermesProviderOption("anthropic", "Anthropic", false, HermesProviderKind.API_KEY),
    HermesProviderOption("gemini", "Google AI Studio", false, HermesProviderKind.API_KEY),
    HermesProviderOption("deepseek", "DeepSeek", false, HermesProviderKind.API_KEY),
    HermesProviderOption("xai", "xAI Grok", false, HermesProviderKind.API_KEY),
    HermesProviderOption("zai", "Z.AI / GLM", false, HermesProviderKind.API_KEY),
    HermesProviderOption("minimax", "MiniMax", false, HermesProviderKind.API_KEY),
    HermesProviderOption("minimax-cn", "MiniMax (China)", false, HermesProviderKind.API_KEY),
    HermesProviderOption("kimi-coding", "Kimi / Moonshot", false, HermesProviderKind.API_KEY),
    HermesProviderOption("kimi-coding-cn", "Kimi / Moonshot (China)", false, HermesProviderKind.API_KEY),
    HermesProviderOption("nvidia", "NVIDIA NIM", false, HermesProviderKind.API_KEY),
    HermesProviderOption("alibaba", "Qwen (DashScope)", false, HermesProviderKind.API_KEY),
    HermesProviderOption("alibaba-coding-plan", "Alibaba Coding Plan", false, HermesProviderKind.API_KEY),
    HermesProviderOption("copilot", "GitHub Copilot", false, HermesProviderKind.API_KEY),
    HermesProviderOption("lmstudio", "LM Studio", true, HermesProviderKind.API_KEY),
    HermesProviderOption("azure-foundry", "Azure Foundry", true, HermesProviderKind.API_KEY),
    HermesProviderOption("stepfun", "StepFun", false, HermesProviderKind.API_KEY),
    HermesProviderOption("arcee", "Arcee AI", false, HermesProviderKind.API_KEY),
    HermesProviderOption("gmi", "GMI Cloud", false, HermesProviderKind.API_KEY),
    HermesProviderOption("huggingface", "Hugging Face", false, HermesProviderKind.API_KEY),
    HermesProviderOption("kilocode", "Kilo Code", false, HermesProviderKind.API_KEY),
    HermesProviderOption("opencode-zen", "OpenCode Zen", false, HermesProviderKind.API_KEY),
    HermesProviderOption("opencode-go", "OpenCode Go", false, HermesProviderKind.API_KEY),
    HermesProviderOption("xiaomi", "Xiaomi MiMo", false, HermesProviderKind.API_KEY),
    HermesProviderOption("tencent-tokenhub", "Tencent TokenHub", false, HermesProviderKind.API_KEY),
    HermesProviderOption("ollama-cloud", "Ollama Cloud", false, HermesProviderKind.API_KEY),
    HermesProviderOption("novita", "Novita AI", false, HermesProviderKind.API_KEY),
    // OAuth
    HermesProviderOption("nous", "Nous Portal", false, HermesProviderKind.OAUTH),
    HermesProviderOption("openai-codex", "OpenAI Codex", false, HermesProviderKind.OAUTH),
    HermesProviderOption("minimax-oauth", "MiniMax", false, HermesProviderKind.OAUTH),
    HermesProviderOption("xai-oauth", "xAI Grok", false, HermesProviderKind.OAUTH),
    HermesProviderOption("qwen-oauth", "Qwen", false, HermesProviderKind.OAUTH),
    HermesProviderOption("google-gemini-cli", "Gemini CLI", false, HermesProviderKind.OAUTH),
    HermesProviderOption("copilot-acp", "GitHub Copilot ACP", false, HermesProviderKind.OAUTH),
    // AWS
    HermesProviderOption("bedrock", "AWS Bedrock", false, HermesProviderKind.AWS),
)

fun hermesProvider(id: String): HermesProviderOption? = hermesProviders.firstOrNull { it.id == id }

// endregion
