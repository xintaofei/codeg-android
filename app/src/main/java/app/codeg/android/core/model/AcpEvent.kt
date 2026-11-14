package app.codeg.android.core.model

import app.codeg.android.core.model.wire.arrayOrNull
import app.codeg.android.core.model.wire.boolOrNull
import app.codeg.android.core.model.wire.longOrNull
import app.codeg.android.core.model.wire.objectOrNull
import app.codeg.android.core.model.wire.stringOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Status of a live ACP connection (Rust `ConnectionStatus`). Unknown → [ERROR]. */
enum class ConnectionStatus(val wire: String) {
    CONNECTING("connecting"),
    CONNECTED("connected"),
    PROMPTING("prompting"),
    DISCONNECTED("disconnected"),
    ERROR("error");

    companion object {
        fun fromWire(raw: String?): ConnectionStatus =
            entries.firstOrNull { it.wire == raw } ?: ERROR
    }
}

/** A block of a user's submitted prompt echoed on the connection stream
 * (Rust `UserMessageBlock`). */
sealed interface UserMessageBlock {
    data class Text(val text: String) : UserMessageBlock
    data class Image(val image: ImageData) : UserMessageBlock
    data object Unknown : UserMessageBlock

    companion object {
        fun fromWire(obj: JsonObject): UserMessageBlock =
            when (obj.stringOrNull("type")) {
                "text" -> Text(obj.stringOrNull("text").orEmpty())
                "image" -> Image(
                    ImageData(
                        data = obj.stringOrNull("data").orEmpty(),
                        mimeType = obj.stringOrNull("mime_type") ?: "image/png",
                        uri = null,
                    ),
                )
                else -> Unknown
            }
    }
}

/**
 * A backend→client ACP event (Rust `AcpEvent`, internally tagged `type`).
 * Decode-only. Decoded by hand from the flattened wire object so unknown event
 * types yield [Unknown] rather than breaking the whole stream — exactly as the
 * iOS decoder does.
 */
sealed interface AcpEvent {
    /**
     * Streamed assistant text. [parentToolUseId] is the launching Agent tool call's
     * id when the chunk came from a Claude *subagent* rather than the main thread
     * (`_meta.claudeCode.parentToolUseId`; claude-agent-acp ≥0.63, which codeg opts
     * into for Claude Code only). Null for main-thread content and for every other
     * agent — the server omits the key entirely there.
     *
     * A parented chunk belongs to the Agent tool call, NOT to the assistant's own
     * message; see [ContentDelta.isSubagent].
     */
    data class ContentDelta(val text: String, val parentToolUseId: String? = null) : AcpEvent {
        /** True when this chunk is a subagent's, so the main thread must skip it. */
        val isSubagent: Boolean get() = parentToolUseId != null
    }

