package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the hand-written, flattened `AcpEvent` / `EventEnvelope` decoders. */
class AcpEventTest {

    private val json: Json = CodegJson.response

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject
    private fun event(raw: String): AcpEvent = AcpEvent.fromWire(obj(raw), json)

    @Test
    fun `content delta`() {
        assertEquals(AcpEvent.ContentDelta("hi"), event("""{"type":"content_delta","text":"hi"}"""))
    }

    @Test
    fun `main-thread chunks carry no subagent attribution`() {
        // The server omits `parent_tool_use_id` entirely for main-thread content and
        // for every agent but Claude Code, so absent must read as "not a subagent".
        val delta = event("""{"type":"content_delta","text":"hi"}""") as AcpEvent.ContentDelta
        assertFalse(delta.isSubagent)
        val thinking = event("""{"type":"thinking","text":"hmm"}""") as AcpEvent.Thinking
        assertFalse(thinking.isSubagent)
    }

    @Test
    fun `subagent chunks decode their parent tool call`() {
        val delta = event(
            """{"type":"content_delta","text":"sub","parent_tool_use_id":"toolu_42"}""",
        ) as AcpEvent.ContentDelta
        assertEquals("toolu_42", delta.parentToolUseId)
        assertTrue(delta.isSubagent)

        val thinking = event(
            """{"type":"thinking","text":"sub","parent_tool_use_id":"toolu_42"}""",
        ) as AcpEvent.Thinking
        assertEquals("toolu_42", thinking.parentToolUseId)
        assertTrue(thinking.isSubagent)
    }

