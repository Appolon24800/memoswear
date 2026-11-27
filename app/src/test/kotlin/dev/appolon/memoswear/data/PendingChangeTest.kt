package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.AppJson
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.MemoState
import dev.appolon.memoswear.data.model.Visibility
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingChangeTest {
    private fun create(id: String = "c1", content: String = "one") =
        PendingChange.Create(localId = id, content = content, visibility = Visibility.PRIVATE, createdAt = 1L, owner = "users/watch")

    private fun update(name: String, id: String = "u1", content: String? = null, pinned: Boolean? = null) =
        PendingChange.Update(changeId = id, memoName = name, content = content, pinned = pinned, createdAt = 2L)

    private fun delete(name: String) = PendingChange.Delete(changeId = "d1", memoName = name, createdAt = 3L)

    @Test
    fun `a change to a memo replaces the change already queued for it`() {
        val first = update("memos/a", content = "new")
        val merged = PendingChange.merge(listOf(first), update("memos/a", id = "u2", pinned = true))
        assertEquals(listOf(update("memos/a", content = "new", pinned = true)), merged)
    }

    @Test
    fun `edits to an unsent note fold into its create`() {
        val note = create()
        val merged = PendingChange.merge(listOf(note), update(note.localName, content = "two"))
        assertEquals(listOf(create(content = "two")), merged)

        val pinned = PendingChange.merge(merged, update(create().localName, pinned = true))
        assertEquals(true, (pinned.single() as PendingChange.Create).pinned)
        // The note keeps the time it was first written on the watch.
        assertEquals(1L, pinned.single().createdAt)
    }

    @Test
    fun `deleting an unsent note removes it without a delete`() {
        val note = create()
        assertEquals(emptyList<PendingChange>(), PendingChange.merge(listOf(note), delete(note.localName)))
    }

    @Test
    fun `deleting a memo with queued edits keeps only the delete`() {
        val merged = PendingChange.merge(listOf(update("memos/a", content = "x"), update("memos/b", pinned = true)), delete("memos/a"))
        assertEquals(listOf(update("memos/b", pinned = true), delete("memos/a")), merged)
    }

    @Test
    fun `an update to a memo on its way out is dropped`() {
        assertEquals(listOf(delete("memos/a")), PendingChange.merge(listOf(delete("memos/a")), update("memos/a", pinned = true)))
    }

    @Test
    fun `changes survive a round trip through storage`() {
        val changes = listOf(create(content = "# Hi\n- [ ] a"), update("memos/x", pinned = true), delete("memos/y"))
        val text = AppJson.encodeToString(ListSerializer(PendingChange.serializer()), changes)
        assertEquals(changes, AppJson.decodeFromString(ListSerializer(PendingChange.serializer()), text))
        assertTrue(text.contains("\"create\""))
    }

    @Test
    fun `applyTo gives the memo its final shape`() {
        val note = create(content = "hello").toMemo()
        assertEquals("local/c1", note.name)
        assertEquals("hello", note.content)
        assertTrue(note.isLocal)

        val base = Memo(name = "memos/x", content = "old", createTime = "2026-11-26T23:47:58Z")
        val edited = update("memos/x", content = "# New", pinned = true).applyTo(base)!!
        assertEquals("New", edited.property.title)
        assertEquals(true, edited.pinned)
        assertEquals("2026-11-26T23:47:58Z", edited.createTime)

        assertNull(delete("memos/x").applyTo(base))
    }

    @Test
    fun `a rejected change keeps its reason until it is edited again`() {
        val note = create()
        val failed = note.markFailed("Too long")
        assertEquals(true, failed.failed)
        val retried = PendingChange.merge(listOf(failed), update(note.localName, content = "short")).single()
        assertEquals(false, retried.failed)
        assertEquals("short", (retried as PendingChange.Create).content)
    }

    @Test
    fun `archiving an unsent note is remembered for when it is created`() {
        val note = create()
        val merged = PendingChange.merge(
            listOf(note),
            PendingChange.Update(changeId = "u9", memoName = note.localName, state = MemoState.ARCHIVED, createdAt = 5L),
        ).single() as PendingChange.Create
        assertEquals(MemoState.ARCHIVED, merged.state)
    }
}

class JsonListStoreTest {
    private fun temporary(name: String): File = File.createTempFile(name, ".json").also { it.delete() }

