package app.codeg.android.feature.projects

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.model.GitBranchList
import app.codeg.android.core.model.GitCredentials
import app.codeg.android.core.model.GitHubAccount
import app.codeg.android.core.model.GitHubTokenValidation
import app.codeg.android.core.model.GitPullResult
import app.codeg.android.core.model.GitPushResult
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isAuthFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Loads one folder's identity + resolves the [CodegClient] used by the Files/Changes/Commits tabs. */
@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    private val repository: ServerRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val folderId: Int = savedStateHandle.get<Int>("folderId") ?: -1

    private val _ui = MutableStateFlow(ProjectDetailState(loading = true))
    val ui: StateFlow<ProjectDetailState> = _ui.asStateFlow()

    init { load() }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = repository.selectedProfile.first() ?: throw IllegalStateException("No server selected")
                val client = repository.client(profile) ?: throw IllegalStateException("Missing token for this server")
                val folders = client.listFolders()
                val folder = folders.firstOrNull { it.id == folderId }
                    ?: throw IllegalStateException("Folder not found")
                // The worktrees the Folders tab folds into this folder's row count here too, so the
                // two pages agree. Without the open set, this folder's own conversations still do.
                val worktrees = runCatching { client.listOpenFolders() }.getOrNull()
                    ?.let { FolderVisibility.childToParent(it) }
                    ?.filterValues { it == folderId }?.keys.orEmpty()
                val conversations = runCatching { client.listConversations(folderIds = listOf(folderId) + worktrees) }.getOrDefault(emptyList())
                // Prefer the live branch (the folder list often omits gitBranch); null for non-git dirs.
                val liveBranch = folder.gitBranch ?: runCatching { client.getGitBranch(folder.path) }.getOrNull()
                _ui.update {
                    it.copy(
                        loading = false,
                        folder = folder,
                        client = client,
                        branch = liveBranch,
                        runningCount = conversations.count { c -> c.status.isLive },
                        sessionCount = conversations.size,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage()) }
            }
        }
    }

    // region Git branches

    suspend fun loadBranches(): GitBranchList {
        val c = _ui.value.client ?: return GitBranchList()
        val path = _ui.value.folder?.path ?: return GitBranchList()
        return runCatching { c.gitListAllBranches(path) }.getOrDefault(GitBranchList())
    }

    /** Checkout [branch]; reports an error string or null on success, and refreshes the current branch. */
    fun checkout(branch: String, onResult: (String?) -> Unit) {
        val c = _ui.value.client ?: return
        val path = _ui.value.folder?.path ?: return
        viewModelScope.launch {
            runCatching { c.gitCheckout(path, branch); c.getGitBranch(path) }
                .onSuccess { resolved -> _ui.update { it.copy(branch = resolved ?: branch) }; onResult(null) }
                .onFailure { onResult(it.displayMessage()) }
        }
    }

    fun createBranch(name: String, onResult: (String?) -> Unit) {
        val c = _ui.value.client ?: return
        val path = _ui.value.folder?.path ?: return
        viewModelScope.launch {
            runCatching { c.gitNewBranch(path, name.trim()); c.getGitBranch(path) }
                .onSuccess { resolved -> _ui.update { it.copy(branch = resolved ?: name.trim()) }; onResult(null) }
                .onFailure { onResult(it.displayMessage()) }
        }
    }

    // endregion

    // region Git operations (commit / push / pull / fetch / working-tree)
    //
    // Port of the iOS FolderGitModel: the Changes/Commits tabs share this VM so
    // they show one busy/banner surface and refresh together (via [reloadToken])
    // after a mutation. Push/pull/fetch route through [withCredentialRetry] — first
    // the server's stored GitHub accounts, then a credential prompt on auth failure.

    private val client: CodegClient? get() = _ui.value.client
    private val repoPath: String? get() = _ui.value.folder?.path

    private var credentialDeferred: CompletableDeferred<GitCredentialOutcome?>? = null
    private var promptSeq = 0L

    /**
     * Commit [files] with [message]. Throws on failure so the commit sheet can show
     * an inline error and stay open; on success it sets the banner + signals a reload.
     * The server stages the listed files itself, so untracked paths commit directly.
     */
    suspend fun gitCommit(message: String, files: List<String>) {
        val c = client ?: throw IllegalStateException("No connection")
        val path = repoPath ?: throw IllegalStateException("No folder")
        val result = c.gitCommit(path, message, files, folderId = _ui.value.folder?.id)
        setBanner(GitBannerKind.SUCCESS, quantity(R.plurals.git_committed, result.committedFiles))
        didMutate()
    }

    fun push() = performGit(R.string.git_busy_pushing) { c, path ->
        pushBanner(withCredentialRetry { creds -> c.gitPush(path, credentials = creds, folderId = _ui.value.folder?.id) })
    }

    fun pull() = performGit(R.string.git_busy_pulling) { c, path ->
        pullBanner(withCredentialRetry { creds -> c.gitPull(path, credentials = creds) })
    }

    fun fetch() = performGit(R.string.git_busy_fetching) { c, path ->
        withCredentialRetry { creds -> c.gitFetch(path, credentials = creds) }
        GitBanner(GitBannerKind.SUCCESS, appContext.getString(R.string.git_fetched))
    }

    /** Discard a tracked file's changes (`git restore`). */
    fun discard(file: String, displayName: String) = performGit(R.string.git_busy_discarding) { c, path ->
        c.gitRollbackFile(path, file)
        GitBanner(GitBannerKind.SUCCESS, appContext.getString(R.string.git_discarded, displayName))
    }

    /** Stage an untracked/modified file so git starts tracking it. */
    fun stage(file: String, displayName: String) = performGit(R.string.git_busy_staging) { c, path ->
        c.gitAddFiles(path, listOf(file))
        GitBanner(GitBannerKind.SUCCESS, appContext.getString(R.string.git_staged, displayName))
    }

    /** Delete an untracked file from disk (no HEAD version to discard back to). */
    fun deleteUntracked(file: String, displayName: String) = performGit(R.string.git_busy_deleting) { c, path ->
        c.deleteFileTreeEntry(rootPath = path, path = file)
        GitBanner(GitBannerKind.WARNING, appContext.getString(R.string.git_deleted, displayName))
    }

    /** Dismiss the current result banner. */
    fun dismissBanner() = _ui.update { it.copy(gitBanner = null) }

    // Credential sheet plumbing (called by GitCredentialSheet)

    fun submitCredentials(outcome: GitCredentialOutcome) = resolveCredentials(outcome)

    fun cancelCredentials() = resolveCredentials(null)

    /** Teardown safety net: cancel only the prompt the sheet was actually showing. */
    fun cancelCredentialsIfShowing(id: Long) {
        if (_ui.value.credentialPrompt?.id == id) resolveCredentials(null)
    }

    private fun resolveCredentials(outcome: GitCredentialOutcome?) {
        _ui.update { it.copy(credentialPrompt = null) }
        credentialDeferred?.complete(outcome)
        credentialDeferred = null
    }

    /** Validate a GitHub token (for the credential sheet's GitHub mode). */
    suspend fun validateGithubToken(serverUrl: String, token: String): GitHubTokenValidation? =
        client?.validateGithubToken(serverUrl, token)

    /** Best-effort: persist validated GitHub credentials as an account for reuse. */
    suspend fun saveGithubAccount(serverUrl: String, host: String?, validation: GitHubTokenValidation, token: String) {
        val c = client ?: return
        runCatching {
            val existing = c.getGitHubAccounts()
            if (existing.any { it.username == validation.username && extractHost(it.serverUrl) == host }) return
            val account = GitHubAccount(
                id = UUID.randomUUID().toString(),
                serverUrl = serverUrl,
                username = validation.username ?: "unknown",
                isDefault = existing.isEmpty(),
                scopes = validation.scopes,
                avatarUrl = validation.avatarUrl,
                createdAt = Instant.now().toString(),
            )
            c.saveAccountToken(account.id, token)
            c.updateGithubAccounts(existing + account)
        }
    }

    // Internals

    private fun setBanner(kind: GitBannerKind, message: String) =
        _ui.update { it.copy(gitBanner = GitBanner(kind, message)) }

    private fun didMutate() = _ui.update { it.copy(reloadToken = it.reloadToken + 1) }

    private fun quantity(@PluralsRes id: Int, count: Int): String =
        appContext.resources.getQuantityString(id, count, count)

    /** Run a tab-level op with the shared busy + banner lifecycle; reload on success. */
    private fun performGit(@StringRes title: Int, body: suspend (CodegClient, String) -> GitBanner?) {
        val c = client ?: return
        val path = repoPath ?: return
        if (_ui.value.gitBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(gitBusy = true, gitBusyTitle = appContext.getString(title), gitBanner = null) }
            try {
                body(c, path)?.let { banner -> _ui.update { s -> s.copy(gitBanner = banner) } }
                didMutate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // On cancel, withCredentialRetry rethrows the auth error — surface it
                // calmly (a warning, no reload) rather than as a red failure.
                val kind = if (e.isAuthFailure) GitBannerKind.WARNING else GitBannerKind.ERROR
                _ui.update { it.copy(gitBanner = GitBanner(kind, e.displayMessage())) }
            } catch (e: Exception) {
                _ui.update { it.copy(gitBanner = GitBanner(GitBannerKind.ERROR, e.displayMessage())) }
            } finally {
                _ui.update { it.copy(gitBusy = false, gitBusyTitle = null) }
            }
        }
    }

    /**
     * Try [operation] with the server's stored credentials; on a git auth failure,
     * prompt (GitHub token or username/password by host) and retry until success or
     * the user cancels (cancel rethrows the auth error). Persists a generic account
     * on success when the prompt asked to save.
     */
    private suspend fun <T> withCredentialRetry(operation: suspend (GitCredentials?) -> T): T {
        try {
            return operation(null)
        } catch (first: ApiError) {
            if (!first.isAuthFailure) throw first
            val host = resolveRemoteHost()
            val github = host == "github.com"
            var outcome = requestCredentials(github, host, retry = false) ?: throw first
            while (true) {
                try {
                    val result = operation(outcome.credentials)
                    if (outcome.saveAfterSuccess) saveGenericAccount(host, outcome.credentials)
                    return result
                } catch (retry: ApiError) {
                    if (!retry.isAuthFailure) throw retry
                    outcome = requestCredentials(github, host, retry = true) ?: throw retry
                }
            }
        }
    }

    private suspend fun requestCredentials(github: Boolean, host: String?, retry: Boolean): GitCredentialOutcome? {
        val prompt = GitCredentialPrompt(id = ++promptSeq, github = github, host = host, isRetry = retry)
        val deferred = CompletableDeferred<GitCredentialOutcome?>()
        credentialDeferred = deferred
        _ui.update { it.copy(credentialPrompt = prompt) }
        return deferred.await()
    }

    /** The origin remote's host, to choose the prompt mode; null → generic. */
    private suspend fun resolveRemoteHost(): String? {
        val c = client ?: return null
        val path = repoPath ?: return null
        val remotes = runCatching { c.gitListRemotes(path) }.getOrNull() ?: return null
        val origin = remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull()
        return origin?.url?.let { extractHost(it) }
    }

    private suspend fun saveGenericAccount(host: String?, credentials: GitCredentials) {
        val c = client ?: return
        runCatching {
            val existing = c.getGitHubAccounts()
            if (existing.any { it.username == credentials.username && extractHost(it.serverUrl) == host }) return
            val account = GitHubAccount(
                id = UUID.randomUUID().toString(),
                serverUrl = host?.let { "https://$it" } ?: "https://unknown",
                username = credentials.username,
                isDefault = existing.isEmpty(),
                createdAt = Instant.now().toString(),
            )
            c.saveAccountToken(account.id, credentials.password)
            c.updateGithubAccounts(existing + account)
        }
    }

    private fun pushBanner(result: GitPushResult): GitBanner = when {
        result.pushedCommits == 0 -> GitBanner(GitBannerKind.SUCCESS, appContext.getString(R.string.git_push_up_to_date))
        result.upstreamSet -> GitBanner(GitBannerKind.SUCCESS, quantity(R.plurals.git_pushed_upstream, result.pushedCommits))
        else -> GitBanner(GitBannerKind.SUCCESS, quantity(R.plurals.git_pushed, result.pushedCommits))
    }

    private fun pullBanner(result: GitPullResult): GitBanner {
        val conflict = result.conflict
        return when {
            conflict != null && conflict.hasConflicts ->
                GitBanner(GitBannerKind.WARNING, quantity(R.plurals.git_pull_conflicts, conflict.conflictedFiles.size))
            result.updatedFiles == 0 -> GitBanner(GitBannerKind.SUCCESS, appContext.getString(R.string.git_pull_up_to_date))
            else -> GitBanner(GitBannerKind.SUCCESS, quantity(R.plurals.git_pulled, result.updatedFiles))
        }
    }

    /** Host from an https or ssh remote URL, lowercased; null if unrecognized. */
    private fun extractHost(url: String): String? {
        val trimmed = url.trim()
        Regex("^https?://(?:[^@/]+@)?([^/:]+)").find(trimmed)?.groupValues?.getOrNull(1)?.let {
            if (it.isNotEmpty()) return it.lowercase()
        }
        val at = trimmed.indexOf('@')
        if (at >= 0) {
            val rest = trimmed.substring(at + 1)
            val sep = rest.indexOfFirst { it == ':' || it == '/' }
            if (sep > 0) return rest.substring(0, sep).lowercase()
        }
        return null
    }

    // endregion
}

