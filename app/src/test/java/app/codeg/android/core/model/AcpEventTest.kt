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