    @Test
    fun `what is saved is what the next run reads`() = runBlocking {
        val file = temporary("store")
        val first = JsonListStore(file, AppJson, Memo.serializer())
        first.update { it + Memo(name = "memos/a", content = "one") }
        first.replace(listOf(Memo(name = "memos/b", content = "two"), Memo(name = "memos/a")))

        val second = JsonListStore(file, AppJson, Memo.serializer())
        assertEquals(listOf("memos/b", "memos/a"), second.value.map { it.name })
        assertTrue(file.readText().startsWith("["))
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `a damaged file is set aside instead of overwritten`() = runBlocking {
        val file = temporary("damaged")
        file.writeText("{not json")
        val store = JsonListStore(file, AppJson, Memo.serializer())
        assertTrue(store.value.isEmpty())

        store.replace(listOf(Memo(name = "memos/a")))
        val salvaged = File(file.path + ".corrupt")
        assertTrue(salvaged.isFile)
        assertEquals("{not json", salvaged.readText())
        assertEquals(1, store.value.size)
    }
}

class PersistedCookieJarTest {
    private val url = "https://memos.example.com/".toHttpUrl()

    /** The cookie the real server sets over HTTPS. */
    private fun cookie(value: String, expires: String = "Fri, 21 Oct 2050 07:28:00 GMT"): Cookie? =
        Cookie.parse(url, "memos_refresh=$value; Path=/; HttpOnly; Expires=$expires; SameSite=Lax; Secure")

    @Test
    fun `a cookie set by the server is sent back to it and only to it`() {
        val jar = PersistedCookieJar(temporary("cookies"))
        jar.saveFromResponse(url, listOf(cookie("abc")!!))

        assertEquals("abc", jar.loadForRequest(url).single().value)
        assertEquals("abc", jar.loadForRequest("https://memos.example.com/api/auth/refresh".toHttpUrl()).single().value)
        assertTrue(jar.loadForRequest("https://other.example.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://memos.example.com/".toHttpUrl()).isEmpty())
        assertEquals(listOf("memos_refresh"), jar.names())
    }

    @Test
    fun `the refresh cookie survives the process that received it`() {
        val file = temporary("persisted")
        PersistedCookieJar(file).saveFromResponse(url, listOf(cookie("first")!!))

        val nextProcess = PersistedCookieJar(file)
        assertEquals("first", nextProcess.loadForRequest(url).single().value)

        // The server rotates the cookie on every refresh: same name, same path, new value.
        nextProcess.saveFromResponse(url, listOf(cookie("second")!!))
        assertEquals("second", PersistedCookieJar(file).loadForRequest(url).single().value)
    }

    @Test
    fun `a host-only cookie for a bare address stays bound to it`() {
        val file = temporary("local")
        val local = "http://10.0.2.2:2283/".toHttpUrl()
        val set = Cookie.parse(local, "memos_refresh=x; Path=/; HttpOnly; SameSite=Lax")!!
        assertTrue(set.hostOnly)
        PersistedCookieJar(file).saveFromResponse(local, listOf(set))

        val reloaded = PersistedCookieJar(file).loadForRequest(local).single()
        assertTrue(reloaded.hostOnly)
        assertEquals("10.0.2.2", reloaded.domain)
        // Cookies are not specific to a port, so the same address on another port still gets it...
        assertEquals("x", PersistedCookieJar(file).loadForRequest("http://10.0.2.2:2284/".toHttpUrl()).single().value)
        // ...while a different address does not.
        assertTrue(PersistedCookieJar(file).loadForRequest("http://10.0.2.3:2283/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `an expired or cleared cookie is not sent`() {
        val file = temporary("expiry")
        val jar = PersistedCookieJar(file)
        jar.saveFromResponse(url, listOf(cookie("gone", expires = "Thu, 01 Jan 1970 00:00:00 GMT")!!))
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertFalse(file.readText().contains("gone"))

        jar.saveFromResponse(url, listOf(cookie("kept")!!))
        jar.clear()
        assertTrue(PersistedCookieJar(file).loadForRequest(url).isEmpty())
    }

    @Test
    fun `two sites keep their cookies apart`() {
        val jar = PersistedCookieJar(temporary("sites"))
        val other = "https://other.example.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("a")!!))
        jar.saveFromResponse(other, listOf(Cookie.parse(other, "memos_refresh=b; Path=/")!!))
        assertEquals("a", jar.loadForRequest(url).single().value)
        assertEquals("b", jar.loadForRequest(other).single().value)
    }

    private fun temporary(name: String): File = File.createTempFile(name, ".json").also { it.delete() }
}
