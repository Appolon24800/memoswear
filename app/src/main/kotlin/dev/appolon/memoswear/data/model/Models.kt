package dev.appolon.memoswear.data.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who can read a memo. Wire names are the ones used by the Memos API. */
@Serializable
enum class Visibility {
    @SerialName("VISIBILITY_UNSPECIFIED")
    UNSPECIFIED,

    @SerialName("PRIVATE")
    PRIVATE,

    @SerialName("PROTECTED")
    PROTECTED,

    @SerialName("PUBLIC")
    PUBLIC,

    @SerialName("SPACE")
    SPACE,
}

/** Lifecycle state shared by memos and users. */
@Serializable
enum class MemoState {
    @SerialName("STATE_UNSPECIFIED")
    UNSPECIFIED,

    @SerialName("NORMAL")
    NORMAL,

    @SerialName("ARCHIVED")
    ARCHIVED,
}

@Serializable
data class MemoProperty(
    val title: String = "",
    val hasLink: Boolean = false,
    val hasCode: Boolean = false,
    val hasTaskList: Boolean = false,
    val hasIncompleteTasks: Boolean = false,
    val hasInlineImage: Boolean = false,
)

@Serializable
data class Attachment(
    val name: String = "",
    val filename: String = "",
    val type: String = "",
    @Serializable(with = FlexibleLongSerializer::class)
    val size: Long = 0,
    val memo: String? = null,
    val externalLink: String? = null,
    val createTime: String = "",
) {
    val uid: String get() = name.substringAfterLast('/')
    val isImage: Boolean get() = type.startsWith("image/")
}

@Serializable
data class Memo(
    val name: String,
    val creator: String = "",
    val content: String = "",
    val visibility: Visibility = Visibility.PRIVATE,
    val state: MemoState = MemoState.NORMAL,
    val pinned: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val tags: List<String> = emptyList(),
    val property: MemoProperty = MemoProperty(),
    val snippet: String = "",
    val createTime: String = "",
    val updateTime: String = "",
    val commentCount: Int = 0,
    val parent: String? = null,
) {
    val uid: String get() = name.substringAfterLast('/')
    val isLocal: Boolean get() = name.startsWith(LOCAL_PREFIX)
    val isArchived: Boolean get() = state == MemoState.ARCHIVED

    val createdInstant: Instant? by lazy { parseInstant(createTime) }
    val updatedInstant: Instant? by lazy { parseInstant(updateTime) }

    /** True when the server reports an edit after creation. */
    val isEdited: Boolean
        get() {
            val created = createdInstant ?: return false
            val updated = updatedInstant ?: return false
            return updated.epochSecond - created.epochSecond >= EDIT_WINDOW_SECONDS
        }

    val taskCounts: Pair<Int, Int>?
        get() {
            if (!property.hasTaskList) return null
            var done = 0
            var total = 0
            for (line in content.lines()) {
                val match = TASK_LINE.matchEntire(line) ?: continue
                total++
                if (match.groupValues[1] != " ") done++
            }
            return if (total == 0) null else done to total
        }

    val imageAttachments: List<Attachment> get() = attachments.filter { it.isImage }

    /** Image URLs referenced by Markdown image syntax, in document order. */
    val inlineImageUrls: List<String>
        get() = INLINE_IMAGE.findAll(content).map { it.groupValues[2] }.toList()

    companion object {
        const val LOCAL_PREFIX = "local/"
        private const val EDIT_WINDOW_SECONDS = 2L
        private val TASK_LINE = Regex("""^\s*[-*+]\s+\[([ xX])]\s*(.*)$""")
        private val INLINE_IMAGE = Regex("""!\[([^\]]*)]\(\s*(\S+?)\s*\)""")

        fun parseInstant(value: String): Instant? =
            runCatching { Instant.parse(value.trim()) }.getOrNull()
    }
}

@Serializable
data class User(
    val name: String = "",
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val role: String = "USER",
    val state: MemoState = MemoState.NORMAL,
) {
    val displayNameOrUsername: String get() = displayName.ifBlank { username.ifBlank { name.substringAfterLast('/') } }
}

@Serializable
data class InstanceProfile(
    val version: String = "",
    val instanceUrl: String = "",
    val accessMode: String = "",
    val needsSetup: Boolean = false,
)

@Serializable
internal data class CustomProfile(val title: String = "")

@Serializable
internal data class GeneralInstanceSetting(
    val customProfile: CustomProfile? = null,
    val disallowPasswordAuth: Boolean = false,
)

@Serializable
internal data class InstanceSettingEnvelope(val generalSetting: GeneralInstanceSetting? = null)

/** The public part of the instance GENERAL setting that the sign-in screen needs. */
data class InstanceGeneral(
    val title: String,
    val passwordAuthAllowed: Boolean,
) {
    companion object {
        internal fun from(json: InstanceSettingEnvelope?): InstanceGeneral = InstanceGeneral(
            title = json?.generalSetting?.customProfile?.title.orEmpty().ifBlank { "Memos" },
            passwordAuthAllowed = json?.generalSetting?.disallowPasswordAuth != true,
        )
    }
}

@Serializable
data class OAuth2Config(
    val clientId: String = "",
    val authUrl: String = "",
    val scopes: List<String> = emptyList(),
)

@Serializable
data class IdentityProviderConfigEnvelope(val oauth2Config: OAuth2Config? = null)

/** An OAuth2 identity provider the instance offers for sign-in. */
@Serializable
data class IdentityProvider(
    val name: String = "",
    val title: String = "",
    val type: String = "",
    val config: IdentityProviderConfigEnvelope? = null,
) {
    val isOAuth2: Boolean get() = type == "OAUTH2" && oauth2 != null

    val oauth2: OAuth2Config? get() = config?.oauth2Config?.takeIf { it.clientId.isNotBlank() && it.authUrl.isNotBlank() }
}

@Serializable
data class SignInResponse(
    val user: User = User(),
    val accessToken: String = "",
    val accessTokenExpireTime: String = "",
)

@Serializable
data class RefreshResponse(
    val accessToken: String = "",
    val accessTokenExpireTime: String = "",
)

@Serializable
private data class CurrentUserEnvelope(val user: User = User())

@Serializable
data class ListMemosResponse(
    val memos: List<Memo> = emptyList(),
    val nextPageToken: String = "",
)

@Serializable
internal data class TagCountsEnvelope(val tagCounts: Map<String, Int> = emptyMap())

/** Tag usage of the signed-in user's readable memos. */
data class TagStats(val counts: Map<String, Int>)

@Serializable
internal data class GeneralUserSetting(val memoVisibility: String = "")

@Serializable
internal data class UserSettingEnvelope(val generalSetting: GeneralUserSetting? = null)

@Serializable
internal data class UserSettingsEnvelope(val userSettings: List<UserSettingEnvelope> = emptyList())

/** The memo visibility the user configured in the web client. */
data class UserGeneral(val defaultVisibility: Visibility) {
    companion object {
        internal fun from(json: UserSettingsEnvelope?): UserGeneral = UserGeneral(
            defaultVisibility = parseVisibility(json?.userSettings?.firstNotNullOfOrNull { it.generalSetting?.memoVisibility }),
        )
    }
}

fun parseVisibility(value: String?): Visibility =
    when (value?.trim()?.uppercase()) {
        "PRIVATE" -> Visibility.PRIVATE
        "PROTECTED" -> Visibility.PROTECTED
        "PUBLIC" -> Visibility.PUBLIC
        "SPACE" -> Visibility.SPACE
        else -> Visibility.PRIVATE
    }
