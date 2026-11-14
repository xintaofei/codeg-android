package app.codeg.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A block of a prompt sent to an agent (Rust `PromptInputBlock`, tagged by
 * `type`). Encode-only. The request `Json` does NOT apply a naming strategy, so
 * the snake_case wire key (`mime_type`) is spelled explicitly. The default
 * class discriminator is `type`, matching the server's tag.
 */
@Serializable
sealed interface PromptInputBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : PromptInputBlock

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        @SerialName("mime_type") val mimeType: String,
        val uri: String? = null,
    ) : PromptInputBlock
}

// MARK: - Request bodies (camelCase keys; null fields are omitted by the request Json)

/** Empty body — encodes to `{}` for the many no-argument POST endpoints. */
@Serializable
object EmptyBody

@Serializable
data class ListConversationsBody(
    val folderIds: List<Int>? = null,
    val agentType: String? = null,
    val search: String? = null,
    val sortBy: String? = null,
    val status: String? = null,
    val includeChildren: Boolean? = null,
)

@Serializable
data class ConversationIdBody(val conversationId: Int)

/**
 * Which slice of a transcript to fetch. Mutually exclusive on the wire — the
 * server rejects both selectors at once — so this is a sealed choice rather than
 * two nullable ints.
 */
sealed interface TurnWindow {
    /**
     * The last [n] turns, with the start aligned BACKWARD to a user-round
     * boundary. Alignment only ever adds earlier turns, and walks back at most
     * 200 of them, so a single degenerate round cannot re-expand the window into
     * a full transfer.
     *
     * That cap means the window usually — but NOT always — opens on a user turn:
     * a round with more than 200 replies exhausts the walk and the window starts
     * mid-reply. Anything keyed on the first loaded turn has to tolerate that.
     */
    data class Tail(val n: Int) : TurnWindow

    /**
     * The exact slice starting at [from], NEVER round-aligned: the response
     * offset has to match the coordinate the caller refreshed against, or the
     * two prefix fingerprints describe different prefixes and cannot be
     * compared.
     */
    data class FromIndex(val from: Int) : TurnWindow

    companion object {
        /** Server-side clamp on [Tail.n] and on a page `limit`. */
        const val MAX_TURNS = 500
    }
}

/**
 * `get_folder_conversation`. Both selectors omitted asks for the whole
 * transcript, which is also what a pre-0.24.0 server always answers with.
 */
@Serializable
data class ConversationDetailBody(
    val conversationId: Int,
    val tailTurns: Int? = null,
    val fromIndex: Int? = null,
) {
    companion object {
        fun of(conversationId: Int, window: TurnWindow?): ConversationDetailBody = when (window) {
            null -> ConversationDetailBody(conversationId)
            is TurnWindow.Tail -> ConversationDetailBody(conversationId, tailTurns = window.n)
            is TurnWindow.FromIndex -> ConversationDetailBody(conversationId, fromIndex = window.from)
        }
    }
}

/** `get_folder_conversation_turns` — one page of history ending before [beforeIndex]. */
@Serializable
data class ConversationTurnsBody(
    val conversationId: Int,
    val beforeIndex: Int,
    val limit: Int,
)

@Serializable
data class CreateConversationBody(
    val folderId: Int,
    val agentType: AgentType,
    val title: String? = null,
)

/** Body for `acp_find_connection_for_conversation`. */
@Serializable
data class FindConnectionBody(
    val conversationId: Int,
    val sessionId: String? = null,
    val agentType: AgentType,
)

@Serializable
data class ConnectBody(
    val agentType: AgentType,
    val workingDir: String? = null,
    val sessionId: String? = null,
    /** Last-used mode/config to start the session with (server applies before
     * reporting state). Omitted when null. */
    val preferredModeId: String? = null,
    val preferredConfigValues: Map<String, String>? = null,
)

@Serializable
data class PromptBody(
    val connectionId: String,
    val blocks: List<PromptInputBlock>,
    val folderId: Int? = null,
    val conversationId: Int? = null,
    val clientMessageId: String? = null,
)

@Serializable
data class ConnectionIdBody(val connectionId: String)

/** Body for `acp_respond_permission` — resolves a `permission_request` by
 * selecting one of the offered options. */
@Serializable
data class RespondPermissionBody(
    val connectionId: String,
    val requestId: String,
    val optionId: String,
)

