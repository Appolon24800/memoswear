package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.model.Visibility
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Keeps the account in memory, the way the app keeps it on disk. */
class InMemoryAccountStore : AccountStore {
    var saved: AccountStore.Account? = null
    private var visibilityChosen = false
    private val visibility = MutableStateFlow(Visibility.PRIVATE)

    override val defaultVisibility: Flow<Visibility> = visibility

    override suspend fun account(): AccountStore.Account? = saved

    override suspend fun saveAccount(account: AccountStore.Account) {
        saved = account
    }

    override suspend fun clearAccount() {
        // The server address stays, as it does in the app.
        saved = saved?.copy(mode = "", personalAccessToken = null, user = null)
    }

    override suspend fun setDefaultVisibility(visibility: Visibility) {
        this.visibility.value = visibility
        visibilityChosen = true
    }

    override suspend fun defaultVisibilityIsSet(): Boolean = visibilityChosen
}

class SessionStoreTest {
    private lateinit var server: FakeMemosServer
    private lateinit var dir: File
    private val account = InMemoryAccountStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var forgotten = 0

    @Before
    fun setUp() {
        server = FakeMemosServer()
        dir = Files.createTempDirectory("memoswear").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun newSession(restore: Boolean = false): SessionStore =
        SessionStore(account, dir, scope, onForget = { forgotten++ }).also { if (restore) it.restore() }

    private suspend fun signIn(session: SessionStore = newSession()): SessionStore {
        session.probe(server.baseUrl.toString())
        session.signInWithPassword("watch", "watchpass123")
        return session
    }

    private suspend fun SessionStore.settled(): SessionStore.State {
        state.first { it !is SessionStore.State.Restoring }
        return state.value
    }

    private fun cookieFile() = File(dir, "session-cookies.json")

    @Test
    fun `signing in with a password saves the account and keeps the cookie on disk`() = runBlocking {
        val session = signIn()

        assertTrue(session.settled() is SessionStore.State.SignedIn)
        assertEquals("watch", session.user?.username)
        assertEquals("Test Memos", session.instance.value.title)
        assertFalse(session.instance.value.passwordAuthAllowed)
        assertEquals("cookies", account.saved?.mode)
        assertEquals("users/watch", account.saved?.user?.name)
        assertTrue(cookieFile().readText().contains("memos_refresh"))

        assertEquals("users/watch", session.api!!.currentUser().name)
        val sent = server.requestsTo("/memos.api.AuthService/GetCurrentUser").single()
        assertTrue(sent.headers["Authorization"]!!.startsWith("Bearer at-"))
        assertEquals(1, server.requestsTo("/memos.api.AuthService/SignIn").size)
    }

    @Test
    fun `a wrong password is reported and leaves the app signed out`() = runBlocking {
        val session = newSession()
        session.probe(server.baseUrl.toString())

        val error = runCatching { session.signInWithPassword("watch", "nope") }.exceptionOrNull()

        assertTrue(error is dev.appolon.memoswear.data.api.ApiException)
        assertEquals(SessionStore.State.SignedOut, session.state.value)
        assertFalse(cookieFile().readText().contains("memos_refresh"))
    }

    @Test
    fun `an address that is not a server is refused`() = runBlocking {
        val session = newSession()
        val error = runCatching { session.probe("nonsense://x") }.exceptionOrNull()
        assertTrue(error is dev.appolon.memoswear.data.SignInException)

        server.goOffline()
        val offline = runCatching { session.probe(server.baseUrl.toString()) }.exceptionOrNull()
        assertTrue(offline is IOException)
        assertEquals(SessionStore.State.SignedOut, session.state.value)
        server.comeOnline()
    }

    @Test
    fun `an access token is used as it is`() = runBlocking {
        val session = newSession()
        session.probe(server.baseUrl.toString())

        session.signInWithToken("  memos_pat_known  ")

        assertTrue(session.settled() is SessionStore.State.SignedIn)
        assertEquals("token", account.saved?.mode)
        val sent = server.requestsTo("/memos.api.AuthService/GetCurrentUser").single()
        assertEquals("Bearer memos_pat_known", sent.headers["Authorization"])
        assertEquals(0, server.requestsTo("/memos.api.AuthService/RefreshToken").size)
    }

    @Test
    fun `a rejected access token is refused with a message`() = runBlocking {
        val session = newSession()
        session.probe(server.baseUrl.toString())

        val error = runCatching { session.signInWithToken("memos_pat_wrong") }.exceptionOrNull()
        val notAToken = runCatching { session.signInWithToken("hello") }.exceptionOrNull()

        assertTrue(error is dev.appolon.memoswear.data.SignInException)
        assertTrue(notAToken is dev.appolon.memoswear.data.SignInException)
        assertEquals(SessionStore.State.SignedOut, session.state.value)
        assertEquals(null, account.saved?.user)
    }

    @Test
    fun `an access token that is not recognised later ends the session`() = runBlocking {
        val session = newSession()
        session.probe(server.baseUrl.toString())
        session.signInWithToken("memos_pat_known")
        server.invalidateTokens = true

        val error = runCatching { session.api!!.currentUser() }.exceptionOrNull()

        assertTrue((error as dev.appolon.memoswear.data.api.ApiException).isUnauthenticated)
        // Nothing can renew a personal access token, so the person has to sign in again.
        assertEquals(SessionStore.State.SignedOut, session.state.value)
    }

    @Test
    fun `an expired access token is renewed before the request`() = runBlocking {
        server.tokenLifetimeSeconds = 0
        val session = signIn()
        server.tokenLifetimeSeconds = 900

        assertEquals("users/watch", session.api!!.currentUser().name)

        val refresh = server.requestsTo("/memos.api.AuthService/RefreshToken").single()
        assertTrue(refresh.headers["Cookie"]!!.contains("memos_refresh=rt-"))
        assertEquals(1, server.requestsTo("/memos.api.AuthService/GetCurrentUser").size)
    }

    @Test
    fun `a refused token is renewed once and the request is retried`() = runBlocking {
        val session = signIn()
        server.invalidateTokens = true
        server.forgetRequests()

        assertEquals("users/watch", session.api!!.currentUser().name)

        val calls = server.requestsTo("/memos.api.AuthService/GetCurrentUser")
        assertEquals(2, calls.size)
        assertTrue(calls[0].headers["Authorization"] != calls[1].headers["Authorization"])
        assertEquals(1, server.requestsTo("/memos.api.AuthService/RefreshToken").size)
        assertTrue(session.state.value is SessionStore.State.SignedIn)
    }

    @Test
    fun `requests that arrive together share one renewal`() = runBlocking {
        server.tokenLifetimeSeconds = 0
        val session = signIn()
        server.tokenLifetimeSeconds = 900
        server.forgetRequests()

        val users = (1..6).map { scope.async { session.api!!.currentUser() } }.awaitAll()

        assertEquals(6, users.size)
        assertEquals(1, server.requestsTo("/memos.api.AuthService/RefreshToken").size)
        assertEquals(6, server.requestsTo("/memos.api.AuthService/GetCurrentUser").size)
    }

    @Test
    fun `when the refresh cookie is refused the session ends and the notes are kept`() = runBlocking {
        val session = signIn()
        server.invalidateTokens = true
        server.rejectRefresh = true

        val error = runCatching { session.api!!.currentUser() }.exceptionOrNull()

        assertTrue((error as dev.appolon.memoswear.data.api.ApiException).isUnauthenticated)
        assertEquals(SessionStore.State.SignedOut, session.state.value)
        assertEquals(0, forgotten)
    }

    @Test
    fun `a new process is still signed in through its cookie`() = runBlocking {
        signIn()

        val nextProcess = newSession(restore = true).settled()

        assertTrue(nextProcess is SessionStore.State.SignedIn)
        assertEquals("watch", (nextProcess as SessionStore.State.SignedIn).user.username)
        assertEquals(1, server.requestsTo("/memos.api.AuthService/SignIn").size)
        assertEquals(1, server.requestsTo("/memos.api.AuthService/RefreshToken").size)
        assertEquals("Test Memos", account.saved?.instanceTitle)
    }

    @Test
    fun `starting offline keeps the saved account so its notes stay readable`() = runBlocking {
        signIn()
        server.goOffline()

        val state = newSession(restore = true).settled()

        assertTrue(state is SessionStore.State.SignedIn)
        server.comeOnline()
    }

    @Test
    fun `a cookie the server no longer knows signs the app out`() = runBlocking {
        signIn()
        server.invalidateTokens = true
        server.rejectRefresh = true

        assertEquals(SessionStore.State.SignedOut, newSession(restore = true).settled())
        assertEquals(0, forgotten)
    }

    @Test
    fun `signing out clears the account, the cookie, and asks for the notes to be forgotten`() = runBlocking {
        val session = signIn()

        session.signOut()

        assertEquals(SessionStore.State.SignedOut, session.state.value)
        assertEquals(1, forgotten)
        assertFalse(cookieFile().readText().contains("memos_refresh"))
        assertEquals(null, account.saved?.user)
        assertEquals(account.saved?.serverUrl, server.baseUrl.toString())
        val signOut = server.requestsTo("/memos.api.AuthService/SignOut").single()
        assertTrue(signOut.headers["Authorization"]!!.startsWith("Bearer "))
    }

    @Test
    fun `naming a different server forgets the previous server's notes`() = runBlocking {
        val session = signIn()

        runCatching { session.probe("http://127.0.0.1:1/") }

        assertEquals(1, forgotten)
        assertEquals(SessionStore.State.SignedOut, session.state.value)
    }

    @Test
    fun `naming the same server again keeps the notes`() = runBlocking {
        val session = signIn()
        val same = server.baseUrl.toString().trimEnd('/')

        session.probe(same)

        assertEquals(0, forgotten)
    }

    @Test
    fun `server addresses are normalised for every request`() {
        fun normalized(text: String) = SessionStore.normalizeServerUrl(text)?.toString()

        assertEquals("https://memos.example.com/", normalized("memos.example.com"))
        assertEquals("https://memos.example.com/", normalized("  https://memos.example.com  "))
        assertEquals("https://memos.example.com/", normalized("https://memos.example.com/?x=1#top"))
        assertEquals("https://example.com/memos/", normalized("example.com/memos"))
        assertEquals("http://10.0.2.2:2283/", normalized("http://10.0.2.2:2283"))
        assertEquals(null, normalized(""))
        assertEquals(null, normalized("ftp://example.com"))
        assertEquals(null, normalized("https://"))
    }
}
