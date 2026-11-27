package dev.appolon.memoswear.ui.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.Flash
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.data.model.User
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.capture.VoiceInput
import dev.appolon.memoswear.ui.components.BannerCard
import dev.appolon.memoswear.ui.components.EmptyState
import dev.appolon.memoswear.ui.components.FlashConfirmation
import dev.appolon.memoswear.ui.components.NoteCard
import dev.appolon.memoswear.ui.components.NoteStack
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MemoListViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository

    val state: StateFlow<MemoRepository.ListState> = repository.home
    val instance: StateFlow<SessionStore.Instance> = container.session.instance
    val user: User? = container.session.user
    val voiceAvailable: Boolean = VoiceInput.isAvailable(container.context)

    /** Reloads the list, and sends anything written while there was no connection. Both are
     *  cheap when there is nothing to do. */
    fun resume() {
        viewModelScope.launch {
            repository.resyncOnResume()
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch { repository.refresh(force) }
    }

    fun loadMore() {
        viewModelScope.launch { repository.loadMore() }
    }

    fun discardFailed() {
        viewModelScope.launch { repository.discardFailed() }
    }

    fun retryFailed() {
        viewModelScope.launch { repository.retryFailed() }
    }

    /** Saves spoken words as a new note. [onSaved] gets its name, or nothing when the text was empty. */
    fun saveSpokenNote(text: String?, onSaved: (String) -> Unit) {
        val spoken = text?.trim().orEmpty()
        if (spoken.isEmpty()) {
            onSaved("")
            return
        }
        viewModelScope.launch {
            val name = repository.createMemo(spoken)
            if (name.isNotEmpty()) {
                container.flash.value = Flash("Saved", memoName = name)
                onSaved(name)
            }
        }
    }
}

/** The newest notes, pins first, with a button at the bottom edge to add one. */
@Composable
fun MemoListScreen(navigator: AppNavigator, viewModel: MemoListViewModel = containerViewModel { MemoListViewModel(it) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val instance by viewModel.instance.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    var confirmDiscard by remember { mutableStateOf(false) }
    val voice = viewModel.voiceAvailable

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.saveSpokenNote(VoiceInput.textFrom(result.data)) { name ->
            if (name.isNotEmpty()) navigator.openMemo(name)
        }
    }

    // Coming back to the watch or from another screen is when the list may be out of date.
    LifecycleResumeEffect(Unit) {
        viewModel.resume()
        onPauseOrDispose { }
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItems.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - EARLY_LOAD_ITEMS
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) viewModel.loadMore()
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (voice) voiceLauncher.launch(VoiceInput.intent()) else navigator.newNote(voice = false)
                },
                buttonSize = EdgeButtonSize.Small,
            ) {
                Icon(if (voice) Icons.Default.Mic else Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (voice) "Speak" else "Note")
            }
        },
    ) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NoteStack.spacing),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = NoteStack.separation),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(instance.title.ifBlank { "Notes" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = countLabel(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Four buttons of 48 dp fit the screen at this height only when close together.
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = { navigator.search() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(22.dp))
                        }
                        if (voice) {
                            IconButton(
                                onClick = { voiceLauncher.launch(VoiceInput.intent()) },
                                colors = IconButtonDefaults.filledIconButtonColors(),
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = "Speak a new note", modifier = Modifier.size(22.dp))
                            }
                        }
                        IconButton(
                            onClick = { navigator.newNote(voice = false) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "New note with keyboard", modifier = Modifier.size(22.dp))
                        }
                        IconButton(
                            onClick = { navigator.settings() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            state.error?.let { message ->
                item(key = "error") {
                    BannerCard(
                        text = message,
                        icon = Icons.Outlined.ErrorOutline,
                        error = true,
                        actionLabel = "Retry",
                        onAction = { viewModel.refresh(force = true) },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                    )
                }
            }
            if (state.offline) {
                item(key = "offline") {
                    BannerCard(
                        text = if (state.entries.isEmpty()) "Can't reach the server." else "Offline. Showing saved notes.",
                        icon = Icons.Default.CloudOff,
                        actionLabel = "Retry",
                        onAction = { viewModel.refresh(force = true) },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                    )
                }
            }
            if (state.pendingCount > 0) {
                item(key = "pending") {
                    BannerCard(
                        text = if (state.pendingCount == 1) "1 change waiting to send" else "${state.pendingCount} changes waiting to send",
                        icon = Icons.Default.Sync,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                    )
                }
            }
            if (state.failedCount > 0) {
                item(key = "failed") {
                    BannerCard(
                        text = if (state.failedCount == 1) "1 change could not be saved" else "${state.failedCount} changes could not be saved",
                        icon = Icons.Outlined.ErrorOutline,
                        error = true,
                        actionLabel = if (state.failedCount == 1) "Discard it" else "Discard them",
                        onAction = { confirmDiscard = true },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                    )
                }
            }

            when {
                state.entries.isEmpty() && !state.loaded -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        if (!state.offline) CircularProgressIndicator()
                    }
                }

                state.entries.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = "No notes yet",
                        subtitle = if (voice) "Tap Speak to save a thought." else "Tap Note to write one.",
                        icon = Icons.AutoMirrored.Outlined.Notes,
                    )
                }

                else -> items(state.entries.size, key = { state.entries[it].memo.name }) { index ->
                    NoteCard(
                        entry = state.entries[index],
                        onClick = { navigator.openMemo(state.entries[index].memo.name) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        shape = NoteStack.shape(index = index, count = state.entries.size),
                    )
                }
            }

            if (state.entries.isNotEmpty()) {
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                            state.endReached && state.entries.size > 8 -> Text(
                                "End of notes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    FlashConfirmation(memoName = null)

    if (confirmDiscard) {
        AlertDialog(
            visible = true,
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discardFailed()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep") }
            },
        )
    }
}

private const val EARLY_LOAD_ITEMS = 4

private fun countLabel(state: MemoRepository.ListState): String {
    val count = state.entries.size
    // A plus means the server has more pages. That is only known once a page has been loaded.
    val more = if (state.loaded && !state.endReached) "+" else ""
    val noun = if (count == 1) "note" else "notes"
    return when {
        count == 0 && !state.loaded -> "Opening…"
        count == 0 -> "No notes"
        else -> "$count$more $noun"
    }
}
