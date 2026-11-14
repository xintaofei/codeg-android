package app.codeg.android.feature.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail

/**
 * Filter the Chats list by tags (web `TagFilterPanel`): match any or all, the tags grouped
 * Global then by folder, each with how many listed conversations carry it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagFilterSheet(
    tags: List<ConversationTag>,
    conversations: List<ConversationSummary>,
    folders: List<FolderDetail>,
    filter: TagFilter,
    onChange: (TagFilter) -> Unit,
    onManageTags: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var query by remember { mutableStateOf("") }
    val counts = remember(conversations) {
        val out = HashMap<Int, Int>()
        conversations.forEach { c -> c.tagIds.forEach { out[it] = (out[it] ?: 0) + 1 } }
        out
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tags_filter_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (filter.isActive) {
                    TextButton(onClick = { onChange(TagFilter.NONE) }) { Text(stringResource(R.string.tags_filter_clear), color = colors.accent) }
                }
                if (onManageTags != null) {
                    IconButton(onClick = { onDismiss(); onManageTags() }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tags_manage), tint = colors.textSecondary)
                    }
                }
            }
            if (tags.isEmpty()) {
                Text(stringResource(R.string.tags_filter_no_tags), fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                return@Column
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tags_filter_match), fontSize = 13.sp, color = colors.textSecondary)
                for (mode in TagFilterMode.entries) {
                    FilterChip(
                        selected = filter.mode == mode,
                        onClick = { onChange(filter.copy(mode = mode)) },
                        label = { Text(stringResource(if (mode == TagFilterMode.ANY) R.string.tags_filter_any else R.string.tags_filter_all)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accent.copy(alpha = 0.18f)),
                    )
                }
            }
            if (tags.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.tags_filter_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val needle = query.trim().lowercase()
            val visible = tags.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            val groups = remember(visible, folders) {
                val global = visible.filter { it.folderId == null }.sortedWith(ConversationTags.ORDER)
                val byFolder = visible.filter { it.folderId != null }.groupBy { it.folderId!! }
                    .map { (id, list) -> (folders.firstOrNull { it.id == id }?.name ?: "#$id") to list.sortedWith(ConversationTags.ORDER) }
                    .sortedBy { it.first.lowercase() }
                listOfNotNull(global.takeIf { it.isNotEmpty() }?.let { null to it }) + byFolder
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (groups.isEmpty()) {
                    item { Text(stringResource(R.string.tags_picker_no_results), fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.padding(20.dp)) }
                }
                groups.forEach { (label, list) ->
                    item {
                        Text(
                            label ?: stringResource(R.string.tags_global),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { "f${it.id}" }) { tag ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Checkbox) { onChange(filter.toggle(tag.id)) }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Checkbox(
                                checked = tag.id in filter.tagIds,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textTertiary),
                                modifier = Modifier.padding(8.dp),
                            )
                            TagChip(tag.name, tag.color, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.weight(1f))
                            Text("${counts[tag.id] ?: 0}", fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The strip over a filtered list (web `SidebarTagFilterBar`): the picked tags, each removable,
 * how they combine when there are several, and clear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFilterBar(filter: TagFilter, tags: List<ConversationTag>, onChange: (TagFilter) -> Unit, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val byId = remember(tags) { tags.associateBy { it.id } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (filter.tagIds.size > 1) {
                Text(
                    stringResource(if (filter.mode == TagFilterMode.ALL) R.string.tags_filter_mode_all else R.string.tags_filter_mode_any),
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            filter.tagIds.mapNotNull { byId[it] }.forEach { tag ->
                Row(
                    Modifier.clickable(onClickLabel = stringResource(R.string.tags_filter_remove, tag.name)) { onChange(filter.toggle(tag.id)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagChip(tag.name, tag.color)
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                }
            }
        }
        TextButton(onClick = { onChange(TagFilter.NONE) }) { Text(stringResource(R.string.tags_filter_clear), fontSize = 12.sp, color = colors.accent) }
    }
}
