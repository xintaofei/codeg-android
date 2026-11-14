package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.sessiondetail.composer.ComposerReferenceCodec.LinkToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Codec = ComposerReferenceCodec

/** The wire form of `@` mentions: web `reference-text.test.ts`, `src/lib/reference-link.test.ts`
 *  and `suggestion/adapters.test.ts` where they apply (via iOS). */
class ComposerReferenceEscapingTest {
    @Test
    fun `link text escapes every inline-significant character`() {
        assertEquals("""a\]\(http://evil\) x""", Codec.escapeMarkdownText("a](http://evil) x"))
        assertEquals("""a\`b""", Codec.escapeMarkdownText("a`b"))
        assertEquals("""\\\*\_\~\<\>""", Codec.escapeMarkdownText("""\*_~<>"""))
        assertEquals("请看 README", Codec.escapeMarkdownText("请看 README"))
    }

    @Test
    fun `a destination is angle-wrapped only when it must be`() {
        assertEquals("file:///repo/app.ts", Codec.escapeLinkDestination("file:///repo/app.ts"))
        assertEquals("<file:///a/b (1).ts>", Codec.escapeLinkDestination("file:///a/b (1).ts"))
        // "\" -> "\\" and ">" -> "\>": a literal `\>` cannot close the link early.
        assertEquals("""<file:///a/\\\> x>""", Codec.escapeLinkDestination("""file:///a/\> x"""))
        assertEquals("file:///a/b", Codec.escapeLinkDestination("file:///a\r\n/b"))
    }

    @Test
    fun `a label stays one inline token`() {
        assertEquals("line1 line2", Codec.collapseNewlines("line1\nline2"))
        assertEquals("a b", Codec.collapseNewlines("a  \r\n  b"))
        assertEquals("a run without a break is kept", "a  b", Codec.collapseNewlines("a  b"))
    }

    @Test
    fun `references serialize like the web`() {
        val file = ComposerReference("@app.ts", ComposerReference.Kind.FILE, "app.ts", "file:///repo/app.ts")
        assertEquals("[app.ts](file:///repo/app.ts)", file.markdown)
        assertEquals("[@Codex](codeg://agent/codex)", ComposerReference.agent(AgentType.CODEX, "Codex").markdown)
        val crafted = ComposerReference("@x", ComposerReference.Kind.AGENT, "Rev](x) [y", "codeg://agent/x")
        assertEquals("""[@Rev\]\(x\) \[y](codeg://agent/x)""", crafted.markdown)
        val multiline = ComposerReference("@x", ComposerReference.Kind.AGENT, "line1\nline2", "codeg://agent/x")
        assertEquals("[@line1 line2](codeg://agent/x)", multiline.markdown)
    }

    @Test
    fun `a file mention joins the root and the relative path`() {
        val item = ComposerReference.file("app.ts", "src/app.ts", "/repo")
        assertEquals("@src/app.ts", item.token)
        assertEquals("app.ts", item.label)
        assertEquals("file:///repo/src/app.ts", item.uri)
        assertEquals("file:///repo/src/app.ts", ComposerReference.file("app.ts", "src/app.ts", "/repo/").uri)
    }

    @Test
    fun `an agent mention carries its routing uri`() {
        val item = ComposerReference.agent(AgentType.CLAUDE_CODE, "Claude Code")
        assertEquals("@Claude Code", item.token)
        assertEquals("codeg://agent/claude_code", item.uri)
        assertEquals("[@Claude Code](codeg://agent/claude_code)", item.markdown)
        assertEquals("codeg://agent/custom:my-agent", ComposerReference.agent(AgentType.fromWire("custom:my-agent"), "Mine").uri)
    }
}

/** Web `buildFileUri` / `encodeURIComponent`. */
class FileUriTest {
    @Test
    fun `posix path`() {
        assertEquals("file:///repo/src/app.ts", Codec.buildFileUri("/repo/src/app.ts"))
    }

