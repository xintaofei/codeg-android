package app.codeg.android.feature.sessiondetail.interactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collapse rules of the web's `ask-question-card.test.tsx` ("AskQuestionCard collapse",
 *  codeg 0.44), as iOS ports them (`AskQuestionFoldTests`). */
class AskQuestionFoldTest {

    @Test
    fun `opens by default`() {
        assertFalse(AskQuestionFold().isCollapsed("q-1"))
    }

    @Test
    fun `the toggle folds and unfolds the same set`() {
        val folded = AskQuestionFold().toggle("q-1")
        assertTrue(folded.isCollapsed("q-1"))
        assertFalse(folded.toggle("q-1").isCollapsed("q-1"))
    }

    @Test
    fun `re-rendering the same set keeps it folded`() {
        assertTrue(AskQuestionFold().toggle("q-1").present("q-1").isCollapsed("q-1"))
    }

    @Test
    fun `a different question set reads as open from its first render`() {
        // No `present` yet: the new set must not render folded even for a frame.
        assertFalse(AskQuestionFold().toggle("q-1").isCollapsed("q-2"))
    }

    @Test
    fun `a replaced set does not resurface folded if it comes back`() {
        val swapped = AskQuestionFold().toggle("q-1").present("q-2")
        assertFalse(swapped.isCollapsed("q-2"))
        assertFalse(swapped.present("q-1").isCollapsed("q-1"))
    }

    @Test
    fun `folding a replacement set works on its own id`() {
        assertTrue(AskQuestionFold().toggle("q-1").present("q-2").toggle("q-2").isCollapsed("q-2"))
    }

    @Test
    fun `a failed submit opens the card again`() {
        assertFalse(AskQuestionFold().toggle("q-1").submitFailed().isCollapsed("q-1"))
    }
}
