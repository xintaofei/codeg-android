package app.codeg.android.core.designsystem.webrender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatHtmlDocument]: the chat HTML preview's sanitizer and document builder. Cases ported
 * from the web's `chat-html-document.test.ts` and `html-preview-inline.test.ts` (the wrapper /
 * head / body / CSP cases; the resource-inlining ones don't apply — the chat inlines
 * nothing), by way of iOS's `ChatHtmlDocumentTests`. The web asserts on a parsed DOM; these
 * assert on the markup, which is kept as written.
 */
class ChatHtmlDocumentTest {

    private val theme = ChatHtmlTheme(dark = true, accentId = "neutral", primary = Rgb(200, 200, 200), onPrimary = Rgb(0, 0, 0))

    private fun occurrences(needle: String, haystack: String) = haystack.windowed(needle.length).count { it == needle }

    /**
     * The normalized document's head and body content: from its `<head>` to the first
     * `</head>` after it, and from the `<body>` after that to the last `</body>`.
     */
    private fun parts(html: String): Pair<String, String> {
        val headStart = html.indexOf("<head")
        val headOpenEnd = html.indexOf('>', headStart) + 1
        val headEnd = html.indexOf("</head>", headOpenEnd)
        val bodyStart = html.indexOf("<body", headEnd)
        val bodyOpenEnd = html.indexOf('>', bodyStart) + 1
        val bodyEnd = html.lastIndexOf("</body>")
        return html.substring(headOpenEnd, headEnd) to html.substring(bodyOpenEnd, bodyEnd)
    }

    // region Document isolation

