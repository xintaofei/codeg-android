package app.codeg.android.core.model

import app.codeg.android.core.network.CodegJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed-transcript protocol. What has to hold: a pre-0.24.0 server (which
 * answers every request with the whole transcript and none of these fields) must
 * come out indistinguishable from the old behaviour, and the two selectors must
 * never both reach the wire — the server rejects a request carrying both.
 */
class TurnWindowTest {

    private fun detail(extra: String) = CodegJson.response.decodeFromString(
        ConversationDetail.serializer(),
        """{"summary":{"id":1,"folder_id":2,"agent_type":"claude_code","status":"completed",
            "created_at":"2026-08-11T00:00:00Z","updated_at":"2026-08-11T00:00:00Z"},
            "turns":[]$extra}""",
    )

    @Test
    fun `a windowed response reports its coordinates`() {
        val d = detail(
            ""","turns_offset":120,"turns_total":400,"assistant_turns_before_offset":58,
              "prefix_hash":"7060d8542023feb2","uncovered_prefix_max_ts":"2026-08-10T09:00:00Z"""",
        )

        val window = d.window!!
        assertEquals(120, window.offset)
        assertEquals(400, window.total)
        assertEquals("7060d8542023feb2", window.prefixHash)
        assertEquals(58, d.assistantTurnsBeforeOffset)
        assertEquals(400, d.effectiveTotal)
        assertTrue(window.hasOlder)
    }

    @Test
    fun `a legacy full response has no window`() {
        // The absence of these fields is the ONLY signal that the server is older.
        // Reporting a window here would let the client page against an endpoint
        // that does not exist, and compare fingerprints it never received.
        val d = detail("")

        assertNull(d.window)
        assertEquals(0, d.effectiveTotal)
        assertNull(d.turnsOffset)
    }

    @Test
    fun `a half-populated window is treated as no window`() {
        // The server sets all of them together, so a partial set means something
        // reshaped the payload — safer to fall back than to page on half a contract.
        assertNull(detail(""","turns_offset":120,"turns_total":400""").window)
        assertNull(detail(""","turns_offset":120,"prefix_hash":"7060d8542023feb2"""").window)
        assertNull(detail(""","turns_total":400,"prefix_hash":"7060d8542023feb2"""").window)

        // And the total must not slip past the gate either: it drives the "did the
        // reply land?" comparison, so a total from a payload too broken to page
        // against would silently end turns early.
        assertEquals(0, detail(""","turns_total":400""").effectiveTotal)
    }

    @Test
    fun `a window at the top of the transcript has nothing older`() {
        val d = detail(""","turns_offset":0,"turns_total":12,"prefix_hash":"cbf29ce484222325"""")

        assertFalse(d.window!!.hasOlder)
    }

    @Test
    fun `each selector travels alone`() {
        // Both at once is a 400 from the server, and `explicitNulls = false` is what
        // keeps the unused one off the wire entirely rather than sending it as null.
        fun encode(w: TurnWindow?) = CodegJson.request.encodeToString(
            ConversationDetailBody.serializer(),
            ConversationDetailBody.of(7, w),
        )

        assertEquals("""{"conversationId":7,"tailTurns":120}""", encode(TurnWindow.Tail(120)))
        assertEquals("""{"conversationId":7,"fromIndex":120}""", encode(TurnWindow.FromIndex(120)))
        // No selector asks for everything — what a legacy server always answers.
        assertEquals("""{"conversationId":7}""", encode(null))
    }

    @Test
    fun `a history page decodes its seam proof`() {
        val page = CodegJson.response.decodeFromString(
            ConversationTurnsPage.serializer(),
            """{"turns":[],"turns_offset":0,"turns_total":400,"assistant_turns_before_offset":0,
                "prefix_hash":"cbf29ce484222325","prefix_hash_before_index":"7060d8542023feb2"}""",
        )

        assertEquals(0, page.turnsOffset)
        assertEquals(400, page.turnsTotal)
        assertEquals("cbf29ce484222325", page.prefixHash)
        assertEquals("7060d8542023feb2", page.prefixHashBeforeIndex)
        assertNull(page.uncoveredPrefixMaxTs)
    }
}
