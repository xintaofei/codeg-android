package app.codeg.android.feature.sessiondetail.interactive

import app.codeg.android.core.model.QuestionOption
import app.codeg.android.core.model.QuestionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The question card's answer rules: when a typed answer counts, and what gets sent. */
class AskQuestionAnswerTest {

    private val options = listOf(QuestionOption("X"), QuestionOption("Y"))
    private val other = OTHER_SELECTION_KEY

    @Test
    fun `a typed answer is sent only where one is accepted`() {
        val open = QuestionSpec("a", "Pick", options = options)
        val closed = QuestionSpec("b", "Pick", options = options, allowOther = false)
        val answer = buildAnswer(
            listOf(open, closed),
            mapOf("a" to setOf(other), "b" to setOf("X", other)),
            mapOf("a" to " mine ", "b" to "sneaked in"),
        )
        assertEquals(listOf("mine"), answer.answers[0].labels)
        // A restricted question never forwards free text: the server would cancel it.
        assertEquals(listOf("X"), answer.answers[1].labels)
    }

    @Test
    fun `a restricted question is answered only by picking an option`() {
        val closed = QuestionSpec("b", "Pick", options = options, allowOther = false)
        assertFalse(isAnswered(closed, setOf(other), "typed"))
        assertTrue(isAnswered(closed, setOf("Y"), ""))
    }

    @Test
    fun `an option-less question takes its typed answer without picking Other`() {
        val freeText = QuestionSpec("c", "API key?", options = emptyList(), isSecret = true, allowOther = false)
        assertTrue(freeText.acceptsFreeText)
        assertFalse(isAnswered(freeText, emptySet(), "  "))
        assertTrue(isAnswered(freeText, emptySet(), "sk-123"))
        assertEquals(listOf("sk-123"), buildAnswer(listOf(freeText), emptyMap(), mapOf("c" to "sk-123")).answers[0].labels)
    }
}
