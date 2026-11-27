package dev.appolon.memoswear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.appolon.memoswear.data.api.AppJson
import dev.appolon.memoswear.data.model.User
import dev.appolon.memoswear.data.model.Visibility
import dev.appolon.memoswear.data.model.parseVisibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "memoswear_settings")

/** What the app remembers between runs. Kept behind an interface so the session and the notes can
 *  be exercised with an in-memory stand-in. */
interface AccountStore {
    /** Visibility given to new notes created on this watch. Starts from the web client's setting. */
    val defaultVisibility: Flow<Visibility>

    suspend fun account(): Account?

    suspend fun saveAccount(account: Account)

    /** Forgets the account but keeps the server address, so the next sign-in starts one step in. */
    suspend fun clearAccount()

    suspend fun setDefaultVisibility(visibility: Visibility)

    /** False until the user, or the first sync with the web client's setting, has chosen one. */
    suspend fun defaultVisibilityIsSet(): Boolean

    data class Account(
        val serverUrl: String,
        val mode: String,
        val personalAccessToken: String?,
        val user: User?,
        val instanceTitle: String = "",
        val instanceVersion: String = "",
        val instanceUrl: String = "",
    )
}

/** Saved account and preferences, in the app's private storage. The access token, when there is
 *  one, is a long-lived personal access token that the user typed in on this watch. */
class SettingsStore(private val context: Context) : AccountStore {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val MODE = stringPreferencesKey("auth_mode")
        val PERSONAL_ACCESS_TOKEN = stringPreferencesKey("personal_access_token")
        val USER = stringPreferencesKey("user")
        val DEFAULT_VISIBILITY = stringPreferencesKey("default_visibility")
        val INSTANCE_TITLE = stringPreferencesKey("instance_title")
        val INSTANCE_VERSION = stringPreferencesKey("instance_version")
        val INSTANCE_URL = stringPreferencesKey("instance_url")
    }

    override val defaultVisibility: Flow<Visibility> = context.settingsDataStore.data.map { parseVisibility(it[Keys.DEFAULT_VISIBILITY]) }

    override suspend fun defaultVisibilityIsSet(): Boolean =
        context.settingsDataStore.data.first().contains(Keys.DEFAULT_VISIBILITY)

    override suspend fun setDefaultVisibility(visibility: Visibility) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_VISIBILITY] = visibility.name }
    }

    override suspend fun account(): AccountStore.Account? {
        val prefs = context.settingsDataStore.data.first()
        val url = prefs[Keys.SERVER_URL] ?: return null
        val user = prefs[Keys.USER]?.let { runCatching { AppJson.decodeFromString(User.serializer(), it) }.getOrNull() }
        return AccountStore.Account(
            serverUrl = url,
            mode = prefs[Keys.MODE] ?: MODE_COOKIES,
            personalAccessToken = prefs[Keys.PERSONAL_ACCESS_TOKEN],
            user = user,
            instanceTitle = prefs[Keys.INSTANCE_TITLE].orEmpty(),
            instanceVersion = prefs[Keys.INSTANCE_VERSION].orEmpty(),
            instanceUrl = prefs[Keys.INSTANCE_URL].orEmpty(),
        )
    }

    override suspend fun saveAccount(account: AccountStore.Account) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = account.serverUrl
            prefs[Keys.MODE] = account.mode
            if (account.personalAccessToken != null) {
                prefs[Keys.PERSONAL_ACCESS_TOKEN] = account.personalAccessToken
            } else {
                prefs.remove(Keys.PERSONAL_ACCESS_TOKEN)
            }
            if (account.user != null) {
                prefs[Keys.USER] = AppJson.encodeToString(User.serializer(), account.user)
            } else {
                prefs.remove(Keys.USER)
            }
            prefs[Keys.INSTANCE_TITLE] = account.instanceTitle
            prefs[Keys.INSTANCE_VERSION] = account.instanceVersion
            prefs[Keys.INSTANCE_URL] = account.instanceUrl
        }
    }

    override suspend fun clearAccount() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.MODE)
            prefs.remove(Keys.PERSONAL_ACCESS_TOKEN)
            prefs.remove(Keys.USER)
        }
    }

    companion object {
        const val MODE_COOKIES = "cookies"
        const val MODE_TOKEN = "token"
    }
}
