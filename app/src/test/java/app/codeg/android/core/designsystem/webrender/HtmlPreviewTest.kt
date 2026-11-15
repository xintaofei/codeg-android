package app.codeg.android.core.designsystem.webrender

import app.codeg.android.core.designsystem.markdown.FenceKind
import app.codeg.android.core.designsystem.markdown.MarkdownFence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML preview's fence routing (closed ```html in an assistant reply, with the setting
 * on), its heights (`html-code-preview.tsx`), its cache keys and theme, and the per-source
 * view memory. Ported from iOS `HtmlPreviewTests`.
 */
class HtmlPreviewTest {

    private fun kind(
        language: String?,
        code: String = "<p>x</p>",
        closed: Boolean = true,
        settled: Boolean = true,
        reply: Boolean = true,
        enabled: Boolean = true,
    ) = MarkdownFence.kind(language, code, closed, settled, assistantReply = reply, htmlEnabled = enabled)

    // region Routing

    @Test
    fun `previews a closed html fence in a reply with the setting on`() {
        assertEquals(FenceKind.HtmlPreview, kind("html"))
        assertEquals(FenceKind.HtmlPreview, kind("htm"))
        assertEquals(FenceKind.HtmlPreview, kind("HTML"))
        assertEquals(FenceKind.HtmlPreview, kind("html title=card"))
    }

    @Test
    fun `leaves it as code with the setting off, or outside an assistant reply`() {
        assertEquals(FenceKind.Code, kind("html", enabled = false))
        assertEquals(FenceKind.Code, kind("html", reply = false))
    }

    @Test
    fun `leaves an open or unsettled fence as code`() {
        // Still streaming in.
        assertEquals(FenceKind.Code, kind("html", closed = false, settled = false))
        // Closed, but its closing line is still arriving.
        assertEquals(FenceKind.Code, kind("html", closed = true, settled = false))
        // The reply ended mid-fence: final, but never closed.
        assertEquals(FenceKind.Code, kind("html", closed = false, settled = true))
    }

    @Test
    fun `leaves an empty fence and other languages as code`() {
        assertEquals(FenceKind.Code, kind("html", "  \n\t"))
        assertEquals(FenceKind.Code, kind("html5"))
        assertEquals(FenceKind.Code, kind("xhtml"))
        assertEquals(FenceKind.Code, kind("vue"))
        assertEquals(FenceKind.Code, kind(null))
    }

    @Test
    fun `still draws mermaid wherever it is`() {
        assertEquals(FenceKind.Mermaid(ready = true), kind("mermaid", "graph TD;", reply = false, enabled = false))
    }

    // endregion

    // region Heights

    @Test
    fun `clamps the measured height to 96 through 12000`() {
        assertEquals(96f, HtmlPreviewMetrics.clamp(50.0))
        assertEquals(501f, HtmlPreviewMetrics.clamp(500.2))
        assertEquals(12_000f, HtmlPreviewMetrics.clamp(20_000.0))
        assertEquals(640f, HtmlPreviewMetrics.clamp(640.0))
    }

