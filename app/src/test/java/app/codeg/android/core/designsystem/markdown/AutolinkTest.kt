package app.codeg.android.core.designsystem.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bare urls in prose, and the punctuation that must NOT come with them.
 *
 * The case that motivated this — `(https://github.com/o/r)。`, an ordinary
 * sentence in Chinese — kept the `)` inside the href, so the tapped link 404'd.
 * GFM literals end only at whitespace, `<`, or end of input, so the trailing-
 * punctuation rules that would have trimmed the `)` never fire; the fix cuts at
 * the CJK punctuation first and then re-runs them on what that exposes.
 *
 * Recognition is pinned against micromark 2.1.0, which the web renders through —
 * not against the GFM spec prose, which differs from it in both directions.
 */
class AutolinkTest {

    private fun links(raw: String): List<Pair<String, String>> {
        val built = inlineMarkdown(raw, Color.Blue, Color.Gray, Color.Black)
        return built.getLinkAnnotations(0, built.length).map { range ->
            val url = (range.item as LinkAnnotation.Url).url
            built.substring(range.start, range.end) to url
        }
    }

    @Test
    fun `a url before a fullwidth stop leaves its wrapping paren behind`() {
        // The reported bug, verbatim: the `)` and the `。` both belong to the
        // sentence, and neither may reach the href.
        val raw = "已推送: dca27f1 → origin/main (https://github.com/o/hap_api)。"
        val built = inlineMarkdown(raw, Color.Blue, Color.Gray, Color.Black)

        assertEquals(
            listOf("https://github.com/o/hap_api" to "https://github.com/o/hap_api"),
            links(raw),
        )
        // Nothing is dropped from the visible text — the tail moves out of the
        // link, it does not disappear.
        assertEquals(raw, built.text)
    }

    @Test
    fun `every CJK punctuation mark ends the url`() {
        for (mark in listOf("。", "，", "、", "？", "！", "：", "；", "）", "】", "」", "》")) {
            assertEquals(
                "cut at $mark",
                listOf("https://x.com/a" to "https://x.com/a"),
                links("见 https://x.com/a$mark"),
            )
        }
    }

