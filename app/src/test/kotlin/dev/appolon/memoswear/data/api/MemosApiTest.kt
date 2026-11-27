package dev.appolon.memoswear.data.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemosApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MemosApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = MemosApi(OkHttpClient(), server.url("/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(body: String, status: Int = 200, vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .body(body)
                .build(),
        )
    }

    @Test
    fun `listing memos is a Connect call with the filter in its body`() = runBlocking {
        respond("""{"memos":[{"name":"memos/a","content":"Hi","visibility":"PRIVATE","state":"NORMAL"}],"nextPageToken":"t2"}""")

        val page = api.listMemos(pageSize = 20, filter = MemosApi.TOP_LEVEL, orderBy = MemosApi.HOME_ORDER)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/memos.api.MemoService/ListMemos", request.url.encodedPath)
        assertEquals("1", request.headers["Connect-Protocol-Version"])
        assertEquals("""{"pageSize":20,"filter":"parent == null","orderBy":"pinned desc, create_time desc"}""", request.body!!.utf8())
        assertEquals("Hi", page.memos.single().content)
        assertEquals("t2", page.nextPageToken)
    }

    @Test
    fun `creating a memo sends only what the API accepts`() = runBlocking {
        respond("""{"name":"memos/a","content":"Hi","visibility":"PRIVATE","state":"NORMAL"}""")

        api.createMemo("Hi", dev.appolon.memoswear.data.model.Visibility.PRIVATE)

        assertEquals("""{"memo":{"content":"Hi","visibility":"PRIVATE"}}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `a comment is a memo with a parent`() = runBlocking {
        respond("""{"name":"memos/c","content":"ok","parent":"memos/a","visibility":"PRIVATE","state":"NORMAL"}""")

        api.createMemo("ok", dev.appolon.memoswear.data.model.Visibility.PRIVATE, parent = "memos/a")

        assertEquals("""{"memo":{"content":"ok","visibility":"PRIVATE","parent":"memos/a"}}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `updating sends the changed fields and their mask`() = runBlocking {
        respond("""{"name":"memos/a","pinned":true,"visibility":"PRIVATE","state":"NORMAL","content":"x"}""")

        api.updateMemo(MemoPatch(name = "memos/a", pinned = true))

        assertEquals("""{"memo":{"name":"memos/a","pinned":true},"updateMask":"pinned"}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `the mask names every field that is set`() {
        assertEquals("content,pinned,visibility", MemoPatch("memos/a", "c", true, null, dev.appolon.memoswear.data.model.Visibility.PUBLIC).updateMask)
        val full = MemoPatch("memos/a", "c", true, dev.appolon.memoswear.data.model.MemoState.ARCHIVED, dev.appolon.memoswear.data.model.Visibility.PUBLIC)
        assertEquals("content,pinned,state,visibility", full.updateMask)
    }

    @Test
    fun `errors of both protocols are understood`() = runBlocking {
        respond("""{"code":"invalid_argument","message":"unmatched username and password"}""", 400)
        val connect = runCatching { api.signInWithPassword("watch", "wrong") }.exceptionOrNull() as ApiException
        assertTrue(connect.isInvalidArgument)
        assertEquals("unmatched username and password", connect.message)
        assertEquals(400, connect.httpStatus)

        respond("""{"code":16,"message":"authentication required","details":[]}""", 401)
        val rest = runCatching { api.getMemo("memos/a") }.exceptionOrNull() as ApiException
        assertTrue(rest.isUnauthenticated)

        respond("""{"code":"unavailable","message":"try later"}""", 503)
        assertTrue((runCatching { api.getMemo("memos/a") }.exceptionOrNull() as ApiException).isRetryable)

        respond("""<html>Bad Gateway</html>""", 502)
        val proxy = runCatching { api.getMemo("memos/a") }.exceptionOrNull() as ApiException
        assertEquals("http_502", proxy.code)
        assertTrue(proxy.isRetryable)
    }

    @Test
    fun `filters quote whatever they contain`() {
        assertEquals("\"a \\\"b\\\" \\\\ c\"", MemosApi.celString("a \"b\" \\ c"))
        assertEquals("""parent == null && content.contains("rock & roll")""", MemosApi.contentFilter("rock & roll"))
        assertEquals("""parent == null && "work" in tags""", MemosApi.tagFilter("#work"))
        assertEquals("parent == \"memos/abc\\\"d\"", MemosApi.commentsFilter("memos/abc\"d"))
        assertEquals("\"one line\"", MemosApi.celString("one\nline\r"))
    }

    @Test
    fun `refreshing returns the new token, or nothing when the cookie is refused`() = runBlocking {
        respond("""{"accessToken":"t2","accessTokenExpireTime":"2026-11-27T00:02:48.104139609Z"}""")
        assertEquals("t2", api.refreshToken(OkHttpClient())?.accessToken)

        respond("""{"code":"unauthenticated","message":"refresh token not found"}""", 401)
        assertNull(api.refreshToken(OkHttpClient()))

        val refresh = server.takeRequest()
        assertEquals("/memos.api.AuthService/RefreshToken", refresh.url.encodedPath)
        assertEquals("1", refresh.headers["Connect-Protocol-Version"])
        assertNull(refresh.headers["Authorization"])
    }

    @Test
    fun `a server behind a path prefix keeps its prefix on every call`() = runBlocking {
        val underPath = MemosApi(OkHttpClient(), server.url("/memos/"))
        respond("""{"version":"26.11.2","instanceUrl":"https://example.com/memos"}""")

        underPath.instanceProfile()

        assertEquals("/memos/memos.api.InstanceService/GetInstanceProfile", server.takeRequest().url.encodedPath)
        assertEquals("/memos/file/attachments/a/b.png", underPath.fileUrl("/file/attachments/a/b.png")!!.encodedPath)
        // A reference written as a URL on the instance's own origin follows the same path.
        underPath.instanceOrigin = "https://example.com/".toHttpUrl()
        assertEquals("/memos/file/attachments/a/b.png", underPath.fileUrl("https://example.com/file/attachments/a/b.png")!!.encodedPath)
        assertEquals("/elsewhere/a.jpg", underPath.fileUrl("https://other.example.com/elsewhere/a.jpg")!!.encodedPath)
    }

    @Test
    fun `file URLs add the thumbnail flag`() {
        val url = api.fileUrl("/file/attachments/a/b.png", thumbnail = true)!!
        assertEquals("/file/attachments/a/b.png", url.encodedPath)
        assertEquals("true", url.queryParameter("thumbnail"))
        assertFalse(api.displayUrl.endsWith("/"))
    }
}
