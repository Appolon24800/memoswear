package dev.appolon.memoswear.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.BuildConfig
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.data.model.User
import dev.appolon.memoswear.data.model.Visibility
import dev.appolon.memoswear.ui.AppNavigator
import dev.appolon.memoswear.ui.components.ActionButton
import dev.appolon.memoswear.ui.components.label
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val session = container.session
    private val repository = container.repository

    val instance: StateFlow<SessionStore.Instance> = session.instance
    val user: User? = session.user
    val serverUrl: String = session.displayUrl
    val appVersion: String = BuildConfig.VERSION_NAME
    val defaultVisibility: StateFlow<Visibility> = container.settings.defaultVisibility
        .stateIn(viewModelScope, SharingStarted.Eagerly, Visibility.PRIVATE)

    /** Changes made on the watch that the server has not taken. */
    val unsent: StateFlow<Int> = combine(repository.home, repository.archived) { home, archived ->
        home.pendingCount + home.failedCount + archived.pendingCount + archived.failedCount
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setDefaultVisibility(visibility: Visibility) {
        viewModelScope.launch { container.settings.setDefaultVisibility(visibility) }
    }

    fun retryUnsent() {
        viewModelScope.launch { repository.retryFailed() }
    }

    /** Signs out on the server and forgets the account, including any change not sent yet. */
    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            session.signOut()
            onSignedOut()
        }
    }
}

/** The account, a few choices, and where to leave. */
@Composable
fun SettingsScreen(navigator: AppNavigator, viewModel: SettingsViewModel = containerViewModel { SettingsViewModel(it) }) {
    val instance by viewModel.instance.collectAsStateWithLifecycle()
    val visibility by viewModel.defaultVisibility.collectAsStateWithLifecycle()
    val unsent by viewModel.unsent.collectAsStateWithLifecycle()
    var showVisibility by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item(key = "title") {
                ListHeader {
                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "account") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(viewModel.user?.displayNameOrUsername.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "@${viewModel.user?.username.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = viewModel.serverUrl.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item(key = "visibility") {
                ActionButton(
                    label = "New notes: ${visibility.label()}",
                    icon = Icons.Default.Visibility,
                    onClick = { showVisibility = true },
                )
            }
            item(key = "archived") {
                ActionButton(label = "Archived notes", icon = Icons.Default.Archive, onClick = { navigator.archived() })
            }
            if (unsent > 0) {
                item(key = "unsent") {
                    ActionButton(
                        label = if (unsent == 1) "1 change not sent. Try again" else "$unsent changes not sent. Try again",
                        onClick = { viewModel.retryUnsent() },
                    )
                }
            }
            item(key = "signout") {
                ActionButton(
                    label = "Sign out",
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = { confirmSignOut = true },
                )
            }
            item(key = "about") {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MemosWear ${viewModel.appVersion}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (instance.version.isBlank()) instance.title else "${instance.title} ${instance.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showVisibility) {
        AlertDialog(
            visible = true,
            onDismissRequest = { showVisibility = false },
            title = { Text("Who can read new notes") },
            confirmButton = {
                TextButton(onClick = { showVisibility = false }) { Text("Cancel") }
            },
            text = {
                Column {
                    for ((option, hint) in listOf(
                        Visibility.PRIVATE to "Only you",
                        Visibility.PROTECTED to "Other accounts on this server",
                        Visibility.PUBLIC to "Everyone",
                    )) {
                        RadioButton(
                            selected = visibility == option,
                            onSelect = {
                                viewModel.setDefaultVisibility(option)
                                showVisibility = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            secondaryLabel = { Text(hint) },
                            label = { Text(option.label()) },
                        )
                    }
                }
            },
        )
    }

    if (confirmSignOut) {
        val lossWarning = if (unsent > 0) "$unsent changes not sent yet are lost." else null
        AlertDialog(
            visible = true,
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = lossWarning?.let { warning -> { Text(warning) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut { navigator.backToList() }
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}
