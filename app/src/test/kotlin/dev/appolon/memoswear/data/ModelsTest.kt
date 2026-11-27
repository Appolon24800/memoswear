package dev.appolon.memoswear.data

import dev.appolon.memoswear.data.api.AppJson
import dev.appolon.memoswear.data.model.Attachment
import dev.appolon.memoswear.data.model.InstanceGeneral
import dev.appolon.memoswear.data.model.InstanceProfile
import dev.appolon.memoswear.data.model.InstanceSettingEnvelope
import dev.appolon.memoswear.data.model.ListMemosResponse
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.MemoState
import dev.appolon.memoswear.data.model.SignInResponse
import dev.appolon.memoswear.data.model.TagStats
import dev.appolon.memoswear.data.model.User
import dev.appolon.memoswear.data.model.UserGeneral
import dev.appolon.memoswear.data.model.UserSettingsEnvelope
import dev.appolon.memoswear.data.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes documents copied from a Memos 26.11.2 server. */
class ModelsTest {
    @Test
    fun `a memo with tasks, tags and code`() {
        val memo = AppJson.decodeFromString(
            Memo.serializer(),
            """{"name":"memos/ANRgbAxW6ZfmZhvEHJCFao","creator":"users/watch","content":"# Shopping list\n\n- [ ] Milk\n- [x] Bread\n- [ ] Cheese\n\nBuy at #store `today`\n\n```kotlin\nval x = 1\n```\n\n[Memos](https://github.com/usememos/memos) and _italic_ **bold** ~~gone~~","visibility":"PRIVATE","state":"NORMAL","tags":["store"],"property":{"title":"Shopping list","hasLink":true,"hasCode":true,"hasTaskList":true,"hasIncompleteTasks":true},"snippet":"Shopping list Milk Bread Cheese Buy at #store today Memos and ...","createTime":"2026-11-26T23:47:58Z","updateTime":"2026-11-26T23:47:58Z"}""",
        )

        assertEquals("ANRgbAxW6ZfmZhvEHJCFao", memo.uid)
        assertEquals(listOf("store"), memo.tags)
        assertEquals("Shopping list", memo.property.title)
        assertEquals(1 to 3, memo.taskCounts)
        assertFalse(memo.pinned)
        assertFalse(memo.isEdited)
        assertFalse(memo.isArchived)
        assertEquals("2026-11-26T23:47:58Z", memo.createdInstant.toString())
    }

    @Test
    fun `a pinned memo with an attachment, a comment count and an edit`() {
        val memo = AppJson.decodeFromString(
            Memo.serializer(),
            """{"name":"memos/UZvb7","creator":"users/watch","content":"Ideas #idea/work\nSecond line","visibility":"PUBLIC","state":"NORMAL","pinned":true,"attachments":[{"name":"attachments/gPTqkArKko59VEzkBMGpBk","creator":"users/watch","filename":"bands.png","type":"image/png","memo":"memos/UZvb7","size":"172","createTime":"2026-11-26T23:48:56Z"}],"tags":["idea","idea/work"],"property":{},"snippet":"Ideas","createTime":"2026-11-26T23:47:58Z","updateTime":"2026-11-26T23:50:58Z","commentCount":1}""",
        )

        assertTrue(memo.pinned)
        assertTrue(memo.isEdited)
        assertEquals(1, memo.commentCount)
        assertEquals(listOf("idea", "idea/work"), memo.tags)
        assertEquals(172L, memo.attachments.single().size)
        assertTrue(memo.imageAttachments.single().isImage)
        assertNull(memo.taskCounts)
    }

    @Test
    fun `an attachment size is accepted as a number or as text`() {
        val asNumber = AppJson.decodeFromString(Attachment.serializer(), """{"name":"attachments/a","size":172}""")
        val asText = AppJson.decodeFromString(Attachment.serializer(), """{"name":"attachments/b","size":"4294967296"}""")
        assertEquals(172L, asNumber.size)
        assertEquals(4294967296L, asText.size)
        assertEquals(0L, AppJson.decodeFromString(Attachment.serializer(), """{"name":"attachments/c"}""").size)
    }

