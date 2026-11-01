package app.codeg.android.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.SettingsIconBadge
import app.codeg.android.core.designsystem.theme.AccentPalette
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.datastore.ThemeMode
import app.codeg.android.core.model.AgentType
import android.text.format.Formatter

private enum class SettingsLeaf(@StringRes val titleRes: Int, val icon: ImageVector, @StringRes val groupRes: Int, val implemented: Boolean) {
    APPEARANCE(R.string.settings_leaf_appearance, Icons.Rounded.Brush, R.string.settings_group_personalization, true),
    LANGUAGE(R.string.settings_leaf_language, Icons.Rounded.Language, R.string.settings_group_personalization, true),
    GENERAL(R.string.settings_leaf_general, Icons.Rounded.Tune, R.string.settings_group_personalization, true),
    QUICK_MESSAGES(R.string.settings_leaf_quick_messages, Icons.AutoMirrored.Rounded.Chat, R.string.settings_group_personalization, true),
    AGENTS(R.string.settings_leaf_agents, Icons.Rounded.Person, R.string.settings_group_ai_agents, true),
    MODEL_PROVIDERS(R.string.settings_leaf_model_providers, Icons.Rounded.Memory, R.string.settings_group_ai_agents, true),
    EXPERTS(R.string.settings_leaf_experts, Icons.Rounded.School, R.string.settings_group_ai_agents, true),
    SKILLS(R.string.settings_leaf_skills, Icons.Rounded.Extension, R.string.settings_group_ai_agents, true),
    MCP(R.string.settings_leaf_mcp, Icons.Rounded.Memory, R.string.settings_group_ai_agents, true),
    VERSION_CONTROL(R.string.settings_leaf_version_control, Icons.Rounded.Difference, R.string.settings_group_integrations, true),
    CHAT_CHANNELS(R.string.settings_leaf_chat_channels, Icons.AutoMirrored.Rounded.Chat, R.string.settings_group_integrations, true),
    SYSTEM(R.string.settings_leaf_system, Icons.Rounded.Settings, R.string.settings_group_system, true),
    LOCAL_CACHE(R.string.settings_local_cache, Icons.Rounded.Memory, R.string.settings_group_system, true),
    DOWNLOADS(R.string.session_files_title, Icons.Rounded.FolderOpen, R.string.settings_group_system, true),
    OPEN_SOURCE_LICENSES(R.string.settings_leaf_open_source_licenses, Icons.Rounded.Policy, R.string.settings_group_system, true),
    ABOUT(R.string.settings_leaf_about, Icons.Rounded.Info, R.string.settings_group_system, true),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), onOpenDownloads: () -> Unit = {}) {
    val colors = CodegTheme.colors
    var leaf by remember { mutableStateOf<SettingsLeaf?>(null) }
    // A second level beneath the Agents leaf: the per-agent config editor.
    var agentDetail by remember { mutableStateOf<AgentType?>(null) }
    val inAgentDetail = leaf == SettingsLeaf.AGENTS && agentDetail != null
    BackHandler(enabled = leaf != null) { if (inAgentDetail) agentDetail = null else leaf = null }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        inAgentDetail -> agentDetail!!.displayName
                        leaf != null -> stringResource(leaf!!.titleRes)
                        else -> stringResource(R.string.tab_settings)
                    }
                    Text(title)
                },
                navigationIcon = {
                    if (leaf != null) IconButton(onClick = { if (inAgentDetail) agentDetail = null else leaf = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (leaf) {
                null -> SettingsRoot(onSelect = { if (it == SettingsLeaf.DOWNLOADS) onOpenDownloads() else { leaf = it; agentDetail = null } })
                SettingsLeaf.APPEARANCE -> AppearanceSettings(viewModel)
                SettingsLeaf.LANGUAGE -> LanguageContent()
                SettingsLeaf.ABOUT -> AboutScreen(viewModel)
                SettingsLeaf.QUICK_MESSAGES -> QuickMessagesContent()
                SettingsLeaf.GENERAL -> GeneralContent()
                SettingsLeaf.MODEL_PROVIDERS -> ModelProvidersContent()
                SettingsLeaf.SYSTEM -> SystemContent()
                SettingsLeaf.LOCAL_CACHE -> LocalCacheSettings(viewModel)
                SettingsLeaf.OPEN_SOURCE_LICENSES -> OpenSourceLicensesScreen()
                SettingsLeaf.MCP -> McpContent()
                SettingsLeaf.AGENTS ->
                    if (agentDetail == null) AgentsContent(onOpen = { agentDetail = it })
                    else AgentDetailContent(agentDetail!!, onClose = { agentDetail = null })
                SettingsLeaf.SKILLS -> SkillsContent()
                SettingsLeaf.EXPERTS -> ExpertsContent()
                SettingsLeaf.CHAT_CHANNELS -> ChatChannelsContent()
                SettingsLeaf.VERSION_CONTROL -> VersionControlContent()
                else -> ComingSoon(leaf!!.titleRes)
            }
        }
    }
}

