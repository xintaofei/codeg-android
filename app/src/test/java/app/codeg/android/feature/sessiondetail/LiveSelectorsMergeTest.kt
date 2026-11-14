package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Folding a live config-option push into the options sheet.
 *
 * The push is the agent's whole catalog as it stands right now — but the sheet may be showing
 * an optimistic value for an option whose apply hasn't landed yet.
 */
class LiveSelectorsMergeTest {

    private fun option(id: String, value: String?, type: String = "select") =
        SessionConfigOption(id = id, kind = SessionConfigKind(type = type, currentValue = value))

    @Test
    fun `a push replaces the values on screen`() {
        val merged = mergeLiveConfigValues(
            current = mapOf("effort" to "low", "auto_approve" to "false"),
            pushed = listOf(option("effort", "high"), option("auto_approve", "true", "boolean")),
            applying = emptySet(),
        )
        assertEquals(mapOf("effort" to "high", "auto_approve" to "true"), merged)
    }

    @Test
    fun `an option with an apply in flight keeps the optimistic value`() {
        // The sheet moved the switch to ON and the apply is still running; this push was
        // emitted before the agent processed it. Adopting it would snap the switch back to OFF
        // until the apply lands, then flip it forward again.
        val merged = mergeLiveConfigValues(
            current = mapOf("auto_approve" to "true"),
            pushed = listOf(option("auto_approve", "false", "boolean")),
            applying = setOf("auto_approve"),
        )
        assertEquals(mapOf("auto_approve" to "true"), merged)
    }

    @Test
    fun `only the applying option is held back`() {
        val merged = mergeLiveConfigValues(
            current = mapOf("effort" to "low", "auto_approve" to "true"),
            pushed = listOf(option("effort", "high"), option("auto_approve", "false", "boolean")),
            applying = setOf("auto_approve"),
        )
        assertEquals(mapOf("effort" to "high", "auto_approve" to "true"), merged)
    }

    @Test
    fun `an option the push introduces picks up its value`() {
        val merged = mergeLiveConfigValues(
            current = emptyMap(),
            pushed = listOf(option("effort", "medium")),
            applying = emptySet(),
        )
        assertEquals(mapOf("effort" to "medium"), merged)
    }

    @Test
    fun `an option the push drops loses its value`() {
        // The catalog was replaced wholesale, so a leftover value would key a row that is no
        // longer rendered.
        val merged = mergeLiveConfigValues(
            current = mapOf("effort" to "low", "gone" to "yes"),
            pushed = listOf(option("effort", "high")),
            applying = emptySet(),
        )
        assertEquals(mapOf("effort" to "high"), merged)
    }

    @Test
    fun `an option with no current value is not carried as an empty selection`() {
        // Matches the load path, which drops empties rather than pre-selecting a blank row.
        val merged = mergeLiveConfigValues(
            current = mapOf("effort" to "low"),
            pushed = listOf(option("effort", null), option("other", "")),
            applying = emptySet(),
        )
        assertEquals(emptyMap<String, String>(), merged)
    }

    @Test
    fun `an applying option with nothing on screen yet stays absent`() {
        val merged = mergeLiveConfigValues(
            current = emptyMap(),
            pushed = listOf(option("effort", "high")),
            applying = setOf("effort"),
        )
        assertEquals(emptyMap<String, String>(), merged)
    }

}
