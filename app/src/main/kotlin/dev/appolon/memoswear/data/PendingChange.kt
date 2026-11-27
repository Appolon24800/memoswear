package dev.appolon.memoswear.data

import dev.appolon.memoswear.core.markdown.Block
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.MemoProperty
import dev.appolon.memoswear.data.model.MemoState
import dev.appolon.memoswear.data.model.Visibility
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A change made on the watch that the server has not accepted yet. Changes are applied in order,
 * and there is at most one per memo: a later change to the same memo is folded into the earlier
 * one, so a note created offline and then edited is still sent as a single create.
 */
@Serializable
sealed class PendingChange {
    abstract val id: String
    abstract val createdAt: Long

    /** Set when the server rejected the change for good. Such changes wait for the user. */
    abstract val error: String?

    /** The memo the change is about: a server name, or `local/…` for a note not yet created. */
    abstract val target: String

    /** The resource name of the user who made the change. Changes never travel to another account. */
    abstract val owner: String

    fun markFailed(message: String?): PendingChange = when (this) {
        is Create -> copy(error = message)
        is Update -> copy(error = message)
        is Delete -> copy(error = message)
    }

    val failed: Boolean get() = error != null

    /** The memo as it will be once this change is applied. Null when the change removes it. */
    fun applyTo(memo: Memo?): Memo? = when (this) {
        is Create -> toMemo()
        is Update -> memo?.let { current ->
            current
                .copy(
                    name = memoName,
                    pinned = pinned ?: current.pinned,
                    state = state ?: current.state,
                    visibility = visibility ?: current.visibility,
                )
                .withDerivedContent(content ?: current.content)
        }
        is Delete -> null
    }

    @Serializable
    @SerialName("create")
    data class Create(
        val localId: String,
        val content: String,
        val visibility: Visibility,
        val parent: String? = null,
        val pinned: Boolean = false,
        val state: MemoState = MemoState.NORMAL,
        override val createdAt: Long,
        override val error: String? = null,
        override val owner: String = "",
    ) : PendingChange() {
        override val id: String get() = localId
        override val target: String get() = localName

        val localName: String get() = Memo.LOCAL_PREFIX + localId

        fun toMemo(): Memo = Memo(
            name = localName,
            visibility = visibility,
            pinned = pinned,
            state = state,
            parent = parent,
            createTime = Instant.ofEpochMilli(createdAt).toString(),
            updateTime = Instant.ofEpochMilli(createdAt).toString(),
        ).withDerivedContent(content)

        fun fold(update: Update): Create = copy(
            content = update.content ?: content,
            pinned = update.pinned ?: pinned,
            state = update.state ?: state,
            visibility = update.visibility ?: visibility,
            error = null,
        )
    }

    @Serializable
    @SerialName("update")
    data class Update(
        val changeId: String,
        val memoName: String,
        val content: String? = null,
        val pinned: Boolean? = null,
        val state: MemoState? = null,
        val visibility: Visibility? = null,
        override val createdAt: Long,
        override val error: String? = null,
        override val owner: String = "",
    ) : PendingChange() {
        override val id: String get() = changeId
        override val target: String get() = memoName

        fun fold(update: Update): Update = copy(
            content = update.content ?: content,
            pinned = update.pinned ?: pinned,
            state = update.state ?: state,
            visibility = update.visibility ?: visibility,
            error = null,
        )
    }

    @Serializable
    @SerialName("delete")
    data class Delete(
        val changeId: String,
        val memoName: String,
        override val createdAt: Long,
        override val error: String? = null,
        override val owner: String = "",
    ) : PendingChange() {
        override val id: String get() = changeId
        override val target: String get() = memoName
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

        /** Adds [incoming] to [existing], replacing the change already queued for the same memo. */
        fun merge(existing: List<PendingChange>, incoming: PendingChange): List<PendingChange> {
            val prior = existing.lastOrNull { it.target == incoming.target }
            val rest = existing.filter { it.target != incoming.target }
            return when (incoming) {
                is Create -> rest + incoming
                is Update -> when (prior) {
                    is Create -> rest + prior.fold(incoming)
                    is Update -> rest + prior.fold(incoming)
                    // The memo is already on its way out, so there is nothing left to update.
                    is Delete -> existing
                    null -> rest + incoming
                }
                is Delete -> if (prior is Create) rest else rest + incoming
            }
        }
    }
}

/**
 * A copy with new content and with the tags, title, snippet and flags the server derives from
 * content, so a note edited on the watch looks the same before and after it syncs.
 */
fun Memo.withDerivedContent(newContent: String): Memo {
    val blocks = MemoMarkdown.parse(newContent)
    val spans = blocks.flatMap { block ->
        when (block) {
            is Block.Heading -> block.spans
            is Block.Paragraph -> block.spans
            is Block.TaskItem -> block.spans
            is Block.ListItem -> block.spans
            is Block.Quote -> block.spans
            else -> emptyList()
        }
    }
    val title = (blocks.firstOrNull() as? Block.Heading)?.takeIf { it.level == 1 }
        ?.spans?.joinToString("") { it.text }.orEmpty()
    return copy(
        content = newContent,
        tags = MemoMarkdown.tags(newContent),
        snippet = MemoMarkdown.plainText(newContent, SNIPPET_LENGTH),
        property = MemoProperty(
            title = title,
            hasLink = spans.any { it.link != null },
            hasCode = blocks.any { it is Block.CodeBlock } || spans.any { it.code },
            hasTaskList = blocks.any { it is Block.TaskItem },
            hasIncompleteTasks = blocks.any { it is Block.TaskItem && !it.checked },
            hasInlineImage = blocks.any { it is Block.Image },
        ),
    )
}

private const val SNIPPET_LENGTH = 120
