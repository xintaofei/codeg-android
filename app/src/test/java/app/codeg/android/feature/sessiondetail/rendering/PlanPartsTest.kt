package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.core.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Plan checklists read off calls, and codex's `<proposed_plan>` documents lifted out of prose. */
class PlanPartsTest {

    @Test
    fun `a checklist reads from a call's todos, entries or plan`() {
        val entries = PlanParse.parseTodos(
            """{"todos":[{"content":"Read the code","status":"done","priority":"urgent"},{"step":"Fix it","status":"running"},{"content":"  "}]}""",
        )
        assertEquals(
            listOf(PlanEntry("Read the code", "high", "completed"), PlanEntry("Fix it", "medium", "in_progress")),
            entries,
        )
        // codex's update_plan
        assertEquals(listOf(PlanEntry("Ship", "medium", "pending")), PlanParse.parseTodos("""{"plan":[{"step":"Ship"}]}"""))
        // ExitPlanMode's plan is a document, not a checklist.
        assertTrue(PlanParse.parseTodos("""{"plan":"# The plan"}""").isEmpty())
        assertTrue(PlanParse.isPlanLikeToolName("TodoWrite"))
        assertTrue(PlanParse.isPlanLikeToolName("todo_list"))
        assertTrue(PlanParse.isPlanLikeToolName("update_plan"))
        assertFalse(PlanParse.isPlanLikeToolName("Read"))
        // What a hand-off writes reads back the same.
        assertEquals(entries, PlanParse.parseTodos(PlanParse.handoffInput(entries)))
    }

    private fun turn(vararg blocks: ContentBlock) = MessageTurn("a1", TurnRole.ASSISTANT, blocks.toList(), Instant.EPOCH)

    @Test
    fun `a persisted plan call draws as the plan, its result consumed`() {
        val parts = MessageRender.adaptTurn(
            turn(
                ContentBlock.ToolUse("t1", "TodoWrite", """{"todos":[{"content":"A","status":"pending"}]}"""),
                ContentBlock.ToolResult("t1", "Todos have been modified successfully", false),
                ContentBlock.ToolUse("t2", "TodoWrite", """{"todos":[]}"""),
                ContentBlock.ToolResult("t2", "ok", false),
            ),
        )
        assertEquals(2, parts.size)
        assertEquals(listOf(PlanEntry("A")), (parts[0] as RenderPart.Plan).entries)
        // One with nothing to show stays a call.
        assertTrue(parts[1] is RenderPart.Tool)
        // Neither the plan nor a todo call is a step of the header's.
        assertEquals(0, summarizeActivity(parts).steps)
        assertFalse(isAnswerPart(parts[0]))
    }

    @Test
    fun `a proposed plan lifts out of the prose around it`() {
        val parts = ProposedPlan.expand("Here it is.\n<proposed_plan>\n# Plan\n- one\n</proposed_plan>\nOK?", streaming = false)!!
        assertEquals(
            listOf(RenderPart.Text("Here it is.\n"), RenderPart.ProposedPlan("# Plan\n- one", false), RenderPart.Text("\nOK?")),
            parts,
        )
        assertTrue(isAnswerPart(parts[1]))
        // Quoted as code, or named mid-line, the tag is prose.
        assertNull(ProposedPlan.expand("Wrap it in `<proposed_plan>` tags.", streaming = false))
        assertNull(ProposedPlan.expand("```\n<proposed_plan>\nx\n```", streaming = false))
        assertNull(ProposedPlan.expand("I will write a <proposed_plan> block", streaming = false))
        // Unclosed: a card being written while the reply streams, the rest of the text once settled.
        assertEquals(listOf(RenderPart.ProposedPlan("", true)), ProposedPlan.expand("<proposed_plan>\n", streaming = true))
        assertEquals(listOf(RenderPart.ProposedPlan("draft", false)), ProposedPlan.expand("<proposed_plan>\ndraft", streaming = false))
        // An empty settled block draws nothing, and its tags don't either.
        assertEquals(emptyList<RenderPart>(), ProposedPlan.expand("<proposed_plan></proposed_plan>", streaming = false))
    }

    @Test
    fun `persisted text gets the same cards`() {
        val parts = MessageRender.adaptTurn(turn(ContentBlock.Text("<proposed_plan>\nDo it\n</proposed_plan>")))
        assertEquals(listOf(RenderPart.ProposedPlan("Do it", false)), parts)
    }
}