    /** Streamed reasoning. [parentToolUseId] has the same contract as
     *  [ContentDelta.parentToolUseId]. */
    data class Thinking(val text: String, val parentToolUseId: String? = null) : AcpEvent {
        /** True when this chunk is a subagent's, so the main thread must skip it. */
        val isSubagent: Boolean get() = parentToolUseId != null
    }
    /** [meta] is the agent's opaque ACP extensibility metadata (context compaction,
     *  Grok's `x.ai/tool` identity, …). Absent for most tools/hosts. */
    data class ToolCall(
        val id: String,
        val title: String,
        val kind: String,
        val status: String,
        val content: String?,
        val rawInput: String?,
        val rawOutput: String?,
        val meta: JsonObject? = null,
    ) : AcpEvent
    /** A null [meta] means "unchanged" (the server omits the key), NOT "cleared" —
     *  see `LiveTurnBuilder`'s replace-on-update handling. */
    data class ToolCallUpdate(
        val id: String,
        val title: String?,
        val status: String?,
        val content: String?,
        val rawInput: String?,
        val rawOutput: String?,
        val append: Boolean,
        val meta: JsonObject? = null,
    ) : AcpEvent
    data class TurnComplete(val stopReason: String) : AcpEvent
    data class SessionStarted(val sessionId: String) : AcpEvent
    data class ConversationLinked(val conversationId: Int, val folderId: Int) : AcpEvent
    data class ConversationStatusChanged(val conversationId: Int, val status: ConversationStatus) : AcpEvent
    data class StatusChanged(val status: ConnectionStatus) : AcpEvent
    data class UsageUpdate(val used: Long, val size: Long) : AcpEvent
    data class UserMessage(val messageId: String, val blocks: List<UserMessageBlock>) : AcpEvent
    data class UserPromptSent(val textPreview: String) : AcpEvent
    /**
     * A turn or connection failure. [details] is out-of-band diagnostic evidence for errors
     * codeg *inferred* rather than received — the `turn_failed_empty*` family, where the agent
     * reported success and the wire carried no error at all. It holds the turn's agent stderr
     * tail and a count of updates codeg failed to parse, already redacted and length-bounded
     * server-side. Absent for every ordinary error.
     */
    data class Error(val message: String, val code: String?, val details: String? = null) : AcpEvent
    /** Agent asks to approve a tool call (also carries ExitPlanMode — the plan
     * rides inside [toolCall]). Resolve via `acp_respond_permission`. */
    data class PermissionRequest(
        val requestId: String,
        val toolCall: JsonElement,
        val options: List<PermissionOption>,
    ) : AcpEvent
    data class PermissionResolved(val requestId: String) : AcpEvent
    /** Agent asks one or more multiple-choice questions. Resolve via
     * `acp_answer_question`. */
    data class QuestionRequest(val questionId: String, val questions: List<QuestionSpec>) : AcpEvent
    data class QuestionResolved(val questionId: String) : AcpEvent
    /**
     * Grok's native `exit_plan_mode`: the agent finished planning and is BLOCKED on the
     * user's approval before it leaves plan mode and starts implementing. Resolve via
     * `acp_answer_plan_approval`; also carried on the session snapshot so a mid-turn
     * attach recovers it.
     */
    data class PlanApprovalRequest(
        val approvalId: String,
        val toolCallId: String,
        val planMarkdown: String,
    ) : AcpEvent
    /** A pending plan approval was answered (from any client) or canceled — clear the
     *  card. Idempotent on apply. */
    data class PlanApprovalResolved(val approvalId: String) : AcpEvent
    /** The agent's live plan / TODO list (display only; does not block the turn). */
    data class PlanUpdate(val entries: List<PlanEntry>) : AcpEvent
    /**
     * A mid-turn steering note was submitted — by this client or any other one viewing the
     * same conversation. The sending client also receives its own echo and dedups it against
     * the note the submit call returned (both carry the same `id`).
     */
    data class FeedbackSubmitted(val note: FeedbackNote) : AcpEvent
    /**
     * The agent read one or more pending notes. Carries only ids: clients already hold the
     * text and just flip those notes to delivered.
     *
     * The event's `delivered_at` is dropped — nothing renders a delivery time, and modelling
     * a field no surface reads would only invite it to drift from the server's.
     */
    data class FeedbackConsumed(val ids: List<String>) : AcpEvent

    /**
     * The agent's config-option catalog **and** its current values.
     *
     * Emitted whenever either moves — the agent flipping an option itself (cline's
     * `auto_approve`), a `set_config_option` from any client, or the catalog landing late
     * during session setup. The server sends the WHOLE list every time and assigns it
     * wholesale (`SessionState::apply_event`), so this replaces what was held rather than
     * merging into it.
     */
    data class SessionConfigOptions(val configOptions: List<SessionConfigOption>) : AcpEvent

    /** The agent's mode catalog + current mode. Same whole-list contract as
     *  [SessionConfigOptions]. */
    data class SessionModes(val modes: SessionModeState) : AcpEvent

