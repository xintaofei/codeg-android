package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.ToolApprovalInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approval chip's sources and how they combine (web `approval.test.ts`, `use-live-step.ts`). */
class ApprovalTest {

    private fun info(id: String, kind: String, at: String = "2026-11-14T03:00:00Z") =
        ToolApprovalInfo(id, kind, "Always Allow", "user", at)

    private fun meta(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun vm(id: String, state: ToolCallState, output: String? = null, meta: JsonObject? = null) =
        ToolCallVM.of(id, "Bash", "execute", state, """{"command":"ls"}""", output, null, state == ToolCallState.ERROR, meta)

    @Test
    fun `a detail load's stamp reads as the call's approval`() {
        val stamp = meta("""{"codeg.approval":{"option_kind":"allow_always","option_name":"Always","origin":"chat_channel","decided_at":"2026-11-14T03:00:00Z"}}""")
        val approval = ToolApprovals.fromMeta(stamp)!!
        assertEquals(ApprovalDecision.ALLOW_ALWAYS, approval.decision)
        assertEquals("chat_channel", approval.origin)
        assertEquals(approval, vm("t", ToolCallState.DONE, meta = stamp).approval)
        // Both reject kinds turned the call away; an unknown kind draws nothing.
        assertEquals(ApprovalDecision.REJECTED, ToolApprovals.fromMeta(meta("""{"codeg.approval":{"option_kind":"reject_always"}}"""))?.decision)
        assertNull(ToolApprovals.fromMeta(meta("""{"codeg.approval":{"option_kind":"maybe"}}""")))
        assertNull(ToolApprovals.fromMeta(meta("""{"codeg.approval":"allow_once"}""")))
    }

    @Test
    fun `records fold one per call, the later decision winning, in any order`() {
        val first = info("a", "allow_once", "2026-11-14T03:00:00Z")
        val later = info("a", "reject_once", "2026-11-14T03:00:05.123456Z")
        val one = ToolApprovals.merge(emptyMap(), listOf(first))
        val two = ToolApprovals.merge(one, listOf(later))
        assertEquals("reject_once", two.getValue("a").optionKind)
        // An older snapshot can't walk the newer answer back, and changes nothing at all.
        assertSame(two, ToolApprovals.merge(two, listOf(first)))
        assertSame(two, ToolApprovals.merge(two, emptyList()))
        assertEquals(setOf("a", "b"), ToolApprovals.merge(two, listOf(info("b", "allow_once"))).keys)
    }

    @Test
    fun `the live record outranks the stamp, and a pending request marks its call`() {
        val stamp = meta("""{"codeg.approval":{"option_kind":"allow_once"}}""")
        val live = ActivityLive(approvals = mapOf("t" to info("t", "allow_always")))
        assertEquals(ApprovalDecision.ALLOW_ALWAYS, liveToolStep(vm("t", ToolCallState.DONE, meta = stamp), live).approval?.decision)
        assertEquals(ApprovalDecision.ALLOW_ONCE, liveToolStep(vm("t", ToolCallState.DONE, meta = stamp), ActivityLive()).approval?.decision)

        val waiting = ActivityLive(awaitingToolCallId = "t", awaitingUser = true)
        assertTrue(liveToolStep(vm("t", ToolCallState.RUNNING), waiting).awaiting)
        assertFalse("another call", liveToolStep(vm("u", ToolCallState.RUNNING), waiting).awaiting)
        assertFalse("a settled call waits on nobody", liveToolStep(vm("t", ToolCallState.DONE), waiting).awaiting)
    }

    @Test
    fun `a declined call reads declined once, not twice`() {
        val rejected = ActivityLive(approvals = mapOf("t" to info("t", "reject_once")))
        val failed = liveToolStep(vm("t", ToolCallState.ERROR, output = "exit 1"), rejected)
        assertTrue(failed.declined)
        assertFalse("its end column already says it", failed.showsApproval)
        // Claude's own decline wording reads declined too.
        val worded = liveToolStep(vm("t", ToolCallState.ERROR, output = "The user doesn't want to proceed with this tool use."), ActivityLive())
        assertTrue(worded.declined)
        // An approved call that failed afterwards keeps both.
        val approved = liveToolStep(vm("t", ToolCallState.ERROR, output = "exit 1"), ActivityLive(approvals = mapOf("t" to info("t", "allow_once"))))
        assertFalse(approved.declined)
        assertTrue(approved.showsApproval)
    }
}
