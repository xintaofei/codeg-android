package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Filling a locally handed-off batch's footer figures in from the transcript. */
class TurnMetadataTest {

    private fun turn(id: String, role: TurnRole, secs: Long = 0, usage: TurnUsage? = null, duration: Int? = null, model: String? = null, done: Long? = null) =
        MessageTurn(
            id, role, listOf(ContentBlock.Text("x")), Instant.ofEpochSecond(secs),
            usage = usage, durationMs = duration, model = model, completedAt = done?.let(Instant::ofEpochSecond),
        )

    private fun user(id: String) = turn(id, TurnRole.USER)
    private fun local(id: String) = turn(id, TurnRole.ASSISTANT)
    private fun parsed(id: String, out: Int, duration: Int, model: String? = "m", done: Long = 100) =
        turn(id, TurnRole.ASSISTANT, usage = TurnUsage(outputTokens = out), duration = duration, model = model, done = done)

    @Test
    fun `the fingerprint matches the server's shared test vector`() {
        val turns = listOf(
            turn("a", TurnRole.USER, 1_700_000_001),
            turn("b", TurnRole.ASSISTANT, 1_700_000_002),
            turn("c", TurnRole.SYSTEM, 1_700_000_003),
        )
        assertEquals(FNV_SEED_HEX, extendPrefixFingerprint(FNV_SEED_HEX, emptyList()))
        assertEquals("7060d8542023feb2", extendPrefixFingerprint(FNV_SEED_HEX, turns.take(1)))
        assertEquals("f171a62320fc10d3", extendPrefixFingerprint(FNV_SEED_HEX, turns))
        // Extending in steps is extending at once — how a window's hash is carried to the batch.
        assertEquals("f171a62320fc10d3", extendPrefixFingerprint(extendPrefixFingerprint(FNV_SEED_HEX, turns.take(1))!!, turns.drop(1)))
        assertNull(extendPrefixFingerprint("not-a-hash", turns))
    }

    @Test
    fun `a reply the parser split finer adds up on the one local turn`() {
        val local = listOf(user("p1"), local("live-1"))
        val read = listOf(user("u1"), parsed("t1", out = 10, duration = 1_000, model = null), parsed("t2", out = 5, duration = 500, done = 200))
        val patched = applyMetadataPatches(local, turnMetadataPatches(local, read))[1]
        assertEquals(15, patched.usage?.outputTokens)
        assertEquals(1_500, patched.durationMs)
        assertEquals("m", patched.model)
        assertEquals(Instant.ofEpochSecond(200), patched.completedAt)
    }

    @Test
    fun `each round of a batch gets its own reply's figures`() {
        val local = listOf(user("p1"), local("live-1"), user("p2"), local("live-2"))
        val read = listOf(user("u1"), parsed("t1", 10, 1_000), user("u2"), parsed("t2", 3, 300), parsed("t3", 4, 400))
        val patched = applyMetadataPatches(local, turnMetadataPatches(local, read))
        assertEquals(10, patched[1].usage?.outputTokens)
        assertEquals(7, patched[3].usage?.outputTokens)
    }

    @Test
    fun `nothing is named while the newest round isn't written yet`() {
        val local = listOf(user("p1"), local("live-1"), user("p2"), local("live-2"))
        // The read holds the first round and the second prompt, not its reply.
        assertTrue(turnMetadataPatches(local, listOf(user("u1"), parsed("t1", 10, 1_000), user("u2"))).isEmpty())
    }

    @Test
    fun `a value already held is kept`() {
        val held = turn("live-1", TurnRole.ASSISTANT, model = "local-model")
        val patched = applyMetadataPatches(listOf(held), mapOf(0 to TurnMetadataPatch(TurnUsage(outputTokens = 1), 5, "other", null)))[0]
        assertEquals("local-model", patched.model)
        assertEquals(1, patched.usage?.outputTokens)
    }

