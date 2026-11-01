package app.codeg.android.core.cache

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Disposable raw API snapshots. Each manifest publishes a complete set of immutable pages. */
class ConversationDiskCache(
    private val root: File,
    private val maxBytes: Long = 100L * 1024 * 1024,
    private val maxConversationBytes: Long = 10L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Scope internal constructor(val profile: String, val endpoint: String, internal val epoch: Long)
    class Ticket internal constructor(
        val scope: Scope, val group: String,
        internal val epoch: Long, internal val revision: Long,
    )
    data class Entry(val text: String, val savedAt: Long, val snapshot: String) {
        fun isFresh(at: Long = System.currentTimeMillis()): Boolean = savedAt > 0 && at - savedAt in 0 until 60_000
    }

    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val snapshot: String,
        val savedAt: Long,
        val files: Map<String, String>,
    )

    private val mutex = Mutex()
    private val profileEpochs = mutableMapOf<String, Long>()
    private val revisions = mutableMapOf<String, Long>()
    private var epoch = 0L

    /** Neither endpoint URLs nor credentials are used as directory/file names. */
    @Synchronized
    fun scope(profileId: String, baseUrl: String, token: String): Scope {
        val profile = digest(profileId)
        return Scope(profile, digest(baseUrl.trim().trimEnd('/') + "\u0000" + token), profileEpochs[profile] ?: 0L)
    }

    suspend fun ticket(scope: Scope, group: String): Ticket = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(group.matches(Regex("[a-z0-9-]+")))
            Ticket(scope, group, epoch, revisions[key(scope, group)] ?: 0L)
        }
    }

    suspend fun read(scope: Scope, group: String, page: String, snapshot: String? = null): Entry? = safe(null) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!validScope(scope)) return@withLock null
                val directory = directory(scope, group)
                val manifest = manifest(directory) ?: return@withLock null
                if (snapshot != null && snapshot != manifest.snapshot) return@withLock null
                val fileName = manifest.files[page] ?: return@withLock null
                if (!fileName.matches(Regex("[a-f0-9-]+\\.json"))) return@withLock null
                val file = File(directory, fileName)
                if (!file.isFile || file.length() > maxConversationBytes) return@withLock null
                val text = file.readText()
                File(directory, "manifest.json").setLastModified(now())
                Entry(text, manifest.savedAt, manifest.snapshot)
            }
        }
    }

    /** A new authoritative detail replaces all previous pages, even if structural hashes match. */
    suspend fun replace(ticket: Ticket, pages: Map<String, String>): String? = safe(null) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!valid(ticket)) return@withLock null
                val selected = linkedMapOf<String, String>()
                var bytes = 0L
                for ((name, text) in pages) {
                    val length = text.toByteArray(Charsets.UTF_8).size
                    if (bytes + length > maxConversationBytes) break
                    selected[name] = text
                    bytes += length
                }
                // An oversized new tail must not leave an old tail looking current.
                val directory = directory(ticket.scope, ticket.group)
                bump(ticket.scope, ticket.group)
                if (selected.isEmpty()) {
                    directory.deleteRecursively()
                    return@withLock null
                }
                val snapshot = UUID.randomUUID().toString()
                publish(directory, Manifest(snapshot = snapshot, savedAt = now(), files = emptyMap()), selected)
                prune()
                snapshot
            }
        }
    }

    /** History pages keep the tail's freshness time and snapshot identity. */
    suspend fun append(ticket: Ticket, page: String, text: String): Boolean = safe(false) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!valid(ticket)) return@withLock false
                val directory = directory(ticket.scope, ticket.group)
                val current = manifest(directory) ?: return@withLock false
                val retainedBytes = current.files.filterKeys { it != page }.values.sumOf { File(directory, it).length() }
                if (retainedBytes + text.toByteArray(Charsets.UTF_8).size > maxConversationBytes) return@withLock false
                publish(directory, current, mapOf(page to text))
                prune()
                true
            }
        }
    }

    suspend fun remove(scope: Scope, group: String, snapshot: String? = null): Unit = safe(Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (validScope(scope)) {
                    if (snapshot != null && manifest(directory(scope, group))?.snapshot != snapshot) return@withLock
                    bump(scope, group)
                    directory(scope, group).deleteRecursively()
                }
            }
        }
    }

    /** Keep readable history while forcing a server check after a new prompt. */
    suspend fun expire(scope: Scope, group: String): Unit = safe(Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!validScope(scope)) return@withLock
                expireSnapshot(scope, group)
            }
        }
    }

    suspend fun expire(ticket: Ticket): Unit = safe(Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (valid(ticket)) expireSnapshot(ticket.scope, ticket.group)
            }
        }
    }

    private fun expireSnapshot(scope: Scope, group: String) {
        bump(scope, group)
        val directory = directory(scope, group)
        val current = manifest(directory) ?: return
        publish(directory, current.copy(savedAt = 0), emptyMap())
    }

    suspend fun clearProfile(profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val profile = digest(profileId)
            synchronized(this@ConversationDiskCache) { profileEpochs[profile] = (profileEpochs[profile] ?: 0L) + 1L }
            if (!File(root, profile).deleteRecursively()) throw IOException("Could not clear server cache")
        }
    }

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            epoch++
            revisions.clear()
            if (!root.deleteRecursively()) throw IOException("Could not clear local cache")
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { diskBytes(root) } }

    private fun key(scope: Scope, group: String) = "${scope.profile}/${scope.endpoint}/$group"
    private fun directory(scope: Scope, group: String) = File(root, key(scope, group))
    @Synchronized
    private fun validScope(scope: Scope) = (profileEpochs[scope.profile] ?: 0L) == scope.epoch
    private fun valid(ticket: Ticket) = validScope(ticket.scope) && ticket.epoch == epoch &&
        ticket.revision == (revisions[key(ticket.scope, ticket.group)] ?: 0L)
    private fun bump(scope: Scope, group: String) {
        val key = key(scope, group)
        revisions[key] = (revisions[key] ?: 0L) + 1L
    }

    private fun manifest(directory: File): Manifest? = try {
        val file = File(directory, "manifest.json")
        if (!file.isFile || file.length() > 1024 * 1024) null
        else Json.decodeFromString<Manifest>(file.readText()).takeIf { it.version == 1 }
    } catch (_: Exception) { null }

    private fun publish(directory: File, previous: Manifest, pages: Map<String, String>) {
        check(directory.mkdirs() || directory.isDirectory)
        val files = previous.files.toMutableMap()
        for ((name, text) in pages) {
            val filename = "${UUID.randomUUID()}.json"
            File(directory, filename).writeText(text)
            files[name] = filename
        }
        val temp = File(directory, "manifest.tmp")
        temp.writeText(Json.encodeToString(previous.copy(files = files)))
        Files.move(temp.toPath(), File(directory, "manifest.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        File(directory, "manifest.json").setLastModified(now())
        val retained = files.values.toSet() + "manifest.json"
        directory.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
    }

    private fun prune() {
        val groups = root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            .flatMap { it.listFiles().orEmpty().toList() }
            .sortedBy { File(it, "manifest.json").lastModified() }
        var total = diskBytes(root)
        for (group in groups) {
            if (total <= maxBytes) break
            val bytes = diskBytes(group)
            // Eviction also fences a request that started before this snapshot disappeared.
            val relative = group.relativeTo(root).invariantSeparatorsPath
            revisions[relative] = (revisions[relative] ?: 0L) + 1L
            if (group.deleteRecursively()) total -= bytes
        }
    }

    private fun diskBytes(file: File): Long = if (file.exists()) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { fallback }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
