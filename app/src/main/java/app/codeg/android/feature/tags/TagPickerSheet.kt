package app.codeg.android.feature.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag

/**
 * A conversation's tags (web `ConversationTagPickerPanel`): search, toggle, and create a tag
 * from the search text — global, or in the conversation's folder. Grouped Global / its folder /
 * "Other folders", the last only for tags already on it from elsewhere, so they can come off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    conversation: ConversationSummary,
    onManageTags: (() -> Unit)?,
    onDismiss: () -> Unit,
    viewModel: TagPickerViewModel = hiltViewModel(),
) {
    val colors = CodegTheme.colors
    LaunchedEffect(conversation.id) { viewModel.open(conversation) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tags_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(
                        conversation.trimmedTitle ?: stringResource(R.string.session_untitled),
                        fontSize = 12.sp,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onManageTags != null) {
                    IconButton(onClick = { onDismiss(); onManageTags() }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tags_manage), tint = colors.textSecondary)
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(ConversationTags.MAX_NAME_LENGTH) },
                placeholder = { Text(stringResource(R.string.tags_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ui.error?.let { error ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, fontSize = 12.sp, color = colors.danger, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::dismissError, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            val tags = ui.tags
            if (tags == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                }
                return@Column
            }
            val scope = ui.scope
            val needle = query.trim().lowercase()
            fun visible(t: ConversationTag) = needle.isEmpty() || t.name.lowercase().contains(needle)
            val global = tags.filter { it.folderId == null && visible(it) }.sortedWith(ConversationTags.ORDER)
            val own = tags.filter { scope != null && it.folderId == scope && visible(it) }.sortedWith(ConversationTags.ORDER)
            val other = tags.filter { it.folderId != null && it.folderId != scope && it.id in ui.tagIds && visible(it) }.sortedWith(ConversationTags.ORDER)
            val name = query.trim()
            val offerGlobal = name.isNotEmpty() && ConversationTags.findByName(tags, null, name) == null
            val offerFolder = name.isNotEmpty() && scope != null && ConversationTags.findByName(tags, scope, name) == null
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (tags.isEmpty() && name.isEmpty()) {
                    item { Hint(stringResource(R.string.tags_picker_empty)) }
                } else if (global.isEmpty() && own.isEmpty() && other.isEmpty() && !offerGlobal && !offerFolder) {
                    item { Hint(stringResource(R.string.tags_picker_no_results)) }
                }
                if (global.isNotEmpty()) {
                    item { GroupLabel(stringResource(R.string.tags_global)) }
                    items(global, key = { "t${it.id}" }) { TagToggleRow(it, it.id in ui.tagIds) { viewModel.toggle(it) } }
                }
                if (own.isNotEmpty()) {
                    item { GroupLabel(ui.scopeName ?: stringResource(R.string.tags_folder_fallback)) }
                    items(own, key = { "t${it.id}" }) { TagToggleRow(it, it.id in ui.tagIds) { viewModel.toggle(it) } }
                }
                if (other.isNotEmpty()) {
                    item { GroupLabel(stringResource(R.string.tags_other_folders)) }
                    items(other, key = { "t${it.id}" }) { TagToggleRow(it, true) { viewModel.toggle(it) } }
                }
                if (offerGlobal) {
                    item {
                        CreateRow(stringResource(R.string.tags_create_global, name), enabled = !ui.creating) {
                            viewModel.create(name, global = true)
                            query = ""
                        }
                    }
                }
                if (offerFolder) {
                    item {
                        CreateRow(stringResource(R.string.tags_create_in_folder, name, ui.scopeName ?: ""), enabled = !ui.creating) {
                            viewModel.create(name, global = false)
                            query = ""
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = CodegTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = CodegTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun TagToggleRow(tag: ConversationTag, checked: Boolean, onToggle: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textTertiary),
            modifier = Modifier.padding(8.dp),
        )
        TagChip(tag.name, tag.color)
    }
}

@Composable
private fun CreateRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 14.sp, color = if (enabled) colors.accent else colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
