package app.codeg.android.core.designsystem.webrender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ported from the web's `mermaid-view.test.ts` (zoom / size helpers) and
 * `mermaid-export.test.ts` (raster size, export file names), plus the inline picture's size
 * math — by way of iOS `MermaidGeometryTests`.
 */
class MermaidGeometryTest {

    /** A realistic Mermaid 11 root tag: `width="100%"`, the real size only in the viewBox. */
    private val mermaidSvg =
        """<svg aria-roledescription="flowchart-v2" viewBox="0 0 780 1240" """ +
            """style="max-width: 780px; background-color: transparent;" """ +
            """width="100%" id="mermaid-1" xmlns="http://www.w3.org/2000/svg">""" +
            "<g></g></svg>"

    private fun near(expected: Float, actual: Float) = assertTrue("$actual ≉ $expected", abs(expected - actual) < 1e-5f)

    // region Zoom

    @Test
    fun `holds the zoom inside the bounds`() {
        assertEquals(MermaidGeometry.MIN_ZOOM, MermaidGeometry.clampZoom(0f))
        assertEquals(MermaidGeometry.MAX_ZOOM, MermaidGeometry.clampZoom(1000f))
        assertEquals(1.5f, MermaidGeometry.clampZoom(1.5f))
    }

    @Test
    fun `falls back to 1 for a non-finite zoom`() {
        assertEquals(1f, MermaidGeometry.clampZoom(Float.NaN))
        assertEquals(1f, MermaidGeometry.clampZoom(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `fit zoom shrinks a diagram wider than the column, never blows a narrow one up`() {
        near(0.5f, MermaidGeometry.fitZoom(1000f, 500f))
        assertEquals(1f, MermaidGeometry.fitZoom(200f, 800f))
    }

    @Test
    fun `fit zoom stays at 1 before the viewport is measured, and not below the minimum`() {
        assertEquals(1f, MermaidGeometry.fitZoom(800f, 0f))
        assertEquals(1f, MermaidGeometry.fitZoom(0f, 800f))
        assertEquals(MermaidGeometry.MIN_ZOOM, MermaidGeometry.fitZoom(100_000f, 300f))
    }

    @Test
    fun `contain zoom fits the limiting axis`() {
        near(0.8f, MermaidGeometry.containZoom(DiagramSize(500f, 1000f), DiagramSize(1200f, 800f)))
        near(0.5f, MermaidGeometry.containZoom(DiagramSize(2000f, 400f), DiagramSize(1000f, 900f)))
        assertEquals(1f, MermaidGeometry.containZoom(DiagramSize(200f, 100f), DiagramSize(1200f, 800f)))
        assertEquals(1f, MermaidGeometry.containZoom(DiagramSize(500f, 500f), DiagramSize(0f, 800f)))
        assertEquals(1f, MermaidGeometry.containZoom(DiagramSize(0f, 500f), DiagramSize(800f, 800f)))
    }

    // endregion

    // region parseSvgSize

    @Test
    fun `reads the natural size from the viewBox`() {
        assertEquals(DiagramSize(780f, 1240f), MermaidGeometry.parseSvgSize(mermaidSvg))
        assertEquals(DiagramSize(10f, 20f), MermaidGeometry.parseSvgSize("""<svg viewBox="0,0,10,20"></svg>"""))
    }

    @Test
    fun `falls back to pixel width and height attributes`() {
        assertEquals(DiagramSize(300f, 150f), MermaidGeometry.parseSvgSize("""<svg width="300" height="150"></svg>"""))
        assertEquals(DiagramSize(300f, 150f), MermaidGeometry.parseSvgSize("""<svg width="300px" height="150"></svg>"""))
    }

    @Test
    fun `ignores a percentage size, a degenerate viewBox and markup with no svg tag`() {
        assertNull(MermaidGeometry.parseSvgSize("""<svg width="100%" height="100%"></svg>"""))
        assertNull(MermaidGeometry.parseSvgSize("""<svg viewBox="0 0 0 0"></svg>"""))
        assertNull(MermaidGeometry.parseSvgSize("<div>not a diagram</div>"))
        // JS Number(): a suffix makes a part NaN, where Java's parser would read "10d" as 10.
        assertNull(MermaidGeometry.parseSvgSize("""<svg viewBox="0 0 10d 20"></svg>"""))
    }

    // endregion

    // region stripSvgMaxWidth

    @Test
    fun `removes only the max-width declaration from the root tag`() {
        val stripped = MermaidGeometry.stripSvgMaxWidth(mermaidSvg)
        assertFalse(stripped.lowercase().contains("max-width"))
        assertTrue(stripped.contains("background-color: transparent"))
        assertTrue(stripped.contains("""viewBox="0 0 780 1240""""))
        assertTrue(stripped.contains("""width="100%""""))
    }

    @Test
    fun `leaves nested styles alone and is a no-op with nothing to strip`() {
        val svg = """<svg viewBox="0 0 10 10" style="max-width: 10px;"><style>.node{max-width:5px}</style></svg>"""
        assertTrue(MermaidGeometry.stripSvgMaxWidth(svg).contains(".node{max-width:5px}"))
        val plain = """<svg viewBox="0 0 10 10"></svg>"""
        assertEquals(plain, MermaidGeometry.stripSvgMaxWidth(plain))
    }

    // endregion

    // region Export

    private val exportSvg =
        """<svg xmlns="http://www.w3.org/2000/svg" aria-roledescription="flowchart-v2" """ +
            """viewBox="0 0 400 250" style="max-width: 400px; background-color: transparent;" """ +
            """width="100%" id="mermaid-1"><g><rect width="10" height="10"/></g></svg>"""

    @Test
    fun `names the files as the web does`() {
        assertEquals("diagram.png", MermaidExport.fileName(MermaidExport.Format.PNG))
        assertEquals("diagram.svg", MermaidExport.fileName(MermaidExport.Format.SVG))
        assertEquals("diagram.mmd", MermaidExport.fileName(MermaidExport.Format.MMD))
        assertEquals("flow.svg", MermaidExport.fileName(MermaidExport.Format.SVG, baseName = "flow"))
    }

    @Test
    fun `takes the pixel size from the viewBox and scales it`() {
        assertEquals(DiagramSize(1200f, 750f), MermaidExport.rasterSize(exportSvg, 3f))
        assertEquals(3f, MermaidExport.PNG_SCALE)
    }

    @Test
    fun `never reads mermaid's hundred-percent width as hundred pixels`() {
        assertNull(MermaidExport.rasterSize("""<svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%"></svg>""", 3f))
    }

    @Test
    fun `falls back to absolute width and height attributes`() {
        assertEquals(DiagramSize(600f, 300f), MermaidExport.rasterSize("""<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="150"></svg>""", 2f))
    }

    @Test
    fun `returns null for a degenerate viewBox or non-svg markup, and rounds to whole pixels`() {
        assertNull(MermaidExport.rasterSize("""<svg viewBox="0 0 0 0"></svg>""", 3f))
        assertNull(MermaidExport.rasterSize("<div>not a diagram</div>", 3f))
        assertNull(MermaidExport.rasterSize("", 3f))
        assertEquals(DiagramSize(151f, 50f), MermaidExport.rasterSize("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100.4 33.3"></svg>""", 1.5f))
    }

    // endregion

    // region Inline size

    @Test
    fun `fits a wide diagram to the column and keeps a narrow one at its own width`() {
        assertEquals(360f, MermaidGeometry.inlineWidth(DiagramSize(1000f, 500f), 360f))
        assertEquals(200f, MermaidGeometry.inlineWidth(DiagramSize(200f, 500f), 360f))
        // The inline picture is not pannable: no 0.2 floor as in `fitZoom`.
        assertEquals(300f, MermaidGeometry.inlineWidth(DiagramSize(100_000f, 50f), 300f))
    }

    @Test
    fun `takes the column without a natural size`() {
        assertEquals(320f, MermaidGeometry.inlineWidth(null, 320f))
        assertEquals(0f, MermaidGeometry.inlineWidth(DiagramSize(400f, 300f), 0f))
    }

    @Test
    fun `caps the height and fades only when something is cut`() {
        val clip = MermaidGeometry.inlineClip(1800f, 500f)
        assertEquals(500f, clip)
        assertTrue(MermaidGeometry.isClipped(1800f, clip))
        val short = MermaidGeometry.inlineClip(240f, 500f)
        assertEquals(240f, short)
        assertFalse(MermaidGeometry.isClipped(240f, short))
        // Under a pixel of overhang is not "more below".
        assertFalse(MermaidGeometry.isClipped(500.6f, 500f))
    }

    // endregion

    // region Pixel budget

    @Test
    fun `keeps the preferred scale within budget, else lowers it`() {
        assertEquals(3f, PixelBudget.scale(DiagramSize(400f, 300f), 3f, 8_000_000f))
        val size = DiagramSize(2000f, 4000f)
        val scale = PixelBudget.scale(size, 3f, 16_000_000f)
        assertTrue(scale < 3f)
        assertTrue(size.width * size.height * scale * scale <= 16_000_000f * 1.0001f)
    }

    @Test
    fun `lowers the scale to stay within the maximum side`() {
        near(0.8192f, PixelBudget.scale(DiagramSize(10_000f, 10f), 3f, 1e12f, maxSide = 8192f))
    }

    // endregion
}
