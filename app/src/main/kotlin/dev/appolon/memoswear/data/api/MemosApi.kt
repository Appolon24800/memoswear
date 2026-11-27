package dev.appolon.memoswear.data.api

import dev.appolon.memoswear.data.model.Attachment
import dev.appolon.memoswear.data.model.IdentityProvider
import dev.appolon.memoswear.data.model.InstanceGeneral
import dev.appolon.memoswear.data.model.InstanceProfile
import dev.appolon.memoswear.data.model.ListMemosResponse
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.MemoState
import dev.appolon.memoswear.data.model.RefreshResponse
import dev.appolon.memoswear.data.model.SignInResponse
import dev.appolon.memoswear.data.model.TagStats
import dev.appolon.memoswear.data.model.User
import dev.appolon.memoswear.data.model.UserGeneral
import dev.appolon.memoswear.data.model.UserSettingsEnvelope
import dev.appolon.memoswear.data.model.Visibility
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** JSON settings shared by the API client and the on-device stores. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

/** A memo being created. Only the fields the API accepts on creation are sent. */
@Serializable
data class NewMemo(
    val content: String,
    val visibility: Visibility,
    val parent: String? = null,
)

/** The fields to change on an existing memo; null fields are left untouched. */
@Serializable
data class MemoPatch(
    val name: String,
    val content: String? = null,
    val pinned: Boolean? = null,
    val state: MemoState? = null,
    val visibility: Visibility? = null,
) {
    /** The FieldMask paths for the fields that are set. */
    val updateMask: String
        get() = buildList {
            content?.let { add("content") }
            pinned?.let { add("pinned") }
            state?.let { add("state") }
            visibility?.let { add("visibility") }
        }.joinToString(",")
}

@Serializable
private data object EmptyRequest

@Serializable
private data class PasswordCredentials(val username: String, val password: String)

@Serializable
private data class SsoCredentials(
    val identityProvider: String,
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
)

@Serializable
private data class SignInRequest(
    val passwordCredentials: PasswordCredentials? = null,
    val ssoCredentials: SsoCredentials? = null,
)

@Serializable
private data class ListMemosRequest(
    val pageSize: Int? = null,
    val pageToken: String? = null,
    val filter: String? = null,
    val orderBy: String? = null,
    val state: MemoState? = null,
)

@Serializable
private data class CreateMemoRequest(val memo: NewMemo)

@Serializable
private data class UpdateMemoRequest(val memo: MemoPatch, val updateMask: String)

@Serializable
private data class NameRequest(val name: String)

@Serializable
private data class ParentRequest(val parent: String)

@Serializable
private data class UserStatsRequest(val name: String, val filter: String)

@Serializable
private data class IdentityProvidersEnvelope(val identityProviders: List<IdentityProvider> = emptyList())

@Serializable
private data class CurrentUserEnvelope(val user: User = User())

/**
 * Client for the Memos API. Every call is a Connect RPC: an HTTP POST to
 * `{base}/memos.api.{Service}/{Method}` with a JSON body. Authentication is applied by the
 * OkHttp client's interceptor.
 */
