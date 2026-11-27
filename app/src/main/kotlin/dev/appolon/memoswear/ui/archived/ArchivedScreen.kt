package dev.appolon.memoswear.ui.archived

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.components.EmptyState
import dev.appolon.memoswear.ui.components.NoteCard
import dev.appolon.memoswear.ui.components.NoteStack
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.launch

class ArchivedViewModel(private val container: AppContainer) : ViewModel() {
    val state = container.repository.archived

    fun reload() {
        viewModelScope.launch { container.repository.archivedMemos() }
    }
}

/** Notes the user put away. Opening one offers "Unarchive". */
@Composable
fun ArchivedScreen(navigator: AppNavigator, viewModel: ArchivedViewModel = containerViewModel { ArchivedViewModel(it) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        onPauseOrDispose { }
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NoteStack.spacing),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item(key = "title") {
                ListHeader(modifier = Modifier.padding(bottom = NoteStack.separation)) {
                    Text(
                        text = "Archived",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            when {
                state.entries.isEmpty() && !state.offline && !state.loaded -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.entries.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = if (state.offline) "Can't reach the server" else "No archived notes",
                        subtitle = if (state.offline) "Try again when you are back online." else "Archive a note to move it here.",
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
        }
    }
}