    /** Only the *current* mode moved; the catalog is unchanged and is not repeated here. */
    data class ModeChanged(val modeId: String) : AcpEvent

    /**
     * The agent has finished publishing its initial selectors.
     *
     * Latched once per connection and carried on every snapshot thereafter. It is the only
     * thing that tells an empty [SessionConfigOptions] apart from the setup transient — some
     * agents publish an empty catalog and push the real one right after, which the backend's
     * own probe has to wait out (`manager.rs::wait_for_session_options`).
     */
    data object SelectorsReady : AcpEvent

    data class Unknown(val type: String) : AcpEvent

    companion object {
        /**
         * Decode an [AcpEvent] from the flattened wire object. [json] is used to
         * decode the nested structured lists (options / questions / plan entries)
         * with the snake_case naming strategy; everything else is read by hand.
         */
        fun fromWire(obj: JsonObject, json: Json): AcpEvent {
            return when (val type = obj.stringOrNull("type").orEmpty()) {
                "content_delta" -> ContentDelta(
                    text = obj.stringOrNull("text").orEmpty(),
                    parentToolUseId = obj.stringOrNull("parent_tool_use_id"),
                )
                "thinking" -> Thinking(
                    text = obj.stringOrNull("text").orEmpty(),
                    parentToolUseId = obj.stringOrNull("parent_tool_use_id"),
                )
                "tool_call" -> ToolCall(
                    id = obj.stringOrNull("tool_call_id").orEmpty(),
                    title = obj.stringOrNull("title").orEmpty(),
                    kind = obj.stringOrNull("kind").orEmpty(),
                    status = obj.stringOrNull("status").orEmpty(),
                    content = obj.stringOrNull("content"),
                    rawInput = obj.stringOrNull("raw_input"),
                    rawOutput = obj.stringOrNull("raw_output"),
                    meta = obj.objectOrNull("meta"),
                )
                "tool_call_update" -> ToolCallUpdate(
                    id = obj.stringOrNull("tool_call_id").orEmpty(),
                    title = obj.stringOrNull("title"),
                    status = obj.stringOrNull("status"),
                    content = obj.stringOrNull("content"),
                    rawInput = obj.stringOrNull("raw_input"),
                    rawOutput = obj.stringOrNull("raw_output"),
                    append = obj.boolOrNull("raw_output_append") ?: false,
                    meta = obj.objectOrNull("meta"),
                )
                "turn_complete" -> TurnComplete(obj.stringOrNull("stop_reason") ?: "end_turn")
                "session_started" -> SessionStarted(obj.stringOrNull("session_id").orEmpty())
                "conversation_linked" -> ConversationLinked(
                    conversationId = obj.intOrZero("conversation_id"),
                    folderId = obj.intOrZero("folder_id"),
                )
                "conversation_status_changed" -> ConversationStatusChanged(
                    conversationId = obj.intOrZero("conversation_id"),
                    status = ConversationStatus.fromWire(obj.stringOrNull("status").orEmpty()),
                )
                "status_changed" -> StatusChanged(ConnectionStatus.fromWire(obj.stringOrNull("status")))
                "usage_update" -> UsageUpdate(
                    used = obj.longOrNull("used") ?: 0,
                    size = obj.longOrNull("size") ?: 0,
                )
                "user_message" -> UserMessage(
                    messageId = obj.stringOrNull("message_id").orEmpty(),
                    blocks = obj.arrayOrNull("blocks")
                        ?.mapNotNull { (it as? JsonObject)?.let(UserMessageBlock::fromWire) }
                        ?: emptyList(),
                )
                "user_prompt_sent" -> UserPromptSent(obj.stringOrNull("text_preview").orEmpty())
                "error" -> Error(
                    message = obj.stringOrNull("message") ?: "Unknown error",
                    code = obj.stringOrNull("code"),
                    details = obj.stringOrNull("details"),
                )
                "permission_request" -> PermissionRequest(
                    requestId = obj.stringOrNull("request_id").orEmpty(),
                    toolCall = obj["tool_call"] ?: JsonNull,
                    options = decodeList(json, obj["options"], PermissionOption.serializer()),
                )
                "permission_resolved" -> PermissionResolved(obj.stringOrNull("request_id").orEmpty())
                "question_request" -> QuestionRequest(
                    questionId = obj.stringOrNull("question_id").orEmpty(),
                    questions = decodeList(json, obj["questions"], QuestionSpec.serializer()),
                )
                "question_resolved" -> QuestionResolved(obj.stringOrNull("question_id").orEmpty())
                "plan_approval_request" -> PlanApprovalRequest(
                    approvalId = obj.stringOrNull("approval_id").orEmpty(),
                    toolCallId = obj.stringOrNull("tool_call_id").orEmpty(),
                    // An empty/missing plan still opens the approval surface (Grok's
                    // plan-mode doc allows it) — the card shows an empty-state notice.
                    planMarkdown = obj.stringOrNull("plan_markdown").orEmpty(),
                )
                "plan_approval_resolved" -> PlanApprovalResolved(obj.stringOrNull("approval_id").orEmpty())
                "plan_update" -> PlanUpdate(
                    entries = decodeList(json, obj["entries"], PlanEntry.serializer()),
                )
                // A submitted note with no `item` is a shape surprise, not an empty note:
                // decoding it into a blank one would put an untitled row on screen that no
                // `feedback_consumed` can ever resolve (its id would match nothing).
                "feedback_submitted" -> obj.objectOrNull("item")
                    ?.let { FeedbackSubmitted(FeedbackNote.fromWire(it)) }
                    ?: Unknown(type)
                "feedback_consumed" -> FeedbackConsumed(
                    ids = obj.arrayOrNull("ids")
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?: emptyList(),
                )
                // A genuinely empty list decodes as one — whether that means "nothing to
                // configure" or "the real options are still coming" is the consumer's call, not
                // the decoder's (see `SessionDetailViewModel.applyEvent`). An absent or
                // unreadable payload is dropped instead, because the consumer adopts an empty
                // catalog wholesale: collapsing "one option failed to parse" into "the agent has
                // no options" would clear every control off the sheet.
                "session_config_options" -> decodeListOrNull(
                    json, obj["config_options"], SessionConfigOption.serializer(),
                )?.let(::SessionConfigOptions) ?: Unknown(type)
                "selectors_ready" -> SelectorsReady
                "session_modes" -> decodeOrNull(json, obj["modes"], SessionModeState.serializer())
                    ?.let(::SessionModes)
                    ?: Unknown(type)
                // An empty mode id names no mode, so adopting it would silently CLEAR the
                // picker's selection rather than move it — worse than ignoring a payload
                // this build can't read.
                "mode_changed" -> obj.stringOrNull("mode_id")?.takeIf { it.isNotEmpty() }
                    ?.let(::ModeChanged)
                    ?: Unknown(type)
                else -> Unknown(type)
            }
        }

        private fun JsonObject.intOrZero(key: String): Int =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 0

        private fun <T> decodeList(json: Json, element: JsonElement?, serializer: KSerializer<T>): List<T> {
            val arr = element as? JsonArray ?: return emptyList()
            return runCatching { json.decodeFromJsonElement(ListSerializer(serializer), arr) }
                .getOrDefault(emptyList())
        }

        private fun <T> decodeOrNull(json: Json, element: JsonElement?, serializer: KSerializer<T>): T? {
            val obj = element as? JsonObject ?: return null
            return runCatching { json.decodeFromJsonElement(serializer, obj) }.getOrNull()
        }

        /** [decodeList], but reporting "absent or unreadable" as null instead of collapsing it
         *  onto the same empty list a real empty array decodes to. */
        internal fun <T> decodeListOrNull(
            json: Json,
            element: JsonElement?,
            serializer: KSerializer<T>,
        ): List<T>? {
            val arr = element as? JsonArray ?: return null
            return runCatching { json.decodeFromJsonElement(ListSerializer(serializer), arr) }.getOrNull()
        }
    }
}

