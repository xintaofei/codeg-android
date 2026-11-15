package app.codeg.android.core.designsystem.webrender

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A width × height in CSS pixels (= dp: the render pages lay out at 1 CSS px per dp). */
data class DiagramSize(val width: Float, val height: Float)

/**
 * Mermaid's theme for one color scheme — the web's `{ theme: isDarkMode ? "dark" :
 * "default" }`. Part of every cache key: a diagram drawn for one scheme is never shown in
 * the other.
 */
data class MermaidTheme(val dark: Boolean) {
    /** The `theme` Mermaid is initialized with. */
    val name: String get() = if (dark) "dark" else "default"
}

/**
 * One rendered Mermaid source, for one theme: the SVG markup (with the root's inline
 * `max-width` removed, as the web's `stripSvgMaxWidth`) and its natural size in CSS pixels,
 * when the SVG declares one.
 */
data class MermaidDiagram(val svg: String, val natural: DiagramSize?)

/**
 * Size and zoom helpers ported from the web's `mermaid-view.ts` (via iOS
 * `MermaidGeometry`). Free of views and WebView so they are tested directly.
 */
object MermaidGeometry {
    const val MIN_ZOOM = 0.2f
    const val MAX_ZOOM = 8f

    fun clampZoom(zoom: Float): Float = if (!zoom.isFinite()) 1f else min(MAX_ZOOM, max(MIN_ZOOM, zoom))

    /** Zoom at which a diagram [naturalWidth] wide fills [viewportWidth], capped at 1. */
    fun fitZoom(naturalWidth: Float, viewportWidth: Float): Float {
        if (naturalWidth <= 0f || viewportWidth <= 0f) return 1f
        return clampZoom(min(1f, viewportWidth / naturalWidth))
    }

    /** Zoom at which the whole diagram fits inside [viewport], capped at 1. */
    fun containZoom(natural: DiagramSize, viewport: DiagramSize): Float {
        if (natural.width <= 0f || natural.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) return 1f
        return clampZoom(min(1f, min(viewport.width / natural.width, viewport.height / natural.height)))
    }

    /**
     * The width a diagram is drawn at in a column [column] wide: its natural width, scaled
     * down — never up — to the column. Unlike [fitZoom] there is no lower bound: the inline
     * picture is not pannable, so it always fits. A diagram with no natural size takes the
     * column.
     */
    fun inlineWidth(natural: DiagramSize?, column: Float): Float {
        if (column <= 0f) return 0f
        if (natural == null || natural.width <= 0f) return column
        return min(natural.width, column)
    }

    /** The height of the inline picture: the drawn height, capped at [maxHeight] (`max-h-[60vh]`). */
    fun inlineClip(drawnHeight: Float, maxHeight: Float): Float = max(1f, min(drawnHeight, maxHeight))

    /** There is diagram below the cap — the clipped edge is faded then. */
    fun isClipped(drawnHeight: Float, clipHeight: Float): Boolean = drawnHeight > clipHeight + 1f

    // region SVG