    @Test
    fun `snapshot content blocks carry subagent attribution`() {
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","live_message":{"content":[
                     {"kind":"text","text":"main"},
                     {"kind":"text","text":"sub","parent_tool_use_id":"toolu_42"},
                     {"kind":"thinking","text":"subthink","parent_tool_use_id":"toolu_42"}]}}""",
            ),
            json,
        )
        val blocks = snap.liveMessage!!.content
        assertEquals(3, blocks.size)
        assertFalse((blocks[0] as LiveContentBlockSnapshot.Text).isSubagent)
        assertEquals("toolu_42", (blocks[1] as LiveContentBlockSnapshot.Text).parentToolUseId)
        assertTrue((blocks[2] as LiveContentBlockSnapshot.Thinking).isSubagent)
    }

    @Test
    fun `tool call reads snake_case fields`() {
        val e = event(
            """{"type":"tool_call","tool_call_id":"t1","title":"Read file",
               "kind":"read","status":"in_progress","raw_input":"path"}""",
        ) as AcpEvent.ToolCall
        assertEquals("t1", e.id)
        assertEquals("Read file", e.title)
        assertEquals("read", e.kind)
        assertEquals("in_progress", e.status)
        assertEquals("path", e.rawInput)
    }

    @Test
    fun `permission request decodes nested options`() {
        val e = event(
            """{"type":"permission_request","request_id":"r1","tool_call":{"kind":"execute"},
               "options":[{"option_id":"o1","name":"Allow","kind":"allow_once"},
                          {"option_id":"o2","name":"Deny","kind":"reject_once"}]}""",
        ) as AcpEvent.PermissionRequest
        assertEquals("r1", e.requestId)
        assertEquals(2, e.options.size)
        assertEquals("o1", e.options[0].optionId)
        assertFalse(e.options[0].isReject)
        assertTrue(e.options[1].isReject)
        // No `meta`: every agent below codex 1.1.8 / claude 0.64.1, and every
        // snapshot the server took before it carried the field.
        assertNull(e.options[0].meta)
    }

    @Test
    fun `permission option carries its meta through verbatim`() {
        val e = event(
            """{"type":"permission_request","request_id":"r1","tool_call":{"kind":"execute"},
               "options":[{"option_id":"o1","name":"Always Allow","kind":"allow_always",
                           "meta":{"permission":{"version":1,"changes":[
                             {"description":"Allow all Bash calls",
                              "lifetime":{"scope":"persistent","storage":"project"}}]}}}]}""",
        ) as AcpEvent.PermissionRequest
        // Opaque passthrough: the card parses it, the wire layer must not reshape it.
        assertEquals(
            json.parseToJsonElement(
                """{"permission":{"version":1,"changes":[
                   {"description":"Allow all Bash calls",
                    "lifetime":{"scope":"persistent","storage":"project"}}]}}""",
            ),
            e.options[0].meta,
        )
    }

    @Test
    fun `a reattach snapshot recovers the pending permission's grants`() {
        // The snapshot path swallows a decode failure into an empty option list
        // — a regression there would silently strip the buttons off a card the
        // user is being asked to answer, with nothing in the log.
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","pending_permission":{"request_id":"r1",
                     "tool_call":{"kind":"execute"},
                     "options":[
                       {"option_id":"o1","name":"Always Allow","kind":"allow_always",
                        "meta":{"permission":{"version":1,"changes":[
                          {"description":"Allow all Bash calls","lifetime":{"scope":"session"}}]}}},
                       {"option_id":"o2","name":"Deny","kind":"reject_once","meta":7}]}}""",
            ),
            json,
        )
        val options = snap.pendingPermission!!.options
        assertEquals(2, options.size)
        assertEquals(
            json.parseToJsonElement(
                """{"permission":{"version":1,"changes":[
                   {"description":"Allow all Bash calls","lifetime":{"scope":"session"}}]}}""",
            ),
            options[0].meta,
        )
        // A primitive `meta` must not take the whole list down with it.
        assertEquals("o2", options[1].optionId)
    }

    @Test
    fun `a permission option whose meta is not an object still decodes`() {
        // `meta` is an untyped serde_json::Value upstream. Failing the row here
        // would blank out the buttons and strand the turn.
        val e = event(
            """{"type":"permission_request","request_id":"r1","tool_call":{"kind":"execute"},
               "options":[{"option_id":"o1","name":"Allow","kind":"allow_once","meta":"unexpected"}]}""",
        ) as AcpEvent.PermissionRequest
        assertEquals(1, e.options.size)
        assertEquals("o1", e.options[0].optionId)
    }

    @Test
    fun `question request decodes nested questions`() {
        val e = event(
            """{"type":"question_request","question_id":"q1",
               "questions":[{"id":"a","question":"Which?","header":"H","multi_select":false,
                             "options":[{"label":"X","description":"d"}]}]}""",
        ) as AcpEvent.QuestionRequest
        assertEquals("q1", e.questionId)
        assertEquals(1, e.questions.size)
        assertEquals("Which?", e.questions[0].question)
        assertEquals("X", e.questions[0].options[0].label)
    }

    @Test
    fun `a question restricted to its options and a secret one decode their flags`() {
        val e = event(
            """{"type":"question_request","question_id":"q1",
               "questions":[
                 {"id":"a","question":"Pick","options":[{"label":"X"},{"label":"Y"}],"allow_other":false},
                 {"id":"b","question":"API key?","options":[],"is_secret":true}]}""",
        ) as AcpEvent.QuestionRequest
        assertFalse(e.questions[0].allowOther)
        assertFalse(e.questions[0].acceptsFreeText)
        // Absent means allowed, as on the server (`default_true`).
        assertTrue(e.questions[1].allowOther)
        assertTrue(e.questions[1].isSecret)
        assertTrue(e.questions[1].freeTextOnly)
    }

    @Test
    fun `turn complete defaults stop reason`() {
        assertEquals(AcpEvent.TurnComplete("end_turn"), event("""{"type":"turn_complete"}"""))
    }

    @Test
    fun `unknown event type is preserved, not thrown`() {
        assertEquals(AcpEvent.Unknown("bananas"), event("""{"type":"bananas","x":1}"""))
    }

    @Test
    fun `config options push decodes the catalog and its current values`() {
        val e = event(
            """{"type":"session_config_options","config_options":[
                 {"id":"effort","name":"Reasoning Effort","kind":{"type":"select",
                  "current_value":"high","options":[{"value":"low","name":"Low"},
                                                    {"value":"high","name":"High"}]}},
                 {"id":"auto_approve","name":"Auto-approve tools","description":"Skip prompts",
                  "kind":{"type":"boolean","current_value":true}}]}""",
        ) as AcpEvent.SessionConfigOptions
        assertEquals(2, e.configOptions.size)
        assertEquals("Reasoning Effort", e.configOptions[0].name)
        assertEquals("high", e.configOptions[0].kind.currentValue)
        assertEquals(listOf("low", "high"), e.configOptions[0].kind.options.map { it.value })
        // A boolean's value arrives as a real JSON `true` and lands as the string the rest of
        // the client speaks — the same asymmetry the snapshot path relies on.
        assertTrue(e.configOptions[1].kind.isBoolean)
        assertEquals("true", e.configOptions[1].kind.currentValue)
    }

    @Test
    fun `an empty config options push is a real empty catalog`() {
        // The decoder stays faithful; whether an empty list means "nothing to configure" or
        // "the real options are still coming" is decided by the consumer, from `selectors_ready`.
        assertEquals(
            AcpEvent.SessionConfigOptions(emptyList()),
            event("""{"type":"session_config_options","config_options":[]}"""),
        )
    }

    @Test
    fun `an absent or unreadable config options push is dropped, not read as empty`() {
        // The consumer adopts an empty catalog wholesale, so collapsing "one option failed to
        // parse" onto it would clear every control off the options sheet.
        assertEquals(AcpEvent.Unknown("session_config_options"), event("""{"type":"session_config_options"}"""))
        assertEquals(
            AcpEvent.Unknown("session_config_options"),
            event("""{"type":"session_config_options","config_options":[{"name":"no id"}]}"""),
        )
        assertEquals(
            AcpEvent.Unknown("session_config_options"),
            event("""{"type":"session_config_options","config_options":{"id":"not a list"}}"""),
        )
    }

    @Test
    fun `selectors ready decodes as the latch it is`() {
        assertEquals(AcpEvent.SelectorsReady, event("""{"type":"selectors_ready"}"""))
    }

    @Test
    fun `modes push decodes the catalog and the current mode`() {
        val e = event(
            """{"type":"session_modes","modes":{"current_mode_id":"plan",
                 "available_modes":[{"id":"default","name":"Default"},
                                    {"id":"plan","name":"Plan","description":"Read only"}]}}""",
        ) as AcpEvent.SessionModes
        assertEquals("plan", e.modes.currentModeId)
        assertEquals(listOf("default", "plan"), e.modes.availableModes.map { it.id })
        assertEquals("Read only", e.modes.availableModes[1].description)
    }

    @Test
    fun `a modes push with no modes object is dropped`() {
        assertEquals(AcpEvent.Unknown("session_modes"), event("""{"type":"session_modes"}"""))
    }

    @Test
    fun `mode changed decodes the new mode id`() {
        assertEquals(
            AcpEvent.ModeChanged("plan"),
            event("""{"type":"mode_changed","mode_id":"plan"}"""),
        )
    }

    @Test
    fun `an attach snapshot carries the connection's selectors`() {
        // The only recovery path for pushes missed while the socket was down: a reconnect too
        // far behind to replay gets this instead, and the missed events are never re-sent.
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","current_mode":"stale","selectors_ready":true,
                    "modes":{"current_mode_id":"plan",
                             "available_modes":[{"id":"default"},{"id":"plan"}]},
                    "config_options":[{"id":"auto_approve","name":"Auto-approve tools",
                                       "kind":{"type":"boolean","current_value":true}}]}""",
            ),
            json,
        )
        // Read from `modes.current_mode_id`, which the server keeps in sync for exactly this
        // reason — never from the separate (and here deliberately contradictory) `current_mode`.
        assertEquals("plan", snap.modes?.currentModeId)
        assertEquals(listOf("default", "plan"), snap.modes?.availableModes?.map { it.id })
        assertEquals("true", snap.configOptions?.single()?.kind?.currentValue)
        assertTrue(snap.selectorsReady)
    }

    @Test
    fun `a snapshot that publishes no selectors reads as absent, not empty`() {
        // Absent means "nothing to adopt, keep what is held"; an empty list would mean "the
        // agent has nothing to configure" and would clear the sheet.
        val snap = LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c1"}"""), json)
        assertNull(snap.modes)
        assertNull(snap.configOptions)
        // Nothing published yet is also the state in which an empty catalog can't be trusted.
        assertFalse(snap.selectorsReady)
        val empty = LiveSessionSnapshot.fromWire(
            obj("""{"connection_id":"c1","config_options":[],"selectors_ready":true}"""),
            json,
        )
        assertEquals(emptyList<SessionConfigOption>(), empty.configOptions)
    }

    @Test
    fun `a mode changed naming no mode is dropped`() {
        // Adopting an empty id would CLEAR the picker's selection rather than move it.
        assertEquals(AcpEvent.Unknown("mode_changed"), event("""{"type":"mode_changed","mode_id":""}"""))
        assertEquals(AcpEvent.Unknown("mode_changed"), event("""{"type":"mode_changed"}"""))
    }

    @Test
    fun `envelope is flattened with seq and connection id`() {
        val env = EventEnvelope.fromWire(
            obj("""{"seq":5,"connection_id":"conn-1","type":"content_delta","text":"x"}"""),
            json,
        )
        assertEquals(5L, env.seq)
        assertEquals("conn-1", env.connectionId)
        assertEquals(AcpEvent.ContentDelta("x"), env.event)
    }

    @Test
    fun `tool call carries the agent's opaque meta`() {
        val e = event(
            """{"type":"tool_call","tool_call_id":"t1","title":"Context compacting","kind":"other",
               "status":"in_progress","meta":{"contextCompaction":true,"tokensBefore":120000}}""",
        ) as AcpEvent.ToolCall
        assertEquals(true, e.meta?.get("contextCompaction")?.toString()?.toBoolean())
        // A payload with no meta at all decodes to null (= "unchanged" on an update).
        val bare = event("""{"type":"tool_call_update","tool_call_id":"t1","status":"completed"}""") as AcpEvent.ToolCallUpdate
        assertEquals(null, bare.meta)
    }

    @Test
    fun `plan approval request and resolved decode`() {
        val req = event(
            """{"type":"plan_approval_request","approval_id":"a1","tool_call_id":"t9",
               "plan_markdown":"# Plan\n- step"}""",
        )
        assertEquals(AcpEvent.PlanApprovalRequest("a1", "t9", "# Plan\n- step"), req)
        assertEquals(AcpEvent.PlanApprovalResolved("a1"), event("""{"type":"plan_approval_resolved","approval_id":"a1"}"""))
        // An empty/missing plan still opens the approval surface — the card shows a notice.
        val empty = event("""{"type":"plan_approval_request","approval_id":"a2"}""") as AcpEvent.PlanApprovalRequest
        assertEquals("", empty.planMarkdown)
        assertEquals("", empty.toolCallId)
    }

    @Test
    fun `snapshot carries a pending plan approval`() {
        val snap = LiveSessionSnapshot.fromWire(
            obj(
                """{"connection_id":"c1","status":"prompting",
                   "pending_plan_approval":{"approval_id":"a1","tool_call_id":"t9","plan_markdown":"body"}}""",
            ),
            json,
        )
        assertEquals("a1", snap.pendingPlanApproval?.approvalId)
        assertEquals("t9", snap.pendingPlanApproval?.toolCallId)
        assertEquals("body", snap.pendingPlanApproval?.planMarkdown)
        // Absent key ⇒ null, which the VM treats as "clear any stale card".
        assertEquals(null, LiveSessionSnapshot.fromWire(obj("""{"connection_id":"c1"}"""), json).pendingPlanApproval)
    }

    @Test
    fun `plan approval decision serializes snake_case`() {
        assertEquals("request_changes", PlanApprovalDecision.REQUEST_CHANGES.wire)
        assertEquals(
            """{"connectionId":"c1","approvalId":"a1","answer":{"decision":"request_changes","feedback":"more tests"}}""",
            CodegJson.request.encodeToString(
                AnswerPlanApprovalBody.serializer(),
                AnswerPlanApprovalBody("c1", "a1", PlanApprovalAnswer(PlanApprovalDecision.REQUEST_CHANGES, "more tests")),
            ),
        )
        // `explicitNulls = false` on the request codec drops an absent feedback.
        assertFalse(
            CodegJson.request.encodeToString(
                PlanApprovalAnswer.serializer(),
                PlanApprovalAnswer(PlanApprovalDecision.APPROVE, null),
            ).contains("feedback"),
        )
    }
}

