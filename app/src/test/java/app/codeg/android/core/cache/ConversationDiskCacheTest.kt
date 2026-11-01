package app.codeg.android.core.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationDiskCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `persisted snapshots can be read by a new cache instance`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val cache = ConversationDiskCache(root, now = { 12_000L })
        val scope = cache.scope("server", "https://example.test", "token")
        val snapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "{\"turns\":[]}"))
        assertNotNull(snapshot)

        val reopened = ConversationDiskCache(root)
        val restoredScope = reopened.scope("server", "https://example.test/", "token")
        val entry = reopened.read(restoredScope, "session-7", "tail")!!
        assertEquals("{\"turns\":[]}", entry.text)
        assertEquals(12_000L, entry.savedAt)
        assertEquals(snapshot, entry.snapshot)
        assertTrue(entry.isFresh(71_999L))
        assertFalse(entry.isFresh(72_000L))
    }

    @Test fun `profile endpoint and credentials isolate cached data`() = runBlocking {
        val cache = ConversationDiskCache(temporaryFolder.newFolder())
        val original = cache.scope("server-a", "https://first.test", "token-a")
        assertNotNull(cache.replace(cache.ticket(original, "session-7"), mapOf("tail" to "original")))
        val isolated = listOf(
            cache.scope("server-b", "https://first.test", "token-a"),
            cache.scope("server-a", "https://second.test", "token-a"),
            cache.scope("server-a", "https://first.test", "token-b"),
        )
        for ((index, scope) in isolated.withIndex()) {
            assertNull(cache.read(scope, "session-7", "tail"))
            assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "isolated-$index")))
        }
        assertEquals("original", cache.read(original, "session-7", "tail")?.text)
        isolated.forEachIndexed { index, scope ->
            assertEquals("isolated-$index", cache.read(scope, "session-7", "tail")?.text)
        }
    }

    @Test fun `clear all rejects pending writes while permitting a new request`() = runBlocking {
        val cache = ConversationDiskCache(temporaryFolder.newFolder())
        val scope = cache.scope("server", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "old")))
        val pending = cache.ticket(scope, "session-7")
        cache.clearAll()

        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.read(scope, "session-7", "tail"))
        assertNull(cache.replace(pending, mapOf("tail" to "stale")))
        assertFalse(cache.append(pending, "older-5", "stale history"))
        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "new")))
        assertEquals("new", cache.read(scope, "session-7", "tail")?.text)
    }

    @Test fun `clearing a profile fences its old scopes and preserves other servers`() = runBlocking {
        val cache = ConversationDiskCache(temporaryFolder.newFolder())
        val removed = cache.scope("server-a", "https://example.test", "token")
        val retained = cache.scope("server-b", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(removed, "session-7"), mapOf("tail" to "removed")))
        assertNotNull(cache.replace(cache.ticket(retained, "session-7"), mapOf("tail" to "retained")))
        val pending = cache.ticket(removed, "session-7")

        cache.clearProfile("server-a")
        assertNull(cache.replace(pending, mapOf("tail" to "stale")))
        assertNull(cache.replace(cache.ticket(removed, "session-7"), mapOf("tail" to "still stale")))
        assertEquals("retained", cache.read(retained, "session-7", "tail")?.text)

        val renewed = cache.scope("server-a", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(renewed, "session-7"), mapOf("tail" to "renewed")))
        assertNull(cache.read(removed, "session-7", "tail"))
        assertEquals("renewed", cache.read(renewed, "session-7", "tail")?.text)
    }

    @Test fun `authoritative replacement invalidates pending writes and previous history`() = runBlocking {
        var clock = 10_000L
        val cache = ConversationDiskCache(temporaryFolder.newFolder(), now = { clock })
        val scope = cache.scope("server", "https://example.test", "token")
        val oldSnapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "old tail"))!!
        assertTrue(cache.append(cache.ticket(scope, "session-7"), "older-5", "old history"))
        val pendingHistory = cache.ticket(scope, "session-7")
        clock = 20_000L
        val newSnapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "new tail"))!!

        assertNotEquals(oldSnapshot, newSnapshot)
        assertFalse(cache.append(pendingHistory, "older-0", "stale history"))
        assertNull(cache.replace(pendingHistory, mapOf("tail" to "stale tail")))
        assertNull(cache.read(scope, "session-7", "older-5"))
        assertNull(cache.read(scope, "session-7", "tail", oldSnapshot))
        assertEquals("new tail", cache.read(scope, "session-7", "tail", newSnapshot)?.text)
        cache.remove(scope, "session-7", snapshot = oldSnapshot)
        assertEquals("new tail", cache.read(scope, "session-7", "tail", newSnapshot)?.text)
        assertEquals(20_000L, cache.read(scope, "session-7", "tail")?.savedAt)
    }

    @Test fun `history append retains tail freshness and snapshot identity`() = runBlocking {
        var clock = 10_000L
        val cache = ConversationDiskCache(temporaryFolder.newFolder(), now = { clock })
        val scope = cache.scope("server", "https://example.test", "token")
        val snapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "tail"))!!
        clock = 50_000L
        assertTrue(cache.append(cache.ticket(scope, "session-7"), "older-5", "history"))

        for (page in listOf("tail", "older-5")) {
            val entry = cache.read(scope, "session-7", page, snapshot)!!
            assertEquals(10_000L, entry.savedAt)
            assertEquals(snapshot, entry.snapshot)
        }
        assertEquals("tail", cache.read(scope, "session-7", "tail")?.text)
        assertEquals("history", cache.read(scope, "session-7", "older-5")?.text)
    }

    @Test fun `corrupt manifests and missing page files are cache misses`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val cache = ConversationDiskCache(root)
        val scope = cache.scope("server", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "payload")))
        root.walkTopDown().single { it.name == "manifest.json" }.writeText("{broken")
        assertNull(cache.read(scope, "session-7", "tail"))

        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "recovered")))
        root.walkTopDown().single { it.isFile && it.name != "manifest.json" }.delete()
        assertNull(cache.read(scope, "session-7", "tail"))
    }

    @Test fun `conversation limit preserves newest page and rejects oversized new tail`() = runBlocking {
        val cache = ConversationDiskCache(temporaryFolder.newFolder(), maxConversationBytes = 10L)
        val scope = cache.scope("server", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), linkedMapOf(
            "tail" to "你好", // Six UTF-8 bytes.
            "older-5" to "你好",
        )))
        assertEquals("你好", cache.read(scope, "session-7", "tail")?.text)
        assertNull(cache.read(scope, "session-7", "older-5"))
        assertFalse(cache.append(cache.ticket(scope, "session-7"), "older-5", "你好"))
        assertTrue(cache.append(cache.ticket(scope, "session-7"), "older-5", "1234"))
        assertEquals("你好", cache.read(scope, "session-7", "tail")?.text)

        assertNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "界界界界")))
        assertNull(cache.read(scope, "session-7", "tail"))
        assertNull(cache.read(scope, "session-7", "older-5"))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun `response expiration fences stale tickets and invalidates pending snapshot writes`() = runBlocking {
        var clock = 100_000L
        val cache = ConversationDiskCache(temporaryFolder.newFolder(), now = { clock })
        val scope = cache.scope("server", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "old")))
        val beforeClear = cache.ticket(scope, "session-7")
        cache.clearAll()
        clock = 200_000L
        val snapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "new"))!!
        cache.expire(beforeClear)
        assertEquals(200_000L, cache.read(scope, "session-7", "tail", snapshot)?.savedAt)

        val beforeReplace = cache.ticket(scope, "session-7")
        clock = 300_000L
        val newestSnapshot = cache.replace(cache.ticket(scope, "session-7"), mapOf("tail" to "newest"))!!
        cache.expire(beforeReplace)
        assertEquals(300_000L, cache.read(scope, "session-7", "tail", newestSnapshot)?.savedAt)

        val valid = cache.ticket(scope, "session-7")
        val pendingHistory = cache.ticket(scope, "session-7")
        cache.expire(valid)
        val expired = cache.read(scope, "session-7", "tail", newestSnapshot)!!
        assertEquals("newest", expired.text)
        assertEquals(0L, expired.savedAt)
        assertFalse(expired.isFresh(clock))
        assertFalse(cache.append(pendingHistory, "older-5", "stale history"))
        assertNull(cache.replace(pendingHistory, mapOf("tail" to "stale tail")))
    }

    @Test fun `global budget evicts least recently accessed conversation including manifest overhead`() = runBlocking {
        var clock = 10_000L
        val payload = "x".repeat(1_000)
        // Measure one complete group so the budget includes manifests and immutable filenames.
        val probe = ConversationDiskCache(temporaryFolder.newFolder(), now = { clock })
        val probeScope = probe.scope("server", "https://example.test", "token")
        assertNotNull(probe.replace(probe.ticket(probeScope, "session-a"), mapOf("tail" to payload)))
        val oneGroupBytes = probe.sizeBytes()
        assertTrue(oneGroupBytes > payload.length)

        val budget = oneGroupBytes * 2 + 16
        val cache = ConversationDiskCache(temporaryFolder.newFolder(), maxBytes = budget, now = { clock })
        val scope = cache.scope("server", "https://example.test", "token")
        assertNotNull(cache.replace(cache.ticket(scope, "session-a"), mapOf("tail" to payload)))
        clock = 20_000L
        assertNotNull(cache.replace(cache.ticket(scope, "session-b"), mapOf("tail" to payload)))
        val pendingB = cache.ticket(scope, "session-b")
        clock = 30_000L
        assertNotNull(cache.read(scope, "session-a", "tail"))
        clock = 40_000L
        assertNotNull(cache.replace(cache.ticket(scope, "session-c"), mapOf("tail" to payload)))

        assertNull(cache.read(scope, "session-b", "tail"))
        assertNotNull(cache.read(scope, "session-a", "tail"))
        assertNotNull(cache.read(scope, "session-c", "tail"))
        assertTrue(cache.sizeBytes() <= budget)
        assertNull(cache.replace(pendingB, mapOf("tail" to "stale response")))
    }
}
