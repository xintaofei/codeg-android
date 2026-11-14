package app.codeg.android.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.BranchTagSetting
import app.codeg.android.core.model.ConversationTag

/** The tag being created (in [scope]) or edited ([tag]). */
private data class TagForm(val tag: ConversationTag?, val scope: Int?)

/**
 * Manage tags (web `ConversationTagsManager`): the branch tag, the global tags, and the tags of
 * one folder at a time. Reorder by moving a tag up or down (the web drags), edit, delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsManagerScreen(onBack: () -> Unit, viewModel: TagsManagerViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var form by remember { mutableStateOf<TagForm?>(null) }
    var deleting by remember { mutableStateOf<ConversationTag?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tags_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && ui.tags.isEmpty() && ui.loadError == null -> LoadingView(message = stringResource(R.string.common_loading))
                ui.loadError != null && ui.tags.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InlineError(
                        icon = Icons.Rounded.Edit,
                        title = stringResource(R.string.tags_load_failed),
                        message = ui.loadError!!,
                        onRetry = viewModel::load,
                        retryLabel = stringResource(R.string.common_retry),
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(stringResource(R.string.tags_manager_description), fontSize = 13.sp, color = colors.textSecondary)
                    }
                    ui.error?.let { error ->
                        item { Text(error, fontSize = 12.sp, color = colors.danger) }
                    }
                    ui.branchTag?.let { branch ->
                        item { BranchTagSection(branch, enabled = !ui.saving, onChange = viewModel::setBranchTag) }
                    }
                    item { SectionTitle(stringResource(R.string.tags_global_title), stringResource(R.string.tags_global_description)) }
                    tagList(ui.scopeTags(null), ui.saving, onEdit = { form = TagForm(it, it.folderId) }, onDelete = { deleting = it }, onMove = viewModel::move)
                    item { AddTagButton { form = TagForm(null, null) } }
                    item { SectionTitle(stringResource(R.string.tags_folder_title), stringResource(R.string.tags_folder_description)) }
                    if (ui.folders.isEmpty()) {
                        item { Text(stringResource(R.string.tags_no_folders), fontSize = 13.sp, color = colors.textSecondary) }
                    } else {
                        item { FolderSelector(ui, viewModel::selectFolder) }
                        val folderId = ui.selectedFolderId
                        if (folderId != null) {
                            tagList(ui.scopeTags(folderId), ui.saving, onEdit = { form = TagForm(it, it.folderId) }, onDelete = { deleting = it }, onMove = viewModel::move)
                            item { AddTagButton { form = TagForm(null, folderId) } }
                        }
                    }
                }
            }
        }
    }

    form?.let { f ->
        val neighbours = ui.tags.filter { it.folderId == null || it.folderId == f.scope }
        TagFormDialog(
            tag = f.tag,
            initialColor = f.tag?.color ?: ConversationTags.nextColor(neighbours),
            saving = ui.saving,
            error = ui.error,
            onSave = { name, color ->
                val done: (Boolean) -> Unit = { ok -> if (ok) form = null }
                if (f.tag == null) viewModel.create(f.scope, name, color, done) else viewModel.update(f.tag, name, color, done)
            },
            onDismiss = { form = null; viewModel.dismissError() },
        )
    }
    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.tags_delete_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.tags_delete_description, tag.name), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { deleting = null; viewModel.delete(tag) }) { Text(stringResource(R.string.common_delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tagList(
    tags: List<ConversationTag>,
    saving: Boolean,
    onEdit: (ConversationTag) -> Unit,
    onDelete: (ConversationTag) -> Unit,
    onMove: (ConversationTag, Int) -> Unit,
) {
    if (tags.isEmpty()) {
        item { Text(stringResource(R.string.tags_editor_empty), fontSize = 13.sp, color = CodegTheme.colors.textTertiary) }
        return
    }
    items(tags, key = { "tag-${it.id}" }) { tag ->
        val i = tags.indexOf(tag)
        TagEditorRow(
            tag,
            canUp = i > 0 && !saving,
            canDown = i < tags.lastIndex && !saving,
            onUp = { onMove(tag, -1) },
            onDown = { onMove(tag, 1) },
            onEdit = { onEdit(tag) },
            onDelete = { onDelete(tag) },
        )
    }
}

@Composable
private fun SectionTitle(title: String, description: String) {
    val colors = CodegTheme.colors
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(description, fontSize = 12.sp, color = colors.textSecondary)
    }
}

@Composable
private fun TagEditorRow(
    tag: ConversationTag,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { TagChip(tag.name, tag.color) }
        IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.tags_move_up, tag.name), tint = if (canUp) colors.textSecondary else colors.textTertiary.copy(alpha = 0.4f))
        }
        IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.tags_move_down, tag.name), tint = if (canDown) colors.textSecondary else colors.textTertiary.copy(alpha = 0.4f))
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.tags_edit, tag.name), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.tags_delete, tag.name), tint = colors.danger, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddTagButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = CodegTheme.colors.accent, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.tags_new), color = CodegTheme.colors.accent, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun FolderSelector(ui: TagsManagerUiState, onSelect: (Int) -> Unit) {
    val colors = CodegTheme.colors
    var open by remember { mutableStateOf(false) }
    val selected = ui.folders.firstOrNull { it.id == ui.selectedFolderId }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(selected?.name ?: stringResource(R.string.tags_folder_placeholder), fontSize = 14.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.folders.forEach { f ->
                DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; onSelect(f.id) })
            }
        }
    }
}

/** The branch tag: shown or not, and its colour, with a preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchTagSection(branch: BranchTagSetting, enabled: Boolean, onChange: (Boolean, String) -> Unit) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.tags_branch_title), stringResource(R.string.tags_branch_description))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tags_branch_show), fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
            TagChip("main", branch.color, branch = true)
            Switch(
                checked = branch.enabled,
                enabled = enabled,
                onCheckedChange = { onChange(it, branch.color) },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
            )
        }
        ColorPresets(selected = branch.color, enabled = enabled) { onChange(branch.enabled, it) }
    }
}

/** The preset swatches, strong row then soft row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresets(selected: String, enabled: Boolean = true, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConversationTags.COLOR_PRESETS.forEach { hex ->
            val color = colorFromHex(hex) ?: Color.Gray
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) CodegTheme.colors.textPrimary else CodegTheme.colors.surfaceStroke, CircleShape)
                    .clickable(enabled = enabled) { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    val swatch = TagSwatch.of(hex, dark = false)
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = swatch?.let { Color(it.foreground) } ?: Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** New tag / Edit tag: a name, a colour (a preset or `#rrggbb`), a preview, the server's word. */
@Composable
private fun TagFormDialog(
    tag: ConversationTag?,
    initialColor: String,
    saving: Boolean,
    error: String?,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var name by remember { mutableStateOf(tag?.name ?: "") }
    var color by remember { mutableStateOf(initialColor) }
    var custom by remember { mutableStateOf(initialColor) }
    val normalized = ConversationTags.normalizeColor(color)
    val canSave = name.isNotBlank() && normalized != null && !saving
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(stringResource(if (tag == null) R.string.tags_form_create_title else R.string.tags_form_edit_title), color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ConversationTags.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.tags_form_name)) },
                    placeholder = { Text(stringResource(R.string.tags_form_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.tags_form_color), fontSize = 12.sp, color = colors.textSecondary)
                ColorPresets(selected = color) { color = it; custom = it }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { v -> custom = v.take(7); ConversationTags.normalizeColor(v)?.let { color = it } },
                    label = { Text(stringResource(R.string.tags_form_custom_color)) },
                    singleLine = true,
                    isError = ConversationTags.normalizeColor(custom) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name.isNotBlank() && normalized != null) TagChip(name.trim(), normalized)
                error?.let { Text(it, fontSize = 12.sp, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(name, normalized!!) }) {
                Text(stringResource(if (tag == null) R.string.tags_form_create else R.string.common_save), color = if (canSave) colors.accent else colors.textTertiary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) } },
    )
}