    @Test
    fun `windows path normalizes slashes and encodes the drive`() {
        assertEquals("file:///C%3A/repo/app.ts", Codec.buildFileUri("""C:\repo\app.ts"""))
    }

    @Test
    fun `segments are percent-encoded but separators are not`() {
        assertEquals("file:///a/b%20c%23d%3Fe.ts", Codec.buildFileUri("/a/b c#d?e.ts"))
        assertEquals("file:///a/%E8%AF%B4%E6%98%8E%20(1).md", Codec.buildFileUri("/a/说明 (1).md"))
    }

    @Test
    fun `UNC paths take the authority form`() {
        assertEquals("file://server/share/doc.md", Codec.buildFileUri("//server/share/doc.md"))
        assertEquals("file://server/share/doc.md", Codec.buildFileUri("""\\server\share\doc.md"""))
    }

    @Test
    fun `verbatim prefixes are stripped`() {
        assertEquals("file:///C%3A/Users/song/uploads/img.png", Codec.buildFileUri("""\\?\C:\Users\song\uploads\img.png"""))
        assertEquals("file://server/share/doc.md", Codec.buildFileUri("""\\?\UNC\server\share\doc.md"""))
        assertEquals("file://server/share/doc.md", Codec.buildFileUri("""\\?\unc\server\share\doc.md"""))
        assertEquals("file://%3F/Volume%7B7b2f1c40%7D/img.png", Codec.buildFileUri("""\\?\Volume{7b2f1c40}\img.png"""))
    }

    @Test
    fun `encodeURIComponent keeps only the unreserved set`() {
        assertEquals("AZaz09-_.!~*'()", Codec.encodeURIComponent("AZaz09-_.!~*'()"))
        assertEquals("a%20b%2Fc%25", Codec.encodeURIComponent("a b/c%"))
        assertEquals("%E4%B8%AD", Codec.encodeURIComponent("中"))
        assertEquals("%F0%9F%98%80", Codec.encodeURIComponent("😀"))
    }
}

/** Web `reference-link.test.ts`: the link tokenizer and its unescapers. */
class ReferenceLinkTokenizerTest {
    @Test
    fun `unescapes the label`() {
        assertEquals("a]b(c", Codec.unescapeReferenceLabel("""a\]b\(c"""))
        assertEquals("Screenshot (1).png", Codec.unescapeReferenceLabel("""Screenshot \(1\).png"""))
        assertEquals("plain name.ts", Codec.unescapeReferenceLabel("plain name.ts"))
    }

    @Test
    fun `unwraps the destination`() {
        assertEquals("file:///x/foo.ts", Codec.unwrapReferenceDestination("file:///x/foo.ts"))
        assertEquals("codeg://session/42", Codec.unwrapReferenceDestination("  codeg://session/42  "))
        assertEquals("""file:///x\y""", Codec.unwrapReferenceDestination("""file:///x\y"""))
        assertEquals("file:///x/a b.ts", Codec.unwrapReferenceDestination("<file:///x/a b.ts>"))
        assertEquals("""file:///C:\repo\app.ts""", Codec.unwrapReferenceDestination("""<file:///C:\\repo\\app.ts>"""))
        assertEquals("file:///x/a<b>c.ts", Codec.unwrapReferenceDestination("""<file:///x/a\<b\>c.ts>"""))
    }

    @Test
    fun `splits prose around a link`() {
        assertEquals(
            listOf(
                LinkToken.Text("look at "),
                LinkToken.Link("[foo.ts](file:///x/foo.ts)", "foo.ts", "file:///x/foo.ts"),
                LinkToken.Text(" here"),
            ),
            Codec.tokenizeReferenceLinks("look at [foo.ts](file:///x/foo.ts) here"),
        )
    }