    @Test
    fun `keeps CSS, SVG and document attributes, strips scripts, handlers, embeds and redirects`() {
        val doc = ChatHtmlDocument.build(
            """<html lang="zh" class="theme"><head><meta http-equiv="refresh" content="0;url=https://example.com"><base href="https://example.com"><style>.x{display:flex}</style></head><body class="cards" onload="attack()"><div onclick="attack()"><script>attack()</script><iframe srcdoc="attack"></iframe><a href="javascript:attack()">A</a><svg><path d="M0 0" /></svg></div></body></html>""",
            theme,
        )
        assertTrue(doc.contains("""lang="zh""""))
        assertTrue(doc.contains("""class="cards""""))
        assertTrue(doc.contains(".x{display:flex}"))
        assertTrue(doc.contains("<svg>"))
        assertFalse(doc.contains("attack"))
        assertFalse(doc.contains("https://example.com"))
        // No script at all: this client runs none (no nonce'd size reporter).
        assertFalse(doc.contains("<script"))
        assertTrue(doc.contains("script-src 'none'"))
        assertFalse(doc.contains("'unsafe-eval'"))
        // The author's <base> is gone; the pinned one is the only one.
        assertEquals(1, occurrences("<base", doc))
    }

    @Test
    fun `puts the theme before author CSS and is not misled by fake end tags in CSS`() {
        val doc = ChatHtmlDocument.build("""<div><style>.x::before{content:"</head></body>"}body{margin:20px}</style><p>A</p></div>""", theme)
        val themeAt = doc.indexOf("--viz-series-1:var(--blue)")
        val authorAt = doc.indexOf(""".x::before{content:"</head></body>"}body{margin:20px}""")
        assertTrue(themeAt >= 0 && authorAt >= 0)
        assertTrue(themeAt < authorAt)
        // The theme sits in the real head, the author's style in the body.
        val (head, body) = parts(doc)
        assertTrue(head.contains("--viz-series-1"))
        assertTrue(body.contains("""content:"</head></body>""""))
        assertTrue(body.contains("<p>A</p>"))
    }

    @Test
    fun `strips SVG xlink href and animated links but keeps references into the document`() {
        val doc = ChatHtmlDocument.build(
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><defs><linearGradient id="g"/><path id="p" d="M0 0"/></defs><a xlink:href="https://attack.example/x"><text>A</text></a><a><set attributeName="href" to="https://attack.example/y"/><animate attributeName="xlink:href" values="https://attack.example/z"/><text>B</text></a><use href="#p"/><use xlink:href="#p"/><rect fill="url(#g)"/></svg><a href="#top">Top</a>""",
            theme,
        )
        assertFalse(doc.contains("attack.example"))
        assertFalse(doc.contains("<set"))
        assertFalse(doc.contains("<animate"))
        assertTrue(doc.contains("""<use href="#p""""))
        assertTrue(doc.contains("""<use xlink:href="#p""""))
        assertTrue(doc.contains("""<a href="#top">"""))
        assertTrue(doc.contains("""fill="url(#g)""""))
    }

    @Test
    fun `adds the viewport last in the head so it wins over the author's`() {
        val doc = ChatHtmlDocument.build("""<html><head><meta name="viewport" content="width=1200"></head><body><p>x</p></body></html>""", theme)
        val (head, _) = parts(doc)
        val author = head.indexOf("""content="width=1200"""")
        val ours = head.indexOf(ChatHtmlDocument.VIEWPORT_META)
        assertTrue(author >= 0 && ours >= 0)
        assertTrue(author < ours)
    }

    @Test
    fun `themes the surface and the color scheme`() {
        val dark = ChatHtmlDocument.build("<p>x</p>", theme)
        assertTrue(dark.contains(":root{color-scheme:dark}"))
        assertTrue(dark.contains("html{background:${RichBlockPalette.surface(true).css}"))
        val light = ChatHtmlDocument.build("<p>x</p>", theme.copy(dark = false, accentId = "orange"))
        assertTrue(light.contains(":root{color-scheme:light}"))
        assertTrue(light.contains("#1a1c1f"))
    }

    // endregion

    // region Active content stripping

    private fun strip(html: String) = ChatHtmlDocument.inlineStripped(html)

    @Test
    fun `strips an author-supplied CSP meta`() {
        assertFalse(strip("""<head><meta http-equiv="Content-Security-Policy" content="default-src *"></head><body></body>""").contains("default-src *"))
    }

    @Test
    fun `does not throw on malformed input`() {
        assertTrue(strip("""<<<x>>> <img src="missing.png">""").contains("missing.png"))
    }

    @Test
    fun `removes every active element with its content`() {
        val out = strip("""<p>a</p><object data="x.swf"><param name="p" value="v"><em>fallback</em></object><embed src="y"><link rel="stylesheet" href="z.css"><template><b>t</b></template><script src="s.js"></script><iframe src="https://e.example"></iframe><p>b</p>""")
        for (gone in listOf("<object", "fallback", "<param", "<embed", "<link", "<template", "<b>t</b>", "<script", "<iframe", "e.example")) {
            assertFalse(gone, out.contains(gone))
        }
        assertTrue(out.contains("<p>a</p>"))
        assertTrue(out.contains("<p>b</p>"))
    }

    @Test
    fun `removes form targets, nonces and every handler`() {
        val out = strip("""<form action="https://e.example/post"><button formaction="https://e.example/b" ONCLICK="x()" onmouseover=y()>Go</button></form><style nonce="n1">p{}</style>""")
        assertFalse(out.contains("e.example"))
        assertFalse(out.lowercase().contains("onclick"))
        assertFalse(out.contains("onmouseover"))
        assertFalse(out.contains("nonce"))
        assertTrue(out.contains("<form>"))
        assertTrue(out.contains("<button>Go</button>"))
    }

    @Test
    fun `judges a link by its decoded value`() {
        // "&#35;" is "#": a fragment, kept as written.
        assertTrue(strip("""<a href="&#35;notes">N</a>""").contains("""<a href="&#35;notes">"""))
        // "&#106;" is "j": still a javascript: link.
        val removed = strip("""<a href="&#106;avascript:x()">J</a>""")
        assertFalse(removed.contains("avascript"))
        assertTrue(removed.contains("<a>J</a>"))
    }

    @Test
    fun `removes a meta refresh and base but keeps other meta`() {
        val out = strip("""<head><meta charset="utf-8"><meta http-equiv=" Refresh " content="0;url=https://e.example"><base href="https://e.example/"></head><body>x</body>""")
        assertTrue(out.contains("""<meta charset="utf-8">"""))
        assertFalse(out.contains("e.example"))
        assertFalse(out.contains("<base"))
    }

    @Test
    fun `preserves html and body wrapper attributes`() {
        val out = strip("""<html lang="en"><head></head><body class="preview" data-theme="dark"><p>x</p></body></html>""")
        assertTrue(out.contains("""<html lang="en">"""))
        assertTrue(out.contains("""<body class="preview" data-theme="dark">"""))
    }

    @Test
    fun `ignores commented-out and string-literal wrapper tags when extracting attrs`() {
        val out = strip(
            """<!-- <body class="wrong" onload="evil()"> -->""" +
                """<html lang="en"><head><style>p::after{content:"<body class=fake>"}</style></head>""" +
                """<body class="preview" data-theme="dark"></body></html>""",
        )
        // The real body's attributes — not the commented-out tag's, nor the one in the CSS
        // string (which, as text, stays where it was).
        assertTrue(out.contains("</head>\n" + """<body class="preview" data-theme="dark">"""))
    }

    @Test
    fun `does not truncate wrapper attrs containing a greater-than in a quoted value`() {
        assertTrue(strip("""<html><body data-x="a>b" class="c"></body></html>""").contains("""<body data-x="a>b" class="c">"""))
    }

    @Test
    fun `keeps head metadata in the head and content in the body`() {
        val out = strip("""<html><head><meta name="viewport" content="w=1"><title>T</title></head><body class="x"><p>hi</p></body></html>""")
        val (head, body) = parts(out)
        assertTrue(head.contains("""<meta name="viewport" content="w=1">"""))
        assertTrue(head.contains("<title>T</title>"))
        assertTrue(body.contains("<p>hi</p>"))
        assertTrue(out.contains("""<body class="x">"""))
    }

    @Test
    fun `keeps body-leading metadata in the body`() {
        // The web case uses a <script>, which this mode removes; a <style> shows the same split.
        val out = strip("""<html><head><title>T</title></head><body><style>p{}</style><p>hi</p></body></html>""")
        val (head, body) = parts(out)
        assertTrue(head.contains("<title>T</title>"))
        assertFalse(head.contains("<style>"))
        assertTrue(body.contains("<style>p{}</style><p>hi</p>"))
    }

    @Test
    fun `splits a document without a body after its leading metadata`() {
        val out = strip("\n<!-- c --><style>.a{}</style>\n<meta name=\"x\" content=\"y\"><div>A</div><style>.b{}</style>")
        val (head, body) = parts(out)
        assertTrue(head.contains("<style>.a{}</style>"))
        assertTrue(head.contains("""<meta name="x" content="y">"""))
        assertTrue(body.startsWith("<div>A</div>"))
        assertTrue(body.contains("<style>.b{}</style>"))
    }

    @Test
    fun `ignores wrapper tags inside RCDATA title content`() {
        val out = strip("""<html><head><title><body class="fake"></title></head><body class="real"><p>x</p></body></html>""")
        assertTrue(out.contains("""<body class="real">"""))
        assertTrue(out.contains("""<title><body class="fake"></title>"""))
    }

    @Test
    fun `ignores wrapper tags inside template content`() {
        val out = strip("""<html><head><template><body class="fake"></template></head><body class="real"><p>x</p></body></html>""")
        assertTrue(out.contains("""<body class="real">"""))
        assertFalse(out.contains("fake"))
    }

    @Test
    fun `does not end a template skip at an end tag inside a comment`() {
        val out = strip("""<html><head><template><!-- </template> --><body class="fake"></body></template></head><body class="real"><p>x</p></body></html>""")
        assertTrue(out.contains("""<body class="real">"""))
        assertFalse(out.contains("fake"))
    }

    @Test
    fun `keeps multi-byte text byte for byte`() {
        val out = strip("""<p title="标题">你好 — ✓ 🎉</p>""")
        assertTrue(out.contains("""<p title="标题">你好 — ✓ 🎉</p>"""))
    }

    // endregion

    // region Sandbox CSP

    private val base = "<html><head></head><body>x</body></html>"

    @Test
    fun `blocks scripts and network`() {
        val out = ChatHtmlDocument.withSandboxCsp(base)
        assertTrue(out.contains("""http-equiv="Content-Security-Policy""""))
        assertTrue(out.contains("default-src 'none'"))
        assertTrue(out.contains("script-src 'none'"))
        assertTrue(out.contains("form-action 'none'"))
        assertTrue(out.contains("frame-src 'none'"))
        assertFalse(out.contains("connect-src"))
        assertEquals(
            "default-src 'none'; img-src data: blob:; media-src data: blob:; style-src 'unsafe-inline' data:; font-src data:; script-src 'none'; form-action 'none'; base-uri about:; frame-src 'none'",
            ChatHtmlDocument.STRICT_CSP,
        )
    }

    @Test
    fun `inserts the CSP meta as the first head child`() {
        assertTrue(ChatHtmlDocument.withSandboxCsp(base).contains("""<head><meta http-equiv="Content-Security-Policy""""))
    }

    @Test
    fun `injects correctly when the head has an attribute containing a greater-than`() {
        val out = ChatHtmlDocument.withSandboxCsp("""<html><head data-x="a>b"></head><body></body></html>""")
        assertTrue(out.contains("""<head data-x="a>b"><meta http-equiv="Content-Security-Policy""""))
    }

    @Test
    fun `targets the real head, not one inside an attribute value`() {
        val out = ChatHtmlDocument.withSandboxCsp("""<html data-x="<head >"><head></head><body></body></html>""")
        assertEquals(1, occurrences("Content-Security-Policy", out))
        assertTrue(out.contains("""<head><meta http-equiv="Content-Security-Policy""""))
        assertTrue(out.contains("""data-x="<head >""""))
    }

    @Test
    fun `ignores a fake script end and a head inside script text`() {
        val out = ChatHtmlDocument.withSandboxCsp("""<html><script>var s = "</scriptx><head></head>";</script><head></head><body></body></html>""")
        assertEquals(1, occurrences("Content-Security-Policy", out))
        assertTrue(out.indexOf("</script>") < out.indexOf("Content-Security-Policy"))
    }

    @Test
    fun `pins the base URL so fragment links stay in the document`() {
        val out = ChatHtmlDocument.withSandboxCsp(base)
        assertTrue(out.contains("""<base href="about:srcdoc">"""))
        assertTrue(out.contains("base-uri about:"))
        assertFalse(out.contains("base-uri 'none'"))
    }

    @Test
    fun `emits the CSP meta before the base`() {
        val out = ChatHtmlDocument.withSandboxCsp(base)
        assertTrue(out.indexOf("""http-equiv="Content-Security-Policy"""") < out.indexOf("""<base href="about:srcdoc">"""))
    }

    @Test
    fun `makes the injected base the only one`() {
        assertEquals(1, occurrences("<base", ChatHtmlDocument.withSandboxCsp(base)))
    }

    @Test
    fun `adds a head when there is none`() {
        assertEquals("<html><head><i></i></head><p>x</p></html>", ChatHtmlDocument.withHtmlPreviewHead("<html><p>x</p></html>", "<i></i>"))
        assertEquals("<head><i></i></head><p>x</p>", ChatHtmlDocument.withHtmlPreviewHead("<p>x</p>", "<i></i>"))
    }

    // endregion

    // region Tokenizer

    private fun tags(html: String): List<String> =
        HtmlTokenizer.tokenize(html).mapNotNull { (it as? HtmlTokenizer.Token.StartTag)?.tag?.name }

    @Test
    fun `ends comments the way the HTML tokenizer does`() {
        // "<!-->" and "<!--->" are complete (empty) comments; "--!>" ends one.
        assertEquals(listOf("b"), tags("<!--><b>1</b>"))
        assertEquals(listOf("i"), tags("<!---><i>2</i>"))
        assertEquals(listOf("u"), tags("<!-- x --!><u>3</u>"))
        assertEquals(emptyList<String>(), tags("<!-- <s>never</s>"))
    }

    @Test
    fun `keeps raw text until its own end tag`() {
        assertEquals(listOf("script", "i"), tags("<script>a = '</scriptx><b>'</script><i>"))
        assertEquals(listOf("style"), tags("<style>p::after{content:'<em>'}</style>"))
        assertEquals(listOf("textarea"), tags("<textarea><b>x</b></textarea>"))
    }

    @Test
    fun `drops a tag the input ends inside`() {
        assertEquals(listOf("p"), tags("""<p>x</p><a href="unterminated"""))
    }

    @Test
    fun `reads attributes quoted, unquoted and bare`() {
        val tag = (HtmlTokenizer.tokenize("""<input type=checkbox checked data-x='a"b' value = "1"/>""").first() as HtmlTokenizer.Token.StartTag).tag
        assertEquals("input", tag.name)
        assertEquals(listOf("type", "checked", "data-x", "value"), tag.attributes.map { it.name })
        assertNull(tag.attributes[1].valueRange)
        assertTrue(tag.selfClosing)
    }

    @Test
    fun `treats a slash as an attribute separator`() {
        val tag = (HtmlTokenizer.tokenize("<div/onclick=x>").first() as HtmlTokenizer.Token.StartTag).tag
        assertEquals(listOf("onclick"), tag.attributes.map { it.name })
        assertFalse(tag.selfClosing)
    }

    @Test
    fun `lowercases names and counts bogus comments as comments`() {
        assertEquals(listOf("div", "svg"), tags("<DIV><Svg>"))
        assertEquals(listOf("p"), tags("<?xml version='1.0'?><!x><p>"))
    }

    // endregion

    // region Entities

    @Test
    fun `decodes numeric and common named references`() {
        assertEquals("#top", HtmlEntities.decode("&#35;top"))
        assertEquals("#top", HtmlEntities.decode("&#x23;top"))
        assertEquals("a&b<c>", HtmlEntities.decode("a&amp;b&lt;c&gt;"))
        assertEquals("javascript:", HtmlEntities.decode("java&#115;cript&colon;"))
    }

    @Test
    fun `leaves what it does not know`() {
        assertEquals("&bogus; & plain", HtmlEntities.decode("&bogus; & plain"))
        assertEquals("no references", HtmlEntities.decode("no references"))
        assertNotNull(HtmlEntities.decode("&#99999999999;"))
    }

    // endregion
}