    @Test
    fun `keeps the default height when there is nothing to measure`() {
        assertEquals(160f, HtmlPreviewMetrics.clamp(0.0))
        assertEquals(160f, HtmlPreviewMetrics.clamp(-3.0))
        assertEquals(160f, HtmlPreviewMetrics.clamp(Double.NaN))
        assertEquals(160f, HtmlPreviewMetrics.clamp(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `shows at most 640 until show all`() {
        assertFalse(HtmlPreviewMetrics.overflows(640f))
        assertTrue(HtmlPreviewMetrics.overflows(641f))
        assertEquals(640f, HtmlPreviewMetrics.visibleHeight(1000f, expanded = false))
        assertEquals(1000f, HtmlPreviewMetrics.visibleHeight(1000f, expanded = true))
        assertEquals(300f, HtmlPreviewMetrics.visibleHeight(300f, expanded = false))
        assertEquals(300f, HtmlPreviewMetrics.visibleHeight(300f, expanded = true))
    }

    @Test
    fun `draws only the collapsed part until show all`() {
        assertEquals(640f, HtmlPreviewMetrics.pictureClip(expanded = false))
        assertEquals(12_000f, HtmlPreviewMetrics.pictureClip(expanded = true))
        assertEquals(160f, HtmlPreviewMetrics.PLACEHOLDER_HEIGHT)
    }

    // endregion

    // region Keys and theme

    private val darkNeutral = ChatHtmlTheme(dark = true, accentId = "neutral", primary = Rgb(10, 20, 30), onPrimary = Rgb(255, 255, 255))

    @Test
    fun `the theme is part of the key`() {
        val a = WebRenderKey.html("<p>x</p>", darkNeutral, 343f, 640f)
        assertEquals(a, WebRenderKey.html("<p>x</p>", darkNeutral, 343f, 640f))
        assertNotEquals(a, WebRenderKey.html("<p>x</p>", darkNeutral.copy(dark = false), 343f, 640f))
        assertNotEquals(a, WebRenderKey.html("<p>x</p>", darkNeutral.copy(accentId = "blue"), 343f, 640f))
    }

    @Test
    fun `the width and covered height are part of the key`() {
        val a = WebRenderKey.html("<p>x</p>", darkNeutral, 343f, 640f)
        assertNotEquals(a, WebRenderKey.html("<p>x</p>", darkNeutral, 390f, 640f))
        assertNotEquals(a, WebRenderKey.html("<p>x</p>", darkNeutral, 343f, 12_000f))
        assertTrue(a.startsWith(WebRenderKey.htmlDocument("<p>x</p>", darkNeutral)))
        assertNotEquals(WebRenderKey.htmlDocument("<p>x</p>", darkNeutral), WebRenderKey.htmlDocument("<p>y</p>", darkNeutral))
    }

    @Test
    fun `a diagram and a preview of the same text never share a key`() {
        assertNotEquals(WebRenderKey.htmlDocument("x", darkNeutral), WebRenderKey.mermaid("x", MermaidTheme(dark = true)))
    }

    @Test
    fun `carries the theme tokens with the surface and accent`() {
        val css = darkNeutral.variablesCss
        assertTrue(css.startsWith(":root{"))
        assertTrue(css.contains("--background:${RichBlockPalette.surface(true).css}"))
        assertTrue(css.contains("--foreground:oklch(0.985 0 0)"))
        assertTrue(css.contains("--primary:rgb(10,20,30)"))
        assertTrue(css.contains("--primary-foreground:rgb(255,255,255)"))
        assertTrue(css.contains("--ring:rgb(10,20,30)"))
        assertTrue(css.endsWith("--viz-series-1:var(--blue)}"))
        val light = darkNeutral.copy(dark = false).variablesCss
        assertTrue(light.contains("--foreground:oklch(0.145 0 0)"))
        assertNotEquals(css, light)
        assertNotEquals(css, darkNeutral.copy(primary = Rgb(1, 2, 3)).variablesCss)
        assertEquals("dark-neutral", darkNeutral.key)
    }

    @Test
    fun `reads an ARGB color as its RGB channels`() {
        assertEquals(Rgb(0x12, 0x34, 0x56), Rgb.ofArgb(0xFF123456.toInt()))
        assertEquals("rgb(18,52,86)", Rgb(0x12, 0x34, 0x56).css)
    }

    // endregion

    // region View memory

    @Test
    fun `defaults to the collapsed preview`() {
        assertEquals(HtmlPreviewViewMemory.Choice(preview = true, expanded = false), HtmlPreviewViewMemory().choice("<p>new</p>"))
    }

    @Test
    fun `remembers the choice per source`() {
        val memory = HtmlPreviewViewMemory()
        memory.remember(HtmlPreviewViewMemory.Choice(preview = false, expanded = false), "<p>a</p>")
        memory.remember(HtmlPreviewViewMemory.Choice(preview = true, expanded = true), "<p>b</p>")
        assertEquals(HtmlPreviewViewMemory.Choice(false, false), memory.choice("<p>a</p>"))
        assertEquals(HtmlPreviewViewMemory.Choice(true, true), memory.choice("<p>b</p>"))
        memory.remember(HtmlPreviewViewMemory.Choice(preview = true, expanded = false), "<p>a</p>")
        assertEquals(HtmlPreviewViewMemory.Choice(true, false), memory.choice("<p>a</p>"))
        assertEquals(2, memory.count)
    }

    @Test
    fun `forgets the oldest past its limit`() {
        val memory = HtmlPreviewViewMemory()
        for (index in 0..HtmlPreviewViewMemory.LIMIT) {
            memory.remember(HtmlPreviewViewMemory.Choice(preview = false), "<p>$index</p>")
        }
        assertEquals(HtmlPreviewViewMemory.LIMIT, memory.count)
        assertEquals(HtmlPreviewViewMemory.Choice(), memory.choice("<p>0</p>"))
        assertFalse(memory.choice("<p>${HtmlPreviewViewMemory.LIMIT}</p>").preview)
    }

    @Test
    fun `never pins a huge source`() {
        val memory = HtmlPreviewViewMemory()
        val huge = "x".repeat(HtmlPreviewViewMemory.MAX_BYTES)
        memory.remember(HtmlPreviewViewMemory.Choice(preview = false, expanded = true), huge)
        assertEquals(0, memory.count)
        assertEquals(HtmlPreviewViewMemory.Choice(), memory.choice(huge))
    }

    // endregion
}
