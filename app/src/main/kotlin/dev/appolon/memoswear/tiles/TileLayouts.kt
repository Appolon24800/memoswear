package dev.appolon.memoswear.tiles

import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.types.layoutString
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.model.Memo

/*
 * What each tile shows, kept apart from how tiles are served and how they open the app. Each
 * function is a Material 3 layout builder, so it extends MaterialScope.
 */
/** The one tile there is when nobody is signed in: a way to the sign-in screen. */
fun MaterialScope.signOutTile(openApp: () -> Clickable): LayoutElement = primaryLayout(
    titleSlot = { tileHeading("Memos") },
    mainSlot = {
        textButton(onClick = openApp(), labelContent = { text("Sign in".layoutString) })
    },
)

/** The newest notes, each one a tap away. */
fun MaterialScope.notesTile(entries: List<MemoRepository.Entry>, openMemo: (String) -> Clickable): LayoutElement {
    val content = if (entries.isEmpty()) {
        text("No notes yet".layoutString, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant)
    } else {
        val list = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(tilePadding(EDGE_PADDING_DP))
        entries.forEachIndexed { index, entry ->
            if (index > 0) list.addContent(spacer(height = dp(ROW_GAP_DP)))
            val lines = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .setModifiers(tilePadding(ENTRY_PADDING_DP))
            tileTimeOf(entry.memo).let { time -> lines.addContent(tileMutedLine(time)) }
            lines.addContent(tileLine(tileTitleOf(entry.memo), lines = 1))
            list.addContent(
                LayoutElementBuilders.Box.Builder()
                    .addContent(lines.build())
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openMemo(entry.memo.name)).build())
                    .build(),
            )
        }
        list.build()
    }
    return primaryLayout(titleSlot = { tileHeading("Notes") }, mainSlot = { content })
}

/** One note: the first pinned one, or else the newest. */
fun MaterialScope.pinnedNoteTile(entry: MemoRepository.Entry?, openMemo: (String) -> Clickable): LayoutElement {
    val title = when {
        entry == null -> "Notes"
        entry.memo.pinned -> "Pinned note"
        else -> "Latest note"
    }
    val content = if (entry == null) {
        text("No notes yet".layoutString, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant)
    } else {
        titleCard(
            onClick = openMemo(entry.memo.name),
            title = { tileLine(tileTitleOf(entry.memo), lines = 1) },
            time = { tileMutedLine(tileTimeOf(entry.memo)) },
            content = { tileLine(MemoMarkdown.plainText(entry.memo.content, 220), lines = 5) },
        )
    }
    return primaryLayout(titleSlot = { tileHeading(title) }, mainSlot = { content })
}

/** A single button that starts a note, by voice when the watch can listen and by keyboard otherwise. */
fun MaterialScope.captureTile(voice: Boolean, start: () -> Clickable): LayoutElement = primaryLayout(
    titleSlot = { tileHeading("New note") },
    mainSlot = {
        textButton(onClick = start(), labelContent = { text((if (voice) "Speak" else "Write").layoutString) })
    },
)

fun MaterialScope.tileHeading(value: String): LayoutElement =
    text(value.layoutString, typography = Typography.TITLE_MEDIUM, color = colorScheme.onSurface)

/** A note's title when it has a Markdown heading, and otherwise its first line. */
internal fun tileTitleOf(memo: Memo): String =
    memo.property.title.ifBlank { MemoMarkdown.plainText(memo.content, 80).lineSequence().firstOrNull().orEmpty() }

internal fun tileTimeOf(memo: Memo): String =
    memo.createdInstant?.let { dev.appolon.memoswear.core.time.TimeFormat.relative(it) }.orEmpty()

private fun MaterialScope.tileLine(value: String, lines: Int): LayoutElement = text(
    value.layoutString,
    typography = Typography.BODY_MEDIUM,
    color = colorScheme.onSurface,
    maxLines = lines,
    overflow = TEXT_OVERFLOW_ELLIPSIZE,
)

private fun MaterialScope.tileMutedLine(value: String): LayoutElement =
    text(value.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant)

/** Padding on all four sides, as a modifier ready to attach to a layout element. */
private fun tilePadding(all: Float): ModifiersBuilders.Modifiers =
    ModifiersBuilders.Modifiers.Builder()
        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(all)).build())
        .build()

private const val EDGE_PADDING_DP = 18f
private const val ENTRY_PADDING_DP = 2f
private const val ROW_GAP_DP = 8f