    @Test
    fun `keeps the angle brackets of a wrapped destination`() {
        assertEquals(
            listOf(LinkToken.Link("[a b.ts](<file:///x/a b.ts>)", "a b.ts", "<file:///x/a b.ts>")),
            Codec.tokenizeReferenceLinks("[a b.ts](<file:///x/a b.ts>)"),
        )
    }

    @Test
    fun `empty destinations and empty labels`() {
        assertEquals(listOf(LinkToken.Link("[a]()", "a", "")), Codec.tokenizeReferenceLinks("[a]()"))
        assertEquals(listOf(LinkToken.Text("[](x)")), Codec.tokenizeReferenceLinks("[](x)"))
    }

    @Test
    fun `a stray bracket does not hide a later link`() {
        assertEquals(
            listOf(LinkToken.Text("text [oops "), LinkToken.Link("[x](file:///y)", "x", "file:///y")),
            Codec.tokenizeReferenceLinks("text [oops [x](file:///y)"),
        )
    }

    @Test
    fun `folds links to their labels`() {
        assertEquals("README.md fix the bug", Codec.foldLinks("[README.md](file:///repo/README.md) fix the bug"))
        assertEquals(
            "ask @Codex about a (1)",
            Codec.foldLinks("""ask [@Codex](codeg://agent/codex) about [a \(1\)](<file:///r/a (1)>)"""),
        )
    }
}

/** The field shows tokens; the prompt carries links. Encoding and decoding must round-trip, and a
 *  token the user edited must fall back to plain text. */
class ComposerReferenceCodecTest {
    private val root = "/repo"
    private val app = ComposerReference.file("app.ts", "src/app.ts", root)
    private val codex = ComposerReference.agent(AgentType.CODEX, "Codex")

    @Test
    fun `encodes registered tokens`() {
        assertEquals("look at [app.ts](file:///repo/src/app.ts) please", Codec.encode("look at @src/app.ts please", listOf(app)))
        assertEquals(
            "[@Codex](codeg://agent/codex) review [app.ts](file:///repo/src/app.ts)",
            Codec.encode("@Codex review @src/app.ts", listOf(app, codex)),
        )
    }

    @Test
    fun `encodes next to CJK text`() {
        assertEquals("请看[app.ts](file:///repo/src/app.ts)谢谢", Codec.encode("请看@src/app.ts谢谢", listOf(app)))
        assertEquals("让[@Codex](codeg://agent/codex)，帮我", Codec.encode("让@Codex，帮我", listOf(codex)))
    }

    @Test
    fun `an edited token goes out as plain text`() {
        assertEquals("@src/app.tsx", Codec.encode("@src/app.tsx", listOf(app)))
        assertEquals("@src/app.ts.bak", Codec.encode("@src/app.ts.bak", listOf(app)))
        assertEquals("@src/app", Codec.encode("@src/app", listOf(app)))
        assertEquals("glued to a word: an email, not a mention", "x@src/app.ts", Codec.encode("x@src/app.ts", listOf(app)))
        assertEquals("@Codexx", Codec.encode("@Codexx", listOf(codex)))
    }

    @Test
    fun `a sentence end and closing punctuation still end the token`() {
        assertEquals("see [app.ts](file:///repo/src/app.ts).", Codec.encode("see @src/app.ts.", listOf(app)))
        assertEquals("`(` cannot start a mention", "(@src/app.ts)", Codec.encode("(@src/app.ts)", listOf(app)))
        assertEquals("[app.ts](file:///repo/src/app.ts), ok", Codec.encode("@src/app.ts, ok", listOf(app)))
    }

    @Test
    fun `the longer token wins`() {
        val dir = ComposerReference.file("src", "src", root)
        assertEquals(
            "[app.ts](file:///repo/src/app.ts) and [src](file:///repo/src)",
            Codec.encode("@src/app.ts and @src", listOf(dir, app)),
        )
    }

