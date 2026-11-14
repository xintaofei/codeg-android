package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Undoing the preference an apply saved before it failed.
 *
 * The save has to precede the apply (the apply may open the connection, and `acp_connect`
 * reads this store), so this undo is the only thing keeping a failed change from being
 * applied for real on the next connect. The catch is that applies are not serialized — the
 * undo has to tell "still mine" from "a later pick has moved on".
 */
class SelectorPrefsRestoreTest {

    private fun prefs(vararg values: Pair<String, String>) =
        SelectorPrefs(configValues = values.toMap())

    @Test
    fun `a failed toggle is put back`() {
        val after = prefs("auto_approve" to "true")
            .restoringConfig("auto_approve", expected = "true", previous = "false")

        assertEquals("false", after.configValues?.get("auto_approve"))
    }

    @Test
    fun `a value that was never remembered is removed, not blanked`() {
        // Leaving an entry behind would ship a connect preference for an option the user has
        // never actually chosen.
        val after = prefs("auto_approve" to "true")
            .restoringConfig("auto_approve", expected = "true", previous = null)

        assertFalse(after.configValues.orEmpty().containsKey("auto_approve"))
    }

    @Test
    fun `a newer pick for the same option is left alone`() {
        // Apply #1 (medium -> high) is still in flight when a second pick saves "low". #1 then
        // fails. An unconditional restore would write "medium" over the newer, possibly
        // succeeding pick — and the sheet, which is showing #2's result, would never say so.
        val after = prefs("effort" to "low")
            .restoringConfig("effort", expected = "high", previous = "medium")

        assertEquals("low", after.configValues?.get("effort"))
    }

    @Test
    fun `other options are untouched`() {
        val after = prefs("auto_approve" to "true", "effort" to "high")
            .restoringConfig("auto_approve", expected = "true", previous = "false")

        assertEquals("high", after.configValues?.get("effort"))
    }

    @Test
    fun `restoring an option that was never stored is a no-op`() {
        val before = SelectorPrefs()

        assertEquals(before, before.restoringConfig("auto_approve", expected = "true", previous = "false"))
    }

    @Test
    fun `a failed mode is put back`() {
        assertEquals("default", SelectorPrefs(modeId = "plan").restoringMode("plan", "default").modeId)
        assertNull(SelectorPrefs(modeId = "plan").restoringMode("plan", null).modeId)
    }

    @Test
    fun `a superseded mode is left alone`() {
        // Same ownership rule as the config path: a later pick owns the value now.
        assertEquals("yolo", SelectorPrefs(modeId = "yolo").restoringMode("plan", "default").modeId)
    }
}
