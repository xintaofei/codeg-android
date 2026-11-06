package app.codeg.android.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.feature.server.ServerListScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.codeg.android.core.designsystem.CodegBackground
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.main.MainShell
import app.codeg.android.feature.onboarding.OnboardingScreen
import app.codeg.android.feature.server.ServerEditorScreen

/**
 * Root of the app: applies the theme (from persisted appearance settings) and the
 * glowing background, then branches on whether any server exists.
 *
 * - servers still loading → a splash (so onboarding never flashes).
 * - no servers → the onboarding sub-graph (add your first server).
 * - servers present → [MainShell] (the bottom-nav app shell).
 */
@Composable
fun CodegApp(appViewModel: AppViewModel = hiltViewModel()) {
    val settings by appViewModel.settings.collectAsStateWithLifecycle()

    CodegTheme(themeMode = settings.themeMode, accentId = settings.accentId) {
        CodegBackground {
            val servers by appViewModel.servers.collectAsStateWithLifecycle()
            val connection by appViewModel.connection.collectAsStateWithLifecycle()
            val managing by appViewModel.managingServers.collectAsStateWithLifecycle()
            when {
                servers == null -> Splash()
                servers!!.isEmpty() -> OnboardingRoot()
                managing -> ServerManagement(appViewModel, servers!!)
                connection is ConnectionState.Ready -> key((connection as ConnectionState.Ready).profile) {
                    MainShell(appViewModel, servers!!)
                }
                else -> ConnectionGate(connection, appViewModel)
            }
        }
    }
}

/** Empty-state graph: onboarding with a path to the server editor. */
@Composable
private fun OnboardingRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "onboarding") {
        composable("onboarding") {
            OnboardingScreen(onAddServer = { nav.navigate("editor") })
        }
        composable("editor") {
            ServerEditorScreen(onDone = { nav.popBackStack() })
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = CodegTheme.colors.accent)
    }
}

/** A failed or slow mesh connection never traps the user away from server management. */
@Composable
private fun ConnectionGate(state: ConnectionState, model: AppViewModel) {
    val failed = state is ConnectionState.Failed
    val profile = when (state) {
        is ConnectionState.Connecting -> state.profile
        is ConnectionState.Failed -> state.profile
        else -> null
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!failed) CircularProgressIndicator()
            Text(stringResource(if (failed) R.string.mesh_connect_failed else R.string.mesh_connecting, profile?.name.orEmpty()))
            if (failed) TextButton(onClick = model::retryConnection) { Text(stringResource(R.string.mesh_retry)) }
            TextButton(onClick = { model.managingServers.value = true }) { Text(stringResource(R.string.mesh_choose_server)) }
        }
    }
}

@Composable
private fun ServerManagement(model: AppViewModel, servers: List<ServerProfile>) {
    val nav = rememberNavController()
    val selected by model.selectedProfile.collectAsStateWithLifecycle()
    NavHost(navController = nav, startDestination = "servers") {
        composable("servers") {
            ServerListScreen(
                servers = servers, selectedId = selected?.id,
                onSelect = model::selectServer, onDelete = model::deleteServer,
                onAdd = { nav.navigate("editor") },
                onEdit = { nav.navigate("editor?serverId=$it") },
                onBack = { model.managingServers.value = false },
            )
        }
        composable("editor?serverId={serverId}", arguments = listOf(navArgument("serverId") {
            type = NavType.StringType; nullable = true; defaultValue = null
        })) { ServerEditorScreen(onDone = { nav.popBackStack() }) }
    }
}
