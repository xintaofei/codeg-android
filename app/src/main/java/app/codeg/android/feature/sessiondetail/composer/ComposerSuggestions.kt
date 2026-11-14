package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AcpAgentInfo
import app.codeg.android.core.model.AgentSkillItem
import app.codeg.android.core.model.AgentSkillsListResult
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.WorkspaceFileEntry
import app.codeg.android.core.model.WorkspaceSearchRequest
import app.codeg.android.feature.sessiondetail.orNull
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The composer's inline `/` (and Codex `$`) command/skill menu and `@` mention panel — ported
// from web `message-input.tsx` (the `/` menu) and `composer/suggestion/*` +
// `use-reference-search.ts` (the `@` panel, files and agents only), by way of iOS
// `ComposerSuggestionModel.swift`. The compose bar reports text + caret; this detects the
// trigger, gathers what the menu lists, and turns a pick into an edit.

/**
 * What the composer menus have already seen, kept for the life of the process so a cold session
 * still offers the commands an earlier one advertised.
 *
 * Keyed by server + agent + folder: an agent's commands and skills depend on the workspace it
 * runs in, and two servers can share a folder path. Main-thread only, like its owners.
 */
class ComposerCatalog {
    class SkillsEntry(
        var skills: List<AgentSkillItem>? = null,
        var loading: Boolean = false,
        /** Bumped per scan, so only the newest scan's answer lands. */
        var generation: Int = 0,
    )

    /** The last command list seen per `server|agent|folder`. */
    val commands = HashMap<String, List<AvailableCommandInfo>>()

    /** Bumped per key whenever a connection pushes a command list, so a snapshot or probe read
     *  already on its way cannot land over it. */
    val commandRevisions = HashMap<String, Int>()

    /** Disk skills per `server|agent|folder` (only for agents that don't advertise them). */
    val skills = HashMap<String, SkillsEntry>()

    /** `acp_list_agents` per server. */
    val agents = HashMap<String, List<AcpAgentInfo>>()

    companion object {
        val shared = ComposerCatalog()
    }
}

/** An agent the `@` panel offers (web `agentToSuggestion`). */
data class MentionAgent(val agentType: AgentType, val label: String, val detail: String?)

/** The `@` panel's tabs, in the web's built-in order: agent first (handing a subtask to another
 *  agent is what `@` is for), then files. */
enum class MentionTab { AGENT, FILE }

/** One selectable row in either menu. */
sealed interface ComposerSuggestionRow {
    val key: String

    /** [skill]: the agent marked the command as a skill. */
    data class Command(val command: AvailableCommandInfo, val skill: Boolean) : ComposerSuggestionRow {
        override val key: String get() = "cmd:${command.name}"
    }

    data class Skill(val skill: AgentSkillItem, val prefix: InvocationPrefix) : ComposerSuggestionRow {
        override val key: String get() = "skill:${skill.scope}:${skill.id}"
    }

    data class File(val entry: WorkspaceFileEntry) : ComposerSuggestionRow {
        override val key: String get() = "file:${entry.path}"
    }

    data class Agent(val agent: MentionAgent) : ComposerSuggestionRow {
        override val key: String get() = "agent:${agent.agentType.wire}"
    }
}

/** What the panel above the field shows right now. */
sealed interface ComposerPanelState {
    val rows: List<ComposerSuggestionRow>
    val highlighted: Int

    /**
     * [loading]: the agent's commands are still being fetched (a loading row, which keeps the
     * panel open while there is nothing to list). [skillColumn]: some command is a skill, so
     * every row keeps an icon column and the tokens line up.
     */
    data class Commands(
        override val rows: List<ComposerSuggestionRow>,
        val loading: Boolean,
        val skillColumn: Boolean,
        override val highlighted: Int,
    ) : ComposerPanelState

    /**
     * [stale]: the file rows answer an earlier query (narrowed to this one) while the search for
     * this one runs; they can be tapped, but Enter does not pick one.
     */
    data class Mention(
        val tab: MentionTab,
        override val rows: List<ComposerSuggestionRow>,
        val counts: Map<MentionTab, Int>,
        val loading: Boolean,
        val stale: Boolean,
        val truncated: Boolean,
        val hasWorkspace: Boolean,
        override val highlighted: Int,
    ) : ComposerPanelState
}

