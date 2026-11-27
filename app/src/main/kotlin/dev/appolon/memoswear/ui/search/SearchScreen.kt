package dev.appolon.memoswear.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.components.BannerCard
import dev.appolon.memoswear.ui.components.EmptyState
import dev.appolon.memoswear.ui.components.NoteCard
import dev.appolon.memoswear.ui.components.NoteStack
import dev.appolon.memoswear.ui.components.SectionLabel
import dev.appolon.memoswear.ui.components.WatchTextField
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Finds notes by words or by tag. With no words it offers the tags in use. */
class SearchViewModel(private val container: AppContainer, initialTag: String?) : ViewModel() {
    private val repository = container.repository

    var query by mutableStateOf("")
        private set

    /** The tag the screen was opened with, without its hash. */
    var tag by mutableStateOf(initialTag?.removePrefix("#")?.ifBlank { null })
        private set

    var results by mutableStateOf<MemoRepository.SearchResult?>(null)
        private set

    var searching by mutableStateOf(false)
        private set

    var tags by mutableStateOf<List<MemoRepository.TagCount>>(emptyList())
        private set

    private var searchJob: Job? = null

    init {
        loadTags()
        if (tag != null) search()
    }

    fun onQueryChange(value: String) {
        query = value
        searchJob?.cancel()
        if (value.isBlank()) {
            results = null
            searching = false
            if (tag != null) search()
        } else {
            searchJob = viewModelScope.launch {
                delay(SEARCH_DELAY_MS)
                search()
            }
        }
    }

    /** Searches now, for the keyboard's Done key. */
    fun submit() {
        searchJob?.cancel()
        search()
    }

    /** Shows notes with [value] as their tag. An empty value drops the tag and shows all tags again. */
    fun searchTag(value: String) {
        searchJob?.cancel()
        tag = value.removePrefix("#").ifBlank { null }
        query = ""
        results = null
        if (tag != null) search()
    }

    fun search() {
        viewModelScope.launch {
            searching = true
            results = repository.search(query, tag)
            searching = false
        }
    }

    private fun loadTags() {
        viewModelScope.launch { tags = repository.tags() }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 450L
    }
}

@Composable
fun SearchScreen(navigator: AppNavigator, tag: String?, viewModel: SearchViewModel = containerViewModel { SearchViewModel(it, tag) }) {
    val focus = remember { FocusRequester() }
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(Unit) { if (viewModel.tag == null) runCatching { focus.requestFocus() } }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NoteStack.spacing),
            contentPadding = PaddingValues(
                // Extra room at the top keeps the field clear of the curved edge when it is focused.
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item(key = "field") {
                WatchTextField(
                    value = viewModel.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation)
                        .focusRequester(focus),
                    placeholder = "Search notes",
                    singleLine = true,
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.None,
                    onDone = { viewModel.submit() },
                )
            }

            val currentTag = viewModel.tag
            val currentResults = viewModel.results
            when {
                currentResults == null && currentTag == null -> {
                    item(key = "tags-label") { SectionLabel("Tags", modifier = Modifier.padding(bottom = NoteStack.separation)) }
                    if (viewModel.tags.isEmpty()) {
                        item(key = "tags-empty") {
                            Text(
                                text = "Tags from your notes appear here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = NoteStack.separation),
                            )
                        }
                    } else {
                        items(viewModel.tags.size, key = { viewModel.tags[it].tag }) { index ->
                            val entry = viewModel.tags[index]
                            OutlinedButton(
                                onClick = { navigator.search(entry.tag) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                                icon = { Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                secondaryLabel = { Text(if (entry.count == 1) "1 note" else "${entry.count} notes") },
                                label = { Text("#${entry.tag}") },
                            )
                        }
                    }
                }

                viewModel.searching -> item(key = "searching") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                else -> {
                    if (currentTag != null) {
                        item(key = "tag") {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = NoteStack.separation),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#$currentTag",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.searchTag("") }) { Text("Clear") }
                            }
                        }
                    }
                    if (currentResults?.offline == true) {
                        item(key = "offline") {
                            BannerCard(
                                text = "Offline. Searching saved notes.",
                                icon = Icons.Default.CloudOff,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = NoteStack.separation),
                            )
                        }
                    }
                    if (currentResults != null && currentResults.entries.isEmpty()) {
                        item(key = "none") { EmptyState(title = "No matching notes", subtitle = "Try other words.") }
                    } else if (currentResults != null) {
                        items(currentResults.entries.size, key = { currentResults.entries[it].memo.name }) { index ->
                            NoteCard(
                                entry = currentResults.entries[index],
                                onClick = { navigator.openMemo(currentResults.entries[index].memo.name) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                                shape = NoteStack.shape(index = index, count = currentResults.entries.size),
                            )
                        }
                    }
                }
            }
        }
    }
}