    @Test
    fun `balanced parentheses stay inside the url`() {
        // The unbalanced-paren rule is what separates a wrapper from a path.
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Foo_(bar)" to
                    "https://en.wikipedia.org/wiki/Foo_(bar)",
            ),
            links("see https://en.wikipedia.org/wiki/Foo_(bar)。"),
        )
    }

    @Test
    fun `ASCII trailing punctuation is trimmed too`() {
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("go to https://x.com/a."))
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("go to https://x.com/a,"))
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("(https://x.com/a)"))
        // `&amp;` at the end is a whole entity, so it goes with the trail.
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("https://x.com/a&amp;"))
        // …but one that is not an entity is url content.
        assertEquals(listOf("https://x.com/a&b" to "https://x.com/a&b"), links("https://x.com/a&b"))
    }

    @Test
    fun `a www literal gets the scheme mdast would have added`() {
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("see www.example.com"))
    }

    @Test
    fun `a url glued to CJK text still links`() {
        // THE case this port exists for. micromark's protocol rule is only "the
        // previous character is not an ASCII letter" (`previousProtocol`), so a
        // url written straight after Chinese text — no space, the ordinary way —
        // is a link. Reading the spec's www rule onto both forms would leave the
        // commonest Chinese shape dead on Android while it works on the web.
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("详见https://x.com/a"))
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("=https://x.com/a"))
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("1https://x.com/a"))
        // An ASCII letter is the one thing that blocks it.
        assertTrue(links("ahttps://x.com/a").isEmpty())
        // `www.` keeps its own, stricter rule — a delimiter allowlist.
        assertTrue(links("详见www.example.com").isEmpty())
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("(www.example.com"))
    }

    @Test
    fun `the www delimiter is a markdown space, not any unicode space`() {
        // `previousWww` tests `markdownLineEndingOrSpace`, so an ideographic or
        // non-breaking space does NOT open a literal — the two shapes most
        // likely to show up in Chinese prose and in text pasted from a browser.
        assertTrue(links("见\u3000www.example.com").isEmpty())
        assertTrue(links("see\u00A0www.example.com").isEmpty())
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("see\nwww.example.com"))
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("see\twww.example.com"))
        // The protocol form has no such rule; only an ASCII letter blocks it.
        assertEquals(listOf("https://x.com" to "https://x.com"), links("见\u3000https://x.com"))
    }

    @Test
    fun `a literal inside an unclosed bracket is not a link`() {
        // micromark's `previousUnbalanced`: a `[` that has not met its `]` may
        // still become a label, so nothing inside it is autolinked.
        assertTrue(links("[www.example.com").isEmpty())
        assertTrue(links("[www.example.com]").isEmpty())
        assertTrue(links("[foo https://x.com/a").isEmpty())
        // …and the count follows emphasis, or the same text linked once the
        // renderer stepped into a `**…**`.
        assertTrue(links("[a **https://x.com/a**").isEmpty())
        // Once the bracket closes, the suppression lifts.
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("[foo] https://x.com/a"))
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("a [b] www.example.com"))
        // A nested pair has to close all the way out.
        assertTrue(links("[a [b] https://x.com/a").isEmpty())
        // …and the count survives the emphasis it was opened inside. micromark
        // tracks brackets across the whole paragraph, not per delimiter run, so
        // the `[` here keeps suppressing after the `*` closes.
        assertTrue(links("*[a* https://x.com/b").isEmpty())
        assertTrue(links("*[a* www.example.com").isEmpty())
        assertTrue(links("~~[a~~ https://x.com/b").isEmpty())
        // Once its `]` arrives, later text links again.
        assertEquals(
            listOf("https://x.com/b" to "https://x.com/b"),
            links("*[a* b] https://x.com/b"),
        )
        // A stray `]` with no opener suppresses nothing.
        assertEquals(listOf("www.example.com" to "http://www.example.com"), links("a] www.example.com"))
        // An explicit link is resolved, not open — its own `]` is consumed with it.
        assertEquals(
            listOf("t" to "https://real.example/", "https://x.com/a" to "https://x.com/a"),
            links("[t](https://real.example/) https://x.com/a"),
        )
    }

    @Test
    fun `the domain rule is micromark's, not the spec prose`() {
        // micromark dropped the spec's "must contain a period" — `domainAfter`
        // carries the comment saying so — so a dotless host IS a link.
        assertEquals(listOf("http://localhost/a" to "http://localhost/a"), links("see http://localhost/a"))
        // An underscore in either of the last two segments still rejects it.
        assertTrue(links("see https://x_y.com/a").isEmpty())
        assertTrue(links("see https://a.x_y.com/a").isEmpty())
        // One in the PATH is not the domain's business.
        assertEquals(listOf("https://x.com/a_b" to "https://x.com/a_b"), links("see https://x.com/a_b"))
        // Nothing behind the scheme is nothing to link.
        assertTrue(links("https:// is a scheme").isEmpty())
        // Trailing-dot host: the stop is trimmed first, so what remains is valid.
        assertEquals(listOf("https://x.com" to "https://x.com"), links("at https://x.com."))
    }

    @Test
    fun `the domain is walked, not split off at the first slash`() {
        // `domainInside` ends the domain at the first unicode punctuation other
        // than `-`, which is what puts the underscore in the LAST segment here.
        // Splitting the host at `/?#` instead reads `a.b_c:d` as one segment of
        // a longer host and lets it through.
        assertTrue(links("see https://a.b_c:d/x").isEmpty())
        // Same rule from the other side: `,` ends a segment that saw nothing,
        // and `domainAfter` rejects on `!seen`.
        assertTrue(links("see https://,x.com/a").isEmpty())
        // `seen` spans the domain rather than the segment, so an empty LAST
        // segment is fine as long as an earlier one was not.
        assertEquals(listOf("https://x./a" to "https://x./a"), links("see https://x./a"))
        // A port is punctuation-terminated too, and perfectly valid.
        assertEquals(listOf("http://localhost:3090/a" to "http://localhost:3090/a"), links("see http://localhost:3090/a"))
        // `-` is the one punctuation mark the domain walk does not stop at.
        assertEquals(listOf("https://a-b.com" to "https://a-b.com"), links("see https://a-b.com"))
    }

    @Test
    fun `the character right after the slashes may not be punctuation`() {
        // `afterProtocol` rejects any unicode punctuation there, and unlike
        // `domainInside` it grants `-` no exception.
        assertTrue(links("see https://-x.com").isEmpty())
        assertTrue(links("see https://.x.com").isEmpty())
        assertTrue(links("see https://_x.com").isEmpty())
        // The guard is the protocol form's alone: a www literal's domain starts
        // at the `w`, so there is no post-scheme position to test.
        assertEquals(listOf("www.x-y.com" to "http://www.x-y.com"), links("see www.x-y.com"))
    }

    @Test
    fun `a delimited autolink does not swallow its closing bracket`() {
        // micromark never meets this: its core tokenizer claims `<…>` as a
        // delimited autolink before the literal rules run. This renderer has no
        // such rule, so without `>` as a terminator the href 404s on the `>`.
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("<https://x.com/a>"))
        assertEquals(listOf("https://x.com" to "https://x.com"), links("见<https://x.com>后"))
    }

    @Test
    fun `an uppercase scheme is folded in the href but not in the text`() {
        // GFM matches the scheme case-insensitively, but `IntentFilter` matches
        // its data scheme case-sensitively against the lowercase one browsers
        // register — so an unfolded `HTTPS:` resolves no activity and throws.
        assertEquals(listOf("HTTPS://x.com/a" to "https://x.com/a"), links("see HTTPS://x.com/a"))
        assertEquals(listOf("Http://x.com" to "http://x.com"), links("see Http://x.com"))
        assertEquals(listOf("mail" to "mailto:a@b.com"), links("[mail](MAILTO:a@b.com)"))
        // Only the scheme folds; a path is case-sensitive to the server.
        assertEquals(listOf("HTTPS://x.com/A/b" to "https://x.com/A/b"), links("see HTTPS://x.com/A/b"))
    }

    @Test
    fun `an explicit markdown link keeps its own href and does not nest`() {
        // The label happens to be a url too; it must not become a second link
        // inside the first.
        assertEquals(
            listOf("https://x.com/a" to "https://real.example/target"),
            links("[https://x.com/a](https://real.example/target)"),
        )
        // …and the suppression has to survive emphasis inside the label, or the
        // inner annotation overlaps the outer one and a tap opens the wrong url.
        assertEquals(
            listOf("https://x.com/a" to "https://real.example/target"),
            links("[**https://x.com/a**](https://real.example/target)"),
        )
        assertEquals(
            listOf("https://x.com/a" to "https://real.example/target"),
            links("[~~*https://x.com/a*~~](https://real.example/target)"),
        )
    }

    @Test
    fun `a destination's balanced parentheses are not truncated`() {
        assertEquals(
            listOf("Wikipedia" to "https://en.wikipedia.org/wiki/Function_(mathematics)"),
            links("[Wikipedia](https://en.wikipedia.org/wiki/Function_(mathematics))"),
        )
    }

    @Test
    fun `links do not nest, so no two annotations cover the same character`() {
        // CommonMark forbids a link inside a link at any depth: the INNER one
        // wins and the outer brackets stay literal. Emitting both gave Compose
        // one annotation contained in another, leaving the destination a tap
        // opens undefined.
        val built = inlineMarkdown(
            "[a [b](https://y.com) c](https://z.com)",
            Color.Blue,
            Color.Gray,
            Color.Black,
        )
        assertEquals("[a b c](https://z.com)", built.text)
        assertEquals(
            listOf("b" to "https://y.com", "https://z.com" to "https://z.com"),
            links("[a [b](https://y.com) c](https://z.com)"),
        )
        assertEquals(
            listOf("x" to "https://y.com", "https://z.com" to "https://z.com"),
            links("[[x](https://y.com)](https://z.com)"),
        )
        // Emphasis in the label does not hide the inner link from the check.
        assertEquals(
            listOf("x" to "https://y.com", "https://z.com" to "https://z.com"),
            links("[**[x](https://y.com)**](https://z.com)"),
        )
        // An IMAGE may sit inside a link, so the outer one survives — and the
        // label is consumed rather than printed as raw markdown, even though
        // the picture itself is still missing.
        val image = inlineMarkdown(
            "[![alt](https://img.example/i.png)](https://z.com)",
            Color.Blue,
            Color.Gray,
            Color.Black,
        )
        assertEquals("!alt", image.text)
        assertEquals(
            listOf("!alt" to "https://z.com"),
            links("[![alt](https://img.example/i.png)](https://z.com)"),
        )
    }

    @Test
    fun `markdown inside a code span is not a nested link`() {
        // The nesting check has to skip code spans for the same reason the
        // parser does: the brackets here are code, and reading them as a link
        // costs the label the only real link it had — and leaks the
        // destination into the text as a bare autolink.
        assertEquals(
            listOf("a [b](https://y.com) c" to "https://z.com"),
            links("[a `[b](https://y.com)` c](https://z.com)"),
        )
    }

    @Test
    fun `inside a link label a url is just text, so emphasis works normally`() {
        // The literal mask exists to stop a url's `_` closing an emphasis run.
        // Inside a label there is no literal to protect — autolinking is off —
        // and masking there only stopped the underscores from italicising,
        // printing them instead.
        val built = inlineMarkdown(
            "[foo _https://a.com/x_/y](https://z.com)",
            Color.Blue,
            Color.Gray,
            Color.Black,
        )
        assertEquals("foo https://a.com/x/y", built.text)
        assertEquals(listOf("foo https://a.com/x/y" to "https://z.com"), links("[foo _https://a.com/x_/y](https://z.com)"))
        // …while outside a label the mask still holds.
        assertEquals(
            listOf("https://x.com/Foo_(bar)" to "https://x.com/Foo_(bar)"),
            links("_https://x.com/Foo_(bar)"),
        )
    }

    @Test
    fun `a delimiter the walk never reaches as text cannot close emphasis`() {
        // The walk jumps a code span, an explicit link and an autolink whole, so
        // a `*`/`_`/`~~` inside one is never offered as a delimiter — but a bare
        // `indexOf` still finds it, and every miss truncates something visible.

        // In a link DESTINATION: closing here breaks the syntax open and links
        // the truncated url.
        assertEquals(
            listOf("foo" to "https://x.com/p_"),
            links("_[foo](https://x.com/p_)_"),
        )
        // In a link LABEL: the label has its own run to close.
        assertEquals(
            listOf("a_b" to "https://z.com"),
            links("_[a_b](https://z.com)_"),
        )
        // In a code span: closing here loses the span and prints its backticks.
        assertEquals(
            "a x*y b",
            inlineMarkdown("*a `x*y` b*", Color.Blue, Color.Gray, Color.Black).text,
        )
        assertEquals(
            "a x_y b",
            inlineMarkdown("_a `x_y` b_", Color.Blue, Color.Gray, Color.Black).text,
        )
        // An UNterminated backtick is plain text, so what follows stays available.
        assertEquals(
            "a `x y b",
            inlineMarkdown("a `x *y* b", Color.Blue, Color.Gray, Color.Black).text,
        )
        // …and all of it holds inside a link LABEL too. No literal is emitted
        // there, but the code spans and links around it are still jumped whole.
        assertEquals(
            "a x*y b",
            inlineMarkdown("[*a `x*y` b*](https://z.com)", Color.Blue, Color.Gray, Color.Black).text,
        )
        assertEquals(
            listOf("a x*y b" to "https://z.com"),
            links("[*a `x*y` b*](https://z.com)"),
        )
    }

    @Test
    fun `the mask follows the bracket depth of each url, not of the emphasis`() {
        // Two shapes that pull in opposite directions, and both open their
        // emphasis inside the bracket — so no test taken where the run OPENS
        // can tell them apart. The mask has to know where each URL sits.

        // Past the `]`, so the url links, and the mask must cover it: otherwise
        // its own `_` closes the run and the link points at `https://a.com/x`
        // under text that still reads `…/x_/y`.
        assertEquals(
            listOf("https://a.com/x_/y" to "https://a.com/x_/y"),
            links("[_foo] https://a.com/x_/y_"),
        )
        // Inside the bracket, so the url does NOT link, and the mask must leave
        // it alone: otherwise the run stays open until the `_` in the next
        // link's destination and swallows the markdown in between.
        val cited = "[note _https://a.com/x_/y] [results](https://example.com/search?q=foo_)"
        assertEquals(
            "[note https://a.com/x/y] results",
            inlineMarkdown(cited, Color.Blue, Color.Gray, Color.Black).text,
        )
        assertEquals(
            listOf("results" to "https://example.com/search?q=foo_"),
            links(cited),
        )
        // The unclosed-bracket case falls out of the same rule.
        assertTrue(links("[foo _https://a.com/x_/y").isEmpty())
        assertEquals(
            "[foo https://a.com/x/y",
            inlineMarkdown("[foo _https://a.com/x_/y", Color.Blue, Color.Gray, Color.Black).text,
        )
    }

    @Test
    fun `a label's own brackets are balanced before the destination is looked for`() {
        // Stopping at the first `]` missed the `(` behind the real one, so the
        // markdown rendered as text AND the destination showed up on its own as
        // an autolink — a link the author never wrote at all.
        assertEquals(
            listOf("https://x.com/p/[id]" to "https://real.example/"),
            links("[https://x.com/p/[id]](https://real.example/)"),
        )
        assertEquals(listOf("see [1]" to "https://x.com/a"), links("[see [1]](https://x.com/a)"))
    }

    @Test
    fun `an underscore between word characters is not emphasis`() {
        // CommonMark's intraword rule, which holds for `_` and not for `*`.
        // Without it the italics split the url and each half autolinked on its
        // own: `_https://x_y.com` produced a tappable `https://x`.
        assertTrue(links("_https://x_y.com").isEmpty())
        assertEquals(
            listOf("https://a_b.c.d.com" to "https://a_b.c.d.com"),
            links("_https://a_b.c.d.com"),
        )
        // The plain-text half of the same rule: an identifier keeps its shape.
        val built = inlineMarkdown("call snake_case_name now", Color.Blue, Color.Gray, Color.Black)
        assertEquals("call snake_case_name now", built.text)
    }

    @Test
    fun `emphasis cannot close on a delimiter inside a url`() {
        // The literal is claimed first, as micromark's text phase does it.
        // Otherwise the `_` of `Foo_` closes the run opened before `https`, and
        // the truncated `https://x.com/Foo` becomes the tappable destination.
        assertEquals(
            listOf("https://x.com/Foo_(bar)" to "https://x.com/Foo_(bar)"),
            links("_https://x.com/Foo_(bar)"),
        )
        assertEquals(
            listOf("https://x.com/a*b" to "https://x.com/a*b"),
            links("*https://x.com/a*b*"),
        )
        assertEquals(
            listOf("https://x.com/a~b" to "https://x.com/a~b"),
            links("~~https://x.com/a~b~~"),
        )
        // …while a delimiter OUTSIDE the url still closes normally.
        assertEquals(listOf("https://x.com/a" to "https://x.com/a"), links("**https://x.com/a**"))
    }

    @Test
    fun `a destination Compose cannot open is styled but inert`() {
        // `LinkAnnotation.Url` fires ACTION_VIEW unguarded: `file://` throws
        // FileUriExposedException on API 24+, and a scheme with no activity
        // throws ActivityNotFoundException. Neither may become a tappable link.
        val built = inlineMarkdown("[open](file:///sdcard/x)", Color.Blue, Color.Gray, Color.Black)
        assertTrue(built.getLinkAnnotations(0, built.length).isEmpty())
        assertEquals("open", built.text)
        assertTrue(links("[ref](codeg://session/7)").isEmpty())
        // The schemes that do survive the handler still link.
        assertEquals(listOf("mail" to "mailto:a@b.com"), links("[mail](mailto:a@b.com)"))
    }

    @Test
    fun `a url inside a code span stays literal`() {
        assertTrue(links("run `curl https://x.com/a`").isEmpty())
    }

    @Test
    fun `autolinkEnd keeps a url that ends in path characters`() {
        // Guard the negative: nothing is trimmed when there is no trail.
        assertEquals("https://x.com/a/b".length, Autolink.autolinkEnd("https://x.com/a/b"))
        assertEquals("https://x.com/a?q=1".length, Autolink.autolinkEnd("https://x.com/a?q=1"))
    }

    @Test
    fun `a long punctuation run does not go quadratic`() {
        // micromark re-scans forward at every punctuation character, which is
        // O(n^2) on this shape — 16 000 dots measured at ~280 ms on the main
        // thread. The suffix table makes it one pass.
        val url = "https://x.com/" + ".".repeat(40_000) + "a"
        val started = System.nanoTime()
        assertEquals(url.length, Autolink.autolinkEnd(url))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("autolinkEnd took ${millis}ms", millis < 500)
    }
}
