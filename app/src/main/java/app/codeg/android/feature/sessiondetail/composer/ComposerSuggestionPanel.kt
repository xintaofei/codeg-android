package app.codeg.android.feature.sessiondetail.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentIcon
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The suggestion card the compose bar draws directly above its field while a `/`·`$` or `@`
 * trigger is open — so it rides the keyboard with the bar (iOS `ComposerSuggestionPanel`).
 *
 * Rows take taps without taking focus from the field, so the keyboard stays up and typing goes
 * on filtering. The highlighted row is what a hardware keyboard's Enter picks (web parity: the
 * first row by default).
 */
@Composable
internal fun ComposerSuggestionPanel(
    state: ComposerPanelState,
    onPick: (ComposerSuggestionRow) -> Unit,
    onSelectTab: (MentionTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(colors.bgElevated)
            .border(0.5.dp, colors.surfaceStroke, shape),
    ) {
        if (state is ComposerPanelState.Mention) {
            TabStrip(active = state.tab, counts = state.counts, onSelect = onSelectTab)
            HorizontalDivider(thickness = Dp.Hairline, color = colors.surfaceStroke)
        }
        val listState = rememberLazyListState()
        LaunchedEffect(state.highlighted, state.rows.size) {
            if (state.rows.isNotEmpty()) listState.animateScrollToItem(state.highlighted.coerceIn(0, state.rows.size - 1))
        }
        LazyColumn(
            state = listState,
            // Tall enough for five or six rows; longer lists scroll inside it.
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (state) {
                is ComposerPanelState.Commands -> {
                    if (state.loading) item(key = "status") { StatusRow(stringResource(R.string.composer_suggest_loading_commands), spinner = true) }
                    itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                        SuggestionRow(row, highlighted = index == state.highlighted, skillColumn = state.skillColumn, onClick = { onPick(row) })
                    }
                }
                is ComposerPanelState.Mention -> {
                    if (state.rows.isEmpty()) {
                        // An earlier query's rows (narrowed to this one) stay up while this one's
                        // search runs, rather than blinking to "Searching…" on every keystroke.
                        item(key = "status") {
                            when {
                                state.tab == MentionTab.FILE && !state.hasWorkspace ->
                                    StatusRow(stringResource(R.string.composer_suggest_no_workspace), spinner = false)
                                state.loading -> StatusRow(stringResource(R.string.composer_suggest_searching), spinner = true)
                                else -> StatusRow(stringResource(R.string.composer_suggest_no_matches), spinner = false)
                            }
                        }
                    } else {
                        itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                            SuggestionRow(row, highlighted = index == state.highlighted, skillColumn = false, onClick = { onPick(row) })
                        }
                        if (state.truncated) item(key = "more") { StatusRow(stringResource(R.string.composer_suggest_more), spinner = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabStrip(active: MentionTab, counts: Map<MentionTab, Int>, onSelect: (MentionTab) -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MentionTab.entries.forEach { tab ->
            val isActive = tab == active
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (isActive) colors.accentDim else colors.bgElevated)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { selected = isActive }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (tab == MentionTab.AGENT) R.string.composer_suggest_tab_agents else R.string.composer_suggest_tab_files),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) colors.textPrimary else colors.textSecondary,
                )
                val count = counts[tab] ?: 0
                if (count > 0) {
                    Text("$count", fontSize = 11.sp, lineHeight = 14.sp, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(row: ComposerSuggestionRow, highlighted: Boolean, skillColumn: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) colors.accentDim else colors.bgElevated)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (row) {
            is ComposerSuggestionRow.Command -> {
                if (skillColumn) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (row.skill) SkillIcon()
                    }
                }
                Text(
                    ComposerInvocations.commandInvocationToken(row.command.name),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ComposerInvocations.commandInputHint(row.command)?.let { hint ->
                    Text(hint, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Detail(row.command.description.orEmpty(), colors.textSecondary)
            }
            is ComposerSuggestionRow.Skill -> {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { SkillIcon() }
                Title(row.skill.name.ifEmpty { row.skill.id })
                Detail(row.skill.description ?: (row.prefix.text + row.skill.id), colors.textSecondary)
            }
            is ComposerSuggestionRow.File -> {
                Icon(
                    if (row.entry.isDir) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Title(row.entry.displayName)
                Detail(row.entry.path, colors.textTertiary)
            }
            is ComposerSuggestionRow.Agent -> {
                AgentIcon(row.agent.agentType, size = 18.dp)
                Title(row.agent.label)
                row.agent.detail?.let { Detail(it, colors.textTertiary) }
            }
        }
    }
}

@Composable
private fun SkillIcon() {
    Icon(
        Icons.AutoMirrored.Rounded.MenuBook,
        contentDescription = stringResource(R.string.composer_suggest_skill),
        tint = CodegTheme.colors.accent,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        color = CodegTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.Detail(text: String, color: Color) {
    if (text.isEmpty()) {
        Spacer(Modifier.weight(1f))
        return
    }
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = true),
    )
}

@Composable
private fun StatusRow(text: String, spinner: Boolean) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(color = colors.textTertiary, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        }
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = colors.textTertiary)
    }
}