    @Test
    fun `reads go on while the newest reply lacks figures, unless they can never verify`() {
        val bare = listOf(user("p1"), local("live-1"))
        assertEquals(MetadataSyncStep.RETRY, metadataSyncStep(bare, verified = true, readOffset = 12, batchStart = 12, usageOnlyRetries = 0))
        // Opened short of the batch start: the transcript is behind.
        assertEquals(MetadataSyncStep.RETRY, metadataSyncStep(bare, verified = false, readOffset = 10, batchStart = 12, usageOnlyRetries = 0))
        // Opened at it with another fingerprint: the prefix was rewritten.
        assertEquals(MetadataSyncStep.STOP, metadataSyncStep(bare, verified = false, readOffset = 12, batchStart = 12, usageOnlyRetries = 0))
        val noUsage = listOf(user("p1"), turn("live-1", TurnRole.ASSISTANT, done = 5))
        assertEquals(MetadataSyncStep.RETRY, metadataSyncStep(noUsage, true, 12, 12, usageOnlyRetries = 0))
        assertEquals(MetadataSyncStep.STOP, metadataSyncStep(noUsage, true, 12, 12, usageOnlyRetries = 1))
        val done = listOf(user("p1"), parsed("live-1", 1, 1))
        assertEquals(MetadataSyncStep.STOP, metadataSyncStep(done, true, 12, 12, usageOnlyRetries = 0))
    }

    @Test
    fun `a session just created has a known, empty prefix`() {
        assertEquals(FNV_SEED_HEX, batchPrefixHash(window = null, turns = emptyList()))
        // A pre-0.24.0 server's whole transcript: unknown.
        assertNull(batchPrefixHash(window = null, turns = listOf(user("u1"))))
        val window = app.codeg.android.core.model.TurnWindowState(offset = 0, total = 1, prefixHash = FNV_SEED_HEX)
        assertEquals(extendPrefixFingerprint(FNV_SEED_HEX, listOf(user("u1"))), batchPrefixHash(window, listOf(user("u1"))))
    }

    @Test
    fun `a steered note splits the reply into the turns the transcript holds`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("first part")
        assertTrue(b.appendSteer("n1", " also check the tests "))
        // Once per note.
        assertTrue(!b.appendSteer("n1", "also check the tests"))
        b.appendText("second part")
        val turns = b.snapshotAsMessageTurns()
        assertEquals(listOf("live-1", "${STEER_TURN_PREFIX}n1", "live-1-1"), turns.map { it.id })
        assertEquals(listOf(TurnRole.ASSISTANT, TurnRole.USER, TurnRole.ASSISTANT), turns.map { it.role })
        assertEquals(listOf(ContentBlock.Text("also check the tests")), turns[1].blocks)
        // A steer isn't a prompt of the batch: the server may not persist it as one.
        assertEquals(1, promptCount(listOf(user("p1")) + turns))
    }

    @Test
    fun `turns take the transcript's names only where every round lines up`() {
        val local = listOf(user("p1"), local("live-1"), user("p2"), local("live-2"))
        val read = listOf(user("u1"), parsed("turn-1", 1, 1), user("u2"), parsed("turn-3", 1, 1))
        val named = applyMetadataPatches(local, turnMetadataPatches(local, read))
        assertEquals(listOf(null, "turn-1", null, "turn-3"), named.map { it.sourceTurnId })
        // A first write wins: a later read doesn't rename.
        val again = applyMetadataPatches(named, turnMetadataPatches(named, read.map { if (it.id == "turn-3") it.copy(id = "turn-9") else it }))
        assertEquals("turn-3", again[3].sourceTurnId)
    }

    @Test
    fun `a split round or a turn this client never streamed leaves every turn unnamed`() {
        val local = listOf(user("p1"), local("live-1"), user("p2"), local("live-2"))
        // The parser split the first reply in two: the totals no longer line up.
        val split = listOf(user("u1"), parsed("turn-1", 1, 1), parsed("turn-2", 1, 1), user("u2"), parsed("turn-4", 1, 1))
        assertTrue(applyMetadataPatches(local, turnMetadataPatches(local, split)).all { it.sourceTurnId == null })
        // Split one way and short the other: the totals match, but round by round they don't.
        val three = local + listOf(user("p3"), local("live-3"))
        val cancel = listOf(user("u1"), parsed("turn-1", 1, 1), parsed("turn-2", 1, 1), user("u2"), user("u3"), parsed("turn-5", 1, 1))
        assertTrue(applyMetadataPatches(three, turnMetadataPatches(three, cancel)).all { it.sourceTurnId == null })
    }
}