/**
 * The flat ACP event envelope (Rust `EventEnvelope`): `{seq, connection_id,
 * type, ...payload}`. The payload is flattened, so we read `seq`/`connectionId`
 * and then the event from the same object.
 */
data class EventEnvelope(
    val seq: Long,
    val connectionId: String,
    val event: AcpEvent,
) {
    companion object {
        fun fromWire(obj: JsonObject, json: Json): EventEnvelope =
            EventEnvelope(
                seq = obj.longOrNull("seq") ?: 0,
                connectionId = obj.stringOrNull("connection_id").orEmpty(),
                event = AcpEvent.fromWire(obj, json),
            )
    }
}

/**
 * Projection of the live session snapshot delivered on attach (Rust
 * `LiveSessionSnapshot`). The scalar fields drive the send path; [liveMessage] +
 * [activeToolCalls] let `reattachIfLive` rebuild an in-flight assistant turn (and
 * [pendingPermission] / [pendingQuestion] its interactive card) when opening a
 * session whose turn is still running. All parsing is tolerant — a shape surprise
 * yields null/empty, never a thrown frame (the send path decodes this on every
 * `.snapshot`).
 */
data class LiveSessionSnapshot(
    val connectionId: String?,
    val conversationId: Int?,
    val folderId: Int?,
    val status: ConnectionStatus?,
    val externalId: String?,
    val eventSeq: Long?,
    val liveMessage: LiveMessageSnapshot?,
    val activeToolCalls: List<ToolCallStateSnapshot>,
    val pendingPermission: PendingPermissionSnapshot?,
    val pendingQuestion: PendingQuestionSnapshot?,
    val pendingPlanApproval: PendingPlanApprovalSnapshot?,
    /**
     * The turn's mid-turn steering notes, and whether it can take more.
     *
     * Carried here as well as on the HTTP snapshot because this is the ONLY recovery path
     * when a reconnect's gap is too wide to replay: the missed `feedback_submitted` /
     * `feedback_consumed` events are never re-sent, so without these the strip keeps showing
     * whatever it held when the socket dropped.
     */
    val feedback: List<FeedbackNote>,
    val feedbackToolAvailable: Boolean,
    val nativeSteeringAvailable: Boolean,
    /**
     * Whether the server has broadcast this turn's `user_message` yet (presence of
     * `pending_user_message`, which it sets from that event and clears at `turn_complete`).
     *
     * Only the presence is modelled, because only the presence is used: it is the proof a
     * steering note would survive. [status] alone isn't — the connection loop reports
     * `prompting` first and emits `user_message` after, and that event CLEARS the turn's
     * notes, so a note sent in between is accepted and then wiped unread.
     */
    val hasPendingUserMessage: Boolean,
    /**
     * The connection's most recent error. Attaching is the ONLY channel for it — the live
     * `error` event already fired — so without this a client that opened the session a beat
     * too late shows a turn that produced nothing and said nothing.
     */
    val lastError: SessionLastError?,
    /**
     * The connection's mode catalog + current mode, or null when the agent has published none.
     *
     * Carried for the same reason as [feedback]: a reconnect whose gap is too wide to replay
     * gets a snapshot INSTEAD of the events it missed, and those `session_modes` /
     * `mode_changed` pushes are never re-sent. Without this the attach advances the event
     * cursor past them and the selectors stay on whatever they held when the socket dropped.
     *
     * The separate `current_mode` field is deliberately not modelled: the server keeps
     * `modes.current_mode_id` in sync on every `mode_changed` precisely so snapshot consumers
     * read it from here (`SessionState::apply_event`), and a second copy would only invite the
     * two to disagree.
     */
    val modes: SessionModeState?,
    /**
     * The connection's config-option catalog + current values, or null when the agent has
     * published none. Same recovery role as [modes].
     *
     * Null and empty mean different things — "never published" versus "published, and there is
     * nothing to configure" — so this stays nullable rather than defaulting to an empty list.
     * An unreadable payload also lands as null: adopting it as empty would report an agent with
     * options as having none.
     */
    val configOptions: List<SessionConfigOption>?,
    /**
     * Whether the agent has finished publishing its initial selectors — the server's latch
     * (false until its `selectors_ready` event, true on every snapshot after).
     *
     * Needed alongside [configOptions] because an empty catalog is ambiguous without it: during
     * the connect→`initialize` window some agents publish an empty list and push the real one
     * moments later. See [AcpEvent.SelectorsReady].
     */
    val selectorsReady: Boolean,
) {
    companion object {
        fun fromWire(obj: JsonObject, json: Json): LiveSessionSnapshot =
            LiveSessionSnapshot(
                connectionId = obj.stringOrNull("connection_id"),
                conversationId = obj.intOrNull("conversation_id"),
                folderId = obj.intOrNull("folder_id"),
                status = obj.stringOrNull("status")?.let(ConnectionStatus::fromWire),
                externalId = obj.stringOrNull("external_id"),
                eventSeq = obj.longOrNull("event_seq"),
                liveMessage = obj.objectOrNull("live_message")
                    ?.let { LiveMessageSnapshot.fromWire(it, json) },
                activeToolCalls = obj.arrayOrNull("active_tool_calls")
                    ?.mapNotNull { (it as? JsonObject)?.let(ToolCallStateSnapshot::fromWire) }
                    ?: emptyList(),
                pendingPermission = obj.objectOrNull("pending_permission")
                    ?.let { PendingPermissionSnapshot.fromWire(it, json) },
                pendingQuestion = obj.objectOrNull("pending_question")
                    ?.let { PendingQuestionSnapshot.fromWire(it, json) },
                pendingPlanApproval = obj.objectOrNull("pending_plan_approval")
                    ?.let(PendingPlanApprovalSnapshot::fromWire),
                feedback = obj.arrayOrNull("feedback")
                    ?.mapNotNull { (it as? JsonObject)?.let(FeedbackNote::fromWire) }
                    ?: emptyList(),
                feedbackToolAvailable = obj.boolOrNull("feedback_tool_available") ?: false,
                nativeSteeringAvailable = obj.boolOrNull("native_steering_available") ?: false,
                hasPendingUserMessage = obj.objectOrNull("pending_user_message") != null,
                lastError = obj.objectOrNull("last_error")?.let(SessionLastError::fromWire),
                modes = obj.objectOrNull("modes")?.let {
                    runCatching { json.decodeFromJsonElement(SessionModeState.serializer(), it) }.getOrNull()
                },
                configOptions = AcpEvent.decodeListOrNull(
                    json, obj["config_options"], SessionConfigOption.serializer(),
                ),
                selectorsReady = obj.boolOrNull("selectors_ready") ?: false,
            )

        private fun JsonObject.intOrNull(key: String): Int? =
            (this[key] as? JsonPrimitive)
                ?.let { runCatching { it.content.toInt() }.getOrNull() }
    }
}

