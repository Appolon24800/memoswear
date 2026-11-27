package dev.appolon.memoswear.tiles

import android.content.ContextWrapper
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.modifiers.clickable
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.model.Memo
import dev.appolon.memoswear.data.model.Visibility
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Builds each tile's layout for a round watch of 227 dp and reads back the strings it shows. */
class TileLayoutsTest {
    /** Dynamic color is off, so the layouts never ask the context for anything. */
    private class NoContext : ContextWrapper(null)

    private val stringValue = Regex("""value: "((?:[^"\\]|\\.)*)"""")

    /** The text form of the proto repeats every string twice. Keep each once, in layout order. */
    private fun shown(build: MaterialScope.() -> LayoutElement): List<String> {
        val watch = DeviceParameters.Builder()
            .setScreenWidthDp(227)
            .setScreenHeightDp(227)
            .setScreenDensity(320f)
            .build()
        val element = materialScope(NoContext(), watch, allowDynamicTheme = false) { build() }
        return Layout.fromLayoutElement(element).toProto().toString()
            .lineSequence()
            .mapNotNull { stringValue.find(it)?.groupValues?.get(1) }
            .filter { it.isNotEmpty() }
            .map(::unescape)
            .distinct()
            .toList()
    }

    /** Undoes the escapes the text form uses: octal UTF-8 bytes, newlines, quotes and backslashes. */
    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    in '0'..'7' -> {
                        // Consecutive three-digit octal escapes are the bytes of one UTF-8 character.
                        val bytes = ArrayList<Byte>()
                        var j = i
                        while (j + 4 <= text.length && text[j] == '\\' && text[j + 1] in '0'..'7') {
                            bytes += text.substring(j + 1, j + 4).toInt(8).toByte()
                            j += 4
                        }
                        out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                        i = j
                        continue
                    }
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun click(): Clickable = clickable(action = ActionBuilders.LoadAction.Builder().build(), id = "test")

    private fun memo(name: String, content: String, pinned: Boolean = false, created: Instant) = MemoRepository.Entry(
        Memo(name = name, content = content, visibility = Visibility.PRIVATE, pinned = pinned, createTime = created.toString()),
        pending = false,
        failed = false,
    )

    @Test
    fun `the tile for a signed-out watch offers a way in`() {
        assertEquals(listOf("Memos", "Sign in"), shown { signOutTile { click() } })
    }

    @Test
    fun `the notes tile names the newest notes with their age`() {
        val now = Instant.now()
        val entries = listOf(
            memo("memos/a", "Buy coffee", created = now.minus(Duration.ofMinutes(5))),
            memo("memos/b", "# Title\nBody", created = now.minus(Duration.ofMinutes(10))),
            memo("memos/c", "- [ ] Milk", created = now.minus(Duration.ofMinutes(15))),
        )

        // Each note is a small block: its age above its first line.
        assertEquals(
            listOf("Notes", "5m ago", "Buy coffee", "10m ago", "Title", "15m ago", "○ Milk"),
            shown { notesTile(entries) { click() } },
        )
    }

    @Test
    fun `the notes tile says so when there are no notes`() {
        assertEquals(listOf("Notes", "No notes yet"), shown { notesTile(emptyList()) { click() } })
    }

    @Test
    fun `the single-note tile shows the pinned note and calls it that`() {
        val created = Instant.now().minus(Duration.ofHours(3))
        val pinned = memo("memos/p", "Shopping\n- [ ] Milk", pinned = true, created = created)
        val plain = memo("memos/n", "Another note", created = created)

        // The age is shown the way the app shows it everywhere, by the same rule.
        val age = dev.appolon.memoswear.core.time.TimeFormat.relative(created)
        assertEquals(
            listOf("Pinned note", "Shopping", age, "Shopping\n○ Milk"),
            shown { pinnedNoteTile(pinned) { click() } },
        )
        assertEquals("Latest note", shown { pinnedNoteTile(plain) { click() } }.first())
        assertEquals(listOf("Notes", "No notes yet"), shown { pinnedNoteTile(null) { click() } })
    }

    @Test
    fun `the capture tile offers speech or the keyboard, as the watch allows`() {
        assertEquals(listOf("New note", "Speak"), shown { captureTile(voice = true) { click() } })
        assertEquals(listOf("New note", "Write"), shown { captureTile(voice = false) { click() } })
    }

    @Test
    fun `a note that is too long is cut rather than breaking the tile`() {
        val long = memo("memos/long", "x".repeat(400), created = Instant.now().minus(Duration.ofMinutes(1)))

        val strings = shown { pinnedNoteTile(long) { click() } }

        // The title line is cut at 80 characters and the body at 220, each with an ellipsis.
        assertEquals("x".repeat(80) + "…", strings[1])
        assertEquals("x".repeat(220) + "…", strings.last())
    }
}
