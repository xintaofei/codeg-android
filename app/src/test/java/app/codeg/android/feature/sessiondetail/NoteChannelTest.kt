package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the composer offers for a mid-turn note.
 *
 * Three independent facts feed this, each arriving from a different place at a different
 * time — the user's setting from the settings endpoint, the session's capability from the
 * snapshot and from every attach frame, and a mid-session revocation only this client
 * remembers. Every wrong answer here is user-visible in one of two ways: an affordance the
 * server will refuse, or a missing one on a session that would have accepted the note.
 */
class NoteChannelTest {

    private fun channel(
        featureEnabled: Boolean = true,
        toolAvailable: Boolean = false,
        nativeAvailable: Boolean = false,
        nativeRevoked: Boolean = false,
    ) = noteChannel(featureEnabled, toolAvailable, nativeAvailable, nativeRevoked)

    @Test
    fun `either channel makes a note sendable`() {
        assertTrue(channel(toolAvailable = true).supported)
        assertTrue(channel(nativeAvailable = true).supported)
    }

    @Test
    fun `only the native channel is instant`() {
        assertFalse(channel(toolAvailable = true).instant)
        assertTrue(channel(nativeAvailable = true).instant)
    }

    @Test
    fun `a session with neither channel offers nothing`() {
        assertEquals(NoteChannel(supported = false, instant = false), channel())
    }

    @Test
    fun `the setting overrides any capability`() {
        // The kill switch is the user's current wish, and it wins over what the session can
        // technically still do — native steering in particular stays live server-side after
        // the feature is switched off, so nothing but this stops us offering it.
        assertEquals(
            NoteChannel(supported = false, instant = false),
            channel(featureEnabled = false, toolAvailable = true, nativeAvailable = true),
        )
    }

    @Test
    fun `a revoked native channel is gone even while the read still reports it`() {
        // The revocation is mid-session and one-way; a capability read issued before it
        // resolves afterwards still carrying the old answer, and this is what outranks it.
        assertEquals(
            NoteChannel(supported = false, instant = false),
            channel(nativeAvailable = true, nativeRevoked = true),
        )
    }

    @Test
    fun `revoking native leaves the pull channel intact`() {
        // Only the native half is revoked. A session that also has the MCP tool keeps taking
        // notes — they just wait for the agent to check instead of landing in the turn.
        assertEquals(
            NoteChannel(supported = true, instant = false),
            channel(toolAvailable = true, nativeAvailable = true, nativeRevoked = true),
        )
    }

    @Test
    fun `instant is never claimed without support`() {
        // Structural: "lands instantly" is a refinement of "can be sent", so no combination
        // of inputs may produce the second without the first.
        val flags = listOf(true, false)
        for (enabled in flags) {
            for (tool in flags) {
                for (native in flags) {
                    for (revoked in flags) {
                        val c = channel(enabled, tool, native, revoked)
                        assertFalse(
                            "instant without supported for ($enabled,$tool,$native,$revoked)",
                            c.instant && !c.supported,
                        )
                    }
                }
            }
        }
    }
}