/**
 * The connection's last error, carried on the attach snapshot (Rust `SessionLastError`).
 *
 * **Undated.** The server clears it in the `StatusChanged(Prompting)` arm, which is late — a
 * prompt is enqueued, and its conversation row already says `in_progress`, well before the
 * loop emits that status. So a snapshot taken in between still carries the PREVIOUS turn's
 * failure, and nothing on the record distinguishes that from a session whose last turn really
 * did fail. Never attribute one of these to a turn without a proof that dates it; the only
 * one available is the snapshot's own `status` — see `snapshotTurnError`.
 */
data class SessionLastError(
    val message: String,
    val code: String?,
    /** See [AcpEvent.Error.details]. Already redacted server-side. */
    val details: String?,
) {
    companion object {
        fun fromWire(obj: JsonObject): SessionLastError = SessionLastError(
            message = obj.stringOrNull("message").orEmpty(),
            code = obj.stringOrNull("code"),
            details = obj.stringOrNull("details"),
        )
    }
}

/** The in-flight assistant message carried by a snapshot (Rust `LiveMessage`). */
data class LiveMessageSnapshot(val content: List<LiveContentBlockSnapshot>) {
    companion object {
        fun fromWire(obj: JsonObject, json: Json): LiveMessageSnapshot =
            LiveMessageSnapshot(
                content = obj.arrayOrNull("content")
                    ?.mapNotNull { (it as? JsonObject)?.let { o -> LiveContentBlockSnapshot.fromWire(o, json) } }
                    ?: emptyList(),
            )
    }
}

