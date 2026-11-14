package app.codeg.android.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegFilterChip
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.LocalMcpServer

private const val SPEC_TEMPLATE = "{\n  \"type\": \"stdio\",\n  \"command\": \"\",\n  \"args\": []\n}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpContent(viewModel: McpViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var editor by remember { mutableStateOf<McpEditorTarget?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.loading && ui.servers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.servers.isEmpty() && !ui.loading) {
                    item("empty") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { EmptyState(Icons.Rounded.Memory, stringResource(R.string.mcp_empty_title), stringResource(R.string.mcp_empty_subtitle)) } }
                }
                items(ui.servers, key = { it.id }) { s -> McpRow(s, onEdit = { editor = McpEditorTarget(s) }, onDelete = { viewModel.delete(s) }) }
            }
        }
        if (!(ui.loading && ui.servers.isEmpty())) {
            FloatingActionButton(
                onClick = { editor = McpEditorTarget(null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.mcp_new_cd)) }
        }
    }

    editor?.let { target ->
        McpEditor(
            existing = target.server,
            onDismiss = { editor = null },
            onSave = { id, spec, apps, onErr -> viewModel.save(target.server?.id, id, spec, apps) { err -> if (err == null) editor = null else onErr(err) } },
        )
    }
}

private data class McpEditorTarget(val server: LocalMcpServer?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun McpRow(s: LocalMcpServer, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = CodegTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.bgElevated.copy(alpha = 0.5f))
                .combinedClickable(onClick = onEdit, onLongClick = { menu = true }).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(s.id, fontSize = 15.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.spec.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.apps.isNotEmpty()) Text(s.apps.joinToString(", "), fontSize = 11.sp, color = colors.accent)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun McpEditor(
    existing: LocalMcpServer?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, (String) -> Unit) -> Unit,
) {
    val colors = CodegTheme.colors
    var id by remember { mutableStateOf(existing?.id ?: "") }
    var spec by remember { mutableStateOf(existing?.spec?.toString() ?: SPEC_TEMPLATE) }
    val apps: SnapshotStateList<String> = remember { existing?.apps.orEmpty().toMutableStateList() }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopAppBar(
                title = { Text(if (existing == null) stringResource(R.string.mcp_new_title) else stringResource(R.string.mcp_edit_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.mcp_close_cd), tint = colors.textPrimary) }
                },
                actions = {
                    TextButton(onClick = { onSave(id, spec, apps.toList()) { error = it } }, enabled = id.isNotBlank()) {
                        Text(stringResource(R.string.common_save), fontWeight = FontWeight.SemiBold, color = if (id.isNotBlank()) colors.accent else colors.textTertiary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CodegTextField(id, { id = it }, label = stringResource(R.string.mcp_server_id), mono = true)
                CodegTextField(spec, { spec = it }, label = stringResource(R.string.mcp_spec_json), mono = true, singleLine = false)
                Text(stringResource(R.string.mcp_enabled_for), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Built-ins only: the server dropped custom-agent server management
                    // from the MCP settings surface (0.22.0), so a `custom:<id>` app id
                    // here would be written and then ignored.
                    for (agent in AgentType.BUILTINS) {
                        val on = apps.contains(agent.wire)
                        CodegFilterChip(
                            label = agent.shortName,
                            selected = on,
                            onClick = { if (on) apps.remove(agent.wire) else apps.add(agent.wire) },
                            icon = if (on) Icons.Rounded.Check else null,
                        )
                    }
                }
                error?.let { Text(it, fontSize = 13.sp, color = colors.danger) }
            }
        }
    }
}
