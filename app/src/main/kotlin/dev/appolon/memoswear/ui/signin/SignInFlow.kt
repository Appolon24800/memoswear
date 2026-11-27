package dev.appolon.memoswear.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.core.UserMessages
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.data.api.OAuth
import dev.appolon.memoswear.data.model.IdentityProvider
import dev.appolon.memoswear.ui.LocalAppContainer
import dev.appolon.memoswear.ui.components.BannerCard
import dev.appolon.memoswear.ui.components.WatchTextField
import dev.appolon.memoswear.ui.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sign-in, one step at a time: the server, then how to prove who you are. */
class SignInViewModel(private val container: AppContainer) : ViewModel() {
    enum class Step { Server, Methods, Password, Token, Sso }

    /**
     * Whether this watch can show the identity provider's sign-in page. Some watches ship
     * without a WebView; for them, an access token is the only way in besides a password.
     */
    val canShowSignInPage: Boolean = WebViewAvailability.hasProvider(container.context)

    data class State(
        val step: Step = Step.Server,
        val server: String = "",
        val username: String = "",
        val password: String = "",
        val token: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val instance: SessionStore.Instance = SessionStore.Instance(),
        val providers: List<IdentityProvider> = emptyList(),
        /** The provider being signed in with, and the secrets that finish it. */
        val sso: Pair<IdentityProvider, OAuth.Challenge>? = null,
    )

    private val session = container.session
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(server = session.lastServerUrl()) }
        }
    }

    fun edit(change: (State) -> State) = _state.update(change)

    /** Checks the address and reads what the instance offers. */
    fun submitServer() {
        val address = _state.value.server
        run {
            val instance = session.probe(address)
            val providers = session.identityProviders()
            _state.value = State(step = Step.Methods, server = address, instance = instance, providers = providers)
        }
    }

    fun showPassword() = edit { it.copy(step = Step.Password, error = null) }
    fun showToken() = edit { it.copy(step = Step.Token, error = null) }

    fun submitPassword() {
        val (username, password) = _state.value.let { it.username to it.password }
        run { session.signInWithPassword(username, password) }
    }

    fun submitToken() {
        val token = _state.value.token
        run { session.signInWithToken(token) }
    }

    /** Opens the provider's sign-in page, or explains why it cannot and offers the other way in. */
    fun startSso(provider: IdentityProvider) {
        if (!canShowSignInPage) {
            edit {
                it.copy(
                    step = Step.Token,
                    error = "This watch cannot open the ${provider.title.ifBlank { "sign-in" }} page. " +
                        "Use an access token instead.",
                )
            }
            return
        }
        run {
            val challenge = session.beginSso(provider)
            edit { it.copy(step = Step.Sso, sso = provider to challenge, error = null, busy = false) }
        }
    }

    /**
     * Called for every page the sign-in view wants to open. Returns true when the page is the
     * instance's callback, which is never loaded: its code is exchanged for a session instead.
     */
    fun onSsoNavigation(url: String): Boolean {
        val (provider, challenge) = _state.value.sso ?: return false
        val callback = OAuth.parseCallback(url, challenge.redirectUri) ?: return false
        when (callback) {
            is OAuth.Callback.Denied -> edit { it.copy(step = Step.Methods, sso = null, error = callback.description) }

            is OAuth.Callback.Code -> {
                if (callback.state != challenge.state) {
                    edit { it.copy(step = Step.Methods, sso = null, error = "Sign-in could not be verified. Try again.") }
                } else {
                    run { session.completeSso(provider, challenge, callback.code) }
                }
            }
        }
        return true
    }

    /** One step back. Returns false on the first step, where the system should handle back. */
    fun back(): Boolean {
        val step = _state.value.step
        if (step == Step.Server) return false
        edit { it.copy(step = if (step == Step.Sso || step == Step.Password || step == Step.Token) Step.Methods else Step.Server, sso = null, error = null, busy = false) }
        return true
    }

    /** Runs one sign-in action, showing progress and turning failures into a line of text. */
    private fun run(action: suspend () -> Unit) {
        if (_state.value.busy) return
        edit { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                // On success the session state changes and the app moves on by itself.
                edit { it.copy(busy = false) }
            } catch (e: Exception) {
                edit { it.copy(busy = false, error = UserMessages.forThrowable(e)) }
            }
        }
    }
}