    private val SVG_TAG = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val VIEW_BOX = Regex("\\bviewBox\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val WIDTH = Regex("\\bwidth\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val HEIGHT = Regex("\\bheight\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val PIXEL_VALUE = Regex("^\\s*(-?[\\d.]+)(px)?\\s*$", RegexOption.IGNORE_CASE)
    private val STYLE = Regex("\\bstyle\\s*=\\s*([\"'])([\\s\\S]*?)\\1", RegexOption.IGNORE_CASE)
    private val MAX_WIDTH_DECL = Regex("(?:^|;)\\s*max-width\\s*:[^;]*", RegexOption.IGNORE_CASE)
    private val EDGE_SEMICOLONS = Regex("^;+|;+$")
    private val VIEW_BOX_SEP = Regex("[\\s,]+")

    /**
     * The natural size of a rendered SVG, in CSS pixels (`parseSvgSize`). Mermaid's root
     * carries `width="100%"` and the real size in its `viewBox`, so the viewBox decides;
     * absolute `width`/`height` attributes are the fallback. Percentages never count.
     */
    fun parseSvgSize(svg: String): DiagramSize? {
        val tag = SVG_TAG.find(svg)?.value ?: return null
        VIEW_BOX.find(tag)?.groupValues?.get(1)?.let { viewBox ->
            val parts = viewBox.trim().split(VIEW_BOX_SEP).filter { it.isNotEmpty() }.map(::jsNumber)
            if (parts.size == 4 && finitePositive(parts[2]) && finitePositive(parts[3])) {
                return DiagramSize(parts[2].toFloat(), parts[3].toFloat())
            }
        }
        val width = pixelAttr(WIDTH.find(tag)?.groupValues?.get(1)) ?: return null
        val height = pixelAttr(HEIGHT.find(tag)?.groupValues?.get(1)) ?: return null
        return DiagramSize(width.toFloat(), height.toFloat())
    }

    /** Drop the inline `max-width` Mermaid puts on the root `<svg>` (`stripSvgMaxWidth`). */
    fun stripSvgMaxWidth(svg: String): String {
        val tagMatch = SVG_TAG.find(svg) ?: return svg
        val tag = tagMatch.value
        val style = STYLE.find(tag) ?: return svg
        val quote = style.groupValues[1]
        val css = style.groupValues[2]
        val next = css.replace(MAX_WIDTH_DECL, ";").replace(EDGE_SEMICOLONS, "")
        val newTag = tag.replaceRange(style.range, "style=$quote$next$quote")
        return svg.replaceRange(tagMatch.range, newTag)
    }

    /** A bare number or an explicit `px` — `"100%"` is not 100 pixels. */
    private fun pixelAttr(raw: String?): Double? {
        val number = raw?.let { PIXEL_VALUE.find(it)?.groupValues?.get(1) } ?: return null
        val value = parseFloat(number)
        return if (finitePositive(value)) value else null
    }

    private fun finitePositive(value: Double) = value.isFinite() && value > 0

    // A JS decimal literal; Java's parser would also take "1d", "0x1p3" or "NaN".
    private val JS_DECIMAL = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$")

    /** JavaScript `Number(s)` for a viewBox part: empty is 0, junk is NaN. */
    private fun jsNumber(s: String): Double = when {
        s.isEmpty() -> 0.0
        JS_DECIMAL.matches(s) -> s.toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    /** JavaScript `parseFloat` on `[-]digits/dots`: the longest prefix that reads as a number. */
    private fun parseFloat(s: String): Double {
        var prefix = s
        while (prefix.isNotEmpty()) {
            prefix.toDoubleOrNull()?.let { return it }
            prefix = prefix.dropLast(1)
        }
        return Double.NaN
    }

    // endregion
}

/**
 * The diagram's exports (`mermaid-export.ts`): the rendered SVG, a PNG at three times the
 * diagram's own size within the export budget, and the fence source.
 */
object MermaidExport {
    enum class Format(val extension: String, val mime: String) {
        SVG("svg", "image/svg+xml"),
        PNG("png", "image/png"),
        MMD("mmd", "text/plain"),
    }

    /** Multiplier applied to the diagram's own size when rasterizing to PNG. */
    const val PNG_SCALE = 3f

    /** The PNG's bitmap stays within this many pixels… */
    const val MAX_PIXELS = 16_000_000f

    /** …and this many on its long side. */
    const val MAX_SIDE = 8192f

    /** `${baseName}.${format}` — `diagram.svg`, `diagram.png`, `diagram.mmd`. */
    fun fileName(format: Format, baseName: String = "diagram"): String = "$baseName.${format.extension}"

    /** The PNG's pixel size (`sizeSvgForRaster`): natural size × [scale], rounded; null without one. */
    fun rasterSize(svg: String, scale: Float): DiagramSize? {
        val natural = MermaidGeometry.parseSvgSize(svg) ?: return null
        if (scale <= 0f) return null
        return DiagramSize((natural.width * scale).roundToInt().toFloat(), (natural.height * scale).roundToInt().toFloat())
    }
}

/**
 * How many pixels per CSS pixel a picture is drawn at: the wanted scale, lowered so its
 * bitmap stays within a pixel budget and a maximum side.
 */
object PixelBudget {
    fun scale(size: DiagramSize, preferred: Float, maxPixels: Float, maxSide: Float = 8192f): Float {
        if (size.width <= 0f || size.height <= 0f || preferred <= 0f) return max(0f, preferred)
        val byArea = sqrt(maxPixels / (size.width * size.height))
        val bySide = maxSide / max(size.width, size.height)
        return min(preferred, min(byArea, bySide))
    }
}