@Composable
private fun SettingsRoot(onSelect: (SettingsLeaf) -> Unit) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (group in SettingsLeaf.entries.map { it.groupRes }.distinct()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(group), style = MaterialTheme.typography.labelLarge, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.5f))) {
                    val leaves = SettingsLeaf.entries.filter { it.groupRes == group }
                    leaves.forEachIndexed { index, l ->
                        ListItem(
                            headlineContent = { Text(stringResource(l.titleRes)) },
                            leadingContent = { SettingsIconBadge(l.icon) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = colors.textPrimary,
                            ),
                            modifier = Modifier.clickable { onSelect(l) },
                        )
                        if (index < leaves.lastIndex) {
                            HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 60.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCacheSettings(viewModel: SettingsViewModel) {
    val colors = CodegTheme.colors
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refreshCacheSize() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_cache_description), color = colors.textSecondary)
        Text(
            stringResource(R.string.settings_cache_size, cache.sizeBytes?.let { Formatter.formatFileSize(context, it) } ?: "—"),
            color = colors.textPrimary,
        )
        Button(onClick = viewModel::clearCache, enabled = !cache.clearing) {
            if (cache.clearing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                stringResource(if (cache.clearing) R.string.settings_cache_clearing else R.string.settings_cache_clear),
                modifier = Modifier.padding(start = if (cache.clearing) 8.dp else 0.dp),
            )
        }
        if (cache.cleared) Text(stringResource(R.string.settings_cache_cleared), color = colors.textSecondary)
        cache.error?.let {
            Text(stringResource(R.string.settings_cache_failed, it), color = colors.danger)
        }
    }
}

@Composable
private fun AppearanceSettings(viewModel: SettingsViewModel) {
    val colors = CodegTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
            val modes = ThemeMode.entries
            CodegSegmented(
                options = modes.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                selectedIndex = modes.indexOf(settings.themeMode),
                onSelect = { viewModel.setThemeMode(modes[it]) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_accent), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (palette in AccentPalette.all) {
                    val selected = settings.accentId == palette.id
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(palette.resolve(colors.isDark))
                            .border(if (selected) 3.dp else 0.dp, colors.textPrimary, CircleShape)
                            .clickable { viewModel.setAccent(palette.id) },
                    )
                }
            }
        }
        // Live preview.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.6f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_preview), fontSize = 13.sp, color = colors.textTertiary)
            Text(stringResource(R.string.settings_accent_preview_caption), fontSize = 14.sp, color = colors.textPrimary)
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.accent).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_accent_button), color = colors.onAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AboutScreen(viewModel: SettingsViewModel) {
    val colors = CodegTheme.colors
    val about by viewModel.about.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(stringResource(R.string.settings_version, appVersion), fontSize = 13.sp, color = colors.textSecondary)
        about.serverName?.let { Text(stringResource(R.string.settings_server, it), fontSize = 13.sp, color = colors.textSecondary) }
        about.serverVersion?.let { Text(stringResource(R.string.settings_codeg_version, it), fontSize = 13.sp, color = colors.textTertiary) }
        Text(
            stringResource(R.string.settings_about_description),
            fontSize = 13.sp, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ComingSoon(@StringRes title: Int) {
    val colors = CodegTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(36.dp))
            Text(stringResource(title), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_coming_soon), fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}
