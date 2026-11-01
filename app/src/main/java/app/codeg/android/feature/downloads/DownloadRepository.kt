package app.codeg.android.feature.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.displayMessage
import app.codeg.android.feature.sessiondetail.PreparedDownload
import app.codeg.android.feature.sessiondetail.isApkLink
import app.codeg.android.feature.sessiondetail.messageWebUrl
import app.codeg.android.feature.sessiondetail.safeDownloadFilename
import app.codeg.android.feature.sessiondetail.workspaceFileLink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-owned preparation; Android owns the transfer even while no screen is open. */
@Singleton
internal class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val servers: ServerRepository,
    private val store: DownloadStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { requireNotNull(context.getSystemService(DownloadManager::class.java)) }
    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records = _records.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            try {
                val saved = store.read().map { it.restored(context.getString(R.string.download_interrupted)) }
                _records.value = (_records.value + saved).distinctBy { it.key }.sortedByDescending { it.createdAt }
                ready.complete(Unit)
                while (true) {
                    _records.subscriptionCount.first { it > 0 }
                    refreshRecords(onlyActive = true)
                    delay(1000)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _error.value = e.displayMessage()
                ready.completeExceptionally(e)
            }
        }
    }

    fun start(profile: ServerProfile, conversationId: Int?, title: String, folderPath: String?, link: String): String {
        _records.value.activeDownload(profile, conversationId, link)?.let { return it.key }
        val record = DownloadRecord(
            key = UUID.randomUUID().toString(), link = link,
            filename = safeDownloadFilename(link.substringBefore('#').substringAfterLast('/')),
            serverId = profile.id, serverUrl = profile.baseUrl, serverName = profile.name,
            conversationId = conversationId, conversationTitle = title, folderPath = folderPath,
        )
        _records.value = listOf(record) + _records.value
        jobs[record.key] = scope.launch {
            try {
                ready.await()
                mutex.withLock {
                    store.put(record)
                }
                prepare(record.key)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail(record.key, e)
            } finally { jobs.remove(record.key) }
        }
        return record.key
    }

    fun retry(key: String) {
        if (jobs[key]?.isActive == true) return
        val record = _records.value.firstOrNull { it.key == key } ?: return
        if (record.busy || record.status == FileDownloadStatus.COMPLETE) return
        jobs[key] = scope.launch {
            try {
                record.id?.let { withContext(Dispatchers.IO) { manager.remove(it) } }
                update(key) { it.copy(id = null, status = FileDownloadStatus.PREPARING, bytes = 0, total = -1, error = null) }
                prepare(key)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail(key, e)
            } finally { jobs.remove(key) }
        }
    }

    /** Fetch the current server version, preserving the earlier downloaded file and history. */
    fun redownload(key: String): String? {
        val record = _records.value.firstOrNull { it.key == key } ?: return null
        return start(ServerProfile(record.serverId, record.serverName, record.serverUrl),
            record.conversationId, record.conversationTitle, record.folderPath, record.link)
    }

    private suspend fun prepare(key: String) {
        val record = requireNotNull(_records.value.firstOrNull { it.key == key })
        val prepared = messageWebUrl(record.link)?.let { web ->
            require(isApkLink(record.link)) { context.getString(R.string.download_invalid_link) }
            PreparedDownload(web.toASCIIString(), web.path.substringAfterLast('/'))
        } ?: run {
            val profile = servers.profiles.first().firstOrNull { record.belongsTo(it) }
                ?: error(context.getString(R.string.download_source_unavailable))
            val client = servers.client(profile) ?: error(context.getString(R.string.download_server_unavailable))
            val target = workspaceFileLink(record.link, listOfNotNull(record.folderPath), record.folderPath)
                ?: error(context.getString(R.string.download_outside_workspace))
            val ticket = client.workspaceDownloadTicket(target.rootPath, target.path)
            PreparedDownload(ticket.url, ticket.filename)
        }
        require(messageWebUrl(prepared.url) != null) { context.getString(R.string.download_invalid_link) }
        val filename = safeDownloadFilename(prepared.filename)
        update(key) { it.copy(filename = filename) }
        withContext(NonCancellable) {
            val id = withContext(Dispatchers.IO) {
                manager.enqueue(DownloadManager.Request(Uri.parse(prepared.url))
                    .setTitle(filename)
                    .setMimeType(downloadMimeType(filename))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Codeg/${uniqueFilename(filename)}"))
            }
            // Persist the system ID before leaving this block so navigation cannot orphan the record.
            update(key) { it.copy(id = id, status = FileDownloadStatus.QUEUED) }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try { ready.await(); refreshRecords(onlyActive = false); _error.value = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _error.value = e.displayMessage()
            } finally { _refreshing.value = false }
        }
    }

    private suspend fun refreshRecords(onlyActive: Boolean) {
        for (record in _records.value) {
            val id = record.id ?: continue
            if (onlyActive && !record.busy) continue
            try {
                val next = withContext(Dispatchers.IO) {
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { context.getString(R.string.download_missing) }
                        fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                        val status = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                            DownloadManager.STATUS_SUCCESSFUL -> FileDownloadStatus.COMPLETE
                            DownloadManager.STATUS_FAILED -> FileDownloadStatus.FAILED
                            DownloadManager.STATUS_PAUSED -> FileDownloadStatus.PAUSED
                            DownloadManager.STATUS_PENDING -> FileDownloadStatus.QUEUED
                            else -> FileDownloadStatus.DOWNLOADING
                        }
                        val error = if (status == FileDownloadStatus.FAILED) context.getString(
                            R.string.download_failed_reason, number(DownloadManager.COLUMN_REASON).toInt(),
                        ) else null
                        record.copy(status = status, bytes = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), error = error)
                    }
                }
                update(record.key) { current ->
                    if (current.id == id && current.status != FileDownloadStatus.CANCELED) next else current
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { update(record.key) { if (it.id == id) it.copy(status = FileDownloadStatus.FAILED, error = e.displayMessage()) else it } }
        }
    }

    fun cancel(key: String) = scope.launch {
        try {
            jobs[key]?.cancelAndJoin()
            val record = _records.value.firstOrNull { it.key == key } ?: return@launch
            if (!record.busy) return@launch
            record.id?.let { withContext(Dispatchers.IO) { manager.remove(it) } }
            update(key) { it.copy(id = null, status = FileDownloadStatus.CANCELED, error = null) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _error.value = e.displayMessage() }
    }

    /** Removing history never deletes an already downloaded file. */
    fun remove(key: String) = scope.launch {
        try {
            mutex.withLock {
                if (_records.value.firstOrNull { it.key == key }?.busy == true) return@withLock
                store.remove(key)
                _records.value = _records.value.filterNot { it.key == key }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _error.value = e.displayMessage() }
    }

    suspend fun contentUri(key: String): Uri {
        val record = _records.value.firstOrNull { it.key == key && it.status == FileDownloadStatus.COMPLETE }
            ?: error(context.getString(R.string.download_missing))
        return try {
            withContext(Dispatchers.IO) {
                val uri = manager.getUriForDownloadedFile(requireNotNull(record.id)) ?: error(context.getString(R.string.download_missing))
                context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error(context.getString(R.string.download_missing))
                uri
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            update(key) { if (it.id == record.id) it.copy(status = FileDownloadStatus.FAILED, error = context.getString(R.string.download_missing)) else it }
            throw e
        }
    }

    fun openExternal(key: String) = scope.launch {
        try {
            val record = _records.value.firstOrNull { it.key == key } ?: return@launch
            val uri = contentUri(key)
            if (record.apk && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                update(key) { it.copy(error = context.getString(R.string.download_allow_install)) }
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, downloadMimeType(record.filename))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                update(key) { it.copy(error = null) }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            try { update(key) { it.copy(error = context.getString(R.string.download_open_failed)) }
            } catch (writeError: CancellationException) { throw writeError
            } catch (writeError: Exception) { _error.value = writeError.displayMessage() }
        }
    }

    private suspend fun fail(key: String, error: Exception) {
        try { update(key) { it.copy(status = FileDownloadStatus.FAILED, error = error.displayMessage()) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _error.value = e.displayMessage() }
    }

    private suspend fun update(key: String, transform: (DownloadRecord) -> DownloadRecord) = mutex.withLock {
        val before = _records.value.firstOrNull { it.key == key } ?: return@withLock
        val after = transform(before)
        // Progress stays in memory; durable changes are written atomically by DataStore.
        _records.value = _records.value.map { if (it.key == key) after else it }
        if (before.copy(bytes = after.bytes, total = after.total) != after) store.put(after)
    }

    private fun uniqueFilename(filename: String): String {
        val suffix = UUID.randomUUID().toString().take(8)
        val dot = filename.lastIndexOf('.')
        return if (dot > 0) filename.substring(0, dot) + "-$suffix" + filename.substring(dot) else "$filename-$suffix"
    }
}

internal fun downloadMimeType(filename: String): String = if (filename.endsWith(".apk", ignoreCase = true)) {
    "application/vnd.android.package-archive"
} else MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase(Locale.ROOT))
    ?: "application/octet-stream"
