package app.codeg.android.core.designsystem.component

import androidx.compose.ui.graphics.luminance
import app.codeg.android.core.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVisualsTest {

    /** Every built-in, plus the two shapes that have no brand mark compiled in. */
    private val allShapes: List<AgentType> =
        AgentType.BUILTINS + AgentType.Custom("goose") + AgentType.Unknown("future_agent")

    @Test
    fun `every built-in agent has its own brand icon`() {
        val icons = AgentType.BUILTINS.map(AgentVisuals::icon)

        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun `custom and unrecognised agents share the neutral mark`() {
        // No brand mark can be compiled in for an agent registered at runtime; both
        // fall back to the same neutral plug, tinted like the other mono marks.
        val custom = AgentVisuals.icon(AgentType.Custom("goose"))
        assertEquals(custom, AgentVisuals.icon(AgentType.Unknown("future_agent")))
        assertTrue(AgentType.BUILTINS.none { AgentVisuals.icon(it) == custom })
        assertTrue(AgentVisuals.iconIsTemplate(AgentType.Custom("goose")))
    }

    @Test
    fun `accents are fully opaque in both themes`() {
        allShapes.forEach { agent ->
            assertEquals(1f, AgentVisuals.accent(agent, isDark = false).alpha, 0f)
            assertEquals(1f, AgentVisuals.accent(agent, isDark = true).alpha, 0f)
        }
    }

    @Test
    fun `a custom agent's accent is stable across lookups and themes`() {
        val goose = AgentVisuals.accent(AgentType.Custom("goose"), isDark = false)
        assertEquals(goose, AgentVisuals.accent(AgentType.Custom("goose"), isDark = false))
        // The palette reads on either surface, so the same agent keeps one identity
        // colour rather than flipping with the theme.
        assertEquals(goose, AgentVisuals.accent(AgentType.Custom("goose"), isDark = true))
    }

    @Test
    fun `custom accents spread across the palette and avoid the brand colours`() {
        // Two ids CAN collide — the palette is short on purpose. What must hold is that
        // it spreads (one colour for everything would make the plug useless) and that no
        // custom agent wears a built-in's brand colour.
        val used = (1..60).map { AgentVisuals.accent(AgentType.Custom("agent-$it"), isDark = false) }.toSet()
        assertTrue("accents should spread across ids, got $used", used.size >= 4)

        val brand = AgentType.BUILTINS.flatMap {
            listOf(AgentVisuals.accent(it, isDark = false), AgentVisuals.accent(it, isDark = true))
        }.toSet()
        assertTrue("a custom agent must not wear a brand colour", used.none { it in brand })

        // Every accent stays in the mid band that reads on both surfaces.
        used.forEach { assertTrue("$it is too extreme", it.luminance() in 0.12f..0.75f) }
    }

    @Test
    fun `an unrecognised agent follows the theme like the mono brands`() {
        val unknown = AgentType.Unknown("future_agent")
        assertTrue(AgentVisuals.accent(unknown, isDark = false).luminance() < 0.5f)
        assertTrue(AgentVisuals.accent(unknown, isDark = true).luminance() > 0.5f)
    }

    @Test
    fun `theme-following marks swap tint with the theme`() {
        // Grok's and Cursor's monochrome brand marks must not vanish on either
        // surface: dark tint in light theme, light tint in dark theme.
        listOf(AgentType.GROK, AgentType.CURSOR).forEach { agent ->
            val light = AgentVisuals.accent(agent, isDark = false).luminance()
            val dark = AgentVisuals.accent(agent, isDark = true).luminance()

            assertTrue("${agent.displayName} light tint should be dark", light < 0.5f)
            assertTrue("${agent.displayName} dark tint should be light", dark > 0.5f)
        }
    }
}