    @Test
    fun `nothing registered leaves the text alone`() {
        assertEquals("@src/app.ts", Codec.encode("@src/app.ts", emptyList()))
    }

    private fun assertRoundTrip(text: String, references: List<ComposerReference>) {
        val wire = Codec.encode(text, references)
        assertNotEquals("nothing was encoded in $text", text, wire)
        val decoded = Codec.decode(wire, root)
        assertEquals(text, decoded.text)
        assertEquals(wire, Codec.encode(decoded.text, decoded.references))
    }

    @Test
    fun `round-trips plain mentions`() {
        assertRoundTrip("look at @src/app.ts please", listOf(app))
        assertRoundTrip("@Codex review @src/app.ts", listOf(app, codex))
    }

    @Test
    fun `round-trips spaces and parentheses`() {
        val odd = ComposerReference.file("my file (1).md", "docs/my file (1).md", root)
        assertEquals("file:///repo/docs/my%20file%20(1).md", odd.uri)
        assertEquals("""[my file \(1\).md](<file:///repo/docs/my%20file%20(1).md>)""", odd.markdown)
        assertRoundTrip("open @docs/my file (1).md now", listOf(odd))
    }

    @Test
    fun `round-trips CJK`() {
        assertRoundTrip("请看@文档/说明.md，谢谢", listOf(ComposerReference.file("说明.md", "文档/说明.md", root)))
        assertRoundTrip("让@克劳德 处理", listOf(ComposerReference.agent(AgentType.CLAUDE_CODE, "克劳德")))
    }

    @Test
    fun `decode keeps what the composer did not produce`() {
        val outside = "[x.md](file:///elsewhere/x.md)"
        assertEquals(outside, Codec.decode(outside, root).text)
        assertTrue(Codec.decode(outside, root).references.isEmpty())
        val session = "[Login](codeg://session/123)"
        assertEquals(session, Codec.decode(session, root).text)
        val web = "[docs](https://example.com)"
        assertEquals(web, Codec.decode(web, null).text)
        val fileWithoutRoot = "[app.ts](file:///repo/src/app.ts)"
        assertEquals(fileWithoutRoot, Codec.decode(fileWithoutRoot, null).text)
    }

    @Test
    fun `decode keeps a link its token would not survive`() {
        // Glued to the word before: `see@src/app.ts` would not encode back.
        val glued = "see[app.ts](file:///repo/src/app.ts)"
        assertEquals(glued, Codec.decode(glued, root).text)
        // Two links back to back: `@a@b` would not either.
        val adjacent = "[a](file:///repo/a)[b](file:///repo/b)"
        assertEquals(adjacent, Codec.decode(adjacent, root).text)
        // Followed by more of a name.
        val continued = "[app.ts](file:///repo/src/app.ts)x"
        assertEquals(continued, Codec.decode(continued, root).text)
    }

    @Test
    fun `decode folds agent links and strips their at`() {
        val decoded = Codec.decode("ask [@Codex](codeg://agent/codex) now", null)
        assertEquals("ask @Codex now", decoded.text)
        assertEquals(
            listOf(ComposerReference("@Codex", ComposerReference.Kind.AGENT, "Codex", "codeg://agent/codex")),
            decoded.references,
        )
    }

    @Test
    fun `relative path requires the exact uri the builder makes`() {
        assertEquals("src/app.ts", Codec.relativePath("file:///repo/src/app.ts", "/repo"))
        assertEquals("src/app.ts", Codec.relativePath("file:///repo/src/app.ts", "/repo/"))
        assertNull(Codec.relativePath("file:///repo2/app.ts", "/repo"))
        assertNull(Codec.relativePath("file:///repo/a.ts#L10", "/repo"))
        assertEquals("a b.ts", Codec.relativePath("file:///repo/a%20b.ts", "/repo"))
        // A malformed escape is not a path.
        assertNull(Codec.relativePath("file:///repo/a%zz.ts", "/repo"))
    }
}