/** Body for `acp_answer_question` — answers an `ask_user_question`. */
@Serializable
data class AnswerQuestionBody(
    val connectionId: String,
    val questionId: String,
    val answer: QuestionAnswer,
)

/**
 * Body for `acp_answer_plan_approval` — resolves Grok's blocked `exit_plan_mode`.
 * [PlanApprovalAnswer.feedback] carries the revision notes for a `request_changes`
 * decision (ignored by the other two); omitted when null.
 */
@Serializable
data class AnswerPlanApprovalBody(
    val connectionId: String,
    val approvalId: String,
    val answer: PlanApprovalAnswer,
)

@Serializable
data class PlanApprovalAnswer(
    val decision: PlanApprovalDecision,
    val feedback: String? = null,
)

@Serializable
data class UpdateConversationPinnedBody(val conversationId: Int, val pinned: Boolean)

@Serializable
data class UpdateConversationTitleBody(val conversationId: Int, val title: String)

@Serializable
data class UpdateConversationStatusBody(val conversationId: Int, val status: String)

/** Body for endpoints taking a single absolute server-side `path`. */
@Serializable
data class PathBody(val path: String)

// MARK: - Projects / Git request bodies (camelCase keys)

@Serializable
data class ReadFilePreviewBody(val rootPath: String, val path: String)

@Serializable
data class GitStatusBody(val path: String, val showAllUntracked: Boolean = true)

@Serializable
data class GitLogBody(
    val path: String,
    val limit: Int? = null,
    val branch: String? = null,
    val remote: String? = null,
)

@Serializable
data class GitDiffBody(val path: String, val file: String? = null)

@Serializable
data class GitShowDiffBody(val path: String, val commit: String, val file: String? = null)

/** Git credentials for a clone; omitted entirely when both fields are blank. */
@Serializable
data class GitCredentials(val username: String, val password: String)

@Serializable
data class CloneRepositoryBody(
    val url: String,
    val targetDir: String,
    val credentials: GitCredentials? = null,
)

@Serializable
data class GitCheckoutBody(val path: String, val branchName: String)

@Serializable
data class GitNewBranchBody(val path: String, val branchName: String, val startPoint: String? = null)

/** Locate where a branch is checked out, to route a worktree-aware switch. */
@Serializable
data class ResolveWorktreeFolderBody(val repoPath: String, val branch: String)

/** Register a worktree directory as a folder, parented to its root repo. */
@Serializable
data class OpenWorktreeFolderBody(val path: String, val sourceFolderId: Int)

/**
 * Body for `git_commit`. The server **stages the listed files itself** (`git add`),
 * so untracked paths commit directly. [folderId] (null omitted) lets the server
 * broadcast a commit event to other workspace clients.
 */
@Serializable
data class GitCommitBody(
    val folderId: Int? = null,
    val path: String,
    val message: String,
    val files: List<String>,
)

/**
 * Body for `git_push`. All of [folderId]/[remote]/[credentials] are optional
 * (null omitted → server resolves the default remote / stored credentials).
 */
@Serializable
data class GitPushBody(
    val folderId: Int? = null,
    val path: String,
    val remote: String? = null,
    val credentials: GitCredentials? = null,
)

/** Body for `git_pull`. */
@Serializable
data class GitPullBody(val path: String, val credentials: GitCredentials? = null)

/** Body for `git_fetch`. */
@Serializable
data class GitFetchBody(val path: String, val credentials: GitCredentials? = null)

/** Body for `git_add_files`. */
@Serializable
data class GitAddFilesBody(val path: String, val files: List<String>)

/** Body for `git_rollback_file` and other `{path, file}` git calls. */
@Serializable
data class PathFileBody(val path: String, val file: String)

/** Body for `delete_file_tree_entry` — [path] is relative to [rootPath]. */
@Serializable
data class DeleteFileTreeEntryBody(val rootPath: String, val path: String)

// MARK: - Settings request bodies

@Serializable
data class QuickMessageCreateBody(val title: String, val content: String)

@Serializable
data class QuickMessageUpdateBody(val id: Int, val title: String, val content: String)

@Serializable
data class IdBody(val id: Int)

@Serializable
data class QuickMessageReorderBody(val ids: List<Int>)