    @Test
    fun `timestamps with nanosecond precision parse`() {
        val response = AppJson.decodeFromString(
            SignInResponse.serializer(),
            """{"user":{"name":"users/watch","username":"watch","role":"ADMIN","state":"NORMAL","createTime":"2026-11-26T23:47:48Z","updateTime":"2026-11-26T23:47:48Z"},"accessToken":"abc","accessTokenExpireTime":"2026-11-27T00:02:48.104139609Z"}""",
        )
        assertEquals("2026-11-27T00:02:48.104139609Z", Memo.parseInstant(response.accessTokenExpireTime).toString())
        assertEquals("watch", response.user.username)
    }

    @Test
    fun `the user listing on the REST route carries extra fields and empty strings`() {
        val user = AppJson.decodeFromString(User.serializer(), """{"name":"users/watch","username":"watch","displayName":"","email":"","avatarUrl":"","description":"","password":"","role":"ADMIN","state":"NORMAL","createTime":"2026-11-26T23:47:48Z","updateTime":"2026-11-26T23:47:48Z"}""")
        assertEquals("watch", user.displayNameOrUsername)
    }

    @Test
    fun `a memo page ends with an empty token`() {
        val page = AppJson.decodeFromString(
            ListMemosResponse.serializer(),
            "{\"memos\":[{\"name\":\"memos/a\",\"content\":\"x\",\"visibility\":\"PRIVATE\",\"state\":\"NORMAL\"}],\"nextPageToken\":\"\"}",
        )
        assertEquals(1, page.memos.size)
        assertEquals("", page.nextPageToken)
        assertEquals(Visibility.PRIVATE, page.memos.single().visibility)
        assertEquals(MemoState.NORMAL, page.memos.single().state)
    }

    @Test
    fun `instance facts from a private instance behind Pocket ID`() {
        val profile = AppJson.decodeFromString(InstanceProfile.serializer(), """{"version":"26.11.2","commit":"00fcd17","instanceUrl":"https://memos.example.com","accessMode":"INSTANCE_ACCESS_MODE_PRIVATE"}""")
        assertEquals("26.11.2", profile.version)

        val setting = AppJson.decodeFromString(InstanceSettingEnvelope.serializer(), """{"generalSetting":{"customProfile":{"title":"Memos"},"disallowPasswordAuth":true,"weekStartDayOffset":1}}""")
        val general = InstanceGeneral.from(setting)
        assertEquals("Memos", general.title)
        assertFalse(general.passwordAuthAllowed)

        val open = InstanceGeneral.from(AppJson.decodeFromString(InstanceSettingEnvelope.serializer(), """{"generalSetting":{"customProfile":{"title":"Notes"}}}"""))
        assertTrue(open.passwordAuthAllowed)
    }

    @Test
    fun `the user's default visibility comes from the GENERAL setting only`() {
        val settings = AppJson.decodeFromString(
            UserSettingsEnvelope.serializer(),
            """{"userSettings":[{"name":"users/watch/settings/GENERAL","generalSetting":{"locale":"en","memoVisibility":"PUBLIC"}}]}""",
        )
        assertEquals(Visibility.PUBLIC, UserGeneral.from(settings).defaultVisibility)
        assertEquals(Visibility.PRIVATE, UserGeneral.from(null).defaultVisibility)
    }

    @Test
    fun `tag counts come back as they were counted`() {
        val json = AppJson.decodeFromString(dev.appolon.memoswear.data.model.TagCountsEnvelope.serializer(), """{"tagCounts":{"idea":2,"work":1}}""")
        assertEquals(mapOf("idea" to 2, "work" to 1), TagStats(json.tagCounts).counts)
    }

    @Test
    fun `editing content locally derives the same facts the server would`() {
        val memo = Memo(name = "memos/x", content = "old")
        val edited = memo.withDerivedContent("# Title\n\n- [ ] Milk\nBuy at #store #idea/work\n\n![pic](/file/attachments/a/b.png)")

        assertEquals(listOf("store", "idea", "idea/work"), edited.tags)
        assertEquals("Title", edited.property.title)
        assertTrue(edited.property.hasTaskList)
        assertTrue(edited.property.hasIncompleteTasks)
        assertTrue(edited.property.hasInlineImage)
        assertFalse(edited.property.hasLink)
        assertEquals("Title\n○ Milk\nBuy at #store #idea/work\npic", edited.snippet)
        assertEquals(listOf("/file/attachments/a/b.png"), edited.inlineImageUrls)
    }
}
