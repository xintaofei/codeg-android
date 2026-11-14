package app.codeg.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * One choice the user can pick to resolve a `permission_request` (Rust
 * `PermissionOptionInfo`). `kind` is the SACP option kind (`allow_once` /
 * `allow_always` / `reject_once` / `reject_always`); a `reject*` option is the
 * deny / "keep planning" choice.
 */
@Serializable
data class PermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
    /**
     * The option's ACP `_meta`, forwarded verbatim by the server (same
     * opaque-passthrough treatment as the request's `tool_call`). codex-acp
     * ≥1.1.8 and claude-agent-acp ≥0.64.1 hang
     * `permission = {version: 1, changes: [...]}` here, saying what picking this
     * option would grant and for how long; `parsePermissionOptionChanges` reads
     * it. Absent for every other agent, and for snapshots taken before the
     * server carried it — hence the default.
     *
     * Typed as a raw element, not `JsonObject`: it is an untyped
     * `serde_json::Value` upstream, and a non-object here must not fail the
     * whole options list and blank out the buttons.
     */
    val meta: JsonElement? = null,
) {
    /** Deny / keep-planning style choice — rendered as a bordered (non-filled) button. */
    val isReject: Boolean get() = kind.lowercase().startsWith("reject")
}

/**
 * One answered permission request, as a record about its tool call (Rust
 * `ToolApprovalInfo`): what was chosen, by whom, and when. Live on the `tool_approval` event
 * and the attach snapshot's `tool_approvals`; a reopened conversation's detail load stamps the
 * same fields onto the call's `meta["codeg.approval"]`.
 */
@Serializable
data class ToolApprovalInfo(
    val toolCallId: String,
    /** The chosen option's ACP kind (`allow_once` / `allow_always` / `reject_once` /
     *  `reject_always`), passed through verbatim so a kind a newer agent invents survives. */
    val optionKind: String,
    /** The chosen option's label in the agent's own words — the only place that says what
     *  an "always" covers for that agent. */
    val optionName: String = "",
    /** Who answered: `user`, `chat_channel`, `cli_policy`. */
    val origin: String = "",
    /** RFC 3339. */
    val decidedAt: String = "",
)

/** One option of an `ask_user_question` question (Rust `QuestionOption`). */
@Serializable
data class QuestionOption(
    val label: String,
    val description: String = "",
)

/**
 * One question in an `ask_user_question` set (Rust `QuestionSpec`). [id] is the
 * per-question UUID the answer must echo back as [QuestionAnswerItem.questionId].
 */
@Serializable
data class QuestionSpec(
    val id: String,
    val question: String,
    val header: String = "",
    val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
    /** The answer is a secret (codex marks API keys etc. with `_meta.codex.isSecret`): the
     *  free-text input is masked. */
    val isSecret: Boolean = false,
    /**
     * Whether a free-text answer is accepted alongside [options]. False for native
     * permission bridges (Pi select, Antigravity) that can only take one of the listed
     * option ids: the server CANCELS the question on any other answer. Defaults to true,
     * matching the server's `default_true`.
     */
    val allowOther: Boolean = true,
) {
    /** No options to pick from: the answer can only be typed, so the input always shows
     *  (the server renders it regardless of [allowOther]). */
    val freeTextOnly: Boolean get() = options.isEmpty()

    /** Whether the card offers a free-text answer at all. */
    val acceptsFreeText: Boolean get() = allowOther || freeTextOnly
}

/**
 * One question's answer: the per-question id plus the chosen option labels (a
 * free-text "Other" entry is appended verbatim). Encode-only; sent with the
 * request `Json` so keys stay camelCase (`questionId`), matching the server.
 */
@Serializable
data class QuestionAnswerItem(
    val questionId: String,
    val labels: List<String>,
)

/**
 * The full reply to a `question_request`. `declined == true` (with empty
 * [answers]) dismisses the prompt and lets the agent use its own judgment.
 */
@Serializable
data class QuestionAnswer(
    val answers: List<QuestionAnswerItem> = emptyList(),
    val declined: Boolean = false,
) {
    companion object {
        val dismissed = QuestionAnswer(answers = emptyList(), declined = true)
    }
}

/**
 * The user's decision on a pending Grok plan approval. snake_case on the wire; the
 * request `Json` has no naming strategy, so the value is spelled out here.
 */
@Serializable(with = PlanApprovalDecision.Serializer::class)
enum class PlanApprovalDecision(val wire: String) {
    /** Grok leaves plan mode and starts implementing. */
    APPROVE("approve"),
    /** Grok revises the plan; plan mode stays active. */
    REQUEST_CHANGES("request_changes"),
    /** Plan mode is turned off. */
    ABANDON("abandon");

    object Serializer : KSerializer<PlanApprovalDecision> {
        override val descriptor =
            PrimitiveSerialDescriptor("com.codeg.PlanApprovalDecision", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: PlanApprovalDecision) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): PlanApprovalDecision =
            entries.firstOrNull { it.wire == decoder.decodeString() } ?: APPROVE
    }
}

/** One row of the agent's live plan (`plan_update` event / snapshot `plan` block). */
@Serializable
data class PlanEntry(
    val content: String,
    val priority: String = "medium",
    val status: String = "pending",
) {
    enum class Status { COMPLETED, IN_PROGRESS, PENDING }

    val normalizedStatus: Status
        get() = when (status.lowercase()) {
            "completed", "done" -> Status.COMPLETED
            "in_progress", "in-progress", "running", "active" -> Status.IN_PROGRESS
            else -> Status.PENDING
        }

    enum class Priority { HIGH, MEDIUM, LOW }

    val normalizedPriority: Priority
        get() = when (priority.lowercase()) {
            "high", "urgent" -> Priority.HIGH
            "low" -> Priority.LOW
            else -> Priority.MEDIUM
        }
}