data class ProjectDetailState(
    val loading: Boolean = false,
    val folder: FolderDetail? = null,
    val client: CodegClient? = null,
    val branch: String? = null,
    val runningCount: Int = 0,
    val sessionCount: Int = 0,
    val error: String? = null,
    val gitBusy: Boolean = false,
    val gitBusyTitle: String? = null,
    val gitBanner: GitBanner? = null,
    val reloadToken: Int = 0,
    val credentialPrompt: GitCredentialPrompt? = null,
)

/** Severity of the inline git-operation banner. */
enum class GitBannerKind { SUCCESS, WARNING, ERROR }

/** Result of the most recent git operation, shown as a strip atop the active tab. */
data class GitBanner(val kind: GitBannerKind, val message: String)

/** A pending credential request driving the single [GitCredentialSheet]. */
data class GitCredentialPrompt(
    val id: Long,
    /** GitHub host → a token field; other hosts → username + password. */
    val github: Boolean,
    val host: String?,
    /** True when re-prompting after a failed retry (the sheet shows a hint). */
    val isRetry: Boolean,
)

/**
 * What the credential sheet hands back: the credentials to retry with, and (for
 * generic hosts) whether to persist them as an account on success. GitHub creds are
 * validated + saved inside the sheet, so [saveAfterSuccess] is false there.
 */
data class GitCredentialOutcome(
    val credentials: GitCredentials,
    val saveAfterSuccess: Boolean,
)
