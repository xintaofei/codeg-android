package app.codeg.android.core.mesh

import app.codeg.android.core.datastore.MeshSettings
import app.codeg.android.core.datastore.ServerProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class MeshTransportTest {
    private val profile = ServerProfile("test-server", "Test", "http://10.44.0.2:3080",
        MeshSettings("test-network", listOf("tcp://peer.example.test:11010")))

    private class Engine : MeshEngine {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        override fun start(config: String) { started += config }
        override fun stop(instance: String) { stopped += instance }
    }

    @Test fun `legacy JSON remains a direct connection`() {
        val old = Json.decodeFromString<ServerProfile>("""{"id":"a","name":"A","baseUrl":"https://example.test"}""")
        assertNull(old.mesh)
    }

    @Test fun `direct mode does not load or start native code`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        val direct = profile.copy(mesh = null)
        assertEquals(direct.baseUrl, transport.prepare(direct, ""))
        assertEquals(direct.baseUrl, transport.address(direct))
        assertTrue(engine.started.isEmpty())
    }

    @Test fun `concurrent clients reuse the selected native instance`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        val requests = List(6) { async { transport.prepare(profile, "fixture-password") } }
        val addresses = requests.map { it.await() }.toSet()
        assertEquals(1, addresses.size)
        assertTrue(addresses.single().startsWith("http://127.0.0.1:"))
        assertEquals(1, engine.started.size)
        assertEquals(addresses.single(), transport.address(profile))
    }

    @Test fun `switching to direct stops the mesh and invalidates its address`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        transport.prepare(profile, "fixture-password")
        transport.prepare(profile.copy(id = "direct", mesh = null), "")
        assertEquals(1, engine.stopped.size)
        assertNull(transport.address(profile))
    }

    @Test fun `editing a mesh profile restarts the instance`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        transport.prepare(profile, "fixture-password")
        val edited = profile.copy(baseUrl = "http://10.44.0.3:3080")
        transport.prepare(edited, "new-fixture-password")
        assertEquals(2, engine.started.size)
        assertEquals(1, engine.stopped.size)
        assertNull(transport.address(profile))
        assertNotNull(transport.address(edited))
    }

    @Test fun `cancelled connection test closes only its own instance`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        val selectedUrl = transport.prepare(profile, "fixture-password")
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            transport.test(profile.copy(mesh = profile.mesh!!.copy(networkName = "other-fixture-network")), "fixture-password") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(2, engine.started.size)
        assertEquals(1, engine.stopped.size)
        assertEquals(selectedUrl, transport.address(profile))
        transport.disconnect()
        assertEquals(2, engine.stopped.size)
    }

    @Test fun `cancellation while native start returns still releases the instance`() = runTest {
        var stopped = 0
        lateinit var job: Job
        val engine = object : MeshEngine {
            override fun start(config: String) { job.cancel() }
            override fun stop(instance: String) { stopped++ }
        }
        val transport = MeshTransport(engine)
        job = launch(start = CoroutineStart.LAZY) {
            transport.test(profile, "fixture-password") { fail("Cancelled test must not send a request") }
        }
        job.start()
        job.join()
        assertEquals(1, stopped)
    }

    @Test fun `password-only changes cannot keep using the old native instance`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        val old = transport.prepare(profile, "fixture-password")
        val fresh = transport.prepare(profile, "updated-fixture-password")
        assertNotEquals(old, fresh)
        assertEquals(2, engine.started.size)
        assertEquals(1, engine.stopped.size)
    }

    @Test fun `testing the selected static address reuses it and changed settings cannot duplicate it`() = runTest {
        val engine = Engine()
        val transport = MeshTransport(engine)
        val fixed = profile.copy(mesh = profile.mesh!!.copy(ipv4 = "10.44.0.8"))
        val selected = transport.prepare(fixed, "fixture-password")
        transport.test(fixed, "fixture-password") { assertEquals(selected, it) }
        assertEquals(1, engine.started.size)
        assertTrue(engine.stopped.isEmpty())
        val failure = runCatching {
            transport.test(fixed.copy(baseUrl = "http://10.44.0.3:3080"), "fixture-password") { fail("Must not duplicate a static address") }
        }.exceptionOrNull()
        assertTrue(failure is MeshTestConflict)
        assertEquals(selected, transport.address(fixed))
    }

    @Test fun `configuration is userspace private and binds loopback only`() {
        val config = MeshConfig.render(profile, "fixture-password", "fixture-instance", 12345)
        assertTrue(config.contains("no_tun = true"))
        assertTrue(config.contains("use_smoltcp = true"))
        assertTrue(config.contains("private_mode = true"))
        assertTrue(config.contains("bind_addr = \"127.0.0.1:12345\""))
        assertTrue(config.contains("dst_addr = \"10.44.0.2:3080\""))
        assertFalse(config.contains("0.0.0.0"))
    }

    @Test fun `credential characters cannot inject TOML sections`() {
        val config = MeshConfig.render(profile, "fixture\"\n[flags]\nno_tun = false", "fixture-instance", 12345)
        assertEquals(1, config.lineSequence().count { it == "[flags]" })
        assertTrue(config.contains("network_secret = \"fixture\\\"\\n[flags]\\nno_tun = false\""))
    }

    @Test fun `invalid or credential-bearing destinations and peers are rejected`() {
        listOf("https://10.44.0.2:3080", "http://localhost:3080", "http://127.0.0.1:3080",
            "http://user:password@10.44.0.2:3080", "http://10.44.0.2:0", "http://10.44.0.2:3080/path").forEach {
            assertTrue(it, runCatching { MeshConfig.validate(profile.copy(baseUrl = it), "fixture-password") }.isFailure)
        }
        listOf("tcp://user:password@peer.example.test:11010", "tcp://peer.example.test", "tcp://peer.example.test:11010?q=x", "tcp://peer.example.test:11010\n[flags]").forEach {
            assertTrue(it, runCatching { MeshConfig.validate(profile.copy(mesh = profile.mesh!!.copy(peers = listOf(it))), "fixture-password") }.isFailure)
        }
    }
}