/** The 0.44 permission-queue and approval events. */
class AcpEventApprovalTest {

    private val json: Json = CodegJson.response

    private fun event(raw: String): AcpEvent = AcpEvent.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    @Test
    fun `a request says how many wait behind it, and the depth moves alone`() {
        val request = event("""{"type":"permission_request","request_id":"r","tool_call":{},"options":[],"queued":2}""") as AcpEvent.PermissionRequest
        assertEquals(2, request.queued)
        assertEquals(0, (event("""{"type":"permission_request","request_id":"r","tool_call":{},"options":[]}""") as AcpEvent.PermissionRequest).queued)
        assertEquals(AcpEvent.PermissionQueueDepth(3), event("""{"type":"permission_queue_depth","depth":3}"""))
    }

    @Test
    fun `an approval record decodes, an unreadable one is dropped`() {
        val approval = event(
            """{"type":"tool_approval","approval":{"tool_call_id":"t","option_kind":"allow_once","option_name":"Yes","origin":"user","decided_at":"2026-11-14T03:00:00Z"}}""",
        ) as AcpEvent.ToolApproval
        assertEquals(ToolApprovalInfo("t", "allow_once", "Yes", "user", "2026-11-14T03:00:00Z"), approval.approval)
        assertTrue(event("""{"type":"tool_approval","approval":{"option_kind":"allow_once"}}""") is AcpEvent.Unknown)
    }

