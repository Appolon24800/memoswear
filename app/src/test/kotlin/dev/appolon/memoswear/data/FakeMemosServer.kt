package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.AppJson
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl

/**
 * A stand-in for a Memos server with the routes this app uses. It signs users in the way the
 * real server does, a short-lived access token plus a rotating refresh cookie, keeps memos in
 * memory, and records every request it receives.
 */
class FakeMemosServer : AutoCloseable {
    private var server = MockWebServer()
    private val lock = Any()

    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** Memos by resource name, as the server would return them. */
    val memos = LinkedHashMap<String, JsonObject>()

    @Volatile var tokenLifetimeSeconds: Long = 900
    /** When set, API calls answer with this HTTP status. */
    @Volatile var failApiWith: Int? = null
    /** Makes the next create fail the way a rejected note does. */
    @Volatile var rejectNextCreate = false
    @Volatile var rejectRefresh = false
    /** Forgets every access token issued so far, once; the refresh cookie keeps working. */
    @Volatile var invalidateTokens = false

    private val accessTokens = ConcurrentHashMap.newKeySet<String>()
    private val refreshTokens = ConcurrentHashMap.newKeySet<String>()
    private var tokenCounter = 0
    private var memoCounter = 0

    val baseUrl: HttpUrl get() = server.url("/")
    val port: Int get() = server.port


    /** Refuses every request, as when the watch has no network. New connections are hung up on. */
    @Volatile var online = true

    fun goOffline() {
        online = false
    }

    fun comeOnline() {
        online = true
    }

    override fun close() {
        runCatching { server.close() }
    }

    fun requestsTo(path: String): List<RecordedRequest> = synchronized(requests) { requests.filter { it.url.encodedPath == path } }

    fun forgetRequests() = synchronized(requests) { requests.clear() }

    private fun nextId(prefix: String): String = synchronized(lock) { "$prefix${++memoCounter}" }

    private fun issueTokens(): Pair<String, String> = synchronized(lock) {
        val access = "at-${++tokenCounter}"
        val refresh = "rt-${tokenCounter}"
        accessTokens += access
        refreshTokens += refresh
        access to refresh
    }

    /** The user for the request's bearer token, or null when the token is missing or unknown. */
    private fun authenticate(request: RecordedRequest): String? {
        if (invalidateTokens) {
            accessTokens.clear()
            invalidateTokens = false
        }
        val header = request.headers["Authorization"] ?: return null
        val token = header.removePrefix("Bearer ")
        return if (token != header && accessTokens.contains(token)) "users/watch" else null
    }

    private fun ok(content: String, vararg headers: Pair<String, String>): MockResponse =
        document(200, content, *headers)

