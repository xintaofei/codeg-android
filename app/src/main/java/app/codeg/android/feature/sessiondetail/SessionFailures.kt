package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionFailureRecord

/**
 * The client half of the AIR session-failure contract (codeg web 0.44 `lib/session-failures.ts`),
 * kept pure so it can be tested on its own.
 *
 * - A record is accepted only when its revision is STRICTLY greater than the stored one (equal
 *   is a verbatim replay — claude re-publishes active failures on `session/load`). An accepted
 *   upsert re-arms `resolved`: a bumped revision on the same id is how codex escalates a retry
 *   warning into the turn's terminal error. The one looser case is an equal revision carrying
 *   `resolved`, which only a snapshot hands over.
 * - Adapters never resolve anything, so the client settles: retry incidents as soon as the turn
 *   makes progress, the remaining warnings at a clean turn end, everything when the user starts
 *   a new prompt.
 *
 * Entries outlive their resolution as their id's revision watermark, so a delayed stale upsert
 * can never resurrect a settled record.
 */
object SessionFailures {

    enum class SettleScope {
        /** The turn produced content again: every in-flight retry incident recovered. */
        RETRY_INCIDENTS,

        /** A clean turn end: whatever warnings are left. */
        WARNINGS,

        /** The user started a new prompt, acknowledging even terminal errors. */
        ALL,
    }

    /** What an upsert is to the user: news to tell ([TERMINAL], [ADVISORY]), or nothing. */
    enum class Notice { TERMINAL, ADVISORY }

    /** Fold [incoming] into [current]; [current] itself when nothing changed. */
    fun merge(current: List<SessionFailureRecord>, incoming: List<SessionFailureRecord>): List<SessionFailureRecord> {
        var next: MutableList<SessionFailureRecord>? = null
        for (record in incoming) {
            if (record.id.isEmpty() || record.revision < 1) continue
            val target = next ?: current
            val index = target.indexOfFirst { it.id == record.id }
            val stored = target.getOrNull(index)
            if (stored != null && record.revision < stored.revision) continue
            if (stored != null && record.revision == stored.revision) {
                if (!record.resolved || stored.resolved) continue
                val into = next ?: current.toMutableList().also { next = it }
                into[index] = stored.copy(resolved = true)
                continue
            }
            val into = next ?: current.toMutableList().also { next = it }
            val accepted = record.copy(dismissed = false)
            if (index >= 0) into[index] = accepted else into.add(accepted)
        }
        return next ?: current
    }

    /**
     * A warning that is an in-flight RETRY INCIDENT: the adapter lost the upstream and is
     * reconnecting, so the next byte of output proves it recovered. Category `unknown` is where
     * both adapters put their informational records (config notices, model-fallback notes):
     * nothing recovers those, and settling them on the next token would flash them away.
     */
    fun isRetryIncident(f: SessionFailureRecord): Boolean = f.severity == "warning" && f.category != "unknown"

    fun hasActiveRetryIncident(failures: List<SessionFailureRecord>): Boolean =
        failures.any { !it.resolved && isRetryIncident(it) }

    /** The most recently arrived active terminal record (table order). */
    fun latestActiveTerminal(failures: List<SessionFailureRecord>): SessionFailureRecord? =
        failures.lastOrNull { !it.resolved && it.severity != "warning" }

    /** Settle at a lifecycle boundary; errors survive everything but [SettleScope.ALL]. */
    fun settle(failures: List<SessionFailureRecord>, scope: SettleScope): List<SessionFailureRecord> {
        fun settles(f: SessionFailureRecord) = !f.resolved && when (scope) {
            SettleScope.ALL -> true
            SettleScope.WARNINGS -> f.severity == "warning"
            SettleScope.RETRY_INCIDENTS -> isRetryIncident(f)
        }
        if (failures.none(::settles)) return failures
        return failures.map { if (settles(it)) it.copy(resolved = true) else it }
    }

    /**
     * Resolve [ids] because the user closed their strip — all of them: one collapsed strip
     * stands for every incident behind it. Marked dismissed, so the "recovered" line doesn't
     * claim a silenced incident got fixed. Applies to resolved records too (closing the
     * recovered line itself).
     */
    fun dismiss(failures: List<SessionFailureRecord>, ids: Collection<String>): List<SessionFailureRecord> {
        val targets = ids.toSet()
        fun changes(f: SessionFailureRecord) = f.id in targets && !(f.resolved && f.dismissed)
        if (failures.none(::changes)) return failures
        return failures.map { if (changes(it)) it.copy(resolved = true, dismissed = true) else it }
    }

    /** The one strip the active retry incidents collapse to: the last, plus how many hide behind it. */
    data class IncidentView(val incident: SessionFailureRecord?, val hiddenCount: Int, val ids: List<String>)

    fun activeRetryIncidents(failures: List<SessionFailureRecord>): IncidentView {
        val incidents = failures.filter { !it.resolved && isRetryIncident(it) }
        return IncidentView(incidents.lastOrNull(), maxOf(0, incidents.size - 1), incidents.map { it.id })
    }

    /**
     * The record behind the muted "recovered" line: the most recent retry incident that settled
     * on its own. Dismissed ones are excluded; only incidents can recover.
     */
    fun mostRecentRecovered(failures: List<SessionFailureRecord>): SessionFailureRecord? =
        failures.lastOrNull { it.resolved && !it.dismissed && isRetryIncident(it) }

    /**
     * Whether upsert [incoming] is news, given what was [stored] under its id: a turn or session
     * that failed, an advisory, or nothing — a stale or replayed revision, a retry incident (the
     * strip draws those live), or a revision that only re-sends what is already showing.
     */
    fun notice(stored: SessionFailureRecord?, incoming: SessionFailureRecord): Notice? {
        if (incoming.id.isEmpty() || incoming.revision < 1) return null
        if (stored != null && incoming.revision <= stored.revision) return null
        if (isRetryIncident(incoming)) return null
        val repeat = stored != null && !stored.resolved &&
            stored.severity == incoming.severity &&
            stored.title == incoming.title &&
            (stored.details ?: "") == (incoming.details ?: "") &&
            stored.actions == incoming.actions
        if (repeat) return null
        return if (incoming.severity == "warning") Notice.ADVISORY else Notice.TERMINAL
    }
}
