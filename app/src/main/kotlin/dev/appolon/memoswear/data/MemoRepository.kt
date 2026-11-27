package dev.appolon.memoswear.data

import dev.appolon.memoswear.core.UserMessages
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.data.api.ApiException
import dev.appolon.memoswear.data.api.AppJson
import dev.appolon.memoswear.data.api.MemoPatch
import dev.appolon.memoswear.data.api.MemosApi
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.MemoState
import dev.appolon.memoswear.data.model.Visibility
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The memos on the watch: a local copy of the newest notes, the changes waiting to be sent, and
 * the work of keeping both in step with the server.
 *
 * Reads never wait for the network. Writes are applied at once to the local copy and queued in
 * the outbox; [syncPending] sends the queue in order and then fetches the newest page.
 */
class MemoRepository(
    private val session: SessionStore,
    private val settings: AccountStore,
    cacheFile: File,
    outboxFile: File,
    private val scope: CoroutineScope,
    /** Sends the queued changes soon. The app decides how: at once, in the background, or both. */
    private val requestSync: () -> Unit,
    private val requestTiles: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val memo: Memo, val pending: Boolean, val failed: Boolean)

    data class ListState(
        val entries: List<Entry> = emptyList(),
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val offline: Boolean = false,
        val error: String? = null,
        /** True once a page has been loaded from the server in this process. */
        val loaded: Boolean = false,
        val endReached: Boolean = false,
        val pendingCount: Int = 0,
        val failedCount: Int = 0,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    data class SearchResult(val entries: List<Entry>, val offline: Boolean)

    data class TagCount(val tag: String, val count: Int)

    enum class SyncResult { SUCCESS, RETRY, AUTH_FAILED }

    private data class NetState(
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val offline: Boolean = false,
        val error: String? = null,
        val loaded: Boolean = false,
        val endReached: Boolean = false,
    )

    private val cache = JsonListStore(cacheFile, AppJson, Memo.serializer())
    private val outbox = JsonListStore(outboxFile, AppJson, PendingChange.serializer())
    private val net = MutableStateFlow(NetState())
    private val actionMutex = Mutex()
    private val syncMutex = Mutex()
    private val flushedNames = ConcurrentHashMap<String, String>()

    @Volatile private var nextPageToken: String? = null
    @Volatile private var lastRefreshAt: Long = 0
    @Volatile private var seededVisibility: Boolean = false
    @Volatile private var defaultVisibility: Visibility = Visibility.PRIVATE

    init {
        scope.launch { settings.defaultVisibility.collect { defaultVisibility = it } }
    }

    /** The newest memos that are not archived, pins first. */
    val home: StateFlow<ListState> = listState(archived = false)

    /** The user's archived memos. */
    val archived: StateFlow<ListState> = listState(archived = true)

    private fun listState(archived: Boolean): StateFlow<ListState> =
        combine(cache.flow, outbox.flow, net) { memos, changes, state ->
            project(memos, changes, state, archived)
        }.stateIn(scope, SharingStarted.Eagerly, ListState())

    // ---- Reading --------------------------------------------------------------------------------

    /** One memo by name, following a queued create once the server has given it a real name. */
    fun memoFlow(name: String): Flow<Memo?> = combine(cache.flow, outbox.flow) { memos, changes ->
        resolve(name, memos, changes)
    }

    suspend fun memo(name: String): Memo? = resolve(name, cache.value, outbox.value)

    /** Whether a memo is one the signed-in user can change. */
    fun canEdit(memo: Memo): Boolean = memo.isLocal || session.user?.name == memo.creator || session.user?.role == "ADMIN"

    /** The newest notes for tiles, read from the local copy only. */
    fun recentEntries(limit: Int): List<Entry> = home.value.entries.filter { !it.failed }.take(limit)

    /** The first pinned note, or else the newest one. Null when there are none. */
    fun pinnedEntry(): Entry? = home.value.entries.firstOrNull { it.memo.pinned && !it.failed } ?: home.value.entries.firstOrNull { !it.failed }

    fun hasSendableChanges(): Boolean = outbox.value.any { !it.failed }

    suspend fun refresh(force: Boolean = false) {
        val api = session.api ?: return
        if (!force && net.value.loaded && now() - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return
        if (!actionMutex.tryLock()) return
        try {
            net.value = net.value.copy(refreshing = true, error = null)
            try {
                val page = api.listMemos(filter = MemosApi.TOP_LEVEL, orderBy = MemosApi.HOME_ORDER, pageSize = MemosApi.PAGE_SIZE)
                // The page holds active notes only, so archived ones saved earlier stay as they are.
                cache.update { saved -> mergeByNewest(saved.filter { it.isArchived }, page.memos) }
                nextPageToken = page.nextPageToken.takeIf { it.isNotBlank() }
                lastRefreshAt = now()
                net.value = net.value.copy(refreshing = false, offline = false, loaded = true, endReached = nextPageToken == null)
                seedVisibility(api)
                requestTiles()
            } catch (e: ApiException) {
                net.value = net.value.copy(refreshing = false, error = UserMessages.forApi(e))
                if (e.isUnauthenticated) session.onUnauthenticated()
            } catch (e: IOException) {
                net.value = net.value.copy(refreshing = false, offline = true)
            }
        } finally {
            actionMutex.unlock()
        }
    }

    /**
     * Updates the list when the person returns to it, such as after swiping back from a detail
     * screen. A successful send already refreshes, so avoid a duplicate request in that case.
     */
    suspend fun resyncOnResume() {
        val hasSendableChanges = outbox.value.any { !it.failed }
        syncPending()
        if (!hasSendableChanges) refresh(force = true)
    }

    /** Takes the default visibility for new notes from the web client's setting, the first time. */
    private suspend fun seedVisibility(api: MemosApi) {
        if (seededVisibility) return
        seededVisibility = true
        if (settings.defaultVisibilityIsSet()) return
        runCatching { api.userGeneral() }.getOrNull()?.let { settings.setDefaultVisibility(it.defaultVisibility) }
    }

    suspend fun loadMore() {
        val api = session.api ?: return
        val token = nextPageToken ?: return
        if (!actionMutex.tryLock()) return
        try {
            net.value = net.value.copy(loadingMore = true)
            try {
                val page = api.listMemos(pageToken = token, filter = MemosApi.TOP_LEVEL, orderBy = MemosApi.HOME_ORDER, pageSize = MemosApi.PAGE_SIZE)
                cache.update { current -> mergeByNewest(current, page.memos) }
                nextPageToken = page.nextPageToken.takeIf { it.isNotBlank() }
                net.value = net.value.copy(loadingMore = false, offline = false, endReached = nextPageToken == null)
            } catch (e: ApiException) {
                net.value = net.value.copy(loadingMore = false, error = UserMessages.forApi(e))
                if (e.isUnauthenticated) session.onUnauthenticated()
            } catch (e: IOException) {
                net.value = net.value.copy(loadingMore = false, offline = true)
            }
        } finally {
            actionMutex.unlock()
        }
    }

    suspend fun archivedMemos(): List<Entry> {
        val api = session.api
        if (api != null) {
            try {
                val page = api.listMemos(state = MemoState.ARCHIVED, orderBy = "create_time desc", pageSize = MemosApi.PAGE_SIZE)
                cache.update { current -> mergeByNewest(current, page.memos) }
            } catch (e: ApiException) {
                if (e.isUnauthenticated) session.onUnauthenticated()
            } catch (e: IOException) {
                // Show what was saved earlier.
            }
        }
        return archived.value.entries
    }

    suspend fun search(query: String, tag: String?): SearchResult {
        val text = query.trim()
        val tagName = tag?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
        if (text.isEmpty() && tagName == null) return SearchResult(emptyList(), offline = false)

        val api = session.api
        val filter = if (tagName != null) MemosApi.tagFilter(tagName) else MemosApi.contentFilter(text)
        if (api != null) {
            try {
                val page = api.listMemos(filter = filter, orderBy = MemosApi.HOME_ORDER, pageSize = SEARCH_PAGE_SIZE)
                return SearchResult(project(page.memos, outbox.value, net.value, archived = false).entries, offline = false)
            } catch (e: ApiException) {
                if (e.isUnauthenticated) session.onUnauthenticated()
                // Fall through to the local copy, which is better than nothing.
            } catch (e: IOException) {
                // Offline.
            }
        }
        val local = home.value.entries.filter { entry ->
            val memo = entry.memo
            (tagName == null || memo.tags.any { it == tagName || it.startsWith("$tagName/") }) &&
                (text.isEmpty() || memo.content.contains(text, ignoreCase = true))
        }
        return SearchResult(local, offline = true)
    }

    /** Tags with how many memos carry them, most used first. */
    suspend fun tags(): List<TagCount> {
        val api = session.api
        val counts = try {
            api?.tagStats()?.counts
        } catch (e: IOException) {
            null
        }
        val source = counts ?: home.value.entries
            .flatMap { it.memo.tags }
            .groupingBy { it }
            .eachCount()
        return source.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { TagCount(it.key, it.value) }
    }

    /** Comments on a memo, oldest first, including ones not yet sent. */
    suspend fun comments(parent: String): List<Entry> {
        val api = session.api
        val remote = try {
            api?.listMemos(filter = MemosApi.commentsFilter(parent), orderBy = "create_time asc", pageSize = MemosApi.PAGE_SIZE)?.memos.orEmpty()
        } catch (e: IOException) {
            if (e is ApiException && e.isUnauthenticated) session.onUnauthenticated()
            cache.value.filter { it.parent == parent }
        }
        val local = outbox.value.filterIsInstance<PendingChange.Create>().filter { it.parent == parent }
        return remote.map { Entry(it, pending = false, failed = false) } +
            local.map { Entry(it.toMemo(), pending = it.error == null, failed = it.error != null) }
    }

    // ---- Writing --------------------------------------------------------------------------------

    /** Creates a note and returns its name. It appears at once; the server gets it when online. */
    suspend fun createMemo(content: String, parent: String? = null): String {
        val text = content.trim()
        if (text.isEmpty()) return ""
        val visibility = if (parent != null) Visibility.PRIVATE else defaultVisibility
        val change = PendingChange.Create(
            localId = PendingChange.newId(),
            content = text,
            visibility = visibility,
            parent = parent,
            createdAt = now(),
            owner = session.user?.name.orEmpty(),
        )
        outbox.update { PendingChange.merge(it, change) }
        requestSync()
        return change.localName
    }

    suspend fun updateContent(name: String, content: String) = enqueue(name) { it.copy(content = content.trim()) }

    suspend fun setPinned(name: String, pinned: Boolean) = enqueue(name) { it.copy(pinned = pinned) }

    suspend fun setArchived(name: String, archived: Boolean) =
        enqueue(name) { it.copy(state = if (archived) MemoState.ARCHIVED else MemoState.NORMAL) }

    suspend fun setVisibility(name: String, visibility: Visibility) = enqueue(name) { it.copy(visibility = visibility) }

    suspend fun deleteMemo(name: String) {
        if (name.isBlank()) return
        val change = PendingChange.Delete(PendingChange.newId(), name, now(), owner = session.user?.name.orEmpty())
        outbox.update { PendingChange.merge(it, change) }
        requestSync()
    }

    suspend fun toggleTask(name: String, line: Int, checked: Boolean) {
        val content = memo(name)?.content ?: return
        updateContent(name, MemoMarkdown.setTaskState(content, line, checked))
    }

    /** Drops the changes the server refused. The notes go back to how the server has them, and a
     *  refused new note disappears. */
    suspend fun discardFailed() {
        outbox.update { changes -> changes.filter { it.error == null } }
    }

    /** Retries the changes the server refused earlier, for example after the user edited them. */
    suspend fun retryFailed() {
        outbox.update { changes -> changes.map { it.markFailed(null) } }
        requestSync()
    }

    suspend fun clear() {
        cache.replace(emptyList())
        outbox.replace(emptyList())
        flushedNames.clear()
        nextPageToken = null
        lastRefreshAt = 0
        seededVisibility = false
        net.value = NetState()
    }

    /** Queues a field change on a memo. If the memo has an earlier change waiting, the two are
     *  combined so the server sees the end result. */
    private suspend fun enqueue(name: String, change: (PendingChange.Update) -> PendingChange.Update) {
        if (name.isBlank()) return
        val update = PendingChange.Update(
            changeId = PendingChange.newId(),
            memoName = name,
            createdAt = now(),
            owner = session.user?.name.orEmpty(),
        )
        outbox.update { PendingChange.merge(it, change(update)) }
        requestSync()
    }

    // ---- Syncing --------------------------------------------------------------------------------

    /** Waits for a saved session to be checked, for callers that start with the process. */
    suspend fun awaitSession(): Boolean = withTimeoutOrNull(SESSION_RESTORE_TIMEOUT_MS) {
        session.state.first { it !is SessionStore.State.Restoring }
        session.isSignedIn
    } ?: false

    /** Sends every queued change in order, then reloads the newest page. */
    suspend fun syncPending(): SyncResult {
        if (!awaitSession()) return SyncResult.AUTH_FAILED
        var sent = false
        syncMutex.withLock {
            val owner = session.user?.name.orEmpty()
            for (change in outbox.value) {
                if (change.failed) continue
                if (owner.isNotEmpty() && change.owner.isNotEmpty() && change.owner != owner) {
                    outbox.update { changes ->
                        changes.map { if (it.id == change.id) it.markFailed(OTHER_ACCOUNT) else it }
                    }
                    continue
                }
                try {
                    send(change)
                    outbox.update { changes -> changes.filterNot { it.id == change.id } }
                    sent = true
                } catch (e: ApiException) {
                    if (e.isUnauthenticated) {
                        session.onUnauthenticated()
                        return SyncResult.AUTH_FAILED
                    }
                    if (e.isRetryable) return SyncResult.RETRY
                    outbox.update { changes -> changes.map { if (it.id == change.id) it.markFailed(UserMessages.forApi(e)) else it } }
                } catch (e: IOException) {
                    return SyncResult.RETRY
                }
            }
        }
        if (sent) {
            refresh(force = true)
            requestTiles()
        }
        return SyncResult.SUCCESS
    }

    private suspend fun send(change: PendingChange) {
        val api = session.api ?: throw IOException("No server")
        when (change) {
            is PendingChange.Create -> {
                val created = api.createMemo(change.content, change.visibility, change.parent)
                if (change.pinned != created.pinned || change.state != created.state) {
                    val settled = api.updateMemo(MemoPatch(created.name, pinned = change.pinned, state = change.state))
                    cache.update { mergeByNewest(it, listOf(settled)) }
                    flushedNames[change.localName] = settled.name
                } else {
                    cache.update { mergeByNewest(it, listOf(created)) }
                    flushedNames[change.localName] = created.name
                }
            }

            is PendingChange.Update -> {
                val updated = api.updateMemo(
                    MemoPatch(change.memoName, change.content, change.pinned, change.state, change.visibility),
                )
                cache.update { mergeByNewest(it, listOf(updated)) }
            }

            is PendingChange.Delete -> {
                api.deleteMemo(change.memoName)
                cache.update { memos -> memos.filterNot { it.name == change.memoName } }
            }
        }
    }

    // ---- Projection -----------------------------------------------------------------------------

    private fun project(memos: List<Memo>, changes: List<PendingChange>, state: NetState, archived: Boolean): ListState {
        val byName = LinkedHashMap<String, Memo>(memos.size)
        for (memo in memos) byName[memo.name] = memo
        for (change in changes) {
            val next = change.applyTo(byName[change.target])
            if (next != null) byName[change.target] = next else byName.remove(change.target)
        }
        val pending = changes.filter { it.error == null }.mapTo(HashSet()) { it.target }
        val failed = changes.filter { it.error != null }.mapTo(HashSet()) { it.target }
        val entries = byName.values.asSequence()
            .filter { it.isArchived == archived }
            .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.createdInstant ?: Instant.EPOCH })
            .map { Entry(it, pending = it.name in pending, failed = it.name in failed) }
            .toList()
        return ListState(
            entries = entries,
            refreshing = state.refreshing,
            loadingMore = state.loadingMore,
            offline = state.offline,
            error = state.error,
            loaded = state.loaded,
            endReached = state.endReached,
            pendingCount = pending.size,
            failedCount = failed.size,
        )
    }

    private fun resolve(name: String, memos: List<Memo>, changes: List<PendingChange>): Memo? {
        val target = flushedNames[name] ?: name
        var memo = memos.firstOrNull { it.name == target }
        for (change in changes) {
            if (change.target == target) memo = change.applyTo(memo)
        }
        return memo
    }

    /** Keeps the order of [current] and adds or replaces with anything newer in [incoming], then
     *  caps the list so the file stays small. */
    private fun mergeByNewest(current: List<Memo>, incoming: List<Memo>): List<Memo> {
        val fresh = incoming.associateBy { it.name }
        val merged = current.map { fresh[it.name] ?: it }.toMutableList()
        val known = merged.mapTo(HashSet()) { it.name }
        merged += incoming.filter { it.name !in known }
        return merged.asSequence()
            .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.createdInstant ?: Instant.EPOCH })
            .take(MAX_CACHED)
            .toList()
    }

    companion object {
        private const val MAX_CACHED = 500
        private const val MIN_REFRESH_INTERVAL_MS = 15_000L
        private const val SEARCH_PAGE_SIZE = 40
        private const val SESSION_RESTORE_TIMEOUT_MS = 20_000L
        private const val OTHER_ACCOUNT = "Made with another account"
    }
}