/** A hardware key the panel may take while the field has focus. */
enum class ComposerKey { UP, DOWN, ENTER, TAB, SHIFT_TAB, ESCAPE }

/** How a hardware key press in the field was handled. */
sealed interface ComposerKeyOutcome {
    /** Not the panel's key: let the field have it. */
    data object Ignored : ComposerKeyOutcome

    /** Consumed with nothing to change in the text. */
    data object Handled : ComposerKeyOutcome

    /** Consumed: replace the text and put the caret here. */
    data class Replace(val text: String, val caret: Int) : ComposerKeyOutcome
}

/** A pick applied to the field: the new text and where the caret goes. */
data class ComposerPick(val text: String, val caret: Int)

class ComposerSuggestions(
    private val scope: CoroutineScope,
    private val source: Source,
    private val catalog: ComposerCatalog = ComposerCatalog.shared,
    /** Where the fire-and-forget search cancel runs: it must outlive a cleared screen. */
    private val detachedScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** One consumer per screen; the generation strictly increases on it. */
    private val consumerId: String = UUID.randomUUID().toString(),
) {
    /** The server, agent and folder the composer sends to. Pulled (not pushed) so it can never
     *  lag a draft's agent/folder switch. */
    data class Context(val serverKey: String, val agent: AgentType, val workspaceRoot: String?)

    /** Where the lists come from. */
    interface Source {
        fun context(): Context

        /** The agent's commands from wherever the screen can read them (live connection,
         *  conversation snapshot, or — with [allowProbe] — a probe agent). Null when none had
         *  any. */
        suspend fun fetchCommands(allowProbe: Boolean): List<AvailableCommandInfo>?

        suspend fun runtimeSkills(agent: AgentType, workspaceRoot: String?): AgentSkillsListResult

        suspend fun agents(): List<AcpAgentInfo>

        suspend fun searchFiles(root: String, query: String, limit: Int, request: WorkspaceSearchRequest): List<WorkspaceFileEntry>

        suspend fun cancelFileSearch(request: WorkspaceSearchRequest)
    }

    private val _panel = MutableStateFlow<ComposerPanelState?>(null)

    /** What to draw above the field, or null when nothing is open. */
    val panel: StateFlow<ComposerPanelState?> = _panel.asStateFlow()

    // region Trigger state

    var trigger: ComposerTrigger? = null
        private set
    private var highlightedIndex = 0

    /** The tab the user picked, or null to follow the first non-empty one. */
    private var pinnedTab: MentionTab? = null
    private var lastText = ""
    private var lastCaret: Int? = null

    /** Closed (Escape, or a pick) on this exact text: stays closed until the text changes, so a
     *  caret move cannot reopen it. */
    private var closedOnText: String? = null

    // endregion

    /** The tokens the draft's mentions read as, and what each is sent as. */
    var references: List<ComposerReference> = emptyList()
        private set

    // region Commands / skills / agents

    /** Fetches in flight per catalog key — the `/` panel's loading row. */
    private val commandFetches = HashMap<String, Int>()

    /** Keys this screen already asked about, per mode (`true` = probe allowed), so a `/` that
     *  found nothing does not ask again on every keystroke. */
    private val commandsAsked = HashMap<Boolean, MutableSet<String>>()
    private val skillsScanned = HashSet<String>()

    /** The agents tab shows "Searching…" while set and no list has arrived. */
    private var agentsRequested = false

    // endregion

    // region File search

    private data class FileResult(val root: String, val query: String, val entries: List<WorkspaceFileEntry>)

    private var fileResult: FileResult? = null
    private var fileGeneration = 0L
    private var fileSearchJob: Job? = null
    private var fileQueryRequested: Pair<String, String>? = null

    /** The search the server may still be running — what closing cancels. */
    private var fileSearchOutstanding: WorkspaceSearchRequest? = null

    // endregion

    private fun context(): Context = source.context()

    /** Where the catalog files this context's commands and skills. */
    fun catalogKey(context: Context = context()): String =
        "${context.serverKey}|${context.agent.wire}|${context.workspaceRoot.orEmpty()}"

    // region Commands

    /** The agent's commands as this screen knows them. */
    val commands: List<AvailableCommandInfo> get() = catalog.commands[catalogKey()].orEmpty()

    val commandsLoading: Boolean get() = (commandFetches[catalogKey()] ?: 0) > 0

    /** A live `available_commands` event: the agent re-advertised, and the new list replaces
     *  the old one whole. */
    fun adoptLiveCommands(list: List<AvailableCommandInfo>) {
        adopt(list, catalogKey())
        publish()
    }

    /** Commands carried by a snapshot. An empty list is not taken as "none": a fresh connection
     *  reports nothing until its agent has advertised. */
    fun adoptSnapshotCommands(list: List<AvailableCommandInfo>?) {
        if (list.isNullOrEmpty()) return
        adopt(list, catalogKey())
        publish()
    }

    private fun adopt(list: List<AvailableCommandInfo>, key: String) {
        catalog.commands[key] = list
        catalog.commandRevisions[key] = (catalog.commandRevisions[key] ?: 0) + 1
    }

    /** The screen has its agent and folder: read what a live session already knows (never a
     *  probe), and warm the disk skills. */
    fun screenDidLoad() {
        startCommandFetch(catalogKey(), allowProbe = false)
        ensureSkills()
        publish()
    }

    /** The "+" sheet's list: the same one the inline menu shows, refreshed first (probing only
     *  when nothing is known yet). */
    suspend fun loadCommands(): List<AvailableCommandInfo> {
        val key = catalogKey()
        commandFetches[key] = (commandFetches[key] ?: 0) + 1
        runCommandFetch(key, allowProbe = commands.isEmpty(), revision = catalog.commandRevisions[key] ?: 0)
        return commands
    }

    private fun startCommandFetch(key: String, allowProbe: Boolean) {
        val asked = commandsAsked.getOrPut(allowProbe) { HashSet() }
        if (!asked.add(key)) return
        // Counted now, not when the job starts: the `/` that asked must see the loading row on
        // this very update. The revision is read now too — a push before the job runs is newer.
        commandFetches[key] = (commandFetches[key] ?: 0) + 1
        val revision = catalog.commandRevisions[key] ?: 0
        scope.launch { runCommandFetch(key, allowProbe, revision) }
    }

    /** The fetch itself; its caller has already counted it in [commandFetches] and read the
     *  key's push [revision] when it asked. */
    private suspend fun runCommandFetch(key: String, allowProbe: Boolean, revision: Int) {
        val list = try {
            orNull { source.fetchCommands(allowProbe) }
        } finally {
            val left = (commandFetches[key] ?: 1) - 1
            if (left <= 0) commandFetches.remove(key) else commandFetches[key] = left
        }
        // A live update (even an empty one) arrived while this was out: it is newer than
        // whatever the snapshot or probe read.
        if (!list.isNullOrEmpty() && (catalog.commandRevisions[key] ?: 0) == revision) {
            catalog.commands[key] = list
        }
        // The cheap read found nothing while a `/` is waiting on it: go on to the probe instead
        // of closing the menu under the user.
        val waiting = trigger as? ComposerTrigger.Command
        if (!allowProbe && waiting?.prefix == InvocationPrefix.SLASH && catalogKey() == key && commands.isEmpty()) {
            startCommandFetch(key, allowProbe = true)
        }
        publish()
    }

    // endregion

    // region Skills

    private fun ensureSkills() {
        val ctx = context()
        if (ComposerInvocations.diskSkillTrigger(ctx.agent) == null) return
        val key = catalogKey(ctx)
        // Once per screen: skills change on disk, and the list on screen stays up until the
        // rescan lands.
        if (!skillsScanned.add(key)) return
        val entry = catalog.skills.getOrPut(key) { ComposerCatalog.SkillsEntry() }
        entry.generation += 1
        entry.loading = true
        val generation = entry.generation
        scope.launch {
            val result = orNull { source.runtimeSkills(ctx.agent, ctx.workspaceRoot) }
            val current = catalog.skills[key]?.takeIf { it.generation == generation } ?: return@launch
            current.loading = false
            if (result != null) {
                current.skills = if (result.supported) result.skills else emptyList()
            } else {
                // A failed scan keeps the last list that loaded, and may be tried again.
                skillsScanned.remove(key)
            }
            publish()
        }
    }

    // endregion

    // region Agents

    private fun ensureAgents() {
        if (agentsRequested) return
        agentsRequested = true
        val server = context().serverKey
        scope.launch {
            val list = orNull { source.agents() }
            if (list == null) agentsRequested = false else catalog.agents[server] = list
            publish()
        }
    }

    /** Only enabled agents are mentionable (web `buildReferenceGroups`), in the server's order. */
    private val mentionAgents: List<MentionAgent>
        get() = catalog.agents[context().serverKey].orEmpty()
            .filter { it.enabled }
            .sortedBy { it.sortOrder }
            .map { agent ->
                MentionAgent(
                    agentType = agent.agentType,
                    label = agent.name.ifEmpty { agent.agentType.displayName },
                    detail = agent.description.ifEmpty { null },
                )
            }

    // endregion

    // region Text and caret

    /** Report the field's text and caret (null for a ranged selection). */
    fun update(text: String, caret: Int?, focused: Boolean) {
        if (closedOnText != null && closedOnText != text) closedOnText = null
        // A sent (or cleared) draft takes its mentions with it.
        if (text.isEmpty() && references.isNotEmpty()) references = emptyList()
        lastText = text
        lastCaret = caret
        setTrigger(if (focused && closedOnText == null && caret != null) detect(text, caret) else null)
        publish()
    }

    /** Escape (or the panel's own close): shut until the text changes. */
    fun dismiss() {
        closedOnText = lastText
        setTrigger(null)
        publish()
    }

    private fun detect(text: String, caret: Int): ComposerTrigger? {
        val found = ComposerTrigger.detect(text, caret, context().agent) ?: return null
        // The caret inside a mention picked earlier is not a new `@`.
        if (found is ComposerTrigger.Mention && isInsideReference(text, found.start, caret)) return null
        return found
    }

    private fun isInsideReference(text: String, start: Int, caret: Int): Boolean =
        references.any { ref ->
            val end = start + ref.token.length
            end <= text.length && caret <= end && text.startsWith(ref.token, start)
        }

    private fun setTrigger(next: ComposerTrigger?) {
        val previous = trigger
        if (next == previous) return
        trigger = next
        highlightedIndex = 0
        val wasMention = previous is ComposerTrigger.Mention
        when (next) {
            is ComposerTrigger.Command -> {
                if (wasMention) closeFileSearch()
                val ctx = context()
                if (next.prefix == InvocationPrefix.SLASH && commands.isEmpty() && !commandsLoading) {
                    startCommandFetch(catalogKey(ctx), allowProbe = true)
                }
                if (ComposerInvocations.diskSkillTrigger(ctx.agent) == next.prefix) ensureSkills()
            }
            is ComposerTrigger.Mention -> {
                if (!wasMention) pinnedTab = null
                ensureAgents()
                scheduleFileSearch(next.query)
            }
            null -> if (wasMention) closeFileSearch()
        }
    }

    // endregion

    // region Panel

    private fun publish() {
        _panel.value = when (val t = trigger) {
            is ComposerTrigger.Command -> commandPanel(t.prefix, t.query)
            is ComposerTrigger.Mention -> mentionPanel(t.query)
            null -> null
        }
    }

    private fun clamped(count: Int): Int = minOf(highlightedIndex, maxOf(0, count - 1))

    /** Web `filteredSlashCommands` + `filteredSlashSkills`: commands under `/` only, then the
     *  disk skills under the trigger their agent uses. */
    private fun commandPanel(prefix: InvocationPrefix, query: String): ComposerPanelState? {
        val ctx = context()
        val skillTrigger = ComposerInvocations.diskSkillTrigger(ctx.agent)
        val commands = this.commands
        val loading = commandsLoading
        // A commands-only `/` with nothing to list closes — unless the list is still on its
        // way. A trigger that also lists disk skills stays armed: the panel fills in when the
        // scan lands.
        if (prefix == InvocationPrefix.SLASH && skillTrigger != InvocationPrefix.SLASH && commands.isEmpty() && !loading) return null
        val commandRows = if (prefix == InvocationPrefix.SLASH) FuzzyTextMatch.rank(query, commands, { it.name }) else emptyList()
        val skills = catalog.skills[catalogKey(ctx)]?.skills
        val skillRows = if (skillTrigger == prefix && !skills.isNullOrEmpty()) {
            FuzzyTextMatch.rank(
                query,
                ComposerInvocations.skillsForMenu(skills, commands, prefix),
                primary = { it.name },
                secondary = { it.id },
            )
        } else {
            emptyList()
        }
        val marked = ComposerInvocations.skillMarkedCommands(commands)
        val rows = commandRows.map { ComposerSuggestionRow.Command(it, skill = it.name in marked) } +
            skillRows.map { ComposerSuggestionRow.Skill(it, prefix) }
        val showLoading = prefix == InvocationPrefix.SLASH && loading && commands.isEmpty()
        if (rows.isEmpty() && !showLoading) return null
        return ComposerPanelState.Commands(rows, showLoading, skillColumn = marked.isNotEmpty(), highlighted = clamped(rows.size))
    }

    private fun mentionPanel(query: String): ComposerPanelState {
        val q = JsWhitespace.trim(query).lowercase()
        val agentRows = mentionAgents
            .filter { agent ->
                q.isEmpty() ||
                    agent.label.lowercase().contains(q) ||
                    agent.agentType.wire.lowercase().contains(q) ||
                    agent.detail?.lowercase()?.contains(q) == true
            }
            .take(MAX_PER_GROUP)
            .map { ComposerSuggestionRow.Agent(it) }

        val root = context().workspaceRoot
        val hasWorkspace = !root.isNullOrEmpty()
        val fileRows = ArrayList<ComposerSuggestionRow>()
        var truncated = false
        var fresh = false
        val result = fileResult
        if (hasWorkspace && result != null && result.root == root) {
            fresh = result.query == query
            // The panel's substring semantics over what the server ranked (web
            // `buildReferenceGroups`); an earlier query's rows narrow to this one while its own
            // search runs.
            for (entry in result.entries) {
                if (q.isNotEmpty() && !entry.displayName.lowercase().contains(q) && !entry.path.lowercase().contains(q)) continue
                if (fileRows.size >= MAX_PER_GROUP) {
                    truncated = true
                    break
                }
                fileRows.add(ComposerSuggestionRow.File(entry))
            }
        }
        val filesLoading = hasWorkspace && !fresh
        val agentsLoading = agentsRequested && catalog.agents[context().serverKey] == null
        val counts = mapOf(MentionTab.AGENT to agentRows.size, MentionTab.FILE to fileRows.size)
        // Follow the first non-empty tab until the user picks one; with both empty, the files
        // tab says it is still searching.
        val firstNonEmpty = MentionTab.entries.firstOrNull { (counts[it] ?: 0) > 0 }
        val tab = pinnedTab ?: firstNonEmpty ?: if (hasWorkspace) MentionTab.FILE else MentionTab.AGENT
        val rows = if (tab == MentionTab.AGENT) agentRows else fileRows
        return ComposerPanelState.Mention(
            tab = tab,
            rows = rows,
            counts = counts,
            loading = if (tab == MentionTab.FILE) filesLoading else agentsLoading,
            stale = tab == MentionTab.FILE && !fresh,
            truncated = tab == MentionTab.FILE && truncated,
            hasWorkspace = hasWorkspace,
            highlighted = clamped(rows.size),
        )
    }

    /** Switch the `@` panel's tab (and keep it, whatever the query matches). */
    fun selectTab(tab: MentionTab) {
        pinnedTab = tab
        highlightedIndex = 0
        publish()
    }

    // endregion

    // region Hardware keys

    /**
     * A hardware key while the field has focus; [text] is the field's text right now. While an
     * input method is [composing], every key is its own.
     */
    fun handleKey(key: ComposerKey, text: String, composing: Boolean = false): ComposerKeyOutcome {
        val panel = _panel.value ?: return ComposerKeyOutcome.Ignored
        if (composing) return ComposerKeyOutcome.Ignored
        if (key == ComposerKey.ESCAPE) {
            dismiss()
            return ComposerKeyOutcome.Handled
        }
        val rows = panel.rows
        return when (panel) {
            is ComposerPanelState.Commands -> {
                if (rows.isEmpty()) {
                    // The loading row owns the navigation keys, inserting nothing.
                    return ComposerKeyOutcome.Handled
                }
                when (key) {
                    ComposerKey.DOWN -> moveHighlight(panel.highlighted + 1, rows.size)
                    ComposerKey.UP -> moveHighlight(panel.highlighted - 1, rows.size)
                    ComposerKey.ENTER, ComposerKey.TAB ->
                        pick(rows[panel.highlighted], text)?.let { ComposerKeyOutcome.Replace(it.text, it.caret) }
                            ?: ComposerKeyOutcome.Handled
                    else -> ComposerKeyOutcome.Ignored
                }
            }
            is ComposerPanelState.Mention -> when (key) {
                ComposerKey.DOWN -> if (rows.isEmpty()) ComposerKeyOutcome.Handled else moveHighlight(panel.highlighted + 1, rows.size)
                ComposerKey.UP -> if (rows.isEmpty()) ComposerKeyOutcome.Handled else moveHighlight(panel.highlighted - 1, rows.size)
                ComposerKey.TAB, ComposerKey.SHIFT_TAB -> {
                    val tabs = MentionTab.entries
                    val step = if (key == ComposerKey.SHIFT_TAB) -1 else 1
                    selectTab(tabs[(tabs.indexOf(panel.tab) + step + tabs.size) % tabs.size])
                    ComposerKeyOutcome.Handled
                }
                // No fresh row: decline, so the key is the field's newline.
                ComposerKey.ENTER -> if (rows.isEmpty() || panel.stale) {
                    ComposerKeyOutcome.Ignored
                } else {
                    pick(rows[panel.highlighted], text)?.let { ComposerKeyOutcome.Replace(it.text, it.caret) }
                        ?: ComposerKeyOutcome.Ignored
                }
                else -> ComposerKeyOutcome.Ignored
            }
        }
    }

    private fun moveHighlight(to: Int, count: Int): ComposerKeyOutcome {
        highlightedIndex = (to + count) % count
        publish()
        return ComposerKeyOutcome.Handled
    }

    // endregion

    // region Picking

    /**
     * Apply a picked [row] to [text] (the field's text right now): the new text and where the
     * caret goes. Closes the panel until the text changes again.
     */
    fun pick(row: ComposerSuggestionRow, text: String): ComposerPick? {
        if (text != lastText) {
            // The field moved on since the last report; read the trigger off what it holds
            // now, with the caret clamped into it.
            lastText = text
            lastCaret = lastCaret?.let { minOf(it, text.length) }
        }
        val caret = lastCaret ?: return null
        val found = detect(lastText, caret) ?: return null
        val edit = when {
            row is ComposerSuggestionRow.Command && found is ComposerTrigger.Command ->
                invocationEdit(ComposerInvocations.commandInvocationToken(row.command.name), found)
            row is ComposerSuggestionRow.Skill && found is ComposerTrigger.Command ->
                invocationEdit(row.prefix.text + row.skill.id, found)
            row is ComposerSuggestionRow.File && found is ComposerTrigger.Mention -> {
                val root = context().workspaceRoot?.takeIf { it.isNotEmpty() } ?: return null
                val reference = ComposerReference.file(row.entry.displayName, row.entry.path, root)
                register(reference)
                ComposerEdit(found.start, found.end, reference.token + " ")
            }
            row is ComposerSuggestionRow.Agent && found is ComposerTrigger.Mention -> {
                val reference = ComposerReference.agent(row.agent.agentType, row.agent.label)
                register(reference)
                ComposerEdit(found.start, found.end, reference.token + " ")
            }
            else -> return null
        }
        val next = edit.apply(lastText) ?: return null
        closedOnText = next
        lastText = next
        lastCaret = edit.caretAfter
        setTrigger(null)
        publish()
        return ComposerPick(next, edit.caretAfter)
    }

    /** Web `replaceTriggerWithReference`: the `/…`·`$…` token before the caret becomes the
     *  invocation, plus a space unless whitespace already follows. */
    private fun invocationEdit(token: String, trigger: ComposerTrigger): ComposerEdit {
        val followedBySpace = trigger.end < lastText.length && JsWhitespace.contains(lastText[trigger.end])
        return ComposerEdit(trigger.start, trigger.end, token + if (followedBySpace) "" else " ")
    }

    // endregion

    // region Mentions on the wire

    private fun register(reference: ComposerReference) {
        references = references.filterNot { it.token == reference.token } + reference
    }

    /** The draft as it is sent: each mention token as its Markdown link. */
    fun encode(text: String): String = ComposerReferenceCodec.encode(text, references)

    /** A sent message coming back to the composer: its links become tokens again (and are
     *  registered), so re-sending reproduces the original. */
    fun restore(wire: String): String {
        val decoded = ComposerReferenceCodec.decode(wire, context().workspaceRoot)
        decoded.references.forEach(::register)
        return decoded.text
    }

    // endregion

    // region File search

    private fun scheduleFileSearch(query: String) {
        val root = context().workspaceRoot?.takeIf { it.isNotEmpty() } ?: return
        if (fileQueryRequested == root to query) return
        fileQueryRequested = root to query
        // Whatever was pending answers another query now — `@ab` backspaced to `@a` must not let
        // the `ab` search land over `a`'s results.
        fileSearchJob?.cancel()
        fileSearchJob = null
        fileResult?.let { if (it.root == root && it.query == query) return }
        if (query.toByteArray(Charsets.UTF_8).size > MAX_QUERY_BYTES) {
            fileResult = FileResult(root, query, emptyList())
            return
        }
        fileSearchJob = scope.launch {
            delay(FILE_DEBOUNCE_MS)
            fileGeneration += 1
            val request = WorkspaceSearchRequest(consumerId, fileGeneration)
            fileSearchOutstanding = request
            // A failed search reads as no matches (web: `.catch(() => [])`). Cancelled here is
            // only the local wait: the server may still be running it, so it stays outstanding
            // for [closeFileSearch].
            val entries = orNull { source.searchFiles(root, query, MAX_PER_GROUP + 1, request) }.orEmpty()
            if (fileSearchOutstanding == request) fileSearchOutstanding = null
            if (request.generation != fileGeneration || fileQueryRequested != root to query) return@launch
            fileResult = FileResult(root, query, entries)
            publish()
        }
    }

    /** The panel closed: stop waiting, and tell the server to stop too. Only here — a newer
     *  query already supersedes an older one server-side. */
    private fun closeFileSearch() {
        fileSearchJob?.cancel()
        fileSearchJob = null
        fileQueryRequested = null
        fileResult = null
        val outstanding = fileSearchOutstanding ?: return
        fileSearchOutstanding = null
        detachedScope.launch { orNull { source.cancelFileSearch(outstanding) } }
    }

    /** The screen went away. */
    fun teardown() {
        setTrigger(null)
        closeFileSearch()
        publish()
    }

    // endregion

    companion object {
        /** Rows per group (web `MAX_PER_GROUP`); the server is asked for one more. */
        const val MAX_PER_GROUP = 50
        private const val MAX_QUERY_BYTES = 2048
        private const val FILE_DEBOUNCE_MS = 150L
    }
}
