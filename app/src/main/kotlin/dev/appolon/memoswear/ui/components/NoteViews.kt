package dev.appolon.memoswear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.core.markdown.Block
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.core.time.TimeFormat
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.model.Visibility

/** A button with an icon and a label that spans the list. */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val buttonColors = if (destructive) {
        ButtonDefaults.buttonColors(
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            iconColor = colors.onErrorContainer,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        enabled = enabled,
        colors = buttonColors,
        icon = { icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

/** How consecutive notes are laid out in a list. */
object NoteStack {
    /** Space between two notes. They sit close, as rows of one list rather than cards of their own. */
    val spacing: Dp = 2.dp

    /** Padding under an element that is not a note, so that it keeps its distance from the notes. */
    val separation: Dp = 6.dp

    /**
     * The shape of the note at [index] among [count] consecutive notes. Where two notes meet the
     * corners are small, and only the top of the first note and the bottom of the last keep the
     * round shape of a card.
     */
    @Composable
    fun shape(index: Int, count: Int): Shape {
        val round = MaterialTheme.shapes.large
        val flat = MaterialTheme.shapes.small
        val first = index == 0
        val last = index == count - 1
        return RoundedCornerShape(
            topStart = if (first) round.topStart else flat.topStart,
            topEnd = if (first) round.topEnd else flat.topEnd,
            bottomEnd = if (last) round.bottomEnd else flat.bottomEnd,
            bottomStart = if (last) round.bottomStart else flat.bottomStart,
        )
    }
}

/** One note in a list: its text, then a line of facts about it. */
@Composable
fun NoteCard(
    entry: MemoRepository.Entry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
) {
    val memo = entry.memo
    val colors = MaterialTheme.colorScheme
    val blocks = remember(memo.content) { MemoMarkdown.parse(memo.content) }
    val title = memo.property.title
    // Tags are colored where they stand in the text, the way Memos shows them, so the card has
    // no separate tag line.
    val body = remember(blocks, title, colors) {
        val shown = if (title.isNotBlank() && blocks.firstOrNull() is Block.Heading) blocks.drop(1) else blocks
        compactText(shown, colors)
    }
    val time = remember(memo.createTime) { memo.createdInstant?.let { TimeFormat.relative(it) }.orEmpty() }
    val tasks = memo.taskCounts

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (entry.failed) colors.errorContainer else colors.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (body.text.isNotBlank()) Spacer(Modifier.height(2.dp))
            }
            if (body.text.isNotBlank()) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = time, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (memo.isEdited) {
                    MetaDot()
                    Text("edited", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (memo.pinned) MetaIcon(Icons.Default.PushPin, "Pinned")
                if (tasks != null) {
                    MetaIcon(Icons.Default.CheckCircle, "Tasks")
                    Text("${tasks.first}/${tasks.second}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (memo.attachments.any { it.isImage } || memo.inlineImageUrls.isNotEmpty()) MetaIcon(Icons.Default.Image, "Picture")
                if (memo.commentCount > 0) {
                    MetaIcon(Icons.AutoMirrored.Filled.Comment, "Comments")
                    Text("${memo.commentCount}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (memo.visibility == Visibility.PRIVATE) MetaIcon(Icons.Default.Lock, "Private")
                Spacer(Modifier.weight(1f))
                when {
                    entry.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Not saved", style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                    }

                    entry.pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Sending", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaDot() {
    Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MetaIcon(icon: ImageVector, description: String) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
    )
}

/** A message about the state of the list, with an optional way out. */
@Composable
fun BannerCard(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else colors.surfaceContainerHigh,
            contentColor = if (error) colors.onErrorContainer else colors.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** What a list shows when there is nothing in it. */
@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A line of muted text used as a section heading inside a list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

/** The short name of who can read a memo. */
fun Visibility.label(): String = when (this) {
    Visibility.PRIVATE, Visibility.UNSPECIFIED -> "Private"
    Visibility.PROTECTED -> "Signed-in users"
    Visibility.PUBLIC -> "Public"
    Visibility.SPACE -> "Space"
}
