package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import kotlinx.coroutines.delay

/** How long past a turn's end (or the screen opening) the summary re-reads the live snapshot:
 *  a reattaching session publishes its selectors a moment after, and a turn's end can still be
 *  followed by a config update the connection drains (see the sheet's own settle read). */
private const val SUMMARY_SETTLE_MS = 2_500L

/**
 * The options button in the session's top bar: the agent's avatar followed by its current
 * settings at a glance — "Opus 5.5 / High / Plan", codeg 0.44's merged settings chip
 * (`session-settings-picker.tsx`). Tapping it opens [AgentOptionsSheet].
 *
 * The summary is read from [SessionDetailViewModel.optionsSummary], which never spawns a probe
 * agent: it holds what a live session published or the sheet already loaded, and re-reads only
 * the live snapshot here ([SessionDetailViewModel.refreshLiveSelectors]). Until a catalog exists
 * the button is just the avatar, as before.
 */
@Composable
internal fun AgentOptionsButton(
    viewModel: SessionDetailViewModel,
    agent: AgentType,
    isInFlight: Boolean,
    /** The sheet runs its own (fuller) reads while it is open. */
    sheetOpen: Boolean,
    conversationId: Int?,
    onClick: () -> Unit,
) {
    val summary by viewModel.optionsSummary.collectAsStateWithLifecycle()
    val modeTitle = stringResource(R.string.agentopts_mode_name)
    // Only while the summary is THIS agent's: a draft that just switched agents shows the bare
    // avatar rather than the previous agent's values.
    val settings = remember(summary, agent, modeTitle) {
        summary?.takeIf { it.agent == agent }?.settings(modeTitle).orEmpty()
    }
    val parts = remember(settings) { SessionSettings.summaryParts(settings) }
    val on = stringResource(R.string.agentopts_value_on)
    val off = stringResource(R.string.agentopts_value_off)
    val spoken = remember(settings, on, off) { SessionSettings.accessibilityValue(settings, on, off) }

    // Keep the values current between sheet opens: when the screen opens, and when a turn
    // settles (the agent may have moved its own mode, as a plan exit does). Mid-turn the values
    // arrive as pushes anyway.
    LaunchedEffect(agent, conversationId, isInFlight, sheetOpen) {
        if (isInFlight || sheetOpen) return@LaunchedEffect
        viewModel.refreshLiveSelectors()
        delay(SUMMARY_SETTLE_MS)
        viewModel.refreshLiveSelectors()
    }

    // A phone's top bar shares its row with the back button, the title and the actions, so the
    // values stay short there and long ones truncate.
    val maxSummaryWidth = if (LocalConfiguration.current.screenWidthDp >= 600) 240.dp else 116.dp
    val label = stringResource(R.string.session_agent_options)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                // Every setting, including the ones the visible label leaves out (a `default`
                // value, a switch that is off).
                if (spoken.isNotEmpty()) stateDescription = spoken
            }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentAvatar(agent, size = 28.dp)
        if (parts.isNotEmpty()) {
            SettingsSummary(parts, Modifier.widthIn(max = maxSummaryWidth).padding(end = 4.dp))
        }
    }
}

/**
 * The values joined by "/", on one line. A short value ([SessionSettings.isShortSummary]) never
 * truncates; longer ones share the squeeze, and whatever still doesn't fit is clipped at the end
 * rather than drawn past it (the web's `overflow-hidden`).
 */
@Composable
private fun SettingsSummary(parts: List<String>, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(
        // The button's own semantics speak for it.
        modifier.clipToBounds().clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(
                    "/",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
            }
            val short = SessionSettings.isShortSummary(part)
            Text(
                part,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = if (short) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (short) Modifier else Modifier.weight(1f, fill = false),
            )
        }
    }
}