/** One ordered block of the in-flight message (Rust `LiveContentBlock`,
 *  `kind`-tagged). [ToolCallRef] points into [LiveSessionSnapshot.activeToolCalls]. */
sealed interface LiveContentBlockSnapshot {
    /** [parentToolUseId] carries the same subagent attribution as
     *  [AcpEvent.ContentDelta.parentToolUseId] — the backend splits blocks at
     *  attribution boundaries, so a parented block is wholly a subagent's. */
    data class Text(val text: String, val parentToolUseId: String? = null) : LiveContentBlockSnapshot {
        /** True when this block is a subagent's, so the main thread must skip it. */
        val isSubagent: Boolean get() = parentToolUseId != null
    }

    /** [parentToolUseId]: see [Text]. */
    data class Thinking(val text: String, val parentToolUseId: String? = null) : LiveContentBlockSnapshot {
        /** True when this block is a subagent's, so the main thread must skip it. */
        val isSubagent: Boolean get() = parentToolUseId != null
    }
    data class ToolCallRef(val toolCallId: String) : LiveContentBlockSnapshot
    data class Plan(val entries: List<PlanEntry>) : LiveContentBlockSnapshot
    data object Unknown : LiveContentBlockSnapshot

    companion object {
        fun fromWire(obj: JsonObject, json: Json): LiveContentBlockSnapshot =
            when (obj.stringOrNull("kind")) {
                "text" -> Text(
                    text = obj.stringOrNull("text").orEmpty(),
                    parentToolUseId = obj.stringOrNull("parent_tool_use_id"),
                )
                "thinking" -> Thinking(
                    text = obj.stringOrNull("text").orEmpty(),
                    parentToolUseId = obj.stringOrNull("parent_tool_use_id"),
                )
                "tool_call_ref" -> ToolCallRef(obj.stringOrNull("tool_call_id").orEmpty())
                "plan" -> Plan(
                    (obj["entries"] as? JsonArray)?.let {
                        runCatching { json.decodeFromJsonElement(ListSerializer(PlanEntry.serializer()), it) }
                            .getOrDefault(emptyList())
                    } ?: emptyList(),
                )
                else -> Unknown
            }
    }
}

