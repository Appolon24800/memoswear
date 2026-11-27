package dev.appolon.memoswear.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.Flash
import dev.appolon.memoswear.core.markdown.MemoMarkdown
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.capture.VoiceInput
import dev.appolon.memoswear.ui.components.WatchTextField
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The text of a note being written. Text is saved as it is typed, shortly after each change, so
 * nothing is lost when the watch puts the app aside or the screen is left.
 *
 * For a new note the first non-empty text creates it; after that, edits change it. Clearing the
 * text of a note that this editor created deletes it again. Clearing an existing note leaves it
 * as it was.
 */
class EditorViewModel(
    private val container: AppContainer,
    /** The memo being edited, or null for a new note. */
    private val target: String?,
    /** The memo being commented on, for a new comment. */
    private val parent: String?,
) : ViewModel() {
    private val repository = container.repository
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    /** The memo being written: [target], or the one created from this editor. */
    private var workingName: String? = target
    private var lastSaved: String? = null

    var field by mutableStateOf(TextFieldValue(""))
        private set

    /** False only while the memo to edit is still loading. */
    var ready by mutableStateOf(target == null)
        private set

    val placeholder: String
        get() = when {
            parent != null -> "Write a comment"
            target != null -> "Edit note"
            else -> "Write a note"
        }

    init {
        if (target != null) {
            viewModelScope.launch {
                val content = repository.memo(target)?.content.orEmpty()
                field = TextFieldValue(content, TextRange(content.length))
                lastSaved = content
                ready = true
            }
        }
    }

    fun onValueChange(value: TextFieldValue) {
        if (field == value) return
        field = value
        scheduleSave()
    }

    /** Speaks into the text: replaces the selection, or adds to the end of a word. */
    fun insertSpoken(spoken: String) {
        val text = spoken.trim()
        if (text.isEmpty()) return
        val current = field.text
        val start = field.selection.min.coerceIn(0, current.length)
        val end = field.selection.max.coerceIn(0, current.length)
        val before = current.substring(0, start)
        val after = current.substring(end)
        val leading = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trailing = if (after.isEmpty() || after.first().isWhitespace()) "" else " "
        val merged = before + leading + text + trailing + after
        val cursor = (before + leading + text).length
        field = TextFieldValue(merged, TextRange(cursor))
        scheduleSave()
    }

    /** Turns the line with the cursor into a task, or back into text. */
    fun toggleTaskAtCursor() {
        val (text, cursor) = MemoMarkdown.toggleTaskMarker(field.text, field.selection.end)
        field = TextFieldValue(text, TextRange(cursor))
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            persist()
        }
    }

    /** Saves without the delay. Runs in the app scope, which outlives this screen. */
    fun saveNow() {
        saveJob?.cancel()
        container.appScope.launch { persist() }
    }

    /** Saves, then reports the memo's name, or null when nothing was written. */
    fun finish(onFinished: (String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            persist()
            onFinished(workingName)
        }
    }

    /** Marks a note written here as saved, so the next screen confirms it. */
    fun confirmSaved() {
        workingName?.let { name -> container.flash.value = Flash("Saved", memoName = name) }
    }

    private suspend fun persist() = saveMutex.withLock {
        val content = field.text.trim()
        val name = workingName
        if (name == null) {
            if (content.isEmpty()) return@withLock
            workingName = repository.createMemo(content, parent)
            lastSaved = content
        } else if (content.isEmpty()) {
            if (target == null) {
                repository.deleteMemo(name)
                workingName = null
                lastSaved = null
            }
        } else if (content != lastSaved) {
            repository.updateContent(name, content)
            lastSaved = content
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 700L
    }
}

/**
 * Writes a note, edits one, or comments on one. The check button at the bottom edge closes it.
 * With [startWithVoice], the watch listens first and one spoken sentence makes the note.
 */
@Composable
fun EditorScreen(
    navigator: AppNavigator,
    target: String?,
    parent: String?,
    startWithVoice: Boolean,
    viewModel: EditorViewModel = containerViewModel(key = "$target|$parent") { EditorViewModel(it, target, parent) },
) {
    val listState = rememberTransformingLazyColumnState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val voice = remember { VoiceInput.isAvailable(context) }
    var listening by remember { mutableStateOf(startWithVoice && voice) }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        val spoken = VoiceInput.textFrom(result.data)
        if (spoken == null) {
            // Backed out of speaking: leave the editor instead of showing an empty note.
            navigator.back()
        } else {
            viewModel.insertSpoken(spoken)
            viewModel.finish { name ->
                viewModel.confirmSaved()
                if (name != null) navigator.replaceWithMemo(name) else navigator.back()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (listening) {
            voiceLauncher.launch(VoiceInput.intent())
        } else {
            runCatching { focus.requestFocus() }
        }
    }

    // Whatever the text is when the screen goes away is what gets saved.
    DisposableEffect(Unit) {
        onDispose { viewModel.saveNow() }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    keyboard?.hide()
                    viewModel.finish { navigator.back() }
                },
                buttonSize = EdgeButtonSize.ExtraSmall,
                modifier = Modifier.semantics { contentDescription = "Done" },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        },
    ) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Extra room at the top keeps the field clear of the curved edge when it is focused.
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            item(key = "field") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    WatchTextField(
                        value = viewModel.field,
                        onValueChange = viewModel::onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        placeholder = viewModel.placeholder,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        if (voice) {
                            IconButton(
                                onClick = { voiceLauncher.launch(VoiceInput.intent()) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(),
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Dictate", modifier = Modifier.size(24.dp))
                            }
                        }
                        IconButton(
                            onClick = viewModel::toggleTaskAtCursor,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(),
                        ) {
                            Icon(Icons.Outlined.CheckBox, contentDescription = "Turn this line into a task", modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