@Serializable
data class CreateModelProviderBody(
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val agentType: AgentType,
    val model: String? = null,
)

@Serializable
data class UpdateModelProviderBody(
    val id: Int,
    val name: String? = null,
    val apiUrl: String? = null,
    val apiKey: String? = null,
    val agentType: AgentType? = null,
    val model: String? = null,
)

@Serializable
data class UpdateAgentEnvBody(
    val agentType: AgentType,
    val enabled: Boolean,
    val env: Map<String, String> = emptyMap(),
    val modelProviderId: Int? = null,
)

/** `acp_preflight`. `forceRefresh` is left off: the server's only cache is the npm-environment
 *  probe, which this client renders none of. */
@Serializable
data class AgentTypeBody(val agentType: AgentType)

@Serializable
data class SkillListBody(val agentType: AgentType, val workspacePath: String? = null)

@Serializable
data class SkillReadBody(val agentType: AgentType, val scope: String, val skillId: String, val workspacePath: String? = null)

@Serializable
data class SkillSaveBody(
    val agentType: AgentType,
    val scope: String,
    val skillId: String,
    val content: String,
    val workspacePath: String? = null,
    val layout: String? = null,
)

@Serializable
data class SkillDeleteBody(val agentType: AgentType, val scope: String, val skillId: String, val workspacePath: String? = null)

@Serializable
data class ExpertAgentBody(val agentType: AgentType)

@Serializable
data class ExpertLinkBody(val expertId: String, val agentType: AgentType)

@Serializable
data class ExpertIdBody(val expertId: String)

// MARK: - Chat channel request bodies (camelCase keys)

@Serializable
data class CreateChatChannelBody(
    val name: String,
    val channelType: ChannelType,
    val configJson: String,
    val enabled: Boolean,
    val dailyReportEnabled: Boolean,
    val dailyReportTime: String? = null,
)

/** Tri-state edit for `update_chat_channel`'s `dailyReportTime` (server field is
 *  `Option<Option<String>>`): [Keep] omits the key, [Clear] sends explicit null,
 *  [Set] sends the value. Encoded by hand in [app.codeg.android.core.network.CodegClient]. */
sealed interface FieldEdit {
    data object Keep : FieldEdit
    data object Clear : FieldEdit
    data class Set(val value: String) : FieldEdit
}

/** Partial update (omit = keep). Built by the editor, encoded via `buildJsonObject`. */
data class UpdateChatChannelBody(
    val id: Int,
    val name: String? = null,
    val enabled: Boolean? = null,
    val configJson: String? = null,
    val dailyReportEnabled: Boolean? = null,
    val dailyReportTime: FieldEdit = FieldEdit.Keep,
)

@Serializable
data class ChannelTokenBody(val channelId: Int, val token: String)

@Serializable
data class ChannelIdOnlyBody(val channelId: Int)

@Serializable
data class ListChannelMessagesBody(val channelId: Int, val limit: Int? = null, val offset: Int? = null)

@Serializable
data class WeixinCheckBody(val channelId: Int, val qrcode: String)

@Serializable
data class ChatPrefixBody(val prefix: String)

@Serializable
data class ChatLanguageBody(val language: String)

@Serializable
data class ChatWebhooksBody(val webhooks: List<WebhookConfig>)

// MARK: - Session snapshot / agent options request bodies

@Serializable
data class DescribeAgentOptionsBody(val agentType: AgentType, val workingDir: String? = null)

@Serializable
data class SetModeBody(val connectionId: String, val modeId: String)

@Serializable
data class SetConfigOptionBody(val connectionId: String, val configId: String, val valueId: String)

/** Body for `submit_session_feedback` — a mid-turn steering note for a running connection. */
@Serializable
data class SubmitFeedbackBody(val connectionId: String, val text: String)

// MARK: - GitHub credential request bodies

/** Body for `validate_github_token`. */
@Serializable
data class ValidateGitHubTokenBody(val serverUrl: String, val token: String)

/** Body for `save_account_token` — persist the account's keyring token. */
@Serializable
data class AccountTokenBody(val accountId: String, val token: String)

// MARK: - Kimi Code request bodies