/** One active tool call carried by a snapshot (Rust `ToolCallState`). `kind` /
 *  `status` are bare strings; `input` / `output` are freeform JSON (`output` is
 *  `{kind,...}`-tagged — text/error/json). */
data class ToolCallStateSnapshot(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val input: JsonElement?,
    val output: JsonElement?,
    val content: String?,
    /** The agent's opaque ACP metadata, as on the live event path. */
    val meta: JsonObject? = null,
) {
    /** The agent's argument JSON as a preview string (matches the event path's
     *  `raw_input`, which already arrives as a string). */
    val inputPreview: String?
        get() = when (val i = input) {
            null, JsonNull -> null
            is JsonPrimitive -> if (i.isString) i.content else i.toString()
            else -> i.toString()
        }

    /** Flatten the `{kind,...}`-tagged output into plain text for the live card. */
    val outputText: String
        get() {
            val o = output as? JsonObject
                ?: return (output as? JsonPrimitive)?.let { if (it.isString) it.content else it.toString() }.orEmpty()
            return when (o.stringOrNull("kind")) {
                "text" -> o.stringOrNull("content").orEmpty()
                "error" -> o.stringOrNull("message").orEmpty()
                "json" -> o["value"]?.toString().orEmpty()
                else -> ""
            }
        }

    /** The snapshot `status` normalized to the lowercase form the live tool card
     *  interprets — snapshot statuses may arrive PascalCase (iOS `normalizedToolStatus`). */
    val normalizedStatus: String
        get() = when (status.lowercase()) {
            "inprogress", "in_progress", "in-progress", "running" -> "in_progress"
            "completed", "done", "success" -> "completed"
            "failed", "error" -> "failed"
            "pending" -> "pending"
            else -> status.lowercase()
        }

    companion object {
        fun fromWire(obj: JsonObject): ToolCallStateSnapshot =
            ToolCallStateSnapshot(
                id = obj.stringOrNull("id").orEmpty(),
                kind = obj.stringOrNull("kind").orEmpty(),
                label = obj.stringOrNull("label").orEmpty(),
                status = obj.stringOrNull("status").orEmpty(),
                input = obj["input"],
                output = obj["output"],
                content = obj.stringOrNull("content"),
                meta = obj.objectOrNull("meta"),
            )
    }
}

