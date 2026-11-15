package app.codeg.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownFence]: which renderer a fenced block goes to, and when its source is final; and
 * the parser's `closed` flags it reads. The language match is the web's
 * `mermaidSourceFromPre` (`mermaid-view.test.ts`): `language-mermaid` exactly. Ported from
 * iOS `MarkdownFenceTests`.
 */
class MarkdownFenceTest {

    // region Language

    @Test
    fun `claims a mermaid fence by its language`() {
        assertTrue(MarkdownFence.isMermaid("mermaid"))
        // The language is the info string's first word.
        assertTrue(MarkdownFence.isMermaid("mermaid title=flow"))
        assertTrue(MarkdownFence.isMermaid("mermaid\textra"))
    }

    @Test
    fun `does not claim a look-alike language`() {
        assertFalse(MarkdownFence.isMermaid("mermaidx"))
        assertFalse(MarkdownFence.isMermaid("mermaid-lite"))
        assertFalse(MarkdownFence.isMermaid("ts"))
        assertFalse(MarkdownFence.isMermaid("Mermaid"))
        assertFalse(MarkdownFence.isMermaid(null))
        assertFalse(MarkdownFence.isMermaid(""))
    }

    @Test
    fun `leaves PlantUML as code`() {
        for (language in listOf("plantuml", "puml")) {
            assertEquals(FenceKind.Code, MarkdownFence.kind(language, "@startuml\nA -> B\n@enduml", closed = true, settled = true, assistantReply = true))
        }
    }

    // endregion

    // region Settling

    @Test
    fun `every fence is final once the text stops streaming`() {
        assertTrue(MarkdownFence.isSettled(closed = true, streaming = false, isLastBlock = true, closingLineEnded = false))
        // A reply that ended mid-fence: its fence never closes, and is final.
        assertTrue(MarkdownFence.isSettled(closed = false, streaming = false, isLastBlock = true, closingLineEnded = false))
    }

    @Test
    fun `an open fence is not final while streaming`() {
        assertFalse(MarkdownFence.isSettled(closed = false, streaming = true, isLastBlock = true, closingLineEnded = true))
        assertFalse(MarkdownFence.isSettled(closed = false, streaming = true, isLastBlock = false, closingLineEnded = true))
    }

    @Test
    fun `a closing line still arriving is not final`() {
        // "```" received, its line not ended: it may yet be "````" or "```js".
        assertFalse(MarkdownFence.isSettled(closed = true, streaming = true, isLastBlock = true, closingLineEnded = false))
        assertTrue(MarkdownFence.isSettled(closed = true, streaming = true, isLastBlock = true, closingLineEnded = true))
    }

    @Test
    fun `a closed fence with text after it is final`() {
        assertTrue(MarkdownFence.isSettled(closed = true, streaming = true, isLastBlock = false, closingLineEnded = false))
    }

    // endregion

    // region Routing

    @Test
    fun `draws a diagram in any Markdown`() {
        for (reply in listOf(true, false)) {
            assertEquals(FenceKind.Mermaid(ready = true), MarkdownFence.kind("mermaid", "graph TD;", closed = true, settled = true, assistantReply = reply))
        }
    }

    @Test
    fun `holds a streaming diagram until its source is final`() {
        assertEquals(FenceKind.Mermaid(ready = false), MarkdownFence.kind("mermaid", "graph TD;\nA-->", closed = false, settled = false, assistantReply = true))
    }

    @Test
    fun `leaves other fences as code`() {
        assertEquals(FenceKind.Code, MarkdownFence.kind("swift", "let x = 1", closed = true, settled = true, assistantReply = true))
        assertEquals(FenceKind.Code, MarkdownFence.kind(null, "plain", closed = true, settled = true, assistantReply = true))
    }

    // endregion

    // region Parsed fences

    private fun fences(raw: String) = parseMarkdownBlocks(raw).filterIsInstance<MarkdownBlock.Code>()

    @Test
    fun `reads a backtick or tilde mermaid fence`() {
        for (raw in listOf("```mermaid\ngraph TD;\nA-->B;\n```", "~~~mermaid\ngraph TD;\nA-->B;\n~~~")) {
            val found = fences(raw).single()
            assertEquals("mermaid", found.language)
            assertEquals("graph TD;\nA-->B;", found.code)
            assertTrue(found.closed)
            // The text ends on the closing line: it may still grow.
            assertFalse(found.closingLineEnded)
            assertTrue(MarkdownFence.isMermaid(found.language))
        }
    }

    @Test
    fun `a closing line followed by a newline is ended`() {
        val found = fences("```mermaid\ngraph TD;\n```\n").single()
        assertTrue(found.closed)
        assertTrue(found.closingLineEnded)
    }

    @Test
    fun `a fence still arriving is open`() {
        val found = fences("Here:\n\n```mermaid\ngraph TD;\nA-->").single()
        assertFalse(found.closed)
        assertFalse(found.closingLineEnded)
    }

    @Test
    fun `routes a parsed block by where it stands in the stream`() {
        val closedAtEnd = fences("```mermaid\ngraph TD;\n```").single()
        // The last thing received while streaming: not yet.
        assertEquals(FenceKind.Mermaid(ready = false), MarkdownFence.kind(closedAtEnd, streaming = true, assistantReply = true, htmlEnabled = false))
        // Persisted, or no longer the tail: final.
        assertEquals(FenceKind.Mermaid(ready = true), MarkdownFence.kind(closedAtEnd, streaming = false, assistantReply = true, htmlEnabled = false))
        val html = fences("```html\n<p>x</p>\n```\n").single()
        assertEquals(FenceKind.HtmlPreview, MarkdownFence.kind(html, streaming = true, assistantReply = true, htmlEnabled = true))
        assertEquals(FenceKind.Code, MarkdownFence.kind(html, streaming = false, assistantReply = false, htmlEnabled = true))
    }

    @Test
    fun `the incremental parser agrees with a whole parse on fence flags`() {
        val text = "Intro\n\n```mermaid\ngraph TD;\nA-->B;\n```\n\nAfter"
        LiveBlockParser.forget("fence-test")
        val live = LiveBlockParser.parse("fence-test", text)
        assertEquals(parseMarkdownBlocks(text), live)
        LiveBlockParser.forget("fence-test")
    }

    // endregion
}