/**
 * `acp_update_kimi_code_config` — discriminated on [mode] (`apikey`|`login`|`raw`).
 * camelCase wire (matches the backend `rename_all="camelCase"`); null optionals are
 * omitted (CodegJson.request has explicitNulls=false), which the backend's
 * `#[serde(default)]` reads as absent.
 *
 * The four reasoning fields are NOT "leave alone when absent": in `apikey` mode the
 * backend rebuilds the whole managed model block from this body, reading an absent
 * [reasoningEnabled] as `false` and then dropping `capabilities`, `support_efforts`
 * and `default_effort` from config.toml. Every `apikey` save must therefore echo the
 * declaration read back from disk — see `AgentConfigKimiView`. (`login` clears the
 * block by design and `raw` writes verbatim toml, so neither is affected.)
 */
@Serializable
data class UpdateKimiCodeConfigBody(
    val mode: String,
    val interfaceType: String? = null,
    val authType: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val maxContextSize: Int? = null,
    val vertexProject: String? = null,
    val vertexLocation: String? = null,
    val rawConfigToml: String? = null,
    /** Declares a thinking capability, which is what makes `kimi acp` advertise its
     *  Thinking picker in the composer at all. */
    val reasoningEnabled: Boolean? = null,
    /** Declares `always_thinking` instead of `thinking` — the picker has no Off row. */
    val alwaysThinking: Boolean? = null,
    /** The reasoning levels the composer offers; passed to the provider verbatim. */
    val supportEfforts: List<String>? = null,
    /** The level the picker opens on; the backend drops one outside [supportEfforts]. */
    val defaultEffort: String? = null,
)

/** A provider's wire protocol, for listing its models (Rust `ModelsProtocol`, snake_case). */
@Serializable
enum class ProviderModelsProtocol {
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC,
    @SerialName("gemini") GEMINI,
}

/**
 * `list_provider_models` — a provider's models asked the way the agent itself would (doubles
 * as a key test). Replaced the Kimi-only `acp_fetch_kimi_models` in codeg 0.4x. Only the
 * typed-key source is modelled (`keySource.kind == "inline"`); the server also takes saved
 * providers and agent-held keys. Wire: `{params: {protocol, baseUrl, keySource}}`.
 */
@Serializable
data class ListProviderModelsBody(val params: ProviderModelsParams)

@Serializable
data class ProviderModelsParams(
    val protocol: ProviderModelsProtocol,
    val baseUrl: String,
    val keySource: InlineModelsKeySource,
)

/** `ModelsKeySource::Inline` — internally tagged by `kind`. */
@Serializable
data class InlineModelsKeySource(val apiKey: String, val kind: String = "inline")

/** A provider's list (`ModelList`). The server serializes it camelCase. */
@Serializable
data class ProviderModelList(
    val url: String = "",
    val models: List<ListedProviderModel> = emptyList(),
)

@Serializable
data class ListedProviderModel(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Long? = null,
)

// MARK: - Pi request bodies

/**
 * `acp_update_pi_config` — writes pi's native settings.json/auth.json/models.json.
 * camelCase wire; null optionals omitted (backend `#[serde(default)]`).
 */
@Serializable
data class UpdatePiConfigBody(
    val provider: String,
    val model: String,
    val thinkingLevel: String? = null,
    val apiKey: String? = null,
    val customBaseUrl: String? = null,
    val customApi: String? = null,
)

/** `acp_validate_pi_command` — check a BYO-pi binary path/command. */
@Serializable
data class ValidatePiCommandBody(val command: String)

/** Tags a long-running server op's progress stream (install/uninstall). */
@Serializable
data class TaskIdBody(val taskId: String)

// MARK: - Cursor request bodies

/**
 * Cursor's structured controls sent inside `acp_update_agent_config`, merged onto
 * the current on-disk `~/.cursor/cli-config.json` so keys written by the CLI's own
 * `/config` UI survive.
 *
 * Unlike Grok's structured payload, an absent field here means "leave that key
 * alone" (the backend's `Option` is a patch, not a set-or-delete), so
 * [CodegClient.acpUpdateAgentConfig] OMITS null fields instead of sending explicit
 * nulls. The rule lists are replaced wholesale, so an emptied list is sent as `[]`
 * — that's a real "no rules", not an omission.
 */
data class CursorStructuredConfig(
    val sandboxMode: String? = null,
    val permissionsAllow: List<String>? = null,
    val permissionsDeny: List<String>? = null,
)