    @Test
    fun `the snapshot carries the queue depth and the recent approvals`() {
        val snap = LiveSessionSnapshot.fromWire(
            json.parseToJsonElement(
                """{"pending_permission":{"request_id":"r","tool_call":{},"options":[],"queued":1},
                   "tool_approvals":[{"tool_call_id":"t","option_kind":"reject_once","decided_at":"2026-11-14T03:00:00Z"},{"bogus":1}]}""",
            ).jsonObject,
            json,
        )
        assertEquals(1, snap.pendingPermission?.queued)
        assertEquals(listOf("t"), snap.toolApprovals.map { it.toolCallId })
    }
}

/** The retry line's two sources and the AIR failure upsert. */
class AcpEventRetryTest {

    private val json: Json = CodegJson.response

    private fun event(raw: String): AcpEvent = AcpEvent.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    @Test
    fun `codex reports a cause, pi only counters`() {
        val codex = event("""{"type":"turn_retrying","message":"stream disconnected","error_status":503}""") as AcpEvent.ApiRetry
        assertEquals("stream disconnected", codex.message)
        assertEquals(503L, codex.errorStatus)
        assertTrue(codex.reportsError)
        assertNull(codex.attempt)
        val pi = event("""{"type":"turn_retrying","message":"","attempt":2,"max_retries":5,"retry_delay_ms":4000}""") as AcpEvent.ApiRetry
        assertNull(pi.message)
        assertFalse(pi.reportsError)
        assertEquals(2L, pi.attempt)
        assertEquals(4000L, pi.retryDelayMs)
    }

