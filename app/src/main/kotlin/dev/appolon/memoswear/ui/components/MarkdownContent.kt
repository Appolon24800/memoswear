package dev.appolon.memoswear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.SubcomposeAsyncImage
import dev.appolon.memoswear.core.markdown.Block
import dev.appolon.memoswear.core.markdown.Span

private const val URL_ANNOTATION = "url"
private const val TAG_ANNOTATION = "tag"

/** Stands for "nothing listens to this", so inline text can skip its tap handling. */
private val NO_ACTION: (String) -> Unit = {}

/** How the content of a memo reacts to the person reading it. */
class MarkdownActions(
    val onToggleTask: (Block.TaskItem) -> Unit = {},
    val onOpenUrl: (String) -> Unit = NO_ACTION,
    val onOpenTag: (String) -> Unit = NO_ACTION,
    val onOpenImage: (String) -> Unit = {},
    /** Maps an image reference in the content to a URL that can be loaded, or null to skip it. */
    val resolveImage: (String) -> String? = { it },
)

/** Draws one block of a memo's Markdown. */
@Composable
fun BlockContent(block: Block, actions: MarkdownActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is Block.Heading -> InlineText(
            spans = block.spans,
            modifier = modifier.fillMaxWidth(),
            style = when {
                block.level == 1 -> MaterialTheme.typography.titleLarge
                block.level == 2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            actions = actions,
        )

        is Block.Paragraph -> InlineText(
            spans = block.spans,
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            actions = actions,
        )

        is Block.TaskItem -> {
            val label: @Composable () -> Unit = { InlineText(spans = block.spans, style = MaterialTheme.typography.bodySmall, actions = actions) }
            CheckboxButton(
                checked = block.checked,
                onCheckedChange = { actions.onToggleTask(block) },
                modifier = modifier.fillMaxWidth(),
                label = { Box(Modifier.weight(1f)) { label() } },
            )
        }

        is Block.ListItem -> Row(modifier.fillMaxWidth()) {
            Text(
                text = block.number?.let { "$it." } ?: "•",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            InlineText(
                spans = block.spans,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                actions = actions,
            )
        }

        is Block.CodeBlock -> Box(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHigh)
                .padding(10.dp),
        ) {
            Text(text = block.code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }

        is Block.Quote -> Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
            Spacer(Modifier.width(10.dp))
            InlineText(
                spans = block.spans,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurfaceVariant,
                actions = actions,
            )
        }

        is Block.Image -> {
            val url = actions.resolveImage(block.url)
            if (url != null) {
                MemoImage(url = url, contentDescription = block.alt.ifBlank { "Picture" }, modifier = modifier, onClick = { actions.onOpenImage(url) })
            }
        }

        Block.Divider -> Box(
            modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineVariant),
        )
    }
}

/** A picture from a memo. It fills the width and stays short enough to scroll past. */
@Composable
fun MemoImage(url: String, contentDescription: String?, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        loading = {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        },
        error = { FailedImage() },
    )
}

@Composable
private fun FailedImage() {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Text("Picture didn't load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The text of the blocks with their inline styling but none of their layout, as used in the
 * compact card of a list: one line per block, tasks and list items with a short marker.
 */
internal fun compactText(blocks: List<Block>, colors: ColorScheme): AnnotatedString = buildAnnotatedString {
    for ((index, block) in blocks.withIndex()) {
        if (index > 0) append('\n')
        when (block) {
            is Block.Heading, is Block.Paragraph, is Block.Quote -> append(annotate(block.textOnly, colors))
            is Block.TaskItem -> {
                append(if (block.checked) "\u2713 " else "\u25CB ")
                append(annotate(block.spans, colors))
            }

            is Block.ListItem -> {
                append(block.number?.let { "$it. " } ?: "\u2022 ")
                append(annotate(block.spans, colors))
            }

            is Block.CodeBlock -> append(annotate(listOf(Span(block.code, code = true)), colors))
            is Block.Image -> append(block.alt.ifBlank { "Picture" })
            Block.Divider -> Unit
        }
    }
}

/** Inline marks with their styling. Links and tags are tappable when [actions] handles them. */
@Composable
fun InlineText(
    spans: List<Span>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    actions: MarkdownActions = MarkdownActions(),
) {
    val colors = MaterialTheme.colorScheme
    val text = remember(spans, colors) { annotate(spans, colors) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tappable = actions.onOpenUrl !== NO_ACTION || actions.onOpenTag !== NO_ACTION
    val tapModifier = if (tappable) {
        Modifier.pointerInput(text) {
            detectTapGestures { position ->
                val result: TextLayoutResult = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                val annotated = result.layoutInput.text
                val url = annotated.getStringAnnotations(URL_ANNOTATION, offset, offset).firstOrNull()
                val tag = annotated.getStringAnnotations(TAG_ANNOTATION, offset, offset).firstOrNull()
                when {
                    url != null -> actions.onOpenUrl(url.item)
                    tag != null -> actions.onOpenTag(tag.item)
                }
            }
        }
    } else {
        Modifier
    }
    Text(
        text = text,
        modifier = modifier.then(tapModifier),
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
    )
}

/** The inline text of the block types that are only text. */
private val Block.textOnly: List<Span>
    get() = when (this) {
        is Block.Heading -> spans
        is Block.Paragraph -> spans
        is Block.Quote -> spans
        else -> emptyList()
    }

/** Styles inline marks with the theme's colors: tags and links in the accent color, code in monospace. */
internal fun annotate(spans: List<Span>, colors: ColorScheme): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val start = length
        append(span.text)
        val end = length
        if (end == start) continue
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = when {
                span.strike -> TextDecoration.LineThrough
                span.link != null -> TextDecoration.Underline
                else -> null
            },
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) colors.surfaceContainerHigh else Color.Unspecified,
            color = if (span.link != null || span.tag != null) colors.primary else Color.Unspecified,
        )
        if (style != SpanStyle()) addStyle(style, start, end)
        span.link?.let { addStringAnnotation(URL_ANNOTATION, it, start, end) }
        span.tag?.let { addStringAnnotation(TAG_ANNOTATION, it, start, end) }
    }
}
