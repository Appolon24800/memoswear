package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.ApiException
import dev.appolon.memoswear.data.api.MemosApi
import dev.appolon.memoswear.data.api.OAuth
import dev.appolon.memoswear.data.model.IdentityProvider
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.User
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** A sign-in attempt the user has to correct, as opposed to a transport or server failure. */
class SignInException(message: String) : IOException(message)

/**
 * The signed-in account and the credentials that go with it.
 *
 * Memos signs a client in with a 15-minute access token plus a refresh cookie that rotates on
 * every use. The cookie lives in [PersistedCookieJar]; the access token stays in memory and is
 * renewed on demand by [authInterceptor], with one refresh for concurrent callers. The
 * alternative is a personal access token, which is sent as-is and never needs renewing.
 */
class SessionStore(
    private val settings: AccountStore,
    filesDir: File,
    private val scope: CoroutineScope,
    /** Called when stored notes must go: the user signed out, or named a different server. An
     *  expired session keeps them, so notes written offline survive signing in again. */
    private val onForget: suspend () -> Unit = {},
) {
    sealed interface State {
        /** The saved session is being checked. */
        data object Restoring : State

        data object SignedOut : State
        data class SignedIn(val user: User) : State
    }

    /** Public facts about the instance the app points at. */
    data class Instance(
        val title: String = "Memos",
        val version: String = "",
        /** The instance's own canonical URL, which identity providers know as the redirect origin. */
        val url: String = "",
        val passwordAuthAllowed: Boolean = true,
    )

    private sealed interface Credential {
        data class Accepted(val token: String) : Credential
        data object Rejected : Credential
        data object Unreachable : Credential
    }

    private sealed interface Check {
        data class SignedIn(val user: User) : Check
        data object Rejected : Check
        data object Refused : Check
        data object Unreachable : Check
    }

    private val _state = MutableStateFlow<State>(State.Restoring)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _instance = MutableStateFlow(Instance())
    val instance: StateFlow<Instance> = _instance.asStateFlow()

    val cookieJar = PersistedCookieJar(File(filesDir, "session-cookies.json"))

    private val refreshLock = Any()
    private val abandoning = AtomicBoolean(false)

    private val instanceOrigin: HttpUrl?
        get() = _instance.value.url.toHttpUrlOrNull()

    /** Publishes what is known about the instance, to the app and to its API client. */
    private fun setInstance(instance: Instance) {
        _instance.value = instance
        cachedApi?.instanceOrigin = instance.url.toHttpUrlOrNull()
    }

    @Volatile private var mode: String? = null
    @Volatile private var personalAccessToken: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenValidUntil: Instant? = null
    @Volatile private var base: HttpUrl? = null
    @Volatile private var cachedApi: MemosApi? = null

    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** Adds the credential to a request and renews it once if the server says it has expired. */
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = accessTokenForRequest()
        val request = token?.let { original.newBuilder().header("Authorization", "Bearer $it").build() } ?: original
        val response = chain.proceed(request)
        val personal = personalAccessToken
        if (response.code != 401 || mode == null) {
            return@Interceptor response
        }
        response.close()
        if (personal != null) {
            // Nothing can renew a personal access token: it was revoked or has expired.
            onUnauthenticated()
            return@Interceptor chain.proceed(original)
        }
        when (val renewed = renewAccessToken()) {
            is Credential.Accepted -> chain.proceed(original.newBuilder().header("Authorization", "Bearer ${renewed.token}").build())
            Credential.Rejected -> {
                onUnauthenticated()
                chain.proceed(original)
            }
            // Offline, or the server is failing: report that, and keep the session for later.
            Credential.Unreachable -> chain.proceed(request)
        }
    }

    /** Client for API calls. It authenticates as the signed-in user when there is one. */
    val client: OkHttpClient = plainClient.newBuilder().addInterceptor(authInterceptor).build()

    val isSignedIn: Boolean get() = _state.value is State.SignedIn
    val user: User? get() = (_state.value as? State.SignedIn)?.user

    /** API bound to the current server. Null until a server address is set. */
    val api: MemosApi?
        get() {
            val url = base ?: return null
            return cachedApi?.takeIf { it.base == url }
                ?: MemosApi(client, url).also {
                    it.instanceOrigin = instanceOrigin
                    cachedApi = it
                }
        }

    val displayUrl: String get() = base?.toString()?.trimEnd('/').orEmpty()

    /** Server address to prefill the sign-in form with. */
    suspend fun lastServerUrl(): String = settings.account()?.serverUrl.orEmpty()

    /** Re-opens the saved session at process start. */
    fun restore() {
        scope.launch {
            val account = runCatching { settings.account() }.getOrNull()
            val url = account?.let { normalizeServerUrl(it.serverUrl) }
            if (account == null || url == null) {
                _state.value = State.SignedOut
                return@launch
            }
            base = url
            mode = account.mode
            personalAccessToken = account.personalAccessToken
            setInstance(
                Instance(
                    title = account.instanceTitle.ifBlank { "Memos" },
                    version = account.instanceVersion,
                    url = account.instanceUrl,
                ),
            )
            when (val check = checkCurrentUser()) {
                is Check.SignedIn -> finishSignIn(check.user)
                Check.Rejected -> abandonSession()
                Check.Refused, Check.Unreachable ->
                    // Keep the account so saved notes stay readable; the next online call retries.
                    _state.value = account.user?.let { State.SignedIn(it) } ?: State.SignedOut
            }
            if (_state.value is State.SignedIn) loadInstanceFacts()
        }
    }

    /** Points the app at a server and reads its public facts. Any earlier session is dropped. */
    suspend fun probe(serverInput: String): Instance {
        val url = normalizeServerUrl(serverInput) ?: throw SignInException("Enter a valid server address")
        val previous = settings.account()?.let { normalizeServerUrl(it.serverUrl) }
        abandonSession()
        if (previous != null && previous != url) onForget()
        base = url
        cachedApi = null
        val api = this.api ?: throw SignInException("Enter a valid server address")
        val profile = try {
            api.instanceProfile()
        } catch (e: IOException) {
            base = null
            throw e
        }
        setInstance(
            Instance(
                version = profile.version,
                url = profile.instanceUrl.ifBlank { api.displayUrl },
            ),
        )
        loadInstanceFacts()
        return _instance.value
    }

    /** Reads the instance title and sign-in policy. Failure here is not fatal. */
    private suspend fun loadInstanceFacts() {
        val general = runCatching { api?.instanceGeneral() }.getOrNull()
        setInstance(
            _instance.value.copy(
                title = general?.title ?: "Memos",
                passwordAuthAllowed = general?.passwordAuthAllowed ?: true,
            ),
        )
        if (general != null) {
            settings.account()?.let { settings.saveAccount(it.copy(instanceTitle = general.title, instanceUrl = _instance.value.url)) }
        }
    }

    private suspend fun checkCurrentUser(): Check {
        val api = api ?: return Check.Rejected
        return try {
            Check.SignedIn(api.currentUser())
        } catch (e: ApiException) {
            if (e.isUnauthenticated) Check.Rejected else Check.Refused
        } catch (e: IOException) {
            Check.Unreachable
        }
    }

    suspend fun identityProviders(): List<IdentityProvider> =
        api?.identityProviders() ?: emptyList()

    suspend fun signInWithPassword(username: String, password: String): User {
        val api = api ?: throw SignInException("No server selected")
        if (username.isBlank() || password.isEmpty()) throw SignInException("Enter your username and password")
        val response = api.signInWithPassword(username.trim(), password)
        accessToken = response.accessToken
        accessTokenValidUntil = validityStart(response.accessTokenExpireTime)
        return finishSignIn(response.user)
    }

    suspend fun signInWithToken(token: String): User {
        val api = api ?: throw SignInException("No server selected")
        val cleaned = token.trim()
        if (!cleaned.startsWith(PERSONAL_ACCESS_TOKEN_PREFIX)) throw SignInException("That is not a Memos access token")
        personalAccessToken = cleaned
        val user = try {
            api.currentUser()
        } catch (e: ApiException) {
            personalAccessToken = null
            if (e.isUnauthenticated) throw SignInException("The server rejected that access token")
            throw e
        } catch (e: IOException) {
            personalAccessToken = null
            throw e
        }
        return finishSignIn(user)
    }

    suspend fun beginSso(provider: IdentityProvider): OAuth.Challenge =
        OAuth.begin(provider, _instance.value.url)
            ?: throw SignInException("${provider.title.ifBlank { "This provider" }} is not set up for OAuth sign-in")

    suspend fun completeSso(provider: IdentityProvider, challenge: OAuth.Challenge, code: String): User {
        val api = api ?: throw SignInException("No server selected")
        val response = try {
            api.signInWithSso(provider.name, code, challenge.redirectUri, challenge.codeVerifier)
        } catch (e: ApiException) {
            throw SignInException(e.message.ifBlank { "Sign-in failed" })
        }
        accessToken = response.accessToken
        accessTokenValidUntil = validityStart(response.accessTokenExpireTime)
        return finishSignIn(response.user)
    }

    /** Signs out on the server and forgets the account and everything saved for it. */
    suspend fun signOut() {
        runCatching { api?.signOut() }
        abandonSession()
        onForget()
    }

    /** Called when the API refused credentials that a refresh could not rescue. */
    fun onUnauthenticated() {
        if (_state.value !is State.SignedIn) return
        if (!abandoning.compareAndSet(false, true)) return
        scope.launch {
            try {
                abandonSession()
            } finally {
                abandoning.set(false)
            }
        }
    }

    private suspend fun finishSignIn(user: User): User {
        val token = personalAccessToken
        mode = if (token != null) SettingsStore.MODE_TOKEN else SettingsStore.MODE_COOKIES
        val current = _instance.value
        settings.saveAccount(
            AccountStore.Account(
                serverUrl = base?.toString().orEmpty(),
                mode = mode!!,
                personalAccessToken = token,
                user = user,
                instanceTitle = current.title,
                instanceVersion = current.version,
                instanceUrl = current.url,
            ),
        )
        _state.value = State.SignedIn(user)
        return user
    }

    /** Forgets the account and its credentials. The server address stays for the next sign-in. */
    private suspend fun abandonSession() {
        mode = null
        personalAccessToken = null
        accessToken = null
        accessTokenValidUntil = null
        cookieJar.clear()
        settings.clearAccount()
        _state.value = State.SignedOut
    }

    private fun validityStart(expireTime: String): Instant? =
        Memo.parseInstant(expireTime)?.minusSeconds(TOKEN_SKEW_SECONDS)

    private fun accessTokenForRequest(): String? {
        personalAccessToken?.let { return it }
        if (mode == null) return null
        synchronized(refreshLock) {
            val now = Instant.now()
            val current = accessToken
            if (current != null && accessTokenValidUntil?.isAfter(now) == true) return current
            return when (val renewed = renewAccessTokenLocked()) {
                is Credential.Accepted -> renewed.token
                Credential.Rejected -> {
                    onUnauthenticated()
                    null
                }
                Credential.Unreachable -> current
            }
        }
    }

    private fun renewAccessToken(): Credential = synchronized(refreshLock) { renewAccessTokenLocked() }

    /** Must run while holding [refreshLock], so that one refresh serves every waiting caller. */
    private fun renewAccessTokenLocked(): Credential {
        personalAccessToken?.let { return Credential.Accepted(it) }
        val api = api ?: return Credential.Rejected
        return runBlocking {
            try {
                val response = api.refreshToken(plainClient)
                if (response == null) {
                    Credential.Rejected
                } else {
                    accessToken = response.accessToken
                    accessTokenValidUntil = validityStart(response.accessTokenExpireTime)
                    Credential.Accepted(response.accessToken)
                }
            } catch (e: IOException) {
                Credential.Unreachable
            }
        }
    }

    companion object {
        const val PERSONAL_ACCESS_TOKEN_PREFIX = "memos_pat_"
        private const val TOKEN_SKEW_SECONDS = 30L

        /**
         * Accepts what a person types for a server address and returns the base every API path is
         * resolved against. The path is kept for instances behind a reverse proxy on a sub-path,
         * and always ends in a slash so relative resolution stays inside it.
         */
        fun normalizeServerUrl(input: String): HttpUrl? {
            var text = input.trim()
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "https://$text"
            val url = text.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" && url.scheme != "http") return null
            if (url.host.isBlank()) return null
            return url.newBuilder()
                .query(null)
                .fragment(null)
                .encodedPath(url.encodedPath.trimEnd('/') + "/")
                .build()
        }
    }
}
