package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.AppJson
import dev.appolon.memoswear.data.model.Visibility
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoRepositoryTest {
    private lateinit var server: FakeMemosServer
    private lateinit var dir: File
    private val account = InMemoryAccountStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var syncsRequested = 0
    private var tileRefreshes = 0
    private lateinit var session: SessionStore
    private lateinit var repository: MemoRepository

    @Before
    fun setUp() {
        server = FakeMemosServer()
        dir = Files.createTempDirectory("memoswear").toFile()
        session = SessionStore(account, dir, scope)
        repository = MemoRepository(
            session = session,
            settings = account,
            cacheFile = File(dir, "memos.json"),
            outboxFile = File(dir, "outbox.json"),
            scope = scope,
            requestSync = { syncsRequested++ },
            requestTiles = { tileRefreshes++ },
        )
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private suspend fun signedIn() {
        session.probe(server.baseUrl.toString())
        session.signInWithPassword("watch", "watchpass123")
        repository.refresh(force = true)
    }

    private fun names(): List<String> = repository.home.value.entries.map { it.memo.name }

    @Test
    fun `the list shows pins first, then the newest, and leaves archived notes out`() = runBlocking {
        signedIn()
        val first = session.api!!.createMemo("first", Visibility.PRIVATE).name
        val second = session.api!!.createMemo("second", Visibility.PRIVATE).name
        val third = session.api!!.createMemo("third", Visibility.PRIVATE).name
        session.api!!.updateMemo(dev.appolon.memoswear.data.api.MemoPatch(third, pinned = true))
        session.api!!.updateMemo(dev.appolon.memoswear.data.api.MemoPatch(first, state = dev.appolon.memoswear.data.model.MemoState.ARCHIVED))

        repository.refresh(force = true)
        // Archived notes live on their own page, which is fetched when that list is opened.
        assertEquals(listOf(first), repository.archivedMemos().map { it.memo.name })

        assertEquals(listOf(third, second), names())
        assertEquals(listOf(first), repository.archived.value.entries.map { it.memo.name })
        assertTrue(repository.home.value.endReached)
        assertEquals(2, repository.home.value.entries.size)
    }

    @Test
    fun `a note written offline appears at once and is sent when back online`() = runBlocking {
        signedIn()
        server.goOffline()

        val local = repository.createMemo("A thought")

        assertTrue(local.startsWith("local/"))
        assertEquals(listOf(local), names())
        assertTrue(repository.home.value.entries.single().pending)
        assertEquals("A thought", repository.home.value.entries.single().memo.content)
        assertEquals(1, syncsRequested)
        assertEquals(MemoRepository.SyncResult.RETRY, repository.syncPending())
        assertTrue(repository.home.value.pendingCount == 1)

        server.comeOnline()
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())

        val entry = repository.home.value.entries.single()
        assertTrue(entry.memo.name.startsWith("memos/"))
        assertFalse(entry.pending)
        assertFalse(repository.home.value.entries.single().memo.isLocal)
        assertEquals("A thought", entry.memo.content)
        assertEquals(1, server.memos.size)
        // The name the editor knew still finds the note.
        assertEquals("A thought", repository.memo(local)?.content)
    }

    @Test
    fun `edits to an unsent note arrive as one note`() = runBlocking {
        signedIn()
        server.goOffline()
        val local = repository.createMemo("a")

        repository.updateContent(local, "ab")
        repository.setPinned(local, true)
        assertEquals("ab", repository.memo(local)?.content)
        assertEquals(true, repository.memo(local)?.pinned)

        server.comeOnline()
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())

        val memo = server.memos.values.single()
        assertEquals("ab", (memo["content"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(true, (memo["pinned"] as kotlinx.serialization.json.JsonPrimitive?)?.content == "true")
        assertEquals(1, server.requestsTo("/memos.api.MemoService/CreateMemo").size)
        assertEquals(1, server.requestsTo("/memos.api.MemoService/UpdateMemo").size)
    }

    @Test
    fun `deleting an unsent note sends nothing`() = runBlocking {
        signedIn()
        server.goOffline()
        val local = repository.createMemo("never mind")

        repository.deleteMemo(local)

        assertTrue(repository.home.value.isEmpty)
        server.comeOnline()
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())
        assertEquals(0, server.requestsTo("/memos.api.MemoService/CreateMemo").size)
        assertEquals(0, server.requestsTo("/memos.api.MemoService/DeleteMemo").size)
    }

    @Test
    fun `a note the server rejects is kept and marked, and later notes still go through`() = runBlocking {
        signedIn()
        server.goOffline()
        val rejected = repository.createMemo("too long")
        val fine = repository.createMemo("fine")
        server.comeOnline()
        server.rejectNextCreate = true

        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())

        val state = repository.home.value
        assertEquals(1, state.failedCount)
        assertEquals(0, state.pendingCount)
        val rejectedEntry = state.entries.single { it.memo.name == rejected }
        assertTrue(rejectedEntry.failed)
        assertTrue(rejectedEntry.memo.isLocal)
        val fineEntry = state.entries.single { it.memo.name != rejected }
        assertFalse(fineEntry.failed)
        assertTrue(fineEntry.memo.name.startsWith("memos/"))
        assertEquals("too long", repository.memo(rejected)?.content)

        repository.retryFailed()
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())
        assertEquals(0, repository.home.value.failedCount)
        assertEquals(2, server.memos.size)
    }

    @Test
    fun `server trouble means the change waits and is tried again`() = runBlocking {
        signedIn()
        server.failApiWith = 503
        repository.createMemo("later")

        assertEquals(MemoRepository.SyncResult.RETRY, repository.syncPending())
        assertTrue(repository.home.value.entries.single().pending)
        assertEquals(1, repository.home.value.pendingCount)

        server.failApiWith = null
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())
        assertFalse(repository.home.value.entries.single().pending)
    }

    @Test
    fun `an ended session stops sending without losing the notes`() = runBlocking {
        signedIn()
        server.goOffline()
        repository.createMemo("keep me")
        server.comeOnline()
        server.invalidateTokens = true
        server.rejectRefresh = true

        assertEquals(MemoRepository.SyncResult.AUTH_FAILED, repository.syncPending())

        assertEquals(SessionStore.State.SignedOut, session.state.value)
        assertTrue(repository.home.value.entries.single().pending)
        assertEquals(1, repository.home.value.pendingCount)
    }

    @Test
    fun `ticking a task rewrites only that line`() = runBlocking {
        signedIn()
        val local = repository.createMemo("- [ ] Milk\n- [ ] Bread")
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())
        val name = repository.home.value.entries.single().memo.name

        repository.toggleTask(name, line = 0, checked = true)
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())

        val content = (server.memos.values.single()["content"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("- [x] Milk\n- [ ] Bread", content)
        assertEquals(1 to 2, repository.memo(name)?.taskCounts)
    }

    @Test
    fun `archiving a note from the watch moves it to the archived list`() = runBlocking {
        signedIn()
        val name = session.api!!.createMemo("old plan", Visibility.PRIVATE).name
        repository.refresh(force = true)

        repository.setArchived(name, true)
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())

        assertTrue(repository.home.value.isEmpty)
        // The reload of the main list must not drop it from the archived one.
        repository.refresh(force = true)
        assertEquals(listOf(name), repository.archived.value.entries.map { it.memo.name })

        repository.setArchived(name, false)
        assertEquals(MemoRepository.SyncResult.SUCCESS, repository.syncPending())
        assertEquals(listOf(name), names())
        assertTrue(repository.archived.value.isEmpty)
    }

    @Test
    fun `the first sync adopts the default visibility of the web client`() = runBlocking {
        signedIn()

        val name = repository.createMemo("who can read this?")
        repository.syncPending()

        assertEquals("PROTECTED", (server.memos.values.single()["visibility"] as kotlinx.serialization.json.JsonPrimitive).content)
        account.setDefaultVisibility(Visibility.PUBLIC)
        repository.createMemo("now public")
        repository.syncPending()
        assertEquals("PUBLIC", server.memos.values.last()["visibility"].let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertFalse(name.isEmpty())
    }

    @Test
    fun `search falls back to saved notes when offline, and tags come from the server`() = runBlocking {
        signedIn()
        val local = repository.createMemo("buy #store milk")
        repository.syncPending()
        server.goOffline()

        val offline = repository.search("MILK", tag = null)
        assertTrue(offline.offline)
        assertEquals(1, offline.entries.size)

        val tagged = repository.search("", tag = "store")
        assertTrue(tagged.offline)
        assertEquals(1, tagged.entries.size)
        assertEquals(1, repository.tags().size)

        server.comeOnline()
        val online = repository.search("milk", tag = null)
        assertFalse(online.offline)
        assertEquals(1, online.entries.size)
        assertEquals(mapOf("idea" to 2, "work" to 1), repository.tags().associate { it.tag to it.count })
        assertFalse(local.isEmpty())
    }

    @Test
    fun `changes made under another account are never sent`() = runBlocking {
        signedIn()
        val outbox = File(dir, "outbox.json")
        val foreign = PendingChange.Create(
            localId = "foreign",
            content = "someone else's note",
            visibility = Visibility.PRIVATE,
            createdAt = 1L,
            owner = "users/someone-else",
        )
        outbox.writeText(AppJson.encodeToString(ListSerializer(PendingChange.serializer()), listOf(foreign)))
        val reopened = MemoRepository(
            session = session,
            settings = account,
            cacheFile = File(dir, "memos.json"),
            outboxFile = outbox,
            scope = scope,
            requestSync = {},
            requestTiles = {},
        )

        assertEquals(MemoRepository.SyncResult.SUCCESS, reopened.syncPending())

        assertEquals(1, reopened.home.value.failedCount)
        assertEquals(0, server.requestsTo("/memos.api.MemoService/CreateMemo").size)
        assertEquals(0, server.memos.size)
    }

    @Test
    fun `the newest memos on the watch are offered to tiles without failed notes`() = runBlocking {
        signedIn()
        server.goOffline()
        val failed = repository.createMemo("will fail")
        val kept = repository.createMemo("kept")
        server.comeOnline()
        server.rejectNextCreate = true
        repository.syncPending()

        val recent = repository.recentEntries(10)
        assertEquals(1, recent.size)
        assertEquals("kept", recent.single().memo.content)
        assertFalse(failed.isEmpty())
    }

    @Test
    fun `forgetting the account empties both lists`() = runBlocking {
        signedIn()
        repository.createMemo("note")
        repository.syncPending()

        repository.clear()

        assertTrue(repository.home.value.isEmpty)
        assertFalse(repository.home.value.loaded)
    }
}
