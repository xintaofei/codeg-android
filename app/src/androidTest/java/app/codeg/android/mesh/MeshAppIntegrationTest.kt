package app.codeg.android.mesh

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.codeg.android.BuildConfig
import app.codeg.android.MainActivity
import app.codeg.android.app.AppViewModel
import app.codeg.android.app.ConnectionState
import app.codeg.android.core.datastore.MeshSettings
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.StreamFrame
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test against a synthetic peer; never run against a user's live server. */
@RunWith(AndroidJUnit4::class)
class MeshAppIntegrationTest {
    @Test fun selectedMeshReachesTheMainShellAndProductionHttpAndWebSocketClients() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.EASYTIER_ENABLED && arguments.containsKey("meshPeer"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = EntryPointAccessors.fromApplication(context, MeshTestServices::class.java).serverRepository()
        val profile = ServerProfile("instrumented-mesh", "Instrumented mesh", arguments.getString("meshServer")!!,
            MeshSettings(arguments.getString("meshNetwork")!!, listOf(arguments.getString("meshPeer")!!)))
        repository.save(profile, "codeg-fixture-token", "codeg-fixture-network-password")
        repository.select(profile.id)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppViewModel::class.java] }
        val ready = withTimeout(60_000) {
            model.connection.filterIsInstance<ConnectionState.Ready>().first { it.profile?.id == profile.id }
        }
        val selected = requireNotNull(ready.profile)
        assertEquals("Instrumented mesh", selected.name)
        val client = requireNotNull(repository.client(selected))
        assertTrue(client.baseUrl.startsWith("http://127.0.0.1:"))
        assertEquals("codeg-mesh-fixture", client.health().version)
        assertEquals("fixture-project", client.listFolders().single().name)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "mesh HTTP and profile restoration passed\n") })
        val stream = requireNotNull(repository.eventStream(selected))
        stream.ping()
        assertEquals(StreamFrame.Pong, withTimeout(10_000) { stream.frames().first { it == StreamFrame.Pong } })
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "mesh WebSocket pong passed\n") })
        // The real root gate has transitioned to MainShell; check a rendered tab via accessibility.
        withTimeout(10_000) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null && hasChatsTab(root)) break
                kotlinx.coroutines.delay(100)
            }
        }
        // Leave only this synthetic profile for a separate force-stop/cold-start acceptance check.
    }

    private fun hasChatsTab(node: AccessibilityNodeInfo): Boolean {
        // Compose exposes virtual children, but its provider need not implement
        // findAccessibilityNodeInfosByText. Walk the same tree UIAutomator reads.
        if (node.text?.toString() in setOf("Chats", "会话")) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasChatsTab) == true }
    }
}