@Composable
fun SignInFlow(viewModel: SignInViewModel = containerViewModel { SignInViewModel(it) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.step != SignInViewModel.Step.Server) { viewModel.back() }

    when (state.step) {
        SignInViewModel.Step.Server -> ServerStep(viewModel, state)
        SignInViewModel.Step.Methods -> MethodsStep(viewModel, state)
        SignInViewModel.Step.Password -> PasswordStep(viewModel, state)
        SignInViewModel.Step.Token -> TokenStep(viewModel, state)
        SignInViewModel.Step.Sso -> state.sso?.let { (_, challenge) ->
            SsoWebView(
                challenge = challenge,
                onNavigation = viewModel::onSsoNavigation,
                onBack = { viewModel.back() },
                busy = state.busy,
            )
        }
    }
}

/** A scrolling column with room for the time text, and optionally a button on the bottom edge. */
@Composable
private fun SignInScaffold(
    edgeButton: (@Composable () -> Unit)?,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val list: @Composable (PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Extra room at the top keeps the first element clear of the curved edge.
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding(),
            ),
            content = content,
        )
    }
    if (edgeButton == null) {
        ScreenScaffold(scrollState = listState, content = { padding -> list(padding) })
    } else {
        ScreenScaffold(scrollState = listState, edgeButton = { edgeButton() }, content = { padding -> list(padding) })
    }
}

