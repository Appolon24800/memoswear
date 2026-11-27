package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.AppJson
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

@Serializable
private data class StoredCookies(val cookies: List<StoredCookie> = emptyList())

/**
 * CookieJar that keeps cookies in an app-private file, so the refresh cookie that Memos sets on
 * sign-in survives process death. Memos rotates that cookie on every refresh, so each change is
 * written straight away; the file holds one small JSON document.
 */
class PersistedCookieJar(private val file: File, private val json: Json = AppJson) : CookieJar {
    private val lock = Any()
    private var cookies: MutableList<StoredCookie> = load()

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val expired = cookies.any { it.expiresAt <= now }
        if (expired) {
            cookies.removeAll { it.expiresAt <= now }
            persist()
        }
        cookies.mapNotNull { it.toCookie() }.filter { it.matches(url) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                // A cookie that already expired is the server's way of deleting it.
                if (cookie.expiresAt > now) this.cookies += cookie.toStored()
            }
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies = mutableListOf()
            persist()
        }
    }

    fun names(): List<String> = synchronized(lock) { cookies.map { it.name } }

    private fun load(): MutableList<StoredCookie> = runCatching {
        if (file.isFile) json.decodeFromString(StoredCookies.serializer(), file.readText()).cookies.toMutableList() else mutableListOf()
    }.getOrDefault(mutableListOf())

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(StoredCookies.serializer(), StoredCookies(cookies)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}

private fun Cookie.toStored(): StoredCookie = StoredCookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    expiresAt = if (persistent) expiresAt else Long.MAX_VALUE,
    secure = secure,
    httpOnly = httpOnly,
    hostOnly = hostOnly,
)

private fun StoredCookie.toCookie(): Cookie? = runCatching {
    val builder = Cookie.Builder().name(name).value(value).path(path)
    if (hostOnly) {
        builder.hostOnlyDomain(domain.removePrefix("."))
    } else {
        builder.domain(if (domain.startsWith(".")) domain else ".$domain")
    }
    if (expiresAt != Long.MAX_VALUE) builder.expiresAt(expiresAt)
    if (secure) builder.secure()
    if (httpOnly) builder.httpOnly()
    builder.build()
}.getOrNull()