class MemosApi(
    private val http: OkHttpClient,
    val base: HttpUrl,
    private val json: Json = AppJson,
) {
    /** The base URL without a trailing slash, for display. */
    val displayUrl: String get() = base.toString().trimEnd('/')

    /** The instance's canonical origin. References to it are rewritten under [base]. */
    @Volatile var instanceOrigin: HttpUrl? = null

    /**
     * The URL to load for a file reference from a memo or an attachment. Memos writes references
     * as paths from the server root, or as URLs on the instance's own origin. When this app
     * reaches the server under a path of its own, both are resolved under that path.
     */
    fun fileUrl(pathOrUrl: String, thumbnail: Boolean = false): HttpUrl? {
        val resolved = when {
            pathOrUrl.startsWith("/") -> base.resolve(pathOrUrl.drop(1))
            else -> {
                val absolute = pathOrUrl.toHttpUrlOrNull()
                val origin = instanceOrigin
                if (absolute != null && origin != null && sameOrigin(absolute, origin)) {
                    val resource = absolute.encodedPath.trimStart('/')
                    base.resolve(if (absolute.encodedQuery == null) resource else "$resource?${absolute.encodedQuery}")
                } else {
                    absolute
                }
            }
        } ?: return null
        return resolved.newBuilder().apply {
            if (thumbnail) addQueryParameter("thumbnail", "true")
        }.build()
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    fun attachmentUrl(attachment: Attachment, thumbnail: Boolean = false): HttpUrl? =
        fileUrl("/file/attachments/${attachment.uid}/${attachment.filename.ifBlank { "file" }}", thumbnail)

    // ---- Instance and sign-in ---------------------------------------------------------------

    suspend fun instanceProfile(): InstanceProfile = call("InstanceService", "GetInstanceProfile", EmptyRequest)

    suspend fun instanceGeneral(): InstanceGeneral =
        InstanceGeneral.from(call("InstanceService", "GetInstanceSetting", NameRequest("instance/settings/GENERAL")))

    suspend fun identityProviders(): List<IdentityProvider> =
        callEmpty<IdentityProvidersEnvelope>("IdentityProviderService", "ListIdentityProviders").identityProviders

    suspend fun signInWithPassword(username: String, password: String): SignInResponse = call(
        "AuthService",
        "SignIn",
        SignInRequest(passwordCredentials = PasswordCredentials(username, password)),
    )

    suspend fun signInWithSso(identityProvider: String, code: String, redirectUri: String, codeVerifier: String): SignInResponse =
        call(
            "AuthService",
            "SignIn",
            SignInRequest(
                ssoCredentials = SsoCredentials(
                    identityProvider = identityProvider,
                    code = code,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier,
                ),
            ),
        )

    /**
     * Exchanges the refresh cookie for a new access token. It needs no bearer token, so it goes
     * through [refreshHttp], which only carries the cookie jar. Returns null when the cookie is
     * missing, expired or revoked.
     *
     * This is a Connect call on purpose: Memos forwards the cookie to this method from Connect
     * requests only, and answers REST ones as if no cookie had been sent.
     */
    suspend fun refreshToken(refreshHttp: OkHttpClient): RefreshResponse? {
        val url = base.resolve("memos.api.AuthService/RefreshToken") ?: throw IOException("Invalid server URL")
        val request = Request.Builder()
            .url(url)
            .post(EMPTY_BODY)
            .header("Accept", "application/json")
            .header("Connect-Protocol-Version", "1")
            .build()
        return withContext(Dispatchers.IO) {
            refreshHttp.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> json.decodeFromString(RefreshResponse.serializer(), text.ifBlank { "{}" })
                    response.code == 401 || response.code == 403 -> null
                    else -> throw parseError(response.code, text)
                }
            }
        }
    }

    suspend fun signOut() {
        callEmpty<UnitEnvelope>("AuthService", "SignOut")
    }

    suspend fun currentUser(): User = callEmpty<CurrentUserEnvelope>("AuthService", "GetCurrentUser").user

    suspend fun userGeneral(): UserGeneral =
        UserGeneral.from(call<ParentRequest, UserSettingsEnvelope>("UserService", "ListUserSettings", ParentRequest("users/-")))

    /** Tag counts over the memos the user can read. The API has no single-user name here, so the
     *  per-user list is reduced to the first entry, which is the signed-in user's own. */
    suspend fun tagStats(): TagStats {
        val response = call<UserStatsRequest, UserStatsListEnvelope>("UserService", "ListUserStats", UserStatsRequest("users/-", TOP_LEVEL))
        return TagStats(response.userStats.firstOrNull()?.tagCounts ?: emptyMap())
    }

    // ---- Memos ------------------------------------------------------------------------------

    suspend fun listMemos(
        pageSize: Int = PAGE_SIZE,
        pageToken: String? = null,
        filter: String? = null,
        orderBy: String? = null,
        state: MemoState? = null,
    ): ListMemosResponse = call(
        "MemoService",
        "ListMemos",
        ListMemosRequest(
            pageSize = pageSize,
            pageToken = pageToken?.takeIf { it.isNotBlank() },
            filter = filter,
            orderBy = orderBy,
            state = state,
        ),
    )

    suspend fun getMemo(name: String): Memo = call("MemoService", "GetMemo", NameRequest(name))

    suspend fun createMemo(content: String, visibility: Visibility, parent: String? = null): Memo =
        call("MemoService", "CreateMemo", CreateMemoRequest(NewMemo(content, visibility, parent)))

    suspend fun updateMemo(patch: MemoPatch): Memo =
        call("MemoService", "UpdateMemo", UpdateMemoRequest(patch, patch.updateMask))

    suspend fun deleteMemo(name: String) {
        call<NameRequest, UnitEnvelope>("MemoService", "DeleteMemo", NameRequest(name))
    }

    // ---- Transport --------------------------------------------------------------------------

    /** For methods whose request message has no fields. */
    private suspend inline fun <reified Res> callEmpty(service: String, method: String): Res =
        call<EmptyRequest, Res>(service, method, EmptyRequest)

    private suspend inline fun <reified Req, reified Res> call(service: String, method: String, request: Req): Res =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(serializer<Req>(), request).toRequestBody(JSON_MEDIA_TYPE)
            val url = base.resolve("memos.api.$service/$method") ?: throw IOException("Invalid server URL")
            val httpRequest = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/json")
                .header("Connect-Protocol-Version", "1")
                .build()

            http.newCall(httpRequest).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw parseError(response.code, text)
                try {
                    json.decodeFromString(serializer<Res>(), text.ifBlank { "{}" })
                } catch (e: SerializationException) {
                    // A 200 that is not what this server version promises: a proxy page, or an
                    // instance that is too old or too new for this client.
                    throw ApiException("bad_response", "Unexpected reply from the server", response.code)
                }
            }
        }

    /** Stands in for the empty message the API returns for calls without a result. */
    @Serializable
    private data object UnitEnvelope

    @Serializable
    private data class UserStatsEntry(val tagCounts: Map<String, Int> = emptyMap())

    @Serializable
    private data class UserStatsListEnvelope(val userStats: List<UserStatsEntry> = emptyList())

    companion object {
        const val PAGE_SIZE = 50
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val WHITESPACE = Regex("[\\r\\n\\t ]+")
        private val EMPTY_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)

        /** Pins first, then newest first: the order the home list uses. */
        const val HOME_ORDER: String = "pinned desc, create_time desc"

        /** Top-level memos only; the API returns comments in the same listing. */
        const val TOP_LEVEL: String = "parent == null"

        /** Quotes a string for a CEL filter expression. Line breaks become single spaces. */
        fun celString(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(WHITESPACE, " ")
            .trim() + "\""

        /** Filter for memos whose content contains the query. */
        fun contentFilter(query: String): String = "$TOP_LEVEL && content.contains(${celString(query)})"

        /** Filter for memos carrying a tag. Tags are stored without the leading hash. */
        fun tagFilter(tag: String): String = "$TOP_LEVEL && ${celString(tag.removePrefix("#"))} in tags"

        /** Filter for the comments on one memo. */
        fun commentsFilter(parent: String): String = "parent == ${celString(parent)}"

        fun parseError(status: Int, text: String): ApiException {
            val parsed = runCatching {
                val obj = AppJson.parseToJsonElement(text).jsonObject
                val code = (obj["code"] as? JsonPrimitive)?.content ?: ""
                val message = (obj["message"] as? JsonPrimitive)?.content ?: ""
                ApiException(code, message.ifBlank { "Request failed ($status)" }, status)
            }
            return parsed.getOrElse { ApiException.http(status) }
        }
    }
}
