package app.codeg.android.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.codeg.android.core.designsystem.CodegBackground
import app.codeg.android.core.designsystem.markdown.LocalChatHtmlRendering
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
        CompositionLocalProvider(LocalChatHtmlRendering provides settings.chatHtmlRendering) {
            CodegBackground {
                val servers by appViewModel.servers.collectAsStateWithLifecycle()
                when (val list = servers) {
                    null -> Splash()
                    else -> if (list.isEmpty()) OnboardingRoot() else MainShell(appViewModel, list)
                }
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