    @Test
    fun `claude's api_retry is read off the SDK stream, nothing else is`() {
        val retry = event(
            """{"type":"claude_sdk_message","session_id":"s","message":{"type":"system","subtype":"api_retry","attempt":"1","max_retries":10,"error":"overloaded","error_status":529,"retry_delay_ms":1200}}""",
        ) as AcpEvent.ApiRetry
        assertEquals(1L, retry.attempt)
        assertEquals("overloaded", retry.message)
        assertTrue(retry.reportsError)
        assertTrue(event("""{"type":"claude_sdk_message","session_id":"s","message":{"type":"system","subtype":"init"}}""") is AcpEvent.Unknown)
    }

    @Test
    fun `a failure record decodes, and one without an id or revision is dropped`() {
        val failure = event(
            """{"type":"session_failure","record":{"id":"f","revision":3,"category":"limit","severity":"error","title":"Rate limited","actions":["retry"],"resolved":false}}""",
        ) as AcpEvent.SessionFailure
        assertEquals(SessionFailureRecord("f", 3, "limit", "error", "Rate limited", null, listOf("retry")), failure.record)
        assertTrue(event("""{"type":"session_failure","record":{"id":"f","revision":0}}""") is AcpEvent.Unknown)
        assertTrue(event("""{"type":"session_failure","record":{"revision":1}}""") is AcpEvent.Unknown)
    }
}

/** The 0.44 advisories and the session-load failure. */
class AcpEventNoticeTest {

    private val json: Json = CodegJson.response

