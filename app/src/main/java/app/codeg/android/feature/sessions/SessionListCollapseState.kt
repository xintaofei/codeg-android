package app.codeg.android.feature.sessions

/** Compact mode temporarily hides rows without overwriting the user's normal layout. */
internal data class SessionListCollapseState(
    val collapsed: Set<String> = emptySet(),
    val compact: Boolean = false,
    val expandedInCompact: Set<String> = emptySet(),
) {
    fun isCollapsed(id: String): Boolean =
        if (compact) id !in expandedInCompact else id in collapsed

    fun toggleCompact(): SessionListCollapseState =
        copy(compact = !compact, expandedInCompact = emptySet())

    fun toggleSection(id: String): SessionListCollapseState =
        if (compact) {
            copy(expandedInCompact = if (id in expandedInCompact) expandedInCompact - id else expandedInCompact + id)
        } else {
            copy(collapsed = if (id in collapsed) collapsed - id else collapsed + id)
        }
}
