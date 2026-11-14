package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.QuickMessage

/** Draft transforms for the compose "+" inserts. Port of iOS `ComposeInsertModel`. */
object ComposeInsert {
    fun appendMessage(content: String, draft: String): String =
        if (draft.isBlank()) content else "${draft.trimEnd()} $content"

    /**
     * What an advertised command runs as. Codex advertises its skills as commands NAMED
     * `$<skill>`, where the `$` is the invocation prefix itself; every other command runs as
     * `/<name>`. Port of the web's `commandInvocationToken`.
     */
    fun invocationToken(name: String): String = if (name.startsWith("$")) name else "/$name"

    fun appendCommand(name: String, draft: String): String {
        val token = invocationToken(name)
        return if (draft.isBlank()) "$token " else "${draft.trimEnd()} $token "
    }

    /** Prepend `<prefix><id> `, replacing a leading expert mention if its id is known. */
    fun applyExpert(id: String, draft: String, agent: AgentType, knownIds: Set<String>): String {
        val prefix = if (agent == AgentType.CODEX) "$" else "/"
        val regex = Regex("^" + Regex.escape(prefix) + "([A-Za-z0-9_-]+)\\s+([\\s\\S]*)$")
        val rest = regex.find(draft)?.let { m -> if (knownIds.contains(m.groupValues[1])) m.groupValues[2] else null } ?: draft
        return if (rest.isBlank()) "$prefix$id " else "$prefix$id ${rest.trimStart()}"
    }
}

private enum class InsertSource { QUICK, EXPERTS, COMMANDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeInsertSheet(
    viewModel: SessionDetailViewModel,
    onInsert: ((String) -> String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var source by remember { mutableStateOf(InsertSource.QUICK) }
    var loading by remember { mutableStateOf(true) }
    var quick by remember { mutableStateOf<List<QuickMessage>>(emptyList()) }
    var experts by remember { mutableStateOf<List<ExpertListItem>>(emptyList()) }
    var commands by remember { mutableStateOf<List<AvailableCommandInfo>>(emptyList()) }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(source) {
        loading = true
        when (source) {
            InsertSource.QUICK -> quick = viewModel.loadQuickMessages()
            InsertSource.EXPERTS -> { experts = viewModel.loadExpertsForInsert(); knownIds = viewModel.knownExpertIds() }
            InsertSource.COMMANDS -> commands = viewModel.loadSlashCommands()
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Segmented selector.
            val sources = InsertSource.entries
            val sourceLabels = listOf(
                stringResource(R.string.insert_quick_messages),
                stringResource(R.string.insert_experts),
                stringResource(R.string.insert_commands),
            )
            CodegSegmented(
                options = sourceLabels,
                selectedIndex = sources.indexOf(source),
                onSelect = { source = sources[it] },
            )

            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                    source == InsertSource.QUICK -> InsertList(quick.isEmpty(), stringResource(R.string.insert_empty_quick)) {
                        items(quick, key = { it.id }) { qm ->
                            InsertRow(qm.title.ifEmpty { qm.content }, qm.content) {
                                onInsert { draft -> ComposeInsert.appendMessage(qm.content, draft) }; onDismiss()
                            }
                        }
                    }
                    source == InsertSource.EXPERTS -> InsertList(experts.isEmpty(), stringResource(R.string.insert_empty_experts)) {
                        items(experts, key = { it.id }) { ex ->
                            InsertRow(ex.metadata.title(), ex.metadata.desc()) {
                                onInsert { draft -> ComposeInsert.applyExpert(ex.id, draft, viewModel.agentForInsert, knownIds) }; onDismiss()
                            }
                        }
                    }
                    else -> InsertList(commands.isEmpty(), stringResource(R.string.insert_empty_commands)) {
                        items(commands, key = { it.name }) { cmd ->
                            InsertRow(ComposeInsert.invocationToken(cmd.name), cmd.description ?: "", mono = true) {
                                onInsert { draft -> ComposeInsert.appendCommand(cmd.name, draft) }; onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsertList(empty: Boolean, emptyText: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val colors = CodegTheme.colors
    if (empty) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(emptyText, fontSize = 13.sp, color = colors.textTertiary) }
    } else {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun InsertRow(title: String, subtitle: String, mono: Boolean = false, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
