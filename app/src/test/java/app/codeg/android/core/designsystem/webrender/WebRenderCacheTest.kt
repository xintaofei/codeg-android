package app.codeg.android.core.designsystem.webrender

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-screen renderer's cache keys (content + theme + size, never a turn id), its
 * bounded cache, and how the Mermaid page is handed a source. Ported from iOS
 * `WebRenderCacheTests`, plus the argument encoding Android needs (no call-with-arguments
 * API: values go in as JSON literals).
 */
class WebRenderCacheTest {

    private val dark = MermaidTheme(dark = true)
    private val light = MermaidTheme(dark = false)

    // region Keys

    @Test
    fun `the same source and theme share a key, and the theme is part of it`() {
        assertEquals(WebRenderKey.mermaid("graph TD;\nA-->B;", dark), WebRenderKey.mermaid("graph TD;\nA-->B;", dark))
        assertNotEquals(WebRenderKey.mermaid("graph TD;", dark), WebRenderKey.mermaid("graph TD;", light))
        assertEquals("dark", dark.name)
        assertEquals("default", light.name)
    }

    @Test
    fun `any change to the source changes the key, and a long source keeps it small`() {
        assertNotEquals(WebRenderKey.mermaid("graph TD;", dark), WebRenderKey.mermaid("graph TD; ", dark))
        val long = "A-->B;\n".repeat(10_000)
        assertTrue(WebRenderKey.mermaid(long, dark).toByteArray().size < 100)
    }

    @Test
    fun `a picture key adds its width and height cap`() {
        val a = WebRenderKey.mermaidImage("graph TD;", dark, 343f, 500f)
        assertTrue(a.startsWith(WebRenderKey.mermaid("graph TD;", dark)))
        assertNotEquals(a, WebRenderKey.mermaidImage("graph TD;", dark, 360f, 500f))
        assertNotEquals(a, WebRenderKey.mermaidImage("graph TD;", dark, 343f, 420f))
        // A sub-dp wobble in the measured column is the same picture.
        assertEquals(a, WebRenderKey.mermaidImage("graph TD;", dark, 343.2f, 500f))
    }

    @Test
    fun `rounds to whole dp`() {
        assertEquals(343, WebRenderKey.points(343.4f))
        assertEquals(344, WebRenderKey.points(343.6f))
        assertEquals(0, WebRenderKey.points(0f))
        assertEquals(0, WebRenderKey.points(Float.NaN))
    }

    @Test
    fun `the digest is SHA-256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WebRenderKey.digest("abc"))
    }

    // endregion

    // region Bounded cache

    private fun cache(limit: Int = 3, maxBytes: Long = 1L shl 20) = ByteBoundedLru<String>(limit, maxBytes) { it.length * 2L }

    @Test
    fun `evicts the least recently used past its entry limit`() {
        val c = cache(limit = 2)
        c.put("1", "a")
        c.put("2", "b")
        c["1"]
        c.put("3", "c")
        assertEquals("a", c["1"])
        assertNull(c["2"])
        assertEquals("c", c["3"])
        assertEquals(2, c.count)
    }

    @Test
    fun `evicts past its byte budget`() {
        val c = cache(limit = 10, maxBytes = 1000)
        c.put("big1", "x".repeat(200))
        c.put("big2", "y".repeat(200))
        c.put("big3", "z".repeat(200))
        assertTrue(c.totalBytes <= 1000)
        assertNull(c["big1"])
        assertNotNull(c["big3"])
    }

    @Test
    fun `keeps the newest entry even when it alone is over budget`() {
        val c = cache(limit = 10, maxBytes = 100)
        c.put("a", "small")
        c.put("huge", "x".repeat(500))
        assertNotNull(c["huge"])
        assertNull(c["a"])
    }

    @Test
    fun `replacing an entry releases its bytes`() {
        val c = cache()
        c.put("k", "x".repeat(100))
        val first = c.totalBytes
        c.put("k", "y")
        assertTrue(c.totalBytes < first)
        assertEquals(1, c.count)
        c.clear()
        assertEquals(0L, c.totalBytes)
    }

    // endregion

    // region Page calls

    @Test
    fun `a source reaches the page as data, never as script`() {
        val hostile = "graph TD;\nA[\"x\"]-->B\n\"); fetch('https://e.example'); (\"\\\n </script>"
        val call = MermaidHost.startCall("j1", hostile, dark)
        val prefix = "window.__codegMermaid.start(\"j1\", "
        assertTrue(call.startsWith(prefix))
        // The second argument is one JSON string literal that decodes back to the source.
        val literal = call.removePrefix(prefix).substringBefore(", \"dark\", ")
        assertEquals(hostile, Json.parseToJsonElement(literal).jsonPrimitive.content)
        // Escaped: no raw line break, and every quote inside it is escaped.
        assertFalse(literal.contains("\n"))
        assertFalse(Regex("[^\\\\]\"").containsMatchIn(literal.substring(1, literal.length - 1)))
    }

    @Test
    fun `the stage call carries numbers and colors as literals`() {
        val call = MermaidHost.stageCall("<svg></svg>", 343.5f, 0f, "rgb(20,22,25)")
        assertTrue(call.contains("""window.__codegMermaid.stage("<svg></svg>", 343.5, 0.0, "rgb(20,22,25)")"""))
        // A page that throws says why, instead of an opaque null.
        assertTrue(call.contains("catch (e) { return { error:"))
    }

    @Test
    fun `the start and take calls name their job`() {
        assertEquals("""window.__codegMermaid.take("j7")""", MermaidHost.takeCall("j7"))
        assertTrue(MermaidHost.startCall("j7", "graph TD;", MermaidTheme(dark = false)).endsWith(""""default", "monospace")"""))
    }

    @Test
    fun `the page allows no network`() {
        assertTrue(MermaidHost.PAGE_HTML.contains("connect-src 'none'"))
        assertTrue(MermaidHost.PAGE_HTML.contains("default-src 'none'"))
        assertTrue(MermaidHost.BOOTSTRAP_SCRIPT.contains("securityLevel: \"strict\""))
    }

    // endregion
}