    private fun event(raw: String): AcpEvent = AcpEvent.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    @Test
    fun `advisories decode`() {
        assertEquals(
            AcpEvent.ConfigOptionRejected("thought_level", "Thinking", "High", "Off", "high", "off"),
            event("""{"type":"config_option_rejected","config_id":"thought_level","option_name":"Thinking","requested":"High","actual":"Off","requested_value":"high","actual_value":"off"}"""),
        )
        assertEquals(
            AcpEvent.SessionNotice("warning", "Model fallback", null),
            event("""{"type":"session_notice","notice":{"severity":"warning","title":"Model fallback"}}"""),
        )
        val plugins = event("""{"type":"plugin_load_failures","failures":[{"plugin":"a@m","kind":"generic-error","message":"boom"},{"bad":1}]}""") as AcpEvent.PluginLoadFailures
        assertEquals(listOf("a@m"), plugins.failures.map { it.plugin })
        assertEquals(AcpEvent.SessionConfigStale(true, "model_provider"), event("""{"type":"session_config_stale","stale":true,"kind":"model_provider"}"""))
        assertEquals(AcpEvent.SessionConfigStale(false, null), event("""{"type":"session_config_stale","stale":false}"""))
    }

    @Test
    fun `a session load failure carries its code`() {
        assertEquals(
            AcpEvent.SessionLoadFailed("s-1", "Resource not found", "resource_not_found"),
            event("""{"type":"session_load_failed","session_id":"s-1","message":"Resource not found","code":"resource_not_found"}"""),
        )
    }

    @Test
    fun `the snapshot carries a stale config`() {
        val snap = LiveSessionSnapshot.fromWire(json.parseToJsonElement("""{"config_stale":true,"config_stale_kind":"agent_config"}""").jsonObject, json)
        assertTrue(snap.configStale)
        assertEquals("agent_config", snap.configStaleKind)
        assertFalse(LiveSessionSnapshot.fromWire(json.parseToJsonElement("{}").jsonObject, json).configStale)
    }
}

/** The predicted next prompt, live and on the snapshot. */
class AcpEventPromptSuggestionTest {

