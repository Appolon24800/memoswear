package dev.appolon.memoswear.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ConfirmationDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.Flash
import dev.appolon.memoswear.core.markdown.Block
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.core.time.TimeFormat
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.model.Attachment
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.Visibility
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.components.ActionButton
import dev.appolon.memoswear.ui.components.BlockContent
import dev.appolon.memoswear.ui.components.FlashConfirmation
import dev.appolon.memoswear.ui.components.MarkdownActions
import dev.appolon.memoswear.ui.components.MemoImage
import dev.appolon.memoswear.ui.components.label
import dev.appolon.memoswear.ui.containerViewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoDetailViewModel(private val container: AppContainer, private val memoName: String) : ViewModel() {
    private val repository = container.repository

    data class Comments(val loading: Boolean = false, val items: List<MemoRepository.Entry> = emptyList())

    val memo: StateFlow<Memo?> = repository.memoFlow(memoName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while a change to this memo is waiting for the server to accept it. */
    val pending: StateFlow<Boolean> = combine(repository.home, repository.archived) { home, archived ->
        (home.entries + archived.entries).any { it.memo.name == memoName && it.pending }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the server refused a change to this memo. The person then has to resolve it. */
    val failed: StateFlow<Boolean> = combine(repository.home, repository.archived) { home, archived ->
        (home.entries + archived.entries).any { it.memo.name == memoName && it.failed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _comments = MutableStateFlow(Comments())
    val comments: StateFlow<Comments> = _comments

    /** Whether the signed-in user may change the memo. False until it has loaded. */
    val editable: Boolean get() = memo.value?.let { repository.canEdit(it) } == true

    fun toggleTask(block: Block.TaskItem) {
        viewModelScope.launch { repository.toggleTask(memoName, block.line, !block.checked) }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(memoName, pinned) }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { repository.setArchived(memoName, archived) }
    }

    fun setVisibility(visibility: Visibility) {
        viewModelScope.launch { repository.setVisibility(memoName, visibility) }
    }

    /** Deletes the memo, or drops it when it has not reached the server yet. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val local = memoName.startsWith(Memo.LOCAL_PREFIX)
            repository.deleteMemo(memoName)
            container.flash.value = Flash(if (local) "Discarded" else "Deleted")
            onDeleted()
        }
    }

    /** Asks the server again for every change it refused earlier. */
    fun retryFailed() {
        viewModelScope.launch { repository.retryFailed() }
    }

    fun loadComments() {
        viewModelScope.launch {
            _comments.value = _comments.value.copy(loading = true)
            _comments.value = Comments(items = repository.comments(memoName))
        }
    }

    /** The URL to load for an image reference in the content. Null when it cannot be shown. */
    fun resolveImage(reference: String): String? {
        val api = container.session.api ?: return null
        val absolute = reference.startsWith("http://", ignoreCase = true) || reference.startsWith("https://", ignoreCase = true)
        if (!reference.startsWith("/") && !absolute) return null
        return api.fileUrl(reference)?.toString()
    }

    fun attachmentUrl(attachment: Attachment): String? =
        container.session.api?.attachmentUrl(attachment)?.toString()

    /** True when the content already shows this attachment, so it is not repeated below. */
    fun isShownInline(attachment: Attachment): Boolean {
        val prefix = "/file/attachments/${attachment.uid}"
        return memo.value?.inlineImageUrls?.any { it.contains(prefix) } == true
    }
}

/** One note: its content, attachments, comments, and what can be done with it. */
@Composable
fun MemoDetailScreen(navigator: AppNavigator, memoName: String) {
    val context = LocalContext.current
    val viewModel: MemoDetailViewModel = containerViewModel { MemoDetailViewModel(it, memoName) }
    val memo by viewModel.memo.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    var showVisibility by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var linkNotice by remember { mutableStateOf<String?>(null) }
    val current = memo
    // The list's content block is not composable, so everything it shows is derived here.
    val blocks = remember(current?.content) { current?.let { MemoMarkdown.parse(it.content) }.orEmpty() }
    val extraImages = remember(current?.attachments, current?.content) {
        current?.attachments?.filter { it.isImage && !viewModel.isShownInline(it) }.orEmpty()
    }
    val files = remember(current?.attachments) { current?.attachments?.filter { !it.isImage }.orEmpty() }
    val editable = viewModel.editable
    val onServer = editable && current != null && !current.isLocal

    // Comments change on other devices, and on this one after the editor closes.
    LifecycleResumeEffect(current?.name) {
        if ((current?.commentCount ?: 0) > 0) viewModel.loadComments()
        onPauseOrDispose { }
    }

    val actions = remember(viewModel) {
        MarkdownActions(
            onToggleTask = { if (viewModel.editable) viewModel.toggleTask(it) },
            onOpenTag = { navigator.search(it) },
            onOpenImage = { url -> navigator.image(url) },
            onOpenUrl = { url ->
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    linkNotice = url
                }
            },
            resolveImage = { viewModel.resolveImage(it) },
        )
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            if (current == null) {
                item(key = "waiting") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@TransformingLazyColumn
            }

            item(key = "meta") { NoteMeta(current, pending) }
            items(blocks.size, key = { "block-$it" }) { index ->
                BlockContent(blocks[index], actions, modifier = Modifier.padding(horizontal = 10.dp))
            }
            items(extraImages.size, key = { "image-${extraImages[it].uid}" }) { index ->
                val url = viewModel.attachmentUrl(extraImages[index])
                if (url != null) {
                    MemoImage(
                        url = url,
                        contentDescription = extraImages[index].filename.ifBlank { "Attachment" },
                        modifier = Modifier.padding(horizontal = 10.dp),
                        onClick = { navigator.image(url) },
                    )
                }
            }
            items(files.size, key = { "file-${files[it].uid}" }) { index ->
                FileCard(files[index], Modifier.padding(horizontal = 8.dp))
            }

            if (current.commentCount > 0 || comments.items.isNotEmpty()) {
                item(key = "comments-label") {
                    Text(
                        text = "Comments",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(comments.items.size, key = { "comment-${comments.items[it].memo.name}" }) { index ->
                    CommentCard(comments.items[index], Modifier.padding(horizontal = 8.dp))
                }
                if (comments.loading) {
                    item(key = "comments-loading") { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }

            if (editable) {
                item(key = "edit") {
                    ActionButton(label = "Edit note", icon = Icons.Default.Edit, onClick = { navigator.edit(current.name) })
                }
            }
            item(key = "comment") {
                ActionButton(label = "Add comment", icon = Icons.AutoMirrored.Filled.Comment, onClick = { navigator.comment(current.name) })
            }
            if (onServer) {
                item(key = "pin") {
                    ActionButton(
                        label = if (current.pinned) "Unpin" else "Pin to top",
                        icon = Icons.Default.PushPin,
                        onClick = { viewModel.setPinned(!current.pinned) },
                    )
                }
                item(key = "visibility") {
                    ActionButton(
                        label = "Visibility: ${current.visibility.label()}",
                        icon = Icons.Default.Visibility,
                        onClick = { showVisibility = true },
                    )
                }
                item(key = "archive") {
                    ActionButton(
                        label = if (current.isArchived) "Unarchive" else "Archive",
                        icon = if (current.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                        onClick = {
                            viewModel.setArchived(!current.isArchived)
                            if (!current.isArchived) navigator.backToList()
                        },
                    )
                }
            }
            if (failed) {
                item(key = "retry") {
                    ActionButton(label = "Try sending again", icon = Icons.Outlined.ErrorOutline, onClick = { viewModel.retryFailed() })
                }
            }
            item(key = "delete") {
                val local = current.isLocal
                ActionButton(
                    label = if (local) "Discard note" else "Delete note",
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = { confirmDelete = true },
                )
            }
            item(key = "end") { Spacer(Modifier.height(4.dp)) }
        }
    }

    FlashConfirmation(memoName = memoName)

    if (showVisibility) {
        AlertDialog(
            visible = true,
            onDismissRequest = { showVisibility = false },
            title = { Text("Who can see it") },
            confirmButton = {
                TextButton(onClick = { showVisibility = false }) { Text("Cancel") }
            },
            text = {
                Column {
                    for ((visibility, hint) in listOf(
                        Visibility.PRIVATE to "Only you",
                        Visibility.PROTECTED to "Other accounts on this server",
                        Visibility.PUBLIC to "Everyone",
                    )) {
                        RadioButton(
                            selected = current?.visibility == visibility,
                            onSelect = {
                                viewModel.setVisibility(visibility)
                                showVisibility = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            secondaryLabel = { Text(hint) },
                            label = { Text(visibility.label()) },
                        )
                    }
                }
            },
        )
    }

    if (confirmDelete) {
        val local = current?.isLocal == true
        val warning = when {
            local -> "It has not been sent yet."
            (current?.commentCount ?: 0) > 0 -> "Its comments are deleted too."
            else -> null
        }
        AlertDialog(
            visible = true,
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (local) "Discard this note?" else "Delete this note?") },
            text = warning?.let { { Text(it) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        // For a note that never reached the server this drops it instead.
                        viewModel.delete { navigator.backToList() }
                    },
                ) { Text(if (local) "Discard" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    linkNotice?.let { url ->
        val host = runCatching { url.toUri().host }.getOrNull() ?: url
        ConfirmationDialog(
            visible = true,
            onDismissRequest = { linkNotice = null },
            durationMillis = 4000L,
            text = { Text("Open $host on another device") },
            content = {},
        )
    }
}

/** When the note was written, who can read it, and its tags. */
@Composable
private fun NoteMeta(memo: Memo, pending: Boolean) {
    val colors = MaterialTheme.colorScheme
    val created = remember(memo.createTime) { memo.createdInstant }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Visibility",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(memo.visibility.label(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(
                text = buildString {
                    append(created?.let { TimeFormat.full(it) }.orEmpty())
                    if (memo.isEdited) append(" · edited")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (memo.tags.isNotEmpty()) {
                Text(
                    text = memo.tags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pending) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Outlined.HourglassEmpty,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Not sent yet", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: Attachment, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.filename.ifBlank { "Attachment" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (file.size > 0) {
                    Text(
                        text = formatSize(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentCard(entry: MemoRepository.Entry, modifier: Modifier = Modifier) {
    val memo = entry.memo
    val colors = MaterialTheme.colorScheme
    val time = remember(memo.createTime) { memo.createdInstant?.let { TimeFormat.relative(it) }.orEmpty() }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("@${memo.creator.substringAfterLast('/')}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Spacer(Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(MemoMarkdown.plainText(memo.content, 400), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
