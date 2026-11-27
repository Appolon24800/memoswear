package dev.appolon.memoswear.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.LaunchRequest
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.ui.archived.ArchivedScreen
import dev.appolon.memoswear.ui.detail.MemoDetailScreen
import dev.appolon.memoswear.ui.editor.EditorScreen
import dev.appolon.memoswear.ui.image.ImageViewerScreen
import dev.appolon.memoswear.ui.list.MemoListScreen
import dev.appolon.memoswear.ui.search.SearchScreen
import dev.appolon.memoswear.ui.settings.SettingsScreen
import dev.appolon.memoswear.ui.signin.SignInFlow
import dev.appolon.memoswear.ui.theme.MemosWearTheme

/** The app's long-lived objects, for screens and their view models. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("App container is missing") }

/** Builds a view model from the container the first time it is asked for. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Route patterns. Memo names contain a slash, so they are percent-encoded into the path. */
object Routes {
    const val LIST = "list"
    const val NEW = "new?voice={voice}"
    const val MEMO = "memo/{name}"
    const val EDIT = "edit/{name}"
    const val COMMENT = "comment/{name}"
    const val SEARCH = "search?tag={tag}"
    const val ARCHIVED = "archived"
    const val SETTINGS = "settings"
    const val IMAGE = "image"

    fun memo(name: String): String = "memo/${Uri.encode(name)}"
    fun edit(name: String): String = "edit/${Uri.encode(name)}"
    fun comment(parent: String): String = "comment/${Uri.encode(parent)}"
    fun newNote(voice: Boolean): String = "new?voice=$voice"
    fun search(tag: String?): String = if (tag.isNullOrBlank()) "search" else "search?tag=${Uri.encode(tag)}"
}

private val memoArgument = listOf(navArgument("name") { type = NavType.StringType })

/** Where a screen can take the person next. */
class AppNavigator(private val navController: NavHostController) {
    /** Opens a memo. Launching the same memo again from a tile does not stack a copy. */
    fun openMemo(name: String) = navController.navigate(Routes.memo(name)) {
        popUpTo(Routes.LIST)
        launchSingleTop = true
    }

    fun newNote(voice: Boolean) = navController.navigate(Routes.newNote(voice)) {
        popUpTo(Routes.LIST)
        launchSingleTop = true
    }

    fun edit(name: String) = navController.navigate(Routes.edit(name))
    fun comment(parent: String) = navController.navigate(Routes.comment(parent))
    fun search(tag: String? = null) = navController.navigate(Routes.search(tag))
    fun archived() = navController.navigate(Routes.ARCHIVED)
    fun settings() = navController.navigate(Routes.SETTINGS)
    fun image(url: String) {
        navController.currentBackStackEntry?.savedStateHandle?.set(IMAGE_URL_KEY, url)
        navController.navigate(Routes.IMAGE)
    }

    fun back() {
        navController.popBackStack()
    }

    /** Leaves the detail screen too, for after a note is deleted. */
    fun backToList() {
        navController.popBackStack(Routes.LIST, inclusive = false)
    }

    /** Closes whatever is on top and then opens the memo, for a note just created. */
    fun replaceWithMemo(name: String) {
        navController.popBackStack()
        openMemo(name)
    }

    companion object {
        const val IMAGE_URL_KEY = "image_url"
    }
}

@Composable
fun MemosWearRoot(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        MemosWearTheme {
            val session by container.session.state.collectAsStateWithLifecycle()
            val hideTime by container.chromeHidden.collectAsStateWithLifecycle()
            AppScaffold(
                modifier = Modifier.fillMaxSize(),
                timeText = { if (!hideTime) TimeText() },
            ) {
                when (val state = session) {
                    SessionStore.State.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    SessionStore.State.SignedOut -> SignInFlow()
                    is SessionStore.State.SignedIn -> MainNavHost()
                }
            }
        }
    }
}

@Composable
private fun MainNavHost() {
    val container = LocalAppContainer.current
    val navController = rememberSwipeDismissableNavController()
    val navigator = remember(navController) { AppNavigator(navController) }

    LaunchedEffect(navController) {
        container.launchRequest.collect { request ->
            if (request != null) {
                when (request) {
                    is LaunchRequest.OpenMemo -> navigator.openMemo(request.name)
                    is LaunchRequest.NewNote -> navigator.newNote(request.voice)
                }
                // Taken: a later visit to this host must not act on the same request again.
                container.launchRequest.value = null
            }
        }
    }

    SwipeDismissableNavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) { MemoListScreen(navigator) }
        composable(Routes.NEW, arguments = listOf(navArgument("voice") { type = NavType.BoolType; defaultValue = false })) { entry ->
            EditorScreen(
                navigator = navigator,
                target = null,
                parent = null,
                startWithVoice = entry.arguments?.getBoolean("voice") == true,
            )
        }

        composable(Routes.MEMO, arguments = memoArgument) { entry ->
            MemoDetailScreen(navigator, entry.arguments?.getString("name").orEmpty())
        }

        composable(Routes.EDIT, arguments = memoArgument) { entry ->
            EditorScreen(navigator, target = entry.arguments?.getString("name").orEmpty(), parent = null, startWithVoice = false)
        }

        composable(Routes.COMMENT, arguments = memoArgument) { entry ->
            EditorScreen(navigator, target = null, parent = entry.arguments?.getString("name").orEmpty(), startWithVoice = false)
        }

        composable(Routes.SEARCH, arguments = listOf(navArgument("tag") { type = NavType.StringType; defaultValue = "" })) { entry ->
            SearchScreen(navigator, entry.arguments?.getString("tag").orEmpty().ifBlank { null })
        }

        composable(Routes.ARCHIVED) { ArchivedScreen(navigator) }
        composable(Routes.SETTINGS) { SettingsScreen(navigator) }
        composable(Routes.IMAGE) {
            ImageViewerScreen(
                url = navController.previousBackStackEntry?.savedStateHandle?.get<String>(AppNavigator.IMAGE_URL_KEY).orEmpty(),
                onClose = { navigator.back() },
            )
        }
    }
}
