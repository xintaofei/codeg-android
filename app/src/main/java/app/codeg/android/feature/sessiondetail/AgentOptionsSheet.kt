package app.codeg.android.feature.sessiondetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.AgentVisuals
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentChoice
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectOption
import kotlinx.coroutines.launch

private enum class SheetPage { Main, Branch }

/** The `applying` key for the mode selector (config options key by their own id). Keying by
 *  the logical selector — not the specific value — lets us lock every row of that selector
 *  while one apply is in flight, so a late-returning apply can't clobber a newer pick. */
private const val MODE_APPLY = "mode"

/**
 * The agent options sheet. For a new-session draft it hosts an **Agent picker**
 * and a switchable **Workspace** (folder + branch); for an existing session the
 * folder is read-only and only the branch is switchable. Below that sit the
 * agent's Mode / Config selectors. The branch picker is an in-sheet page swap
 * (back via the system back gesture). Mirrors iOS `AgentOptionsButton`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentOptionsSheet(
    viewModel: SessionDetailViewModel,
    onOpenSession: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SheetPage.Main) }

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(AgentOptionsData()) }
    var currentMode by remember { mutableStateOf<String?>(null) }
    var currentValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var note by remember { mutableStateOf<String?>(null) }
    // Keyed by the LOGICAL selector ("mode" / config-option id), as a set — several config
    // options can be applying at once, and each must clear only its own lock (iOS Set<String>).
    var applying by remember { mutableStateOf<Set<String>>(emptySet()) }
    val applyFailedNote = stringResource(R.string.agentopts_apply_failed)

    // Reload the agent's options whenever the (draft) agent or folder changes.
    LaunchedEffect(ui.agent, ui.selectedFolderId) {
        loading = true
        note = null
        // Clear any in-flight apply lock: a switch cancels the old-context options connect, which
        // cancels the awaiting apply before it can release its own lock. Resetting here (the switch
        // is the trigger) keeps the new agent's rows tappable.
        applying = emptySet()
        data = viewModel.loadAgentOptions()
        currentMode = data.initialModeId
        currentValues = data.initialConfig
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
        // Disable the sheet's own back-to-dismiss so our handler can route back:
        // the branch page returns to the main page, the main page dismisses.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler { if (page == SheetPage.Branch) page = SheetPage.Main else onDismiss() }
        when (page) {
            SheetPage.Branch -> BranchPickerContent(
                viewModel = viewModel,
                onBack = { page = SheetPage.Main },
                onOpenSession = { folderId -> onDismiss(); onOpenSession(folderId) },
            )

            SheetPage.Main -> {
                val hasConfig = data.configOptions.isNotEmpty()
                val showModes = data.modes?.availableModes?.isNotEmpty() == true && !hasConfig
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        stringResource(if (ui.isDraftEditable) R.string.agentopts_new_session_title else R.string.agentopts_title),
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
                    )

                    if (ui.isDraftEditable) AgentPickerSection(ui.availableAgents, ui.agent) { viewModel.selectAgent(it) }

                    if (ui.folderPath != null) {
                        WorkspaceSection(
                            ui = ui,
                            onSelectFolder = { viewModel.selectFolder(it) },
                            onOpenBranch = { page = SheetPage.Branch },
                        )
                    }

                    if (ui.isInFlight) {
                        Text(stringResource(R.string.agentopts_busy), fontSize = 12.sp, color = colors.textTertiary)
                    }

                    note?.let { Text(it, fontSize = 12.sp, color = colors.textTertiary) }

                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                        showModes -> {
                            Text(stringResource(R.string.agentopts_mode), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
                            val modeApplying = MODE_APPLY in applying
                            OptionGroup {
                                data.modes!!.availableModes.forEachIndexed { i, mode ->
                                    if (i > 0) GroupDivider()
                                    // Lock every mode row while a mode apply is in flight; also while a
                                    // turn is running (options can't change mid-response — and an apply
                                    // that failed then could null the live turn's connection). Spinner on
                                    // the optimistically-selected row.
                                    OptionRow(mode.name.ifEmpty { mode.id }, mode.description, selected = currentMode == mode.id, busy = modeApplying && currentMode == mode.id, enabled = !modeApplying && !ui.isInFlight) {
                                        // Move the checkmark instantly (iOS parity); the apply runs in the
                                        // background and reverts to the previous pick only on real failure.
                                        val previous = currentMode
                                        currentMode = mode.id
                                        applying = applying + MODE_APPLY
                                        note = null
                                        scope.launch {
                                            val resolved = viewModel.applyMode(mode.id)
                                            applying = applying - MODE_APPLY
                                            // Adopt the reconciled/normalized value; on failure revert + notice.
                                            if (resolved != null) currentMode = resolved
                                            else { currentMode = previous; note = applyFailedNote }
                                        }
                                    }
                                }
                            }
                        }
                        hasConfig -> data.configOptions.forEach { option ->
                            ConfigOptionSection(option, currentValues[option.id], optionApplying = option.id in applying, locked = ui.isInFlight) { valueId ->
                                // Optimistic: move the checkmark now, revert only if the apply fails.
                                val previous = currentValues[option.id]
                                currentValues = currentValues + (option.id to valueId)
                                applying = applying + option.id
                                note = null
                                scope.launch {
                                    val resolved = viewModel.applyConfig(option.id, valueId)
                                    applying = applying - option.id
                                    if (resolved != null) {
                                        currentValues = currentValues + (option.id to resolved)
                                    } else {
                                        currentValues = if (previous != null) currentValues + (option.id to previous) else currentValues - option.id
                                        note = applyFailedNote
                                    }
                                }
                            }
                        }
                        else -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.agentopts_no_options), fontSize = 13.sp, color = colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

// region Agent picker (draft only)

@Composable
private fun AgentPickerSection(agents: List<AgentChoice>, selected: AgentType, onSelect: (AgentType) -> Unit) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.agentopts_agent_section), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(agents, key = { it.type.wire }) { choice ->
                val agent = choice.type
                val isSelected = agent == selected
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(agent) }
                        .padding(vertical = 6.dp, horizontal = 6.dp)
                        .width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Box(Modifier.size(54.dp).clip(CircleShape).border(2.dp, AgentVisuals.accent(agent), CircleShape))
                        }
                        AgentAvatar(agent, size = 46.dp, modifier = Modifier.alpha(if (isSelected) 1f else 0.75f))
                    }
                    Text(
                        choice.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// endregion

// region Workspace (folder + branch)

@Composable
private fun WorkspaceSection(
    ui: SessionDetailUiState,
    onSelectFolder: (FolderDetail) -> Unit,
    onOpenBranch: () -> Unit,
) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.agentopts_workspace), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
        OptionGroup {
            if (ui.isDraftEditable) FolderMenuRow(ui, onSelectFolder) else FolderReadOnlyRow(ui)
            GroupDivider()
            BranchRow(ui.currentBranch, isBusy = ui.isInFlight, onClick = onOpenBranch)
        }
    }
}

@Composable
private fun FolderMenuRow(ui: SessionDetailUiState, onSelectFolder: (FolderDetail) -> Unit) {
    val colors = CodegTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Folder, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(ui.folderName ?: stringResource(R.string.agentopts_choose_folder), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ui.folderPath?.let { PathLine(it) }
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ui.availableFolders.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(f.name, fontSize = 14.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.path, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null, tint = if (f.id == ui.selectedFolderId) colors.accent else colors.textTertiary, modifier = Modifier.size(18.dp)) },
                    onClick = { onSelectFolder(f); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun FolderReadOnlyRow(ui: SessionDetailUiState) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Folder, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(ui.folderName ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.folderPath?.let { PathLine(it) }
        }
        Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.agentopts_folder_readonly), tint = colors.textTertiary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun BranchRow(current: String?, isBusy: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy) { onClick() }
            .alpha(if (isBusy) 0.5f else 1f)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, null, tint = if (isBusy) colors.textTertiary else colors.accent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.agentopts_branch), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            Text(
                current ?: stringResource(R.string.agentopts_select_branch),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PathLine(path: String) {
    Text(path, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CodegTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// endregion

// region Branch picker page

@Composable
private fun BranchPickerContent(
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    onOpenSession: (Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var list by remember { mutableStateOf<GitBranchList?>(null) }
    var query by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val isBusy = ui.isInFlight
    val current = ui.currentBranch

    LaunchedEffect(Unit) {
        list = viewModel.loadBranches()
        loading = false
    }

    fun pick(display: String, target: String, isRemote: Boolean) {
        if (switching != null) return
        switching = display
        scope.launch {
            val outcome = viewModel.switchBranch(target, isRemote)
            switching = null
            when (outcome) {
                BranchSwitchOutcome.Noop, BranchSwitchOutcome.SwitchedInPlace -> onBack()
                is BranchSwitchOutcome.OpenSession -> onOpenSession(outcome.folderId)
                BranchSwitchOutcome.Failed -> Unit // notice surfaced; stay on the page
            }
        }
    }

    val worktree = list?.worktreeBranches?.toSet() ?: emptySet()
    val locals = list?.local.orEmpty().filter { it.matches(query) }
    val remotes = list?.remote.orEmpty().filterNot { it.endsWith("/HEAD") }.filter { it.matches(query) }

    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
                }
                Text(stringResource(R.string.agentopts_branch), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            }
        }
        ui.notice?.let { msg -> item("err") { Text(msg, fontSize = 12.sp, color = colors.danger) } }
        item("search") {
            CodegTextField(query, { query = it }, label = stringResource(R.string.branch_filter), mono = true)
        }
        item("new") {
            NewBranchInline(
                show = showNew, name = newName, current = current, creating = creating,
                onShow = { showNew = true }, onName = { newName = it },
                onCancel = { showNew = false; newName = "" },
                onCreate = {
                    creating = true
                    scope.launch {
                        val ok = viewModel.createBranch(newName, current)
                        creating = false
                        if (ok) onBack()
                    }
                },
            )
        }

        if (loading) {
            item("loading") {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.branch_loading), fontSize = 12.sp, color = colors.textSecondary)
                }
            }
        } else if (locals.isEmpty() && remotes.isEmpty()) {
            item("empty") { Text(stringResource(R.string.branch_none), fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp)) }
        } else {
            if (locals.isNotEmpty()) {
                item("lh") { BranchSectionLabel(stringResource(R.string.branch_local)) }
                items(locals, key = { "l-$it" }) { name ->
                    val occupied = worktree.contains(name) && name != current
                    BranchRowItem(display = name, target = name, isRemote = false, occupied = occupied, isCurrent = name == current, switching = switching == name, enabled = switching == null) {
                        pick(name, name, false)
                    }
                }
            }
            if (remotes.isNotEmpty()) {
                item("rh") { BranchSectionLabel(stringResource(R.string.branch_remote)) }
                items(remotes, key = { "r-$it" }) { name ->
                    val target = name.substringAfter('/', name)
                    val occupied = worktree.contains(target) && target != current
                    BranchRowItem(display = name, target = target, isRemote = true, occupied = occupied, isCurrent = target == current, switching = switching == name, enabled = switching == null) {
                        pick(name, target, true)
                    }
                }
            }
        }
    }
}

private fun String.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return q.isEmpty() || lowercase().contains(q)
}

@Composable
private fun NewBranchInline(
    show: Boolean,
    name: String,
    current: String?,
    creating: Boolean,
    onShow: () -> Unit,
    onName: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    val colors = CodegTheme.colors
    if (!show) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onShow() }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.branch_new_branch), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CodegTextField(name, onName, label = stringResource(R.string.branch_new_branch), mono = true) }
                FilledIconButton(
                    onClick = onCreate,
                    enabled = name.isNotBlank() && !creating,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.accent.copy(alpha = 0.16f), contentColor = colors.accent),
                ) {
                    if (creating) CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(18.dp))
                    else Icon(Icons.Rounded.Check, stringResource(R.string.branch_create_branch))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                current?.let { Text(stringResource(R.string.branch_from, it), fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) } ?: Box(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun BranchRowItem(
    display: String,
    target: String,
    isRemote: Boolean,
    occupied: Boolean,
    isCurrent: Boolean,
    switching: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && !occupied && !isCurrent) { onClick() }
            .alpha(if (occupied) 0.5f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, null, tint = if (occupied) colors.textTertiary else colors.accent, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(display, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (occupied) Text(stringResource(R.string.branch_occupied), fontSize = 11.sp, color = colors.textTertiary)
        }
        when {
            switching -> CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(16.dp))
            isCurrent -> Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BranchSectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CodegTheme.colors.textTertiary, modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 2.dp))
}

// endregion

// region Mode / config selectors (shared)

@Composable
private fun ConfigOptionSection(option: SessionConfigOption, currentValue: String?, optionApplying: Boolean, locked: Boolean, onSelect: (String) -> Unit) {
    val colors = CodegTheme.colors
    val flat: List<SessionConfigSelectOption> = if (option.kind.groups.isNotEmpty()) option.kind.groups.flatMap { it.options } else option.kind.options
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(option.name.uppercase().ifEmpty { option.id.uppercase() }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textTertiary)
        OptionGroup {
            flat.forEachIndexed { i, opt ->
                if (i > 0) GroupDivider()
                // Lock every row of this option while its apply is in flight, or while a turn is
                // running ([locked]); spinner on the pick.
                OptionRow(opt.name.ifEmpty { opt.value }, opt.description, selected = currentValue == opt.value, busy = optionApplying && currentValue == opt.value, enabled = !optionApplying && !locked) { onSelect(opt.value) }
            }
        }
    }
}

@Composable
private fun OptionGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CodegTheme.colors.bg.copy(alpha = 0.4f))) { content() }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 14.dp), thickness = Dp.Hairline, color = CodegTheme.colors.textPrimary.copy(alpha = 0.06f))
}

@Composable
private fun OptionRow(title: String, description: String?, selected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = colors.textPrimary)
            description?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = colors.textTertiary) }
        }
        if (busy) CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(16.dp))
        else if (selected) Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
    }
}

// endregion
