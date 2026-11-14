package app.codeg.android.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.BuiltinAgent

/**
 * Per-agent brand visuals, mirroring the web client's `agent-icon.tsx` and iOS
 * `AgentType.iconAsset`/`accent`. The brand marks live in `res/drawable` as
 * vector drawables ported from the same SVGs the web and iOS clients ship.
 */
object AgentVisuals {

    /**
     * The agent's brand mark as a vector drawable. A custom (user-registered) or unrecognised
     * agent has no mark compiled in — it gets the neutral ACP plug.
     *
     * A custom agent does publish its own icon (`AcpAgentInfo.iconUrl`, a data: URL), but
     * nothing here renders it: the marks are SVGs, and this app carries no image library to
     * rasterize them. [accent] tints the shared plug per-agent instead.
     */
    @DrawableRes
    fun icon(agent: AgentType): Int = when (agent.builtin) {
        BuiltinAgent.CLAUDE_CODE -> R.drawable.ic_agent_claude_code
        BuiltinAgent.CODEX -> R.drawable.ic_agent_codex
        BuiltinAgent.OPEN_CODE -> R.drawable.ic_agent_open_code
        BuiltinAgent.GEMINI -> R.drawable.ic_agent_gemini
        BuiltinAgent.OPEN_CLAW -> R.drawable.ic_agent_open_claw
        BuiltinAgent.CLINE -> R.drawable.ic_agent_cline
        BuiltinAgent.HERMES -> R.drawable.ic_agent_hermes
        BuiltinAgent.CODE_BUDDY -> R.drawable.ic_agent_code_buddy
        BuiltinAgent.KIMI_CODE -> R.drawable.ic_agent_kimi_code
        BuiltinAgent.PI -> R.drawable.ic_agent_pi
        BuiltinAgent.GROK -> R.drawable.ic_agent_grok
        BuiltinAgent.CURSOR -> R.drawable.ic_agent_cursor
        null -> R.drawable.ic_agent_custom
    }

    /**
     * Whether the brand mark is a monochrome glyph the caller should tint (the
     * web's `MONO_ICONS` / iOS `iconIsTemplate`); the others carry their own
     * brand colors/gradients and render as-is. The custom-agent plug is
     * monochrome like the mono brands.
     */
    fun iconIsTemplate(agent: AgentType): Boolean = when (agent.builtin) {
        BuiltinAgent.OPEN_CODE, BuiltinAgent.CLINE, BuiltinAgent.HERMES,
        BuiltinAgent.CODE_BUDDY, BuiltinAgent.GROK, BuiltinAgent.CURSOR,
        null,
        -> true
        BuiltinAgent.CLAUDE_CODE, BuiltinAgent.CODEX, BuiltinAgent.GEMINI,
        BuiltinAgent.OPEN_CLAW, BuiltinAgent.KIMI_CODE, BuiltinAgent.PI,
        -> false
    }

    /**
     * Accent colour for badges/avatars and the default tint of monochrome brand marks (iOS
     * `AgentType.accent`). Grok's and Cursor's marks are monochrome brands, so their accent
     * follows the theme — near-black in light, near-white in dark — to stay legible on either
     * surface. A CUSTOM agent has no brand colour to claim but does need to be distinguishable
     * from the next one, so it gets a stable colour hashed from its id; only an unrecognised
     * type, which has no id worth hashing, follows the theme like the mono brands.
     */
    fun accent(agent: AgentType, isDark: Boolean): Color = when (agent.builtin) {
        BuiltinAgent.CLAUDE_CODE -> Color(0xFFD98557) // claude clay
        BuiltinAgent.CODEX -> Color(0xFF73C7A8) // teal
        BuiltinAgent.OPEN_CODE -> Color(0xFF8C9EF2) // indigo
        BuiltinAgent.GEMINI -> Color(0xFF80B3FA) // blue
        BuiltinAgent.OPEN_CLAW -> Color(0xFFEB9E6B) // amber
        BuiltinAgent.CLINE -> Color(0xFF9EC780) // green
        BuiltinAgent.HERMES -> Color(0xFF9980D9) // violet
        BuiltinAgent.CODE_BUDDY -> Color(0xFF3378F5) // tencent blue
        BuiltinAgent.KIMI_CODE -> Color(0xFF1782FF) // moonshot blue
        BuiltinAgent.PI -> Color(0xFF383842) // pi slate
        BuiltinAgent.GROK, BuiltinAgent.CURSOR ->
            if (isDark) Color(0xFFEBEBEB) else Color(0xFF1F1F1F)
        // A registered agent has no brand colour codeg can know, and they all wear the
        // same plug — so derive a stable tint from the id, which is the only thing
        // telling two of them apart in a list. An unrecognised type has no id worth
        // hashing, so it follows the theme like the mono brands.
        null -> when (agent) {
            is AgentType.Custom -> CUSTOM_ACCENTS[Math.floorMod(agent.id.hashCode(), CUSTOM_ACCENTS.size)]
            else -> if (isDark) Color(0xFFEBEBEB) else Color(0xFF1F1F1F)
        }
    }

    /**
     * Accents a custom agent can be assigned, picked by a hash of its registry id so the
     * same agent keeps one colour across screens and restarts. Mid-tones from the same
     * family as the built-in accents — that band is what keeps them legible on both the
     * light and dark surface without a per-theme variant — but deliberately none of the
     * built-in values, so a custom agent never wears Claude's or Codex's colour.
     *
     * A short palette means two registered agents CAN collide; the colour is a hint on
     * top of the name, not an identity.
     */
    private val CUSTOM_ACCENTS = listOf(
        Color(0xFF5FB0A8), // teal
        Color(0xFF7E8CE0), // periwinkle
        Color(0xFFCF7BA5), // rose
        Color(0xFF8FB35C), // olive
        Color(0xFFD98C3C), // ochre
        Color(0xFF6FA8D4), // sky
        Color(0xFFB07CC9), // orchid
        Color(0xFFC96F6F), // brick
    )

    /** [accent] resolved against the current theme. */
    @Composable
    fun accent(agent: AgentType): Color = accent(agent, CodegTheme.colors.isDark)
}

/**
 * The per-agent brand icon (web `AgentIcon` / iOS `AgentIcon`): color agents
 * render their own colors/gradients from the vector asset; monochrome agents
 * are tinted with [tint] (the agent accent by default). The surrounding UI
 * usually supplies the visible agent name; the icon keeps its own description
 * for standalone uses.
 */
@Composable
fun AgentIcon(
    agent: AgentType,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val decorated = modifier
        .size(size)
        .clearAndSetSemantics { contentDescription = agent.displayName }
    val painter = painterResource(AgentVisuals.icon(agent))
    if (AgentVisuals.iconIsTemplate(agent)) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint ?: AgentVisuals.accent(agent),
            modifier = decorated,
        )
    } else {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = decorated,
        )
    }
}
