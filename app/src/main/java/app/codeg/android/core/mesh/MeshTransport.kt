package app.codeg.android.core.mesh

import app.codeg.android.BuildConfig
import app.codeg.android.core.datastore.ServerProfile
import com.easytier.jni.EasyTierJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal interface MeshEngine {
    fun start(config: String)
    fun stop(instance: String)
}

private class JniMeshEngine : MeshEngine {
    override fun start(config: String) {
        check(BuildConfig.EASYTIER_ENABLED) { "This build does not include EasyTier." }
        // Native diagnostics can contain config values. Never expose the exception text.
        try { check(EasyTierJNI.runNetworkInstance(config) == 0) }
        catch (_: LinkageError) { error("EasyTier is unavailable on this device.") }
        catch (_: Exception) { error("EasyTier could not start. Check the connection settings.") }
    }
    override fun stop(instance: String) {
        try { check(EasyTierJNI.deleteNetworkInstance(instance) == 0) }
        catch (_: Exception) { error("EasyTier could not stop the previous connection. Retry or restart the app.") }
    }
}

/** One selected-server instance; test connections have an independent short-lived instance.
 * No TUN or device-wide route is installed: only Codeg's localhost TCP forward is exposed.
 */
@Singleton
class MeshTransport internal constructor(private val engine: MeshEngine) {
    @Inject constructor() : this(JniMeshEngine())

    private val mutex = Mutex()
    private var selected: Lease? = null
    private val temporary = mutableListOf<Lease>()
    private val usedPorts = ConcurrentHashMap.newKeySet<Int>()
    private class Lease(val profile: ServerProfile, val password: String, val name: String, val url: String)

    suspend fun prepare(profile: ServerProfile, password: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            selected?.takeIf { it.profile == profile && it.password == password }?.let { return@withLock it.url }
            check(temporary.none { conflicts(profile, password, it) }) { "Finish the connection test before switching this mesh profile." }
            stopSelected()
            if (profile.mesh == null) return@withLock profile.baseUrl
            val lease = start(profile, password)
            selected = lease
            lease.url
        }
    }

    suspend fun address(profile: ServerProfile): String? = mutex.withLock {
        if (profile.mesh == null) profile.baseUrl else selected?.takeIf { it.profile == profile }?.url
    }

    suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { stopSelected() }
    }

    suspend fun <T> test(profile: ServerProfile, password: String, block: suspend (String) -> T): T {
        if (profile.mesh == null) return block(profile.baseUrl)
        var lease: Lease? = null
        var owned = false
        try {
            // Capture before the dispatcher hop resumes, so cancellation cannot leak a native instance.
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val current = selected
                    if (current != null && current.profile.mesh == profile.mesh &&
                        current.profile.baseUrl == profile.baseUrl && current.password == password) {
                        lease = current
                    } else {
                        if ((selected?.let { conflicts(profile, password, it) } == true) ||
                            temporary.any { conflicts(profile, password, it) }) throw MeshTestConflict()
                        lease = start(profile, password)
                        owned = true
                        temporary += requireNotNull(lease)
                    }
                }
            }
            return block(requireNotNull(lease).url)
        } finally {
            if (owned) lease?.let { active ->
                withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock { engine.stop(active.name); temporary.remove(active) }
                }
            }
        }
    }

    private fun conflicts(profile: ServerProfile, password: String, active: Lease): Boolean {
        val mesh = profile.mesh ?: return false
        return mesh.ipv4.isNotEmpty() && mesh.ipv4 == active.profile.mesh?.ipv4 &&
            mesh.networkName == active.profile.mesh?.networkName && password == active.password
    }

    private fun start(profile: ServerProfile, password: String): Lease {
        val port = unusedPort()
        val name = "codeg-${UUID.randomUUID()}"
        val config = MeshConfig.render(profile, password, name, port)
        try { engine.start(config) }
        catch (error: Throwable) {
            runCatching { engine.stop(name) }
            throw error
        }
        return Lease(profile, password, name, "http://127.0.0.1:$port")
    }

    private fun unusedPort(): Int {
        // A stale client from a previous connection must never reach a different profile.
        repeat(64) {
            val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (usedPorts.add(port)) return port
        }
        error("Could not allocate a new local connection port.")
    }

    private fun stopSelected() {
        val previous = selected ?: return
        engine.stop(previous.name)
        selected = null
    }
}

internal class MeshTestConflict : IllegalStateException(
    "The selected connection is already using this virtual IP. Save and select the edited server to test it safely."
)