/** The bottom button, or a spinner while the request is in flight. */
/** The button at the bottom edge, or a spinner while the request is in flight. */
@Composable
private fun SignInEdgeButton(label: String, busy: Boolean, onClick: () -> Unit) {
    if (busy) {
        CircularProgressIndicator()
    } else {
        EdgeButton(onClick = onClick, buttonSize = EdgeButtonSize.Medium) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun ServerStep(viewModel: SignInViewModel, state: SignInViewModel.State) {
    SignInScaffold(edgeButton = { SignInEdgeButton("Continue", state.busy, viewModel::submitServer) }) {
        item(key = "title") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MemosWear", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Sign in to your notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "server") {
            WatchTextField(
                value = state.server,
                onValueChange = { value -> viewModel.edit { it.copy(server = value) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                placeholder = "Server address",
                singleLine = true,
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                onDone = { viewModel.submitServer() },
            )
        }
        state.error?.let { message ->
            item(key = "error") {
                BannerCard(text = message, icon = Icons.Outlined.ErrorOutline, error = true, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun MethodsStep(viewModel: SignInViewModel, state: SignInViewModel.State) {
    SignInScaffold(edgeButton = null) {
        item(key = "title") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.instance.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.instance.url.ifBlank { state.server }.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        state.error?.let { message ->
            item(key = "error") {
                BannerCard(text = message, icon = Icons.Outlined.ErrorOutline, error = true, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        for (provider in state.providers.filter { it.isOAuth2 }) {
            item(key = "sso-${provider.name}") {
                val name = provider.title.ifBlank { "sign-in provider" }
                if (viewModel.canShowSignInPage) {
                    Button(
                        onClick = { viewModel.startSso(provider) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        icon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        label = { Text("Continue with $name", maxLines = 2) },
                    )
                } else {
                    // Keep the way in visible, and say where it leads: a token, typed here.
                    OutlinedButton(
                        onClick = { viewModel.startSso(provider) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        icon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        secondaryLabel = { Text("Needs an access token on this watch") },
                        label = { Text("Sign in with $name", maxLines = 2) },
                    )
                }
            }
        }
        if (state.instance.passwordAuthAllowed) {
            item(key = "password") {
                FilledTonalButton(
                    onClick = viewModel::showPassword,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    icon = { Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = { Text("Use password") },
                )
            }
        }
        item(key = "token") {
            OutlinedButton(
                onClick = viewModel::showToken,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                icon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(24.dp)) },
                secondaryLabel = { Text("Made in Memos on another device") },
                label = { Text("Use access token") },
            )
        }
        item(key = "change") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { viewModel.back() }) { Text("Change server") }
            }
        }
    }
}

@Composable
private fun PasswordStep(viewModel: SignInViewModel, state: SignInViewModel.State) {
    SignInScaffold(edgeButton = { SignInEdgeButton("Sign in", state.busy, viewModel::submitPassword) }) {
        item(key = "title") {
            Text(
                text = "Sign in to ${state.instance.title}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "username") {
            WatchTextField(
                value = state.username,
                onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                placeholder = "Username",
                singleLine = true,
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
            )
        }
        item(key = "password") {
            WatchTextField(
                value = state.password,
                onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                placeholder = "Password",
                singleLine = true,
                password = true,
                onDone = { viewModel.submitPassword() },
            )
        }
        state.error?.let { message ->
            item(key = "error") {
                BannerCard(text = message, icon = Icons.Outlined.ErrorOutline, error = true, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun TokenStep(viewModel: SignInViewModel, state: SignInViewModel.State) {
    SignInScaffold(edgeButton = { SignInEdgeButton("Sign in", state.busy, viewModel::submitToken) }) {
        item(key = "title") {
            Text(
                text = "Paste an access token",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "help") {
            Text(
                text = "In Memos on another device: Settings, Access tokens, then create one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item(key = "token") {
            WatchTextField(
                value = state.token,
                onValueChange = { value -> viewModel.edit { it.copy(token = value) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                placeholder = "memos_pat_…",
                singleLine = true,
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                onDone = { viewModel.submitToken() },
            )
        }
        state.error?.let { message ->
            item(key = "error") {
                BannerCard(text = message, icon = Icons.Outlined.ErrorOutline, error = true, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun SsoWebView(
    challenge: OAuth.Challenge,
    onNavigation: (String) -> Boolean,
    onBack: () -> Unit,
    busy: Boolean,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Built once. The view model keeps watches without a WebView off this screen, but creating
    // one throws on such a watch, so that is caught here as well.
    val page = remember {
        try {
            signInPage(context, challenge.authorizeUrl, onNavigation, onLoaded = { loading = false }, onFailed = { failure = it })
        } catch (e: Exception) {
            failure = NO_WEB_VIEW
            null
        }
    }

    // The page needs the whole screen, so the time text steps aside while it is shown.
    DisposableEffect(Unit) {
        container.chromeHidden.value = true
        onDispose {
            container.chromeHidden.value = false
            page?.destroy()
        }
    }

    BackHandler(enabled = !busy) {
        if (page?.canGoBack() == true) page?.goBack() else onBack()
    }

    Box(Modifier.fillMaxSize()) {
        page?.let { signInPage -> AndroidView(factory = { signInPage }, modifier = Modifier.fillMaxSize()) }
        if (failure == null && (loading || busy)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        failure?.let { message ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text("Go back") }
            }
        }
    }
}

/** The identity provider's page, reporting what it does through the callbacks. */
@SuppressLint("SetJavaScriptEnabled")
private fun signInPage(
    context: Context,
    url: String,
    onNavigation: (String) -> Boolean,
    onLoaded: () -> Unit,
    onFailed: (String) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            onNavigation(request.url.toString())

        override fun onPageFinished(view: WebView, url: String) = onLoaded()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailed("The sign-in page did not load. Check the connection and try again.")
        }
    }
    loadUrl(url)
}

private const val NO_WEB_VIEW = "This watch cannot show the sign-in page. Go back and use an access token."