/** Pending permission carried by a snapshot (Rust `PendingPermissionState`). */
data class PendingPermissionSnapshot(
    val requestId: String,
    val toolCall: JsonElement,
    val options: List<PermissionOption>,
) {
    companion object {
        fun fromWire(obj: JsonObject, json: Json): PendingPermissionSnapshot =
            PendingPermissionSnapshot(
                requestId = obj.stringOrNull("request_id").orEmpty(),
                toolCall = obj["tool_call"] ?: JsonNull,
                options = (obj["options"] as? JsonArray)?.let {
                    runCatching {
                        json.decodeFromJsonElement(ListSerializer(PermissionOption.serializer()), it)
                    }.getOrDefault(emptyList())
                } ?: emptyList(),
            )
    }
}

/**
 * Pending Grok plan approval carried by a snapshot (Rust `PendingPlanApprovalState`),
 * so a client attaching mid-turn recovers the blocked `exit_plan_mode` card instead of
 * watching the turn spin.
 */
data class PendingPlanApprovalSnapshot(
    val approvalId: String,
    val toolCallId: String,
    val planMarkdown: String,
) {
    companion object {
        fun fromWire(obj: JsonObject): PendingPlanApprovalSnapshot =
            PendingPlanApprovalSnapshot(
                approvalId = obj.stringOrNull("approval_id").orEmpty(),
                toolCallId = obj.stringOrNull("tool_call_id").orEmpty(),
                planMarkdown = obj.stringOrNull("plan_markdown").orEmpty(),
            )
    }
}

/** Pending question carried by a snapshot (Rust `PendingQuestionState`). */
data class PendingQuestionSnapshot(
    val questionId: String,
    val questions: List<QuestionSpec>,
) {
    companion object {
        fun fromWire(obj: JsonObject, json: Json): PendingQuestionSnapshot =
            PendingQuestionSnapshot(
                questionId = obj.stringOrNull("question_id").orEmpty(),
                questions = (obj["questions"] as? JsonArray)?.let {
                    runCatching {
                        json.decodeFromJsonElement(ListSerializer(QuestionSpec.serializer()), it)
                    }.getOrDefault(emptyList())
                } ?: emptyList(),
            )
    }
}
