package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionFailureRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client half of the AIR failure contract (web `session-failures.test.ts`). */
class SessionFailuresTest {

    private fun rec(id: String, revision: Long, severity: String = "warning", category: String = "connection", title: String = "Reconnecting") =
        SessionFailureRecord(id, revision, category, severity, title)

    @Test
    fun `only a strictly newer revision is accepted, and it re-arms the record`() {
        val one = SessionFailures.merge(emptyList(), listOf(rec("a", 1)))
        assertSame("a verbatim replay", one, SessionFailures.merge(one, listOf(rec("a", 1))))
        val two = SessionFailures.merge(one, listOf(rec("a", 2)))
        assertEquals(2L, two.single().revision)
        assertSame("a stale one", two, SessionFailures.merge(two, listOf(rec("a", 1))))
        val settled = SessionFailures.settle(one, SessionFailures.SettleScope.ALL)
        assertTrue(settled.single().resolved)
        // codex escalating the retry into the turn's terminal error: same id, higher revision.
        val escalated = SessionFailures.merge(settled, listOf(rec("a", 2, severity = "error")))
        assertFalse(escalated.single().resolved)
        assertEquals("error", escalated.single().severity)
        // A snapshot may hand over a resolution at the same revision; nothing else does.
        val handed = SessionFailures.merge(one, listOf(rec("a", 1).copy(resolved = true)))
        assertTrue(handed.single().resolved)
        assertSame(one, SessionFailures.merge(one, listOf(rec("", 1), rec("b", 0))))
    }

    @Test
    fun `progress settles incidents, a clean end the warnings, a new prompt everything`() {
        val table = listOf(rec("inc", 1), rec("note", 1, category = "unknown"), rec("err", 1, severity = "error"))
        val progressed = SessionFailures.settle(table, SessionFailures.SettleScope.RETRY_INCIDENTS)
        assertEquals(listOf(true, false, false), progressed.map { it.resolved })
        val ended = SessionFailures.settle(progressed, SessionFailures.SettleScope.WARNINGS)
        assertEquals(listOf(true, true, false), ended.map { it.resolved })
        assertEquals(listOf(true, true, true), SessionFailures.settle(ended, SessionFailures.SettleScope.ALL).map { it.resolved })
        assertSame(table.take(0), SessionFailures.settle(table.take(0), SessionFailures.SettleScope.ALL))
    }

    @Test
    fun `incidents collapse to one strip, and the recovered line is the last that settled on its own`() {
        val table = listOf(rec("a", 1), rec("b", 1), rec("c", 1, category = "unknown"))
        val view = SessionFailures.activeRetryIncidents(table)
        assertEquals("b", view.incident?.id)
        assertEquals(1, view.hiddenCount)
        assertEquals(listOf("a", "b"), view.ids)

        val recovered = SessionFailures.settle(table, SessionFailures.SettleScope.RETRY_INCIDENTS)
        assertEquals("b", SessionFailures.mostRecentRecovered(recovered)?.id)
        // Closing a strip silences it: it is not claimed as recovered.
        val dismissed = SessionFailures.dismiss(table, view.ids)
        assertNull(SessionFailures.mostRecentRecovered(dismissed))
        assertNull(SessionFailures.activeRetryIncidents(dismissed).incident)
        // The recovered line can be closed too.
        assertNull(SessionFailures.mostRecentRecovered(SessionFailures.dismiss(recovered, listOf("b", "a"))))
        // A new revision of a dismissed id is news again.
        assertFalse(SessionFailures.merge(dismissed, listOf(rec("a", 2))).first { it.id == "a" }.dismissed)
    }

    @Test
    fun `only what the user hasn't been told is news`() {
        val err = rec("e", 1, severity = "error", title = "API Error: 529")
        assertEquals(SessionFailures.Notice.TERMINAL, SessionFailures.notice(null, err))
        assertEquals(SessionFailures.Notice.ADVISORY, SessionFailures.notice(null, rec("n", 1, category = "unknown")))
        assertNull("the strip draws an incident", SessionFailures.notice(null, rec("i", 1)))
        assertNull("a replay", SessionFailures.notice(err, err))
        assertNull("a re-publish of what shows", SessionFailures.notice(err, err.copy(revision = 2)))
        assertEquals(SessionFailures.Notice.TERMINAL, SessionFailures.notice(err.copy(resolved = true), err.copy(revision = 2)))
        assertEquals(SessionFailures.Notice.TERMINAL, SessionFailures.notice(err, err.copy(revision = 2, title = "API Error: 500")))
    }
}
