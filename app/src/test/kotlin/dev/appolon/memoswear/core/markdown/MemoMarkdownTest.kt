package dev.appolon.memoswear.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoMarkdownTest {
    @Test
    fun `each kind of block is recognised and tasks remember their line`() {
        val content = """
            |# Title
            |
            |First *line*
            |Second line
            |
            |- [ ] Milk
            |- [x] Bread
            |* bullet
            |1. first
            |> quoted
            |
            |```kotlin
            |val x = 1
            |```
            |
            |![alt](/file/attachments/a/b.png)
            |
            |---
        """.trimMargin()

        val blocks = MemoMarkdown.parse(content)

        assertEquals(Block.Heading(1, listOf(Span("Title"))), blocks[0])
        assertEquals(Block.Paragraph(listOf(Span("First "), Span("line", italic = true), Span("\n"), Span("Second line"))), blocks[1])
        assertEquals(Block.TaskItem(false, listOf(Span("Milk")), line = 5), blocks[2])
        assertEquals(Block.TaskItem(true, listOf(Span("Bread")), line = 6), blocks[3])
        assertEquals(Block.ListItem(listOf(Span("bullet")), number = null), blocks[4])
        assertEquals(Block.ListItem(listOf(Span("first")), number = 1), blocks[5])
        assertEquals(Block.Quote(listOf(Span("quoted"))), blocks[6])
        assertEquals(Block.CodeBlock("kotlin", "val x = 1"), blocks[7])
        assertEquals(Block.Image("alt", "/file/attachments/a/b.png"), blocks[8])
        assertEquals(Block.Divider, blocks[9])
    }

    @Test
    fun `inline marks are styled and plain text is unchanged`() {
        val spans = MemoMarkdown.parseInline(
            "**bold** _italic_ ~~gone~~ `code` [Memos](https://github.com/usememos/memos) #idea/work and https://example.com/x.",
        )
        val text = spans.joinToString("") { it.text }
        assertEquals("bold italic gone code Memos #idea/work and https://example.com/x.", text)
        assertEquals(Span("bold", bold = true), spans[0])
        assertEquals(Span(" ", ), spans[1])
        assertEquals(Span("italic", italic = true), spans[2])
        assertEquals(Span("gone", strike = true), spans[4])
        assertEquals(Span("code", code = true), spans[6])
        assertEquals(Span("Memos", link = "https://github.com/usememos/memos"), spans[8])
        assertEquals(Span("#idea/work", tag = "idea/work"), spans[10])
        assertEquals(Span("https://example.com/x", link = "https://example.com/x"), spans[12])
    }

    @Test
    fun `unmatched marks and escapes stay literal`() {
        val spans = MemoMarkdown.parseInline("a * b and \\*not italic\\* and snake_case_name")
        assertEquals("a * b and *not italic* and snake_case_name", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.italic || it.bold })
    }

    @Test
    fun `a hash glued to a word is not a tag`() {
        val spans = MemoMarkdown.parseInline("C# and #123 but not https://example.com/#anchor")
        val tags = spans.mapNotNull { it.tag }
        assertEquals(listOf("123"), tags)
    }

    @Test
    fun `plain text keeps tasks readable and drops markup`() {
        val content = "# Shop\n- [ ] Milk\n- [x] Bread\n**bold** [link](https://x.y)\n\n```\ncode\n```"
        assertEquals("Shop\n○ Milk\n✓ Bread\nbold link\ncode", MemoMarkdown.plainText(content))
        assertEquals("Shop\n○ Mi…", MemoMarkdown.plainText(content, 9))
    }

    @Test
    fun `content never throws`() {
        MemoMarkdown.parse("```\nunclosed fence\n- [ ]\n]\n[")
        MemoMarkdown.parse("")
        MemoMarkdown.parseInline("**__~~``")
    }

    @Test
    fun `tags include the levels of a nested tag in order`() {
        assertEquals(listOf("idea", "idea/work", "home"), MemoMarkdown.tags("Ideas #idea/work and #home"))
        assertEquals(emptyList<String>(), MemoMarkdown.tags("No tags # here"))
    }

    @Test
    fun `task state changes only the marker on that line`() {
        val content = "- [ ] One\n  indented text\n- [x] Two\n- [ ] Three\n"

        val checked = MemoMarkdown.setTaskState(content, line = 0, checked = true)
        assertEquals("- [x] One\n  indented text\n- [x] Two\n- [ ] Three\n", checked)

        val unchecked = MemoMarkdown.setTaskState(content, line = 1, checked = false)
        assertEquals(content, unchecked)
        assertEquals(content, MemoMarkdown.setTaskState(content, line = 9, checked = true))
    }

    @Test
    fun `task flags follow the content`() {
        assertTrue(MemoMarkdown.hasTaskList("- [ ] a\n- [x] b"))
        assertTrue(MemoMarkdown.hasIncompleteTasks("- [x] a\n- [ ] b"))
        assertTrue(!MemoMarkdown.hasIncompleteTasks("- [x] a\n- [X] b"))
        assertTrue(!MemoMarkdown.hasTaskList("- a"))
    }

    @Test
    fun `the line at the cursor becomes a task and back`() {
        val (added, cursor) = MemoMarkdown.toggleTaskMarker("Buy milk\nCall Bob", cursor = 12)
        assertEquals("Buy milk\n- [ ] Call Bob", added)
        assertEquals(cursor, 18)

        val (removed, movedBack) = MemoMarkdown.toggleTaskMarker(added, cursor = cursor)
        assertEquals("Buy milk\nCall Bob", removed)
        assertEquals(12, movedBack)
    }

    @Test
    fun `an already checked task marker is removed too`() {
        val text = "  - [x] done thing"
        val (result, cursor) = MemoMarkdown.toggleTaskMarker(text, cursor = text.length)
        assertEquals("  done thing", result)
        // The cursor was at the end and stays at the end, 8 characters to the left.
        assertEquals(12, cursor)
    }

    @Test
    fun `a cursor inside the marker lands right after the indent`() {
        val text = "- [ ] thing"
        val (result, cursor) = MemoMarkdown.toggleTaskMarker(text, cursor = 3)
        assertEquals("thing", result)
        assertEquals(0, cursor)
    }

    @Test
    fun `windows line endings do not break task lookups`() {
        val content = "- [ ] One\r\n- [ ] Two\r\n"
        val blocks = MemoMarkdown.parse(content)
        assertEquals(2, blocks.size)
        assertEquals("- [ ] One\n- [x] Two\n", MemoMarkdown.setTaskState(content, 1, true))
    }
}