    private fun document(status: Int, content: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder()
            .code(status)
            .addHeader("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .body(content)
            .build()

    /** The error document the real server sends for each of its two protocols. */
    private fun connectError(status: Int, code: String, message: String): MockResponse =
        document(status, """{"code":"$code","message":"$message"}""")

    private fun restError(status: Int, code: Int, message: String): MockResponse =
        document(status, """{"code":$code,"message":"$message","details":[]}""")

    private fun memoJson(name: String, content: String, visibility: String, state: String, pinned: Boolean): JsonObject = buildJsonObject {
        put("name", name)
        put("creator", "users/watch")
        put("content", content)
        put("visibility", visibility)
        put("state", state)
        if (pinned) put("pinned", true)
        put(
            "tags",
            kotlinx.serialization.json.buildJsonArray {
                Regex("(?<![\\p{L}\\p{N}/#&])#([\\p{L}\\p{N}_-]+(?:/[\\p{L}\\p{N}_-]+)*)").findAll(content)
                    .flatMap { match -> match.groupValues[1].split("/").runningReduce { acc, part -> "$acc/$part" } }
                    .distinct().forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "property",
            buildJsonObject {
                put("hasTaskList", content.lines().any { Regex("""^\s*[-*+]\s+\[[ xX]]""").containsMatchIn(it) })
                put("hasLink", content.contains("http"))
            },
        )
        put("snippet", content.take(80))
        put("createTime", Instant.parse("2026-11-26T23:47:58Z").plusSeconds((memoCounter - 1) * 60L).toString())
        put("updateTime", Instant.parse("2026-11-26T23:47:58Z").plusSeconds((memoCounter - 1) * 60L).toString())
    }

    private val router: Dispatcher by lazy {
        object : Dispatcher() {
        // Socket effects are read from peek(), not from the dispatched response.
        override fun peek(): MockResponse =
            if (online) super.peek() else MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!online) return connectError(503, "unavailable", "The service is currently unavailable.")
            requests += request
            val path = request.url.encodedPath
            val body = runCatching { AppJson.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

            fun optionalText(key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

            return when (path) {
                "/memos.api.InstanceService/GetInstanceProfile" ->
                    ok("""{"version":"26.11.2","instanceUrl":"$baseUrl","accessMode":"INSTANCE_ACCESS_MODE_PRIVATE"}""")

                "/memos.api.InstanceService/GetInstanceSetting" ->
                    ok("""{"generalSetting":{"customProfile":{"title":"Test Memos"},"disallowPasswordAuth":true}}""")

                "/memos.api.IdentityProviderService/ListIdentityProviders" -> ok("""{"identityProviders":[]}""")

                "/memos.api.AuthService/SignIn" -> {
                    val credentials = body["passwordCredentials"] as? JsonObject
                    val username = (credentials?.get("username") as? JsonPrimitive)?.content
                    val password = (credentials?.get("password") as? JsonPrimitive)?.content
                    if (username != "watch" || password != "watchpass123") {
                        return connectError(400, "invalid_argument", "unmatched username and password")
                    }
                    val (access, refresh) = issueTokens()
                    val expire = Instant.now().plusSeconds(tokenLifetimeSeconds)
                    ok(
                        """{"user":{"name":"users/watch","username":"watch","role":"ADMIN","state":"NORMAL"},"accessToken":"$access","accessTokenExpireTime":"$expire"}""",
                        "Set-Cookie" to "memos_refresh=$refresh; Path=/; HttpOnly; SameSite=Lax",
                    )
                }

                // Like the real server: the cookie reaches this method over Connect only.
                "/memos.api.AuthService/RefreshToken" -> {
                    val cookie = request.headers["Cookie"].orEmpty()
                    val presented = Regex("memos_refresh=([^;\\s]+)").find(cookie)?.groupValues?.get(1)
                    if (rejectRefresh || presented == null || !refreshTokens.contains(presented)) {
                        return connectError(401, "unauthenticated", if (presented == null) "refresh token not found" else "invalid refresh token: refresh token revoked")
                    }
                    // Rotation: the presented cookie stops working, a new one replaces it.
                    refreshTokens -= presented
                    val (access, refresh) = issueTokens()
                    val expire = Instant.now().plusSeconds(tokenLifetimeSeconds)
                    ok(
                        """{"accessToken":"$access","accessTokenExpireTime":"$expire"}""",
                        "Set-Cookie" to "memos_refresh=$refresh; Path=/; HttpOnly; SameSite=Lax",
                    )
                }

                "/memos.api.AuthService/GetCurrentUser", "/api/auth/me" ->
                    if (authenticate(request) != null) ok("""{"user":{"name":"users/watch","username":"watch","role":"ADMIN","state":"NORMAL"}}""")
                    else restError(401, 16, "authentication required")

                // The REST gateway of the real server hands no cookie to the method.
                "/api/auth/refresh" -> restError(401, 16, "refresh token not found")

                "/memos.api.AuthService/SignOut", "/api/auth/signout" ->
                    if (authenticate(request) != null) ok("{}") else restError(401, 16, "authentication required")

                else -> {
                    if (authenticate(request) == null) return@dispatch restError(401, 16, "authentication required")
                    val status = failApiWith
                    if (status != null) return@dispatch connectError(status, "unavailable", "The service is currently unavailable.")
                    api(path, body, ::optionalText)
                }
            }
        }
    }
    }

    init {
        // A personal access token the "user" made on another device.
        accessTokens += "memos_pat_known"
        server.dispatcher = router
        server.start()
    }

    private fun api(path: String, body: JsonObject, text: (String) -> String?): MockResponse {
        return when (path) {
        "/memos.api.MemoService/ListMemos" -> {
            val state = text("state") ?: "NORMAL"
            val items = memos.values.filter { (it["state"] as? JsonPrimitive)?.content == state }
            ok(buildJsonObject { put("memos", kotlinx.serialization.json.buildJsonArray { items.forEach { add(it) } }) }.toString())
        }

        "/memos.api.MemoService/CreateMemo" -> {
            if (rejectNextCreate) {
                rejectNextCreate = false
                return connectError(400, "invalid_argument", "content length exceeds limit")
            }
            val memo = body["memo"] as? JsonObject ?: return connectError(400, "invalid_argument", "memo is required")
            val content = (memo["content"] as? JsonPrimitive)?.content.orEmpty()
            val visibility = (memo["visibility"] as? JsonPrimitive)?.content ?: "PRIVATE"
            val name = "memos/${nextId("m")}"
            val created = memoJson(name, content, visibility, "NORMAL", pinned = false)
            memos[name] = created
            ok(created.toString())
        }

        "/memos.api.MemoService/UpdateMemo" -> {
            val memo = body["memo"] as? JsonObject ?: return connectError(400, "invalid_argument", "memo is required")
            val name = (memo["name"] as? JsonPrimitive)?.content.orEmpty()
            val current = memos[name] ?: return connectError(404, "not_found", "memo not found")
            val mask = (body["updateMask"] as? JsonPrimitive)?.content.orEmpty().split(',')
            val next = current.toMutableMap()
            if ("content" in mask) next["content"] = JsonPrimitive((memo["content"] as? JsonPrimitive)?.content.orEmpty())
            if ("pinned" in mask) next["pinned"] = JsonPrimitive((memo["pinned"] as? JsonPrimitive)?.content == "true")
            if ("state" in mask) next["state"] = JsonPrimitive((memo["state"] as? JsonPrimitive)?.content ?: "NORMAL")
            if ("visibility" in mask) next["visibility"] = JsonPrimitive((memo["visibility"] as? JsonPrimitive)?.content ?: "PRIVATE")
            val updated = JsonObject(next)
            memos[name] = updated
            ok(updated.toString())
        }

        "/memos.api.MemoService/DeleteMemo" -> {
            memos.remove(text("name"))
            ok("{}")
        }

        "/memos.api.UserService/ListUserSettings" ->
            ok("""{"userSettings":[{"name":"users/watch/settings/GENERAL","generalSetting":{"locale":"en","memoVisibility":"PROTECTED"}}]}""")

        "/memos.api.UserService/ListUserStats" -> ok("""{"userStats":[{"tagCounts":{"idea":2,"work":1}}]}""")

            else -> connectError(404, "not_found", "no such method: $path")
        }
    }
}