    private val json: Json = CodegJson.response

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a prediction is trimmed, and a blank or null one withdraws it`() {
        assertEquals(AcpEvent.PromptSuggestion("run the tests"), AcpEvent.fromWire(obj("""{"type":"prompt_suggestion","text":"  run the tests "}"""), json))
        assertEquals(AcpEvent.PromptSuggestion(null), AcpEvent.fromWire(obj("""{"type":"prompt_suggestion","text":"  "}"""), json))
        assertEquals(AcpEvent.PromptSuggestion(null), AcpEvent.fromWire(obj("""{"type":"prompt_suggestion"}"""), json))
        assertEquals("commit it", LiveSessionSnapshot.fromWire(obj("""{"prompt_suggestion":"commit it"}"""), json).promptSuggestion)
        assertNull(LiveSessionSnapshot.fromWire(obj("{}"), json).promptSuggestion)
    }
}

/** Background work: async tasks, the transcript watcher's activity, delegated children. */
class AcpEventBackgroundTest {

    private val json: Json = CodegJson.response

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `an async task delta decodes`() {
        val e = AcpEvent.fromWire(obj("""{"type":"async_task","delta":{"task_id":"t","spawned":true,"name":"dev server","task_type":"shell","can_stop":true,"usage":{"total_tokens":12,"tool_uses":1,"duration_ms":3}}}"""), json) as AcpEvent.AsyncTask
        assertEquals(AsyncTaskDelta("t", spawned = true, name = "dev server", taskType = "shell", canStop = true, usage = AsyncTaskUsage(12, 1, 3)), e.delta)
        assertTrue(AcpEvent.fromWire(obj("""{"type":"async_task","delta":{"spawned":true}}"""), json) is AcpEvent.Unknown)
    }

    @Test
    fun `background activity carries turns, the outstanding set and what settled`() {
        val e = AcpEvent.fromWire(
            obj(
                """{"type":"background_activity","session_id":"s","outstanding":1,"watermark":4096,
                   "turns":[{"id":"bg-0-0","role":"assistant","blocks":[{"type":"text","text":"done"}],"timestamp":"2026-11-14T03:00:00Z"},{"id":"broken"}],
                   "outstanding_tasks":[{"task_id":"a","kind":"agent","tool_use_id":"call-1","started_at_ms":7}],
                   "settled":[{"task_id":"b","status":"completed","summary":"ok","tool_use_id":"call-2"}]}""",
            ),
            json,
        ) as AcpEvent.BackgroundActivity
        assertEquals(listOf("bg-0-0"), e.turns.map { it.id })
        assertEquals(BackgroundOutstandingTask("a", "agent", "call-1", null, 7), e.outstandingTasks.single())
        assertEquals(BackgroundSettledInfo("b", "completed", "ok", "call-2"), e.settled.single())
        assertEquals(4096L, e.watermark)
    }

    @Test
    fun `delegated children ride the event and the snapshot`() {
        assertEquals(AcpEvent.DelegationChildRunning("c1", true), AcpEvent.fromWire(obj("""{"type":"delegation_child_running","child_connection_id":"c1","running":true}"""), json))
        val snap = LiveSessionSnapshot.fromWire(
            obj("""{"delegated_children":["c1"],"async_tasks":[{"task_id":"t","name":"x","task_type":"shell","description":"","show_in_transcript":true,"can_stop":false,"state":"running"}],"background_outstanding_tasks":[{"task_id":"a","kind":"agent","started_at_ms":0}]}"""),
            json,
        )
        assertEquals(listOf("c1"), snap.delegatedChildren)
        assertEquals(listOf("t"), snap.asyncTasks.map { it.taskId })
        assertEquals(listOf("a"), snap.backgroundOutstandingTasks.map { it.taskId })
    }
}

/** A delegation's binding, live and on the snapshot. */
class AcpEventDelegationTest {

    private val json: Json = CodegJson.response

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a delegation starts and completes`() {
        val started = AcpEvent.fromWire(
            obj("""{"type":"delegation_started","parent_connection_id":"p","parent_tool_use_id":"call-1","child_connection_id":"c","child_conversation_id":12,"agent_type":"codex","task_preview":"Port it","task_id":"t-1","assistant_name":"Reviewer"}"""),
            json,
        ) as AcpEvent.DelegationStarted
        assertEquals(DelegationBinding("call-1", "c", 12, "codex", DelegationBinding.Status.RUNNING, "Port it", "t-1", "Reviewer"), started.delegation)
        val ok = AcpEvent.fromWire(
            obj("""{"type":"delegation_completed","parent_connection_id":"p","parent_tool_use_id":"call-1","child_connection_id":"c","child_conversation_id":12,"agent_type":"codex","result":{"kind":"ok","duration_ms":61000}}"""),
            json,
        ) as AcpEvent.DelegationCompleted
        assertEquals(DelegationBinding.Status.OK, ok.delegation.status)
        assertEquals(61000L, ok.delegation.durationMs)
        val err = AcpEvent.fromWire(
            obj("""{"type":"delegation_completed","parent_tool_use_id":"call-1","child_connection_id":"c","child_conversation_id":12,"agent_type":"codex","result":{"kind":"err","error_code":"timeout"}}"""),
            json,
        ) as AcpEvent.DelegationCompleted
        assertEquals("timeout", err.delegation.errorCode)
        assertTrue(AcpEvent.fromWire(obj("""{"type":"delegation_started","child_connection_id":"c"}"""), json) is AcpEvent.Unknown)
    }

    @Test
    fun `the snapshot recovers the running delegations`() {
        val snap = LiveSessionSnapshot.fromWire(
            obj("""{"active_delegations":[{"parent_tool_use_id":"call-1","child_connection_id":"c","child_conversation_id":12,"agent_type":"codex","task_preview":"","task_id":""}]}"""),
            json,
        )
        val d = snap.activeDelegations.single()
        assertEquals("call-1", d.parentToolUseId)
        assertNull("an empty preview is none", d.task)
        assertEquals(DelegationBinding.Status.RUNNING, d.status)
    }
}

/** The goal-control vocabulary is three-valued on the snapshot. */
class AcpEventGoalActionsTest {

    private val json: Json = CodegJson.response

    private fun snap(raw: String) = LiveSessionSnapshot.fromWire(json.parseToJsonElement(raw).jsonObject, json)

    @Test
    fun `a list is known, null is not yet, absent is a server too old to say`() {
        assertEquals(listOf("set", "clear"), snap("""{"goal_actions":["set","clear"]}""").goalActions)
        assertNull(snap("""{"goal_actions":null}""").goalActions)
        assertEquals(LiveSessionSnapshot.LEGACY_GOAL_ACTIONS, snap("{}").goalActions)
    }
}
