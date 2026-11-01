package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ConversationTurnsPage
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TranscriptWindowTest {
    private val seed = "cbf29ce484222325"
    private val turns = listOf(
        turn("u", TurnRole.USER, 1_700_000_001),
        turn("a", TurnRole.ASSISTANT, 1_700_000_002),
        turn("s", TurnRole.SYSTEM, 1_700_000_003),
    )

    @Test fun `fingerprint matches the shared Rust vector and ignores rewritten ids`() {
        assertEquals("7060d8542023feb2", extendTranscriptHash(seed, turns.take(1)))
        assertEquals("f171a62320fc10d3", extendTranscriptHash(seed, turns))
        assertEquals(extendTranscriptHash(seed, turns), extendTranscriptHash(seed, turns.map { it.copy(id = "changed") }))
        assertEquals(extendTranscriptHash(seed, turns), extendTranscriptHash(
            extendTranscriptHash(seed, turns.take(1))!!, turns.drop(1),
        ))
    }

    @Test fun `verified page prepends while retaining the loaded tail count`() {
        val window = TranscriptWindow(2, 3, extendTranscriptHash(seed, turns.take(2))!!)
        val page = page()
        assertEquals(TranscriptWindow(0, 3, seed), window.prepend(page, turns.drop(2)))
        assertEquals(TranscriptWindow(0, 3, seed), window.prepend(page.copy(turnsTotal = 4), turns.drop(2)))
    }

    @Test fun `rewritten truncated overlapping or nonadvancing pages cannot join`() {
        val window = TranscriptWindow(2, 3, extendTranscriptHash(seed, turns.take(2))!!)
        val page = page()
        assertNull(window.prepend(page.copy(prefixHashBeforeIndex = seed), turns.drop(2)))
        assertNull(window.prepend(page.copy(prefixHash = "bad"), turns.drop(2)))
        assertNull(window.prepend(page.copy(turnsTotal = 1), turns.drop(2)))
        assertNull(window.prepend(page.copy(turnsOffset = 2), turns.drop(2)))
        assertNull(window.prepend(page.copy(turns = turns), turns.drop(2)))
        assertNull(window.prepend(page, turns))
        assertNull(window.prepend(page.copy(turns = listOf(turns[1], turns[0])), turns.drop(2)))
    }

    @Test fun `refresh requires the same global start and history`() {
        val window = TranscriptWindow(2, 3, "7060d8542023feb2")
        assertTrue(window.acceptsRefresh(window.copy(total = 20)))
        assertFalse(window.acceptsRefresh(window.copy(offset = 1)))
        assertFalse(window.acceptsRefresh(window.copy(prefixHash = seed)))
        assertFalse(window.acceptsRefresh(null))
    }

    @Test fun `send baseline includes locally folded replies and is unchanged by prepending`() {
        val window = TranscriptWindow(90, 100, seed)
        assertEquals(100, window.countIncludingLocal(10))
        assertEquals(102, window.countIncludingLocal(12))
        assertEquals(102, window.copy(offset = 80).countIncludingLocal(22))
    }

    private fun page() = ConversationTurnsPage(
        turns.take(2), 0, 3, seed, extendTranscriptHash(seed, turns.take(2))!!,
    )

    private fun turn(id: String, role: TurnRole, seconds: Long) = MessageTurn(
        id = id, role = role, blocks = emptyList(), timestamp = Instant.ofEpochSecond(seconds),
    )
}
